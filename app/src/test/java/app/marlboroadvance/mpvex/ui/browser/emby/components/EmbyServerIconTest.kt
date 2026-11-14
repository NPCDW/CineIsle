package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import coil3.request.CachePolicy
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Format/cache/request boundary tests never fetch a network URL or real credentials. */
class EmbyServerIconTest {
  private val png = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jf9kAAAAASUVORK5CYII=")
  private fun data(bytes: ByteArray, mime: String = "png") = "data:image/$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"

  @Test fun desktopBase64RasterIsDecodedLocallyWithoutTreatingItAsHttp() {
    assertTrue(EmbyServerIconLoader.isSupportedValue(data(png)))
    assertArrayEquals(png, EmbyServerIconLoader.decodeDataImage(data(png)))
    assertArrayEquals(png, EmbyServerIconLoader.decodeDataImage(data(png).replace("image/png", "IMAGE/PNG")))
  }

  @Test fun blankMalformedSvgAndNonImageDataUseFallback() {
    listOf("", " ", "file:///private/icon.png", "content://private/icon", "javascript:private",
      "data:text/plain;base64,aGVsbG8=", "data:image/svg+xml;base64,PHN2Zy8+", "data:image/png;base64,invalid!",
      "data:image/png;base64,aGVsbG8=", "data:image/png,%89PNG")
      .forEach { assertNull(it, EmbyServerIconLoader.decodeDataImage(it)) }
    assertFalse(EmbyServerIconLoader.isSupportedValue("data:image/svg+xml;base64,PHN2Zy8+"))
    assertFalse(EmbyServerIconLoader.isRaster("<svg><path/></svg>".toByteArray()))
  }

  @Test fun boundedBase64RejectsOversizedInputBeforeDecode() {
    val bytes = ByteArray(EmbyServerIconLoader.MAX_IMAGE_BYTES + 1)
    png.copyInto(bytes, endIndex = 8)
    assertNull(EmbyServerIconLoader.decodeDataImage(data(bytes)))
    assertFalse(EmbyServerIconLoader.isSupportedValue("data:image/png;base64," + "A".repeat(4 * 1024 * 1024)))
  }

