package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyLog
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef
import app.marlboroadvance.mpvex.domain.emby.playback.LoembyMediaSelection
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackHttpPool
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackScrobbleSession
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackScrobbler
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.domain.emby.playback.ResolvedPlayback
import app.marlboroadvance.mpvex.domain.postgres.PostgresAutomaticPlaybackWriter
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

interface LoembyMpvTransport {
  fun command(vararg args: String)
  fun replaceCurrentPlaylistEntry(url: String) = command("loadfile", url, "replace")
  fun getDouble(name: String): Double?
  fun propertyString(name: String): String?
  fun setDouble(name: String, value: Double)
  fun setString(name: String, value: String)
  fun setInt(name: String, value: Int) = setString(name, value.toString())
  fun setBoolean(name: String, value: Boolean)
  fun getBoolean(name: String): Boolean?
  fun getNode(name: String): MPVNode? = null
}

interface LoembyPlaybackCallbacks {
  fun onState(state: LoembyPlaybackState) {}
  fun onError(message: String) {}
  fun onFinished(reason: String) {}
}

data class LoembyVersionOption(
  val title: String,
  val url: String,
  val sourceId: String = "",
  val hint: String = "",
)

data class LoembyPlaybackState(
  val url: String,
  val spec: PlaybackSpec,
  val resolved: ResolvedPlayback? = null,
  val item: EmbyItem? = null,
  val series: EmbyItem? = null,
  val versions: List<LoembyVersionOption> = emptyList(),
  val trackTitles: Map<String, String> = emptyMap(),
  val initialized: Boolean = false,
)

