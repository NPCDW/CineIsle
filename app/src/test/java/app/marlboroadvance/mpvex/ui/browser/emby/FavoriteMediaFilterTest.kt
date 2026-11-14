package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FavoriteMediaFilterTest {
  @Test fun seriesNeverIncludesEpisodesSeasonsOrMovies() {
    val mixed = listOf("Movie", "Series", "Season", "Episode", "Video", "Person").map { EmbyItem(Id = it, Type = it) }
    assertEquals(listOf("Series"), mixed.filter(FavoriteMediaFilter.SERIES::accepts).map { it.Type })
    assertEquals(listOf("Movie"), mixed.filter(FavoriteMediaFilter.MOVIE::accepts).map { it.Type })
    assertEquals(listOf("Movie", "Series", "Season", "Episode", "Video"), mixed.filter(FavoriteMediaFilter.ALL::accepts).map { it.Type })
    assertTrue(FavoriteMediaFilter.SERIES.accepts(EmbyItem(Type = "series")))
    assertFalse(FavoriteMediaFilter.SERIES.accepts(EmbyItem()))
  }

  @Test fun ignoredServerTypeFilterStillConsumesOriginalOffsets() = runTest {
    val page = PagedList<EmbyItem>(pageSize = 2, keyOf = { it.Id ?: it })
    val requests = mutableListOf<Int>()
    val mixed = listOf("Movie", "Episode", "Series", "Movie").mapIndexed { index, type -> EmbyItem(Id = "$index", Type = type) }
    page.reload { start, limit ->
      requests += start
      val raw = mixed.drop(start).take(limit)
      PagedResult(raw.filter(FavoriteMediaFilter.SERIES::accepts), null, limit,
        exhausted = start + raw.size >= mixed.size, consumedCount = raw.size)
    }
    assertEquals(listOf(0, 2), requests)
    assertEquals(listOf("Series"), page.items.map { it.Type })
    assertEquals(4, page.nextIndex())
    assertFalse(page.hasMore)
  }
}
