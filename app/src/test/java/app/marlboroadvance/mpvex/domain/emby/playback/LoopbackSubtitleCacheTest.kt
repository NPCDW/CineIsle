package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.postgres.SubtitleCacheConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LoopbackSubtitleCacheTest {
  @Test fun sha256UsesUrlOnlyAndAssRegardlessOfFormat() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LoopbackSubtitleCache.digest("abc"))
  }

  @Test fun oneDayCreationTimeNotMtimeAndMissingCreationKeepsFile() = runBlocking {
    val directory = Files.createTempDirectory("loemby-cache-test").toFile()
    val pool = LoopbackHttpPool()
    try {
      val day = LoopbackSubtitleCache.CLEAN_PERIOD_MILLIS
      val now = 10 * day
      val cache = LoopbackSubtitleCache(directory, pool) { file -> when {
        file.name == LoopbackSubtitleCache.digest("old") + ".ass" -> now - day - 1
        file.name == LoopbackSubtitleCache.digest("recent") + ".ass" -> now - day
        else -> null
      } }
      fun file(key: String) = cache.fileForUrl(key).apply { parentFile!!.mkdirs(); writeText(key); setLastModified(0) }
      val old = file("old"); val recent = file("recent"); val unknown = file("unknown")
      cache.clean(nowMillis = now)
      assertFalse(old.exists()); assertTrue(recent.exists()); assertTrue(unknown.exists())
      old.writeText("again")
      cache.clean(nowMillis = now + day - 1)
      assertTrue(old.exists()) // In-memory 24h gate, not repeated cleanup on each cache hit.
      cache.clean(nowMillis = now + day)
      assertFalse(old.exists()); assertFalse(recent.exists()); assertTrue(unknown.exists())
      cache.clear()
    } finally { pool.close(); directory.deleteRecursively() }
  }

  @Test fun configuredStoredDaysAndReadOnlySharedLastCleanGate() = runBlocking {
    val directory = Files.createTempDirectory("loemby-cache-config-test").toFile()
    val pool = LoopbackHttpPool()
    try {
      val day = LoopbackSubtitleCache.CLEAN_PERIOD_MILLIS
      val now = 20 * day
      val cache = LoopbackSubtitleCache(directory, pool) { now - 2 * day }
      val file = cache.fileForUrl("url").apply { parentFile!!.mkdirs(); writeText("data") }
      cache.clean(SubtitleCacheConfiguration(3L, null), nowMillis = now)
      assertTrue(file.exists())
      cache.clean(SubtitleCacheConfiguration(1L, (now + day) / 1000), nowMillis = now + day)
      assertTrue(file.exists())
      cache.clean(SubtitleCacheConfiguration(1L, null), nowMillis = now + 2 * day)
      assertFalse(file.exists())
    } finally { pool.close(); directory.deleteRecursively() }
  }
}
