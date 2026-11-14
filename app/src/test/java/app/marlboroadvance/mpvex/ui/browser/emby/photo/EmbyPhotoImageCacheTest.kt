package app.marlboroadvance.mpvex.ui.browser.emby.photo

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.NetworkImageCachePolicy
import coil3.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmbyPhotoImageCacheTest {
  private val server = EmbyServer(
    id = 1, name = "fixture", host = "emby.fixture.test", username = "user", password = "secret",
    sourceKey = "source", sourceRevision = 1, remoteId = "row", apiToken = "token",
  )

  @Test fun decodedMemoryIsReadableOnlyWithinOriginalWriteTtl() {
    val key = EmbyPhotoImageLoader.cacheKey(server, "https://emby.fixture.test/image")
    val writtenAt = 4_000L
    NetworkImageCachePolicy.record(key, writtenAt)
    assertEquals(CachePolicy.ENABLED, EmbyPhotoImageLoader.memoryCachePolicy(key, writtenAt))
    assertEquals(CachePolicy.ENABLED, EmbyPhotoImageLoader.memoryCachePolicy(
      key, writtenAt + NetworkImageCachePolicy.TTL_MS - 1,
    ))
    assertEquals(CachePolicy.WRITE_ONLY, EmbyPhotoImageLoader.memoryCachePolicy(
      key, writtenAt + NetworkImageCachePolicy.TTL_MS,
    ))
    assertEquals(CachePolicy.WRITE_ONLY, EmbyPhotoImageLoader.memoryCachePolicy(key, writtenAt - 1))
    NetworkImageCachePolicy.forget(key)
  }

  @Test fun exactPhotoCacheKeyIncludesUrlAndServerIdentity() {
    val url = "https://emby.fixture.test/image"
    val key = EmbyPhotoImageLoader.cacheKey(server, url)
    assertEquals(key, EmbyPhotoImageLoader.cacheKey(server, url))
    assertNotEquals(key, EmbyPhotoImageLoader.cacheKey(server, "$url?format=jpg"))
    assertNotEquals(key, EmbyPhotoImageLoader.cacheKey(server.copy(apiToken = "other"), url))
  }
}
