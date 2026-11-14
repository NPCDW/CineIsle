package app.marlboroadvance.mpvex.preferences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlayerPreferenceCleanupTest {
  @Test
  fun `direct output wins while seeding decoder mode then both legacy keys are removed`() {
    val plan = requireNotNull(
      LegacyPlayerPreferenceCleanup.plan(
        mapOf("direct_output" to true, "try_hw_dec" to false),
      ),
    )

    assertEquals("HardwarePlus", plan.decoderModeToSeed)
    assertTrue("direct_output" in plan.keysToRemove)
    assertTrue("try_hw_dec" in plan.keysToRemove)
  }

  @Test
  fun `disabled legacy hardware decode seeds software`() {
    val plan = requireNotNull(LegacyPlayerPreferenceCleanup.plan(mapOf("try_hw_dec" to false)))

    assertEquals("Software", plan.decoderModeToSeed)
  }

  @Test
  fun `existing decoder mode is preserved and unrelated values are untouched`() {
    val plan = requireNotNull(
      LegacyPlayerPreferenceCleanup.plan(
        mapOf(
          "mpv_decoder_mode" to "Hardware",
          "direct_output" to true,
          "unrelated_setting" to "keep",
        ),
      ),
    )

    assertNull(plan.decoderModeToSeed)
    assertFalse("mpv_decoder_mode" in plan.keysToRemove)
    assertFalse("unrelated_setting" in plan.keysToRemove)
    assertTrue("direct_output" in plan.keysToRemove)
  }

  @Test
  fun `cleanup is idempotent after current marker`() {
    assertNull(
      LegacyPlayerPreferenceCleanup.plan(
        mapOf(
          LegacyPlayerPreferenceCleanup.MARKER_KEY to LegacyPlayerPreferenceCleanup.VERSION,
          "direct_output" to true,
        ),
      ),
    )
  }

  @Test
  fun `approved keys include removed player and hidden danmaku persistence but retain visible controls`() {
    val removed = LegacyPlayerPreferenceCleanup.obsoleteKeys
    listOf(
      "custom_aspect_ratios",
      "default_video_zoom",
      "default_custom_aspect_ratio",
      "sub_picker_path",
      "emby_card_spacing",
      "repeat_mode",
      "shuffle_enabled",
      "filter_brightness",
      "danmaku_with_related",
      "danmaku_time_offset_ms",
    ).forEach { assertTrue(it, it in removed) }

    listOf(
      "pinch_to_zoom_gesture",
      "debanding",
      "danmaku_enabled",
      "danmaku_host",
      "danmaku_app_id",
      "danmaku_app_secret",
      "danmaku_auto_match",
    ).forEach { assertFalse(it, it in removed) }
  }

  @Test
  fun `application runs synchronous cleanup before starting Koin`() {
    val source = sequenceOf(
      File("src/main/java/app/marlboroadvance/mpvex/App.kt"),
      File("app/src/main/java/app/marlboroadvance/mpvex/App.kt"),
    ).first(File::isFile).readText()

    val cleanup = source.indexOf("cleanupLegacyPlayerPreferences()")
    val koin = source.indexOf("startKoin {")
    assertTrue(cleanup >= 0 && koin > cleanup)
    assertTrue(source.contains("editor.commit()"))
  }
}
