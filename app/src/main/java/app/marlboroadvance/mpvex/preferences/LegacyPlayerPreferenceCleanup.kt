package app.marlboroadvance.mpvex.preferences

/** Pure startup preference migration plan, kept Android-free for JVM tests. */
object LegacyPlayerPreferenceCleanup {
  const val MARKER_KEY = "player_hidden_feature_cleanup_version"
  const val VERSION = 2
  const val DECODER_MODE_KEY = "mpv_decoder_mode"

  private const val LEGACY_DIRECT_OUTPUT_KEY = "direct_output"
  private const val LEGACY_TRY_HW_DEC_KEY = "try_hw_dec"

  /** Only keys belonging to approved removed/hidden player and danmaku chains. */
  val obsoleteKeys: Set<String> = setOf(
    LEGACY_DIRECT_OUTPUT_KEY,
    LEGACY_TRY_HW_DEC_KEY,
    "enable_recently_played",
    "sub_picker_path",
    "emby_card_spacing",
    "custom_aspect_ratios",
    "default_video_zoom",
    "pan_and_zoom_enabled",
    "include_subtitles_in_snapshot",
    "gif_width",
    "repeat_mode",
    "shuffle_enabled",
    "default_video_aspect",
    "default_custom_aspect_ratio",
    "deband_iterations",
    "deband_threshold",
    "deband_range",
    "deband_grain",
    "filter_brightness",
    "filter_saturation",
    "filter_gamma",
    "filter_contrast",
    "filter_hue",
    "filter_sharpness",
    "danmaku_engine",
    "danmaku_with_related",
    "danmaku_area_ratio",
    "danmaku_alpha",
    "danmaku_font_scale",
    "danmaku_speed_scale",
    "danmaku_density",
    "danmaku_prevent_overlapping",
    "danmaku_block_words",
    "danmaku_remember_selection",
    "danmaku_time_offset_ms",
    "danmaku_series_offsets",
  )

  data class Plan(
    val decoderModeToSeed: String?,
    val keysToRemove: Set<String>,
    val markerVersion: Int,
  )

  fun plan(values: Map<String, *>): Plan? {
    val currentVersion = (values[MARKER_KEY] as? Number)?.toInt() ?: 0
    if (currentVersion >= VERSION) return null

    val decoderMode = if (values.containsKey(DECODER_MODE_KEY)) {
      null
    } else {
      when {
        values[LEGACY_DIRECT_OUTPUT_KEY] == true -> "HardwarePlus"
        values[LEGACY_TRY_HW_DEC_KEY] == false -> "Software"
        else -> "Auto"
      }
    }

    return Plan(
      decoderModeToSeed = decoderMode,
      keysToRemove = obsoleteKeys.intersect(values.keys),
      markerVersion = VERSION,
    )
  }
}
