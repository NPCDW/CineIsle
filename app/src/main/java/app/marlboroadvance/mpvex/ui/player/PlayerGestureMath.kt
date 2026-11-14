package app.marlboroadvance.mpvex.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val HORIZONTAL_SEEK_MIN_ELAPSED_MILLIS = 100L
private const val HORIZONTAL_SEEK_DIRECTION_BIAS = 2f

internal enum class PlayerDragDirection {
  HORIZONTAL,
  VERTICAL,
  UNDECIDED,
}

internal enum class VerticalGestureRegion {
  BRIGHTNESS,
  VOLUME,
  NONE,
}

internal data class VolumeGestureTarget(
  val systemFraction: Float,
  val mpvVolume: Int,
)

/** Classifies only clearly horizontal or vertical drags; diagonals stay undecided. */
internal fun classifyPlayerDrag(
  deltaX: Float,
  deltaY: Float,
  touchSlop: Float,
): PlayerDragDirection {
  val horizontal = abs(deltaX)
  val vertical = abs(deltaY)
  if (horizontal <= touchSlop && vertical <= touchSlop) return PlayerDragDirection.UNDECIDED
  return when {
    horizontal > vertical * HORIZONTAL_SEEK_DIRECTION_BIAS -> PlayerDragDirection.HORIZONTAL
    vertical > horizontal * HORIZONTAL_SEEK_DIRECTION_BIAS -> PlayerDragDirection.VERTICAL
    else -> PlayerDragDirection.UNDECIDED
  }
}

/**
 * Decides whether a pending drag may become a horizontal seek.
 *
 * Start coordinates are deliberately absent: horizontal seeking owns the whole content surface.
 */
internal fun isHorizontalSeekEligible(
  deltaX: Float,
  deltaY: Float,
  touchSlop: Float,
  elapsedMillis: Long,
  panelOpen: Boolean,
  longPressActive: Boolean,
  speedControlActive: Boolean,
  claimedByAnotherGesture: Boolean,
): Boolean =
  classifyPlayerDrag(deltaX, deltaY, touchSlop) == PlayerDragDirection.HORIZONTAL &&
    elapsedMillis > HORIZONTAL_SEEK_MIN_ELAPSED_MILLIS &&
    !panelOpen &&
    !longPressActive &&
    !speedControlActive &&
    !claimedByAnotherGesture

/** Keeps brightness on the left third and volume on the right third. */
internal fun verticalGestureRegion(
  startX: Float,
  width: Float,
  brightnessEnabled: Boolean,
  volumeEnabled: Boolean,
): VerticalGestureRegion {
  if (width <= 0f) return VerticalGestureRegion.NONE
  return when {
    brightnessEnabled && startX < width / 3f -> VerticalGestureRegion.BRIGHTNESS
    volumeEnabled && startX > width * 2f / 3f -> VerticalGestureRegion.VOLUME
    else -> VerticalGestureRegion.NONE
  }
}

/** Combines Android's 0..1 stream range with mpv's optional boost range into one gesture axis. */
internal fun volumeGestureScalar(
  systemFraction: Float,
  mpvVolume: Int,
  boostCap: Int,
): Float {
  val system = systemFraction.coerceIn(0f, 1f)
  if (boostCap <= 0 || system < 1f) return system
  val boost = (mpvVolume - 100).coerceIn(0, boostCap).toFloat() / boostCap
  return system + boost
}

internal fun mapVolumeGesture(
  scalar: Float,
  boostCap: Int,
): VolumeGestureTarget {
  val bounded = scalar.coerceIn(0f, if (boostCap > 0) 2f else 1f)
  val system = bounded.coerceAtMost(1f)
  val mpv = if (boostCap > 0 && bounded > 1f) {
    (100 + (bounded - 1f) * boostCap).roundToInt().coerceIn(100, 100 + boostCap)
  } else {
    100
  }
  return VolumeGestureTarget(system, mpv)
}
