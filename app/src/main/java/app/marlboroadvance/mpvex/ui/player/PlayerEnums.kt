package app.marlboroadvance.mpvex.ui.player

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R

enum class SingleActionGesture(
  @StringRes val titleRes: Int,
) {
  None(R.string.pref_gesture_double_tap_none),
  Seek(R.string.pref_gesture_double_tap_seek),
  PlayPause(R.string.pref_gesture_double_tap_play),
  Custom(R.string.pref_gesture_double_tap_custom),
}

enum class CustomKeyCodes(
  val keyCode: String,
) {
  DoubleTapLeft("MBTN_LEFT_DBL"),
  DoubleTapCenter("MBTN_MID_DBL"),
  DoubleTapRight("MBTN_RIGHT_DBL"),
  MediaPrevious("PREV"),
  MediaPlay("PLAYPAUSE"),
  MediaNext("NEXT"),
}

enum class Decoder(
  val title: String,
  val value: String,
) {
  AutoCopy("Auto", "auto-copy"),
  Auto("Auto", "auto"),
  SW("SW", "no"),
  HW("HW", "mediacodec-copy"),
  HWPlus("HW+", "mediacodec"),
  ;

  companion object {
    /**
     * 从 mpv 的 `hwdec-current` 取值映射到 [Decoder]。
     *
     * **不能用 `first { }`**：mpv 在不同平台 / 不同构建下可能返回表里没有的值
     * （`vulkan`、`vaapi`、`cuda`、`dxva2` …），GSY 内核也会走自己的一套命名，
     * 一旦匹配不到就是 `NoSuchElementException` 直接把播放页炸掉 ——
     * 而这里只是在 Compose 派生状态里算个图标，取不到值用 Auto 兜住即可。
     */
    fun getDecoderFromValue(value: String): Decoder =
      Decoder.entries.firstOrNull { it.value == value } ?: Decoder.Auto
  }
}

/**
 * **默认解码方式**（设置 → 解码器 →「默认解码器」）。
 *
 * 跟播放中面板里那个 [Decoder]（`hwdec-current` 的实时读数 + 临时切换）是两件事：
 * 那个是「现在这一条片子正在用什么」，这个是「以后每条片子默认用什么」。
 *
 * ## 五档的分工
 *
 * - **自动检测** 两档：先问设备有没有硬件视频解码器（[DecoderCapability]），
 *   有就按题目里的偏好走硬解，没有就干净地落回软解。适合不想研究这些名词的人。
 * - **硬件解码 / 硬件解码+ / 软件解码**：不问设备，用户说什么就是什么。
 *   出问题时用来做对照实验 —— 这是排查「花屏是硬解的锅还是软解的锅」最快的办法。
 *
 * ⚠️ 枚举的 `name` 是**持久化键**（`getEnum` 存 name），发布后不可改名：
 * `Auto` / `Hardware` / `HardwarePlus` / `Software` 都是 v218/v219 就写进用户机器里的值，
 * 这里只新增 `AutoHardwarePlus`，其余三个沿用原名、只改文案。
 */
