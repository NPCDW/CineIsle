package app.marlboroadvance.mpvex.domain.emby.playback

import android.content.Context
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.requireSupportedConnection
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfigurationRepository
import app.marlboroadvance.mpvex.domain.postgres.SubtitleCacheConfiguration
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local capability URLs; start is explicitly called by the application's integration. */
class LoopbackPlaybackServer internal constructor(
  cacheDirectory: java.io.File,
  private val login: suspend (EmbyServer) -> EmbyServer?,
  private val current: (EmbyServer) -> EmbyServer,
  private val snapshot: suspend (EmbyServer) -> PlaybackConfiguration,
) {
  constructor(context: Context, repository: EmbyRepository, configurations: PlaybackConfigurationRepository) :
    this(context.applicationContext.cacheDir, { repository.ensureLoggedIn(it) }, repository::sessionServer,
      { configurations.snapshot(it) })

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val http = LoopbackHttpPool()
  val subtitleCache = LoopbackSubtitleCache(cacheDirectory, http)
  data class PlaybackNetworkError(val status: Int, val message: String)
  private val mutableErrors = MutableSharedFlow<PlaybackNetworkError>(extraBufferCapacity = 32)
  val errors: SharedFlow<PlaybackNetworkError> = mutableErrors
  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
  private val ready = CompletableDeferred<Int>()
  private val started = AtomicBoolean()
  @Volatile private var port: Int = 0
  @Volatile private var lastKnownSubtitleCache = SubtitleCacheConfiguration.DEFAULT
  private data class Session(@Volatile var spec: PlaybackSpec)
  private data class Key(val id: String, val select: Int)
  private data class Upstream(
    val server: EmbyServer, val url: String, val owner: Key? = null,
  )
  private val sessions = ConcurrentHashMap<String, Session>()
  private val requests = ConcurrentHashMap<String, Upstream>()
  private val resolved = ConcurrentHashMap<Key, Deferred<ResolvedPlayback>>()
  private val resolutionLock = Any()
  private val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
    routing {
      get("/play_media/{id}/{media_source_select}") {
        val id = call.parameters["id"].orEmpty()
        val select = call.parameters["media_source_select"]?.toIntOrNull()
        if (select == null || select < 0) {
          call.plain(400, "Invalid media_source_select")
        } else try {
          val result = resolve(id, select)
          call.response.headers.append("Location", result.videoUrl)
          call.rawBytes(ByteArray(0), status = HttpStatusCode.PermanentRedirect)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Throwable) {
          call.plain(503, "play_media: $id $select ${safeFailure(failure)}")
        }
      }
      get("/stream/{types}/{id}") { stream(call, call.parameters["id"].orEmpty()) }
      get("/subtitle/{id}") { subtitle(call, call.parameters["id"].orEmpty()) }
    }
  }

  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch {
      try {
        engine.startSuspend(wait = false)
        port = engine.engine.resolvedConnectors().single().port
        ready.complete(port)
        scope.launch {
          kotlinx.coroutines.delay(60000)
          while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try { subtitleCache.clean(lastKnownSubtitleCache) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { mutableErrors.tryEmit(PlaybackNetworkError(500, "字幕缓存清理失败")) }
            kotlinx.coroutines.delay(LoopbackSubtitleCache.CLEAN_PERIOD_MILLIS)
          }
        }
      } catch (failure: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(failure)
        mutableErrors.tryEmit(PlaybackNetworkError(503, "本地播放服务启动失败"))
      }
    }
  }

  suspend fun awaitReady(): Int { start(); return ready.await() }

  suspend fun register(spec: PlaybackSpec): String {
    require(spec.itemId.isNotBlank()) { "媒体ID不存在" }
    require(spec.playlistIndex in 1..spec.playlistTotal) { "Invalid playlist index" }
    spec.server.requireSupportedConnection()
    val port = awaitReady()
    val id = UUID.randomUUID().toString()
    sessions[id] = Session(spec)
    return "http://127.0.0.1:$port/play_media/$id/0"
  }

  /** Route and file-loaded callers share one POST and one immutable metadata snapshot. */
  suspend fun resolve(id: String, select: Int): ResolvedPlayback {
    require(select >= 0) { "Invalid media_source_select" }
    val session = sessions[id] ?: throw IllegalArgumentException("媒体ID不存在")
    val key = Key(id, select)
    val task = synchronized(resolutionLock) {
      resolved[key] ?: scope.async(start = CoroutineStart.LAZY) { prepare(key, session) }
        .also { resolved[key] = it; it.start() }
    }
    return try {
      task.await().let { result ->
        // A return-to-this-item may clear resume while the request is in flight.
        result.copy(spec = result.spec.copy(positionTicks = session.spec.positionTicks))
      }
    } catch (failure: Throwable) {
      if (task.isCompleted && task.isCancelled) synchronized(resolutionLock) { resolved.remove(key, task) }
      throw failure
    }
  }

  private suspend fun prepare(key: Key, session: Session): ResolvedPlayback {
    val original = session.spec
    val server = login(original.server) ?: throw IOException("emby_server not found")
    server.requireSupportedConnection()
    val config = snapshot(server).also { lastKnownSubtitleCache = it.subtitleCache }
    val isPlayback = config.isPlayback
    val body = buildJsonObject {
      put("UserId", server.userId); put("IsPlayback", isPlayback)
      put("MaxStreamingBitrate", 1400000000); put("MaxStaticBitrate", 1400000000)
      put("EnableDirectPlay", true); put("EnableDirectStream", true); put("EnableTranscoding", false)
      putJsonObject("DeviceProfile") {
        put("DirectPlayProfiles", buildJsonArray {
          add(buildJsonObject { put("Container", ""); put("Type", "Video") })
          add(buildJsonObject { put("Container", ""); put("Type", "Audio") })
        })
      }
    }.toString()
    val text = requestTextWithRedirects(server, "${reverseBase(server)}/emby/Items/${original.itemId}/PlaybackInfo",
      body, config)
    val info = json.decodeFromString<LoopbackPlaybackInfo>(text)
    info.ErrorCode?.let { throw IOException("Emby Playback Info Error: $it") }
    val sourceIndex = LoembyMediaSelection.selectSourceIndex(info.MediaSources, original, key.select,
      config.automaticVersionPolicy)
    val source = info.MediaSources[sourceIndex]
    val spec = original.copy(server = server, positionTicks = session.spec.positionTicks)
    val upstream = LoembyMediaSelection.selectedVideoUrl(spec, source, info.PlaySessionId)
    EmbyMediaSourcePolicy.requirePlayableSource(
      EmbyItem(Id = spec.itemId, MediaSources = listOf(source)), source.Id, initialUrl(server, upstream),
    )
    require(initialUrl(server, upstream).startsWith("http://") || initialUrl(server, upstream).startsWith("https://")) {
      "只支持 Emby HTTP(S) 媒体流"
    }
    // Launch every external subtitle against the pre-play indices before exposing the video redirect.
    for (stream in source.MediaStreams.orEmpty()) if (stream.Type == "Subtitle" && stream.IsExternal == true) {
      val half = LoembyMediaSelection.halfSubtitleStreamUrl(spec.itemId, source, stream)
      subtitleCache.precache(scope, server, initialUrl(server, half), invalidCerts(config)) {
        mutableErrors.tryEmit(PlaybackNetworkError(503, "字幕预缓存失败"))
      }
    }
    current(server) // Reject late results from invalidated credential/source snapshots.
    val video = registerRequest(server, upstream, "video", key, subtitle = false)
    return ResolvedPlayback(key.id, key.select, spec, source, info, sourceIndex, video, config)
  }

  suspend fun resolvedForUrl(url: String): ResolvedPlayback {
    val key = keyForUrl(url) ?: throw IllegalArgumentException("媒体ID不存在")
    return resolve(key.id, key.select)
  }

  fun specForUrl(url: String): PlaybackSpec? = keyForUrl(url)?.let { sessions[it.id]?.spec }
  fun isLocalPlayUrl(url: String): Boolean = playKey(url) != null

  fun ownsMediaUrl(playUrl: String, nativePath: String?): Boolean {
    if (nativePath == null) return false
    val play = playKey(playUrl) ?: return false
    val native = keyForUrl(nativePath) ?: return false
    return play == native
  }

  fun playUrlForNativePath(nativePath: String?): String? {
    val key = nativePath?.let(::keyForUrl) ?: return null
    if (!sessions.containsKey(key.id) || port == 0) return null
    return "http://127.0.0.1:$port/play_media/${key.id}/${key.select}"
  }

  fun release(url: String) {
    val key = keyForUrl(url) ?: return
    synchronized(resolutionLock) {
      resolved.keys.filter { it.id == key.id }.forEach { resolved.remove(it)?.cancel() }
    }
  }

  fun clearResume(url: String) = setResume(url, 0)

  fun setResume(url: String, ticks: Long) {
    require(ticks >= 0) { "Invalid resume position" }
    val key = keyForUrl(url) ?: return
    sessions[key.id]?.let { it.spec = it.spec.copy(positionTicks = ticks) }
    synchronized(resolutionLock) {
      resolved.keys.filter { it.id == key.id }.forEach { cachedKey ->
        val task = resolved[cachedKey] ?: return@forEach
        if (task.isCompleted && !task.isCancelled) {
          @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
          val result = task.getCompleted()
          resolved[cachedKey] = CompletableDeferred(result.copy(spec = result.spec.copy(positionTicks = ticks)))
        }
      }
    }
  }

  suspend fun registerStream(server: EmbyServer, upstream: String, type: String): String =
    registerRequest(server, upstream, type, null, subtitle = false)
  suspend fun registerSubtitle(server: EmbyServer, upstream: String): String =
    registerRequest(server, upstream, "", null, subtitle = true)

  private suspend fun registerRequest(server: EmbyServer, upstream: String, type: String, owner: Key?, subtitle: Boolean): String {
    require(subtitle || type.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid stream type" }
    val port = awaitReady()
    val id = UUID.randomUUID().toString()
    requests[id] = Upstream(server, upstream, owner)
    return "http://127.0.0.1:$port/${if (subtitle) "subtitle/$id" else "stream/$type/$id"}"
  }

  private suspend fun stream(call: ApplicationCall, id: String) {
    val request = requests[id] ?: return call.plain(404, "没有找到对应的流媒体")
    val server: EmbyServer
    val config: PlaybackConfiguration
    try {
      server = current(request.server)
      config = snapshot(server).also { lastKnownSubtitleCache = it.subtitleCache }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { return call.plain(503, "emby_server 不存在") }
    val headers = buildMap {
      call.request.headers["Range"]?.let { put("Range", it) }
      put("icy-metadata", "1"); put("Accept", "*/*")
      put("Accept-Encoding", "deflate, gzip, br, zstd")
    }
    var url = initialUrl(server, request.url)
    var redirects = 0
    var response: Response
    while (true) {
      if (redirects > 10) return call.networkFailure("重定向次数过多")
      try {
        response = http.execute(server, url, kind = LoopbackHttpPool.Kind.STREAM, headers = headers,
          dangerAcceptInvalidCerts = invalidCerts(config))
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Throwable) { return call.networkFailure(safeFailure(failure)) }
      if (response.code !in 300..399) break
      val location = response.header("Location")
      val requestUrl = logicalUpstreamUrl(server, response.request.url.toString()).toHttpUrlOrNull()
      response.close()
      if (location == null) return call.networkFailure("手动重定向失败，媒体流响应没有 Location 头")
      val resolved = requestUrl?.resolve(location)?.toString()
        ?: return call.networkFailure("手动重定向失败，Location 地址无效")
      url = routeUpstream(server, resolved)
      redirects++
    }
    response.use { upstream ->
      if (!upstream.isSuccessful) {
        val errorText = withContext(Dispatchers.IO) { readLossy(upstream) }
        mutableErrors.tryEmit(PlaybackNetworkError(upstream.code, "媒体流响应错误: ${upstream.code} ${upstream.message} ${redactError(errorText, server)}"))
        appendHeaders(call, upstream)
        // axum's non-success tuple contains status+headers only; the drained error body is a notification.
        call.rawOutput(status = HttpStatusCode.fromValue(upstream.code)) { }
        return
      }
      val contentType = upstream.header("Content-Type").orEmpty().trim()
      if (HLS_MIME.any(contentType::startsWith)) {
        val text = withContext(Dispatchers.IO) { readLossy(upstream) }
        val manifestUrl = logicalUpstreamUrl(server, upstream.request.url.toString())
        val rewritten = rewriteHls(text, manifestUrl) { link ->
          registerRequest(server, routeUpstream(server, link), "m3u8", request.owner, subtitle = false)
        }
        call.response.headers.append("Content-Type", contentType)
        call.rawBytes(rewritten.toByteArray(Charsets.UTF_8), status = HttpStatusCode.fromValue(upstream.code))
      } else {
        appendHeaders(call, upstream)
        call.rawOutput(status = HttpStatusCode.fromValue(upstream.code)) {
          withContext(Dispatchers.IO) { upstream.body.byteStream().use { it.copyTo(this@rawOutput) } }
        }
      }
    }
  }

  private suspend fun subtitle(call: ApplicationCall, id: String) {
    val request = requests[id] ?: return call.plain(404, "没有找到对应的流媒体")
    val server = try { current(request.server) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Throwable) { return call.plain(503, "emby_server 不存在") }
    val url = initialUrl(server, request.url)
    val file = subtitleCache.fileForUrl(url)
    if (file.exists()) {
      val input = try { withContext(Dispatchers.IO) { file.inputStream() } }
        catch (failure: Throwable) { return call.plain(500, "从缓存读取失败 ${safeFailure(failure)}") }
      input.use {
        call.rawOutput(status = HttpStatusCode.OK) {
          withContext(Dispatchers.IO) { input.copyTo(this@rawOutput) }
        }
      }
      return
    }
    val config = try { snapshot(server).also { lastKnownSubtitleCache = it.subtitleCache } }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Throwable) { return call.plain(500, "http连接池获取失败 ${safeFailure(failure)}") }
    val response = try { executeFollowingRedirects(server, url, config, LoopbackHttpPool.Kind.IMAGE) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Throwable) { return call.plain(503, safeFailure(failure)) }
    // Miss intentionally does not write a file, unlike the pre-play cache task.
    response.use {
      appendHeaders(call, response)
      call.rawOutput(status = HttpStatusCode.fromValue(response.code)) {
        withContext(Dispatchers.IO) { response.body.byteStream().use { it.copyTo(this@rawOutput) } }
      }
    }
  }

  private suspend fun requestTextWithRedirects(server: EmbyServer, url: String, body: String, config: PlaybackConfiguration): String =
    executeFollowingRedirects(server, url, config, LoopbackHttpPool.Kind.API, body).use {
      if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
      withContext(Dispatchers.IO) { it.body.string() }
    }

  /** reqwest API/image auto redirects retain normal RFC relative semantics, not EmbyHttp corrections. */
  private suspend fun executeFollowingRedirects(
    server: EmbyServer, initial: String, config: PlaybackConfiguration, kind: LoopbackHttpPool.Kind, body: String? = null,
  ): Response {
    var url = initial; var method = if (body == null) "GET" else "POST"; var currentBody = body
    repeat(11) { index ->
      val response = http.execute(
        server,
        url,
        method,
        currentBody,
        kind,
        dangerAcceptInvalidCerts = invalidCerts(config),
      )
      if (response.code !in setOf(301, 302, 303, 307, 308)) return response
      val location = response.header("Location") ?: return response
      if (index == 10) { response.close(); throw IOException("重定向次数过多") }
      val next = response.request.url.resolve(location)?.toString()
      val status = response.code
      response.close()
      url = next ?: throw IOException("Invalid Location")
      if (status == 303 || ((status == 301 || status == 302) && method == "POST")) { method = "GET"; currentBody = null }
    }
    throw IOException("重定向次数过多")
  }

  private fun playKey(url: String): Key? {
    val parts = localParts(url) ?: return null
    if (parts.size != 3 || parts[0] != "play_media" || !sessions.containsKey(parts[1])) return null
    val select = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return Key(parts[1], select)
  }
  private fun keyForUrl(url: String): Key? {
    playKey(url)?.let { return it }
    val parts = localParts(url) ?: return null
    return when {
      parts.size == 3 && parts[0] == "stream" -> requests[parts[2]]?.owner
      parts.size == 2 && parts[0] == "subtitle" -> requests[parts[1]]?.owner
      else -> null
    }
  }
  private fun localParts(url: String): List<String>? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (port == 0 || uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != port ||
      uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    return uri.rawPath.removePrefix("/").split('/')
  }

  private suspend fun ApplicationCall.networkFailure(message: String) {
    mutableErrors.tryEmit(PlaybackNetworkError(503, message))
    plain(503, message)
  }
  private suspend fun ApplicationCall.rawBytes(bytes: ByteArray, status: HttpStatusCode) =
    respond(object : OutgoingContent.ByteArrayContent() {
      override val status = status
      override fun bytes() = bytes
    })

  private suspend fun ApplicationCall.rawOutput(status: HttpStatusCode, write: suspend java.io.OutputStream.() -> Unit) =
    respond(object : OutgoingContent.WriteChannelContent() {
      override val status = status
      override suspend fun writeTo(channel: ByteWriteChannel) {
        withContext(Dispatchers.IO) { write(channel.toOutputStream()) }
      }
    })

  private suspend fun ApplicationCall.plain(status: Int, text: String) =
    rawBytes(text.toByteArray(Charsets.UTF_8), status = HttpStatusCode.fromValue(status))

  /** Integration/tests can stop resources without touching App or shutting down unrelated HTTP. */
  suspend fun stop() {
    engine.stopSuspend(100, 1000)
    scope.cancel()
    http.close()
    sessions.clear(); requests.clear(); resolved.clear()
  }

  companion object {
    private val HLS_MIME = listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl")
    private val ABSOLUTE_LINK = Regex("https?://[^\\s<>\\\"']+")
    private val HLS_URI_ATTRIBUTE = Regex("(?i)(URI\\s*=\\s*\")([^\"]+)(\")")
    private fun invalidCerts(config: PlaybackConfiguration) = config.dangerAcceptInvalidCertificates
    private fun reversePrefix(server: EmbyServer): String =
      if (server.reverseProxyId.isNotBlank() && server.reverseProxyId != "no")
        server.reverseProxyUrl.trim().let { if (it.endsWith('/')) it else "$it/" } else ""
    private fun reverseBase(server: EmbyServer) = reversePrefix(server) + server.baseUrl.removeSuffix("/emby")
    private fun initialUrl(server: EmbyServer, url: String): String = if (url.startsWith("http")) url else reverseBase(server) + url
    private fun routeUpstream(server: EmbyServer, absolute: String): String {
      val prefix = reversePrefix(server)
      if (prefix.isEmpty() || absolute.startsWith(prefix)) return absolute
      val original = server.baseUrl.toHttpUrlOrNull() ?: return absolute
      val target = absolute.toHttpUrlOrNull() ?: return absolute
      return if (original.scheme == target.scheme && original.host == target.host && original.port == target.port) {
        prefix + absolute
      } else absolute
    }
    private fun logicalUpstreamUrl(server: EmbyServer, routed: String): String {
      val prefix = reversePrefix(server)
      return if (prefix.isNotEmpty() && routed.startsWith(prefix)) routed.removePrefix(prefix) else routed
    }
    private fun redactError(text: String, server: EmbyServer): String {
      var result = text
      listOf(server.apiToken, server.password, server.username, server.userId, server.deviceId)
        .filter { it.isNotBlank() }.forEach { result = result.replace(it, "[redacted]") }
      return ABSOLUTE_LINK.replace(result, "[URL]").take(2048)
    }
    private fun safeFailure(failure: Throwable): String = when {
      failure is IllegalArgumentException -> "媒体请求或播放配置无效"
      failure is IOException && failure.message?.startsWith("HTTP ") == true -> failure.message!!
      else -> failure.javaClass.simpleName
    }
    private fun readLossy(response: Response): String {
      val bytes = java.io.ByteArrayOutputStream()
      val input = response.body.byteStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = try { input.read(buffer) } catch (_: IOException) { break }
        if (count < 0) break
        bytes.write(buffer, 0, count)
      }
      return bytes.toByteArray().toString(Charsets.UTF_8)
    }
    private fun appendHeaders(call: ApplicationCall, response: Response) {
      for ((name, value) in response.headers) if (name.lowercase() !in LoopbackHttpPool.HOP_BY_HOP) {
        call.response.headers.append(name, value, safeOnly = false)
      }
    }
    suspend fun rewriteHls(
      text: String,
      manifestUrl: String,
      register: suspend (String) -> String,
    ): String {
      val base = manifestUrl.toHttpUrlOrNull() ?: throw IOException("Invalid HLS manifest URL")
      suspend fun proxy(uri: String): String {
        if (uri.startsWith("data:", ignoreCase = true)) return uri
        val resolved = base.resolve(uri)?.toString() ?: throw IOException("Invalid HLS URI")
        return register(resolved)
      }
      // Rust str::lines drops the final empty line and treats CRLF as one line ending.
      val lines = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
      return lines.map { raw ->
        val line = raw.removeSuffix("\r")
        if (line.isBlank()) line
        else if (!line.startsWith("#")) proxy(line.trim())
        else {
          val result = StringBuilder(line.length)
          var cursor = 0
          for (match in HLS_URI_ATTRIBUTE.findAll(line)) {
            result.append(line, cursor, match.range.first)
            result.append(match.groupValues[1])
            result.append(proxy(match.groupValues[2]))
            result.append(match.groupValues[3])
            cursor = match.range.last + 1
          }
          result.append(line, cursor, line.length).toString()
        }
      }.joinToString("\n")
    }
  }
}
