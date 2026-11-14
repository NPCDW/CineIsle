package app.marlboroadvance.mpvex.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPlayerFeatureRemovalContractTest {
  private fun source(path: String): String = sequenceOf(
    File("src/main/java/$path"),
    File("app/src/main/java/$path"),
  ).first(File::isFile).readText()

  @Test
  fun `hidden player chains are absent while pinch zoom remains session only`() {
    val root = "app/marlboroadvance/mpvex/ui/player/"
    val activity = source("${root}PlayerActivity.kt")
    val viewModel = source("${root}PlayerViewModel.kt")
    val enums = source("${root}PlayerEnums.kt")
    val controls = source("${root}controls/PlayerControls.kt")
    val gestures = source("${root}controls/GestureHandler.kt")
    val sheets = source("${root}controls/PlayerSheets.kt")

    listOf(activity, viewModel, enums, controls, sheets).forEach { text ->
      listOf(
        "Sheets.Chapters",
        "Sheets.VideoZoom",
        "Sheets.AspectRatio",
        "Sheets.FrameNavigation",
        "clearABLoop",
        "toggleMirroring",
        "toggleVerticalFlip",
        "repeatMode",
        "shuffleEnabled",
      ).forEach { removed -> assertFalse(removed, text.contains(removed)) }
    }

    assertTrue(gestures.contains("applyPinchZoom(zoom)"))
    assertTrue(enums.contains("data object PinchZoom"))
    assertTrue(controls.contains("PlayerUpdates.PinchZoom"))
    assertFalse(viewModel.contains("defaultVideoZoom"))
  }

  @Test
  fun `danmaku state keeps automatic core with canonical rendering and no sheet state`() {
    val state = source(
      "app/marlboroadvance/mpvex/ui/player/danmaku/PlayerDanmakuState.kt",
    )
    val preferences = source(
      "app/marlboroadvance/mpvex/preferences/DanmakuPreferences.kt",
    )

    listOf(
      "DanmakuSheetState",
      "fun search(",
      "fun pick(",
      "pickEpisode(",
      "rememberSelection",
      "seriesTimeOffsets",
      "timeOffsetFor",
      "blockWords",
      "withRelated.get()",
    ).forEach { assertFalse(it, state.contains(it)) }

    assertTrue(state.contains("DanmakuMatcher.autoMatch("))
    assertTrue(state.contains("withRelated = false"))
    assertTrue(state.contains("CANONICAL_ALPHA_PERCENT = 100"))
    assertTrue(state.contains("CANONICAL_AREA_RATIO = 0.5f"))

    listOf("enabled", "host", "appId", "appSecret", "autoMatch").forEach {
      assertTrue(it, preferences.contains("val $it"))
    }
    listOf("engine", "withRelated", "fontScale", "timeOffsetMs", "rememberSelection").forEach {
      assertFalse(it, preferences.contains("val $it"))
    }
  }

  @Test
  fun `PlayerActivity retains duration probe without recently played or video zoom persistence`() {
    val activity = source("app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt")

    assertTrue(activity.contains("fun getVideoDurationFromUri"))
    assertFalse(activity.contains("RecentlyPlayed"))
    assertFalse(activity.contains("videoZoom"))
  }
}
