package app.marlboroadvance.mpvex.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSynchronizationContractTest {
  private fun source(path: String): String = sequenceOf(
    File("src/main/java/$path"),
    File("app/src/main/java/$path"),
  ).first { it.isFile }.readText()

  @Test
  fun `live subtitle synchronization writes primary and secondary properties`() {
    val panel = source(
      "app/marlboroadvance/mpvex/ui/player/controls/components/panels/SubtitleDelayPanel.kt",
    )

    listOf(
      "setPropertyDouble(\"sub-delay\"",
      "setPropertyDouble(\"secondary-sub-delay\"",
      "setPropertyDouble(\"sub-speed\"",
      "setPropertyDouble(\"secondary-sub-speed\"",
    ).forEach { assertTrue(it, panel.contains(it)) }
  }

  @Test
  fun `saved and default subtitle state resets both subtitle layers`() {
    val activity = source("app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt")
    val defaults = activity.substringAfter("private fun applyDefaultSettings")
      .substringBefore("private fun enterPipUIMode")

    listOf(
      "setPropertyDouble(\"sub-delay\", defaultSubDelay)",
      "setPropertyDouble(\"secondary-sub-delay\", defaultSubDelay)",
      "setPropertyDouble(\"sub-speed\", defaultSubSpeed)",
      "setPropertyDouble(\"secondary-sub-speed\", defaultSubSpeed)",
    ).forEach { assertTrue(it, defaults.contains(it)) }
    assertFalse(defaults.contains("if (state != null)"))
  }
}
