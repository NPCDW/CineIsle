package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureMathTest {
  @Test
  fun `system volume remains continuous before boost`() {
    val target = mapVolumeGesture(0.427f, boostCap = 50)

    assertEquals(0.427f, target.systemFraction, 0.0001f)
    assertEquals(100, target.mpvVolume)
  }

  @Test
  fun `boost shares one continuous scalar with system volume`() {
    val target = mapVolumeGesture(1.4f, boostCap = 50)

    assertEquals(1f, target.systemFraction, 0f)
    assertEquals(120, target.mpvVolume)
    assertEquals(1.4f, volumeGestureScalar(1f, 120, 50), 0.0001f)
  }

  @Test
  fun `moving down from boost crosses system maximum without a jump`() {
    val boosted = volumeGestureScalar(1f, 125, 50)
    val atBoundary = mapVolumeGesture(boosted - 0.5f, 50)
    val belowBoundary = mapVolumeGesture(boosted - 0.6f, 50)

    assertEquals(1f, atBoundary.systemFraction, 0f)
    assertEquals(100, atBoundary.mpvVolume)
    assertEquals(0.9f, belowBoundary.systemFraction, 0.0001f)
    assertEquals(100, belowBoundary.mpvVolume)
  }

  @Test
  fun `disabled boost clamps gesture to system range`() {
    val target = mapVolumeGesture(1.8f, boostCap = 0)

    assertEquals(1f, target.systemFraction, 0f)
    assertEquals(100, target.mpvVolume)
  }
}