/** All native access is on Main; network/database settlement never blocks the JNI event consumer. */
class LoembyPlaybackCoordinator internal constructor(
  private val backend: LoembyPlaybackBackend,
  private val scope: CoroutineScope,
  private val clock: () -> Long,
) {
  constructor(
    server: LoopbackPlaybackServer,
    repository: EmbyRepository,
    writer: PostgresAutomaticPlaybackWriter,
    scrobbler: PlaybackScrobbler,
    context: android.content.Context,
  ) : this(
    AndroidLoembyPlaybackBackend(server, repository, writer, scrobbler, context),
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    System::currentTimeMillis,
  )

  private val mutableActive = MutableStateFlow<LoembyPlaybackState?>(null)
  val active: StateFlow<LoembyPlaybackState?> = mutableActive
  private var current: Session? = null
  private var generation = 0L
  private var lastSettlement: Job? = null
  private val preparation = Mutex()

  init {
    scope.launch {
      backend.notifications.collect { message -> current?.callbacks?.onError(message) }
    }
  }

  private class Session(
    val generation: Long,
    val owner: Any,
    val transport: LoembyMpvTransport,
    val callbacks: LoembyPlaybackCallbacks,
    var state: LoembyPlaybackState,
  ) {
    var finished = false
    var loaded = false
    var eofPending = false
    var initializing: Job? = null
    var polling: Job? = null
    var guard: Job? = null
    var resolved: ResolvedPlayback? = null
    var item: EmbyItem? = null
    var historyStarted = false
    var startDispatched = false
    var scrobble: PlaybackScrobbleSession? = null
    var startedAt = 0L
    var lastSave = 0L
    var position = roundedSeconds(state.spec.positionTicks / 10_000_000.0)
    var duration = 0.0
    var restoringPosition: Double? = null
    var guardPosition: Double? = null
    var savedKeepOpen: String? = null
    var savedKeepOpenPause: String? = null
    var reloading = false
    var finishedAt = 0L
    var precedingSettlement: Job? = null
    var entryId: Long? = null
    var settlement: Job? = null
    val reports = Mutex()
  }

  suspend fun prepare(
    url: String,
    transport: LoembyMpvTransport,
    callbacks: LoembyPlaybackCallbacks,
    owner: Any,
  ) = withContext(Dispatchers.Main.immediate) {
    preparation.withLock {
      val spec = backend.spec(url) ?: error("播放地址不是当前进程注册的本地 Emby 播放地址")
      val previous = current
      previous?.let { terminate(it, "stop", notifyFinished = false) }
      val session = Session(++generation, owner, transport, callbacks, LoembyPlaybackState(url, spec))
      session.precedingSettlement = previous?.settlement ?: lastSettlement
      current = session
      backend.volume()?.let { transport.setDouble("volume", it) }
      publish(session)
      // Resolving here and the HTTP route share a single-flight cache. It cannot create another core.
      scope.launch {
        try {
          val resolved = backend.resolve(url)
          if (!owns(session)) return@launch
          session.resolved = resolved
          session.state = session.state.copy(resolved = resolved)
          configureCache(session, resolved)
          publish(session)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (owns(session)) {
            session.callbacks.onError("获取 Emby 播放信息失败，请检查服务器连接")
            terminate(session, "error")
          }
        }
      }
    }
  }

  fun fileLoaded(owner: Any) {
    val session = current?.takeIf { it.owner === owner && !it.finished } ?: return
    if (!backend.matches(session.state.url, session.transport.propertyString("path"))) return
    if (session.reloading) {
      session.reloading = false
      disengageGuard(session)
      session.restoringPosition?.let { session.transport.command("seek", it.toString(), "absolute") }
      session.restoringPosition = null
      session.transport.setBoolean("pause", false)
      return
    }
    if (session.loaded) return
    session.loaded = true
    session.startedAt = clock()
    session.lastSave = clock()
    session.initializing = scope.launch {
      try {
        val resolved = session.resolved ?: backend.resolve(session.state.url)
        if (!owns(session)) return@launch
        session.resolved = resolved
        configureCache(session, resolved)
        val item = try {
          backend.item(resolved)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          logHydrationFailure("item", failure)
          session.callbacks.onError("获取 Emby 媒体信息失败，已使用播放入口信息继续播放")
          fallbackItem(resolved.spec)
        }
        if (!owns(session)) return@launch
        session.item = item
        session.state = session.state.copy(item = item)
        publish(session)
        session.precedingSettlement?.join()
        if (!owns(session)) return@launch
        // Once a start write has been dispatched, its result must be joined before settlement.
        withContext(NonCancellable) {
          session.historyStarted = runStartEffect(session, "保存共享播放历史失败") {
            backend.started(resolved, item)
          }
        }
        if (!owns(session)) return@launch
        val versions = resolved.info.MediaSources.mapIndexed { index, source ->
          val selection = if (resolved.mediaSourceSelect == 0 && index == resolved.sourceIndex) 0 else index + 1
          val presentation = LoembyMediaSelection.versionPresentation(source, index)
          LoembyVersionOption(
            presentation.title,
            session.state.url.substringBeforeLast('/') + "/$selection",
            source.Id.orEmpty(),
            presentation.details,
          )
        }
        session.state = session.state.copy(resolved = resolved, versions = versions)
        publish(session)
        session.transport.setString("force-media-title", resolved.spec.title)
        if (resolved.spec.positionTicks > 0) {
          session.transport.command("seek", (resolved.spec.positionTicks / 10_000_000L).toString(), "absolute")
        }
        val titleMap = linkedMapOf<String, String>()
        var audio = 0
        var subtitle = 0
        var video = 0
        for (stream in resolved.source.MediaStreams.orEmpty()) {
          val type = stream.Type
          val ordinal = when (type) {
            "Audio" -> ++audio
            "Subtitle" -> ++subtitle
            "Video" -> ++video
            else -> continue
          }
          val key = when (type) { "Audio" -> "audio"; "Subtitle" -> "sub"; else -> "video" }
          titleMap["$key:$ordinal"] = stream.DisplayTitle.orEmpty()
          if (stream.IsExternal != true) continue
          val url = when (type) {
            "Audio" -> backend.audio(resolved, LoembyMediaSelection.halfAudioStreamUrl(resolved.spec.itemId, resolved.source, stream))
            "Subtitle" -> backend.subtitle(resolved, LoembyMediaSelection.halfSubtitleStreamUrl(resolved.spec.itemId, resolved.source, stream))
            else -> continue
          }
          if (!owns(session)) return@launch
          session.transport.command(
            if (type == "Audio") "audio-add" else "sub-add", url,
            if (type == "Audio") "auto" else "select", stream.DisplayTitle.orEmpty(),
          )
        }
        if (!owns(session)) return@launch
        var trackNodes = nativeTracks(session)
        val expectedCount = resolved.source.MediaStreams.orEmpty().count { it.Type in setOf("Video", "Audio", "Subtitle") }
        if (trackNodes.isNotEmpty()) {
          repeat(40) {
            if (trackNodes.size < expectedCount && owns(session)) {
              delay(50)
              trackNodes = nativeTracks(session)
            }
          }
        }
        if (!owns(session)) return@launch
        val mappedTitles = if (trackNodes.isEmpty()) titleMap else mapTrackTitles(resolved, trackNodes)
        val selection = LoembyMediaSelection.selectTracks(resolved.source, resolved.spec)
        session.transport.setString("vid", nativeTrackProperty(selection.video, "video", resolved, trackNodes))
        session.transport.setString("aid", nativeTrackProperty(selection.audio, "audio", resolved, trackNodes))
        session.transport.setString("sid", nativeTrackProperty(selection.subtitle, "sub", resolved, trackNodes))
        session.duration = validDuration(session.transport.getDouble("duration"))
        session.reports.withLock {
          session.startDispatched = runStartEffect(session, "Emby 开始播放上报失败") {
            backend.report(resolved, "start", resolved.spec.positionTicks)
          }
        }
        if (!owns(session)) return@launch
        session.transport.command("print-text", "Emby播放初始化完成")
        session.state = session.state.copy(trackTitles = mappedTitles, initialized = true)
        backend.clearResume(session.state.url)
        publish(session)
        beginPolling(session)
        beginGuard(session)
        if (session.eofPending || session.transport.getBoolean("eof-reached") == true) {
          session.eofPending = false
          if (!tryRecover(session, true)) {
            terminate(session, "eof")
            return@launch
          }
        }
        val seriesId = item.SeriesId
        val series = if (seriesId.isNullOrBlank()) null else try {
          backend.series(resolved, seriesId)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          logHydrationFailure("series", failure)
          null
        }
        if (!owns(session)) return@launch
        session.state = session.state.copy(series = series)
        publish(session)
        // Only a successfully returned start session is eligible for a matching stop.
        session.scrobble = try {
          withContext(NonCancellable) { backend.scrobbleStart(resolved, item, series) }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          session.callbacks.onError("第三方播放同步失败")
          null
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (owns(session)) {
          session.callbacks.onError("Emby 播放初始化失败，请检查服务器和播放历史连接")
          terminate(session, "error")
        }
      }
    }
  }

  fun event(eventId: Int, data: MPVNode, owner: Any) {
    val session = current?.takeIf { it.owner === owner && !it.finished } ?: return
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
        session.entryId = runCatching {
          val map = data.asMap()
          (map?.get("playlist_entry_id") ?: map?.get("playlist-entry-id"))?.asInt()
        }.getOrNull()
      }
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> fileLoaded(owner)
      MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
        val entry = runCatching {
          val map = data.asMap()
          (map?.get("playlist_entry_id") ?: map?.get("playlist-entry-id"))?.asInt()
        }.getOrNull()
        if (entry != null && session.entryId != null && entry != session.entryId) return
        val reason = endReason(data)
        if (!session.loaded && reason in setOf("stop", "redirect", "eof")) return
        if (session.reloading && reason in setOf("stop", "redirect")) return
        if (reason == "eof" && tryRecover(session, true)) return
        terminate(session, reason)
      }
    }
  }

  fun eofReached(owner: Any) {
    val session = current?.takeIf { it.owner === owner && !it.finished && it.loaded } ?: return
    if (!session.state.initialized) {
      session.eofPending = true
      return
    }
    if (!tryRecover(session, true)) terminate(session, "eof")
  }

  fun propertyChanged(name: String, value: Double, owner: Any) {
    if (name == "volume" && current?.owner === owner && value.isFinite() && value >= 0) backend.saveVolume(value)
  }

  suspend fun finish(reason: String = "quit", owner: Any) = withContext(Dispatchers.Main.immediate) {
    current?.takeIf { it.owner === owner }?.let { terminate(it, reason) }
  }

  private fun owns(session: Session): Boolean = current === session && !session.finished && generation == session.generation

  private fun publish(session: Session) {
    if (!owns(session)) return
    mutableActive.value = session.state
    session.callbacks.onState(session.state)
  }

  private data class NativeTrack(val id: Long, val type: String, val external: Boolean)

  private fun nativeTracks(session: Session): List<NativeTrack> = runCatching {
    session.transport.getNode("track-list")?.asArray()?.mapNotNull { node ->
      val map = node.asMap() ?: return@mapNotNull null
      val id = map["id"]?.asInt() ?: return@mapNotNull null
      val type = map["type"]?.asString() ?: return@mapNotNull null
      NativeTrack(id, type, map["external"]?.asBoolean() == true)
    }.orEmpty()
  }.getOrDefault(emptyList())

  private fun mappedTracks(resolved: ResolvedPlayback, nodes: List<NativeTrack>, type: String): List<Pair<NativeTrack?, String>> {
    val embyType = when (type) { "audio" -> "Audio"; "sub" -> "Subtitle"; else -> "Video" }
    val embedded = nodes.filter { it.type == type && !it.external }.iterator()
    val external = nodes.filter { it.type == type && it.external }.iterator()
    return resolved.source.MediaStreams.orEmpty().filter { it.Type == embyType }.map { stream ->
      val iterator = if (stream.IsExternal == true) external else embedded
      (if (iterator.hasNext()) iterator.next() else null) to stream.DisplayTitle.orEmpty()
    }
  }

  private fun mapTrackTitles(resolved: ResolvedPlayback, nodes: List<NativeTrack>): Map<String, String> = buildMap {
    for (type in listOf("video", "audio", "sub")) for ((node, title) in mappedTracks(resolved, nodes, type)) {
      if (node != null) put("$type:${node.id}", title)
    }
  }

  private fun nativeTrackProperty(selection: Int, type: String, resolved: ResolvedPlayback, nodes: List<NativeTrack>): String {
    if (selection <= 0 || nodes.isEmpty()) return LoembyMediaSelection.trackProperty(selection)
    // Emby ordinals include external tracks, while libmpv appends them to the native list.
    return mappedTracks(resolved, nodes, type).getOrNull(selection - 1)?.first?.id?.toString()
      ?: LoembyMediaSelection.trackProperty(selection)
  }

  private fun configureCache(session: Session, resolved: ResolvedPlayback) {
    if (!owns(session)) return
    session.transport.setString("demuxer-max-bytes", resolved.config.cache.forward.bytesFor(resolved.source.Bitrate).toString())
    session.transport.setString("demuxer-max-back-bytes", resolved.config.cache.backward.bytesFor(resolved.source.Bitrate).toString())
  }

  private fun beginPolling(session: Session) {
    session.polling = scope.launch {
      while (owns(session)) {
        delay(2_000)
        if (!owns(session)) break
        capture(session)
        session.transport.getDouble("volume")?.takeIf { it.isFinite() && it >= 0 }?.let(backend::saveVolume)
        if (clock() - session.lastSave >= 30_000) {
          session.lastSave = clock()
          val ticks = positionTicks(session.position)
          scope.launch {
            session.reports.withLock {
              if (!owns(session)) return@withLock
              runEffect(session, "Emby 播放进度上报失败") { backend.report(session.resolved!!, "progress", ticks) }
            }
          }
        }
      }
    }
  }

  private fun capture(session: Session) {
    session.transport.getDouble("playback-time")?.takeIf { it.isFinite() && it >= 0 }?.let {
      session.position = roundedSeconds(it)
    }
    session.transport.getDouble("duration")?.takeIf { it.isFinite() && it > 0 }?.let { session.duration = it }
  }

  private fun beginGuard(session: Session) {
    session.guard = scope.launch {
      while (owns(session)) {
        delay(500)
        if (!owns(session)) break
        tryRecover(session, session.transport.getBoolean("eof-reached") == true)
      }
    }
  }

  /** Mirrors eof-guard's cache-end/duration comparison; unknown duration is never premature EOF. */
  private fun tryRecover(session: Session, eof: Boolean): Boolean {
    if (!owns(session) || session.reloading) return session.reloading
    val cache = runCatching { session.transport.getNode("demuxer-cache-state")?.asMap() }.getOrNull()
    if (session.guardPosition == null && cache != null) {
      val cachedEof = runCatching { cache["eof-cached"]?.asBoolean() }.getOrNull() == true
      if (cachedEof) {
        val rangeEnd = runCatching {
          cache["seekable-ranges"]?.asArray()?.mapNotNull { range ->
            runCatching { range.asMap()?.get("end")?.asDouble() }.getOrNull()
          }?.maxOrNull()
        }.getOrNull()
        val end = rangeEnd ?: runCatching { cache["cache-end"]?.asDouble() }.getOrNull()
        val duration = session.transport.getDouble("duration") ?: session.duration
        if (end != null && duration.isFinite() && duration > 0 && duration - end > 2.0) {
          session.guardPosition = end
          session.savedKeepOpen = session.transport.propertyString("keep-open")
          session.savedKeepOpenPause = session.transport.propertyString("keep-open-pause")
          session.transport.setString("keep-open", "always")
          session.transport.setString("keep-open-pause", "yes")
        }
      }
    }
    val end = session.guardPosition ?: return false
    val time = session.transport.getDouble("playback-time") ?: session.position
    if ((eof && time >= end - 0.5) || time > end + 0.5) {
      session.reloading = true
      session.restoringPosition = time
      session.transport.command("show-text", "视频流意外中断，正在重新加载…", "10000")
      // Replace only this native entry so version changes and EOF recovery retain the queue.
      session.transport.replaceCurrentPlaylistEntry(session.state.url)
    }
    return true
  }

  private fun disengageGuard(session: Session) {
    session.savedKeepOpen?.let { session.transport.setString("keep-open", it) }
    session.savedKeepOpenPause?.let { session.transport.setString("keep-open-pause", it) }
    session.savedKeepOpen = null
    session.savedKeepOpenPause = null
    session.guardPosition = null
  }

  private fun terminate(session: Session, reason: String, notifyFinished: Boolean = true) {
    if (session.finished) return
    if (owns(session)) {
      if (session.loaded) capture(session)
      disengageGuard(session)
      current = null
      mutableActive.value = null
    }
    session.finishedAt = clock()
    session.finished = true
    session.polling?.cancel()
    session.guard?.cancel()
    session.initializing?.cancel()
    backend.release(session.state.url)
    val finalReason = if (reason == "eof") {
      if (session.state.spec.playlistIndex >= session.state.spec.playlistTotal) "end-of-playlist" else "next"
    } else {
      reason
    }
    session.settlement = scope.launch {
      session.initializing?.join()
      val resolved = session.resolved
      val item = session.item
      if (reason != "redirect" && resolved != null) {
        session.reports.withLock {
          if (session.historyStarted && item != null) {
            val duration = ((session.finishedAt - session.startedAt) / 1_000).coerceAtLeast(0)
            runEffect(session, "保存共享播放历史失败") { backend.stopped(resolved, item, duration) }
          }
          session.scrobble?.let { scrobble ->
            runEffect(session, "第三方播放同步失败") { backend.scrobbleStop(scrobble, session.position, session.duration) }
          }
          if (session.startDispatched) {
            runEffect(session, "Emby 停止播放上报失败") { backend.report(resolved, "stop", positionTicks(session.position)) }
          }
        }
      }
    }
    lastSettlement = session.settlement
    if (notifyFinished) session.callbacks.onFinished(finalReason)
  }

  private suspend fun runStartEffect(
    session: Session,
    failure: String,
    block: suspend () -> Unit,
  ): Boolean = try {
    block()
    true
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    session.callbacks.onError(failure)
    false
  }

  private suspend fun runEffect(session: Session, failure: String, block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { session.callbacks.onError(failure) }
  }

  private fun logHydrationFailure(operation: String, failure: Exception) {
    val stage = when (failure) {
      is LoembyItemResponseException -> "http status=${failure.status}"
      is kotlinx.serialization.SerializationException -> "json status=2xx"
      is IOException -> "transport"
      else -> "client"
    }
    EmbyLog.warn(
      "播放元数据补取失败 operation=$operation stage=$stage error=${failure.javaClass.simpleName}",
    )
  }

  companion object {
    internal fun fallbackItem(spec: PlaybackSpec): EmbyItem = EmbyItem(
      Id = spec.itemId,
      Name = spec.itemName.ifBlank { spec.title },
      Type = spec.itemType,
      ParentIndexNumber = spec.seasonNumber,
      IndexNumber = spec.episodeNumber,
      SeriesName = spec.seriesName,
      SeriesId = spec.seriesId,
      RunTimeTicks = spec.runTimeTicks,
      ImageTags = spec.primaryImageTag?.let { mapOf("Primary" to it) }.orEmpty(),
      BackdropImageTags = listOfNotNull(spec.backdropImageTag),
      PrimaryImageItemId = spec.primaryImageItemId,
      PrimaryImageTag = spec.primaryImageTag,
    )

    internal fun roundedSeconds(value: Double): Double = if (value.isFinite() && value >= 0) {
      BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toDouble()
    } else 0.0

    internal fun positionTicks(value: Double): Long = BigDecimal.valueOf(roundedSeconds(value))
      .multiply(BigDecimal.valueOf(10_000_000L)).min(BigDecimal.valueOf(Long.MAX_VALUE)).toLong()

    internal fun validDuration(value: Double?): Double = value?.takeIf { it.isFinite() && it > 0 } ?: 0.0

    private fun endReason(data: MPVNode): String {
      val node = runCatching { data.asMap()?.get("reason") }.getOrNull()
      val text = runCatching { node?.asString() }.getOrNull()
      if (text in setOf("eof", "stop", "quit", "error", "redirect")) return text!!
      return when (runCatching { node?.asInt() }.getOrNull()) {
        0L -> "eof"; 2L -> "stop"; 3L -> "quit"; 5L -> "redirect"; else -> "error"
      }
    }
  }
}

