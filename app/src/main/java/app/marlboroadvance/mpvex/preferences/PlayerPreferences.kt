
package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

class PlayerPreferences(
  preferenceStore: PreferenceStore,
) {
  val invertDuration = preferenceStore.getBoolean("invert_duration")
  val holdForMultipleSpeed = preferenceStore.getFloat("hold_for_multiple_speed", 2f)
  val showDynamicSpeedOverlay = preferenceStore.getBoolean("show_dynamic_speed_overlay", true)
  val usePreciseSeeking = preferenceStore.getBoolean("use_precise_seeking", false)

  val brightnessGesture = preferenceStore.getBoolean("gestures_brightness", true)
  val volumeGesture = preferenceStore.getBoolean("volume_brightness", true)
  val pinchToZoomGesture = preferenceStore.getBoolean("pinch_to_zoom_gesture", true)
  val horizontalSwipeToSeek = preferenceStore.getBoolean("horizontal_swipe_to_seek", true)
  val horizontalSwipeSensitivity = preferenceStore.getFloat("horizontal_swipe_sensitivity", 0.05f)


  val defaultSpeed = preferenceStore.getFloat("default_speed", 1f)
  val speedPresets =
    preferenceStore.getStringSet(
      "default_speed_presets",
      setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
  val showLoadingCircle = preferenceStore.getBoolean("show_loading_circle", true)
  val savePositionOnQuit = preferenceStore.getBoolean("save_position", true)

  /**
   * 播完是否自动退出播放器。
   *
   * 默认 **false**（播完停在最后一帧，不退出），这也是「更多」面板里那个开关的默认状态；
   * 想要「看完就回列表」的用户可以在开关里打开它。
   */
  val closeAfterReachingEndOfVideo = preferenceStore.getBoolean("close_after_eof", false)

  val rememberBrightness = preferenceStore.getBoolean("remember_brightness")
  val defaultBrightness = preferenceStore.getFloat("default_brightness", -1f)

  /**
   * 按剧记亮度（对标 Yamby 的 `resume_brightness`）：`seriesKey -> 0.0~1.0`，
   * 序列化成 `key=value;key=value`。
   *
   * ## 和 [rememberBrightness] 的关系
   *
   * [rememberBrightness] 是**全局一个值**：调一次，以后所有片都从这个亮度开始。
   * 那对「整季在晚上看的番」合适，但对「白天看的剧 + 晚上看的另一部」完全不对 ——
   * 一部片的亮度需求其实是**看片时的环境光**，而不是片子本身的属性，
   * 可用户真正记得住的是「上次看这部片我调到了这么暗」。
   *
   * 所以这里做成**叠加**而不是替代：
   * - [rememberBrightness] 开着 ⇒ 记下的值同时写进 [defaultBrightness]（保持原有行为）；
   * - [rememberSeriesBrightness] 开着 ⇒ 再额外按剧写一份，换片时**按剧覆盖 > 全局默认**。
   *
   * ## 为什么不建表
   *
   * 与弹幕偏移同一个理由：真正会有数值的片子是个位数，拼一行字符串足够。
   * ⚠️ seriesKey 是 Emby 的 ItemId（32 位十六进制）或本地片的路径 hash，
   * 不含 `;` / `=`，分隔符是安全的。
   */
  val rememberSeriesBrightness = preferenceStore.getBoolean("remember_series_brightness")
  val seriesBrightness = preferenceStore.getString("series_brightness", "")

  /**
   * 本次播放该用的初始亮度。
   *
   * 返回 `-1f`（即 [PlayerActivity.BRIGHTNESS_NOT_SET] 那个值）表示「没有记录、保持系统亮度」——
   * 调用方据此决定要不要写 `window.attributes.screenBrightness`。
   *
   * ⚠️ 必须是「按剧有记录就用按剧的，哪怕全局默认被改过」：用户上次把这部片
   * 调暗了，这次开播如果先套全局值再等他手动调，那一瞬间的刺眼就是 bug 本身。
   */
  fun brightnessFor(seriesKey: String?): Float {
    if (!seriesKey.isNullOrBlank()) {
      parseSeriesBrightness(seriesBrightness.get())[seriesKey]?.let { return it }
    }
    return defaultBrightness.get()
  }

  /** 给某部剧写入亮度；null = 删掉这条覆盖。 */
  fun setSeriesBrightness(seriesKey: String?, value: Float?) {
    if (seriesKey.isNullOrBlank()) return
    val map = parseSeriesBrightness(seriesBrightness.get()).toMutableMap()
    if (value == null) map.remove(seriesKey) else map[seriesKey] = value.coerceIn(0f, 1f)
    seriesBrightness.set(
      map.entries.joinToString(";") { "${it.key}=${it.value}" },
    )
  }

  private fun parseSeriesBrightness(raw: String): Map<String, Float> {
    if (raw.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, Float>()
    for (entry in raw.split(';')) {
      val i = entry.indexOf('=')
      if (i <= 0) continue
      val value = entry.substring(i + 1).toFloatOrNull() ?: continue
      out[entry.substring(0, i)] = value
    }
    return out
  }

  val allowGesturesInPanels = preferenceStore.getBoolean("allow_gestures_in_panels")
  val showSystemStatusBar = preferenceStore.getBoolean("show_system_status_bar")
  val showSystemNavigationBar = preferenceStore.getBoolean("show_system_navigation_bar")
  val playerTimeToDisappear = preferenceStore.getInt("player_time_to_disappear", 4000)



  val customSkipDuration = preferenceStore.getInt("custom_skip_duration", 90)

  /**
   * 自动跳过**片头**（b2+a1）。
   *
   * 数据来源两路，按精度取：
   * 1. **精准**：服务端 4.7+ 的 `/Users/{UserId}/Items/{Id}/Intros` 返回的 Intro 段
   *    （需服务端跑过片头标记扫描任务）；
   * 2. **兜底**：没有精准数据时，按 [customSkipDuration] 认为「开头这么久都是片头」，
   *    起播后自动跳到那个时刻。兜底只在视频总长 > 2×[customSkipDuration] 时生效
   *    （几分钟的短片不可能有 90 秒片头，不设这道坎会误跳）。
   *
   * 默认**关闭** —— 自动跳进度属于侵入性行为（有人就是要看片头），交给用户自己开。
   * 关掉时播放头进入片头区间会显示一个可点的「跳过片头」小胶囊（手动精准跳）。
   */
  val autoSkipIntro = preferenceStore.getBoolean("auto_skip_intro", false)

  /**
   * 自动跳过**片尾**（b2+a1），数据来源与默认关闭的理由同 [autoSkipIntro]。
   *
   * 片尾自动跳 = 跳到视频结尾（触发连播的下一集 / 停在末帧）。
   */
  val autoSkipOutro = preferenceStore.getBoolean("auto_skip_outro", false)

  // ── mpv 播放缓存（清单 #18 / 原 3.3）──
  //
  // ⚠️ 在做之前先纠正一个误解：mpv **本来就有**内存缓冲（`MPVView.initOptions` 里
  // `cache-secs=30` / `demuxer-max-bytes=64MiB`），所以「mpv 完全没有缓存」是错的。
  // 真正缺的是**落盘**那部分：没设 `cache-dir`，缓存只活在一块内存里，
  // 退出播放就没了 ⇒ 同一片子第二次看仍然要从服务器重新拉一遍。
  // 下面这三项就是为了把落盘缓存打开、并把上限交给用户控制。

  /**
   * 是否把网络流缓存**落盘**（`cache-dir` + `cache-persize`），默认**关**（v196 起）。
   *
   * ## 为什么从「默认开」改成「默认关」
   *
   * 落盘缓存的工作方式是**写穿**：每个字节先写进 cache 文件、再读回 demuxer，
   * 播放期间每秒都有「写 + 读」两路 IO 挂在实时路径上。高码率流（4K60 @ 27.7Mbps
   * ≈ 3.5MB/s）本身就把供给绷得很紧，再叠一层磁盘写入（还要跟后台上传任务抢 IO），
   * 预读速率跟不上码率 ⇒ 池子永远填不满 ⇒「放两三秒就卡住」。
   * 真机对照组就是 media3：纯内存路径，同一条片子流畅。
   *
   * v196 把内存缓冲加深到 192MB（≈55 秒）之后，实时性优先于跨会话复用 ——
   * 想要「重看不用重拉」的用户仍可手动打开这个开关（收 4K 长片前留意存储余量）。
   *
   * 只对**网络流**生效 —— mpv 走 `file://` 读本地文件时本来就不经过缓存层，
   * 所以这里不必（也无法）按片源区分。
   */
  val diskCacheEnabled = preferenceStore.getBoolean("mpv_disk_cache_enabled", false)

  /**
   * 每条流落盘缓存的上限（MB，`cache-persize`），范围 64~2048，默认 512。
   *
   * 512MB 大约够缓存一部 1080p 的长片（按 8Mbps 估算 ≈ 8 分钟视频，
   * 实际取决于码率）。上限刻意封在 2GB：mpv 的落盘缓存是**单文件按流**的，
   * 给太大反而会在低端机上把存储撑满，且 LRU 淘汰变慢。
   */
  val diskCacheSizeMb = preferenceStore.getInt("mpv_disk_cache_size_mb", 512)


  /**
   * 播放器**震动反馈总开关**，默认**开**。
   *
   * 统一管着三处震动（issue 反馈后新增/调整的都在这里受控）：
   * 1. 横滑快进每跨一档的轻震（见 `SEEK_HAPTIC_INTERVAL_SEC`）
   * 2. 长按开始播放倍速调节
   * 3. 长按倍速**跨档位**时
   *
   * 做成总开关而不是只给「快进震动」单独一项，是因为**震动本身就有反对的声音**：
   * 手机马达强弱因人而异，长时间播放时反复震动也可能让人不适。
   * 给一个一键全关的出口，比让用户逐项去关更实际。
   *
   * ⚠️ 这里**只管震动**，不管触觉反馈（`performHapticFeedback` 的其余用法）。
   * 那些都是「点到了 / 拖到哪了」这类纯视觉替代，关掉反而影响可用性。
   */
  val hapticFeedbackEnabled = preferenceStore.getBoolean("haptic_feedback_enabled", true)

  // New: autoplay next video when current file ends
  val autoplayNextVideo = preferenceStore.getBoolean("autoplay_next_video", true)

  /**
   * 播完自动切下一集前，先弹一个倒计时卡片让用户来得及取消。
   *
   * 单位秒：**0 表示不弹、直接切**（原来的行为）；3 / 5 / 10 为可选档位。
   * 只在 [autoplayNextVideo] 打开时才有意义 —— 关掉连播后根本不会走到倒计时。
   */
  val autoplayNextCountdownSeconds = preferenceStore.getInt("autoplay_next_countdown_seconds", 5)

  /** 下一集卡片在片尾多少秒前开始悬停（Yamby 的 `next_up_sec`）。 */
  val nextUpShowSeconds = preferenceStore.getInt("next_up_show_seconds", 30)

  /**
   * 是否显示「下一集」悬停卡片。
   *
   * 与 [autoplayNextCountdownSeconds]（最后几秒的数字倒计时）是**两个不同的东西**：
   * 那个是「马上要切了」的催促，这个是提前半分钟问「要不要接着看」。
   * 都关掉 = 播完静默切集，什么都不显示。
   */
  val showNextUpCard = preferenceStore.getBoolean("show_next_up_card", true)

  /**
   * 记住每部剧的播放速度：同一部剧切下一集时沿用上一集调过的倍速。
   *
   * 默认**关闭** —— 倍速是很容易忘记自己改过的状态，自动继承时
   * 「下一集怎么变快了」比「每集重新调一次」更容易让人困惑，所以交给用户自己开。
   * 只在同一部剧（同一个 SeriesId）内继承，跨剧不继承。
   */
  val rememberSpeedPerSeries = preferenceStore.getBoolean("remember_speed_per_series", false)

  /**
   * 记住每部剧选择的音轨。
   *
   * 匹配用「语言 + 编码 + 声道数」指纹而不是 mpv 的 track id ——
   * 不同文件里同一条音轨的 id 可能因为多一条评论轨就整体错位。
   * 默认关闭，理由同 [rememberSpeedPerSeries]。
   */
  val rememberAudioTrackPerSeries = preferenceStore.getBoolean("remember_audio_track_per_series", false)

  /**
   * 记住的播放速度：SeriesKey → 倍速（JSON）。
   * 只在 [rememberSpeedPerSeries] 打开时写入；关掉后旧数据保留，重新打开仍然生效。
   */
  val rememberedSpeeds = preferenceStore.getString("remembered_speeds", "{}")

  /**
   * 记住的音轨指纹：SeriesKey → 指纹串（JSON）。
   * 只在 [rememberAudioTrackPerSeries] 打开时写入。
   */
  val rememberedAudioTracks = preferenceStore.getString("remembered_audio_tracks", "{}")

  /**
   * 记住每部剧选择的字幕轨。
   *
   * 与音轨同理，用「语言+编码」指纹匹配而不是 mpv 的 track id；
   * 匹配不到就什么都不做，交给 TrackSelector 的默认语言逻辑。
   * 默认关闭，理由同 [rememberSpeedPerSeries]。
   */
  val rememberSubtitleTrackPerSeries =
    preferenceStore.getBoolean("remember_subtitle_track_per_series", false)

  /**
   * 记住的字幕轨指纹：SeriesKey → 指纹串（JSON）。
   * 只在 [rememberSubtitleTrackPerSeries] 打开时写入。
   */
  val rememberedSubtitleTracks = preferenceStore.getString("remembered_subtitle_tracks", "{}")

  /**
   * 全局倍速记忆：下一次播放沿用上一次用过的倍速（跨剧、跨应用重启）。
   *
   * 与 [rememberSpeedPerSeries] 的分工：
   * - 本项是「全局默认」，打开后每次开新播放都先用这个倍速；
   * - [rememberSpeedPerSeries] 优先级更高，同一部剧内盖过本项。
   *
   * 默认**关闭** —— 会自动改播放速度属于侵入性行为，由用户自己决定。
   */
  val rememberGlobalSpeed = preferenceStore.getBoolean("remember_global_speed", false)

  /**
   * 上一次用过的倍速；0 表示还没记过。
   * 只在 [rememberGlobalSpeed] 打开时写入。
   */
  val lastPlaybackSpeed = preferenceStore.getFloat("last_playback_speed", 0f)

  val autoPiPOnNavigation = preferenceStore.getBoolean("auto_pip_on_navigation", false)

  /**
   * 自动裁切黑边（cropdetect）。
   *
   * 打开后：每次加载新文件都探测一次画面有效区域，裁掉四周黑边 ——
   * 典型场景是老片 / 4:3 片源在宽屏上留下的上下黑边，以及少数压制时自带黑边的片子。
   *
   * 默认**关闭**：探测要额外跑几百毫秒的滤镜，而且个别母带自带很窄的黑边，
   * 会被一并裁掉。是否值得取决于片源，交给用户自己决定。
   */
  val autoCropBlackBars = preferenceStore.getBoolean("auto_crop_black_bars", false)

  val keepScreenOnWhenPaused = preferenceStore.getBoolean("keep_screen_on_when_paused", false)

}
