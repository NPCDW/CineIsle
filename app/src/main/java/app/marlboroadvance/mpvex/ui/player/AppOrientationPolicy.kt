package app.marlboroadvance.mpvex.ui.player

import android.content.pm.ActivityInfo

/** App windows are portrait; video players lock to an explicit orientation. */
object AppOrientationPolicy {
  const val APP = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  const val VIDEO = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  const val MPV = ActivityInfo.SCREEN_ORIENTATION_LOCKED

  /** Canonicalize requests at the Activity boundary, including requests from engines/host clients. */
  fun constrainRequest(videoPlayback: Boolean, @Suppress("UNUSED_PARAMETER") requested: Int): Int =
    if (videoPlayback) VIDEO else APP

  /** MPV accepts only non-sensor locks. Unknown and legacy requests keep the current orientation. */
  fun constrainMpvRequest(requested: Int): Int = when (requested) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else -> MPV
  }

  /** The first valid rotation-corrected video aspect fixes the Activity until the next file. */
  fun orientationForVideo(aspect: Double?): Int? = aspect
    ?.takeIf { it.isFinite() && it > 0.001 }
    ?.let {
      if (it > 1.0) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

  fun rotatedOrientation(current: Int): Int = when (current) {
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  }

  /** Fullscreen entry cannot rely on an orientation change (launch may already be landscape). */
  fun shouldEnterVideoFullscreen(
    landscape: Boolean,
    inPictureInPicture: Boolean,
    finishing: Boolean,
    destroyed: Boolean,
    alreadyFullscreen: Boolean,
  ): Boolean = landscape && !inPictureInPicture && !finishing && !destroyed && !alreadyFullscreen
}
