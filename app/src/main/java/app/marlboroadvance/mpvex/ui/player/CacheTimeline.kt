package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVNode

/** A finite interval on the playback timeline, measured in seconds. */
internal data class CacheTimeRange(
  val startSeconds: Double,
  val endSeconds: Double,
) {
  val lengthSeconds: Double get() = endSeconds - startSeconds
}

/**
 * UI-ready snapshot of libmpv's `demuxer-cache-state`.
 *
 * Cached ranges are ordered, non-overlapping, and clamped to the media duration when it is known.
 * Small gaps are folded into the surrounding range so transient demuxer boundaries do not flicker in UI.
 */
internal data class CacheTimeline(
  val durationSeconds: Double?,
  val cachedRanges: List<CacheTimeRange>,
  val uncachedRanges: List<CacheTimeRange>,
  val bofCached: Boolean,
  val eofCached: Boolean,
) {
  companion object {
    const val MERGE_GAP_SECONDS = 0.5

    /** Parse and normalize one `demuxer-cache-state` node. Invalid input produces an empty snapshot. */
    fun fromMpv(
      state: MPVNode?,
      durationSeconds: Double?,
    ): CacheTimeline {
      val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
      val stateMap = state?.asMap()
      val bofCached = stateMap?.get("bof-cached")?.asBoolean() == true
      val eofCached = stateMap?.get("eof-cached")?.asBoolean() == true
      val parsed = stateMap?.get("seekable-ranges")?.asArray()
        ?.mapNotNull { it.toRange(duration) }
        .orEmpty()
      val cached = normalize(parsed, duration, bofCached, eofCached)

      return CacheTimeline(
        durationSeconds = duration,
        cachedRanges = cached,
        uncachedRanges = complement(cached, duration),
        bofCached = bofCached,
        eofCached = eofCached,
      )
    }

    private fun MPVNode.toRange(duration: Double?): CacheTimeRange? {
      val range = asMap() ?: return null
      val rawStart = range["start"]?.asNumber() ?: return null
      val rawEnd = range["end"]?.asNumber() ?: return null
      if (!rawStart.isFinite() || !rawEnd.isFinite()) return null

      val start = rawStart.clampTimestamp(duration)
      val end = rawEnd.clampTimestamp(duration)
      return if (start < end) CacheTimeRange(start, end) else null
    }

    private fun MPVNode.asNumber(): Double? = asDouble() ?: asInt()?.toDouble()

    private fun Double.clampTimestamp(duration: Double?): Double {
      val nonNegative = if (this <= 0.0) 0.0 else this
      return duration?.let { nonNegative.coerceAtMost(it) } ?: nonNegative
    }

    private fun normalize(
      ranges: List<CacheTimeRange>,
      duration: Double?,
      bofCached: Boolean,
      eofCached: Boolean,
    ): List<CacheTimeRange> {
      if (ranges.isEmpty()) return emptyList()

      val sorted = ranges.sortedWith(compareBy(CacheTimeRange::startSeconds, CacheTimeRange::endSeconds))
        .toMutableList()
      if (bofCached) sorted[0] = sorted.first().copy(startSeconds = 0.0)
      if (eofCached && duration != null) {
        sorted[sorted.lastIndex] = sorted.last().copy(endSeconds = duration)
      }

      val merged = ArrayList<CacheTimeRange>(sorted.size)
      for (range in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && range.startSeconds - previous.endSeconds <= MERGE_GAP_SECONDS) {
          merged[merged.lastIndex] = previous.copy(endSeconds = maxOf(previous.endSeconds, range.endSeconds))
        } else {
          merged += range
        }
      }
      return merged
    }

    private fun complement(
      cached: List<CacheTimeRange>,
      duration: Double?,
    ): List<CacheTimeRange> {
      if (duration == null) return emptyList()

      val uncached = ArrayList<CacheTimeRange>(cached.size + 1)
      var cursor = 0.0
      for (range in cached) {
        if (cursor < range.startSeconds) uncached += CacheTimeRange(cursor, range.startSeconds)
        cursor = maxOf(cursor, range.endSeconds)
      }
      if (cursor < duration) uncached += CacheTimeRange(cursor, duration)
      return uncached
    }
  }
}
