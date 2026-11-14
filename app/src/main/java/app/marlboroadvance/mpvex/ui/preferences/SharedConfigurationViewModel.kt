package app.marlboroadvance.mpvex.ui.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationDataSource
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Contains only a sanitized in-memory snapshot. No SavedStateHandle or persisted state is used. */
class SharedConfigurationViewModel(
  private val repository: SharedConfigurationDataSource,
) : ViewModel() {
  private val mutableState = MutableStateFlow(SharedConfigurationUiState())
  val state = mutableState.asStateFlow()

  private var source: PostgresSourceToken? = null
  private var generation = 0L
  private var loadJob: Job? = null

  init {
    viewModelScope.launch {
      repository.sourceChanges.collect { changed ->
        val current = source
        if (current != null && (changed.revision != current.revision ||
            (changed.sourceKey.isNotBlank() && changed.sourceKey != current.sourceKey))) {
          invalidateSource()
        }
      }
    }
  }

  fun refresh() = load(forceRefresh = true)

  fun loadIfNeeded() {
    if (state.value.snapshot == null && !state.value.loading) load(forceRefresh = false)
  }

  private fun load(forceRefresh: Boolean) {
    loadJob?.cancel()
    val request = ++generation
    val retained = state.value.snapshot
    mutableState.value = state.value.copy(loading = true, error = null)
    loadJob = viewModelScope.launch {
      var expected = source
      try {
        val token = expected ?: repository.currentSource()
        expected = token
        source = token
        val snapshot = repository.snapshot(token, forceRefresh)
        if (request != generation || snapshot.source != token) return@launch
        mutableState.value = SharedConfigurationUiState(snapshot = snapshot)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (request != generation) return@launch
        val changed = error is PostgresException && error.reason == PostgresFailure.SOURCE_CHANGED
        if (changed) {
          invalidateSource()
        } else {
          mutableState.value = SharedConfigurationUiState(
            snapshot = retained,
            loading = false,
            stale = retained != null,
            error = (error as? PostgresException)?.reason ?: PostgresFailure.UNKNOWN,
          )
        }
      } finally {
        if (request == generation && mutableState.value.loading) {
          mutableState.value = mutableState.value.copy(loading = false)
        }
      }
    }
  }

  private fun invalidateSource() {
    generation++
    loadJob?.cancel()
    source = null
    mutableState.value = SharedConfigurationUiState(error = PostgresFailure.SOURCE_CHANGED)
  }
}

data class SharedConfigurationUiState(
  val snapshot: SharedConfigurationSnapshot? = null,
  val loading: Boolean = false,
  val stale: Boolean = false,
  val error: PostgresFailure? = null,
)
