package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyExternalUrl
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import app.marlboroadvance.mpvex.domain.postgres.playbackConfigurationForTest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure fake transport: no external HTTP, real database or persisted credentials. */
class PlaybackScrobblerTest {
  @Test fun `all services use desktop-compatible movie payloads and settle exactly once`() = runBlocking {
    val f = Fixture()
    val session = f.start()

    f.scrobbler.stop(session, 50.0, 100.0)
    f.scrobbler.stop(session, 99.0, 100.0)

    assertEquals(6, f.requests.size)
    assertTrue(f.requests[0].url.endsWith("/scrobble/start"))
    assertTrue(f.requests[1].url.endsWith("/scrobble/start"))
    assertTrue(f.requests[3].url.endsWith("/scrobble/stop"))
    assertTrue(f.requests[4].url.endsWith("/scrobble/pause"))
    assertEquals(50.0, f.requests[3].json["progress"]!!.jsonPrimitive.double, 0.0)
    assertEquals("tt123", f.requests[0].json["movie"]!!.jsonObject["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)

    val yamtrackStart = buildJsonObject {
      put("event", "start")
      put("external_ids", buildJsonObject { put("imdb_id", "tt123") })
      put("played", false)
      put("type", "Movie")
      put("title", "Movie (2026)")
      put("tv_info", JsonNull)
    }
    assertEquals(yamtrackStart, f.requests[2].json)
    assertEquals(
      JsonObject(yamtrackStart + ("played" to JsonPrimitive(false))),
      f.requests[5].json,
    )
    assertEquals("Bearer access", f.requests[0].headers["Authorization"])
    assertEquals("2", f.requests[0].headers["trakt-api-version"])
    assertEquals("Bearer simkl", f.requests[1].headers["Authorization"])
    assertNull(f.requests[2].headers["Authorization"])
    assertTrue(f.requests.all {
      it.headers.keys.none { key -> key.startsWith("X-Emby") || key == "Device-Id" || key == "Client" }
    })
    assertTrue(f.notices.isEmpty())
  }

  @Test fun `episode payload exactly matches desktop start and completion settlement`() = runBlocking {
    val f = Fixture()
    val item = movie.copy(
      Id = "episode",
      Type = "Episode",
      Name = "Title",
      SeriesId = "show",
      SeriesName = "Show",
      ParentIndexNumber = 2,
      IndexNumber = 3,
      ProviderIds = mapOf("Tvdb" to "77"),
    )
    val series = EmbyItem(
      Id = "show",
      Type = "Series",
      ProviderIds = mapOf("Tmdb" to "42", "Imdb" to "tt900"),
    )

    val session = f.scrobbler.start(f.server(), item, series, f.config())
    f.scrobbler.stop(session, 81.0, 100.0)
    f.scrobbler.stop(session, 99.0, 100.0)

    val trakt = f.requests[0].json
    assertEquals("42", trakt["show"]!!.jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
    assertEquals(2, trakt["episode"]!!.jsonObject["season"]!!.jsonPrimitive.int)
    assertEquals(3, trakt["episode"]!!.jsonObject["number"]!!.jsonPrimitive.int)
    assertEquals("77", trakt["episode"]!!.jsonObject["ids"]!!.jsonObject["tvdb"]!!.jsonPrimitive.content)
    val expectedStart = buildJsonObject {
      put("event", "start")
      put("external_ids", buildJsonObject { put("tvdb_id", "77") })
      put("played", false)
      put("type", "Episode")
      put("title", "Show S2E3. Title")
      put("tv_info", buildJsonObject {
        put("ids", buildJsonObject {
          put("tmdb_id", "42")
          put("imdb_id", "tt900")
        })
        put("season_number", 2)
        put("episode_number", 3)
      })
    }
    assertEquals(expectedStart, f.requests[2].json)
    assertTrue(f.requests[4].url.endsWith("/scrobble/stop"))
    assertEquals(
      JsonObject(expectedStart + ("played" to JsonPrimitive(true))),
      f.requests[5].json,
    )
    assertEquals(6, f.requests.size)
  }

  @Test fun `pure yamtrack payload helper preserves movie and episode layouts`() {
    val movieScrobble = scrobblePayload(movie, null, 0.0)!!
    assertEquals(
      buildJsonObject {
        put("event", "start")
        put("external_ids", buildJsonObject { put("imdb_id", "tt123") })
        put("played", true)
        put("type", "Movie")
        put("title", "Movie (2026)")
        put("tv_info", JsonNull)
      },
      yamtrackPayload(movie, movieScrobble, played = true),
    )

    val episode = movie.copy(
      Type = "Episode",
      Name = "S4E5. Finale",
      SeriesName = "Show",
      ParentIndexNumber = 4,
      IndexNumber = 5,
      ProviderIds = emptyMap(),
    )
    val episodeScrobble = buildJsonObject {
      put("show", buildJsonObject { put("ids", buildJsonObject { put("tmdb", "42") }) })
      put("episode", buildJsonObject { })
      put("progress", 0.0)
    }
    assertEquals(
      buildJsonObject {
        put("event", "start")
        put("external_ids", buildJsonObject { })
        put("played", false)
        put("type", "Episode")
        put("title", "Show S4E5. Finale")
        put("tv_info", buildJsonObject {
          put("ids", buildJsonObject { put("tmdb_id", "42") })
          put("season_number", 4)
          put("episode_number", 5)
        })
      },
      yamtrackPayload(episode, episodeScrobble, played = false),
    )
    assertNull(yamtrackPayload(movie.copy(Type = "Audio"), movieScrobble, played = false))
  }

  @Test fun `yamtrack diagnostics contain only phase status type and presence booleans`() {
    val payload = yamtrackPayload(movie, scrobblePayload(movie, null, 0.0)!!, played = false)!!
    val message = yamtrackDiagnostic("settle", 500, movie, payload)

    assertEquals(
      "YamTrack同步 phase=settle attempt=1 status=500 type=Movie payload=true itemIds=true " +
        "showIds=false season=false episode=false",
      message,
    )
    listOf("Movie (2026)", "tt123", "yam.example", "private-response", "access").forEach {
      assertFalse(message.contains(it))
    }
    assertEquals(
      "YamTrack同步 phase=start attempt=1 status=none type=Movie payload=false itemIds=false " +
        "showIds=false season=false episode=false",
      yamtrackDiagnostic("start", null, movie, null, payloadPresent = false),
    )
  }

  @Test fun `yamtrack start failure retains session and sanitized settlement fails only once`() = runBlocking {
    val f = Fixture()
    f.values["trakt_sync_switch"] = "off"
    f.values["simkl_sync_switch"] = "off"
    f.respond = { ScrobbleResponse(500, "private-response") }

    val session = f.start()
    f.scrobbler.stop(session, 50.0, 100.0)
    f.scrobbler.stop(session, 60.0, 100.0)

    assertEquals(8, f.requests.size)
    assertTrue(f.requests.all { it.json["event"]!!.jsonPrimitive.content == "start" })
    assertTrue(f.requests.all { !it.json["played"]!!.jsonPrimitive.boolean })
    assertEquals(listOf(1000L, 2000L, 4000L, 1000L, 2000L, 4000L), f.waits)
    assertEquals(
      listOf("YamTrack 开始播放同步失败（HTTP 500）", "YamTrack 停止播放同步失败（HTTP 500）"),
      f.notices,
    )
    val diagnostics = f.notices.joinToString("|")
    assertFalse(diagnostics.contains("private-response"))
    assertFalse(diagnostics.contains("yam.example"))
    assertFalse(diagnostics.contains("tt123"))
    assertFalse(diagnostics.contains("Movie"))
  }

  @Test fun `completion requires strictly more than eighty and settlement retains start event`() = runBlocking {
    val atThreshold = Fixture()
    atThreshold.scrobbler.stop(atThreshold.start(), 80.0, 100.0)
    assertTrue(atThreshold.requests[4].url.endsWith("/stop"))
    assertEquals("start", atThreshold.requests[5].json["event"]!!.jsonPrimitive.content)
    assertFalse(atThreshold.requests[5].json["played"]!!.jsonPrimitive.boolean)

    val completed = Fixture()
    completed.scrobbler.stop(completed.start(), 80.01, 100.0)
    assertEquals("start", completed.requests[5].json["event"]!!.jsonPrimitive.content)
    assertTrue(completed.requests[5].json["played"]!!.jsonPrimitive.boolean)
  }

  @Test fun `provider and external IDs combine strict hosts correct slugs query and trailing slash`() {
    val ids = externalIds(movie.copy(ProviderIds = mapOf("IMDB" to "tt777", "Tmdb" to "invalid"), ExternalUrls = listOf(
      EmbyExternalUrl(Url = "https://www.imdb.com/title/tt123/"),
      EmbyExternalUrl(Url = "https://www.themoviedb.org/movie/42-some-title/"),
      EmbyExternalUrl(Url = "https://thetvdb.com/?tab=series&id=99"),
      EmbyExternalUrl(Url = "https://www.imdb.com.evil/title/tt999"))))
    assertEquals("tt777", ids["imdb"]!!.jsonPrimitive.content)
    assertEquals("42", ids["tmdb"]!!.jsonPrimitive.content)
    assertEquals("99", ids["tvdb"]!!.jsonPrimitive.content)
    val traktOnly = externalIds(movie.copy(ProviderIds = emptyMap(), ExternalUrls = listOf(EmbyExternalUrl(Url = "https://trakt.tv/search/imdb/tt555"))))
    assertEquals("tt555", traktOnly["imdb"]!!.jsonPrimitive.content)
  }

  @Test fun `missing identity or unsupported type skips yamtrack without claiming success`() = runBlocking {
    val f = Fixture()
    f.values["trakt_sync_switch"] = "off"
    f.values["simkl_sync_switch"] = "off"

    val missing = f.scrobbler.start(f.server(), movie.copy(ProviderIds = emptyMap()), null, f.config())
    f.scrobbler.stop(missing, 99.0, 100.0)
    val unsupported = f.scrobbler.start(f.server(), movie.copy(Type = "Audio"), null, f.config())
    f.scrobbler.stop(unsupported, 99.0, 100.0)

    assertTrue(f.requests.isEmpty())
    assertTrue(f.notices.isEmpty())
  }

  @Test fun `switch off or missing service configuration does not send`() = runBlocking {
    val f = Fixture()
    f.values["trakt_sync_switch"] = "off"
    f.values.remove("simkl_username")
    f.values["yamtrack_sync_url"] = ""

    f.scrobbler.stop(f.start(), 99.0, 100.0)

    assertTrue(f.requests.isEmpty())
    assertTrue(f.notices.isEmpty())
  }

  @Test fun `refresh expired trakt then stop uses memory rotated token without changing config`() = runBlocking {
    val f = Fixture(); f.values["trakt_expires_in"] = "1"
    val original = f.values.toMap()
    f.respond = { if (it.url.contains("token-exchange")) refresh("rotated") else ScrobbleResponse(200) }
    val session = f.start(); f.scrobbler.stop(session, 90.0, 100.0)
    assertEquals(1, f.requests.count { it.url.contains("token-exchange") })
    val exchange = f.requests.first()
    assertEquals("refresh", exchange.json["refresh_token"]!!.jsonPrimitive.content)
    assertEquals("http://127.0.0.1:123/trakt_auth", exchange.json["redirect_uri"]!!.jsonPrimitive.content)
    assertNull(exchange.headers["Authorization"])
    assertEquals("Bearer rotated", f.requests.first { it.url.endsWith("/scrobble/start") }.headers["Authorization"])
    assertEquals("Bearer rotated", f.requests.first { it.url.endsWith("/scrobble/stop") }.headers["Authorization"])
    assertEquals(original, f.values)
  }

  @Test fun `401 refresh single retry and failed refresh reports sanitized notice while others continue`() = runBlocking {
    val f = Fixture()
    var unauthorized = true
    f.respond = {
      when {
        it.url.contains("token-exchange") -> refresh("new-access")
        it.url.contains("api.trakt") && unauthorized -> { unauthorized = false; ScrobbleResponse(401) }
        else -> ScrobbleResponse(200)
      }
    }
    f.start()
    assertEquals(1, f.requests.count { it.url.contains("token-exchange") })
    assertEquals("Bearer new-access", f.requests.filter { it.url.contains("api.trakt") }.last().headers["Authorization"])
    val g = Fixture(); g.values["trakt_expires_in"] = "1"
    g.respond = { if (it.url.contains("token-exchange")) ScrobbleResponse(403, "private-token") else ScrobbleResponse(200) }
    g.start()
    assertEquals(1, g.notices.size)
    assertTrue(g.notices.single().contains("HTTP 403"))
    assertFalse(g.notices.single().contains("private-token"))
    assertTrue(g.requests.any { it.url.contains("api.simkl") })
    assertTrue(g.requests.any { it.url.contains("yam.example") })
  }

  @Test fun `source revision and desktop credentials invalidate in memory refreshed token`() = runBlocking {
    val f = Fixture(); f.values["trakt_expires_in"] = "1"
    var rotations = 0
    f.respond = { if (it.url.contains("token-exchange")) refresh("rotate-${++rotations}") else ScrobbleResponse(200) }
    f.start(); f.start()
    assertEquals(1, rotations)
    f.source = f.source.copy(revision = 1); f.start()
    assertEquals(2, rotations)
    f.values["trakt_access_token"] = "changed-by-desktop"; f.start()
    assertEquals(3, rotations)
    f.source = PostgresSourceToken("different-database", 1); f.start()
    assertEquals(4, rotations)
  }

  @Test fun `source changes while refresh is in flight prevent sending refreshed token`() = runBlocking {
    val f = Fixture(); f.values["trakt_expires_in"] = "1"
    f.respond = {
      if (it.url.contains("token-exchange")) { f.source = f.source.copy(revision = 2); refresh("must-not-send") }
      else ScrobbleResponse(200)
    }
    val session = f.start()
    assertEquals(1, f.requests.size)
    f.scrobbler.stop(session, 90.0, 100.0)
    assertEquals(1, f.requests.size)
    assertTrue(f.notices.isNotEmpty())
  }

  @Test fun `429 and Yamtrack transient server failures retry with bounded delays`() = runBlocking {
    val f = Fixture()
    f.values["simkl_sync_switch"] = "off"
    f.values["yamtrack_sync_switch"] = "off"
    f.respond = { ScrobbleResponse(429, retryAfter = "99") }
    f.start()
    assertEquals(4, f.requests.size)
    assertEquals(listOf(5000L, 5000L, 5000L), f.waits)
    assertEquals(1, f.notices.size)

    val yamtrack = Fixture()
    yamtrack.values["trakt_sync_switch"] = "off"
    yamtrack.values["simkl_sync_switch"] = "off"
    var attempts = 0
    yamtrack.respond = { if (++attempts < 3) ScrobbleResponse(503, "transient") else ScrobbleResponse(200) }
    yamtrack.start()
    assertEquals(3, yamtrack.requests.size)
    assertTrue(yamtrack.notices.isEmpty())
    assertEquals(listOf(1000L, 2000L), yamtrack.waits)

    val bounded = Fixture()
    bounded.values["trakt_sync_switch"] = "off"
    bounded.values["simkl_sync_switch"] = "off"
    bounded.respond = { ScrobbleResponse(500, "private-response") }
    bounded.start()
    assertEquals(4, bounded.requests.size)
    assertEquals(listOf("YamTrack 开始播放同步失败（HTTP 500）"), bounded.notices)
    assertEquals(listOf(1000L, 2000L, 4000L), bounded.waits)

    val g = Fixture()
    g.values["simkl_sync_switch"] = "off"
    g.values["yamtrack_sync_switch"] = "off"
    g.respond = { throw IllegalStateException("secret-token proxy-password") }
    g.start()
    assertEquals(1, g.requests.size)
    assertFalse(g.notices.single().contains("secret"))
  }

  @Test fun `nan duration and position safe minimum finite progress and no false completion`() = runBlocking {
    val f = Fixture(); f.scrobbler.stop(f.start(), Double.NaN, Double.POSITIVE_INFINITY)
    assertEquals(1.0, f.requests[3].json["progress"]!!.jsonPrimitive.double, 0.0)
    assertFalse(f.requests[5].json["played"]!!.jsonPrimitive.boolean)
  }

  @Test fun `cancellation is propagated no notification no further services`() = runBlocking {
    val f = Fixture(); f.respond = { throw CancellationException() }
    try { f.start(); fail() } catch (_: CancellationException) { }
    assertEquals(1, f.requests.size)
    assertTrue(f.notices.isEmpty())
  }

  @Test fun `notification flow emits errors and invalid TLS preference follows config`() = runBlocking {
    val f = Fixture(); f.values["danger_accept_invalid_certs"] = "true"
    val notices = mutableListOf<String>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) { f.scrobbler.notifications.collect { notices += it } }
    f.respond = { ScrobbleResponse(500) }
    f.start(); yield()
    assertEquals(3, notices.size)
    assertTrue(f.requests.all { it.danger })
    job.cancelAndJoin()
  }

  private class Request(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
    val danger: Boolean,
  ) {
    val json get() = Json.parseToJsonElement(body).jsonObject
  }
  private class Fixture {
    var source = PostgresSourceToken("fake-source", 0)
    val values = linkedMapOf("trakt_username" to "trakt-user", "trakt_access_token" to "access", "trakt_refresh_token" to "refresh", "trakt_expires_in" to "1000000", "trakt_redirect_uri" to "http://127.0.0.1:123/trakt_auth", "simkl_username" to "simkl-user", "simkl_access_token" to "simkl", "yamtrack_sync_url" to "https://yam.example/hook")
    val requests = mutableListOf<Request>(); val notices = mutableListOf<String>(); val waits = mutableListOf<Long>()
    var respond: suspend (Request) -> ScrobbleResponse = { ScrobbleResponse(200) }
    val scrobbler = PlaybackScrobbler(ScrobbleTransport { url, body, headers, danger ->
      val request = Request(url, body, headers, danger); requests += request; respond(request)
    }, { it.source == source }, { notices += it }, { 1000L }, { waits += it })
    fun config() = playbackConfigurationForTest(values.toMap(), source, "server")
    fun server() = EmbyServer(name = "Fake", host = "fake.example", username = "fake", password = "fake", remoteId = "server", sourceKey = source.sourceKey, sourceRevision = source.revision)
    suspend fun start() = scrobbler.start(server(), movie, null, config())
  }
  companion object {
    private val movie = EmbyItem(Id = "movie", Type = "Movie", Name = "Movie", ProductionYear = 2026, ProviderIds = mapOf("Imdb" to "tt123"))
    private fun refresh(token: String) = ScrobbleResponse(200, """{"access_token":"$token","refresh_token":"rotated-refresh","expires_in":1000000,"created_at":1000}""")
  }
}
