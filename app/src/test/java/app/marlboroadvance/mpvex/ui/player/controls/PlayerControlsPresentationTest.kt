package app.marlboroadvance.mpvex.ui.player.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsPresentationTest {
  @Test
  fun `single item queue hides both controls`() {
    assertEquals(
      QueueNavigationVisibility(previous = false, next = false),
      queueNavigationVisibility(queueSize = 1, currentIndex = 0),
    )
  }

  @Test
  fun `first middle and last items follow real queue boundaries`() {
    assertEquals(
      QueueNavigationVisibility(previous = false, next = true),
      queueNavigationVisibility(queueSize = 3, currentIndex = 0),
    )
    assertEquals(
      QueueNavigationVisibility(previous = true, next = true),
      queueNavigationVisibility(queueSize = 3, currentIndex = 1),
    )
    assertEquals(
      QueueNavigationVisibility(previous = true, next = false),
      queueNavigationVisibility(queueSize = 3, currentIndex = 2),
    )
  }

  @Test
  fun `invalid queue positions hide both controls`() {
    assertEquals(
      QueueNavigationVisibility(previous = false, next = false),
      queueNavigationVisibility(queueSize = 0, currentIndex = 0),
    )
    assertEquals(
      QueueNavigationVisibility(previous = false, next = false),
      queueNavigationVisibility(queueSize = 3, currentIndex = -1),
    )
    assertEquals(
      QueueNavigationVisibility(previous = false, next = false),
      queueNavigationVisibility(queueSize = 3, currentIndex = 3),
    )
  }

  @Test
  fun `versions row is enabled for any non-empty list`() {
    assertFalse(isVersionsRowEnabled(emptyList<Any>()))
    assertTrue(isVersionsRowEnabled(listOf("only")))
    assertTrue(isVersionsRowEnabled(listOf("one", "two")))
  }
}
