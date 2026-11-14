package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSeekGestureClassificationTest {
  private fun eligible(
    deltaX: Float = 80f,
    deltaY: Float = 8f,
    touchSlop: Float = 12f,
    elapsedMillis: Long = HORIZONTAL_SEEK_MIN_ELAPSED_MILLIS + 1,
    panelOpen: Boolean = false,
    longPressActive: Boolean = false,
    speedControlActive: Boolean = false,
    claimedByAnotherGesture: Boolean = false,
  ): Boolean = isHorizontalSeekEligible(
    deltaX = deltaX,
    deltaY = deltaY,
    touchSlop = touchSlop,
    elapsedMillis = elapsedMillis,
    panelOpen = panelOpen,
    longPressActive = longPressActive,
    speedControlActive = speedControlActive,
    claimedByAnotherGesture = claimedByAnotherGesture,
  )

  @Test
  fun `obvious horizontal drags classify as seek without a start x gate`() {
    assertEquals(PlayerDragDirection.HORIZONTAL, classifyPlayerDrag(80f, 8f, 12f))
    assertTrue(eligible())

    // Eligibility intentionally accepts no start coordinate: left, center, and right starts
    // all use this same result at runtime.
    listOf(0f, 500f, 999f).forEach { unusedStartX ->
      assertTrue("start x $unusedStartX", eligible())
    }
  }

  @Test
  fun `obvious vertical drags never classify as seek`() {
    assertEquals(PlayerDragDirection.VERTICAL, classifyPlayerDrag(7f, 90f, 12f))
    assertFalse(eligible(deltaX = 7f, deltaY = 90f))
  }

  @Test
  fun `slop time panel and long press gates remain`() {
    assertFalse(eligible(deltaX = 10f, deltaY = 1f, touchSlop = 12f))
    assertFalse(eligible(elapsedMillis = HORIZONTAL_SEEK_MIN_ELAPSED_MILLIS))
    assertFalse(eligible(panelOpen = true))
    assertFalse(eligible(longPressActive = true))
    assertFalse(eligible(speedControlActive = true))
  }

  @Test
  fun `another gesture owner blocks horizontal seek`() {
    assertFalse(eligible(claimedByAnotherGesture = true))
  }

  @Test
  fun `vertical region ownership remains left brightness right volume and center none`() {
    val width = 900f

    assertEquals(
      VerticalGestureRegion.BRIGHTNESS,
      verticalGestureRegion(100f, width, brightnessEnabled = true, volumeEnabled = true),
    )
    assertEquals(
      VerticalGestureRegion.NONE,
      verticalGestureRegion(450f, width, brightnessEnabled = true, volumeEnabled = true),
    )
    assertEquals(
      VerticalGestureRegion.VOLUME,
      verticalGestureRegion(800f, width, brightnessEnabled = true, volumeEnabled = true),
    )
    assertEquals(
      VerticalGestureRegion.NONE,
      verticalGestureRegion(100f, width, brightnessEnabled = false, volumeEnabled = true),
    )
    assertEquals(
      VerticalGestureRegion.NONE,
      verticalGestureRegion(800f, width, brightnessEnabled = true, volumeEnabled = false),
    )
  }
}