internal interface LoembyPlaybackBackend {
  val notifications: Flow<String> get() = emptyFlow()
  fun spec(url: String): PlaybackSpec?
  fun matches(url: String, nativePath: String?): Boolean
  suspend fun resolve(url: String): ResolvedPlayback
  suspend fun item(resolved: ResolvedPlayback): EmbyItem
  suspend fun series(resolved: ResolvedPlayback, id: String): EmbyItem
  suspend fun started(resolved: ResolvedPlayback, item: EmbyItem)
  suspend fun stopped(resolved: ResolvedPlayback, item: EmbyItem, seconds: Long)
  suspend fun report(resolved: ResolvedPlayback, kind: String, ticks: Long)
  suspend fun audio(resolved: ResolvedPlayback, upstream: String): String
  suspend fun subtitle(resolved: ResolvedPlayback, upstream: String): String
  suspend fun scrobbleStart(resolved: ResolvedPlayback, item: EmbyItem, series: EmbyItem?): PlaybackScrobbleSession
  suspend fun scrobbleStop(session: PlaybackScrobbleSession, position: Double, duration: Double)
  fun volume(): Double? = null
  fun saveVolume(volume: Double)
  fun clearResume(url: String)
  fun release(url: String)
}

internal class LoembyItemResponseException(val status: Int) : IOException("Emby item response $status")