  @Test fun rasterSignaturesRecognizePngJpegWebpGifBmpButNotSvgOrHtml() {
    assertTrue(EmbyServerIconLoader.isRaster(png))
    assertTrue(EmbyServerIconLoader.isRaster(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
    assertTrue(EmbyServerIconLoader.isRaster("RIFF0000WEBP".toByteArray()))
    assertTrue(EmbyServerIconLoader.isRaster("GIF89a".toByteArray()))
    assertTrue(EmbyServerIconLoader.isRaster("BM".toByteArray()))
    assertFalse(EmbyServerIconLoader.isRaster("<html>server error</html>".toByteArray()))
    assertFalse(EmbyServerIconLoader.isRaster(byteArrayOf()))
  }

  @Test fun cacheKeyIncludesSourceAndIconContentButDoesNotExposeEither() {
    val key = EmbyServerIconLoader.cacheKey("source-private", "https://cdn.test/icon.png?private=secret")
    assertEquals(64, key.length)
    assertTrue(key.all { it in "0123456789abcdef" })
    assertEquals(key, EmbyServerIconLoader.cacheKey("source-private", "https://cdn.test/icon.png?private=secret"))
    assertNotEquals(key, EmbyServerIconLoader.cacheKey("other-source", "https://cdn.test/icon.png?private=secret"))
    assertNotEquals(key, EmbyServerIconLoader.cacheKey("source-private", "https://cdn.test/other.png"))
    assertNotEquals(EmbyServerIconLoader.cacheKey("ab", "c"), EmbyServerIconLoader.cacheKey("a", "bc"))
    assertFalse(EmbyServerIconData("https://private.test?token=secret", key).toString().contains("secret"))
  }

  @Test fun onlyPublicHttpUrlsAcceptedNoUserInfoLocalUrisOrBadPorts() {
    listOf("https://cdn.test/icon.png", "http://cdn.test/icon.webp?version=2", "http://[::1]:8096/icon.png")
      .forEach { assertTrue(it, EmbyServerIconLoader.isSupportedValue(it)) }
    listOf("https://user:secret@cdn.test/icon.png", "https://cdn.test:0/icon", "https://cdn.test:65536/icon",
      "ftp://cdn.test/icon.png", "https://cdn.test/icon#private", "invalid url")
      .forEach { assertFalse(it, EmbyServerIconLoader.isSupportedValue(it)) }
  }

  private fun server() = EmbyServer(
    id = 7, sourceKey = "source-a", sourceRevision = 2, remoteId = "row-a",
    name = "row", host = "emby.test", port = 443, useHttps = true,
    username = "user", password = "password", apiToken = "token-a", userId = "user-a",
    client = "row-client", device = "row-device", deviceId = "row-device-id",
    clientVersion = "row-version", userAgent = "row-agent",
  )

  @Test fun ownIconRequestUsesOnlyItsExplicitRowsIdentity() {
    val row = server()
    val request = EmbyServerIconLoader.iconRequest("https://emby.test/icon.png", row)
    assertEquals(row, request.tag(EmbyServer::class.java))
    assertEquals("token-a", request.header("X-Emby-Token"))
    assertEquals("row-client", request.header("X-Emby-Client"))
    assertEquals("row-device-id", request.header("X-Emby-Device-Id"))
    assertEquals("row-agent", request.header("User-Agent"))
    assertNull(request.url.queryParameter("api_key"))
    assertEquals("token-b", EmbyServerIconLoader.iconRequest(
      "https://emby.test/icon.png", row.copy(apiToken = "token-b"),
    ).header("X-Emby-Token"))
  }

  @Test fun ownIconUsesConfiguredApiAndReversePrefixExactlyOnce() {
    val row = server().copy(configuredBaseUrl = "https://emby.test/tenant/emby", reverseProxyId = "r",
      reverseProxyUrl = "https://relay.test/forward")
    val expected = "https://relay.test/forward/https://emby.test/tenant/emby/Items/1/Images/Primary?v=a%2Fb"
    listOf("https://emby.test/Items/1/Images/Primary?v=a%2Fb",
      "https://emby.test/tenant/emby/Items/1/Images/Primary?v=a%2Fb", expected).forEach { value ->
      val request = EmbyServerIconLoader.iconRequest(value, row)
      assertEquals(expected, request.url.toString())
      assertEquals(row, request.tag(EmbyServer::class.java))
      assertEquals(row.apiToken, request.header("X-Emby-Token"))
    }
  }

  @Test fun publicIconWithAnExplicitRowRemainsUntaggedAndCredentialFree() {
    val request = EmbyServerIconLoader.iconRequest("https://cdn.test/icon.png?v=2", server())
    assertNull(request.tag(EmbyServer::class.java))
    assertNull(request.header("X-Emby-Token"))
    assertNull(request.header("X-Emby-Authorization"))
    assertNull(request.header("Authorization"))
    assertNull(request.header("Cookie"))
    assertNull(request.header("Referer"))
    assertNull(request.header("X-Emby-Device-Id"))
    assertNull(request.url.queryParameter("api_key"))
    assertEquals("2", request.url.queryParameter("v"))
  }

  @Test fun iconCacheIsolatedByRowRevisionAndCredentialsEvenForTheSameUrl() {
    val row = server()
    val url = "https://emby.test/icon.png"
    val key = EmbyServerIconLoader.cacheKey(row.sourceKey, url, row)
    listOf(row.copy(remoteId = "row-b"), row.copy(sourceRevision = 3),
      row.copy(apiToken = "token-b"), row.copy(deviceId = "other-device"))
      .forEach { assertNotEquals(key, EmbyServerIconLoader.cacheKey(it.sourceKey, url, it)) }
    assertNotEquals(key, EmbyServerIconLoader.cacheKey(row.sourceKey, url))
    assertFalse(EmbyServerIconData(url, key, row).toString().contains("token-a"))
  }

  @Test fun externalIconRequestNeverAddsEmbyCredentialsOrSessionTag() {
    val request = EmbyServerIconLoader.publicRequest("https://cdn.test/icon.png?v=2")
    assertEquals("GET", request.method)
    assertNull(request.tag(EmbyServer::class.java))
    assertNull(request.header("X-Emby-Token"))
    assertNull(request.header("X-Emby-Authorization"))
    assertNull(request.header("Authorization"))
    assertNull(request.url.queryParameter("api_key"))
    assertEquals("2", request.url.queryParameter("v"))
    assertThrows(IllegalArgumentException::class.java) { EmbyServerIconLoader.publicRequest(data(png)) }
  }

  @Test fun remoteDecodedMemoryCannotBypassTtlButLocalDataRemainsLocal() {
    val key = "icon-memory-fixture"
    val writtenAt = 12_000L
    NetworkImageCachePolicy.record(key, writtenAt)
    assertEquals(CachePolicy.ENABLED, EmbyServerIconLoader.memoryCachePolicy("https://cdn.test/icon.png", key, writtenAt))
    assertEquals(CachePolicy.WRITE_ONLY, EmbyServerIconLoader.memoryCachePolicy(
      "https://cdn.test/icon.png", key, writtenAt + NetworkImageCachePolicy.TTL_MS,
    ))
    assertEquals(CachePolicy.WRITE_ONLY, EmbyServerIconLoader.memoryCachePolicy(
      "https://cdn.test/icon.png", key, writtenAt - 1,
    ))
    assertEquals(CachePolicy.ENABLED, EmbyServerIconLoader.memoryCachePolicy(data(png), "untracked", writtenAt))
    NetworkImageCachePolicy.forget(key)
  }
}
