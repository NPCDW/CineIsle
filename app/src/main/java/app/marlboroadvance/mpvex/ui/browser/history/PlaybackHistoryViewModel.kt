package app.marlboroadvance.mpvex.ui.browser.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.database.repository.PostgresPlaybackHistoryRepository
import app.marlboroadvance.mpvex.domain.postgres.POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryFilters
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkResult
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryPage
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryRow
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** No credentials, navigation, Emby queries, saved-state records, or startup network work. */
class PlaybackHistoryViewModel(private val repository: PostgresPlaybackHistoryRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(PlaybackHistoryUiState())
  val state = mutableState.asStateFlow()
  private var generation = 0L
  private var readGeneration = 0L
  private var source: PostgresSourceToken? = null
  private var readJob: Job? = null
  private var markJob: Job? = null
  private data class UnresolvedMark(val row: PostgresPlaybackHistoryRow, val failure: PlaybackHistoryMarkFailure)
  private var unresolved: UnresolvedMark? = null

  init {
    viewModelScope.launch {
      repository.sourceChanges.collect {
        source?.let { token -> if (!repository.isCurrent(token)) invalidateSource() }
      }
    }
  }

  private fun invalidateSource() {
    generation++
    readGeneration++
    readJob?.cancel()
    markJob?.cancel()
    unresolved = null
    source = null
    // UUID filters from another database must not leak into the next source.
    mutableState.value = PlaybackHistoryUiState(error = PostgresFailure.SOURCE_CHANGED)
  }

  fun setFilters(filters: PostgresPlaybackHistoryFilters) {
    if (isMarkBusy()) return
    if (filters == state.value.filters) return
    mutableState.value = state.value.copy(filters = filters, pageNumber = 1, rows = emptyList(), total = 0L, error = null)
    refresh()
  }

  fun goToPage(pageNumber: Int) {
    if (isMarkBusy() || pageNumber < 1 || pageNumber == state.value.pageNumber) return
    val maxPage = ((state.value.total - 1L).coerceAtLeast(0L) / POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE) + 1L
    if (pageNumber.toLong() > maxPage) return
    mutableState.value = state.value.copy(pageNumber = pageNumber, rows = emptyList(), error = null)
    refresh()
  }

  fun refresh() {
    if (isMarkBusy()) return
    if (unresolved != null) { reconcileMark(); return }
    loadPage()
  }

  private fun loadPage() {
    readJob?.cancel()
    val request = ++readGeneration
    val epoch = generation
    val filters = state.value.filters
    val pageNumber = state.value.pageNumber
    mutableState.value = state.value.copy(loading = true, error = null)
    readJob = viewModelScope.launch {
      var expected: PostgresSourceToken? = source
      try {
        if (expected != null && !repository.isCurrent(expected)) { invalidateSource(); return@launch }
        val token = expected ?: repository.currentSource()
        expected = token
        source = token
        val page = repository.loadPage(filters, pageNumber, token)
        if (epoch == generation && request == readGeneration && repository.isCurrent(token)) publish(page)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (epoch == generation && request == readGeneration) {
          if (expected != null && !repository.isCurrent(expected)) invalidateSource()
          else mutableState.value = state.value.copy(loading = false, error = reason(error))
        }
      } finally {
        if (epoch == generation && request == readGeneration) mutableState.value = state.value.copy(loading = false)
      }
    }
  }

  fun cycleMark(row: PostgresPlaybackHistoryRow) {
    if (state.value.markState != PlaybackHistoryMarkState.Idle || state.value.loading) return
    if (!repository.isCurrent(row.source) || source != row.source) { invalidateSource(); return }
    // A stale composable row may not overwrite the latest observed mark.
    val latest = state.value.rows.firstOrNull { it.key == row.key }
    if (latest == null || latest.pinned != row.pinned) {
      mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Failed(row.key, PlaybackHistoryMarkFailure.CONFLICT))
      return
    }
    val epoch = generation
    mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Pending(row.key))
    markJob = viewModelScope.launch {
      try {
        when (val result = repository.cycleMark(row)) {
          is PostgresPlaybackHistoryMarkResult.Committed -> {
            if (!valid(epoch, row.source)) return@launch
            // Keep the current page stable; merge only the two fields changed and returned by the CAS write.
            mutableState.value = state.value.copy(
              rows = state.value.rows.map {
                if (it.key == row.key) it.copy(pinned = result.pinned, updateTimeMs = result.updateTimeMs) else it
              },
              markState = PlaybackHistoryMarkState.Idle,
            )
          }
          PostgresPlaybackHistoryMarkResult.Conflict -> reconcileWithin(row, PlaybackHistoryMarkFailure.CONFLICT, epoch)
          PostgresPlaybackHistoryMarkResult.CommitUnknown -> reconcileWithin(row, PlaybackHistoryMarkFailure.COMMIT_UNKNOWN, epoch)
          PostgresPlaybackHistoryMarkResult.SourceChanged -> if (epoch == generation) invalidateSource()
          is PostgresPlaybackHistoryMarkResult.Failed -> if (valid(epoch, row.source)) {
            mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Failed(row.key, PlaybackHistoryMarkFailure.DATABASE, result.reason))
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (valid(epoch, row.source)) mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Failed(row.key, PlaybackHistoryMarkFailure.DATABASE, reason(error)))
      } finally {
        if (epoch == generation && state.value.markState is PlaybackHistoryMarkState.Pending) {
          mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Idle)
        }
      }
    }
  }

  /** Retry only the observation, never the UPDATE, including when the earlier commit is uncertain. */
  fun reconcileMark() {
    if (isMarkBusy()) return
    val pending = unresolved ?: return
    readJob?.cancel()
    readGeneration++
    val epoch = generation
    markJob = viewModelScope.launch { reconcileWithin(pending.row, pending.failure, epoch) }
  }

  private suspend fun reconcileWithin(row: PostgresPlaybackHistoryRow, failure: PlaybackHistoryMarkFailure, epoch: Long) {
    if (!valid(epoch, row.source)) return
    unresolved = UnresolvedMark(row, failure)
    mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Reconciling(row.key))
    try {
      val observed = repository.reconcile(row)
      if (!valid(epoch, row.source)) return
      // Observe only the fields that the mark cycle owns. Do not import desktop metadata, reorder, or refill the page.
      val rows = if (observed == null) state.value.rows.filterNot { it.key == row.key }
        else state.value.rows.map {
          if (it.key == row.key) it.copy(pinned = observed.pinned, updateTimeMs = observed.updateTimeMs) else it
        }
      unresolved = null
      mutableState.value = state.value.copy(rows = rows, loading = false, error = null,
        markState = PlaybackHistoryMarkState.Failed(row.key, failure, requiresReconcile = false, reconciled = true,
          observedPinned = observed?.pinned, observedRowExists = observed != null))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (!valid(epoch, row.source)) return
      mutableState.value = state.value.copy(loading = false, error = reason(error),
        markState = PlaybackHistoryMarkState.Failed(row.key, failure, reason(error), requiresReconcile = true))
    }
  }

  fun dismissMarkError() {
    val failed = state.value.markState as? PlaybackHistoryMarkState.Failed ?: return
    if (!failed.requiresReconcile && unresolved == null) mutableState.value = state.value.copy(markState = PlaybackHistoryMarkState.Idle)
  }

  private fun valid(epoch: Long, token: PostgresSourceToken): Boolean {
    if (epoch != generation) return false
    if (!repository.isCurrent(token)) { invalidateSource(); return false }
    return true
  }

  private fun publish(page: PostgresPlaybackHistoryPage) {
    source = page.source
    mutableState.value = state.value.copy(rows = page.rows, total = page.total, pageNumber = page.pageNumber, loading = false, error = null)
  }

  private fun isMarkBusy() = state.value.markState is PlaybackHistoryMarkState.Pending || state.value.markState is PlaybackHistoryMarkState.Reconciling
  private fun reason(error: Exception) = (error as? PostgresException)?.reason ?: PostgresFailure.UNKNOWN
}

