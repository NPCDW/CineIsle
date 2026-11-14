package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class EmbySharedConnectionTest {
  @Before fun resetBefore() { EmbyHttp.resetSessionSourceForTests() }
  @After fun resetAfter() { EmbyHttp.resetSessionSourceForTests() }
  private fun server(url: String = "https://example.test/tenant/emby") = EmbyServer(
    id = 1L shl 48, name = "test", host = "example.test", username = "u", password = "p",
    configuredBaseUrl = url, deviceId = "shared-device", client = "shared-client", device = "shared-device-name",
    clientVersion = "4.10", userAgent = "Shared/4.10", apiToken = "token", userId = "user",
  )

  @Test fun `login body escapes credentials losslessly`() {
    val user = "a\"b\\c\n雪"
    val password = "\\\"\t\r\n密码"
    val parsed = Json.parseToJsonElement(EmbyClient.loginJson(user, password)).jsonObject
    assertEquals(user, parsed.getValue("Username").jsonPrimitive.content)
    assertEquals(password, parsed.getValue("Pw").jsonPrimitive.content)
  }

  @Test fun `full API prefix is not duplicated`() {
    assertEquals("https://example.test/tenant/emby", EmbyEndpoints.apiBaseUrl(server()))
    assertEquals("https://example.test/tenant/api", EmbyEndpoints.apiBaseUrl(server("https://example.test/tenant/api/")))
    assertEquals("https://example.test/emby", EmbyEndpoints.apiBaseUrl(server("https://example.test")))
    assertTrue(EmbyClient.imageUrl(server(), "1").startsWith("https://example.test/tenant/emby/Items/1/Images/"))
  }

  @Test fun `relative stream URLs resolve below configured prefix once`() {
    assertEquals("https://example.test/tenant/emby/Videos/1/stream?q=1", EmbyEndpoints.resolve(server(), "/Videos/1/stream?q=1"))
    assertEquals("https://example.test/tenant/emby/Videos/1/stream", EmbyEndpoints.resolve(server(), "/emby/Videos/1/stream"))
    assertEquals("https://example.test/tenant/emby/Videos/1/stream", EmbyEndpoints.resolve(server(), "/tenant/emby/Videos/1/stream"))
    assertEquals("https://cdn.test/media", EmbyEndpoints.resolve(server(), "https://cdn.test/media"))
  }

  @Test fun `API redirects preserve prefix and reverse while CDN and relative directories stay untouched`() {
    val row = server().copy(reverseProxyId = "r", reverseProxyUrl = "https://relay.test/forward")
    val base = EmbyEndpoints.apiBaseUrl(row)
    val request = "$base/Videos/1/stream"
    listOf("/Videos/1/stream.m3u8?signature=a%2Fb", "/emby/Videos/1/stream.m3u8?signature=a%2Fb",
      "/tenant/emby/Videos/1/stream.m3u8?signature=a%2Fb", "https://example.test/Videos/1/stream.m3u8?signature=a%2Fb").forEach {
      assertEquals("$base/Videos/1/stream.m3u8?signature=a%2Fb", EmbyEndpoints.resolveRedirect(row, request, it))
    }
    listOf("$base/Videos/2", "https://cdn.test/Videos/2?signature=a%2Fb", "next.m3u8", "../next.m3u8",
      "?signature=a%2Fb", "/cdn/file.mp4", "https://example.test/cdn/file.mp4").forEach {
      assertEquals(it, EmbyEndpoints.resolveRedirect(row, request, it))
    }
    assertEquals("/Videos/segment.ts", EmbyEndpoints.resolveRedirect(row, "https://cdn.test/media", "/Videos/segment.ts"))
    assertEquals("/Videos/segment.ts", EmbyEndpoints.resolveRedirect(server(), "https://example.test/cdn/media", "/Videos/segment.ts"))
    assertEquals("$base/Items/2", EmbyEndpoints.resolveRedirect(row, "https://cdn.test/media", "https://example.test/Items/2"))
  }

  @Test fun `endpoint parser preserves prefix and IPv6`() {
    val endpoint = EmbyEndpoint.fromUrl("https://[2001:db8::1]:8443/prefix/emby/")
    assertEquals("2001:db8::1", endpoint.host)
    assertEquals(8443, endpoint.port)
    assertEquals("https://[2001:db8::1]:8443/prefix/emby", endpoint.baseUrl)
    assertEquals("http://[2001:db8::1]:8096", EmbyEndpoint("2001:db8::1", 8096, false).baseUrl)
    listOf("https://user:password@example.test/", "https://example.test/?token=secret", "https://example.test/#fragment").forEach {
      assertThrows(EmbyApiException::class.java) { EmbyEndpoint.fromUrl(it) }
    }
  }

  @Test fun `requests use exact shared auth identity and user agent`() {
    val row = server()
    val request = EmbyClient.authedRequest(row, "/Items/1").build()
    assertEquals("Shared/4.10", request.header("User-Agent"))
    assertEquals("MediaBrowser Client=\"shared-client\", Device=\"shared-device-name\", DeviceId=\"shared-device\", Version=\"4.10\"", request.header("X-Emby-Authorization"))
    assertSame(row, request.tag(EmbyServer::class.java))
    assertEquals("token", request.header("X-Emby-Token"))
    assertEquals("shared-client", request.header("X-Emby-Client"))
    assertEquals("shared-device-name", request.header("X-Emby-Device-Name"))
    assertEquals("shared-device", request.header("X-Emby-Device-Id"))
    assertEquals("4.10", request.header("X-Emby-Client-Version"))
    assertNull(request.url.queryParameter("api_key"))
  }

  @Test fun `header maps resolve the entire session identity and retain an empty token header`() {
    val row = server()
    val fresh = row.copy(apiToken = "", client = "session-client", device = "session-device",
      deviceId = "session-id", clientVersion = "9.2", userAgent = "Session/9.2")
    EmbyHttp.installSessionSource { fresh }
    val headers = EmbyRequestHeaders.headers(row, "${row.baseUrl}/Items/1")
    assertEquals("", headers["X-Emby-Token"])
    assertEquals("session-client", headers["X-Emby-Client"])
    assertEquals("session-device", headers["X-Emby-Device-Name"])
    assertEquals("session-id", headers["X-Emby-Device-Id"])
    assertEquals("9.2", headers["X-Emby-Client-Version"])
    assertEquals("Session/9.2", headers["User-Agent"])
    assertEquals(EmbyClient.authorizationHeader(fresh.authIdentity), headers["X-Emby-Authorization"])
    assertFalse(EmbyRequestHeaders.headers(row, row.baseUrl, includeReferer = false).containsKey("Referer"))
    val request = EmbyRequestHeaders.request(row, row.baseUrl, mapOf("Range" to "bytes=0-0")).build()
    assertEquals("bytes=0-0", request.header("Range"))
    assertSame(fresh, request.tag(EmbyServer::class.java))
  }

  @Test fun `external bound CDN maps retain complete identity and ordinary credentials`() {
    val row = server()
    val url = "https://cdn.test/media?signature=fixture"
    val identity = mapOf(
      "User-Agent" to row.userAgent, "Referer" to row.hostUrl,
      "X-Emby-Token" to row.apiToken, "X-Emby-Client" to row.client,
      "X-Emby-Device-Name" to row.device, "X-Emby-Device-Id" to row.deviceId,
      "X-Emby-Client-Version" to row.clientVersion,
      "X-Emby-Authorization" to EmbyClient.authorizationHeader(row.authIdentity),
    )
    assertEquals(identity, EmbyRequestHeaders.headers(row, url))
    val request = EmbyRequestHeaders.request(row, url, mapOf(
      "X-Emby-Token" to "stale", "X-Emby-Client" to "stale", "Authorization" to "ordinary-auth",
      "X-Emby-Authorization" to "stale", "Cookie" to "ordinary-cookie=1", "Range" to "bytes=0-0",
    )).build()
    identity.forEach { (name, value) -> assertEquals(name, value, request.header(name)) }
    assertEquals("ordinary-auth", request.header("Authorization"))
    assertEquals("ordinary-cookie=1", request.header("Cookie"))
    assertEquals("bytes=0-0", request.header("Range"))
    assertSame(row, request.tag(EmbyServer::class.java))
    assertEquals(url, request.url.toString())
    assertNull(request.url.queryParameter("api_key"))
  }

  @Test fun `unrelated loopback proxies have no headers but a configured loopback Emby has all six`() {
    val row = server()
    listOf("http://localhost:3210/iso", "http://127.0.0.1:3210/iso", "http://[::1]:3210/iso").forEach {
      assertTrue(EmbyRequestHeaders.headers(row, it).isEmpty())
    }
    listOf("http://localhost:8096/emby", "http://127.0.0.1:8096/emby", "http://[::1]:8096/emby").forEach {
      val local = row.copy(configuredBaseUrl = it)
      assertEquals(local.apiToken, EmbyRequestHeaders.headers(local, "$it/Items/1")["X-Emby-Token"])
      assertEquals(local.client, EmbyRequestHeaders.headers(local, "$it/Items/1")["X-Emby-Client"])
      assertTrue(EmbyRequestHeaders.headers(local, it.replace(":8096", ":3210")).isEmpty())
    }
  }

  @Test fun `reverse prefix bound requests retain complete identity even for other forwarded hosts`() {
    val row = server().copy(reverseProxyId = "relay", reverseProxyUrl = "https://relay.test/forward")
    val own = EmbyRequestHeaders.headers(row, "${row.effectiveBaseUrl}/Items/1")
    assertEquals(row.apiToken, own["X-Emby-Token"])
    assertEquals(row.apiToken, EmbyRequestHeaders.headers(row, "${row.baseUrl}/Items/1")["X-Emby-Token"])
    assertEquals(own, EmbyRequestHeaders.headers(row, "https://relay.test/forward/https://other.test/emby/Items/1"))
  }

  @Test fun `selected line settings do not override the applied server row`() {
    val row = server().copy(
      lineId = "line",
      lines = listOf(EmbyServerLine("line", "Desktop selected", "https://stale.test", reverseProxyId = "stale")),
    )
    assertNull(row.connectionError)
    assertEquals("Desktop selected", row.currentLineName)
    assertEquals(server().baseUrl, row.effectiveBaseUrl)
    assertTrue(EmbyClient.imageUrl(row, "1").startsWith(row.baseUrl))
    assertThrows(EmbyApiException::class.java) { EmbyEndpoints.apiBaseUrl(row.copy(disabled = true)) }
  }

  @Test fun `reverse prefix concatenates original raw API URL without encoding or URI resolving`() {
    val row = server("https://example.test:8443/tenant%2Fname/api").copy(reverseProxyId = "r",
      reverseProxyName = "relay", reverseProxyUrl = " https://relay.test/forward ")
    val base = "https://relay.test/forward/https://example.test:8443/tenant%2Fname/api"
    assertEquals(base, row.effectiveBaseUrl)
    assertTrue(EmbyClient.imageUrl(row, "1").startsWith("$base/Items/1/Images/"))
    val streamUrl = EmbyClient.videoStreamUrl(row, "1", mediaSourceId = "selected")
    assertTrue(streamUrl.startsWith("$base/Videos/1/stream"))
    assertTrue(streamUrl.contains("static=true"))
    assertTrue(streamUrl.contains("MediaSourceId=selected"))
    assertTrue(EmbyClient.subtitleUrl(row, "1", "ms", 2).startsWith("$base/Videos/1/ms/Subtitles/2/Stream.srt"))
    assertEquals("$base/Videos/1/stream?q=a%2Fb", EmbyEndpoints.resolve(row, "/emby/Videos/1/stream?q=a%2Fb"))
    assertEquals("$base/Videos/1/stream", EmbyEndpoints.resolve(row, "https://example.test:8443/Videos/1/stream"))
    assertEquals("$base/Videos/1/stream", EmbyEndpoints.resolve(row, "$base/Videos/1/stream"))
    assertEquals("https://cdn.test/media?q=1", EmbyEndpoints.resolve(row, "https://cdn.test/media?q=1"))
    assertFalse(EmbyEndpoints.belongsToServer(row, "https://cdn.test/media"))
    assertFalse(EmbyEndpoints.belongsToServer(row, "https://relay.test/forward/https://evil.test/media"))
    assertTrue(EmbyEndpoints.belongsToServer(row, "$base/Videos/1/stream"))
  }

  @Test fun `missing lines and missing invalid reverse references fail explicitly without changing route`() {
    val row = server().copy(lineId = "missing")
    assertTrue(row.connectionError!!.contains("线路不存在"))
    assertThrows(EmbyApiException::class.java) { row.primaryEndpoint() }
    val missing = server().copy(reverseProxyId = "missing")
    assertTrue(missing.connectionError!!.contains("反向代理引用不存在"))
    assertThrows(EmbyApiException::class.java) { EmbyClient.imageUrl(missing, "1") }
    val invalid = missing.copy(reverseProxyUrl = "not-a-url")
    assertTrue(invalid.connectionError!!.contains("地址无效"))
    assertThrows(EmbyApiException::class.java) { EmbyEndpoints.resolve(invalid, "/Videos/1/stream") }
  }

  @Test fun `URL constructors use the matching process token source instead of stale shared tokens`() {
    val row = server()
    EmbyHttp.installSessionSource { it.copy(apiToken = "memory-only") }
    assertTrue(EmbyClient.imageUrl(row, "1").contains("api_key=memory-only"))
    assertTrue(EmbyClient.videoStreamUrl(row, "1").contains("api_key=memory-only"))
    assertTrue(EmbyClient.subtitleUrl(row, "1", "ms", 0).contains("api_key=memory-only"))
    assertEquals("memory-only", EmbyClient.authedRequest(row, "/Items/1").build().header("X-Emby-Token"))
    assertEquals("token", row.apiToken)
  }
}
