package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTimelineTest {
  @Test
  fun `null and malformed states produce an empty timeline`() {
    val states = listOf<MPVNode?>(
      null,
      MPVNode.None,
      MPVNode.StringNode("not a map"),
      mapNode("seekable-ranges" to MPVNode.StringNode("not an array")),
      mapNode("seekable-ranges" to arrayNode(
        MPVNode.None,
        MPVNode.StringNode("not a range"),
        mapNode("start" to doubleNode(1.0)),
        mapNode("end" to doubleNode(2.0)),
        rangeNode(Double.NaN, 5.0),
        rangeNode(5.0, Double.POSITIVE_INFINITY),
      )),
    )

    states.forEach { state ->
      val timeline = CacheTimeline.fromMpv(state, 60.0)
      assertTrue(timeline.cachedRanges.isEmpty())
      assertEquals(listOf(range(0.0, 60.0)), timeline.uncachedRanges)
      assertFalse(timeline.bofCached)
      assertFalse(timeline.eofCached)
    }
  }

  @Test
  fun `timestamps are clamped and ranges invalid after clamping are discarded`() {
    val timeline = timeline(
      duration = 100.0,
      ranges = listOf(
        -20.0 to 10.0,
        90.0 to 130.0,
        -10.0 to -1.0,
        150.0 to 170.0,
        40.0 to 40.0,
        60.0 to 50.0,
        Double.NEGATIVE_INFINITY to 20.0,
        20.0 to Double.NaN,
      ),
    )

    assertEquals(listOf(range(0.0, 10.0), range(90.0, 100.0)), timeline.cachedRanges)
    assertEquals(listOf(range(10.0, 90.0)), timeline.uncachedRanges)
  }

  @Test
  fun `ranges are sorted and overlaps touching edges and half-second gaps merge`() {
    val timeline = timeline(
      duration = 50.0,
      ranges = listOf(
        30.0 to 35.0,
        5.0 to 10.0,
        19.5 to 25.0,
        9.0 to 15.0,
        15.5 to 20.0,
        35.0 to 36.0,
      ),
    )

    assertEquals(listOf(range(5.0, 25.0), range(30.0, 36.0)), timeline.cachedRanges)
  }

  @Test
  fun `gaps larger than half a second stay disjoint and derive complements`() {
    val timeline = timeline(
      duration = 40.0,
      ranges = listOf(20.0 to 30.0, 2.0 to 8.0, 8.500_001 to 10.0),
    )

    assertEquals(
      listOf(range(2.0, 8.0), range(8.500_001, 10.0), range(20.0, 30.0)),
      timeline.cachedRanges,
    )
    assertEquals(
      listOf(range(0.0, 2.0), range(8.0, 8.500_001), range(10.0, 20.0), range(30.0, 40.0)),
      timeline.uncachedRanges,
    )
  }

  @Test
  fun `bof and eof extend the outer ranges after sorting`() {
    val timeline = timeline(
      duration = 100.0,
      bofCached = true,
      eofCached = true,
      ranges = listOf(70.0 to 90.0, 10.0 to 20.0, 40.0 to 50.0),
    )

    assertEquals(
      listOf(range(0.0, 20.0), range(40.0, 50.0), range(70.0, 100.0)),
      timeline.cachedRanges,
    )
    assertEquals(listOf(range(20.0, 40.0), range(50.0, 70.0)), timeline.uncachedRanges)
    assertTrue(timeline.bofCached)
    assertTrue(timeline.eofCached)
  }

  @Test
  fun `boundary extension can close a small gap and merge ranges`() {
    val timeline = timeline(
      duration = 20.0,
      bofCached = true,
      eofCached = true,
      ranges = listOf(0.0 to 9.0, 9.5 to 18.0),
    )

    assertEquals(listOf(range(0.0, 20.0)), timeline.cachedRanges)
    assertTrue(timeline.uncachedRanges.isEmpty())
  }

  @Test
  fun `unknown or invalid duration keeps finite ranges unbounded above`() {
    val durations = listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)

    durations.forEach { duration ->
      val timeline = timeline(
        duration = duration,
        bofCached = true,
        eofCached = true,
        ranges = listOf(20.0 to 30.0, 100.0 to 150.0),
      )
      assertEquals(null, timeline.durationSeconds)
      assertEquals(listOf(range(0.0, 30.0), range(100.0, 150.0)), timeline.cachedRanges)
      assertTrue(timeline.uncachedRanges.isEmpty())
      assertTrue(timeline.eofCached)
    }
  }

  @Test
  fun `integer timestamps are accepted from defensive fixtures`() {
    val state = mapNode(
      "seekable-ranges" to arrayNode(
        mapNode("start" to MPVNode.IntNode(2), "end" to MPVNode.IntNode(7)),
      ),
    )

    assertEquals(listOf(range(2.0, 7.0)), CacheTimeline.fromMpv(state, 10.0).cachedRanges)
  }

  @Test
  fun `flags without valid ranges do not invent cached data`() {
    val timeline = CacheTimeline.fromMpv(
      mapNode(
        "bof-cached" to MPVNode.BooleanNode(true),
        "eof-cached" to MPVNode.BooleanNode(true),
        "seekable-ranges" to arrayNode(rangeNode(10.0, 10.0)),
      ),
      30.0,
    )

    assertTrue(timeline.cachedRanges.isEmpty())
    assertEquals(listOf(range(0.0, 30.0)), timeline.uncachedRanges)
    assertTrue(timeline.bofCached)
    assertTrue(timeline.eofCached)
  }

  private fun timeline(
    duration: Double?,
    bofCached: Boolean = false,
    eofCached: Boolean = false,
    ranges: List<Pair<Double, Double>>,
  ): CacheTimeline = CacheTimeline.fromMpv(
    mapNode(
      "bof-cached" to MPVNode.BooleanNode(bofCached),
      "eof-cached" to MPVNode.BooleanNode(eofCached),
      "seekable-ranges" to arrayNode(*ranges.map { rangeNode(it.first, it.second) }.toTypedArray()),
    ),
    duration,
  )

  private fun rangeNode(start: Double, end: Double): MPVNode = mapNode(
    "start" to doubleNode(start),
    "end" to doubleNode(end),
  )

  private fun doubleNode(value: Double): MPVNode = MPVNode.DoubleNode(value)

  private fun arrayNode(vararg values: MPVNode): MPVNode = MPVNode.ArrayNode(arrayOf(*values))

  private fun mapNode(vararg values: Pair<String, MPVNode>): MPVNode = MPVNode.MapNode(mapOf(*values))

  private fun range(start: Double, end: Double) = CacheTimeRange(start, end)
}