data class PlaybackHistoryUiState(
  val rows: List<PostgresPlaybackHistoryRow> = emptyList(),
  val total: Long = 0L,
  val pageNumber: Int = 1,
  val filters: PostgresPlaybackHistoryFilters = PostgresPlaybackHistoryFilters(),
  val loading: Boolean = false,
  val error: PostgresFailure? = null,
  val markState: PlaybackHistoryMarkState = PlaybackHistoryMarkState.Idle,
) {
  val pageSize: Int get() = POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE
  val markingIds: Set<String> get() = when (val mark = markState) {
    is PlaybackHistoryMarkState.Pending -> setOf(mark.key)
    is PlaybackHistoryMarkState.Reconciling -> setOf(mark.key)
    else -> emptySet()
  }
}

enum class PlaybackHistoryMarkFailure { CONFLICT, COMMIT_UNKNOWN, SOURCE_CHANGED, DATABASE }

sealed interface PlaybackHistoryMarkState {
  data object Idle : PlaybackHistoryMarkState
  data class Pending(val key: String) : PlaybackHistoryMarkState
  data class Reconciling(val key: String) : PlaybackHistoryMarkState
  data class Failed(
    val key: String,
    val failure: PlaybackHistoryMarkFailure,
    val reason: PostgresFailure? = null,
    val requiresReconcile: Boolean = false,
    val reconciled: Boolean = false,
    val observedPinned: Int? = null,
    val observedRowExists: Boolean? = null,
  ) : PlaybackHistoryMarkState
}
