package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.player.engine.VideoCrop

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.math.roundToInt
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


class PlayerViewModelProviderFactory(
  private val host: PlayerHost,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(
    modelClass: Class<T>,
    extras: CreationExtras,
  ): T {
    if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return PlayerViewModel(host) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@Suppress("TooManyFunctions")
class PlayerViewModel(
  private val host: PlayerHost,
) : ViewModel(),
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val json: Json by inject()

  /**
   * 「记住每部剧播放设置」的键：Emby 剧集是 SeriesId，电影是 ItemId，本地播放为 null。
   *
   * 由 PlayerActivity 从 intent 里读出来后写进来，切集时按下标换成新一集的键。
   * 用普通字段而不是 StateFlow：它只在「用户改速度 / 选音轨」的回调里被读一次，
   * 不需要驱动重组。
   */
  var seriesKey: String? = null

  /** Bound by the host to the actual playback row; never the globally selected server. */
  @Volatile
  var subtitleServer: app.marlboroadvance.mpvex.domain.emby.EmbyServer? = null

  /**
   * 自动连播下一集前的倒计时剩余秒数；0 表示不显示卡片。
   *
   * 播完一集时由播放页起一个每秒递减的协程，卡片上「立即播放 / 取消」
   * 两个按钮会取消这个协程并把它清零。
   */
  val autoplayCountdown = MutableStateFlow(0)

  /** 倒计时卡片里显示的下一集标题（取不到就是 null，卡片只显示秒数） */
  val autoplayNextTitle = MutableStateFlow<String?>(null)

  /**
   * 「下一集」悬停卡片是否可见（Yamby 的 `next_up_sec`）。
   *
   * 与 [autoplayCountdown] 的区别：那个只在**最后几秒**出现且带数字倒计时、
   * 是「马上就要切了」的催促；这个提前 30 秒左右出现、不带倒计时，
   * 是「后面还有一集，要不要现在就看」的提示 —— 长片尾（预告 / 片花）时特别有用。
   */
  val nextUpVisible = MutableStateFlow(false)

  /** 「下一集」卡片里显示的标题 */
  val nextUpTitle = MutableStateFlow<String?>(null)

  /**
   * 「跳过片头 / 跳过片尾」小胶囊的文案；null = 不显示。
   *
   * 由播放页的片头片尾盯梢协程写入：自动跳过关着、而播放头又落在
   * 片头 / 片尾区间里时给出一个可点的提示（点它 = 跳到段尾）。
   * 自动跳过开着时这里恒为 null（不需要用户动手）。
   */
  val skipSegmentLabel = MutableStateFlow<String?>(null)

  // MPV properties with efficient collection
  val paused by PlayerLib.propBoolean["pause"].collectAsState(viewModelScope)
  val pos by PlayerLib.propInt["time-pos"].collectAsState(viewModelScope)
  val duration by PlayerLib.propInt["duration"].collectAsState(viewModelScope)

  // ── 暂停写入通道（v179，见 init 里的消费者）──────────────────────────────

  /**
   * 「想要的暂停态」单槽，`null` = 还没有人要求过。
   *
   * 只由 [setPaused] 写，只由 [init] 里那条串行协程消费。存在的理由：
   * **同一文件上 pause/unpause 的落地顺序必须可控**（详见 init 里的注释）。
   */
  private val _pauseIntent = MutableStateFlow<Boolean?>(null)

  /**
   * 这次 seek 期间的暂停**是我们自己打上的** ⇒ 松手时必须由我们恢复。
   *
   * 刻意不再用 `paused` 的快照判断「用户本来是不是在播」：那个快照来自
   * `propBoolean` 的 StateFlow，是**异步**值 —— 上一次 seek 的恢复还没落地时它
   * 仍然是 `true`，于是这一次就被误判成「用户本来就在暂停」⇒ 松手后不再恢复
   * ⇒ 播放永久停住。这是「拖一下就暂停」的直接成因，见 [beginSeekPause]。
   */
  @Volatile
  private var seekPauseApplied = false

  /** [seekPauseApplied] 的安全阀：手势被中途掐断（`onValueChangeFinished` 不触发）时的最后一道网。 */
  private var seekPauseSafetyJob: Job? = null

  /** 安全阀最近一次续期的时间戳（拖动每帧都会 seek，别每帧都重建协程）。 */
  private var seekPauseRenewedAt = 0L

  // ── 网络加载速度（播放器顶栏的指示器）──
  private val _netSpeedBytesPerSec = MutableStateFlow<Long?>(null)

  /**
   * 当前视频的网络加载速度（字节/秒），约 1 秒刷新一次。
   *
   * null = 指示器整体隐藏：当前流不是网络流（本地文件上
   * 显示「0 KB/s」只会造成困惑）。值为 0 表示「缓冲已满 / 暂停中，没有在下载」，
   * 此时指示器显示 0 KB/s 而不是消失 —— 数值归零是诚实的，忽隐忽现反而让人以为坏了。
   */
  val netSpeedBytesPerSec = _netSpeedBytesPerSec.asStateFlow()

  // High-precision position and duration for smooth seekbar
  private val _precisePosition = MutableStateFlow(0f)
  val precisePosition = _precisePosition.asStateFlow()

  private val _cacheTimelineNode = MutableStateFlow<`is`.xyz.mpv.MPVNode?>(null)
  val cacheTimelineNode = _cacheTimelineNode.asStateFlow()

  /** mpv 自己报的 `duration`（秒）。> 0 才可信，见 [preciseDuration]。 */
  private val _preciseDuration = MutableStateFlow(0f)

  /**
   * 兜底时长（秒）：mpv 报不出来、或报的值已被证伪时，由别的来源探到的值。
   *
   * 写入方只有一个 —— [applyProbedDuration]，由播放页的时长看守协程调用。
   */
  private val _probedDuration = MutableStateFlow(0f)

  /**
   * mpv 报的时长是否**已被证伪**。
   *
   * 证伪的依据只有一个，而且是物理事实：**已经播到的位置超过了声称的总时长**
   * （`.mts` 上很常见 —— mpv 会把时长报成一个很小的值，比如播到 15 秒、总时长还写着 8 秒）。
   * 一旦置位，[preciseDuration] 就彻底不再采信 mpv 的值，只用探针值。
   *
   * 由播放页的时长看守协程通过 [markDurationUntrusted] 置位，换片时随
   * [resetProbedDuration] 一起复位。
   */
  private val _durationUntrusted = MutableStateFlow(false)

  /**
   * 进度条用的总时长（秒）—— **mpv 的真值与兜底值合成后的结果**。
   *
   * ## 为什么需要这一层
   *
   * `.mts` / MPEG-TS 这类容器**没有内嵌索引**，时长只能靠「seek 到文件尾、读最后一个
   * PTS」算出来。取流层一旦不能随机读取（或那一步读到的并不是真正的尾部），mpv 的
   * `duration` 就只有两种坏法：
   *  · **恒为 0** —— 进度条显示 00:00、拖不动，连带「已看/续播」（都读 duration）一起失效；
   *  · **报一个很小的值** —— 播到 15 秒了总时长还写着 8 秒，进度条一开就顶到 100%。
   *
   * 而同一个地址交给 GSY 却正常 —— ExoPlayer / Android MediaExtractor 用的是
   * 「文件长度 ÷ 平均码率」估算，**不需要 seek 到尾部**。所以合成规则是：
   *  ① [markDurationUntrusted] 置位过 ⇒ 一律用探针值（mpv 已被证伪）；
   *  ② mpv 报 0 ⇒ 用探针值顶上；
   *  ③ mpv 报的值**不足探针值一半** ⇒ 判为伪值，仍用探针值；
   *  ④ 两者都拿不到 ⇒ 0，UI 侧如实显示 00:00（不编造时长）。
   */
  val preciseDuration: StateFlow<Float> =
    combine(_preciseDuration, _probedDuration, _durationUntrusted) { mpvDuration, probed, untrusted ->
      when {
        untrusted && probed > 0f -> probed
        mpvDuration <= 0f -> probed
        probed <= 0f -> mpvDuration
        mpvDuration < probed * 0.5f -> probed
        else -> mpvDuration
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

  /**
   * 写入「兜底时长」（秒）。
   *
   * 只在两种情况下生效：mpv 还没给出时长（0），或 mpv 的值已经被证伪
   * （[markDurationUntrusted]）。其余情况 mpv 一旦报出真值，永远以它为准。
   * 传入非法值（NaN / 负数 / 0）一律忽略，避免把进度条弄成空值。
   */
  fun applyProbedDuration(seconds: Float) {
    if (!seconds.isFinite() || seconds <= 0f) return
    if (!_durationUntrusted.value && _preciseDuration.value > 0f) return
    _probedDuration.value = seconds
  }

  /**
   * 宣布「mpv 报的这个时长不可信」。
   *
   * 判据（由播放页给）：**已播位置已经超过声称的总时长** —— 这是物理矛盾，不需要再讨论。
   * 置位后 mpv 的值直接被忽略，见 [preciseDuration] 的合成规则①。
   */
  fun markDurationUntrusted() {
    _durationUntrusted.value = true
  }

  /** 换片时清掉上一部片的兜底时长与「已证伪」标记，否则新片会顶着旧片的时长画进度条。 */
  fun resetProbedDuration() {
    _probedDuration.value = 0f
    _durationUntrusted.value = false
    // ⚠️ 必须把 mpv 那一份也清掉（v179）：不清的话，新片报出时长之前，
    // 进度条画的是**上一部片**的时长；更糟的是 [acceptMpvDuration] 的
    // 「同片跳变」闸门会拿旧片的值当基准，把新片（更长的）正常时长误判成离谱值。
    // 清成 0 之后不用等 StateFlow 重发 —— 时长看守每秒都会把 mpv 当前值喂回
    // [acceptMpvDuration]，最迟一秒就能恢复（见 `PlayerActivity.startDurationWatchdog`）。
    _preciseDuration.value = 0f
  }

  /**
   * 采信一条 mpv 报的 `duration`（秒）—— **带「离谱值」闸门**（v179）。
   *
   * ## 为什么需要闸门
   *
   * 以前这里是裸写（`if (dur > 0) _preciseDuration.value = dur`）。但 mpv 的 `duration`
   * **不是「报出来就定死」的属性**：TS / 直播录制这类没有内嵌索引的容器，时长靠
   * 「seek 到尾部读最后一个 PTS」算出来，那次 seek 一旦落在坏包上、或上游给的
   * 文件长度是错的，它就会重算出一个大得离谱的数（用户实测：正常 40 分钟的片，
   * 放着放着右端变成几百小时）。
   *
   * 而 [preciseDuration] 原来的合成规则只拦「报小了」（`mpv < probed × 0.5`），
   * 对「报大了」完全不设防 —— 所以巨值会直接生效、并且**永远不会被推翻**
   * （看守协程的 overflow 判据是 `pos > duration`，位置永远追不上几百小时）。
   *
   * ## 两道闸（任一命中：拒绝写入 + 宣布 mpv 不可信）
   *
   *  ① 有探针值时 `mpv > probed × [DURATION_OVERSIZE_RATIO]`。探针来自
   *     `MediaMetadataRetriever`（与 GSY 同源）或 `file-size ÷ bitrate`，
   *     与「seek 到尾部」是两条互不相干的路，两边差一个量级就说明 mpv 这次算错了。
   *  ② 同一份文件内的跳变 `mpv > 上一版 × [DURATION_OVERSIZE_RATIO]`。
   *     换片时 [resetProbedDuration] 会清零，所以这里比的一定是**同一份文件**
   *     的前后两次采样 —— 「放着放着变几百小时」正是这一条能抓住的形态。
   *
   * 拒绝写入 ≠ 时长没了：`_preciseDuration` 保留上一次的合理值，进度条继续用它画；
   * 同时 `_durationUntrusted` 置位，看守探到真值后会接管（见 [preciseDuration] 规则①）。
   */
  fun acceptMpvDuration(seconds: Double) {
    val value = seconds.toFloat()
    if (!value.isFinite() || value <= 0f) return

    val probed = _probedDuration.value
    if (probed > 0f && value > probed * DURATION_OVERSIZE_RATIO) {
      _durationUntrusted.value = true
      return
    }
    val previous = _preciseDuration.value
    if (previous > 0f && value > previous * DURATION_OVERSIZE_RATIO) {
      _durationUntrusted.value = true
      return
    }
    _preciseDuration.value = value
  }

  /** 当前可用的总时长（秒）：mpv 真值优先，其次探针兜底；0 = 两者都拿不到。 */
  fun effectiveDurationSeconds(): Float = preciseDuration.value

  // Audio state
  val currentVolume = MutableStateFlow(host.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
  val volumeGestureFraction = MutableStateFlow(
    currentVolume.value.toFloat() / host.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
  )
  private val systemVolumeWrites = Channel<Int>(Channel.CONFLATED)
  private val volumeBoostCap by PlayerLib.propInt["volume-max"].collectAsState(viewModelScope)

  init {
    // Poll precise position only when playing
    // ⚠️ **必须跑在 IO 线程上，不能留在 viewModelScope 默认的 Main。**
    // `MPVLib.getPropertyDouble` 是同步 JNI 调用，客户端线程会去抢 mpv 的
    // 播放线程锁（`mpctx->lock`）。而 demux / 解码线程每秒要持锁几十次，
    // **码率越高、单帧越大，持锁时间越长** —— 4K 原盘（70GB 那种）上主线程
    // 每 42ms 抢一次锁，抢不到就阻塞，直接和「渲染 + 触摸事件」抢同一个线程，
    // 观感就是**整个 App 卡成幻灯片**（不是视频卡，是 UI 卡）。
    // 挪到 `Dispatchers.Default` 之后：读不到锁只影响这个采样协程自己，
    // 位置稍晚一拍对进度条完全无感，但主线程再也不会被 JNI 卡住。
    viewModelScope.launch(Dispatchers.Default) {
      while (isActive) {
        val time = PlayerLib.getPropertyDouble("time-pos")
        if (time != null) {
          _precisePosition.value = time.toFloat()
        }
        delay(42) // ~24fps updates
      }
    }

    // Update precise duration when the integer duration changes (avoid polling)
    // ⚠️ 不能裸写 `_preciseDuration`：mpv 会在播放中途重算时长，偶尔算出大得离谱的值
    // （TS 类容器靠 seek 到尾部读 PTS，读到坏包就翻车）。闸门逻辑见 [acceptMpvDuration]。
    viewModelScope.launch {
      PlayerLib.propInt["duration"].collect { _ ->
        PlayerLib.getPropertyDouble("duration")?.let(::acceptMpvDuration)
      }
    }

    viewModelScope.launch {
      PlayerLib.propNode["demuxer-cache-state"].collect { node ->
        if (node != null) _cacheTimelineNode.value = node
      }
    }

    // ── 暂停写入的**单槽串行**通道 ──
    // 这里必须串行：以前每个 pause()/unpause() 各起一个 IO 协程去写 mpv 属性，
    // **同一个文件上「暂停 → 恢复」的落地顺序没有任何保证**。进度条松手时会先
    // unpause 再（下一轮拖动的第一帧）pause，两个协程并发 ⇒ 谁最后落地看调度，
    // 结果是「暂停」赢下来，而 UI 侧那次收尾又以为「本来就在暂停」不再恢复
    // ⇒ 用户看到的就是「拖一下/快进一下就永久暂停了」。
    // 单槽 StateFlow 天然「最后一次意图胜出」，且 collect 是串行消费 ⇒ 顺序确定。
    viewModelScope.launch(Dispatchers.IO) {
      _pauseIntent.filterNotNull().collect { wantPaused ->
        if (wantPaused) {
          PlayerLib.setPropertyBoolean("pause", true)
          withContext(Dispatchers.Main) { host.abandonAudioFocus() }
        } else {
          withContext(Dispatchers.Main) { host.requestAudioFocus() }
          PlayerLib.setPropertyBoolean("pause", false)
        }
      }
    }

    // ── 网络加载速度：固定 1 秒一拍 ──
    // 数据源与归零策略见 [MpvNetSpeedSampler] / [netSpeedBytesPerSec] 的注释。
    // 轮询而非 observe：cache-speed 的属性变更通知不保证按秒触发，定时读最稳。
    // ⚠️ 同样放 Default：一条采样要连读好几个 mpv 属性（JNI + 播放线程锁），
    // 放在主线程上就又多一处周期性卡顿源（理由见上面 time-pos 那段）。
    viewModelScope.launch(Dispatchers.Default) {
      while (isActive) {
        val paused = PlayerLib.getPropertyBoolean("pause") == true
        _netSpeedBytesPerSec.value = MpvNetSpeedSampler.sample(
          isPaused = paused,
        )
        delay(NET_SPEED_POLL_INTERVAL_MS)
      }
    }
  }

  val maxVolume = host.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

  init {
    viewModelScope.launch(Dispatchers.IO) {
      for (step in systemVolumeWrites) {
        host.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
      }
    }
  }

  /** 全部轨道，由 mpv 的 `track-list` 反序列化。 */
  private val allTracks: StateFlow<List<TrackNode>> =
    PlayerLib.propNode["track-list"]
      .map { node -> node?.toObject<List<TrackNode>>(json) ?: emptyList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  val subtitleTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { list -> list.filter { it.isSubtitle }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  val audioTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { list -> list.filter { it.isAudio }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  // UI state
  private val _controlsShown = MutableStateFlow(false)
  val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()

  /**
   * 首帧**已经真正画到屏幕上了**吗 —— 「出画面前」的加载层靠它收起。
   *
   * ## 为什么不用现成的 `isReady`
   *
   * `PlayerActivity.isReady` 只在 `FILE_LOADED` 时置真、且**换片时不会复位**
   * （只在 `onDestroy` 里清零）⇒ 拿来当加载判据，第二集开始时加载层就不会再出现了。
   *
   * ## 为什么不用 `vo-configured`
   *
   * mpv 文档对它的定义是「VO 是否已配置好，通常对应视频窗口是否可见」——
   * 但它在 `vo_reconfig2()` 返回时就为 yes，**早于第一帧 `flip_page`**。
   * 拿它当判据 = 加载动画先消失、再黑一下、画面才浮现，**比不显示更难看**。
   *
   * ⇒ 用 `MPV_EVENT_PLAYBACK_RESTART`：mpv 的日志顺序是
   *   `first video frame after restart shown` → `playback-restart`，
   *   也就是首帧上屏之后才发。这与视界流那条的教训一致
   *   （见 `FeedViewModel.buffered` 的注释：判据早一步就会「转圈消失→黑屏→画面」）。
   */
  private val _firstFrameRendered = MutableStateFlow(false)
  val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

  /** 换片 / 重新加载时把「首帧已出」打回 false，让加载层重新出现。 */
  fun onLoadingNewFile() {
    _firstFrameRendered.value = false
    _cacheTimelineNode.value = null
    _precisePosition.value = 0f
    horizontalSeekPreview.value = null
    // 换片后 mpv 的 `pause` 会被 loadfile 重置，留在通道里的旧意图（多半是 `true`）
    // 已经不代表事实了 —— 不清的话 [beginSeekPause] 会拿它当「当前已暂停」，
    // 于是换片后的第一次拖动不会暂停（只是解码更吃力，不会卡住播放，但要修掉）。
    _pauseIntent.value = null
  }

  /** mpv 报 `PLAYBACK_RESTART`（首帧已上屏）时调用。 */
  fun onFirstFrameRendered() {
    _firstFrameRendered.value = true
  }

  /**
   * 播放失败原因（非空 ⇒ 界面上显示错误卡片）。
   *
   * 与 [firstFrameRendered] 是两回事：那个管「还在等画面」，这个管「画面永远不会来了」。
   * 写入方有两个，都发生在**播放之前**：
   * · Emby 侧预解析判定死链 ⇒ 通过 `play_error` extra 带进播放页；
   * · 用户点「重试」时重新探测仍被拒（见 [PlayerActivity.retryPlayback]）。
   *
   * 为什么不靠 mpv 的 `end-file` 事件兜底：死链会让 libmpv 在 native 层直接崩掉
   * （整个 App 消失），`end-file` 根本来不及发出。所以判断必须发生在播放之前。
   */
  private val _playbackError = MutableStateFlow<String?>(null)
  val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

  /** 显示播放错误卡片（`message` 是已经写好的人话，直接展示）。 */
  fun showPlaybackError(message: String) {
    _playbackError.value = message
  }

  /** 关掉错误卡片（重试 / 换片前调用）。 */
  fun clearPlaybackError() {
    _playbackError.value = null
  }

  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()

  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)
  val currentBrightness =
    MutableStateFlow(
      runCatching {
        Settings.System
          .getFloat(host.hostContentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .normalize(0f, 255f, 0f, 1f)
      }.getOrElse { 0f },
    )

  val sheetShown = MutableStateFlow(Sheets.None)
  val panelShown = MutableStateFlow(Panels.None)

  // Seek state
  private val _seekText = MutableStateFlow<String?>(null)
  val seekText: StateFlow<String?> = _seekText.asStateFlow()

  private val _doubleTapSeekAmount = MutableStateFlow(0)
  val doubleTapSeekAmount: StateFlow<Int> = _doubleTapSeekAmount.asStateFlow()

  private val _isSeekingForwards = MutableStateFlow(false)
  val isSeekingForwards: StateFlow<Boolean> = _isSeekingForwards.asStateFlow()

  // Media title for subtitle association
  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null

  // External subtitle tracking
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()
  
  // Mapping from mpv internal path/URI to the original source URI (resolves deletion issues)
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  init {
    // Track selection is now handled by TrackSelector in PlayerActivity
    
    // Observe volume boost cap changes to enforce limits dynamically (in PiP)
    viewModelScope.launch {
      audioPreferences.volumeBoostCap.changes().collect { cap ->
        val maxVol = 100 + cap
        runCatching {
          PlayerLib.setPropertyString("volume-max", maxVol.toString())
          
          // Clamp current volume if it exceeds the new limit
          val currentMpvVol = PlayerLib.getPropertyInt("volume") ?: 100
          if (currentMpvVol > maxVol) {
            PlayerLib.setPropertyInt("volume", maxVol)
          }
        }.onFailure { e ->
          Log.e(TAG, "Error setting volume-max: $maxVol", e)
        }
      }
    }

  }

  // Cached values
  private val doubleTapToSeekDuration by lazy { gesturePreferences.doubleTapToSeekDuration.get() }
  private val inputMethodManager by lazy {
    host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
  }

  // Seek coalescing for smooth performance
  private var pendingSeekOffset: Int = 0
  private var seekCoalesceJob: Job? = null

  private companion object {
    const val TAG = "PlayerViewModel"
    const val SEEK_COALESCE_DELAY_MS = 60L

    /**
     * 拖动进度条期间，两次**真正下发**的 mpv seek 之间的最小间隔。
     *
     * 视界流 `previewSeek` 同款口径（那边是 120ms）：保住「画面跟手」的同时，
     * 不让每个移动事件都让解码器在网络流上重新定位一遍。拖完的最终落点
     * 不靠节流那条 —— 由 `commitScrubSeek` 在松手时强制保证。
     */
    const val SCRUB_SEEK_INTERVAL_MS = 150L

    /**
     * 「mpv 报的时长超过参照值多少倍就判为离谱」。
     *
     * 4 倍是刻意留宽的：真实片源里 `file-size ÷ bitrate` 对 VBR 的估算误差通常
     * 在 ±30% 内，两倍已经很反常；而 TS 读坏包算出来的巨值是**一个量级以上**的偏差
     * （40 分钟 → 几百小时）。取 4 既不会误伤正常的重算，也能一刀切掉巨值。
     */
    const val DURATION_OVERSIZE_RATIO = 4f

    /** seek 暂停的安全阀时长：远大于任何一次正常拖动，只在收尾回调被吞掉时才到期。 */
    const val SEEK_PAUSE_SAFETY_MS = 6_000L

    /** 安全阀续期的最小间隔（拖动每帧都会 seekTo）。 */
    const val SEEK_PAUSE_RENEW_INTERVAL_MS = 1_000L

    /** 网络加载速度的采样周期：1 秒一拍，与常见播放器（VLC / PotPlayer）的刷新节奏一致 */
    const val NET_SPEED_POLL_INTERVAL_MS = 1_000L
    val VALID_SUBTITLE_EXTENSIONS =
      setOf(
        // Common & modern
        "srt", "vtt", "ass", "ssa",
        // DVD / Blu-ray
        "sub", "idx", "sup",
        // Streaming / XML / Professional
        "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",
        // Online platforms
        "sbv", "srv1", "srv2", "srv3", "json",
        // Legacy & niche
        "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",
        // Broadcast captions
        "scc", "vttx",
        // Karaoke / lyrics
        "lrc", "krc",
        // Fallback / raw text
        "txt", "pgs"
      )
  }

  // ==================== Decoder ====================

  // ==================== Audio/Subtitle Management ====================

  /**
   * mpv 加载新文件时清空外挂字幕跟踪表。
   *
   * mpv 的轨道列表随文件重建，旧的 URI 记录不再对应任何轨道；不清的话恢复流程会以为
   * 「这条字幕已经加过了」而跳过，字幕就再也加不回来了（同一部片重开时必然踩到）。
   */
  fun resetExternalSubtitles() {
    _externalSubtitles.clear()
    mpvPathToUriMap.clear()
  }

  /** 用户主动添加外挂字幕（即发即忘）。要「加完再选中」请用 [loadSubtitle]。 */
  fun addSubtitle(uri: Uri, select: Boolean = true, silent: Boolean = false) {
    val server = subtitleServer
    viewModelScope.launch(Dispatchers.IO) {
      loadSubtitle(uri, select, silent, server)
    }
  }

  /**
   * 把外挂字幕真正交给 mpv，**在调用方协程里顺序执行**。
   *
   * 与 [addSubtitle] 的唯一区别是「这里是挂起函数」：返回时 `sub-add` 命令已经下发。
   * 恢复播放状态必须用这个版本 —— `addSubtitle` 是即发即忘的，而恢复流程紧接着就要用
   * 存档里的 `sid` 选中字幕，那时轨道还没建出来，mpv 会把这次选择当成「不存在的轨道」
   * 直接丢弃。表现就是「上次加的外挂字幕，重开 app 就没了」。
   *
   * 注意：命令下发 ≠ 轨道已就绪。mpv 挂轨道是异步的，之后还要 [awaitTrackCountAtLeast] 等一拍。
   */
  internal suspend fun loadSubtitle(
    uri: Uri,
    select: Boolean,
    silent: Boolean,
    server: app.marlboroadvance.mpvex.domain.emby.EmbyServer? = subtitleServer,
  ) {
    val uriString = uri.toString()
    if (_externalSubtitles.contains(uriString)) {
      Log.d("PlayerViewModel", "Subtitle already tracked, skipping")
      return
    }

    runCatching {
      val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"

      if (!isValidSubtitleFile(fileName)) {
        if (!silent) withContext(Dispatchers.Main) { showToast("字幕格式不支持") }
        return
      }

      // Take persistent URI permission for content:// URIs
      if (uri.scheme == "content") {
        try {
          host.context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        } catch (e: SecurityException) {
          // Permission already granted, not available, or not needed (e.g. from tree).
          Log.i("PlayerViewModel", "Persistent permission not taken for $uri (may already have it via tree)")
        }
      }

      val mpvPath = app.marlboroadvance.mpvex.utils.media.SubtitleOps.readablePath(host.context, uri, server)
        ?: return@runCatching

      // Track the source so detaching a subtitle never deletes its document.
      mpvPathToUriMap[mpvPath] = uriString

      PlayerLib.command("sub-add", mpvPath, if (select) "select" else "auto")

      // Track external subtitle URI for persistence
      if (!_externalSubtitles.contains(uriString)) _externalSubtitles.add(uriString)

      val displayName = fileName.take(30).let { if (fileName.length > 30) "$it..." else it }
      if (!silent) withContext(Dispatchers.Main) { showToast("已添加 $displayName") }
    }.onFailure {
      if (!silent) withContext(Dispatchers.Main) { showToast("加载字幕失败") }
    }
  }

  /**
   * 等 mpv 的轨道列表涨到至少 [target] 条（最多等 [timeoutMs]）。
   *
   * `sub-add` 是异步命令：命令返回那一刻轨道还没进 `track-list`，这时候去设 `sid`
   * 会被 mpv 当成「不存在的轨道」丢掉。恢复外挂字幕时必须等这一拍。
   */
  internal suspend fun awaitTrackCountAtLeast(
    target: Int,
    timeoutMs: Long = 1000,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if ((PlayerLib.getPropertyInt("track-list/count") ?: 0) >= target) return
      delay(30)
    }
  }

  fun setMediaTitle(mediaTitle: String) {
    if (currentMediaTitle != mediaTitle) {
      currentMediaTitle = mediaTitle
      lastAutoSelectedMediaTitle = null
      // Clear external subtitles when media changes
      _externalSubtitles.clear()
      // A pinch zoom is session-only. Reset it for the next media item without restoring any
      // removed zoom/pan/aspect preference chain.
      PlayerLib.setPropertyDouble("video-zoom", 0.0)
    }
  }


  fun getPrimarySubtitleId(): Int =
    PlayerLib.getPropertyString("sid")?.toIntOrNull() ?: 0

  fun getSecondarySubtitleId(): Int =
    PlayerLib.getPropertyString("secondary-sid")?.toIntOrNull() ?: 0

  fun removeSubtitle(id: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val primarySid = getPrimarySubtitleId()
      val secondarySid = getSecondarySubtitleId()

      // If removing the primary subtitle while a secondary subtitle is active,
      // promote secondary to primary so the remaining subtitle stays at the bottom.
      // Must clear secondary-sid FIRST so mpv does not reject assigning that track to sid.
      if (id == primarySid) {
        if (secondarySid > 0 && secondarySid != id) {
          PlayerLib.setPropertyString("secondary-sid", "no")
          PlayerLib.setPropertyInt("sid", secondarySid)
        } else {
          PlayerLib.setPropertyString("sid", "no")
        }
      } else if (id == secondarySid) {
        PlayerLib.setPropertyString("secondary-sid", "no")
      }

      // Find the subtitle track info before removing
      val tracks = subtitleTracks.value
      val trackToRemove = tracks.firstOrNull { it.id == id }

      // Removing an external subtitle only detaches it from the player and stops
      // tracking it. It must NOT delete the underlying file from disk: external
      // subtitles are user-provided (e.g. downloaded with another app), so
      // deleting them would cause unexpected data loss.
      if (trackToRemove?.external == true && trackToRemove.externalFilename != null) {
        val mpvPath = trackToRemove.externalFilename
        val originalUriString = mpvPathToUriMap[mpvPath] ?: mpvPath
        _externalSubtitles.remove(originalUriString)
        mpvPathToUriMap.remove(mpvPath)
      }

      PlayerLib.command("sub-remove", id.toString())
    }
  }

  fun toggleSubtitle(id: Int) {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()

    when {
      id == primarySid -> {
        // User disabled the primary subtitle while a secondary subtitle is active:
        // Promote secondary to primary so the single remaining subtitle is positioned at the bottom.
        // MUST clear secondary-sid FIRST so mpv does not reject assigning it to sid.
        if (secondarySid > 0 && secondarySid != id) {
          PlayerLib.setPropertyString("secondary-sid", "no")
          PlayerLib.setPropertyInt("sid", secondarySid)
        } else {
          PlayerLib.setPropertyString("sid", "no")
        }
      }
      id == secondarySid -> {
        // User disabled the secondary subtitle (top). Primary stays at bottom.
        PlayerLib.setPropertyString("secondary-sid", "no")
      }
      primarySid <= 0 -> {
        if (secondarySid > 0) {
          if (secondarySid == id) {
            PlayerLib.setPropertyString("secondary-sid", "no")
            PlayerLib.setPropertyInt("sid", id)
          } else {
            val prevSecondary = secondarySid
            PlayerLib.setPropertyString("secondary-sid", "no")
            PlayerLib.setPropertyInt("sid", prevSecondary)
            PlayerLib.setPropertyInt("secondary-sid", id)
          }
        } else {
          PlayerLib.setPropertyInt("sid", id)
        }
      }
      secondarySid <= 0 -> {
        if (primarySid != id) {
          PlayerLib.setPropertyInt("secondary-sid", id)
        }
      }
      else -> {
        // Both slots occupied; replace primary subtitle
        PlayerLib.setPropertyInt("sid", id)
      }
    }
  }

  /**
   * Ensures that if only one subtitle track is active, it is always the primary
   * subtitle (`sid`), which is positioned at the bottom of the screen.
   */
  fun normalizeSubtitles() {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()
    if (primarySid <= 0 && secondarySid > 0) {
      PlayerLib.setPropertyString("secondary-sid", "no")
      PlayerLib.setPropertyInt("sid", secondarySid)
    }
  }

  fun isSubtitleSelected(id: Int): Boolean {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()
    return (id == primarySid && primarySid > 0) || (id == secondarySid && secondarySid > 0)
  }

  private fun getFileNameFromUri(uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        host.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

      "file" -> uri.lastPathSegment
      else -> uri.lastPathSegment
    }

  private fun isValidSubtitleFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in VALID_SUBTITLE_EXTENSIONS

  // ==================== Playback Control ====================

  fun pauseUnpause() {
    viewModelScope.launch(Dispatchers.IO) {
      setPaused(PlayerLib.getPropertyBoolean("pause") != true)
    }
  }

  fun pause() = setPaused(true)

  fun unpause() = setPaused(false)

  /**
   * 请求「暂停 / 恢复」，**不做任何 JNI 调用**，只把一个意图塞进单槽通道，
   * 由 [init] 里那条串行协程按顺序落到 mpv 上。
   *
   * 为什么不让调用方各自起协程去写：同一个文件上「暂停 → 恢复」两次写入若并发，
   * 落地顺序是不确定的，会出现「最后一次意图是恢复，但最终状态是暂停」。
   * 单槽 StateFlow 保证**最后一次意图胜出**，collect 串行保证**不会交错**。
   */
  private fun setPaused(wantPaused: Boolean) {
    _pauseIntent.value = wantPaused
  }

  /**
   * 开始 seek：若当前正在播放，先暂停（拖动时解码器不必追着跳变的位置跑）。
   *
   * ## 为什么不用「记下 paused 再恢复」那套
   *
   * 以前是在调用点 `wasPlayerAlreadyPaused = paused ?: false`。`paused` 是
   * `propBoolean["pause"]` 的 StateFlow 快照，**异步**更新：上一次 seek 的恢复
   * 还没落地时它仍然是 `true` ⇒ 这次被判成「用户本来就在暂停」⇒ 松手后不恢复。
   * 连续拖动两次必现，表现就是「拖一下就永久暂停」。
   *
   * 现在改成**自己记账**（[seekPauseApplied]）：只有「我们亲手打上的暂停」才由我们
   * 撤销，用户自己按的暂停永远不动。判断当前是否已暂停时先看通道里有没有未落地的
   * 意图，没有再同步读一次 native（一次调用，可接受；避免快照滞后）。
   */
  fun beginSeekPause() {
    if (seekPauseApplied) return
    val alreadyPaused = _pauseIntent.value ?: (PlayerLib.getPropertyBoolean("pause") ?: false)
    if (alreadyPaused) return
    seekPauseApplied = true
    setPaused(true)
    armSeekPauseSafetyNet()
  }

  /**
   * 结束 seek：只有当暂停是 [beginSeekPause] 打上的，才恢复播放。
   *
   * 幂等 —— 进度条有三条手势路径（外层 tap / 外层 drag / 内层 Slider）都会调
   * `onValueChangeFinished`，同一次拖动可能进来两次；第二次在这里直接 no-op，
   * 不会把「用户自己暂停的播放」误恢复。
   */
  fun endSeekPause() {
    if (!seekPauseApplied) return
    seekPauseApplied = false
    seekPauseSafetyJob?.cancel()
    seekPauseSafetyJob = null
    setPaused(false)
  }

  /**
   * 给安全阀续期。拖动期间 [seekTo] 每帧都会调，所以只要手还在屏幕上就不会到期。
   */
  private fun armSeekPauseSafetyNet() {
    seekPauseSafetyJob?.cancel()
    seekPauseSafetyJob =
      viewModelScope.launch {
        delay(SEEK_PAUSE_SAFETY_MS)
        // 到点了还挂着 ⇒ 收尾回调一定被吞掉了（手指被父容器抢走、进度条随控制条
        // 一起被移除、返回手势打断…）。这时宁可多恢复一次播放，也不能让用户
        // 对着一个静止的画面到处找播放键。
        if (seekPauseApplied) {
          seekPauseApplied = false
          setPaused(false)
          Log.w(TAG, "seek-pause safety net fired")
        }
      }
  }

  // ==================== UI Control ====================

  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.isAppearanceLightStatusBars = false
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      // Defensive: InsetsController animation can crash under FD pressure
      // (e.g. during high-res HEVC playback on certain devices)
      Log.e(TAG, "Failed to show system bars", e)
    }
    _controlsShown.value = true
  }

  fun hideControls() {
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars", e)
    }
    _controlsShown.value = false
    _seekBarShown.value = false
  }

  fun autoHideControls() {
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars", e)
    }
    _controlsShown.value = false
    _seekBarShown.value = true
  }

  fun showSeekBar() {
    if (sheetShown.value == Sheets.None) {
      _seekBarShown.value = true
    }
  }

  fun hideSeekBar() {
    _seekBarShown.value = false
  }

  fun lockControls() {
    _areControlsLocked.value = true
  }

  fun unlockControls() {
    _areControlsLocked.value = false
  }

  // ==================== Seeking ====================

  // ── 拖动节流（v217）──
  // 一条在途的绝对 seek（单飞：新的取消旧的，避免乱序落地）
  private var scrubSeekJob: Job? = null
  private var scrubThrottleJob: Job? = null
  // 上次真正下发拖动 seek 的时刻（单调时钟）
  private var lastScrubCommitAt = 0L
  // 拖动的最新目标（手指当前指向的位置），松手时用它保证最终落点
  private var lastScrubTarget = -1.0
  // 最近一次已经发给 mpv 的拖动目标（避免收尾重复下发同一位置）
  private var lastDispatchedScrub = -1.0

  /** Numeric preview target rendered immediately, independent of throttled native position updates. */
  val horizontalSeekPreview = MutableStateFlow<PlayerUpdates.HorizontalSeek?>(null)

  fun updateHorizontalSeekPreview(startSeconds: Float, targetSeconds: Float, durationSeconds: Float) {
    horizontalSeekPreview.value = PlayerUpdates.HorizontalSeek(startSeconds, targetSeconds, durationSeconds)
  }

  fun finishHorizontalSeekPreview(delayMillis: Long = 300L) {
    val preview = horizontalSeekPreview.value ?: return
    viewModelScope.launch {
      delay(delayMillis)
      if (horizontalSeekPreview.value === preview) {
        horizontalSeekPreview.value = null
        hideSeekBar()
      }
    }
  }

  fun clearHorizontalSeekPreview() {
    horizontalSeekPreview.value = null
    hideSeekBar()
  }

  fun seekBy(offset: Int) {
    coalesceSeek(offset)
  }

  fun seekTo(position: Int) {
    renewSeekPauseSafetyNet()
    dispatchAbsoluteSeek(position.toDouble(), preview = false)
  }

  /**
   * 拖动进度条期间的 seek —— **每一帧移动都会进来，必须节流**（v217）。
   *
   * 为什么不能像 [seekTo] 那样来一次发一次：一次两秒的拖动会打出 60~120 条
   * `seek` 命令，而网络流上每条 seek 的代价是「丢掉已缓存区间 → HTTP Range
   * 重连 → 重新定位 → 从关键帧解码」，新命令还会掐掉没跑完的上一条 —— 整个
   * 过程被反复推倒重来，缓存被反复清空，观感就是「拖一下卡好久」。视界流的
   * `previewSeek` 早就用 120ms 节流解决过同一个问题（注释原话：「不至于每个
   * 移动事件都让解码器重新定位一次」），这里照同款口径处理。
   *
   * 这里只更新目标并按 [SCRUB_SEEK_INTERVAL_MS] 间隔才真正下发；**最终落点由
   * [commitScrubSeek] 在松手时保证**。老实现里收尾回调不发 seek、完全依赖
   * 「最后一条 onValueChange 恰好是最终位置」—— 并发协程下顺序不保证，
   * 停错位置就是这么来的。
   */
  fun scrubTo(position: Float) {
    renewSeekPauseSafetyNet()
    val duration = effectiveDurationSeconds().takeIf { it > 0f }
      ?: PlayerLib.getPropertyDouble("duration")?.toFloat()?.takeIf { it > 0f }
      ?: return
    lastScrubTarget = position.coerceIn(0f, duration).toDouble()
    scheduleScrubDispatch()
  }

  private fun scheduleScrubDispatch() {
    val elapsed = android.os.SystemClock.elapsedRealtime() - lastScrubCommitAt
    if (lastScrubCommitAt == 0L || elapsed >= SCRUB_SEEK_INTERVAL_MS) {
      dispatchLatestScrub()
      return
    }
    if (scrubThrottleJob?.isActive == true) return
    scrubThrottleJob = viewModelScope.launch {
      delay(SCRUB_SEEK_INTERVAL_MS - elapsed)
      dispatchLatestScrub()
    }
  }

  private fun dispatchLatestScrub() {
    val target = lastScrubTarget
    if (target < 0.0 || target == lastDispatchedScrub) return
    lastScrubCommitAt = android.os.SystemClock.elapsedRealtime()
    lastDispatchedScrub = target
    dispatchAbsoluteSeek(target, preview = true)
  }

  /** Force the final pointer target; exact seeking is reserved for this commit. */
  fun commitScrubSeek() {
    val target = lastScrubTarget
    lastScrubTarget = -1.0
    lastScrubCommitAt = 0L
    scrubThrottleJob?.cancel()
    scrubThrottleJob = null
    if (target < 0.0) return
    lastDispatchedScrub = target
    dispatchAbsoluteSeek(target, preview = false)
  }

  /**
   * 手还在屏幕上：给 seek 暂停的安全阀续期（最多一秒续一次，别每帧都重建协程）。
   */
  private fun renewSeekPauseSafetyNet() {
    if (seekPauseApplied) {
      val now = android.os.SystemClock.elapsedRealtime()
      if (now - seekPauseRenewedAt > SEEK_PAUSE_RENEW_INTERVAL_MS) {
        seekPauseRenewedAt = now
        armSeekPauseSafetyNet()
      }
    }
  }

  /**
   * 真正把绝对定位发给 mpv（IO 线程，单飞：新 seek 取消旧的）。
   * [seekTo]（跳章节、跳过片头这类单点跳转）与拖动节流共用这一条路。
   */
  private fun dispatchAbsoluteSeek(position: Double, preview: Boolean) {
    scrubSeekJob?.cancel()
    scrubSeekJob =
      viewModelScope.launch(Dispatchers.IO) {
        val maxDuration = effectiveDurationSeconds().toDouble().takeIf { it > 0.0 }
          ?: PlayerLib.getPropertyDouble("duration")?.takeIf { it > 0.0 }
          ?: return@launch
        val clampedPosition = position.coerceIn(0.0, maxDuration)


        // Cancel pending relative seek before absolute seek
        seekCoalesceJob?.cancel()
        pendingSeekOffset = 0

        val shouldUsePreciseSeeking = !preview &&
          (playerPreferences.usePreciseSeeking.get() || maxDuration < 120.0)
        val seekMode = if (shouldUsePreciseSeeking) "absolute+exact" else "absolute+keyframes"
        PlayerLib.command("seek", clampedPosition.toString(), seekMode)
      }
  }

  private fun coalesceSeek(offset: Int) {
    pendingSeekOffset += offset
    seekCoalesceJob?.cancel()
    seekCoalesceJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(SEEK_COALESCE_DELAY_MS)
        val toApply = pendingSeekOffset
        pendingSeekOffset = 0
        
        if (toApply != 0) {
          val duration = PlayerLib.getPropertyInt("duration") ?: 0
          val currentPos = PlayerLib.getPropertyInt("time-pos") ?: 0
          
          if (duration > 0 && currentPos + toApply >= duration) {
              // If seeking past the end, force seek to 100% absolute to ensure EOF is triggered
              PlayerLib.command("seek", "100", "absolute-percent+exact")
          } else {
              // Use precise seeking for videos shorter than 2 minutes (120 seconds) or if preference is enabled
              val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || duration < 120
              val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
              PlayerLib.command("seek", toApply.toString(), seekMode)
          }
        }
      }
  }

  fun leftSeek() {
    if ((pos ?: 0) > 0) {
      _doubleTapSeekAmount.value -= doubleTapToSeekDuration
    }
    _isSeekingForwards.value = false
    seekBy(-doubleTapToSeekDuration)
  }

  fun rightSeek() {
    if ((pos ?: 0) < (duration ?: 0)) {
      _doubleTapSeekAmount.value += doubleTapToSeekDuration
    }
    _isSeekingForwards.value = true
    seekBy(doubleTapToSeekDuration)
  }

  fun updateSeekAmount(amount: Int) {
    _doubleTapSeekAmount.value = amount
  }

  fun updateSeekText(text: String?) {
    _seekText.value = text
  }

  fun updateIsSeekingForwards(isForwards: Boolean) {
    _isSeekingForwards.value = isForwards
  }

  private fun seekToWithText(
    seekValue: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    _isSeekingForwards.value = seekValue > currentPos
    _doubleTapSeekAmount.value = seekValue - currentPos
    _seekText.value = text
    seekTo(seekValue)
  }

  private fun seekByWithText(
    value: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    val maxDuration = duration ?: return

    _doubleTapSeekAmount.update {
      if ((value < 0 && it < 0) || currentPos + value > maxDuration) 0 else it + value
    }
    _seekText.value = text
    _isSeekingForwards.value = value > 0
    seekBy(value)
  }

  // ==================== Brightness & Volume ====================

  fun changeBrightnessTo(brightness: Float, persist: Boolean = true) {
    val coercedBrightness = brightness.coerceIn(0f, 1f)
    host.hostWindow.attributes =
      host.hostWindow.attributes.apply {
        screenBrightness = coercedBrightness
      }
    currentBrightness.value = coercedBrightness
    if (persist) persistBrightness(coercedBrightness)
  }

  fun persistBrightness(brightness: Float = currentBrightness.value) {
    val coercedBrightness = brightness.coerceIn(0f, 1f)
    // Save brightness to preferences if enabled
    if (playerPreferences.rememberBrightness.get()) {
      playerPreferences.defaultBrightness.set(coercedBrightness)
    }
    // 按剧再记一份（对标 Yamby 的 resume_brightness）。
    // ⚠️ 和全局记忆是**叠加**关系不是二选一：用户可能只想全局固定一个亮度、
    //   不想某部片单独记住，所以两个开关各自独立判断。
    // ⚠️ seriesKey 为 null（本地播放 / 网盘）时不写 —— 没有稳定的剧标识可挂。
    if (playerPreferences.rememberSeriesBrightness.get()) {
      playerPreferences.setSeriesBrightness(seriesKey, coercedBrightness)
    }
  }

  fun displayBrightnessSlider() {
    isBrightnessSliderShown.value = true
    brightnessSliderTimestamp.value = System.currentTimeMillis()
  }

  fun synchronizeSystemVolume(step: Int) {
    val normalizedStep = step.coerceIn(0, maxVolume)
    currentVolume.value = normalizedStep
    volumeGestureFraction.value = normalizedStep.toFloat() / maxVolume.coerceAtLeast(1)
  }

  fun changeVolumeBy(change: Int) {
    val mpvVolume = PlayerLib.getPropertyInt("volume")
    val absoluteMaxVolume = volumeBoostCap ?: (audioPreferences.volumeBoostCap.get() + 100)

    if (absoluteMaxVolume > 100 && currentVolume.value == maxVolume) {
      if (mpvVolume == 100 && change < 0) {
        changeVolumeTo(currentVolume.value + change)
      }
      val finalMPVVolume = (mpvVolume?.plus(change))?.coerceAtLeast(100) ?: 100
      if (finalMPVVolume in 100..absoluteMaxVolume) {
        return changeMPVVolumeTo(finalMPVVolume)
      }
    }
    changeVolumeTo(currentVolume.value + change)
  }

  fun changeVolumeTo(volume: Int) {
    val newVolume = volume.coerceIn(0..maxVolume)
    updateVolumeGesture(newVolume.toFloat() / maxVolume.coerceAtLeast(1))
  }

  /** UI remains continuous; only the rounded Android stream step crosses the Binder boundary. */
  fun updateVolumeGesture(fraction: Float) {
    val normalized = fraction.coerceIn(0f, 1f)
    volumeGestureFraction.value = normalized
    val step = (normalized * maxVolume).roundToInt().coerceIn(0, maxVolume)
    if (step != currentVolume.value) {
      currentVolume.value = step
      systemVolumeWrites.trySend(step)
    }
  }

  fun changeMPVVolumeTo(volume: Int) {
    PlayerLib.setPropertyInt("volume", volume)
  }

  fun displayVolumeSlider() {
    isVolumeSliderShown.value = true
    volumeSliderTimestamp.value = System.currentTimeMillis()
  }

  // ==================== Screen Rotation ====================

  /** Rotate to the opposite fixed orientation and suppress further automatic decisions. */
  fun cycleScreenRotations() {
    host.rotatePlayerScreen()
  }

  // ==================== Gesture Handling ====================

  fun handleLeftDoubleTap() {
    when (gesturePreferences.leftSingleActionGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapLeft.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  fun handleCenterDoubleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleCenterSingleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleRightDoubleTap() {
    when (gesturePreferences.rightSingleActionGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapRight.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  // ==================== 媒体键（硬件按键 / 耳机线控 / 车机） ====================

  /**
   * 上一曲键（MEDIA_PREVIOUS）。
   *
   * 这三个偏好项（[GesturePreferences.mediaPreviousGesture] 等）以前只在设置页有 UI、
   * 没有任何代码读取 —— 这里补上接线，映射规则与双击手势完全一致：
   * Seek = 后退 / 前进、PlayPause = 播放暂停、Custom = 透传给 input.conf 自定义键、None = 不响应。
   */
  fun handleMediaPrevious() {
    when (gesturePreferences.mediaPreviousGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPlayPause() {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleMediaNext() {
    when (gesturePreferences.mediaNextGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaNext.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  /** Applies the visible pinch gesture as a session-only command; it is never persisted. */
  fun applyPinchZoom(zoom: Float) {
    PlayerLib.setPropertyDouble("video-zoom", zoom.toDouble())
  }

  // ==================== Internal playback queue navigation ====================

  /** Queue capability is independent of the removed playlist panel and its old preference. */
  fun hasPlaybackQueue(): Boolean = (host as? PlayerActivity)?.hasPlaylistItems() == true

  fun getPlaylistInfo(): String? {
    val activity = host as? PlayerActivity ?: return null
    if (activity.playlist.isEmpty()) return null

    val totalCount = activity.playlist.size
    return "${activity.playlistIndex + 1}/$totalCount"
  }

  fun hasNext(): Boolean = (host as? PlayerActivity)?.hasNext() ?: false

  fun hasPrevious(): Boolean = (host as? PlayerActivity)?.hasPrevious() ?: false

  fun playNext() {
    (host as? PlayerActivity)?.playNext()
  }

  fun playPrevious() {
    (host as? PlayerActivity)?.playPrevious()
  }

  // ==================== 黑边自动裁切（cropdetect） ====================

  /** 当前是否已应用自动裁切，供 UI 显示状态。 */
  private val _isAutoCropped = MutableStateFlow(false)
  val isAutoCropped: StateFlow<Boolean> = _isAutoCropped.asStateFlow()

  /** 探测协程。同一时刻只允许一条，避免连点叠加出多条 cropdetect 滤镜。 */
  private var cropJob: Job? = null

  /**
   * 手动「自动裁黑边 / 还原」。
   *
   * 已在裁切状态时点击即还原 —— 探测不准（比如把暗场戏当成黑边）时用户能一键退回去。
   */
  fun toggleAutoCrop() {
    if (VideoCrop.isApplied) {
      VideoCrop.clear()
      _isAutoCropped.value = false
      playerUpdate.value = PlayerUpdates.ShowText(host.context.getString(R.string.player_crop_off))
      return
    }
    runAutoCrop(userInitiated = true)
  }

  /**
   * 「自动裁黑边」开关：写偏好并**立即**生效。
   *
   * 打开 → 马上对当前画面探测一次（不等下一次换片）；
   * 关闭 → 立刻摘掉已挂上的裁切，不然用户看着开关是关的、画面却还裁着。
   */
  fun setAutoCrop(enabled: Boolean) {
    playerPreferences.autoCropBlackBars.set(enabled)
    if (enabled) {
      runAutoCrop(userInitiated = true)
    } else {
      cropJob?.cancel()
      cropJob = null
      VideoCrop.clear()
      _isAutoCropped.value = false
      playerUpdate.value = PlayerUpdates.ShowText(host.context.getString(R.string.player_crop_off))
    }
  }

  /**
   * 每次加载新文件后按偏好套用裁切策略。
   *
   * - 开关打开 → 探测并裁切；
   * - 开关关闭 → **主动清一次**：上一条片子的裁切滤镜会留在 mpv 的 vf 链上，
   *   不清的话新片会带着上一条的黑边偏移继续播。
   */
  fun applyAutoCropPolicy() {
    if (playerPreferences.autoCropBlackBars.get()) {
      runAutoCrop(userInitiated = false)
    } else {
      VideoCrop.clear()
      _isAutoCropped.value = false
    }
  }

  private fun runAutoCrop(userInitiated: Boolean) {
    if (cropJob?.isActive == true) return
    cropJob =
      viewModelScope.launch {
        val crop = VideoCrop.detectAndApply()
        _isAutoCropped.value = crop != null
        if (userInitiated) {
          val msg =
            if (crop != null) {
              host.context.getString(R.string.player_crop_applied)
            } else {
              host.context.getString(R.string.player_crop_failed)
            }
          playerUpdate.value = PlayerUpdates.ShowText(msg)
        }
      }
  }

  /** 播放页退出 / 换内核时把裁切滤镜摘干净。 */
  fun clearAutoCrop() {
    cropJob?.cancel()
    cropJob = null
    VideoCrop.clear()
    _isAutoCropped.value = false
  }

  // ==================== Utility ====================

  fun showToast(message: String) {
    Toast.makeText(host.context, message, Toast.LENGTH_SHORT).show()
  }

  override fun onCleared() {
    super.onCleared()
  }
}

// Extension functions
fun Float.normalize(
  inMin: Float,
  inMax: Float,
  outMin: Float,
  outMax: Float,
): Float = (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin

fun <T> Flow<T>.collectAsState(
  scope: CoroutineScope,
  initialValue: T? = null,
) = object : ReadOnlyProperty<Any?, T?> {
  private var value: T? = initialValue

  init {
    scope.launch { collect { value = it } }
  }

  override fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ) = value
}
