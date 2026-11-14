package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyTitlesTest {
  @Test fun `episode title matches loemby without padding`() {
    assertEquals(
      "S1E2. Title",
      EmbyItem(Type = "Episode", Name = " Title ", ParentIndexNumber = 1, IndexNumber = 2).displayTitle(),
    )
    assertEquals(
      "S0E0. Special",
      EmbyItem(Type = "episode", Name = "Special", ParentIndexNumber = 0, IndexNumber = 0).displayTitle(),
    )
  }

  @Test fun `incomplete and non episode metadata keeps raw title`() {
    assertEquals("Title", EmbyItem(Type = "Episode", Name = " Title ", ParentIndexNumber = 1).displayTitle())
    assertEquals("Title", EmbyItem(Type = "Movie", Name = " Title ", ParentIndexNumber = 1, IndexNumber = 2).displayTitle())
  }

  @Test fun `blank and prefixed episode titles do not add dangling or duplicate text`() {
    assertEquals("S1E2", EmbyItem(Type = "Episode", Name = "S1E2", ParentIndexNumber = 1, IndexNumber = 2).displayTitle())
    assertEquals(
      "S1E2. Title",
      EmbyItem(Type = "Episode", Name = "s01e02.  Title", ParentIndexNumber = 1, IndexNumber = 2).displayTitle(),
    )
  }

  @Test fun `history title fits shared varchar without splitting surrogate pair`() {
    val title = EmbyItem(
      Type = "Episode",
      Name = "😀".repeat(300),
      ParentIndexNumber = 1,
      IndexNumber = 2,
    ).playbackHistoryTitle()
    assertEquals(255, title.codePointCount(0, title.length))
    assertEquals("S1E2. ", title.substring(0, 6))
  }
}
