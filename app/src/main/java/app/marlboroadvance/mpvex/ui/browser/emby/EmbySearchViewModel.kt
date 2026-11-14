package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.emby.EmbySearchGroup
import app.marlboroadvance.mpvex.domain.emby.EmbySearchService
import app.marlboroadvance.mpvex.domain.emby.EmbySearchStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Source diagnostics are not an empty configuration or an empty successful search. */
enum class EmbySearchSourceIssue { LOADING, FAILED, NO_ENABLED_SERVERS, NO_CURRENT_SERVER }

data class EmbySearchScreenState(
  val query: String = "",
  val filters: Set<EmbySearchFilter> = setOf(EmbySearchFilter.MOVIE, EmbySearchFilter.SERIES),
  val groups: List<EmbySearchGroup> = emptyList(),
  val sourceIssue: EmbySearchSourceIssue? = null,
  val pending: Boolean = false,
)

/** Query/row/source generations guard even a non-cooperative fake or a late callback. */
class EmbySearchViewModel(
  private val service: EmbySearchService,
  private val testScope: CoroutineScope? = null,
) : ViewModel() {
  private val scope: CoroutineScope get() = testScope ?: viewModelScope
  private val mutableState = MutableStateFlow(EmbySearchScreenState())
  val state = mutableState.asStateFlow()
  private var rows = emptyList<EmbyServer>()
  private var sourceEpoch: String? = null
  private var job: Job? = null
  private var generation = 0L
  private var interruptedSearch = false
  private val retryJobs = mutableMapOf<String, Job>()
  private val retryVersions = mutableMapOf<String, Long>()

  fun bindSources(servers: List<EmbyServer>, issue: EmbySearchSourceIssue?, epoch: String) {
    val enabled = servers.filterNot { it.disabled }
    // Token/session refresh is not a new query; source/credentials/identity/connection changes are.
    fun identity(server: EmbyServer) = server.copy(apiToken = "", userId = "", lastConnected = 0)
    val changed = sourceEpoch != epoch || rows.map(::identity) != enabled.map(::identity)
    if (!changed && mutableState.value.sourceIssue == issue) {
      val old = mutableState.value
      if (issue == null && old.query.isNotBlank() && !old.pending && (interruptedSearch || old.groups.any(::isBusy))) {
        start(0, preserve = true)
      }
      return
    }
    invalidate()
    val sourceChanged = sourceEpoch != epoch
    sourceEpoch = epoch
    rows = enabled
    val old = mutableState.value
    val groups = if (!changed && !sourceChanged) old.groups else enabled.map { EmbySearchGroup(it) }
    mutableState.value = old.copy(groups = groups, sourceIssue = issue, pending = false)
    if (issue == null && old.query.isNotBlank()) start(0, preserve = !changed)
  }

  fun setQuery(value: String) {
    val query = value.take(200)
    if (mutableState.value.query == query) return
    invalidate()
    mutableState.value = mutableState.value.copy(query = query,
      groups = rows.map { EmbySearchGroup(it) }, pending = query.isNotBlank())
    start(DEBOUNCE_MS, preserve = false)
  }

  fun setFilters(filters: Set<EmbySearchFilter>) {
    if (filters == mutableState.value.filters) return
    invalidate()
    mutableState.value = mutableState.value.copy(filters = filters,
      groups = rows.map { EmbySearchGroup(it) })
    start(0, preserve = false)
  }

  /** Keyboard/history actions bypass debounce and replace, rather than duplicate, the pending job. */
  fun submit() {
    invalidate()
    start(0, preserve = true)
  }

  /** Retry only this row; other rows and their in-flight work remain unchanged. */
  fun retryGroup(key: String) {
    val snapshot = mutableState.value
    val original = snapshot.groups.firstOrNull { it.key == key } ?: return
    if (snapshot.query.isBlank() || snapshot.sourceIssue != null || isBusy(original)) return
    retryJobs.remove(key)?.cancel()
    val expected = generation
    val version = (retryVersions[key] ?: 0L) + 1L
    retryVersions[key] = version
    retryJobs[key] = scope.launch {
      service.search(listOf(original.server), snapshot.query, EmbySearchFilter.toItemTypes(snapshot.filters)).collect { groups ->
        if (generation != expected || retryVersions[key] != version) return@collect
        val next = groups.single()
        val merged = retainFailedResults(next, original.items)
        val all = mutableState.value.groups.map { if (it.key == key) merged else it }
        mutableState.value = mutableState.value.copy(groups = all, pending = all.any(::isBusy))
      }
    }
  }

  /** Called when this screen leaves composition (even if its route ViewModelStore stays alive). */
  fun cancelSearch() {
    val wasPending = mutableState.value.pending
    invalidate()
    interruptedSearch = wasPending
    mutableState.value = mutableState.value.copy(pending = false)
  }

  private fun invalidate() {
    generation++
    interruptedSearch = false
    job?.cancel()
    job = null
    retryJobs.values.forEach { it.cancel() }
    retryJobs.clear()
    retryVersions.clear()
  }

  private fun isBusy(group: EmbySearchGroup) =
    group.status == EmbySearchStatus.QUEUED || group.status == EmbySearchStatus.LOADING

  private fun retainFailedResults(group: EmbySearchGroup, retained: List<app.marlboroadvance.mpvex.domain.emby.EmbyItem>): EmbySearchGroup =
    if (retained.isNotEmpty() && group.items.isEmpty() && (isBusy(group) || group.failures.isNotEmpty())) {
      group.copy(items = retained,
        status = if (group.failures.isNotEmpty()) EmbySearchStatus.PARTIAL else group.status)
    } else group

  private fun start(wait: Long, preserve: Boolean) {
    val snapshot = mutableState.value
    if (snapshot.query.isBlank() || snapshot.sourceIssue != null || rows.isEmpty()) {
      mutableState.value = snapshot.copy(pending = false)
      return
    }
    interruptedSearch = false
    val expected = ++generation
    val targets = rows.toList()
    val previous = if (preserve) snapshot.groups.associateBy { it.key } else emptyMap()
    mutableState.value = snapshot.copy(pending = true)
    job = scope.launch {
      if (wait > 0) delay(wait)
      service.search(targets, snapshot.query, EmbySearchFilter.toItemTypes(snapshot.filters)).collect { groups ->
        if (generation != expected) return@collect
        val current = mutableState.value.groups.associateBy { it.key }
        val merged = groups.map { group ->
          if (group.key in retryVersions) current[group.key] ?: group
          else retainFailedResults(group, previous[group.key]?.items.orEmpty())
        }
        mutableState.value = mutableState.value.copy(groups = merged, pending = merged.any(::isBusy))
      }
    }
  }

  companion object { const val DEBOUNCE_MS = 400L }
}
