package app.marlboroadvance.mpvex.ui.browser.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackHistoryPresentationTest {
  @Test fun `duration is elapsed time not a playback seek position`() {
    assertThat(formatHistoryDuration(3_661_000L)).isEqualTo("01:01:01")
    assertThat(formatHistoryDuration(0L)).isEqualTo("00:00:00")
    assertThat(formatHistoryDuration(-1000L)).isEqualTo("00:00:00")
    assertThat(formatHistoryDuration(360_000_000L)).isEqualTo("100:00:00")
  }
}