internal class AndroidLoembyPlaybackBackend(
  private val server: LoopbackPlaybackServer,
  private val repository: EmbyRepository,
  private val writer: PostgresAutomaticPlaybackWriter,
  private val scrobbler: PlaybackScrobbler,
  context: android.content.Context,
) : LoembyPlaybackBackend {
  private val volumePreferences = context.getSharedPreferences("loemby_playback", android.content.Context.MODE_PRIVATE)
  override val notifications: Flow<String> = server.errors.map { it.message }
  override fun spec(url: String): PlaybackSpec? = if (server.isLocalPlayUrl(url)) server.specForUrl(url) else null
  override fun matches(url: String, nativePath: String?): Boolean = server.ownsMediaUrl(url, nativePath)
  override suspend fun resolve(url: String): ResolvedPlayback = server.resolvedForUrl(url)
  override suspend fun item(resolved: ResolvedPlayback): EmbyItem = readItem(resolved, resolved.spec.itemId)
  override suspend fun series(resolved: ResolvedPlayback, id: String): EmbyItem = readItem(resolved, id)

  private suspend fun readItem(resolved: ResolvedPlayback, id: String): EmbyItem = withContext(Dispatchers.IO) {
    val bound = repository.sessionServer(resolved.spec.server)
    val url = (bound.effectiveBaseUrl + "/Users/${bound.userId}/Items/$id").toHttpUrl()
    server.http.execute(
      server = bound, url = url.toString(),
      dangerAcceptInvalidCerts = resolved.config.dangerAcceptInvalidCertificates,
    ).use { response ->
      if (!response.isSuccessful) throw LoembyItemResponseException(response.code)
      enrichPlaybackItem(EmbyClient.decodeResponseJson<EmbyItem>(response.body.string()), resolved.spec)
    }
  }
  private fun enrichPlaybackItem(item: EmbyItem, spec: PlaybackSpec): EmbyItem {
    if (item.Id != spec.itemId) return item
    val imageTags = item.ImageTags.toMutableMap().apply {
      if (this["Primary"].isNullOrBlank() && !spec.primaryImageTag.isNullOrBlank()) {
        put("Primary", spec.primaryImageTag)
      }
    }
    return item.copy(
      Name = item.Name ?: spec.itemName,
      Type = item.Type ?: spec.itemType,
      ParentIndexNumber = item.ParentIndexNumber ?: spec.seasonNumber,
      IndexNumber = item.IndexNumber ?: spec.episodeNumber,
      SeriesName = item.SeriesName ?: spec.seriesName,
      SeriesId = item.SeriesId ?: spec.seriesId,
      RunTimeTicks = item.RunTimeTicks ?: spec.runTimeTicks,
      ImageTags = imageTags,
      BackdropImageTags = item.BackdropImageTags.ifEmpty { listOfNotNull(spec.backdropImageTag) },
      PrimaryImageItemId = item.PrimaryImageItemId ?: spec.primaryImageItemId,
      PrimaryImageTag = item.PrimaryImageTag ?: spec.primaryImageTag,
    )
  }

  override suspend fun started(resolved: ResolvedPlayback, item: EmbyItem) = writer.started(resolved.spec.server, item)
  override suspend fun stopped(resolved: ResolvedPlayback, item: EmbyItem, seconds: Long) = writer.stopped(resolved.spec.server, item, seconds)

  override suspend fun report(resolved: ResolvedPlayback, kind: String, ticks: Long) {
    val path = when (kind) { "start" -> ""; "progress" -> "/Progress"; else -> "/Stopped" }
    val body = buildJsonObject {
      put("ItemId", resolved.spec.itemId)
      put("MediaSourceId", resolved.source.Id.orEmpty())
      put("PlayMethod", "DirectStream")
      put("PlaySessionId", resolved.info.PlaySessionId)
      put("PositionTicks", ticks)
    }
    val url = (resolved.spec.server.effectiveBaseUrl + "/Sessions/Playing$path").toHttpUrl().newBuilder()
    body.forEach { (key, value) -> url.addQueryParameter(key, value.jsonPrimitive.content) }
    withContext(Dispatchers.IO) {
      server.http.execute(
        server = repository.sessionServer(resolved.spec.server), url = url.build().toString(), method = "POST",
        body = body.toString(), kind = LoopbackHttpPool.Kind.API,
        dangerAcceptInvalidCerts = resolved.config.dangerAcceptInvalidCertificates,
      ).use { response -> check(response.isSuccessful) { "Emby playback response ${response.code}" } }
    }
  }

  override suspend fun audio(resolved: ResolvedPlayback, upstream: String): String = server.registerStream(resolved.spec.server, upstream, "audio")
  override suspend fun subtitle(resolved: ResolvedPlayback, upstream: String): String = server.registerSubtitle(resolved.spec.server, upstream)
  override suspend fun scrobbleStart(resolved: ResolvedPlayback, item: EmbyItem, series: EmbyItem?): PlaybackScrobbleSession =
    scrobbler.start(resolved.spec.server, item, series, resolved.config)
  override suspend fun scrobbleStop(session: PlaybackScrobbleSession, position: Double, duration: Double) = scrobbler.stop(session, position, duration)
  override fun volume(): Double? = if (volumePreferences.contains("mpv_volume")) volumePreferences.getFloat("mpv_volume", 100f).toDouble() else null
  override fun saveVolume(volume: Double) { volumePreferences.edit().putFloat("mpv_volume", volume.toFloat()).apply() }
  override fun clearResume(url: String) { server.clearResume(url) }
  override fun release(url: String) { server.release(url) }
}
