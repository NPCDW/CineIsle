package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackInfo
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackScrobbleSession
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.domain.emby.playback.ResolvedPlayback
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import app.marlboroadvance.mpvex.domain.postgres.playbackConfigurationForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoembyPlaybackCoordinatorTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
  @After fun resetDispatcher() { Dispatchers.resetMain() }

  @Test fun roundingAndTicksMatchReference() {
    assertEquals(3.0, LoembyPlaybackCoordinator.roundedSeconds(2.5), 0.0)
    assertEquals(2.0, LoembyPlaybackCoordinator.roundedSeconds(2.49), 0.0)
    assertEquals(30_000_000L, LoembyPlaybackCoordinator.positionTicks(2.5))
    assertEquals(0L, LoembyPlaybackCoordinator.positionTicks(Double.NaN))
    assertEquals(0.0, LoembyPlaybackCoordinator.validDuration(0.0), 0.0)
  }

  @Test fun bitrateCacheUsesReferenceClampAndFallback() {
    val defaults = playbackConfigurationForTest()
    assertEquals(300L * 1024 * 1024, defaults.cache.forward.bytesFor(8_000_000L))
    val configured = playbackConfigurationForTest(mapOf(
      "mpv_cache_seconds" to "20",
      "mpv_cache_min_bytes" to "2",
      "mpv_cache_max_bytes" to "10",
    ))
    assertEquals(10L * 1024 * 1024, configured.cache.forward.bytesFor(8_000_000L))
    assertEquals(200L * 1024 * 1024, configured.cache.backward.bytesFor(null))
  }

  @Test fun completeInitializationThenTwoSecondReadThirtySecondReportAndStop() = runTest(dispatcher) {
    val backend = Backend()
    val mpv = Transport()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, mpv, callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    assertTrue(coordinator.active.value!!.initialized)
    assertEquals(1, backend.started)
    assertEquals(listOf("start"), backend.reports)
    assertEquals(listOf(LoembyVersionOption(
      title = "Version / ",
      url = backend.url,
      sourceId = "source",
      hint = "Unknown / 0 MB / 0 bps",
    )), coordinator.active.value!!.versions)
    assertTrue(mpv.commands.any { it.firstOrNull() == "audio-add" && it[1].contains("/stream/audio/") })
    assertTrue(mpv.commands.any { it.firstOrNull() == "sub-add" && it[1].contains("/subtitle/") })
    assertTrue(mpv.commands.any { it == listOf("seek", "12", "absolute") })
    assertEquals("1", mpv.strings["aid"])
    assertEquals("1", mpv.strings["sid"])
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals(1, backend.started)
    advanceTimeBy(30_000)
    runCurrent()
    assertTrue(backend.reports.contains("progress"))
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(listOf("quit"), callbacks.finishes)
    assertEquals(1, backend.stopped)
    assertEquals("stop", backend.reports.last())
    assertEquals(30L, backend.seconds)
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.stopped)
  }

  @Test fun itemHydrationFailureUsesPlaybackSpecAndKeepsLoadedMoviePlaying() = runTest(dispatcher) {
    val backend = Backend().apply { itemFailure = IllegalStateException("offline") }
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    val state = coordinator.active.value
    assertNotNull(state)
    assertTrue(state!!.initialized)
    assertEquals("item", state.item!!.Id)
    assertEquals("Test", state.item!!.Name)
    assertEquals("Movie", state.item!!.Type)
    assertTrue(callbacks.errors.contains("获取 Emby 媒体信息失败，已使用播放入口信息继续播放"))
    assertTrue(callbacks.finishes.isEmpty())
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.stopped)
  }

  @Test fun blankEpisodeFallbackTitleRemainsIdempotent() = runTest(dispatcher) {
    val backend = Backend().apply {
      itemFailure = IllegalStateException("offline")
      fallbackSpec = PlaybackSpec(
        server = EmbyServer(
          id = 1,
          name = "test",
          host = "localhost",
          username = "u",
          password = "",
          remoteId = "server",
          sourceKey = "source",
          sourceRevision = 1,
        ),
        itemId = "episode",
        itemName = "",
        title = "S1E2",
        itemType = "Episode",
        seasonNumber = 1,
        episodeNumber = 2,
      )
    }
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    assertEquals("S1E2", backend.startedItem!!.displayTitle())
    coordinator.finish("quit", owner)
    runCurrent()
  }

  @Test fun seriesHydrationFailureFallsBackAndStillSettlesScrobbleExactlyOnce() = runTest(dispatcher) {
    val backend = Backend().apply {
      itemOverride = EmbyItem(
        Id = "episode",
        Name = "Title",
        Type = "Episode",
        SeriesId = "show",
        SeriesName = "Show",
        ParentIndexNumber = 2,
        IndexNumber = 3,
        ProviderIds = mapOf("Tvdb" to "77"),
      )
      seriesFailure = IllegalStateException("offline secret-title tt123")
    }
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    assertEquals(1, backend.seriesReads)
    assertEquals(1, backend.scrobbleStarted)
    assertNull(backend.scrobbleSeries)
    coordinator.finish("quit", owner)
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.scrobbleStopped)
  }

  @Test fun historyStartFailureWarnsButDoesNotTerminateOrDispatchHistoryStop() = runTest(dispatcher) {
    val backend = Backend().apply { historyStartFailure = IllegalStateException("pg unavailable") }
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    assertTrue(coordinator.active.value!!.initialized)
    assertTrue(callbacks.errors.contains("保存共享播放历史失败"))
    assertTrue(callbacks.finishes.isEmpty())
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(0, backend.stopped)
    assertEquals(listOf("start", "stop"), backend.reports)
  }

  @Test fun embyStartReportFailureWarnsButDoesNotTerminateOrSendStopReport() = runTest(dispatcher) {
    val backend = Backend().apply { reportStartFailure = IllegalStateException("emby unavailable") }
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    assertTrue(coordinator.active.value!!.initialized)
    assertTrue(callbacks.errors.contains("Emby 开始播放上报失败"))
    assertTrue(callbacks.finishes.isEmpty())
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.stopped)
    assertEquals(listOf("start"), backend.reports)
  }

  @Test fun scrobbleStartFailureWarnsButDoesNotTerminateOrSendScrobbleStop() = runTest(dispatcher) {
    val backend = Backend().apply { scrobbleStartFailure = IllegalStateException("tracker unavailable") }
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)

    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()

    assertTrue(coordinator.active.value!!.initialized)
    assertTrue(callbacks.errors.contains("第三方播放同步失败"))
    assertTrue(callbacks.finishes.isEmpty())
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.scrobbleStarted)
    assertEquals(0, backend.scrobbleStopped)
    assertEquals(1, backend.stopped)
  }

  @Test fun wrongOwnerAndWrongPathCannotInitializeOrStopSession() = runTest(dispatcher) {
    val backend = Backend()
    val mpv = Transport()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, mpv, Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(Any())
    coordinator.finish("quit", Any())
    runCurrent()
    assertEquals(0, backend.started)
    mpv.path = "http://127.0.0.1:1234/stream/video/old"
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals(0, backend.started)
    mpv.path = backend.url
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals(1, backend.started)
    coordinator.finish("quit", owner)
    runCurrent()
  }

  @Test fun eofLastEntrySettlesOnceAndRedirectDoesNotSave() = runTest(dispatcher) {
    val backend = Backend()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.eofReached(owner)
    coordinator.eofReached(owner)
    runCurrent()
    assertEquals(listOf("end-of-playlist"), callbacks.finishes)
    assertEquals(1, backend.stopped)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.finish("redirect", owner)
    runCurrent()
    assertEquals(1, backend.stopped)
  }

  @Test fun fullyInitializedMediaTransitionSettlesEverySessionExactlyOnce() = runTest(dispatcher) {
    val backend = Backend()
    val owner = Any()
    val coordinator = coordinator(backend)
    val transport = Transport()

    coordinator.prepare(backend.url, transport, Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals(1, backend.started)
    assertEquals(1, backend.scrobbleStarted)

    coordinator.prepare(backend.url, transport, Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals(2, backend.started)
    assertEquals(1, backend.stopped)
    assertEquals(2, backend.scrobbleStarted)
    assertEquals(1, backend.scrobbleStopped)

    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(2, backend.stopped)
    assertEquals(2, backend.scrobbleStopped)
    assertEquals(listOf("start", "stop", "start", "stop"), backend.reports)
  }

  @Test fun changingMediaDuringInitializationDoesNotSendOldNativeCommands() = runTest(dispatcher) {
    val backend = Backend()
    backend.startGate = CompletableDeferred()
    val mpv = Transport()
    val owner = Any()
    val callbacks = Callbacks()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, mpv, callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.prepare(backend.url, mpv, callbacks, owner)
    runCurrent()
    backend.startGate!!.complete(Unit)
    runCurrent()
    assertFalse(mpv.commands.any { it.firstOrNull() == "audio-add" })
    assertFalse(mpv.commands.any { it.firstOrNull() == "seek" })
    assertEquals(1, backend.stopped)
    coordinator.finish("quit", owner)
    runCurrent()
  }

  @Test fun prematureCachedEofReloadsLocalRouteAndRestoresPositionWithoutSettlement() = runTest(dispatcher) {
    val backend = Backend()
    val mpv = Transport()
    mpv.cacheNode = `is`.xyz.mpv.MPVNode.MapNode(mapOf(
      "eof-cached" to `is`.xyz.mpv.MPVNode.BooleanNode(true),
      "cache-end" to `is`.xyz.mpv.MPVNode.DoubleNode(25.5),
    ))
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, mpv, Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.eofReached(owner)
    runCurrent()
    assertTrue(mpv.replacements.contains(backend.url))
    assertEquals(0, backend.stopped)
    coordinator.fileLoaded(owner)
    runCurrent()
    assertTrue(mpv.commands.any { it == listOf("seek", "25.5", "absolute") })
    assertEquals(1, backend.started)
    coordinator.finish("quit", owner)
    runCurrent()
    assertEquals(1, backend.stopped)
  }

  @Test fun oldEndFileEntryCannotSettleNewNativeEntry() = runTest(dispatcher) {
    val backend = Backend()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), Callbacks(), owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_START_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(7))), owner)
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_END_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(6),
        "reason" to `is`.xyz.mpv.MPVNode.StringNode("eof"))), owner)
    runCurrent()
    assertEquals(0, backend.stopped)
    coordinator.finish("quit", owner)
    runCurrent()
  }

  @Test fun eofOnNonFinalEntryAdvancesExactlyOnce() = runTest(dispatcher) {
    val backend = Backend(playlistIndex = 1, playlistTotal = 2)
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.eofReached(owner)
    coordinator.eofReached(owner)
    runCurrent()
    assertEquals(listOf("next"), callbacks.finishes)
    assertEquals(1, backend.stopped)
  }

  @Test fun stopEndFileSettlesWithoutAdvancingPlaylist() = runTest(dispatcher) {
    val backend = Backend()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_START_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(7))), owner)
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_END_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(7),
        "reason" to `is`.xyz.mpv.MPVNode.StringNode("stop"))), owner)
    runCurrent()
    assertEquals(listOf("stop"), callbacks.finishes)
    assertFalse(callbacks.finishes.any { it == "next" || it == "end-of-playlist" })
    assertEquals(1, backend.stopped)
  }

  @Test fun numericStopEndFileSettlesWithoutAdvancingPlaylist() = runTest(dispatcher) {
    val backend = Backend()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_END_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("reason" to `is`.xyz.mpv.MPVNode.IntNode(2))), owner)
    runCurrent()
    assertEquals(listOf("stop"), callbacks.finishes)
    assertEquals(1, backend.stopped)
  }

  @Test fun staleEntryStopCannotSettleCurrentSession() = runTest(dispatcher) {
    val backend = Backend()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_START_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(8))), owner)
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.event(`is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_END_FILE,
      `is`.xyz.mpv.MPVNode.MapNode(mapOf("playlist_entry_id" to `is`.xyz.mpv.MPVNode.IntNode(7),
        "reason" to `is`.xyz.mpv.MPVNode.StringNode("stop"))), owner)
    runCurrent()
    assertTrue(callbacks.finishes.isEmpty())
    assertEquals(0, backend.stopped)
    coordinator.finish("quit", owner)
    runCurrent()
  }

  @Test fun eofBeforeInitializationCompletesIsSettledAfterStartWrite() = runTest(dispatcher) {
    val backend = Backend()
    backend.startGate = CompletableDeferred()
    val callbacks = Callbacks()
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, Transport(), callbacks, owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    coordinator.eofReached(owner)
    runCurrent()
    assertTrue(callbacks.finishes.isEmpty())
    assertEquals(0, backend.stopped)
    backend.startGate!!.complete(Unit)
    runCurrent()
    assertEquals(listOf("end-of-playlist"), callbacks.finishes)
    assertEquals(1, backend.started)
    assertEquals(1, backend.stopped)
    assertEquals(listOf("start", "stop"), backend.reports)
  }

  @Test fun externalTypeOrdinalMapsToNonContiguousNativeTrackId() = runTest(dispatcher) {
    val backend = Backend()
    val mpv = Transport()
    fun track(id: Long, type: String, external: Boolean) = `is`.xyz.mpv.MPVNode.MapNode(mapOf(
      "id" to `is`.xyz.mpv.MPVNode.IntNode(id), "type" to `is`.xyz.mpv.MPVNode.StringNode(type),
      "external" to `is`.xyz.mpv.MPVNode.BooleanNode(external),
    ))
    mpv.trackNode = `is`.xyz.mpv.MPVNode.ArrayNode(arrayOf(track(4, "video", false), track(12, "audio", true), track(23, "sub", true)))
    val owner = Any()
    val coordinator = coordinator(backend)
    coordinator.prepare(backend.url, mpv, Callbacks(), owner)
    runCurrent()
    coordinator.fileLoaded(owner)
    runCurrent()
    assertEquals("4", mpv.strings["vid"])
    assertEquals("12", mpv.strings["aid"])
    assertEquals("23", mpv.strings["sid"])
    coordinator.finish("quit", owner)
    runCurrent()
  }

  private fun TestScope.coordinator(backend: Backend) = LoembyPlaybackCoordinator(
    backend, backgroundScope, { testScheduler.currentTime },
  )

  private class Callbacks : LoembyPlaybackCallbacks {
    val errors = mutableListOf<String>()
    val finishes = mutableListOf<String>()
    override fun onError(message: String) { errors += message }
    override fun onFinished(reason: String) { finishes += reason }
  }

  private class Transport : LoembyMpvTransport {
    var path = "http://127.0.0.1:1234/play_media/test/0"
    var cacheNode: `is`.xyz.mpv.MPVNode? = null
    var trackNode: `is`.xyz.mpv.MPVNode? = null
    val commands = mutableListOf<List<String>>()
    val replacements = mutableListOf<String>()
    val strings = mutableMapOf<String, String>()
    override fun command(vararg args: String) { commands += args.toList() }
    override fun replaceCurrentPlaylistEntry(url: String) { replacements += url }
    override fun getDouble(name: String): Double? = when (name) { "duration" -> 100.0; "playback-time" -> 25.5; "volume" -> 85.0; else -> null }
    override fun propertyString(name: String): String? = if (name == "path") path else strings[name]
    override fun setDouble(name: String, value: Double) {}
    override fun setString(name: String, value: String) { strings[name] = value }
    override fun setBoolean(name: String, value: Boolean) {}
    override fun getBoolean(name: String): Boolean? = false
    override fun getNode(name: String): `is`.xyz.mpv.MPVNode? = when (name) {
      "demuxer-cache-state" -> cacheNode
      "track-list" -> trackNode
      else -> null
    }
  }

  private class Backend(
    playlistIndex: Int = 1,
    playlistTotal: Int = 1,
  ) : LoembyPlaybackBackend {
    val url = "http://127.0.0.1:1234/play_media/test/0"
    private val server = EmbyServer(id = 1, name = "test", host = "localhost", username = "u", password = "", userId = "user", apiToken = "token", remoteId = "server", sourceKey = "source", sourceRevision = 1)
    private val spec = PlaybackSpec(
      server,
      "item",
      itemName = "Test",
      title = "Test",
      itemType = "Movie",
      positionTicks = 120_000_000L,
      playlistIndex = playlistIndex,
      playlistTotal = playlistTotal,
    )
    private val source = EmbyMediaSource(Id = "source", Name = "Version", MediaStreams = listOf(
      EmbyMediaStream(Index = 0, Type = "Video", IsDefault = true),
      EmbyMediaStream(Index = 1, Type = "Audio", IsExternal = true, IsDefault = true, DisplayTitle = "Audio"),
      EmbyMediaStream(Index = 2, Type = "Subtitle", IsExternal = true, IsDefault = true, DisplayTitle = "Subtitle", DisplayLanguage = "Simplified Chinese"),
    ))
    private val config = playbackConfigurationForTest(source = PostgresSourceToken("source", 1), serverUuid = "server")
    private val resolved = ResolvedPlayback("test", 0, spec, source, LoopbackPlaybackInfo(listOf(source), "session"), 0, "http://127.0.0.1:1234/stream/video/test", config)
    private val item = EmbyItem(Id = "item", Name = "Test", Type = "Movie")
    var itemOverride: EmbyItem? = null
    var itemFailure: Exception? = null
    var seriesFailure: Exception? = null
    var seriesReads = 0
    var historyStartFailure: Exception? = null
    var reportStartFailure: Exception? = null
    var scrobbleStartFailure: Exception? = null
    var fallbackSpec: PlaybackSpec? = null
    var startedItem: EmbyItem? = null
    var started = 0
    var stopped = 0
    var seconds = 0L
    var startGate: CompletableDeferred<Unit>? = null
    var scrobbleStarted = 0
    var scrobbleStopped = 0
    var scrobbleSeries: EmbyItem? = null
    val reports = mutableListOf<String>()
    override fun spec(url: String): PlaybackSpec? = if (url == this.url) fallbackSpec ?: spec else null
    override fun matches(url: String, nativePath: String?): Boolean = nativePath == this.url
    override suspend fun resolve(url: String): ResolvedPlayback = fallbackSpec?.let { resolved.copy(spec = it) } ?: resolved
    override suspend fun item(resolved: ResolvedPlayback): EmbyItem = itemFailure?.let { throw it } ?: itemOverride ?: item
    override suspend fun series(resolved: ResolvedPlayback, id: String): EmbyItem {
      seriesReads++
      seriesFailure?.let { throw it }
      return item
    }
    override suspend fun started(resolved: ResolvedPlayback, item: EmbyItem) {
      started++
      startedItem = item
      historyStartFailure?.let { throw it }
      startGate?.await()
    }
    override suspend fun stopped(resolved: ResolvedPlayback, item: EmbyItem, seconds: Long) { stopped++; this.seconds = seconds }
    override suspend fun report(resolved: ResolvedPlayback, kind: String, ticks: Long) {
      reports += kind
      if (kind == "start") reportStartFailure?.let { throw it }
    }
    override suspend fun audio(resolved: ResolvedPlayback, upstream: String): String = "http://127.0.0.1:1234/stream/audio/a"
    override suspend fun subtitle(resolved: ResolvedPlayback, upstream: String): String = "http://127.0.0.1:1234/subtitle/s"
    override suspend fun scrobbleStart(resolved: ResolvedPlayback, item: EmbyItem, series: EmbyItem?): PlaybackScrobbleSession {
      scrobbleStarted++
      scrobbleSeries = series
      scrobbleStartFailure?.let { throw it }
      return PlaybackScrobbleSession(config, item, null, false, false, false)
    }
    override suspend fun scrobbleStop(session: PlaybackScrobbleSession, position: Double, duration: Double) {
      scrobbleStopped++
    }
    override fun saveVolume(volume: Double) {}
    override fun clearResume(url: String) {}
    override fun release(url: String) {}
  }
}
