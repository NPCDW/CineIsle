package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.junit.Assert.*
import org.junit.Test

class EmbyImageIdentityTest {
  private val server = EmbyServer(
    id = 1, name = "fixture", host = "emby.fixture.test", username = "user", password = "secret",
    sourceKey = "source", sourceRevision = 1, remoteId = "row", apiToken = "token",
    client = "Shared", device = "SharedDevice", deviceId = "SharedId", clientVersion = "4", userAgent = "Shared/4",
  )
  private val url = "http://emby.fixture.test:8096/emby/Items/1/Images/Primary"

  @Test fun cacheKeyIsStableAndDoesNotContainCredentials() {
    val key = EmbyImageLoader.cacheKey(server, url)
    assertEquals(key, EmbyImageLoader.cacheKey(server, url))
    assertEquals(64, key.length)
    assertFalse(key.contains("secret"))
    assertFalse(key.contains("token"))
  }

  @Test fun sameUrlForDifferentIdentityOrSourceNeverSharesCache() {
    val key = EmbyImageLoader.cacheKey(server, url)
    listOf(
      server.copy(id = 2), server.copy(remoteId = "other"), server.copy(sourceKey = "other"),
      server.copy(sourceRevision = 2), server.copy(username = "other"), server.copy(password = "other"),
      server.copy(userId = "other"), server.copy(apiToken = "other"), server.copy(client = "other"),
      server.copy(device = "other"), server.copy(deviceId = "other"), server.copy(clientVersion = "other"),
      server.copy(userAgent = "other"), server.copy(configuredBaseUrl = "https://other.test/prefix"),
    ).forEach { assertNotEquals(key, EmbyImageLoader.cacheKey(it, url)) }
  }

  @Test fun cacheKeySeparatesFieldBoundariesAndUrls() {
    assertNotEquals(
      EmbyImageLoader.cacheKey(server.copy(username = "ab", password = "c"), url),
      EmbyImageLoader.cacheKey(server.copy(username = "a", password = "bc"), url),
    )
    assertNotEquals(EmbyImageLoader.cacheKey(server, url), EmbyImageLoader.cacheKey(server, "$url?maxWidth=480"))
    assertNotEquals(EmbyImageLoader.sha256("ab", "c"), EmbyImageLoader.sha256("a", "bc"))
  }

  @Test fun fastHexDigestIsStableLowercaseAndDoesNotExposeParts() {
    val key = EmbyImageLoader.sha256("source-private", "https://example.test/image?token=secret")
    assertEquals(key, EmbyImageLoader.sha256("source-private", "https://example.test/image?token=secret"))
    assertEquals(64, key.length)
    assertTrue(key.all { it in "0123456789abcdef" })
    assertFalse(key.contains("secret"))
  }

  @Test fun photoCacheAcceptsSupportedSignaturesAndRejectsCorruptBodies() {
    assertTrue(EmbyImageLoader.hasSupportedImageSignature(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
    assertTrue(EmbyImageLoader.hasSupportedImageSignature(byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )))
    assertTrue(EmbyImageLoader.hasSupportedImageSignature("RIFF0000WEBP".toByteArray()))
    assertFalse(EmbyImageLoader.hasSupportedImageSignature("<html>not an image</html>".toByteArray()))
    assertFalse(EmbyImageLoader.hasSupportedImageSignature(byteArrayOf()))
  }
}