enum class DefaultDecoder(
  @StringRes val titleRes: Int,
) {
  /**
   * 自动检测，**硬件解码（copyback）优先**。
   *
   * 设备有硬解器 ⇒ `mediacodec-copy,no`（编码不支持时由 mpv 的 `,no` 回退软解）；
   * 没有硬解器 ⇒ 直接 `no`。默认档。
   */
  Auto(R.string.pref_decoder_default_auto),

  /**
   * 自动检测，**硬件解码+（零拷贝）优先**。
   *
   * 设备有硬解器才敢走 `mediacodec` + `vo=mediacodec_embed` —— 没有硬解器的机器上
   * 这一组合会**直接黑屏**（vo 拿不到解码器的 surface 输出，也没有软解路径），
   * 所以它必须由探测兜住，不能像 [HardwarePlus] 那样无条件上。
   *
   * 没有硬解器时退回软解，vo 也随之回到 gpu-next（字幕 / 着色器恢复正常）。
   */
  AutoHardwarePlus(R.string.pref_decoder_default_auto_hwplus),

  /**
   * 硬件解码，**不回退**，仍然是 copyback。
   *
   * 给两类人：一是排查「这条片子到底是硬解在花屏、还是软解太慢」；
   * 二是某些机型上软解路径本身有问题（比如 10bit 软解发灰），宁可播不了也不想要软解。
   * 代价要说清楚：编码不支持时是**直接不出画面**，不是变慢。
   */
  Hardware(R.string.pref_decoder_default_hw),

  /**
   * 硬件解码+（零拷贝）：`hwdec=mediacodec` + `vo=mediacodec_embed`。
   *
   * 与 [Hardware] 的差别只在**搬运**：copyback 档是解码器把整帧回拷到 CPU 内存、
   * 再由 vo 重新上传成纹理（4K 一帧 12.4 MB，2160×3840@60 ≈ 1.5 GB/s），
   * 这一档让解码器输出 buffer 直接挂到 Surface 上，两次搬运都不存在 ——
   * 系统播放器 / MX Player 走的就是这条路径。4K 高帧率卡顿基本都出在这里。
   *
   * 代价：mpv 侧的叠加层与后处理全部失效 —— 字幕（外挂 / 内嵌都不显示）、OSD、
   * GLSL 着色器（Anime4K / 锐化）、截图。播放器界面本身是 Compose 独立图层，不受影响。
   * 所以它只能是一个**用户明确选择**的档位，不能做默认值。
   */
  HardwarePlus(R.string.pref_decoder_default_hwplus),

  /** 软件解码。兼容性最好（解码器不再是变量），代价是功耗、发热与 4K 高帧率可能跟不上 */
  Software(R.string.pref_decoder_default_sw),
  ;

  /** 两档「自动检测」：设置页要据此把探测结果显示在摘要里。 */
  val isAutoDetect: Boolean
    get() = this == Auto || this == AutoHardwarePlus

  /**
   * 结合设备探测结果算出实际要写进 mpv 的值。
   *
   * ⚠️ 千万不要把 [DecoderCapability.Level.Unknown] 当成「没有硬解」：
   * [DecoderCapability.Level.Unknown] 表示「还没探测完」，此处按有硬解处理
   * （＝旧版固定行为），探测完成后再由调用方自然拿到真值。
   */
  fun resolve(capability: DecoderCapability.Level): ResolvedDecoder {
    val hasHardware = capability != DecoderCapability.Level.None
    return when (this) {
      Auto -> if (hasHardware) COPYBACK_WITH_FALLBACK else SOFTWARE_ONLY
      AutoHardwarePlus -> if (hasHardware) ZERO_COPY_WITH_FALLBACK else SOFTWARE_ONLY
      Hardware -> COPYBACK_ONLY
      HardwarePlus -> ZERO_COPY_WITH_FALLBACK
      Software -> SOFTWARE_ONLY
    }
  }

  companion object {
    /**
     * `,no` 是**回退链**：前面那档用不了（编码不被 MediaCodec 支持、设备解码器拒绝
     * 这个规格）就退到下一档；「优先」型档位必须带 `no` 兜底，否则遇到不支持的片子
     * 直接播不了。
     */
    val COPYBACK_WITH_FALLBACK = ResolvedDecoder("mediacodec-copy,no", directOutput = false)

    val ZERO_COPY_WITH_FALLBACK = ResolvedDecoder("mediacodec,no", directOutput = true)

    /** 不回退：编码不支持时是直接不出画面，不是变慢。 */
    val COPYBACK_ONLY = ResolvedDecoder("mediacodec-copy", directOutput = false)

    val SOFTWARE_ONLY = ResolvedDecoder("no", directOutput = false)
  }
}

/**
 * 一次解码档位解析的结果。
 *
 * [directOutput] 与 `hwdec` **必须成对出现**：mpv 的零拷贝档只在
 * `vo=mediacodec_embed` 下成立，vo 是 gpu / gpu-next 时会静默跳过它、退到
 * `mediacodec-copy`（不报错）。所以这两件事不能拆成两个独立设置。
 */
data class ResolvedDecoder(
  val hwdec: String,
  val directOutput: Boolean,
) {
  /** 实际落在纯软解路径上（`vf` 这类只对软解有意义的开关用它做闸门）。 */
  val isSoftware: Boolean get() = hwdec == "no"
}

enum class Debanding(
  @StringRes val titleRes: Int,
) {
  None(R.string.player_sheets_deband_none),
  CPU(R.string.player_sheets_deband_cpu),
  GPU(R.string.player_sheets_deband_gpu),
}

enum class MPVProfile(
  @StringRes val displayNameRes: Int,
  val value: String,
) {
  Fast(R.string.i18n_mpv_fast, "fast"),
  Default(R.string.i18n_mpv_default, "default"),
  HighQuality(R.string.i18n_mpv_high_quality, "high-quality"),
  GpuHQ(R.string.i18n_mpv_gpu_hq, "gpu-hq"),
  LowLatency(R.string.i18n_mpv_low_latency, "low-latency"),
  SwFast(R.string.i18n_mpv_sw_fast, "sw-fast"),
  ;

  override fun toString(): String = name

  companion object {
    fun fromValue(value: String): MPVProfile = entries.firstOrNull { it.value == value } ?: Fast
  }
}

enum class Sheets {
  None,
  PlaybackSpeed,
  SubtitleTracks,
  AudioTracks,
  Decoders,
  More,
  Playlist,
  MediaDetails,
}

enum class Panels {
  None,
  SubtitleSettings,
  SubtitleDelay,
  AudioDelay,
}

sealed class PlayerUpdates {
  data object None : PlayerUpdates()

  data object MultipleSpeed : PlayerUpdates()

  data class DynamicSpeedControl(
    val speed: Float,
    val showFullOverlay: Boolean = true,
  ) : PlayerUpdates()

  /** Feedback for the visible, session-only two-finger zoom gesture. */
  data object PinchZoom : PlayerUpdates()

  data class HorizontalSeek(
    val startSeconds: Float,
    val targetSeconds: Float,
    val durationSeconds: Float,
  ) : PlayerUpdates() {
    val seekDeltaSeconds: Float get() = targetSeconds - startSeconds
  }

  data class ShowText(
    val value: String,
  ) : PlayerUpdates()
}
