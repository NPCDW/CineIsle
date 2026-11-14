package app.marlboroadvance.mpvex.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrientationPolicyTest {
  @Test
  fun `non playback always uses normal portrait`() {
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientationPolicy.APP)
    orientationRequests.forEach { request ->
      assertEquals(AppOrientationPolicy.APP, AppOrientationPolicy.constrainRequest(false, request))
    }
  }

  @Test
  fun `other video playback keeps its existing sensor landscape policy`() {
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, AppOrientationPolicy.VIDEO)
    orientationRequests.forEach { request ->
      assertEquals(AppOrientationPolicy.VIDEO, AppOrientationPolicy.constrainRequest(true, request))
    }
  }

  @Test
  fun `mpv accepts only explicit portrait landscape or current lock`() {
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, AppOrientationPolicy.MPV)
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
      AppOrientationPolicy.constrainMpvRequest(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
      AppOrientationPolicy.constrainMpvRequest(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    )
    orientationRequests
      .filterNot { it in setOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
      .forEach { request -> assertEquals(AppOrientationPolicy.MPV, AppOrientationPolicy.constrainMpvRequest(request)) }
  }

  @Test
  fun `video aspect chooses one fixed orientation`() {
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppOrientationPolicy.orientationForVideo(16.0 / 9.0))
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientationPolicy.orientationForVideo(9.0 / 16.0))
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientationPolicy.orientationForVideo(1.0))
    assertNull(AppOrientationPolicy.orientationForVideo(null))
    assertNull(AppOrientationPolicy.orientationForVideo(0.0))
    assertNull(AppOrientationPolicy.orientationForVideo(Double.NaN))
  }

  @Test
  fun `manual rotate alternates fixed portrait and landscape`() {
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
      AppOrientationPolicy.rotatedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
      AppOrientationPolicy.rotatedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
      AppOrientationPolicy.rotatedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED),
    )
  }

  @Test
  fun `exiting landscape video playback restores portrait instead of carrying its request`() {
    val playback = AppOrientationPolicy.constrainRequest(true, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    assertEquals(AppOrientationPolicy.APP, AppOrientationPolicy.constrainRequest(false, playback))
  }

  @Test
  fun `startup already in landscape enters fullscreen without a rotation callback`() {
    assertTrue(AppOrientationPolicy.shouldEnterVideoFullscreen(true, false, false, false, false))
    assertFalse(AppOrientationPolicy.shouldEnterVideoFullscreen(true, false, false, false, true))
  }

  @Test
  fun `fullscreen gate excludes PiP portrait exiting and destroyed windows`() {
    val states = listOf(false, true)
    for (landscape in states) for (pip in states) for (finishing in states)
      for (destroyed in states) for (fullscreen in states) {
        assertEquals(
          landscape && !pip && !finishing && !destroyed && !fullscreen,
          AppOrientationPolicy.shouldEnterVideoFullscreen(landscape, pip, finishing, destroyed, fullscreen),
        )
      }
  }

  private val orientationRequests = (-1..14).toList() + listOf(Int.MIN_VALUE, Int.MAX_VALUE)
}
