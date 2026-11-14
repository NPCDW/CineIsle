package app.marlboroadvance.mpvex.domain.emby

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual HTTP wire fixtures, bound only to loopback. No PostgreSQL, internet or extra test dependency. */
class EmbyHttpNetworkTest {
  private val fixtures = mutableListOf<Fixture>()

  @Before fun before() { EmbyHttp.resetSessionSourceForTests() }
  @After fun after() {
    EmbyHttp.resetSessionSourceForTests()
    fixtures.forEach { it.close() }
  }

  private fun fixture() = Fixture().also { fixtures += it }
  private fun row(url: String) = EmbyServer(
    id = 1L shl 48, name = "fixture", host = "127.0.0.1", username = "fixture-user", password = "fixture-password",
    configuredBaseUrl = url, apiToken = "fixture-token", userId = "fixture-user-id", client = "FixtureClient",
    device = "FixtureDevice", deviceId = "fixture-device-id", clientVersion = "7.1", userAgent = "Fixture/7.1",
  )

  private fun client(base: OkHttpClient = EmbyHttp.shared) = base.newBuilder()
    .proxy(Proxy.NO_PROXY)
    .dns(object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        check(hostname.endsWith(".fixture.test")) { "Only synthetic fixture DNS is permitted" }
        return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
      }
    })
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

  private fun execute(client: OkHttpClient, request: Request): Int = client.newCall(request).execute().use {
    it.body.string()
    it.code
  }

  private fun assertIdentity(request: Captured, server: EmbyServer) {
    assertEquals(server.apiToken, request.header("X-Emby-Token"))
    assertEquals(server.client, request.header("X-Emby-Client"))
    assertEquals(server.device, request.header("X-Emby-Device-Name"))
    assertEquals(server.deviceId, request.header("X-Emby-Device-Id"))
    assertEquals(server.clientVersion, request.header("X-Emby-Client-Version"))
    assertEquals(server.userAgent, request.header("User-Agent"))
    assertEquals(EmbyClient.authorizationHeader(server.authIdentity), request.header("X-Emby-Authorization"))
    assertEquals(server.hostUrl, request.header("Referer"))
    assertNull(request.header("X-Emby-Device"))
  }

  private fun assertRefreshedQuery(request: Captured, path: String, token: String) {
    val url = "http://fixture.test${request.uri}".toHttpUrl()
    assertEquals(path, url.encodedPath)
    assertEquals(listOf(token), url.queryParameterValues("api_key"))
    assertEquals("a/b", url.queryParameter("signature"))
    // setQueryParameter removes and appends api_key; order is not part of the auth contract.
    assertEquals(setOf("api_key=$token", "signature=a%2Fb"), url.encodedQuery!!.split('&').toSet())
    assertEquals(2, url.querySize)
  }

  @Test fun `movie and episode favorite and played writes use authorized endpoints and never replay`() {
    listOf("Episode" to "episode", "Movie" to "movie").forEach { (type, itemId) ->
      val fixture = fixture()
      val server = row(fixture.url())
      val item = EmbyItem(Id = itemId, Type = type)
      listOf("FavoriteItems", "PlayedItems").forEach { endpoint ->
        listOf(true, false).forEach { enabled ->
          fixture.enqueue(body = """{"IsFavorite":$enabled,"Played":$enabled}""")
          val result = if (endpoint == "FavoriteItems") EmbyClient.setMediaFavorite(server, item, enabled)
            else EmbyClient.setMediaPlayed(server, item, enabled)
          assertEquals(enabled, if (endpoint == "FavoriteItems") result.IsFavorite else result.Played)
          val request = fixture.take()
          assertEquals(if (enabled) "POST" else "DELETE", request.method)
          assertTrue(request.uri.startsWith("/emby/Users/fixture-user-id/$endpoint/$itemId"))
          if (enabled) { assertEquals("{}", request.body); assertEquals("application/json; charset=UTF-8", request.header("Content-Type")) }
          assertIdentity(request, server)
        }
      }
    }
  }

  @Test fun `episode empty success uses readback instead of dto defaults`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = "")
    fixture.enqueue(body = """{"Id":"episode","Type":"Episode","UserData":{"IsFavorite":true,"PlaybackPositionTicks":120000000}}""")
    val result = EmbyClient.setMediaFavorite(server, EmbyItem(Id = "episode", Type = "Episode"), true)
    assertTrue(result.IsFavorite)
    assertEquals("POST", fixture.take().method)
    assertEquals("GET", fixture.take().method)
  }

  @Test fun `uncertain episode write is read once and never automatically retried`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(code = 503, body = "failure")
    fixture.enqueue(body = """{"Id":"episode","Type":"Episode","UserData":{"Played":true}}""")
    assertTrue(EmbyClient.setMediaPlayed(server, EmbyItem(Id = "episode", Type = "Episode"), true).Played)
    assertEquals("POST", fixture.take().method)
    assertEquals("GET", fixture.take().method)
  }

  @Test fun `media status writes reject containers other video types and unsafe ids before network`() {
    listOf(
      EmbyItem(Id = "series", Type = "Series"),
      EmbyItem(Id = "season", Type = "Season"),
      EmbyItem(Id = "box", Type = "BoxSet"),
      EmbyItem(Id = "video", Type = "Video"),
      EmbyItem(Id = "bad/id", Type = "Episode"),
      EmbyItem(Type = "Movie"),
    ).forEach {
      try { EmbyClient.setMediaFavorite(row("http://127.0.0.1:1"), it, true); fail("must reject") }
      catch (_: EmbyEpisodeOperationException) { }
    }
  }

  @Test fun `all shared client profiles fill six headers on the wire even for an empty token`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test")).copy(apiToken = "")
    listOf(EmbyHttp.shared, EmbyHttp.streaming, EmbyHttp.images).forEach { base ->
      fixture.enqueue()
      // An older caller supplies only a row tag: the network boundary still completes its identity.
      val request = Request.Builder().url("${server.baseUrl}/Items/1")
        .tag(EmbyServer::class.java, server).header("X-Emby-Client", "stale").build()
      assertEquals(200, execute(client(base), request))
      assertIdentity(fixture.take(), server)
    }
  }

  @Test fun `session refresh replaces every identity field at execution without adding api_key`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val request = EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1?signature=a%2Fb").build()
    val fresh = server.copy(apiToken = "memory-token", client = "MemoryClient", device = "MemoryDevice",
      deviceId = "memory-device-id", clientVersion = "8.2", userAgent = "Memory/8.2")
    EmbyHttp.installSessionSource { fresh }
    fixture.enqueue()
    assertEquals(200, execute(client(), request))
    val captured = fixture.take()
    assertIdentity(captured, fresh)
    assertEquals("/emby/Items/1?signature=a%2Fb", captured.uri)
    assertEquals("fixture-token", server.apiToken)
  }

  @Test fun `existing api_key refreshes at execution while the rest of the query stays unchanged`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val request = EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1?api_key=old&signature=a%2Fb").build()
    val fresh = server.copy(apiToken = "memory-token")
    EmbyHttp.installSessionSource { fresh }
    fixture.enqueue()
    assertEquals(200, execute(client(), request))
    val captured = fixture.take()
    assertIdentity(captured, fresh)
    assertRefreshedQuery(captured, "/emby/Items/1", "memory-token")
  }

  @Test fun `401 recovery is row scoped and replays refreshed identity and only existing api_key`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    var session = server
    val calls = AtomicInteger()
    EmbyHttp.installSessionSource { session }
    EmbyHttp.onUnauthorized = { bound ->
      assertEquals(server.id, bound.id)
      calls.incrementAndGet()
      session = server.copy(apiToken = "recovered-token", client = "RecoveredClient", device = "RecoveredDevice",
        deviceId = "recovered-device-id", clientVersion = "9.3", userAgent = "Recovered/9.3")
      session.apiToken
    }
    fixture.enqueue(401)
    fixture.enqueue()
    assertEquals(200, execute(client(), EmbyRequestHeaders.request(server,
      "${server.baseUrl}/Items/1?api_key=old&signature=a%2Fb").build()))
    assertIdentity(fixture.take(), server)
    val replay = fixture.take()
    assertIdentity(replay, session)
    assertRefreshedQuery(replay, "/emby/Items/1", "recovered-token")
    assertEquals(1, calls.get())
  }

  @Test fun `401 can replay a JSON POST once using callback token without a session provider`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val calls = AtomicInteger()
    EmbyHttp.onUnauthorized = { calls.incrementAndGet(); "recovered-token" }
    fixture.enqueue(401)
    fixture.enqueue(401)
    val payload = "{\"ItemId\":\"fixture-item\"}"
    val request = EmbyRequestHeaders.request(server, "${server.baseUrl}/Sessions/Playing")
      .post(EmbyClient.jsonBody(payload)).build()
    assertEquals(401, execute(client(), request))
    val first = fixture.take()
    val second = fixture.take()
    assertIdentity(first, server)
    assertIdentity(second, server.copy(apiToken = "recovered-token"))
    assertEquals(payload, first.body)
    assertEquals(payload, second.body)
    assertEquals("application/json; charset=UTF-8", second.header("Content-Type"))
    assertFalse(second.uri.contains("api_key"))
    assertEquals(1, calls.get())
  }

  @Test fun `a session invalidated during a 401 response propagates cancellation without recovery`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val invalidated = AtomicReference(false)
    val recoveryCalls = AtomicInteger()
    val cancellation = kotlinx.coroutines.CancellationException("Fixture session invalidated")
    EmbyHttp.installSessionSource {
      if (invalidated.get()) throw cancellation
      it
    }
    EmbyHttp.onUnauthorized = { recoveryCalls.incrementAndGet(); "must-not-be-used" }
    fixture.enqueue(401, beforeReply = { invalidated.set(true) })
    val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
      execute(client(), EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1").build())
    }
    assertSame(cancellation, thrown)
    assertIdentity(fixture.take(), server)
    assertEquals(0, recoveryCalls.get())
  }

  @Test fun `401 replay does not pin its fallback token over a newer redirect session`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val fresh = server.copy(apiToken = "newer-session-token", userAgent = "Newer/7.1")
    val session = AtomicReference(server)
    EmbyHttp.installSessionSource { session.get() }
    EmbyHttp.onUnauthorized = { "recovered-token" }
    fixture.enqueue(401)
    fixture.enqueue(302, location = "${server.baseUrl}/Items/2?api_key=old", beforeReply = { session.set(fresh) })
    fixture.enqueue()
    assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1").build()))
    assertIdentity(fixture.take(), server)
    assertIdentity(fixture.take(), server.copy(apiToken = "recovered-token"))
    val redirected = fixture.take()
    assertIdentity(redirected, fresh)
    assertEquals("/emby/Items/2?api_key=newer-session-token", redirected.uri)
  }

  @Test fun `login and external CDN 401 responses never invoke automatic login`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val calls = AtomicInteger()
    EmbyHttp.onUnauthorized = { calls.incrementAndGet(); "must-not-be-used" }
    fixture.enqueue(401)
    val login = EmbyRequestHeaders.request(server, "${server.baseUrl}/Users/AuthenticateByName")
      .post(EmbyClient.jsonBody("{}")).build()
    assertEquals(401, execute(client(), login))
    assertIdentity(fixture.take(), server)
    fixture.enqueue(302, location = fixture.url("cdn.fixture.test", "/media"))
    fixture.enqueue(401)
    assertEquals(401, execute(client(), EmbyRequestHeaders.request(server, "${server.baseUrl}/Videos/1/stream").build()))
    assertIdentity(fixture.take(), server)
    assertIdentity(fixture.take(), server)
    assertEquals(0, calls.get())
  }

  @Test fun `API redirect locations preserve full prefix for all client profiles and reverse routing`() {
    val fixture = fixture()
    val original = row(fixture.url("emby.fixture.test", "/tenant/emby"))
    listOf(original, original.copy(reverseProxyId = "r", reverseProxyUrl = fixture.url("relay.fixture.test", "/forward"))).forEach { server ->
      val base = EmbyEndpoints.apiBaseUrl(server)
      listOf(EmbyHttp.shared, EmbyHttp.search, EmbyHttp.images, EmbyHttp.streaming).forEach { profile ->
        listOf("/Videos/1/stream?signature=a%2Fb", "${original.hostUrl}/Videos/1/stream?signature=a%2Fb",
          "/tenant/emby/Items/1/Images/Primary?signature=a%2Fb").forEach { location ->
          fixture.enqueue(302, location = location)
          fixture.enqueue()
          assertEquals(200, execute(client(profile), EmbyRequestHeaders.request(server, "$base/Items/1").build()))
          assertIdentity(fixture.take(), server)
          val followed = fixture.take()
          assertIdentity(followed, server)
          val suffix = if (location.contains("Images")) "/Items/1/Images/Primary" else "/Videos/1/stream"
          assertEquals("${base.toHttpUrl().encodedPath}$suffix?signature=a%2Fb", followed.uri)
        }
      }
    }
  }

  @Test fun `reverse API 307 and 308 keep POST bodies and ordinary CDN relative locations`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test", "/tenant/emby")).copy(reverseProxyId = "r",
      reverseProxyUrl = fixture.url("relay.fixture.test", "/forward"))
    val base = EmbyEndpoints.apiBaseUrl(server)
    listOf(307, 308).forEach { status ->
      fixture.enqueue(status, location = "/Sessions/Playing/Progress")
      fixture.enqueue()
      val payload = """{"ItemId":"fixture-item","PositionTicks":123}"""
      assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "$base/Sessions/Playing")
        .post(EmbyClient.jsonBody(payload)).build()))
      listOf(fixture.take(), fixture.take()).forEach {
        assertEquals("POST", it.method)
        assertEquals(payload, it.body)
        assertEquals("application/json; charset=UTF-8", it.header("Content-Type"))
        assertIdentity(it, server)
      }
    }
    fixture.enqueue(302, location = fixture.url("cdn.fixture.test", "/media/master.m3u8?signature=a%2Fb"))
    fixture.enqueue(302, location = "/Videos/segment.ts?signature=a%2Fb")
    fixture.enqueue(302, location = "${server.hostUrl}/Items/2?signature=a%2Fb")
    fixture.enqueue()
    assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "$base/Items/1").build()))
    fixture.take()
    assertEquals("/media/master.m3u8?signature=a%2Fb", fixture.take().uri)
    assertEquals("/Videos/segment.ts?signature=a%2Fb", fixture.take().uri)
    assertEquals("${base.toHttpUrl().encodedPath}/Items/2?signature=a%2Fb", fixture.take().uri)
  }

  @Test fun `307 and 308 forward login and progress POST bodies with complete identity to an external server`() {
    val emby = fixture()
    val cdn = fixture()
    val server = row(emby.url("emby.fixture.test"))
    listOf(307, 308).forEach { status ->
      listOf("/Users/AuthenticateByName", "/Sessions/Playing/Progress").forEach { path ->
        emby.enqueue(status, location = cdn.url("cdn.fixture.test", "/external?api_key=cdn-key&signature=a%2Fb"))
        cdn.enqueue()
        val payload = if (path.endsWith("AuthenticateByName")) EmbyClient.loginJson("fixture-user雪", "fixture-password\n\\\"")
          else """{"ItemId":"fixture-item","PlaySessionId":"fixture-session","MediaSourceId":"fixture-source","PositionTicks":123}"""
        val request = EmbyRequestHeaders.request(server, "${server.baseUrl}$path")
          .post(EmbyClient.jsonBody(payload)).build()
        assertEquals(200, execute(client(), request))
        val original = emby.take()
        val forwarded = cdn.take()
        listOf(original, forwarded).forEach {
          assertEquals("POST", it.method)
          assertEquals(payload, it.body)
          assertEquals("application/json; charset=UTF-8", it.header("Content-Type"))
          assertIdentity(it, server)
        }
        assertEquals("/emby$path", original.uri)
        assertEquals("/external?api_key=cdn-key&signature=a%2Fb", forwarded.uri)
        assertFalse(emby.hasRequest())
        assertFalse(cdn.hasRequest())
      }
    }
  }

  @Test fun `redirects retain complete CDN identity and cookie before returning to Emby`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    fixture.enqueue(302, location = fixture.url("cdn.fixture.test", "/media?api_key=cdn-key&signature=a%2Fb"))
    fixture.enqueue(302, location = "${server.baseUrl}/Videos/1/stream?api_key=old&signature=a%2Fb")
    fixture.enqueue()
    val request = EmbyRequestHeaders.request(server, "${server.baseUrl}/Videos/1/redirect", mapOf(
      "Authorization" to "fixture-authorization", "Cookie" to "fixture-cookie=1", "Range" to "bytes=0-0",
    )).build()
    assertEquals(200, execute(client(), request))
    val original = fixture.take()
    assertIdentity(original, server)
    assertEquals("fixture-authorization", original.header("Authorization"))
    assertEquals("fixture-cookie=1", original.header("Cookie"))
    val cdn = fixture.take()
    assertIdentity(cdn, server)
    assertEquals("fixture-cookie=1", cdn.header("Cookie"))
    assertEquals("/media?api_key=cdn-key&signature=a%2Fb", cdn.uri)
    assertEquals("bytes=0-0", cdn.header("Range"))
    val returned = fixture.take()
    assertIdentity(returned, server)
    assertEquals("fixture-cookie=1", returned.header("Cookie"))
    assertRefreshedQuery(returned, "/emby/Videos/1/stream", "fixture-token")
  }

  @Test fun `bound CDN redirects preserve ordinary credentials and row identity before returning to Emby`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    fixture.enqueue(302, location = fixture.url("cdn.fixture.test", "/segment?signature=a%2Fb"))
    fixture.enqueue(302, location = "${server.baseUrl}/Items/1")
    fixture.enqueue()
    val request = EmbyRequestHeaders.request(server, fixture.url("cdn.fixture.test", "/media"), mapOf(
      "Authorization" to "cdn-authorization", "Cookie" to "cdn-cookie=1",
    )).build()
    assertSame(server, request.tag(EmbyServer::class.java))
    assertEquals(200, execute(client(), request))
    val first = fixture.take()
    val segment = fixture.take()
    listOf(first, segment).forEach {
      assertIdentity(it, server)
      assertEquals("cdn-authorization", it.header("Authorization"))
      assertEquals("cdn-cookie=1", it.header("Cookie"))
      assertFalse(it.uri.contains("api_key"))
    }
    assertEquals("/segment?signature=a%2Fb", segment.uri)
    val returned = fixture.take()
    assertIdentity(returned, server)
    assertEquals("cdn-cookie=1", returned.header("Cookie"))
    assertFalse(returned.uri.contains("api_key"))
  }

  @Test fun `each redirect hop rechecks the latest session and existing query token`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    val fresh = server.copy(apiToken = "hop-token", deviceId = "hop-device-id", userAgent = "Hop/7.1")
    val session = AtomicReference(server)
    EmbyHttp.installSessionSource { session.get() }
    fixture.enqueue(302, location = "${server.baseUrl}/Items/2?api_key=old", beforeReply = { session.set(fresh) })
    fixture.enqueue()
    assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1").build()))
    assertIdentity(fixture.take(), server)
    val second = fixture.take()
    assertIdentity(second, fresh)
    assertEquals("/emby/Items/2?api_key=hop-token", second.uri)
  }

  @Test fun `reverse proxy redirects keep bound identity across other forwarded server prefixes`() {
    val fixture = fixture()
    val server = row(fixture.url("origin.fixture.test", "/tenant/emby")).copy(
      reverseProxyId = "fixture-relay", reverseProxyUrl = fixture.url("relay.fixture.test", "/forward"),
    )
    val foreign = fixture.url("relay.fixture.test", "/forward/http://other.fixture.test/emby/media")
    fixture.enqueue(302, location = foreign)
    fixture.enqueue(302, location = "${server.effectiveBaseUrl}/Items/2")
    fixture.enqueue()
    assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "${server.effectiveBaseUrl}/Items/1").build()))
    assertIdentity(fixture.take(), server)
    assertIdentity(fixture.take(), server)
    assertIdentity(fixture.take(), server)
  }

  @Test fun `configured loopback Emby is authenticated but unrelated ISO loopback is not`() {
    val emby = fixture()
    val iso = fixture()
    val server = row(emby.url())
    emby.enqueue(302, location = iso.url(path = "/iso"))
    iso.enqueue()
    assertEquals(200, execute(EmbyHttp.shared, EmbyRequestHeaders.request(server, "${server.baseUrl}/Items/1").build()))
    assertIdentity(emby.take(), server)
    val local = iso.take()
    listOf("X-Emby-Token", "X-Emby-Client", "X-Emby-Device-Name", "X-Emby-Device-Id", "X-Emby-Client-Version",
      "X-Emby-Authorization", "Authorization", "Referer").forEach { assertNull(it, local.header(it)) }
    assertNotEquals(server.userAgent, local.header("User-Agent"))
  }

  @Test fun `probe remains compatible and uses the session DB UA when a server is provided`() {
    val fixture = fixture()
    val server = row(fixture.url())
    val fresh = server.copy(apiToken = "probe-token", userAgent = "Probe/8.2")
    EmbyHttp.installSessionSource { fresh }
    fixture.enqueue()
    assertTrue(EmbyClient.probeStreamUrl("${server.baseUrl}/Videos/1/stream", "ignored-UA", server) is StreamProbe.Ok)
    val captured = fixture.take()
    assertIdentity(captured, fresh)
    assertEquals("bytes=0-0", captured.header("Range"))
    fixture.enqueue()
    assertTrue(EmbyClient.probeStreamUrl(fixture.url(path = "/legacy"), "Legacy/1.0") is StreamProbe.Ok)
    assertEquals("Legacy/1.0", fixture.take().header("User-Agent"))
    assertTrue(EmbyClient.probeStreamUrl("content://fixture/item", server = server) is StreamProbe.Ok)
  }

  @Test fun `jsonBody sends actual UTF8 JSON objects for null empty and whitespace payloads`() {
    val fixture = fixture()
    val server = row(fixture.url("emby.fixture.test"))
    listOf(null, "", " \t\r\n", "{\"Name\":\"雪\"}").forEach { payload ->
      fixture.enqueue()
      assertEquals(200, execute(client(), EmbyRequestHeaders.request(server, "${server.baseUrl}/fixture")
        .post(EmbyClient.jsonBody(payload)).build()))
      val captured = fixture.take()
      assertEquals("POST", captured.method)
      assertEquals("application/json; charset=UTF-8", captured.header("Content-Type"))
      assertEquals(if (payload.isNullOrBlank()) "{}" else payload, captured.body)
      assertIdentity(captured, server)
    }
  }

  @Test fun `all eight production POST callsites send JSON media on the wire and preserve payload fields`() = runBlocking {
    val fixture = fixture()
    val server = row(fixture.url())
    val playback = """{"PlaySessionId":"fixture-session","MediaSources":[{"Id":"fixture-source","DirectStreamUrl":"/Videos/1/stream","TranscodingUrl":"/Videos/1/master.m3u8"}]}"""
    fixture.enqueue(body = """{"AccessToken":"fixture-login","User":{"Id":"fixture-user-id"}}""")
    val username = "name\"\\雪"
    val password = "password\n\\\""
    EmbyClient.authenticateByName(server, username, password)
    // Exercise both string and numeric remote-search bodies (a synthetic 400 forces its existing fallback).
    fixture.enqueue(400)
    fixture.enqueue(body = "[]")
    EmbyClient.remoteSearch(server, "Movie", EmbyItemLookupInfo(Name = "fixture-title"), itemId = "42")
    fixture.enqueue(body = playback)
    assertEquals("fixture-session", EmbyClient.requestPlaybackSession(server, "1")?.first)
    fixture.enqueue(204)
    EmbyClient.reportPlaybackStart(server, "1", playSessionId = "fixture-session", mediaSourceId = "fixture-source")
    fixture.enqueue(204)
    EmbyClient.reportPlaybackProgress(server, "1", "fixture-session", 123, mediaSourceId = "fixture-source")
    fixture.enqueue(204)
    EmbyClient.reportPlaybackStopped(server, "1", "fixture-session", 456, mediaSourceId = "fixture-source")
    fixture.enqueue(body = playback)
    assertNotNull(EmbyClient.directStreamUrlForSource(server, "1", "fixture-source"))
    val requests = List(8) { fixture.take() }
    requests.forEach {
      assertEquals("POST", it.method)
      assertEquals("application/json; charset=UTF-8", it.header("Content-Type"))
      assertIdentity(it, server)
      assertTrue(Json.parseToJsonElement(it.body).jsonObject.isNotEmpty())
    }
    val login = Json.parseToJsonElement(requests[0].body).jsonObject
    assertEquals(username, login.getValue("Username").jsonPrimitive.content)
    assertEquals(password, login.getValue("Pw").jsonPrimitive.content)
    assertEquals("42", Json.parseToJsonElement(requests[1].body).jsonObject.getValue("ItemId").jsonPrimitive.content)
    assertTrue(Json.parseToJsonElement(requests[1].body).jsonObject.getValue("ItemId").jsonPrimitive.isString)
    assertFalse(Json.parseToJsonElement(requests[2].body).jsonObject.getValue("ItemId").jsonPrimitive.isString)
    listOf(3, 7).forEach {
      val playbackRequest = Json.parseToJsonElement(requests[it].body).jsonObject
      assertTrue(playbackRequest.getValue("EnableDirectPlay").jsonPrimitive.content.toBoolean())
      assertTrue(playbackRequest.getValue("EnableDirectStream").jsonPrimitive.content.toBoolean())
      assertFalse(playbackRequest.getValue("EnableTranscoding").jsonPrimitive.content.toBoolean())
    }
    listOf(4, 5, 6).forEach {
      val progress = Json.parseToJsonElement(requests[it].body).jsonObject
      assertEquals("fixture-session", progress.getValue("PlaySessionId").jsonPrimitive.content)
      assertEquals("fixture-source", progress.getValue("MediaSourceId").jsonPrimitive.content)
    }
  }

  @Test fun `season episode query keeps season paging and descriptive fields`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = """{"Items":[{"Id":"episode","Type":"Episode","Name":"Title","Overview":"Description","RunTimeTicks":27000000000}],"TotalRecordCount":250}""")
    val result = EmbyClient.getEpisodes(server, "series", "season", startIndex = 100, limit = 100)
    assertEquals("Description", result.Items.single().Overview)
    assertEquals(27000000000L, result.Items.single().RunTimeTicks)
    val request = fixture.take()
    val query = "http://fixture.test${request.uri}".toHttpUrl()
    assertTrue(query.encodedPath.endsWith("/Shows/series/Episodes"))
    assertEquals("season", query.queryParameter("SeasonId"))
    assertEquals("100", query.queryParameter("StartIndex"))
    assertEquals("100", query.queryParameter("Limit"))
    assertTrue(query.queryParameter("Fields")!!.split(',').contains("Overview"))
    assertIdentity(request, server)
  }

  @Test fun `item query filters unsupported wire results and keeps raw consumption and exclusions`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = """{"Items":[{"Id":"pl","Type":"Playlist"},{"Id":"movie","Type":"Movie"},{"Id":"channel","Type":"TvChannel"}],"TotalRecordCount":30,"consumedCount":999}""")
    val result = EmbyClient.getItems(server, excludeItemTypes = listOf("Folder", "CollectionFolder"))
    assertEquals(listOf("movie"), result.Items.map { it.Id })
    assertEquals(3, result.consumedCount)
    assertEquals(30, result.TotalRecordCount)
    val query = "http://fixture.test${fixture.take().uri}".toHttpUrl()
    val exclusions = query.queryParameter("ExcludeItemTypes")!!.split(',')
    assertTrue(exclusions.containsAll(listOf("Folder", "CollectionFolder")))
    assertTrue(exclusions.containsAll(EmbyItemPolicy.unsupportedTypes))
  }

  @Test fun `all item result endpoints hide removed types without changing offset space`() {
    val fixture = fixture()
    val server = row(fixture.url())
    val queries: List<() -> EmbyItemsResult> = listOf(
      { EmbyClient.getVirtualFolders(server) },
      { EmbyClient.getResumeItems(server) },
      { EmbyClient.getItemsByIds(server, listOf("removed", "movie")) },
      { EmbyClient.searchItems(server, "fixture") },
      { EmbyClient.getFavoriteItems(server) },
      { EmbyClient.getSeasons(server, "series") },
      { EmbyClient.getEpisodes(server, "series") },
      { EmbyClient.getSimilarItems(server, "movie") },
      { EmbyClient.getInstantMixItems(server, "movie") },
      { EmbyClient.getNextUpItems(server) },
      { EmbyClient.getCollectionItems(server, "collection") },
      { EmbyClient.getPhotos(server, "photos") },
    )
    queries.forEach { query ->
      fixture.enqueue(body = """{"Items":[{"Id":"removed","Type":"LiveTvProgram"},{"Id":"movie","Type":"Movie"}],"TotalRecordCount":8}""")
      val result = query()
      assertEquals(listOf("movie"), result.Items.map { it.Id })
      assertEquals(2, result.consumedCount)
      assertEquals(8, result.TotalRecordCount)
      fixture.take()
    }
  }

  @Test fun `fully hidden wire page consumes raw count and next page uses that count`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = """{"Items":[{"Id":"pl","Type":"Playlist"},{"Id":"channel","Type":"LiveTvChannel"}],"TotalRecordCount":3}""")
    val hidden = EmbyClient.getItems(server, startIndex = 0)
    assertTrue(hidden.Items.isEmpty())
    assertEquals(2, hidden.consumedCount)
    fixture.take()
    fixture.enqueue(body = """{"Items":[{"Id":"movie","Type":"Movie"}],"TotalRecordCount":3}""")
    val visible = EmbyClient.getItems(server, startIndex = hidden.consumedCount)
    assertEquals(listOf("movie"), visible.Items.map { it.Id })
    val query = "http://fixture.test${fixture.take().uri}".toHttpUrl()
    assertEquals("2", query.queryParameter("StartIndex"))
  }

  @Test fun `latest explicit item array is filtered while unrelated JSON array stays unchanged`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = """[{"Id":"pl","Type":"Playlist"},{"Id":"movie","Type":"Movie"}]""")
    assertEquals(listOf("movie"), EmbyClient.getLatestItems(server).map { it.Id })
    fixture.take()
    fixture.enqueue(body = """["Playlist","TvChannel","Movie"]""")
    assertEquals(listOf("Playlist", "TvChannel", "Movie"), EmbyClient.getJson<List<String>>(server, "/Fixture"))
    fixture.take()
  }

  @Test fun `single item wire read rejects removed item after decode`() {
    val fixture = fixture()
    val server = row(fixture.url())
    fixture.enqueue(body = """{"Id":"channel","Type":"TvChannel"}""")
    val failure = runCatching { EmbyClient.getJson<EmbyItem>(server, "/Users/fixture/Items/channel") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    fixture.take()
  }

  private class Captured(val method: String, val uri: String, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
  }

  private class Reply(val code: Int, val body: String, val location: String?, val beforeReply: () -> Unit)

  private class Fixture {
    private val replies = LinkedBlockingQueue<Reply>()
    private val requests = LinkedBlockingQueue<Captured>()
    private val http = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)

    init {
      http.createContext("/") { exchange ->
        exchange.use {
          requests.add(Captured(exchange.requestMethod, exchange.requestURI.toASCIIString(),
            exchange.requestHeaders.mapValues { it.value.toList() }, exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
          val reply = replies.poll() ?: Reply(400, "Unexpected fixture request", null, {})
          reply.beforeReply()
          reply.location?.let { exchange.responseHeaders.add("Location", it) }
          exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
          val bytes = reply.body.toByteArray(Charsets.UTF_8)
          if (reply.code == 204) exchange.sendResponseHeaders(reply.code, -1)
          else {
            exchange.sendResponseHeaders(reply.code, bytes.size.toLong())
            exchange.responseBody.write(bytes)
          }
        }
      }
      http.start()
    }

    fun url(host: String = "127.0.0.1", path: String = "/emby") = "http://$host:${http.address.port}$path"
    fun enqueue(code: Int = 200, body: String = "{}", location: String? = null, beforeReply: () -> Unit = {}) {
      replies.add(Reply(code, body, location, beforeReply))
    }
    fun take(): Captured = requests.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Expected a local HTTP request")
    fun hasRequest(): Boolean = requests.isNotEmpty()
    fun close() { http.stop(0) }
  }
}
