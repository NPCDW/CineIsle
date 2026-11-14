package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EmbySeasonDetailPresentationTest {
  @Test fun legacyEntryOnlyMatchesPlainEpisodeLibraries() {
    assertTrue(isSeasonLibraryEntry(listOf("Episode"), false))
    assertTrue(isSeasonLibraryEntry(listOf("episode"), false))
    assertFalse(isSeasonLibraryEntry(listOf("Episode"), true))
    assertFalse(isSeasonLibraryEntry(listOf("Episode"), false, "person"))
    assertFalse(isSeasonLibraryEntry(listOf("Episode", "Movie"), false))
    assertFalse(isSeasonLibraryEntry(listOf("Season"), false))
    assertFalse(isSeasonLibraryEntry(null, false))
    assertFalse(isSeasonLibraryEntry(emptyList(), false))
  }

  @Test fun parentCandidatesPreferSeriesIdAndIgnoreDuplicatesOrSelf() {
    assertEquals(listOf("series", "parent"), seasonSeriesCandidates(EmbyItem(Id = "season", SeriesId = "series", ParentId = "parent")))
    assertEquals(listOf("series"), seasonSeriesCandidates(EmbyItem(Id = "season", SeriesId = "series", ParentId = "series")))
    assertTrue(seasonSeriesCandidates(EmbyItem(Id = "season", SeriesId = "season", ParentId = " ")).isEmpty())
  }

  @Test fun seriesResolutionUsesSeriesArtworkOwner() = runTest {
    val requests = mutableListOf<String>()
    val series = EmbyItem(Id = "series", Type = "Series", Name = "剧名", ImageTags = mapOf("Banner" to "banner", "Logo" to "logo"))
    val result = resolveSeasonSeries(EmbyItem(Id = "season", SeriesId = "series", ParentId = "parent")) {
      requests += it
      series
    }
    assertEquals(series, result)
    assertEquals(listOf("series"), requests)
    assertEquals("banner", result.ImageTags["Banner"])
  }

  @Test fun fallbackParentMustActuallyBeSeries() = runTest {
    val requests = mutableListOf<String>()
    val result = resolveSeasonSeries(EmbyItem(SeriesId = "wrong", ParentId = "series")) {
      requests += it
      EmbyItem(Id = it, Type = if (it == "series") "Series" else "Folder")
    }
    assertEquals("series", result.Id)
    assertEquals(listOf("wrong", "series"), requests)
  }

  @Test fun failedSeriesLookupCanUseValidatedParent() = runTest {
    val result = resolveSeasonSeries(EmbyItem(SeriesId = "missing", ParentId = "series")) {
      if (it == "missing") error("unavailable")
      EmbyItem(Id = it, Type = "Series")
    }
    assertEquals("series", result.Id)
  }

  @Test fun invalidParentIsNotPassedToEpisodeApi() = runTest {
    try {
      resolveSeasonSeries(EmbyItem(ParentId = "folder")) { EmbyItem(Id = it, Type = "Folder") }
      fail("Should reject non-Series parents")
    } catch (error: IllegalStateException) {
      assertEquals("无法加载所属剧集", error.message)
    }
  }

  @Test fun cancelledSeriesRequestIsNeverConvertedToFallback() = runTest {
    val requests = mutableListOf<String>()
    try {
      resolveSeasonSeries(EmbyItem(SeriesId = "series", ParentId = "parent")) {
        requests += it
        throw CancellationException()
      }
      fail("Should cancel")
    } catch (_: CancellationException) {
      assertEquals(listOf("series"), requests)
    }
  }

  @Test fun titleUsesSeriesNameNeverSeasonName() {
    val season = EmbyItem(Name = "第一季", SeriesName = " 剧名 ")
    assertEquals("完整剧名", seasonSeriesTitle(season, EmbyItem(Name = "完整剧名")))
    assertEquals("剧名", seasonSeriesTitle(season, null))
    assertEquals("剧名", seasonSeriesTitle(season, EmbyItem(Name = " ")))
    assertEquals("剧集", seasonSeriesTitle(season.copy(SeriesName = null), null))
  }

  @Test fun episodeTitlesUseSharedFullTitleAndAvoidDuplicatePrefixes() {
    assertEquals("S1E3. 标题", seasonEpisodeTitle(EmbyItem(Type = "Episode", ParentIndexNumber = 1, IndexNumber = 3, Name = " 标题 ")))
    assertEquals("S1E3. 标题", seasonEpisodeTitle(EmbyItem(Type = "episode", ParentIndexNumber = 1, IndexNumber = 3, Name = "S1E3. 标题")))
    assertEquals("S0E0", seasonEpisodeTitle(EmbyItem(Type = "Episode", ParentIndexNumber = 0, IndexNumber = 0)))
    assertEquals("标题", seasonEpisodeTitle(EmbyItem(Type = "Episode", IndexNumber = 3, Name = "标题")))
    assertEquals("未命名", seasonEpisodeTitle(EmbyItem(Type = "Episode")))
  }

  @Test fun durationHandlesSecondsMinutesAndHoursWithoutInventingMissingValues() {
    fun duration(seconds: Long) = seasonEpisodeDuration(EmbyItem(RunTimeTicks = EmbyTicks.secondsToTicks(seconds)))
    assertEquals("45分钟", duration(2700))
    assertEquals("1小时30分钟", duration(5400))
    assertEquals("1小时", duration(3600))
    assertEquals("45秒", duration(45))
    assertNull(duration(0))
    assertNull(duration(-10))
    assertNull(seasonEpisodeDuration(EmbyItem()))
  }

  @Test fun seasonPagingLoadsMoreThanOneHundredEpisodes() = runTest {
    val starts = mutableListOf<Int>()
    val page = PagedList<EmbyItem>(keyOf = { it.Id ?: it })
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = { start, limit ->
      starts += start
      PagedResult((start until minOf(start + limit, 250)).map { EmbyItem(Id = "$it", Type = "Episode") }, 250, limit)
    }
    page.reload(loader)
    assertEquals(100, page.items.size)
    page.loadMore(loader)
    page.loadMore(loader)
    assertEquals(250, page.items.size)
    assertEquals(listOf(0, 100, 200), starts)
    assertFalse(page.hasMore)
  }

  @Test fun filteredEpisodesAdvanceTheRawCursorAndRetryKeepsLoadedItems() = runTest {
    val page = PagedList<EmbyItem>(pageSize = 2, keyOf = { it.Id ?: it })
    var failNext = true
    val starts = mutableListOf<Int>()
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = { start, limit ->
      starts += start
      when (start) {
        0 -> PagedResult(emptyList(), 5, limit, consumedCount = 2)
        2 -> PagedResult(listOf(EmbyItem(Id = "episode", Type = "Episode")), 5, limit, consumedCount = 2)
        else -> {
          if (failNext) { failNext = false; error("retry") }
          PagedResult(listOf(EmbyItem(Id = "last", Type = "Episode")), 5, limit, consumedCount = 1)
        }
      }
    }
    page.reload(loader)
    assertEquals(listOf(0, 2), starts)
    assertEquals(4, page.nextIndex())
    page.loadMore(loader)
    assertEquals(1, page.items.size)
    assertNotNull(page.error)
    page.retry(loader)
    assertEquals(listOf("episode", "last"), page.items.map { it.Id })
    assertEquals(listOf(0, 2, 4, 4), starts)
    assertFalse(page.hasMore)
  }
}
