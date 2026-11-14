package app.marlboroadvance.mpvex.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedPlayerControlsContractTest {
  private fun source(path: String): String = sequenceOf(
    File("src/main/java/$path"),
    File("app/src/main/java/$path"),
  ).first { it.isFile }.readText()

  @Test
  fun `fixed layout contains requested controls and excludes removed actions`() {
    val controls = source("app/marlboroadvance/mpvex/ui/player/controls/PlayerControls.kt")

    listOf(
      "FixedStatusRow",
      "Icons.AutoMirrored.Default.ArrowBack",
      "Icons.Default.PictureInPictureAlt",
      "VerticalSpeedControl",
      "Icons.Default.LockOpen",
      "Icons.Default.SkipPrevious",
      "Icons.Default.SkipNext",
      "Icons.Default.Audiotrack",
      "Icons.Default.Subtitles",
      "Icons.Default.PlaylistPlay",
      "Sheets.MediaDetails",
      "Sheets.Playlist",
      "Sheets.More",
    ).forEach { assertTrue(it, controls.contains(it)) }

    assertFalse(controls.contains("Icons.Default.Camera"))
    assertFalse(controls.contains("Icons.Default.AspectRatio"))
    assertFalse(controls.contains("onMoreClick = {}"))
    assertTrue(controls.contains("mediaTitle = mediaTitle"))
    assertTrue(controls.contains("player_control_open_media_details"))
  }

  @Test
  fun `cache timeline resets per file and is shared by seek feedback`() {
    val viewModel = source("app/marlboroadvance/mpvex/ui/player/PlayerViewModel.kt")
    val controls = source("app/marlboroadvance/mpvex/ui/player/controls/PlayerControls.kt")
    val seekbar = source("app/marlboroadvance/mpvex/ui/player/controls/components/Seekbar.kt")

    assertTrue(viewModel.contains("_cacheTimelineNode.value = null"))
    assertTrue(controls.contains("cacheTimeline = cacheTimeline"))
    assertTrue(controls.contains("CachedTimelineCanvas("))
    assertTrue(seekbar.contains("timeline.cachedRanges.forEach"))
    assertFalse(seekbar.contains("PlayerLib.propNode[\"demuxer-cache-state\"]"))
  }

  @Test
  fun `vertical gestures use fixed left and right ownership helper`() {
    val gestures = source("app/marlboroadvance/mpvex/ui/player/controls/GestureHandler.kt")

    assertTrue(gestures.contains("verticalGestureRegion("))
    assertTrue(gestures.contains("VerticalGestureRegion.BRIGHTNESS -> changeBrightness()"))
    assertTrue(gestures.contains("VerticalGestureRegion.VOLUME -> changeVolume()"))
    assertFalse(gestures.contains("brightnessGesture -> changeBrightness()"))
    assertFalse(gestures.contains("volumeGesture -> changeVolume()"))
  }

  @Test
  fun `runtime no longer reads customizable region preferences`() {
    val controls = source("app/marlboroadvance/mpvex/ui/player/controls/PlayerControls.kt")
    listOf(
      "topLeftControls",
      "topRightControls",
      "bottomLeftControls",
      "bottomRightControls",
      "portraitBottomControls",
      "parseButtons(",
      "SeekbarStyle",
    ).forEach { assertFalse(it, controls.contains(it)) }
  }
}
