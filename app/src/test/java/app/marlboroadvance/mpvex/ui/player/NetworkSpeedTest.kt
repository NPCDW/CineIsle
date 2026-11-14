package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedTest {
  @Test
  fun `formatter always uses one decimal MB per second`() {
    assertEquals("0.0 MB/s", formatNetworkSpeed(0))
    assertEquals("0.5 MB/s", formatNetworkSpeed(512L * 1024L))
    assertEquals("1.0 MB/s", formatNetworkSpeed(1024L * 1024L))
    assertEquals("11.8 MB/s", formatNetworkSpeed(12_345_678L))
  }

  @Test
  fun `formatter clamps negative samples and has no direction marker`() {
    assertEquals("0.0 MB/s", formatNetworkSpeed(-1))
    assertEquals("2.0 MB/s", formatNetworkSpeed(2L * 1024L * 1024L))
  }
}
