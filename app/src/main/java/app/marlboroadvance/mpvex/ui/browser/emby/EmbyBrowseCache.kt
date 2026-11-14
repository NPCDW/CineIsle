package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import app.marlboroadvance.mpvex.utils.cache.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** 有界进程缓存；页面先读快照再静默请求。同身份同查询的网络请求只执行一次。 */
internal class BrowseResultCache(
  private val maxEntries: Int = 128,
  private val clock: () -> Long = System::currentTimeMillis,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private data class Entry(val value: Any, val writtenAt: Long, val revision: Long)
  private val entries = LruCache<String, Entry>(maxEntries)
  private val revisions = LruCache<String, Long>(64)
  private val queryRevisions = LruCache<String, Long>(maxEntries)
  private val pending = mutableMapOf<String, Deferred<Any>>()
  private var revisionCounter = 0L

  @Synchronized fun revision(owner: String): Long = revisions[owner] ?: revisionCounter
  @Synchronized fun invalidate(owner: String) {
    revisions[owner] = ++revisionCounter
    entries.keys().filter { it.startsWith("$owner:") }.forEach { entries[it] = Entry(Unit, 0, -1) }
  }

  @Synchronized private fun queryRevision(owner: String, query: String): Long =
    queryRevisions["$owner:$query"] ?: 0L

  @Synchronized fun invalidateQuery(owner: String, query: String) {
    val key = "$owner:$query"
    queryRevisions[key] = queryRevision(owner, query) + 1
    entries[key] = Entry(Unit, 0, -1)
  }

  @Suppress("UNCHECKED_CAST")
  @Synchronized fun <T : Any> peek(owner: String, query: String): T? {
    val entry = entries["$owner:$query"] ?: return null
    val age = clock() - entry.writtenAt
    return if (entry.revision == revision(owner) && age in 0 until RETAIN_MS) entry.value as T else null
  }

  @Synchronized fun <T : Any> put(owner: String, query: String, value: T, expectedRevision: Long = revision(owner)) {
    if (expectedRevision == revision(owner)) entries["$owner:$query"] = Entry(value, clock(), expectedRevision)
  }

  @Suppress("UNCHECKED_CAST")
  suspend fun <T : Any> load(owner: String, query: String, force: Boolean = false, loader: suspend () -> T): T {
    val deferred = synchronized(this) {
      val key = "$owner:$query"
      val revision = revision(owner)
      val queryRevision = queryRevision(owner, query)
      val cached = entries[key]
      if (!force && cached != null && cached.revision == revision && clock() - cached.writtenAt in 0 until COALESCE_MS) {
        return cached.value as T
      }
      val flightKey = "$key:$revision:$queryRevision"
      pending[flightKey] ?: scope.async {
        val value = loader()
        synchronized(this@BrowseResultCache) {
          if (revision == revision(owner) && queryRevision == queryRevision(owner, query)) {
            entries[key] = Entry(value, clock(), revision)
          }
        }
        value as Any
      }.also { request ->
        pending[flightKey] = request
        request.invokeOnCompletion { synchronized(this) { if (pending[flightKey] === request) pending.remove(flightKey) } }
      }
    }
    val value = deferred.await() as T
    return peek<T>(owner, query) ?: value
  }

  companion object {
    private const val RETAIN_MS = 10L * 60 * 1000
    private const val COALESCE_MS = 1500L
  }
}

internal object EmbyBrowseCache {
  val results = BrowseResultCache()
  fun owner(server: EmbyServer): String = EmbyImageLoader.cacheKey(server, "browse-results-v1")
  fun query(vararg values: Any?): String = values.joinToString("|") { value ->
    val text = value.toString()
    "${text.length}:$text"
  }
}
