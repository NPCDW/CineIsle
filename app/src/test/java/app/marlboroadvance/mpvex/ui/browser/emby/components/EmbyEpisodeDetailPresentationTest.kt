package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.*
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.domain.emby.playback.LoembyMediaSelection
import kotlinx.coroutines.test.runTest
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import org.junit.Assert.*
import org.junit.Test

class EmbyEpisodeDetailPresentationTest {
  private val server = EmbyServer(id = 1, name = "fixture", host = "fixture.test", username = "user", password = "", apiToken = "token", userId = "user")
  private val low = EmbyMediaSource(Id = "low", Name = "SD", RunTimeTicks = 600_000_000,
    Size = 700L * 1024 * 1024, Bitrate = 2_500_000,
    MediaStreams = listOf(EmbyMediaStream(Index = 2, Type = "Video", Width = 640, Height = 360),
      EmbyMediaStream(Index = 5, Type = "Audio"), EmbyMediaStream(Index = 9, Type = "Subtitle", IsExternal = true)))
  private val high = EmbyMediaSource(Id = "high", Name = "HD", RunTimeTicks = 1_200_000_000,
    MediaStreams = listOf(EmbyMediaStream(Index = 0, Type = "Video", Width = 1920, Height = 1080)))

  @Test fun initialChoiceUsesLoembyPolicyAndChangingBackRestoresAuto() {
    val sources = listOf(low, high)
    val initial = initialEpisodeSelection(sources, server, "high-resolution")
    assertEquals(2, initial.version)
    assertEquals("auto", episodeSelectionPolicy(initial, initial))
    val manual = changeEpisodeVersion(sources, server, 1)
    assertEquals("manual", episodeSelectionPolicy(manual, initial))
    assertEquals("manual", episodeSelectionPolicy(initial.copy(video = -1), initial))
    assertEquals("auto", episodeSelectionPolicy(changeEpisodeVersion(sources, server, 2), initial))
  }

  @Test fun versionChangeResetsTracksAndOptionsUseTypeOrdinalNotSparseIndex() {
    val choices = changeEpisodeVersion(listOf(low, high), server, 1)
    assertEquals(EpisodeSelection(1, 1, 1, 1), choices)
    assertEquals(listOf(1, -1), episodeTrackOptions(low, "Audio").map { it.value })
    assertTrue(episodeTrackOptions(low, "Subtitle").first().label.endsWith("（外置）"))
    assertEquals(listOf(EpisodeTrackOption("自动", 0)), episodeTrackOptions(high, "Audio"))
    assertEquals(EpisodeSelection(2, 1, 0, 0), changeEpisodeVersion(listOf(low, high), server, 2))
  }

  @Test fun durationComesOnlyFromTheSelectedVersion() {
    assertEquals(formatEmbyDurationFull(low.RunTimeTicks!!), episodeVersionDuration(low))
    assertNotEquals(episodeVersionDuration(low), episodeVersionDuration(high))
    assertNull(episodeVersionDuration(high.copy(RunTimeTicks = null)))
    assertNull(episodeVersionDuration(high.copy(RunTimeTicks = 0)))
  }

  @Test fun versionOptionsReuseTheSharedTwoLinePresentation() {
    val option = episodeVersionOption(low.copy(MediaStreams = listOf(
      EmbyMediaStream(Index = 2, Type = "Video", DisplayTitle = "H264 SD", Width = 640, Height = 360),
    )), 0)
    assertEquals("SD / H264 SD", option.label)
    assertEquals("360p / 700 MB / 2.5 Mbps", option.supportingText)
    assertEquals(1, option.value)

    val missing = episodeVersionOption(EmbyMediaSource(), 1)
    assertEquals("版本 2", missing.label)
    assertEquals("Unknown / 0 MB / 0 bps", missing.supportingText)
  }

  @Test fun versionTitleIsNotTheVideoSelectorLabel() {
    assertEquals("SD / ", episodeVersionOption(low, 0).label)
    assertEquals("版本 2", episodeVersionOption(high.copy(Name = null), 1).label)
    assertTrue(episodeTrackOptions(high, "Video").first().label.contains("1920×1080"))
    assertNull(episodeTrackOptions(high, "Video").first().supportingText)
  }

  @Test fun fullEpisodeTitleUsesSharedFormatWithoutDuplicatingPrefix() {
    assertEquals(
      "S1E2. Title",
      seasonEpisodeTitle(EmbyItem(Type = "Episode", Name = "Title", ParentIndexNumber = 1, IndexNumber = 2)),
    )
    assertEquals(
      "S1E2. Title",
      seasonEpisodeTitle(EmbyItem(Type = "Episode", Name = "S1E2. Title", ParentIndexNumber = 1, IndexNumber = 2)),
    )
  }

  @Test fun directLinkSupportIsDerivedFromCurrentSource() {
    assertTrue(LoembyMediaSelection.supportsDirectLink(low.copy(IsRemote = true, Path = "https://cdn.example.com/video.mkv")))
    assertFalse(LoembyMediaSelection.supportsDirectLink(high))
    assertFalse(LoembyMediaSelection.supportsDirectLink(low.copy(IsRemote = true, Path = "http://192.168.1.2/video.mkv")))
  }

