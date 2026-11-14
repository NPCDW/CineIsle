package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.junit.Assert.*
import org.junit.Test

class LoembyMediaSelectionTest {
  private val server = EmbyServer(name = "test", host = "example.org", username = "", password = "")
  private val spec = PlaybackSpec(server, "item")
  private fun source(name: String = "", size: Long = 0, w: Int? = null, h: Int? = null) = EmbyMediaSource(
    Name = name, Size = size, MediaStreams = listOf(EmbyMediaStream(Type = "Video", Width = w, Height = h)),
  )

  @Test fun resolutionUsesThresholdOrNotPixels() {
    assertEquals(7, LoembyMediaSelection.resolutionLevel(source(w = 3840, h = 100)))
    assertEquals(7, LoembyMediaSelection.resolutionLevel(source(w = 100, h = 2160)))
    assertEquals(1, LoembyMediaSelection.resolutionLevel(source("8K", w = 100, h = 100)))
    assertEquals(8, LoembyMediaSelection.resolutionLevel(source("4320p", w = 100)))
  }

  @Test fun highBitrateActuallySortsFileSizeAndStableTies() {
    val sources = listOf(source(size = 100, w = 3840, h = 2160), source(size = 200), source(size = 200))
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(sources, spec, autoPolicy = "high-bitrate"))
    assertEquals(0, LoembyMediaSelection.selectSourceIndex(sources, spec))
    assertEquals(0, LoembyMediaSelection.selectSourceIndex(sources, spec, autoPolicy = "unknown"))
  }

  @Test fun resolutionTieUsesSize() {
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(listOf(source("4k", 10), source("2160p", 20)), spec))
  }

  @Test fun explicitRouteManualAndAutomaticSelectionBranches() {
    val sources = listOf(source(size = 10).copy(Id = "a"), source(size = 20).copy(Id = "b"))
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(sources, spec, 2))
    assertEquals(0, LoembyMediaSelection.selectSourceIndex(sources, spec, 3))
    assertEquals(0, LoembyMediaSelection.selectSourceIndex(sources, spec.copy(selectPolicy = "manual", versionSelect = 1)))
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(sources, spec.copy(selectPolicy = "manual", versionSelect = 0)))
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(sources, spec.copy(explicitMediaSourceId = "a"), 2))
    assertEquals(0, LoembyMediaSelection.selectSourceIndex(sources, spec.copy(explicitMediaSourceId = "a"), 0))
    try { LoembyMediaSelection.selectSourceIndex(sources, spec.copy(explicitMediaSourceId = "missing")); fail() }
    catch (_: IllegalArgumentException) { }
  }

  @Test fun directLinkRejectsExactlyLoembyInternalClasses() {
    listOf("localhost", "127.4.5.6", "10.0.0.1", "172.16.0.1", "172.31.0.1", "192.168.0.1", "169.254.1.2",
      "a.local", "a.test", "[::1]").forEach { assertTrue(it, LoembyMediaSelection.isInternalUrl("http://$it/a")) }
    listOf("172.32.0.1", "8.8.8.8", "example.org", "private").forEach { assertFalse(it, LoembyMediaSelection.isInternalUrl("https://$it/a")) }
    val remote = source().copy(IsRemote = true, Path = "https://example.org/a")
    assertTrue(LoembyMediaSelection.supportsDirectLink(remote))
    assertFalse(LoembyMediaSelection.supportsDirectLink(remote.copy(IsRemote = false)))
  }

  @Test fun halfUrlsKeepReferenceCasingFallbackAndItemIdentity() {
    val source = source().copy(Id = "version", ItemId = "other", Container = "mkv")
    val stream = EmbyMediaStream(Index = 6, IsExternal = true)
    assertEquals("/emby/Videos/item/stream.mkv?Static=true&mediaSourceId=version&playSessionId=session",
      LoembyMediaSelection.halfVideoStreamUrl("item", source, "session"))
    assertEquals("/emby/Audio/other/stream.flac?Static=true&AudioStreamIndex=6", LoembyMediaSelection.halfAudioStreamUrl("item", source, stream))
    assertEquals("/emby/Videos/item/version/Subtitles/6/Stream.flac", LoembyMediaSelection.halfSubtitleStreamUrl("item", source, stream))
    assertEquals("/emby/emby/x", LoembyMediaSelection.halfDirectStreamUrl("/emby/x"))
    assertEquals("override", LoembyMediaSelection.selectedVideoUrl(spec.copy(overrideUrl = "override"), source, "s"))
  }

  @Test fun trackDefaultsSubtitleScoringAndManualSentinels() {
    val source = source().copy(MediaStreams = listOf(
      EmbyMediaStream(Type = "Video", IsDefault = true), EmbyMediaStream(Type = "Video", IsDefault = true),
      EmbyMediaStream(Type = "Audio", IsDefault = true), EmbyMediaStream(Type = "Audio", IsDefault = true),
      EmbyMediaStream(Type = "Subtitle", IsDefault = true),
      EmbyMediaStream(Type = "Subtitle", IsExternal = true, DisplayLanguage = "Chinese Simplified"),
      EmbyMediaStream(Type = "Subtitle", IsExternal = true, DisplayLanguage = "Chinese Simplified"),
    ))
    assertEquals(LoembyMediaSelection.TrackSelection(2, 1, 2), LoembyMediaSelection.selectTracks(source, spec))
    assertEquals(LoembyMediaSelection.TrackSelection(-1, 0, 8), LoembyMediaSelection.selectTracks(source,
      spec.copy(selectPolicy = "manual", videoSelect = -1, audioSelect = 0, subtitleSelect = 8)))
    assertEquals("no", LoembyMediaSelection.trackProperty(-1)); assertEquals("auto", LoembyMediaSelection.trackProperty(0))
  }

  @Test fun versionPresentationMatchesMpvLinesForNormalFields() {
    val source = source("Version", 2L * 1024 * 1024 * 1024, 1920, 1080).copy(
      Bitrate = 2_500_000,
      MediaStreams = listOf(EmbyMediaStream(Type = "Video", DisplayTitle = "H264 1080p", Width = 1920, Height = 1080)),
    )

    assertEquals(
      LoembyVersionPresentation("Version / H264 1080p", "1080p / 2.00 GB / 2.5 Mbps"),
      LoembyMediaSelection.versionPresentation(source, 0),
    )
  }

  @Test fun versionPresentationKeepsMpvMissingFieldFormattingAndSuppliesBlankTitleFallback() {
    assertEquals(
      LoembyVersionPresentation("版本 3", "Unknown / 0 MB / 0 bps"),
      LoembyMediaSelection.versionPresentation(EmbyMediaSource(), 2),
    )
    assertEquals(
      LoembyVersionPresentation("Named", "Unknown / 0 MB / 0 bps"),
      LoembyMediaSelection.versionPresentation(EmbyMediaSource(Name = "Named"), 0),
    )
  }

  @Test fun zeroScoreSubtitleFallsBackFirstAndTrackTitlesExcludeExternals() {
    val source = source("version").copy(MediaStreams = listOf(
      EmbyMediaStream(Type = "Video", DisplayTitle = "video"),
      EmbyMediaStream(Type = "Audio", DisplayTitle = "audio"),
      EmbyMediaStream(Type = "Audio", IsExternal = true, DisplayTitle = "external"),
      EmbyMediaStream(Type = "Subtitle", DisplayTitle = "sub"),
      EmbyMediaStream(Type = "Subtitle", IsExternal = true, DisplayTitle = "external sub"),
    ))
    assertEquals("version / video", LoembyMediaSelection.displayTitle(source))
    assertEquals(listOf("audio"), LoembyMediaSelection.trackTitles(source).audio)
    assertEquals(listOf("sub"), LoembyMediaSelection.trackTitles(source).sub)
    assertEquals(1, LoembyMediaSelection.selectTracks(source.copy(MediaStreams = source.MediaStreams!!.take(4)), spec).subtitle)
  }
}
