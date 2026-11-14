package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.EmbyServerIdentityMap
import app.marlboroadvance.mpvex.domain.postgres.PostgresEmbyStore
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresExecutor
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceState
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EmbyServerLoadState {
  data object Loading : EmbyServerLoadState
  data class Ready(val servers: List<EmbyServer>) : EmbyServerLoadState
  data class Failed(val reason: PostgresFailure, val servers: List<EmbyServer>) : EmbyServerLoadState
}

/** PostgreSQL is the only source. A Room identity map holds no configuration or credentials. */
class EmbyServerRepository(
  private val executor: PostgresExecutor,
  private val identities: EmbyServerIdentityMap,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private data class Snapshot(val source: PostgresSourceToken, val servers: List<EmbyServer>)
  private val state = MutableStateFlow<EmbyServerLoadState>(EmbyServerLoadState.Loading)
  val loadState: StateFlow<EmbyServerLoadState> = state.asStateFlow()
  val sourceChanged: StateFlow<PostgresSourceState> get() = executor.sourceChanges
  private val refreshLock = Mutex()
  private val snapshotLock = Any()
  private var sourceGeneration = 0L
  @Volatile private var snapshot: Snapshot? = null
  @Volatile private var started = false
  @Volatile private var foreground = false
  private val pollingLock = Any()
  private var pollingJob: Job? = null

  init {
    // Subscribe only; no secure config load or network during App/Koin construction.
    scope.launch {
      sourceChanged.collect {
        synchronized(snapshotLock) {
          val cached = snapshot
          if (cached != null && !executor.isCurrent(cached.source)) {
            snapshot = null
            sourceGeneration++
            state.value = EmbyServerLoadState.Loading
          }
        }
        // No event-triggered reconnect: gate checks/saves config first, then business requests refresh.
      }
    }
  }

  val allServers: Flow<List<EmbyServer>> = flow {
    ensureStarted()
    loadState.map { current ->
      when (current) {
        EmbyServerLoadState.Loading -> null
        is EmbyServerLoadState.Ready -> current.servers
        is EmbyServerLoadState.Failed -> current.servers.takeIf { validSnapshot() != null }
      }
    }.filterNotNull().collect { emit(it) }
  }

  private suspend fun ensureStarted() {
    if (!started || state.value is EmbyServerLoadState.Loading) refresh()
  }

  private fun validSnapshot(): Snapshot? = snapshot?.takeIf { executor.isCurrent(it.source) }

  /** Pure memory lookup for image/player consumers; null never claims that the database was queried. */
  fun snapshotById(id: Long): EmbyServer? = validSnapshot()?.servers?.firstOrNull { it.id == id }
  fun snapshotServers(): List<EmbyServer> = validSnapshot()?.servers.orEmpty()

  suspend fun refresh(): Unit = refreshLock.withLock {
    started = true
    ensurePolling()
    val generation = synchronized(snapshotLock) {
      if (validSnapshot() == null) state.value = EmbyServerLoadState.Loading
      sourceGeneration
    }
    var source: PostgresSourceToken? = null
    try {
      val remote = executor.read { session ->
        source = session.source
        PostgresEmbyStore(session.connection, session::track).getAll(session.source.sourceKey)
      }
      val token = source ?: throw PostgresException(PostgresFailure.LOAD)
      val servers = remote.map { server -> server.copy(id = identities.localId(token.sourceKey, server.remoteId), sourceRevision = token.revision) }
      if (!executor.isCurrent(token)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      synchronized(snapshotLock) {
        // A read begun before a source change cannot publish into the new source.
        if (sourceGeneration == generation && executor.isCurrent(token)) {
          snapshot = Snapshot(token, servers)
          state.value = EmbyServerLoadState.Ready(servers)
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      synchronized(snapshotLock) {
        if (sourceGeneration == generation) {
          val retained = validSnapshot()
          if (retained == null) snapshot = null
          state.value = EmbyServerLoadState.Failed((error as? PostgresException)?.reason ?: PostgresFailure.UNKNOWN, retained?.servers.orEmpty())
        }
      }
    }
  }

  /** Actual refresh for management entry/manual reload; failures are not masqueraded as an empty list. */
  suspend fun getAll(): List<EmbyServer> {
    refresh()
    return when (val loaded = loadState.value) {
      is EmbyServerLoadState.Ready -> loaded.servers
      is EmbyServerLoadState.Failed -> throw PostgresException(loaded.reason)
      EmbyServerLoadState.Loading -> throw PostgresException(PostgresFailure.LOAD)
    }
  }

  suspend fun getById(id: Long): EmbyServer? {
    validSnapshot()?.let { cached ->
      cached.servers.firstOrNull { it.id == id }?.let { return it }
      // A stale success may serve known media requests, but cannot prove a missing record after failure.
      (loadState.value as? EmbyServerLoadState.Failed)?.let { throw PostgresException(it.reason) }
      return null
    }
    ensureStarted()
    validSnapshot()?.servers?.firstOrNull { it.id == id }?.let { return it }
    when (val loaded = loadState.value) {
      is EmbyServerLoadState.Failed -> throw PostgresException(loaded.reason)
      EmbyServerLoadState.Loading -> throw PostgresException(PostgresFailure.LOAD)
      is EmbyServerLoadState.Ready -> return loaded.servers.firstOrNull { it.id == id }
    }
  }

  /** Guard delayed session results without SQL or configuration writes. */
  fun isCurrent(server: EmbyServer): Boolean = server.sourceKey.isNotEmpty() && server.remoteId.isNotEmpty() &&
    executor.isCurrent(PostgresSourceToken(server.sourceKey, server.sourceRevision))

  /** Optional foreground hook. Fast lifecycle toggles cannot create overlapping polling loops. */
  fun setForeground(value: Boolean) {
    synchronized(pollingLock) {
      val changed = foreground != value
      foreground = value
      if (!value) {
        // Let an in-flight read finish; the polling loop is paused.
        return
      }
      ensurePolling()
      if (changed && started) scope.launch { refresh() }
    }
  }

  private fun ensurePolling() {
    synchronized(pollingLock) {
      if (!started || !foreground || pollingJob?.isActive == true) return
      pollingJob = scope.launch(start = CoroutineStart.LAZY) {
        while (true) {
          delay(60_000)
          if (foreground) refresh()
        }
      }.also { it.start() }
    }
  }
}
