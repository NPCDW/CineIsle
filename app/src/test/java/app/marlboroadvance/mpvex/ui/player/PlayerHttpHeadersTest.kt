package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHttpHeadersTest {
  private fun server() = EmbyServer(
    id = 42, name = "fixture", host = "emby.example", username = "fixture", password = "not-serialized",
    configuredBaseUrl = "https://emby.example/library", apiToken = "fixture-token",
    client = "fixture-client", device = "fixture-device", deviceId = "fixture-id",
    clientVersion = "2", userAgent = "fixture-UA/2",
  )

  @Test fun `first pair is not assumed to be UA`() {
    val headers = PlayerHttpHeaders.parse(arrayOf("Referer", "https://origin.example", "X-Test", "value"))
    assertEquals("https://origin.example", headers["Referer"])
    assertEquals("value", headers["X-Test"])
  }

  @Test fun `UA can appear anywhere and duplicate names ignore case`() {
    val headers = PlayerHttpHeaders.parse(arrayOf("Referer", "old", "user-agent", "custom", "referer", "new"))
    assertEquals(2, headers.size)
    assertEquals("new", headers.entries.single { it.key.equals("Referer", true) }.value)
    assertEquals("custom", headers["user-agent"])
  }

  @Test fun `empty odd and incomplete arrays are safe`() {
    assertTrue(PlayerHttpHeaders.parse(null).isEmpty())
    assertTrue(PlayerHttpHeaders.parse(emptyArray()).isEmpty())
    assertTrue(PlayerHttpHeaders.parse(arrayOf("User-Agent")).isEmpty())
    assertEquals(mapOf("X-Test" to "ok"), PlayerHttpHeaders.parse(arrayOf("X-Test", "ok", "orphan")))
  }

  @Test fun `complete pairs round trip`() {
    val headers = linkedMapOf("Referer" to "origin", "User-Agent" to "custom", "X-Test" to "value")
    assertEquals(headers, PlayerHttpHeaders.parse(PlayerHttpHeaders.toPairs(headers)))
  }

  @Test fun `each Emby URL receives all six current fields`() {
    val row = server()
    val stale = mapOf("x-emby-token" to "old", "user-agent" to "old-UA", "referer" to "old-origin")
    for (url in listOf("https://emby.example/Videos/first/stream", "https://emby.example/Videos/next/stream")) {
      val headers = PlayerHttpHeaders.forUrl(url, stale, row)
      assertEquals(row.apiToken, headers["X-Emby-Token"])
      assertEquals(row.client, headers["X-Emby-Client"])
      assertEquals(row.device, headers["X-Emby-Device-Name"])
      assertEquals(row.deviceId, headers["X-Emby-Device-Id"])
      assertEquals(row.clientVersion, headers["X-Emby-Client-Version"])
      assertEquals(row.userAgent, headers["User-Agent"])
      assertEquals(1, headers.keys.count { it.equals("X-Emby-Token", true) })
    }
  }

  @Test fun `bound CDN retains complete identity and ordinary credentials`() {
    val row = server()
    val headers = PlayerHttpHeaders.forUrl("https://cdn.example/movie", mapOf(
      "X-Emby-Token" to "old", "x-emby-device-id" to "old-id", "X-Emby-Authorization" to "old-auth",
      "Authorization" to "ordinary-basic", "cookie" to "ordinary-cookie", "Range" to "bytes=0-1",
    ), row)
    assertEquals(row.apiToken, headers["X-Emby-Token"])
    assertEquals(row.client, headers["X-Emby-Client"])
    assertEquals(row.device, headers["X-Emby-Device-Name"])
    assertEquals(row.deviceId, headers["X-Emby-Device-Id"])
    assertEquals(row.clientVersion, headers["X-Emby-Client-Version"])
    assertEquals(EmbyClient.authorizationHeader(row.authIdentity), headers["X-Emby-Authorization"])
    assertEquals("ordinary-basic", headers["Authorization"])
    assertEquals("ordinary-cookie", headers["cookie"])
    assertEquals(row.userAgent, headers["User-Agent"])
    assertEquals(row.hostUrl, headers["Referer"])
    assertEquals("bytes=0-1", headers["Range"])
    assertEquals(1, headers.keys.count { it.equals("X-Emby-Device-Id", true) })
  }

  @Test fun `local ISO and local files have no inherited network headers`() {
    val stale = mapOf("X-Emby-Token" to "old", "Authorization" to "old", "Referer" to "old")
    for (url in listOf("http://127.0.0.1:12345/iso/movie", "http://localhost:12345/iso", "http://[::1]:12345/iso", "content://media/1", "file:///movie.mkv")) {
      assertTrue(PlayerHttpHeaders.forUrl(url, stale, server()).isEmpty())
    }
  }

  @Test fun `configured loopback Emby is not mistaken for local ISO`() {
    val row = server().copy(host = "127.0.0.1", configuredBaseUrl = "http://127.0.0.1:8096")
    assertEquals(row.apiToken, PlayerHttpHeaders.forUrl("http://127.0.0.1:8096/Videos/a/stream", server = row)["X-Emby-Token"])
  }

  @Test fun `unbound generic streams preserve supplied authentication but clear old Emby identity`() {
    val headers = PlayerHttpHeaders.forUrl("https://video.example/file", mapOf(
      "User-Agent" to "generic", "Referer" to "generic-origin", "Authorization" to "generic-auth",
      "Cookie" to "generic-cookie", "x-emby-token" to "stale",
    ))
    assertEquals("generic", headers["User-Agent"])
    assertEquals("generic-auth", headers["Authorization"])
    assertEquals("generic-cookie", headers["Cookie"])
    assertFalse(headers.keys.any { it.startsWith("X-Emby-", true) })
  }

  @Test fun `mpv removes only UA and escapes complete header field list`() {
    val headers = linkedMapOf("user-agent" to "custom", "Referer" to "origin", "X-Test" to "one,two\\three", "X-Emby-Token" to "fixture")
    assertEquals("Referer: origin,X-Test: one\\,two\\\\three,X-Emby-Token: fixture", PlayerHttpHeaders.mpvHeaderFields(headers))
    assertEquals("", PlayerHttpHeaders.mpvHeaderFields(emptyMap()))
  }
}
