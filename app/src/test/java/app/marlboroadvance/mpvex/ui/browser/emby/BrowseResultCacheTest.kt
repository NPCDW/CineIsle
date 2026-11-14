package app.marlboroadvance.mpvex.ui.browser.emby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BrowseResultCacheTest {
  @Test fun retainsEmptyResultsAndSeparatesQueriesAndOwners() = runTest {
    var now = 1000L
    val cache = BrowseResultCache(clock = { now })
    cache.put("user-a", "items:one", emptyList<String>())
    assertEquals(emptyList<String>(), cache.peek<List<String>>("user-a", "items:one"))
    assertNull(cache.peek<List<String>>("user-b", "items:one"))
    assertNull(cache.peek<List<String>>("user-a", "items:two"))
    now += 10 * 60 * 1000L
    assertNull(cache.peek<List<String>>("user-a", "items:one"))
  }

  @Test fun simultaneousReadsCoalesceAndFailureKeepsPreviousSnapshot() = runTest {
    val cache = BrowseResultCache(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
    val gate = CompletableDeferred<String>()
    val started = CompletableDeferred<Unit>()
    var calls = 0
    val first = async { cache.load("owner", "query", force = true) { calls++; started.complete(Unit); gate.await() } }
    started.await()
    val second = async { cache.load("owner", "query", force = true) { calls++; "wrong" } }
    kotlinx.coroutines.yield()
    gate.complete("loaded")
    assertEquals("loaded", first.await())
    assertEquals("loaded", second.await())
    assertEquals(1, calls)
    try { cache.load<String>("owner", "query", force = true) { error("offline") }; fail("must fail") }
    catch (_: IllegalStateException) { }
    assertEquals("loaded", cache.peek<String>("owner", "query"))
  }

  @Test fun queryInvalidationPreventsLateRefreshFromOverwritingNewValue() = runTest {
    val cache = BrowseResultCache(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
    cache.put("owner", "detail", "old")
    val gate = CompletableDeferred<String>()
    val started = CompletableDeferred<Unit>()
    val pending = async {
      cache.load("owner", "detail", force = true) {
        started.complete(Unit)
        gate.await()
      }
    }
    started.await()
    cache.invalidateQuery("owner", "detail")
    cache.put("owner", "detail", "confirmed")
    gate.complete("stale")

    assertEquals("confirmed", pending.await())
    assertEquals("confirmed", cache.peek<String>("owner", "detail"))
  }

  @Test fun invalidationPreventsLateResponseAndOldPageSnapshotResurrection() = runTest {
    val cache = BrowseResultCache(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
    val revision = cache.revision("owner")
    val gate = CompletableDeferred<String>()
    val started = CompletableDeferred<Unit>()
    val pending = async { cache.load("owner", "query", force = true) { started.complete(Unit); gate.await() } }
    started.await()
    cache.invalidate("owner")
    cache.put("owner", "page", "old", revision)
    gate.complete("old")
    pending.await()
    assertNull(cache.peek<String>("owner", "query"))
    assertNull(cache.peek<String>("owner", "page"))
    cache.put("owner", "query", "new")
    assertEquals("new", cache.peek<String>("owner", "query"))
  }
}
