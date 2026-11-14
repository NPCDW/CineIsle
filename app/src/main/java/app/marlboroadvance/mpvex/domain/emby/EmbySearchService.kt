package app.marlboroadvance.mpvex.domain.emby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Search-only bounded scheduler. Input order is display order, not response completion order. */
class EmbySearchService(private val backend: EmbySearchBackend) {
  private val serverGate = Semaphore(MAX_CONCURRENT_SERVERS)
  constructor(repository: EmbyRepository) : this(object : EmbySearchBackend {
    override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int) =
      repository.searchMediaPage(server, query, types, limit)
    override suspend fun persons(server: EmbyServer, query: String, limit: Int) =
      repository.searchPersonPage(server, query, limit)
  })

  fun search(servers: List<EmbyServer>, query: String, types: List<String>? = null): Flow<List<EmbySearchGroup>> = channelFlow {
    val rows = servers.filterNot { it.disabled }
    val term = query.trim().take(200)
    if (term.isEmpty()) {
      send(rows.map { EmbySearchGroup(it) })
      return@channelFlow
    }
    val groups = rows.map { EmbySearchGroup(it, EmbySearchStatus.QUEUED) }.toMutableList()
    val lock = Mutex()
    send(groups.toList())
    suspend fun update(index: Int, group: EmbySearchGroup) = lock.withLock {
      currentCoroutineContext().ensureActive()
      groups[index] = group
      send(groups.toList())
    }
    // Only four worker coroutines, not one coroutine/HTTP task per server in a large database.
    val workers = minOf(MAX_CONCURRENT_SERVERS, rows.size)
    repeat(workers) { worker ->
      launch {
        for (index in worker until rows.size step workers) {
          val server = rows[index]
          if (server.connectionError != null) {
            update(index, EmbySearchGroup(server, EmbySearchStatus.FAILED,
              failures = listOf(EmbySearchFailure.CONFIGURATION)))
            continue
          }
          serverGate.withPermit {
            update(index, EmbySearchGroup(server, EmbySearchStatus.LOADING))
            update(index, searchOne(server, term, types))
          }
        }
      }
    }
  }

  private suspend fun searchOne(server: EmbyServer, term: String, types: List<String>?): EmbySearchGroup {
    val items = mutableListOf<EmbyItem>()
    val failures = mutableListOf<EmbySearchFailure>()
    var truncated = false
    var successes = 0
    suspend fun page(limit: Int, request: suspend () -> EmbyItemsResult) {
      try {
        val result = withTimeout(REQUEST_TIMEOUT_MS) { request() }
        currentCoroutineContext().ensureActive()
        successes++
        val bounded = result.Items.take(limit)
        items += bounded
        truncated = truncated || result.TotalRecordCount > bounded.size || result.consumedCount >= limit
      } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // Parent cancellation is never converted into timeout.
        failures += EmbySearchFailure.TIMEOUT
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        failures += EmbySearchFailure.from(error)
      }
    }
    val mediaTypes = types?.filterNot { it.equals("Person", ignoreCase = true) } ?: DEFAULT_TYPES
    if (mediaTypes.isNotEmpty()) page(MEDIA_LIMIT) { backend.media(server, term, mediaTypes, MEDIA_LIMIT) }
    if (types == null || types.any { it.equals("Person", ignoreCase = true) }) {
      page(PERSON_LIMIT) { backend.persons(server, term, PERSON_LIMIT) }
    }
    val status = when {
      failures.isNotEmpty() && successes > 0 -> EmbySearchStatus.PARTIAL
      failures.isNotEmpty() -> EmbySearchStatus.FAILED
      items.isEmpty() -> EmbySearchStatus.EMPTY
      else -> EmbySearchStatus.SUCCESS
    }
    return EmbySearchGroup(server, status, items.filter { !it.Id.isNullOrBlank() }
      .distinctBy { "${it.Type}:${it.Id}" }, failures.distinct(), truncated)
  }

  companion object {
    const val MAX_CONCURRENT_SERVERS = 4
    const val MEDIA_LIMIT = 80
    const val PERSON_LIMIT = 40
    const val REQUEST_TIMEOUT_MS = 18_000L
    private val DEFAULT_TYPES = listOf("Movie", "Series", "Episode", "Video", "MusicVideo", "BoxSet")
  }
}
