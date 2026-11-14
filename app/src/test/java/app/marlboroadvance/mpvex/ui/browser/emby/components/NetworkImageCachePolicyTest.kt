package app.marlboroadvance.mpvex.ui.browser.emby.components

import org.junit.Assert.*
import org.junit.Test

class NetworkImageCachePolicyTest {
  @Test fun imageExpiresExactlyThirtyDaysAfterDownloadNotLastAccess() {
    val downloaded = 1000L
    assertTrue(NetworkImageCachePolicy.fresh(downloaded, downloaded))
    assertTrue(NetworkImageCachePolicy.fresh(downloaded, downloaded + NetworkImageCachePolicy.TTL_MS - 1))
    assertFalse(NetworkImageCachePolicy.fresh(downloaded, downloaded + NetworkImageCachePolicy.TTL_MS))
    assertFalse(NetworkImageCachePolicy.fresh(downloaded, downloaded - 1))
    assertEquals(30L * 24 * 60 * 60 * 1000, NetworkImageCachePolicy.TTL_MS)
  }

  @Test fun memoryEntriesDoNotBypassTtlAndReadsDoNotExtendOriginalWrite() {
    val key = "fixture-network-image"
    val downloaded = 10_000L
    NetworkImageCachePolicy.record(key, downloaded)
    assertTrue(NetworkImageCachePolicy.memoryFresh(key, downloaded + NetworkImageCachePolicy.TTL_MS - 1))
    assertEquals(downloaded, NetworkImageCachePolicy.recordedAt(key))
    assertFalse(NetworkImageCachePolicy.memoryFresh(key, downloaded + NetworkImageCachePolicy.TTL_MS))
    assertEquals(downloaded, NetworkImageCachePolicy.recordedAt(key))
    NetworkImageCachePolicy.forget(key)
    assertFalse(NetworkImageCachePolicy.memoryFresh(key, downloaded))
  }

  @Test fun metadataRejectsBoundaryRollbackAndCorruption() {
    val writtenAt = 50_000L
    assertEquals(writtenAt, NetworkImageCachePolicy.metadataTimestamp("$writtenAt\n", writtenAt))
    assertNull(NetworkImageCachePolicy.metadataTimestamp(writtenAt.toString(), writtenAt + NetworkImageCachePolicy.TTL_MS))
    assertNull(NetworkImageCachePolicy.metadataTimestamp(writtenAt.toString(), writtenAt - 1))
    assertNull(NetworkImageCachePolicy.metadataTimestamp("not-a-timestamp", writtenAt))
    assertNull(NetworkImageCachePolicy.metadataTimestamp("", writtenAt))
  }

  @Test fun clearInvalidatesAllMemoryTimestamps() {
    NetworkImageCachePolicy.record("a", 1L)
    NetworkImageCachePolicy.record("b", 2L)
    NetworkImageCachePolicy.clear()
    assertNull(NetworkImageCachePolicy.recordedAt("a"))
    assertNull(NetworkImageCachePolicy.recordedAt("b"))
  }
}
