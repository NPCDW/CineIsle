package app.marlboroadvance.mpvex.ui.player.controls.components

import app.marlboroadvance.mpvex.ui.player.CacheTimeRange
import app.marlboroadvance.mpvex.ui.player.CacheTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineIntervalClassificationTest {
  @Test
  fun `cache classification is unchanged on both sides of playhead`() {
    val timeline = CacheTimeline(
      durationSeconds = 100.0,
      cachedRanges = listOf(CacheTimeRange(20.0, 40.0), CacheTimeRange(70.0, 90.0)),
      uncachedRanges = listOf(
        CacheTimeRange(0.0, 20.0),
        CacheTimeRange(40.0, 70.0),
        CacheTimeRange(90.0, 100.0),
      ),
      bofCached = false,
      eofCached = false,
    )

    assertEquals(
      listOf(
        TimelineClassifiedInterval(0.0, 20.0, TimelineIntervalKind.Uncached),
        TimelineClassifiedInterval(20.0, 40.0, TimelineIntervalKind.Cached),
        TimelineClassifiedInterval(40.0, 70.0, TimelineIntervalKind.Uncached),
        TimelineClassifiedInterval(70.0, 90.0, TimelineIntervalKind.Cached),
        TimelineClassifiedInterval(90.0, 100.0, TimelineIntervalKind.Uncached),
      ),
      classifyTimelineIntervals(timeline, 100.0),
    )
  }

  @Test
  fun `fully uncached and fully cached timelines each use one class`() {
    val uncached = CacheTimeline(
      durationSeconds = 60.0,
      cachedRanges = emptyList(),
      uncachedRanges = listOf(CacheTimeRange(0.0, 60.0)),
      bofCached = false,
      eofCached = false,
    )
    val cached = CacheTimeline(
      durationSeconds = 60.0,
      cachedRanges = listOf(CacheTimeRange(0.0, 60.0)),
      uncachedRanges = emptyList(),
      bofCached = true,
      eofCached = true,
    )

    assertEquals(
      listOf(TimelineClassifiedInterval(0.0, 60.0, TimelineIntervalKind.Uncached)),
      classifyTimelineIntervals(uncached, 60.0),
    )
    assertEquals(
      listOf(TimelineClassifiedInterval(0.0, 60.0, TimelineIntervalKind.Cached)),
      classifyTimelineIntervals(cached, 60.0),
    )
  }

  @Test
  fun `color lookup depends only on cache class`() {
    val colors = TimelineColorPalette(cached = "cached-color", uncached = "uncached-color")

    assertEquals("cached-color", colors.colorFor(TimelineIntervalKind.Cached))
    assertEquals("uncached-color", colors.colorFor(TimelineIntervalKind.Uncached))
  }

  @Test
  fun `invalid durations have no drawable intervals`() {
    val timeline = CacheTimeline(null, emptyList(), emptyList(), false, false)

    assertTrue(classifyTimelineIntervals(timeline, 0.0).isEmpty())
    assertTrue(classifyTimelineIntervals(timeline, Double.NaN).isEmpty())
  }
}