  @Test fun manualChoicesOnlyApplyToCurrentEpisodeAndDoNotLeakToNext() {
    val spec = PlaybackSpec(server, "episode", selectPolicy = "manual", versionSelect = 2,
      videoSelect = 2, audioSelect = 3, subtitleSelect = -1, explicitMediaSourceId = "high", useDirectLink = false,
      overrideUrl = "https://cdn.example.com/cached")
    val current = spec.forPlaylistEntry(true, true)
    assertEquals("manual", current.selectPolicy)
    assertEquals("high", current.explicitMediaSourceId)
    assertEquals(-1, current.subtitleSelect)
    assertNull(current.overrideUrl)
    val next = spec.forPlaylistEntry(false, true)
    assertEquals("auto", next.selectPolicy)
    assertEquals(0, next.audioSelect)
    assertNull(next.explicitMediaSourceId)
    assertEquals(spec, spec.forPlaylistEntry(false, false))
  }

  @Test fun autoChoiceDoesNotBecomeAnExplicitVersion() {
    val auto = PlaybackSpec(server, "episode", explicitMediaSourceId = "low").forPlaylistEntry(true, true)
    assertNull(auto.explicitMediaSourceId)
    assertEquals(1, LoembyMediaSelection.selectSourceIndex(listOf(low, high), auto))
  }

  @Test fun anyAlternativeOptionAllowsSelection() {
    assertTrue(episodeSelectorCanChoose(episodeTrackOptions(low, "Video")))
    assertTrue(episodeSelectorCanChoose(episodeTrackOptions(low, "Audio")))
    assertFalse(episodeSelectorCanChoose(episodeTrackOptions(high, "Subtitle")))
    assertFalse(episodeSelectorCanChoose(listOf(EpisodeTrackOption("版本", 1))))
    val two = low.copy(MediaStreams = low.MediaStreams.orEmpty() + EmbyMediaStream(Index = 12, Type = "Audio"))
    assertTrue(episodeSelectorCanChoose(episodeTrackOptions(two, "Audio")))
    assertTrue(episodeSelectorCanChoose(listOf(EpisodeTrackOption("A", 1), EpisodeTrackOption("B", 2))))
  }

  @Test fun partialProgressControlsResumeAndFromStartMenu() {
    val item = EmbyItem(UserData = EmbyUserData(PlaybackPositionTicks = 300_000_000))
    assertTrue(episodeHasResume(item, low))
    assertFalse(episodeHasResume(item.copy(UserData = EmbyUserData()), low))
    assertFalse(episodeHasResume(item.copy(UserData = EmbyUserData(PlaybackPositionTicks = low.RunTimeTicks!!)), low))
  }

  @Test fun detailInformationIsGroupedByVersionWithSeparateTrackCards() {
    val item = EmbyItem(Id = "episode", RunTimeTicks = 9_999_999_999, MediaSources = listOf(low, high))
    val grouped = buildEmbyVersionMediaInfo(item)
    assertEquals(listOf("SD", "HD"), grouped.map { it.title })
    assertEquals(3, grouped[0].tracks.size)
    assertEquals(1, grouped[1].tracks.size)
    assertEquals(episodeVersionDuration(low), grouped[0].basic.first { it.label == "时长" }.value)
    assertTrue(grouped[0].tracks.last().rows.any { it.label == "外置" && it.value.contains("外置") })
    assertEquals(1, buildEmbyVersionMediaInfo(item.copy(MediaSources = listOf(high.copy(Id = null)))).size)
  }

  @Test fun resolvesParentsByActualTypesNotByCopyingSeasonArtwork() = runTest {
    val episode = EmbyItem(Id = "episode", SeriesId = "series", SeasonId = "season")
    val series = EmbyItem(Id = "series", Type = "Series", ImageTags = mapOf("Logo" to "series-logo"))
    val resolved = resolveEpisodeParents(episode) {
      if (it == "season") EmbyItem(Id = it, Type = "Season", SeriesId = "series") else series
    }
    assertEquals("season", resolved.seasonId)
    assertEquals(series, resolved.series)
  }

  @Test fun currentEpisodeAfterFirstHundredIsLocatedByIdNotEpisodeNumber() = runTest {
    val page = PagedList<EmbyItem>(keyOf = { it.Id ?: it })
    val requests = mutableListOf<Int>()
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = { start, limit ->
      requests += start
      PagedResult((start until minOf(start + limit, 250)).map { EmbyItem(Id = "episode-$it", IndexNumber = 1) }, 250, limit)
    }
    page.reload(loader)
    assertEquals(175, locateEpisodeInSeason(page, "episode-175", loader))
    assertEquals(listOf(0, 100), requests)
    assertTrue(page.hasMore)
  }

  @Test fun switchingSiblingReusesExistingLocatorForNewEpisodeId() = runTest {
    val page = PagedList<EmbyItem>(pageSize = 2, keyOf = { it.Id ?: it })
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = { start, limit ->
      PagedResult((start until minOf(start + limit, 6)).map { EmbyItem(Id = "episode-$it") }, 6, limit)
    }
    page.reload(loader)
    assertEquals(4, locateEpisodeInSeason(page, "episode-4", loader))
    assertEquals("episode-4", page.items[4].Id)
  }

  @Test fun episodeLocateStopsOnFailedAppendWithoutInventingPosition() = runTest {
    val page = PagedList<EmbyItem>(pageSize = 1, keyOf = { it.Id ?: it })
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = { start, limit ->
      if (start > 0) error("unavailable")
      PagedResult(listOf(EmbyItem(Id = "first")), 3, limit)
    }
    page.reload(loader)
    assertEquals(-1, locateEpisodeInSeason(page, "current", loader))
    assertNotNull(page.error)
    assertEquals(listOf("first"), page.items.map { it.Id })
  }

  @Test fun wrongParentTypesDoNotBecomeSeries() = runTest {
    try {
      resolveEpisodeParents(EmbyItem(Id = "episode", ParentId = "folder")) { EmbyItem(Id = it, Type = "Folder") }
      fail("must reject")
    } catch (_: IllegalStateException) { }
  }
}
