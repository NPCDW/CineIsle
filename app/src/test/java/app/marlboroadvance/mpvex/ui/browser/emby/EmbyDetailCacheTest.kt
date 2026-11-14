package app.marlboroadvance.mpvex.ui.browser.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmbyDetailCacheTest {
  @Test fun seasonAndEpisodeUseTheSameEpisodesQuery() {
    assertEquals(
      EmbyDetailQueries.episodes("series", "season"),
      EmbyDetailQueries.episodes("series", "season"),
    )
    assertNotEquals(
      EmbyDetailQueries.episodes("series", "season"),
      EmbyDetailQueries.episodes("series", "other-season"),
    )
  }

  @Test fun detailHierarchyQueriesDoNotCollide() {
    val id = "same-id"
    val queries = setOf(
      EmbyDetailQueries.detail(id),
      EmbyDetailQueries.seasons(id),
      EmbyDetailQueries.seriesForSeason(id),
      EmbyDetailQueries.parentsForEpisode(id),
    )
    assertEquals(4, queries.size)
  }
}
