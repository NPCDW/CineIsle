package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyLog
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfigurationRepository
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Only memory holds the refreshed Trakt token. This class has no database write or persistence capability. */
class PlaybackScrobbler internal constructor(
  private val transport: ScrobbleTransport,
  private val current: (PlaybackConfiguration) -> Boolean,
  private val notify: (String) -> Unit,
  private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
  private val wait: suspend (Long) -> Unit = { delay(it) },
) {
  constructor(http: LoopbackHttpPool, configs: PlaybackConfigurationRepository, notify: (String) -> Unit = {}) : this(
    ScrobbleTransport { url, body, headers, danger ->
      http.execute(server = null, url = url, method = "POST", body = body,
        kind = LoopbackHttpPool.Kind.API, headers = headers, dangerAcceptInvalidCerts = danger, retry = false).use { response ->
        ScrobbleResponse(response.code, response.body.string(), response.header("Retry-After"))
      }
    }, configs::isCurrent, notify,
  )

  private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 16)
  /** Test/diagnostic mirror; production delivery uses the process-level notifier. */
  val notifications: SharedFlow<String> = _notifications.asSharedFlow()
  private val tokenMutex = Mutex()
  private var tokenSource: PostgresSourceToken? = null
  private var tokenFingerprint: String? = null
  private var memoryToken: TraktMemoryToken? = null
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun start(server: EmbyServer, item: EmbyItem, series: EmbyItem?, configuration: PlaybackConfiguration): PlaybackScrobbleSession {
    val source = PostgresSourceToken(server.sourceKey, server.sourceRevision)
    check(source == configuration.source && server.remoteId == configuration.serverUuid) { "Playback configuration source mismatch" }
    ensureCurrent(configuration)
    val payload = scrobblePayload(item, series?.takeIf { it.Id == item.SeriesId }, 0.0)
    val session = PlaybackScrobbleSession(configuration, item,
      payload,
      configuration.trakt.enabled,
      configuration.simkl.enabled,
      configuration.yamTrack.enabled)
    // Source/revision or desktop credential changes invalidate even a disabled service's old overlay.
    tokenMutex.withLock { alignToken(configuration) }
    if (payload != null) {
      if (session.trakt) attempt("Trakt", "开始播放") { sendTrakt(configuration, payload, "start") }
      if (session.simkl) attempt("Simkl", "开始播放") { sendSimkl(configuration, payload, "start") }
      if (session.yamtrack) sendYamtrack(configuration, item, payload, played = false, phase = "start")
    } else if (session.yamtrack) {
      logYamtrack("start", null, item, payloadPresent = false)
    }
    return session
  }

  suspend fun stop(session: PlaybackScrobbleSession, positionSeconds: Double, durationSeconds: Double) {
    if (!session.stopped.compareAndSet(false, true)) return
    val config = session.configuration
    if (!current(config)) {
      tokenMutex.withLock { memoryToken = null; tokenSource = null; tokenFingerprint = null }
      safeNotify("播放同步失败：PostgreSQL 配置来源已变更")
      return
    }
    val base = session.payload ?: return
    val validDuration = durationSeconds.isFinite() && durationSeconds > 0
    val raw = if (validDuration && positionSeconds.isFinite()) positionSeconds.coerceAtLeast(0.0) / durationSeconds * 100 else 0.0
    val progress = (kotlin.math.floor(raw.coerceIn(0.0, 100.0) * 100) / 100).coerceAtLeast(1.0)
    val payload = JsonObject(base + ("progress" to JsonPrimitive(progress)))
    if (session.trakt) attempt("Trakt", "停止播放") { sendTrakt(config, payload, "stop") }
    if (session.simkl) attempt("Simkl", "停止播放") { sendSimkl(config, payload, if (progress < 80) "pause" else "stop") }
    if (session.yamtrack) {
      sendYamtrack(config, session.item, payload, played = validDuration && progress > 80, phase = "settle")
    }
  }

  private suspend fun attempt(service: String, action: String, block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { reportFailure(service, action, error) }
  }

  private fun reportFailure(service: String, action: String, error: Exception) {
    // Never echo token-exchange bodies, arbitrary exception text, proxy credentials or webhook URLs.
    val status = (error as? ScrobbleFailure)?.status
    val message = "$service ${action}同步失败${status?.let { "（HTTP $it）" }.orEmpty()}"
    EmbyLog.warn("第三方播放同步失败 service=$service action=$action${status?.let { " status=$it" }.orEmpty()}")
    safeNotify(message)
  }
  private fun safeNotify(message: String) {
    _notifications.tryEmit(message)
    runCatching { notify(message) }
  }
  private fun ensureCurrent(config: PlaybackConfiguration) {
    if (!current(config)) throw ScrobbleFailure()
  }

  private suspend fun sendTrakt(config: PlaybackConfiguration, payload: JsonObject, action: String) {
    var token = accessToken(config, false)
    var refreshed = false
    var rateRetries = 0
    while (true) {
      ensureCurrent(config)
      val response = transport.post(
        "$TRAKT_API/scrobble/$action",
        payload.toString(),
        commonHeaders() + mapOf(
          "Authorization" to "Bearer $token",
          "trakt-api-version" to "2",
          "trakt-api-key" to TRAKT_CLIENT_ID,
        ),
        config.dangerAcceptInvalidCertificates,
      )
      ensureCurrent(config)
      if (response.code == 401 && !refreshed) {
        token = accessToken(config, true, token)
        refreshed = true
        continue
      }
      if (response.code == 429 && rateRetries++ < 3) { wait(retryDelay(response.retryAfter)); continue }
      if (response.code !in 200..299) throw ScrobbleFailure(response.code)
      return
    }
  }

  private suspend fun sendSimkl(config: PlaybackConfiguration, payload: JsonObject, action: String) {
    val token = config.simkl.accessToken.takeIf { it.isNotBlank() } ?: throw ScrobbleFailure()
    post(
      config,
      "$SIMKL_API/scrobble/$action",
      payload,
      commonHeaders() + mapOf("Authorization" to "Bearer $token", "simkl-api-key" to SIMKL_CLIENT_ID),
    )
  }

  private suspend fun sendYamtrack(
    config: PlaybackConfiguration,
    item: EmbyItem,
    scrobble: JsonObject,
    played: Boolean,
    phase: String,
  ) {
    val payload = yamtrackPayload(item, scrobble, played)
    if (payload == null) {
      logYamtrack(phase, null, item, payloadPresent = false)
      return
    }
    val url = config.yamTrack.webhookUrl.takeIf { it.isNotBlank() } ?: run {
      logYamtrack(phase, null, item, payload)
      attempt("YamTrack", yamtrackAction(phase)) { throw ScrobbleFailure() }
      return
    }
    var lastAttempt = 0
    try {
      post(
        config,
        url,
        payload,
        commonHeaders(),
        retryTransientServerErrors = true,
      ) { attempt, status ->
        lastAttempt = attempt
        logYamtrack(phase, status, item, payload, attempt = attempt)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if ((error as? ScrobbleFailure)?.status == null) {
        logYamtrack(phase, null, item, payload, attempt = (lastAttempt + 1).coerceAtLeast(1))
      }
      reportFailure("YamTrack", yamtrackAction(phase), error)
    }
  }

  private fun yamtrackAction(phase: String): String = if (phase == "start") "开始播放" else "停止播放"

  private fun logYamtrack(
    phase: String,
    status: Int?,
    item: EmbyItem,
    payload: JsonObject? = null,
    payloadPresent: Boolean = payload != null,
    attempt: Int = 1,
  ) {
    EmbyLog.info(yamtrackDiagnostic(phase, status, item, payload, payloadPresent, attempt))
  }

  private suspend fun post(
    config: PlaybackConfiguration,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
    retryTransientServerErrors: Boolean = false,
    onResponse: (attempt: Int, status: Int) -> Unit = { _, _ -> },
  ): ScrobbleResponse {
    var retries = 0
    while (true) {
      ensureCurrent(config)
      val response = transport.post(url, body.toString(), headers, config.dangerAcceptInvalidCertificates)
      ensureCurrent(config)
      val attempt = retries + 1
      onResponse(attempt, response.code)
      val retryable = response.code == 429 ||
        (retryTransientServerErrors && response.code in TRANSIENT_SERVER_STATUS)
      if (retryable && retries++ < MAX_RETRIES) {
        wait(if (response.code == 429) retryDelay(response.retryAfter) else transientRetryDelay(retries))
        continue
      }
      if (response.code !in 200..299) throw ScrobbleFailure(response.code)
      return response
    }
  }

  private fun alignToken(config: PlaybackConfiguration) {
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(
      config.trakt.fingerprintInput().joinToString("") { value -> "${value.length}:$value" }.toByteArray())
      .joinToString("") { "%02x".format(it) }
    if (tokenSource != config.source || tokenFingerprint != fingerprint) {
      tokenSource = config.source; tokenFingerprint = fingerprint; memoryToken = null
    }
  }

  private suspend fun accessToken(config: PlaybackConfiguration, force: Boolean, rejected: String? = null): String = tokenMutex.withLock {
    ensureCurrent(config)
    alignToken(config)
    val runtime = config.trakt
    val existing = memoryToken ?: TraktMemoryToken(
      runtime.accessToken.takeIf { it.isNotBlank() } ?: throw ScrobbleFailure(),
      runtime.refreshToken, runtime.expiresAtSeconds)
    // Concurrent requests that rejected the same old token share a single refresh.
    if (force && rejected != null && existing.access != rejected) return@withLock existing.access
    if (!force && existing.expiresAt > nowSeconds() + 6 * 60 * 60) return@withLock existing.access
    val redirect = runtime.redirectUri.takeIf { it.isNotBlank() } ?: throw ScrobbleFailure()
    if (existing.refresh.isBlank()) throw ScrobbleFailure()
    val body = buildJsonObject { put("code", JsonNull); put("refresh_token", existing.refresh); put("redirect_uri", redirect) }
    val response = post(config, TRAKT_TOKEN_EXCHANGE, body, commonHeaders())
    val parsed = json.parseToJsonElement(response.body).jsonObject
    val access = parsed["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw ScrobbleFailure()
    val refresh = parsed["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw ScrobbleFailure()
    val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: throw ScrobbleFailure()
    val created = parsed["created_at"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 } ?: throw ScrobbleFailure()
    val expiresAt = try { Math.addExact(created, expiresIn) } catch (_: ArithmeticException) { throw ScrobbleFailure() }
    ensureCurrent(config)
    memoryToken = TraktMemoryToken(access, refresh, expiresAt)
    access
  }

  companion object {
    private const val MAX_RETRIES = 3
    private const val TRAKT_API = "https://api.trakt.tv"
    private const val SIMKL_API = "https://api.simkl.com"
    private const val TRAKT_TOKEN_EXCHANGE = "https://token-exchange.i101.workers.dev/trakt"
    private const val TRAKT_CLIENT_ID = "05521c50a5a5ac1fb238648a15e8da57ea7c708127e49711303c9b9691913572"
    private const val SIMKL_CLIENT_ID = "31fbc68631721a73fdebcfe4987ae1f07ca83363d7f2ffa3f5d6f9f2f836b6df"
    private val TRANSIENT_SERVER_STATUS = setOf(500, 502, 503, 504)
    private fun commonHeaders() = mapOf("User-Agent" to "loembyA/1.0", "Content-Type" to "application/json; charset=UTF-8")
    private fun retryDelay(value: String?): Long = ((value?.toLongOrNull() ?: 1L).coerceIn(1, 5)) * 1000
    private fun transientRetryDelay(retry: Int): Long = 1000L shl (retry - 1).coerceIn(0, 2)
  }
}

class PlaybackScrobbleSession internal constructor(
  internal val configuration: PlaybackConfiguration,
  internal val item: EmbyItem,
  internal val payload: JsonObject?,
  internal val trakt: Boolean,
  internal val simkl: Boolean,
  internal val yamtrack: Boolean,
) {
  internal val stopped = AtomicBoolean(false)
  override fun toString() = "PlaybackScrobbleSession(credentials=[redacted])"
}

internal fun interface ScrobbleTransport {
  suspend fun post(
    url: String,
    body: String,
    headers: Map<String, String>,
    dangerAcceptInvalidCerts: Boolean,
  ): ScrobbleResponse
}
internal class ScrobbleResponse(val code: Int, val body: String = "", val retryAfter: String? = null)
private class ScrobbleFailure(val status: Int? = null) : Exception("Playback synchronization failed")
private class TraktMemoryToken(val access: String, val refresh: String, val expiresAt: Long)

internal fun yamtrackDiagnostic(
  phase: String,
  status: Int?,
  item: EmbyItem,
  payload: JsonObject?,
  payloadPresent: Boolean = payload != null,
  attempt: Int = 1,
): String {
  val externalIds = payload?.get("external_ids") as? JsonObject
  val tvInfo = payload?.get("tv_info") as? JsonObject
  val showIds = tvInfo?.get("ids") as? JsonObject
  val seasonPresent = tvInfo?.get("season_number")?.let { it !is JsonNull } == true
  val episodePresent = tvInfo?.get("episode_number")?.let { it !is JsonNull } == true
  return "YamTrack同步 phase=$phase attempt=$attempt status=${status ?: "none"} type=${yamtrackType(item)} " +
    "payload=$payloadPresent itemIds=${externalIds?.isNotEmpty() == true} " +
    "showIds=${showIds?.isNotEmpty() == true} season=$seasonPresent episode=$episodePresent"
}

internal fun yamtrackPayload(item: EmbyItem, scrobble: JsonObject, played: Boolean): JsonObject? {
  val type = yamtrackType(item) ?: return null
  val itemIds = scrobble[if (type == "Movie") "movie" else "episode"]
    ?.let { it as? JsonObject }
    ?.get("ids")
    ?.let { it as? JsonObject }
    ?: JsonObject(emptyMap())
  val showIds = scrobble["show"]
    ?.let { it as? JsonObject }
    ?.get("ids")
    ?.let { it as? JsonObject }
    ?: JsonObject(emptyMap())
  fun external(ids: JsonObject) = JsonObject(ids.mapKeys { "${it.key}_id" })
  return buildJsonObject {
    // Desktop loemby settles by resending the start event and changing only played.
    put("event", "start")
    put("external_ids", external(itemIds))
    put("played", played)
    put("type", type)
    put("title", if (type == "Movie") {
      "${item.Name.orEmpty()} (${item.ProductionYear ?: 0})"
    } else {
      listOfNotNull(
        item.SeriesName?.trim()?.takeIf { it.isNotEmpty() },
        item.displayTitle().takeIf { it.isNotEmpty() },
      ).joinToString(" ")
    })
    put("tv_info", if (type == "Movie") JsonNull else buildJsonObject {
      put("ids", external(showIds))
      put("season_number", item.ParentIndexNumber?.let(::JsonPrimitive) ?: JsonNull)
      put("episode_number", item.IndexNumber?.let(::JsonPrimitive) ?: JsonNull)
    })
  }
}

private fun yamtrackType(item: EmbyItem): String? = when {
  item.Type.equals("Movie", ignoreCase = true) -> "Movie"
  item.Type.equals("Episode", ignoreCase = true) -> "Episode"
  else -> null
}

internal fun scrobblePayload(item: EmbyItem, series: EmbyItem?, progress: Double): JsonObject? {
  val ids = externalIds(item)
  return when (item.Type) {
    "Movie" -> if (ids.isEmpty()) null else buildJsonObject { put("movie", buildJsonObject { put("ids", ids) }); put("progress", progress) }
    "Episode" -> {
      val show = series?.let(::externalIds)
      val includeShow = !show.isNullOrEmpty() && item.ParentIndexNumber != null && item.IndexNumber != null
      if (!includeShow && ids.isEmpty()) return null
      buildJsonObject {
        if (includeShow) put("show", buildJsonObject { put("ids", show) })
        put("episode", buildJsonObject {
          if (ids.isNotEmpty()) put("ids", ids)
          if (includeShow) { put("season", item.ParentIndexNumber); put("number", item.IndexNumber) }
        })
        put("progress", progress)
      }
    }
    else -> null
  }
}

internal fun externalIds(item: EmbyItem): JsonObject {
  val ids = linkedMapOf<String, JsonElement>()
  fun add(provider: String, value: String?) {
    if (provider !in setOf("imdb", "tmdb", "tvdb") || value.isNullOrBlank() || provider in ids) return
    val valid = if (provider == "imdb") value.matches(Regex("tt[0-9]+")) else value.toLongOrNull()?.let { it > 0 } == true
    if (valid) ids[provider] = JsonPrimitive(value)
  }
  item.ProviderIds.forEach { (key, value) -> add(key.lowercase(), value) }
  item.ExternalUrls.forEach { external ->
    try {
      val uri = URI(external.Url ?: return@forEach)
      val path = uri.path.orEmpty().trimEnd('/').split('/').filter { it.isNotEmpty() }
      when (uri.host?.lowercase()) {
        "imdb.com", "www.imdb.com" -> if (path.size == 2 && path[0] == "title") add("imdb", path[1])
        "themoviedb.org", "www.themoviedb.org" -> if (path.size == 2 && path[0] in setOf("movie", "tv")) add("tmdb", path[1].substringBefore('-'))
        "thetvdb.com", "www.thetvdb.com" -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "id" }
          ?.substringAfter('=')?.let { add("tvdb", URLDecoder.decode(it, "UTF-8")) }
        "trakt.tv", "www.trakt.tv" -> if (path.size == 3 && path[0] == "search") add(path[1], path[2])
      }
    } catch (_: Exception) { /* Unrecognized metadata URL is not a playback failure. */ }
  }
  return JsonObject(ids)
}
