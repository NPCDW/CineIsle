package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuOverlay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import kotlin.math.ceil
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import app.marlboroadvance.mpvex.domain.emby.StreamProbe
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KRuntimeDecision
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KRuntimePolicy
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KRuntimeState
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuRenderParams
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.koin.android.ext.android.inject

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
open class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private var anime4kRuntimeState = Anime4KRuntimeState()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /** 弹幕偏好（开关 / 字号 / 速度 / 透明度 / 密度 / 屏蔽词 / 弹幕源）。 */
  private val danmakuPreferences: app.marlboroadvance.mpvex.preferences.DanmakuPreferences by inject()

  /** Emby 仓库（片头片尾分段的精准数据源；非 Emby 播放用不到） */
  private val embyRepository: app.marlboroadvance.mpvex.domain.emby.EmbyRepository by inject()
  private val loopback: app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer by inject()
  private val loemby: LoembyPlaybackCoordinator by inject()
  val loembyPlaybackState = kotlinx.coroutines.flow.MutableStateFlow<LoembyPlaybackState?>(null)
  private var localLoadJob: Job? = null
  internal val playbackEventGeneration: Long get() = localLoadGeneration
  private var localLoadGeneration = 0L

  /** Orientation is chosen once per file, unless the user takes over with the rotate action. */
  private var orientationState: PlayerOrientationLock = PlayerOrientationLock.WaitingForVideo
  private var orientationItemId: String? = null

  private sealed interface PlayerOrientationLock {
    data object WaitingForVideo : PlayerOrientationLock
    data class Automatic(val orientation: Int) : PlayerOrientationLock
    data class Manual(val orientation: Int) : PlayerOrientationLock
  }
  private val loembyTransport: LoembyMpvTransport = object : LoembyMpvTransport {
    override fun command(vararg args: String) = PlayerLib.command(*args)
    override fun replaceCurrentPlaylistEntry(url: String) {
      nativePlaylist.replaceCurrent(playlistIndex, url)
    }
    override fun getDouble(name: String) = PlayerLib.getPropertyDouble(name)
    override fun propertyString(name: String) = PlayerLib.getPropertyString(name)
    override fun setDouble(name: String, value: Double) = PlayerLib.setPropertyDouble(name, value)
    override fun setString(name: String, value: String) = PlayerLib.setPropertyString(name, value)
    override fun setInt(name: String, value: Int) = PlayerLib.setPropertyInt(name, value)
    override fun setBoolean(name: String, value: Boolean) = PlayerLib.setPropertyBoolean(name, value)
    override fun getBoolean(name: String) = PlayerLib.getPropertyBoolean(name)
    override fun getNode(name: String) = PlayerLib.getPropertyNode(name)
  }
  private val nativePlaylist by lazy { NativeMpvPlaylistController(loembyTransport) }
  private val playbackConfigurations:
    app.marlboroadvance.mpvex.domain.postgres.PlaybackConfigurationRepository by inject()
  private val loembyCallbacks = object : LoembyPlaybackCallbacks {
    override fun onState(state: LoembyPlaybackState) {
      if (isDestroyed || !mpvInitialized) return
      loembyPlaybackState.value = state
      state.resolved?.let { resolved ->
        embyMediaSourceByItemId = embyMediaSourceByItemId + (state.spec.itemId to resolved.source.Id.orEmpty())
        val names = playlistMediaSourceNames.toMutableList()
        while (names.size <= playlistIndex) names.add("")
        names[playlistIndex] = app.marlboroadvance.mpvex.domain.emby.playback.LoembyMediaSelection.displayTitle(resolved.source)
        playlistMediaSourceNames = names
        val sources = playlistItemIds.map { embyMediaSourceByItemId[it].orEmpty() }
        intent.putStringArrayListExtra("emby_media_source_id", ArrayList(sources))
        intent.putStringArrayListExtra("emby_media_source_names", ArrayList(names))
        viewModel.subtitleServer = state.spec.server
      }
    }
    override fun onError(message: String) {
      if (!isDestroyed && mpvInitialized) viewModel.showToast(message)
    }
    override fun onFinished(reason: String) {
      if (isDestroyed || isFinishing || !mpvInitialized) return
      when (reason) {
        "next", "end-of-playlist" -> handleEndOfFile(true)
        "error" -> {
          PlayerLib.command("stop")
          if (viewModel.playbackError.value == null) viewModel.showPlaybackError("Emby 播放失败，请重试或返回")
        }
      }
    }
  }

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName = ""

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""

  /**
   * 弹幕状态（加载 / 匹配 / 过滤）。
   *
   * 只在 mpv 播放页存在；视界流不接（弹幕跟随 mpv 的
   * `time-pos`，换内核要重新对接一遍）。
   * 字段用 `by lazy` + nullable：弹幕默认是关的，不该在 onCreate 里就建协程作用域。
   */
  private var danmakuState: app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState? = null

  /** 弹幕状态访问器，供播放器 Sheet 复用当前 Emby 上下文。 */
  internal fun danmakuState(): app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState? = danmakuState

  /**
   * 「从头播放」一次性标记。
   *
   * Emby 详情页的「从头播放」会带 `play_from_start=true` 发起播放。置位后，
   * [applyPlaybackState] 不再把本地续播记录里的 lastPosition 套回 `time-pos`
   * —— 否则「从头播放」会被本地续播覆盖成「继续播放」。
   *
   * 只对本次 intent 的第一个视频生效（[loadVideoPlaybackState] 读出后立刻清掉），
   * 这样剧集连播时后面的剧集仍然能恢复各自的观看进度。
   */
  private var playFromStartOnce = false

  /**
   * 「整场从头播放」标记（随机播放入口专用）。
   *
   * 「随机播放 / 随机播放收藏」里可能混着已经看完的剧：如果切到下一个视频时
   * 恢复本地续播记录，会直接跳到片尾一秒就结束，观感极差。所以这两个入口
   * 带 `play_from_start_all=true`，让**本次播放会话里的每一个视频**都从头放。
   *
   * 与 [playFromStartOnce] 的区别：它在整个会话内持续生效（读的时候不清零），
   * 直到下一个 intent 重新赋值（随机入口必然带 true，其它入口必然是 false）。
   */
  private var playFromStartAllSession = false

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * 与 [playlist] 下标一一对应的显示标题。
   *
   * 网络流（尤其 Emby 的 `/Videos/{id}/stream`）URL 末段没有可用片名，
   * 只能从 URL 猜 → 猜出来是 "stream"。发起播放的一方知道真实片名，
   * 通过 intent extra `playlist_titles` 传进来，切集时优先用它。
   */
  private var playlistTitles: List<String> = emptyList()

  /**
   * 「每部剧记住播放设置」用的键，与 playlist 下标一一对应（Emby 剧集是 SeriesId）。
   *
   * 单文件播放不走列表，用 [singleSeriesKey]；
   * 列表播放按下标取，切集时键随之换成新一集所属的剧。
   * 本地播放（非 Emby）两个都是空，记忆逻辑整体跳过。
   */
  private var playlistSeriesKeys: List<String> = emptyList()
  private var singleSeriesKey: String? = null

  /**
   * 与 [playlistSeriesKeys] 平行的「**剧名**」（Emby 的 `SeriesName`）。
   *
   * ⚠️ 弹幕自动匹配只能拿剧名去搜番。原因是：点第 5 集进来时 title 是
   * 「都市传说」这种**分集名**，拿它去查 dandanplay 是查不到结果的；
   * 要查的是整部剧的名（「某科学的超电磁炮」）。电影这类非剧集条目没有剧名
   * ⇒ 空串 ⇒ 调用方退回用显示名。
   */
  private var playlistSeriesNames: List<String> = emptyList()
  private var singleSeriesName: String? = null

  /**
   * 与 [playlist] 同序的「多版本名」（空串 = 这一条没有多版本 / 没选版本）。
   *
   * 只在 Emby 多版本条目上才有值（用户在下拉框里选了哪个版本），顶栏会把它追加在片名后面。
   * 与 `playlistTitles` / `playlistSeriesKeys` 一样是**平行数组**，保持传入队列的原始顺序。
   */
  private var playlistMediaSourceNames: List<String> = emptyList()

  /**
   * 与 [playlist] 同序的 Emby **ItemId**（非 Emby 播放为空表）。
   *
   * 为什么既留「路径反查」（[currentEmbyItemId]）又要这个平行数组：
   * 开了「预解析直链」后 mpv 的 `path` 是 302 之后的真实地址（网盘 / CDN 域名），
   * 里面根本没有 `/Videos/{id}/stream` 可反查 —— 那时只能按下标取。
   * 与 `playlistTitles` / `playlistMediaSourceNames` 一样是**平行数组**，
   * 按当前播放下标读取；随机播放只改变下标，不重排这些数组。
   *
   * ⚠️ `@Volatile`：`EmbyPlaybackReporter` 的轮询协程在 **IO 线程**读它（见
   * `onCreate` 里 `idProvider` 的注释），主线程切集后必须能立刻看见新值，
   * 否则会拿上一集的 Id 去发进度。
   */
  @Volatile
  private var playlistItemIds: List<String> = emptyList()

  /**
   * 与 [playlist] 同序的 Emby 剧集原始序号（`"2005"` = S02E05；空串 = 不是剧集）。
   *
   * ⚠️ 只给**弹幕匹配**用：弹幕库的集号是「第几集」，而 [playlistIndex] 是「队列里的第几个」。
   * 从第 5 集点进详情页播放时队列只有 1 条，按下标算会取成第 1 集的弹幕 ——
   * 用户看到的现象就是「弹幕和画面完全对不上」。
   *
   * 与 [playlistItemIds] 一样按传入队列的原始顺序保存，切集时按当前下标读取。
   */
  private var playlistEpisodeNumbers: List<String> = emptyList()

  /**
   * Emby **ItemId → 用户为该条选定的媒体源 Id**（多版本条目才有；单版本 / 非 Emby 为空表）。
   *
   * 它的读取方是 [saveVideoPlaybackState]，按当前条目的 ItemId 查媒体源，
   * 不依赖队列顺序，随机切集时也能对应到用户选定的版本。
   */
  private var embyMediaSourceByItemId: Map<String, String> = emptyMap()

  // Bound once per incoming playback intent; never follow another screen's selected server.
  private var playbackServer: EmbyServer? = null
  private var playbackServerId: Long? = null
  private var playbackHeaders: Map<String, String> = emptyMap()
  private var playbackUrl: String? = null
  @Volatile
  private var remoteIsoRejected = false

  /**
   * Current index in the playlist.
   *
   * 用 `mutableStateOf` 暴露给 Compose：切集（playNext/playPrevious）改的是这个值，
   * 但上一集/下一集按钮的 enabled、悬浮切换件的显隐都是**组合期**读 `hasPrevious()/
   * hasNext()` 算出来的 —— 若它是普通 var，值变了不会触发重组，按钮就「卡」在旧状态
   * （横屏尤其明显：切了下一集后上一集按钮仍是灰色、点不动）。改成可观察状态后，
   * 位置一变 UI 立即刷新。所有非组合场景的读写照常当普通 Int 用，零影响。
   */
  internal var playlistIndex: Int by mutableStateOf(0)

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isManualBackgroundPlayback = false // Track manual background playback trigger
  private var mpvInitialized = false // Track MPV initialization state
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * 系统媒体会话（清单 1.5 拆分第 3 刀）：锁屏 / 通知栏 / 蓝牙 / Android Auto 的播放控制。
   *
   * 懒加载：只有真正开始播放、且内核已就绪时才创建，避免冷启动就占系统资源。
   */
  private val mediaSession by lazy {
    PlayerMediaSession(
      appContext = applicationContext,
      onPlay = { viewModel.unpause() },
      onPause = { viewModel.pause() },
      onSeekTo = { posMs -> viewModel.seekTo((posMs / 1000).toInt()) },
      currentPositionMs = { (viewModel.pos ?: 0) * 1000L },
      isPaused = { viewModel.paused ?: false },
    )
  }

  // ==================== Audio Focus ====================

  /**
   * 音频焦点 + 拔耳机处理（清单 1.5 拆分第 2 刀）。
   *
   * 懒加载：它只在 [initializeMpvAndFinishSetup] 走到音频设置那一步时才被创建，
   * 那时 viewModel 已经就绪，可以安全地把 `paused` 传成回调。
   */
  private val audioFocus by lazy {
    PlayerAudioFocus(
      context = this,
      isPaused = { viewModel.paused ?: false },
      onPause = { viewModel.pause() },
      onResume = { viewModel.unpause() },
      onScreenKeepOnChange = { keepOn ->
        if (keepOn) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setOrientation()
    val input = EmbyPlaybackInput.read(intent)
    val server = input?.resolveServer(embyRepository)
    if (input == null || server == null) {
      android.widget.Toast.makeText(this, "仅支持有效 Emby 会话的 HTTP(S) 媒体播放", android.widget.Toast.LENGTH_LONG).show()
      super.finish()
      return
    }
    playbackServerId = input.serverId
    playbackServer = server

    if (PlayerLib.isCoreReady) {
      android.widget.Toast.makeText(this, "已有 mpv 播放会话，请先退出当前播放页面", android.widget.Toast.LENGTH_LONG).show()
      super.finish()
      return
    }

    setContentView(binding.root)

    // 弹幕状态：默认关，所以**按需**创建（不建就不会有任何网络请求）。
    danmakuState = app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState(
      preferences = danmakuPreferences,
      scope = lifecycleScope,
    )

    // 常规播放固定使用 mpv。

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    // ── 内核初始化：必须同步完成，onCreate 返回前 mpv 句柄就要可用 ──
    // ## 这里曾经被改成异步，直接导致「点播放 → 闪退」（清单 #26 的由来）
    // 原来的写法是 `lifecycleScope.launch { withContext(IO) { … } ; setupMPV() }`，
    // 动机是「assets 拷贝 / SAF 读配置是纯 IO，别占住主线程」。但它破坏了一个
    // 硬性前提：**`onCreate` 返回时 mpv 必须已经 create**。
    // 原因在 `MPVView` 的继承链：`BaseMPVView extends SurfaceView`。
    // `setContentView` 一执行，首帧 traversal 就会回调 `surfaceCreated`，
    // 而它的实现是 `MPVLib.attachSurface(holder.surface)` —— 一个 native 调用。
    // AAR 里所有 native 方法操作的句柄都由 `MPVLib.create()` 分配，
    // 在 create 之前调用 = 解引用空指针 = **SIGSEGV**。
    // 这类崩溃在 native 栈里，**JVM 的 try/catch 与 runCatching 都拦不住**，
    // 表现就是整个进程直接消失、且拿不到任何 Java 堆栈。
    // 时序上 IO 段要几百 ms 到几秒（首次进播放页要拷 scripts/shaders/fonts，
    // 还要读 SAF 同步用户自建 mpv 目录），而首帧只要一帧（~16ms）——
    // 异步化之后 `attachSurface` **必然**抢在内核 create 之前跑，100% 复现。
    // 同一窗口里还有第二条路：`onResume → updateVolume() → viewModel` 让
    // `PlayerViewModel` 构造，其字段初始化里的 `PlayerLib.propInt["volume-max"]`
    // 首次访问会触发 native `observeProperty`，同样是死路。
    // ## 现在的做法
    // 同步调用（回到改造前的行为），**但先把 IO 挪出主线程的那部分成果保留**：
    //  ① `Anime4KManager.prepareOnIoThread()` 自带 `.assets_copied` 标记文件的快路径，
    //     冷启动第二次起连 `assets.list()` 都不进；
    // ② [PlayerLib] 的内核就绪门禁作为第二道防线 ——
    //     万一将来又有人挪动初始化时机，最坏也只是「值读不到」而不是「进程崩掉」。
    // 想彻底去掉主线程 IO 的正确解法是**在 Application 启动时预热**拷贝，
    // 而不是把内核 create 推迟到 Activity 里 —— 那是另一件事，不在这里做。
    prepareMpvFilesOnIoThread()
    initializeMpvAndFinishSetup()
  }

  /**
   * mpv 配置文件就绪之后的初始化 + 页面装配（清单 #20 必修 1）。
   *
   * 从 [onCreate] 原样搬出来的部分，因此**整体仍跑在主线程**
   *（[lifecycleScope] 的默认 dispatcher 就是 `Dispatchers.Main.immediate`）。
   *
   * 搬出来而不是只把 `setupMPV()` 异步化，是因为 `player.initialize()` 之后那一整段
   * （`setupAudio` / `setupPlayerControls` / `player.playFile(uri)` …）全都依赖
   * 内核**已经初始化完**这个前提 —— 只把 IO 挪走而把 `player.initialize()` 留在原地，
   * 会让 `playFile` 有机会抢在内核就绪前跑。
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun initializeMpvAndFinishSetup() {
    // NOW initialize MPV - it will find and load the scripts we just copied
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    // 句柄此刻才真正存在，开门禁（清单 #26）。
    // 必须在任何 PlayerLib 调用之前 —— 下面紧接着就是 addObserver / observeProperty。
    PlayerLib.markCoreReady()
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    PlayerLib.addObserver(playerObserver)
    PlayerLib.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)

    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    // 弹幕层独立成 View 层，声明在 controls 之前（= 控件下面）。
    // 顺序无依赖：danmakuState 在 onCreate 更早处（L510）就建好了。
    mountDanmakuLayer()
    setupPipHelper()
    setupMediaSession()
    synchronizeInitialAnime4KRuntimeState()

    playlistIndex = intent.getIntExtra("playlist_index", 0)

    // Emby「从头播放」：本次启动的第一个视频不要从本地续播记录恢复进度
    playFromStartOnce = intent.getBooleanExtra("play_from_start", false)
    // 随机播放入口：整场每个视频都从头放（连播切集也不恢复进度）
    playFromStartAllSession = intent.getBooleanExtra("play_from_start_all", false)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // 与 playlist 一一对应的显示标题（由发起方写入，如 Emby 的「S01E05 剧集名」）。
    // 没有这个列表时只能从 URL 末段猜标题 —— Emby 的流地址末段固定是 "stream"，
    // 于是从第二个视频起标题全变成 "stream"（ bug）。有它就优先用它。
    playlistTitles = intent.getStringArrayListExtra("playlist_titles") ?: emptyList()
    // 「记住每部剧的播放设置」的键，与 playlist 下标一一对应
    playlistSeriesKeys = intent.getStringArrayListExtra("playlist_series_keys") ?: emptyList()
    singleSeriesKey = intent.getStringExtra("emby_series_key")
    // ⚠️ 剧名（与 playlist 同序）——**弹幕自动匹配必须用它**，不能用某一集的标题。
    //    搜「都市传说」（分集名）在弹幕库里是搜不到番的，必须搜「某科学的超电磁炮」。
    //    电影/音乐等非剧集条目没有剧名 ⇒ 空串 ⇒ 退回显示名，行为与旧版一致。
    playlistSeriesNames = intent.getStringArrayListExtra("playlist_series_names") ?: emptyList()
    singleSeriesName = intent.getStringExtra("emby_series_name")
    // Emby 多版本名（与 playlist 下标一一对应，空串占位）——顶栏用它显示「正在播的是哪个版本」
    playlistMediaSourceNames =
      intent.getStringArrayListExtra("emby_media_source_names") ?: emptyList()
    // Emby ItemId（与 playlist 同序）—— 路径反查不到时靠它按当前下标取
    playlistItemIds =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
      ) ?: emptyList()
    // Emby 剧集原始序号（与 playlist 同序，空串 = 不是剧集）—— 弹幕按真实集号匹配，
    // 而不是按队列下标（从第 5 集单独点播时，队列只有 1 条，下标 0 会被当成第 1 集）
    playlistEpisodeNumbers =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS,
      ) ?: emptyList()
    // Emby 多版本：ItemId → 选定的媒体源 Id（按版本记进度的键靠它拼，见 EmbyVersionProgress）
    embyMediaSourceByItemId = buildEmbyMediaSourceMap(intent)
    viewModel.seriesKey = currentSeriesKey()

    // Extract fileName early so it's available when video loads
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE playing the file
    bindPlaybackHeaders(intent)
    playbackUrl?.let {
      if (rejectRemoteIso(it)) return
      if (!setHttpHeadersForUri(Uri.parse(it))) return
    }

    // 起播前已被 Emby 侧判定为死链（403 / 404 / 登录失效）：
    // 地址**根本没交给 mpv** ⇒ 不会 native 崩溃，直接把原因显示成卡片。
    // 见 EmbyViewModel.play() 里对 StreamProbe.Denied 的处理。
    val preflightError = intent.getStringExtra("play_error")
    if (preflightError != null) {
      viewModel.showPlaybackError(preflightError)
    } else {
      getPlayableUri(intent)?.let { uri -> startPlayback(uri) }
    }

    setOrientation()

    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }

  /** URL-only fallback; known Emby ISO containers are rejected before launching this page. */
  private fun rejectRemoteIso(uri: String?): Boolean {
    if (!EmbyMediaSourcePolicy.isRemoteIsoUrl(uri)) return false
    remoteIsoRejected = true
    playbackUrl = uri
    durationWatchdogJob?.cancel()
    cancelAutoplayCountdown()
    // Stop any previous file in this singleTask page; retry must never reopen the rejected ISO.
    if (mpvInitialized) PlayerLib.command("stop")
    viewModel.showPlaybackError(EmbyMediaSourcePolicy.ISO_PLAYBACK_ERROR)
    return true
  }

  private fun playNativePlaylistItem(
    index: Int,
    url: String,
  ) {
    localLoadJob?.cancel()
    val generation = ++localLoadGeneration
    val source = intent
    localLoadJob = lifecycleScope.launch {
      try {
        loemby.prepare(url, loembyTransport, loembyCallbacks, this@PlayerActivity)
        if (generation != localLoadGeneration || source !== intent || isDestroyed || !mpvInitialized) return@launch
        nativePlaylist.play(index)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == localLoadGeneration && !isDestroyed) {
          viewModel.showPlaybackError("本地 Emby 播放准备失败，请重试或返回")
        }
      }
    }
  }

  /** Registers every logical item before building libmpv's native playlist. */
  private fun startPlayback(uri: String) {
    if (rejectRemoteIso(uri)) return
    localLoadJob?.cancel()
    val generation = ++localLoadGeneration
    val source = intent
    val startIndex = playlistIndex
    val server = runCatching { currentPlaybackServer() }.getOrElse {
      viewModel.showPlaybackError("播放服务器配置已失效，请返回重新选择")
      return
    }
    val logicalUris = if (playlist.isEmpty()) listOf(Uri.parse(uri)) else playlist
    remoteIsoRejected = false
    viewModel.onLoadingNewFile()
    localLoadJob = lifecycleScope.launch {
      try {
        val localUrls = logicalUris.mapIndexed { index, candidate ->
          val candidateUrl = candidate.toString()
          if (loopback.isLocalPlayUrl(candidateUrl)) {
            val registered = loopback.specForUrl(candidateUrl) ?: error("播放会话已失效")
            require(
              registered.server.id == server.id &&
                registered.server.remoteId == server.remoteId &&
                registered.server.sourceKey == server.sourceKey &&
                registered.server.sourceRevision == server.sourceRevision &&
                registered.itemId == playlistItemIds.getOrNull(index),
            ) { "播放会话与条目绑定不一致" }
            candidateUrl
          } else {
            val itemId = playlistItemIds.getOrNull(index)?.takeIf { it.isNotBlank() }
              ?: error("缺少 Emby 媒体ID")
            val item = embyRepository.getItem(server, itemId)
            val ticks = if (index == startIndex && !playFromStartAllSession && !playFromStartOnce) {
              source.getIntExtra("position", 0).toLong().coerceAtLeast(0) * 10_000L
            } else {
              0L
            }
            loopback.register(
              app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec(
                server = server,
                itemId = itemId,
                seriesId = item.SeriesId,
                itemName = item.Name.orEmpty(),
                seriesName = item.SeriesName,
                title = item.displayTitle().ifEmpty { getPlaylistTitleAt(index).orEmpty() },
                itemType = item.Type,
                seasonNumber = item.ParentIndexNumber,
                episodeNumber = item.IndexNumber,
                runTimeTicks = item.RunTimeTicks,
                primaryImageItemId = item.primaryImageRef()?.itemId,
                primaryImageTag = item.primaryImageRef()?.tag,
                backdropImageTag = item.BackdropImageTags.firstOrNull(),
                positionTicks = ticks,
                explicitMediaSourceId = embyMediaSourceByItemId[itemId],
                playlistIndex = index + 1,
                playlistTotal = logicalUris.size,
              ),
            )
          }
        }
        if (generation != localLoadGeneration || source !== intent || isDestroyed || !mpvInitialized) return@launch

        if (playlist.isNotEmpty()) {
          playlist = localUrls.map(Uri::parse)
          intent.putParcelableArrayListExtra(PlayerHandoff.EXTRA_PLAYLIST, ArrayList(playlist))
        }
        val local = localUrls[startIndex]
        intent.data = Uri.parse(local)
        intent.putExtra(PlayerHandoff.EXTRA_INDEX, startIndex)
        playbackUrl = local
        if (source.hasExtra("position") && source.getIntExtra("position", 0) > 0) {
          loopback.setResume(local, source.getIntExtra("position", 0).toLong() * 10_000L)
          source.removeExtra("position")
        }

        PlayerLib.setPropertyString("http-header-fields", "")
        PlayerLib.setPropertyString("user-agent", server.userAgent)
        PlayerLib.setPropertyString("keep-open", "always")
        PlayerLib.setPropertyString("keep-open-pause", "yes")
        val prefetch = runCatching {
          playbackConfigurations.snapshot(server)
        }.getOrNull()?.let(::sharedPrefetchPlaylistEnabled) == true

        loemby.prepare(local, loembyTransport, loembyCallbacks, this@PlayerActivity)
        if (generation != localLoadGeneration || source !== intent || isDestroyed || !mpvInitialized) return@launch
        nativePlaylist.replace(localUrls, startIndex, prefetch)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == localLoadGeneration && !isDestroyed) {
          viewModel.showPlaybackError("本地 Emby 播放准备失败，请重试或返回")
        }
      }
    }
  }

  /** Retry the active capability without clearing the surrounding native playlist. */
  private fun retryPlayback(uri: String) {
    val target = playbackUrl ?: uri
    if (!loopback.isLocalPlayUrl(target)) {
      startPlayback(target)
      return
    }
    loopback.release(target)
    replaceOrReloadNativeEntry(target, replace = false, preservePosition = true)
  }

  fun selectLoembyVersion(option: LoembyVersionOption) {
    if (!loopback.isLocalPlayUrl(option.url)) return
    replaceOrReloadNativeEntry(option.url, replace = true, preservePosition = true)
  }

  fun currentAnime4KMode(): Anime4KManager.Mode {
    if (!decoderPreferences.enableAnime4K.get()) return Anime4KManager.Mode.OFF
    return runCatching {
      Anime4KManager.Mode.valueOf(decoderPreferences.anime4kMode.get())
    }.getOrDefault(Anime4KManager.Mode.OFF)
  }

  private fun synchronizeInitialAnime4KRuntimeState() {
    if (!decoderPreferences.enableAnime4K.get() || currentAnime4KMode() == Anime4KManager.Mode.OFF ||
      decoderPreferences.useVulkan.get() || !decoderPreferences.gpuNext.get() || decoderPreferences.isDirectOutput()
    ) return
    anime4kRuntimeState = Anime4KRuntimeState(Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT)
  }

  fun applyAnime4KMode(mode: Anime4KManager.Mode): String? {
    val currentVo = PlayerLib.getPropertyString("current-vo") ?: PlayerLib.getPropertyString("vo")
    val decision = Anime4KRuntimePolicy.decide(
      mode = mode,
      currentVideoOutput = currentVo,
      gpuNextUsesVulkan = decoderPreferences.useVulkan.get(),
      directOutput = decoderPreferences.isDirectOutput(),
      state = anime4kRuntimeState,
      quality = Anime4KManager.Quality.FAST,
    )
    return when (decision) {
      is Anime4KRuntimeDecision.Rejected -> when (decision.reason) {
        app.marlboroadvance.mpvex.domain.anime4k.RejectionReason.DIRECT_OUTPUT_BYPASSES_SHADER_PIPELINE ->
          getString(app.marlboroadvance.mpvex.R.string.player_more_anime4k_direct_output)
      }
      is Anime4KRuntimeDecision.Disable -> {
        PlayerLib.command("change-list", "glsl-shaders", "clr", "")
        decision.restoreVideoOutput?.let(::switchVideoOutput)
        anime4kRuntimeState = decision.nextState
        decoderPreferences.enableAnime4K.set(false)
        decoderPreferences.anime4kMode.set(Anime4KManager.Mode.OFF.name)
        null
      }
      is Anime4KRuntimeDecision.Apply -> {
        val resolved = anime4kManager.resolvePreparedChain(mode, Anime4KManager.Quality.FAST)
          ?: return getString(app.marlboroadvance.mpvex.R.string.player_more_anime4k_unavailable)
        decision.temporaryVideoOutput?.let(::switchVideoOutput)
        PlayerLib.command("change-list", "glsl-shaders", "set", resolved.runtimeCommandValue)
        anime4kRuntimeState = decision.nextState
        decoderPreferences.enableAnime4K.set(true)
        decoderPreferences.anime4kMode.set(mode.name)
        decoderPreferences.anime4kQuality.set(Anime4KManager.Quality.FAST.name)
        null
      }
    }
  }

  private fun switchVideoOutput(target: String) {
    player.setVo(target)
    PlayerLib.setPropertyString("vo", target)
    PlayerLib.setPropertyString("force-window", "yes")
  }

  fun togglePlaybackStatistics() {
    PlayerLib.command("script-binding", "stats/display-stats-toggle")
  }

  fun showPlayerToast(message: String) {
    viewModel.showToast(message)
  }

  private fun replaceOrReloadNativeEntry(
    target: String,
    replace: Boolean,
    preservePosition: Boolean,
  ) {
    localLoadJob?.cancel()
    val generation = ++localLoadGeneration
    val source = intent
    val index = playlistIndex
    val resumeTicks = if (preservePosition) {
      ((PlayerLib.getPropertyDouble("time-pos") ?: 0.0).coerceAtLeast(0.0) * 10_000_000L).toLong()
    } else {
      0L
    }
    viewModel.onLoadingNewFile()
    localLoadJob = lifecycleScope.launch {
      try {
        if (resumeTicks > 0) loopback.setResume(target, resumeTicks)
        if (generation != localLoadGeneration || source !== intent || isDestroyed || !mpvInitialized) return@launch
        playbackUrl = target
        intent.data = Uri.parse(target)
        if (playlist.isNotEmpty()) {
          playlist = playlist.toMutableList().also { it[index] = Uri.parse(target) }
          intent.putParcelableArrayListExtra(PlayerHandoff.EXTRA_PLAYLIST, ArrayList(playlist))
        }
        PlayerLib.setPropertyString("http-header-fields", "")
        loemby.prepare(target, loembyTransport, loembyCallbacks, this@PlayerActivity)
        if (generation != localLoadGeneration || source !== intent || isDestroyed || !mpvInitialized) return@launch
        if (replace) nativePlaylist.replaceCurrent(index, target) else nativePlaylist.reload(index)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == localLoadGeneration && !isDestroyed) {
          viewModel.showPlaybackError("本地 Emby 播放准备失败，请重试或返回")
        }
      }
    }
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        val configurationDisplayMetrics = configurationContext.resources.displayMetrics
        @Suppress("DEPRECATION")
        configurationDisplayMetrics.scaledDensity = updatedConfiguration.fontScale * configurationDisplayMetrics.density
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      pipHelper.enterPipMode()
      return
    }

    isUserFinishing = true
    finish()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvexTheme {
        Box(modifier = Modifier.fillMaxSize()) {
          PlayerControls(
            viewModel = viewModel,
            onBackPress = {
              isUserFinishing = true
              finish()
            },
            modifier = Modifier,
          )
          // ── 「出画面前」的加载层 ────────────────────────────────
          // 从进入播放器到首帧真正上屏（MPV_EVENT_PLAYBACK_RESTART）之间，
          // 视频 Surface 是**纯黑**的 —— 原来这里什么都没有，只有一片黑。
          // ⚠️ 关键：它**必须挂在 PlayerControls 之外**。
          //   `PlayerControls` 内部的缓冲转圈被包在
          //   `AnimatedVisibility(visible = controlsShown)` 里（PlayerControls.kt:845），
          //   而 `controlsShown` 的**初值就是 false**（PlayerViewModel.kt:224）
          //   ⇒ 首帧前控件是收着的，那个转圈根本不显示。
          //   放在这里才不受控件显隐影响。
          // 复用播放器的「显示缓冲转圈」开关（`show_loading_circle`）：
          // 语义一致（都是「正在等画面」），而且设置页已经有这一项，不新增开关。
          PlayerFirstFrameLoading(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.Center),
          )

          // 下一集「悬停卡」（片尾前约 30 秒出现，无倒计时）：比倒计时胶囊(136)更高一截，
          // 两者可能同时出现 —— 悬停卡在上、倒计时胶囊在下，各自都能点到。
          NextUpCard(
            viewModel = viewModel,
            onPlayNow = {
              autoplayCancelled = false
              cancelAutoplayCountdown()
              playNext()
            },
            onDismiss = {
              // 收起 = 「本集别再提示了」：本集内不再弹出，本集播完仍按原逻辑自动连播，
              // 与倒计时胶囊上的 ×（那是取消连播）语义**不同**，别合并。
              // ⚠️ 只把 visible 置 false 没用：盯梢每 500ms 复算窗口会重新推开，
              //    必须打上 nextUpDismissed 让它彻底不再弹出（下一集开播时清零）。
              nextUpDismissed = true
              viewModel.nextUpVisible.value = false
              viewModel.nextUpTitle.value = null
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 176.dp),
          )
          // 下一集连播倒计时：贴在底部、抬高一截避开控制栏。
          // 只有倒计时 > 0 时卡片才出现（AnimatedVisibility 内部判断）。
          AutoplayCountdownCard(
            viewModel = viewModel,
            onPlayNow = {
              // 点卡片 = 立刻切下一集（不等片尾放完）
              autoplayCancelled = false
              cancelAutoplayCountdown()
              playNext()
            },
            onCancel = {
              // 取消 = 本集播完停在最后一帧，不再自动连播
              autoplayCancelled = true
              cancelAutoplayCountdown()
            },
            // 右下角，抬高到控制条按钮簇上方：84dp 时正好压在右下控件上，
            // 太高又飘到画面中央 —— 136dp 刚好越过「seekbar + 按钮簇」的总高度
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 136.dp),
          )
          // 「跳过片头 / 跳过片尾」小胶囊（b2+a1 手动模式）：右下角，
          // 刻意比连播倒计时卡(136)再高一截 —— 片尾区间里两者可能同时可见
          // （兜底片尾默认 90s，倒计时默认只有几秒），叠在一起会互相盖住。
          SkipSegmentChip(
            viewModel = viewModel,
            onSkip = {
              when (viewModel.skipSegmentLabel.value) {
                "跳过片头" -> introRangeMs?.let { range ->
                  runCatching { PlayerLib.command("seek", (range.second / 1000.0).toString(), "absolute") }
                }
                "跳过片尾" -> {
                  val duration = PlayerLib.getPropertyDouble("duration")
                  if (duration != null && duration > 0) {
                    runCatching { PlayerLib.command("seek", (duration - 0.2).toString(), "absolute") }
                  }
                }
              }
              viewModel.skipSegmentLabel.value = null
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 184.dp),
          )
          // ── 播放失败卡片（声明在最后 = 最上层）──
          // 服务端明确拒绝（401 / 403 / 404）时，由 Emby 侧预解析经
          // `play_error` extra 写入，或「重试」时由 [retryPlayback] 写入 ——
          // 替代原来的「native 崩溃、整个 App 消失」。必须排在所有浮层之后，
          // 否则会被悬停卡 / 倒计时胶囊盖住，用户点不到「重试」。
          PlaybackErrorCard(
            viewModel = viewModel,
            onBack = {
              isUserFinishing = true
              finish()
            },
            onRetry = {
              // Known remote ISO is terminal for this URL; do not clear its error or probe it.
              if (remoteIsoRejected) {
                viewModel.showPlaybackError(EmbyMediaSourcePolicy.ISO_PLAYBACK_ERROR)
              } else {
                val uri = playbackUrl ?: getPlayableUri(intent)
                if (uri != null && !rejectRemoteIso(uri)) {
                  viewModel.clearPlaybackError()
                  retryPlayback(uri)
                }
              }
            },
            modifier = Modifier.align(Alignment.Center),
          )
          // 弹幕层**不在这里**了（V118）：它已挪到 `binding.danmaku` 这一独立图层，
          // 声明在 controls **之前**（= 画在它下面、触摸也让给它）。
          // 原因见 [mountDanmakuLayer]。
        }
      }
    }
  }

  /**
   * 把弹幕叠层挂到**它自己的 ComposeView**（`binding.danmaku`）上。
   *
   * ⚠️ V118：这是「点屏幕唤不出控制栏」的根因修复，别再挪回控件那棵 Compose 树里。
   *
   * 之前弹幕叠层是 `binding.controls` 那棵 Compose 树里**最靠后（= 最上层）的全屏
   * `AndroidView`**。Compose 的命中测试里，兄弟节点按 z 序**上面那个先收事件**；
   * 而 `AndroidView` 必挂 `pointerInteropFilter` —— 它会把 ACTION_DOWN 先交给
   * 宿主 View 问一句「你要不要」，一旦那句返回 true，整串手势就被它独占
   * （`suppressMovementConsumption`），排在它后面的 `GestureHandler` 一条都收不到
   * ⇒ 单击永远走不到「显/隐控件」那条分支。
   * 与用户反馈的现象完全对得上：**弹幕还没渲染出来时点屏幕正常，弹幕一出来就点不动了**
   * （弹幕层进入组合的那一刻，DOWN 就被它先截走）。
   *
   * 修法是**改分层，不靠事件传递的运气**：弹幕层变成独立的 View 层，且在 XML 里
   * 声明在 `controls` **之前** ⇒ 在原生 View 体系的 z 序里它**在控件下面**。
   * 于是触摸一定先给上层的 `controls`，手势照常工作；弹幕层只是拿漏下来的那部分，
   * 而它本来就不需要触摸（我们没设 `OnDanmakuClickListener`，弹幕点击功能从没开过）。
   *
   * 顺带把叠放顺序也修正成注释里一直写的那样：**弹幕在画面之上、控制条之下**。
   *
   * 弹幕层同样使用独立 ComposeView，避免拦截播放器控制手势。
   */
  private fun mountDanmakuLayer() {
    binding.danmaku.setViewCompositionStrategy(
      androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
    )
    binding.danmaku.setContent {
      MpvexTheme { DanmakuOverlayContent() }
    }
  }

  /**
   * 弹幕叠层本体（跑在 [mountDanmakuLayer] 那棵独立 Compose 树上）。
   *
   * 进度与播放态直接来自 mpv 属性流。
   *
   * 开关关着 / 没有弹幕数据时整块不渲染：连 DanmakuView 都不建，也就没有绘制线程。
   */
  @Composable
  private fun DanmakuOverlayContent() {
    val state = danmakuState ?: return
    // ⚠️ 这里必须用 **Compose 自带的 `collectAsState()`**（无参、返回非空 `State<T>`），
    //   **不是** `PlayerViewModel.kt` 里那个同名的自定义扩展 —— 后者签名是
    //   `Flow<T>.collectAsState(scope, initial)`，要求显式传 scope 且返回 `T?`，
    //   用它会一路报「No value passed for parameter 'scope'」+ 一串可空类型错误。
    //   同名不同签名是这类批量报错的根源，看到「No value for scope」先查这里。
    val dmComments by state.comments.collectAsState(emptyList())
    val dmKey by state.episodeKey.collectAsState("")
    val dmPrefs by state.renderParams.collectAsState(DanmakuRenderParams())
    val dmPos by viewModel.precisePosition.collectAsState(0f)
    val dmPaused by PlayerLib.propBoolean["pause"].collectAsState()
    if (dmPrefs.enabled && dmComments.isNotEmpty()) {
      DanmakuOverlay(
        episodeKey = dmKey,
        positionSec = dmPos,
        playing = dmPaused != true,
        comments = dmComments,
        alphaPercent = dmPrefs.alphaPercent,
        fontScale = dmPrefs.fontScale,
        speedScale = dmPrefs.speedScale,
        areaRatio = dmPrefs.areaRatio,
        maxOnScreen = dmPrefs.maxOnScreen,
        preventOverlapping = dmPrefs.preventOverlapping,
        // 竖屏下 mpv 窗口铺满、画面居中一条，弹幕层要跟着画面走，
        // 否则 retainer 从窗口顶部 packing ⇒ 全挤在画面上方的黑边里。
        videoAspectProvider = { player.getVideoOutAspect()?.toFloat() },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player)
  }

  private fun setupAudio() {
    val channels = audioPreferences.audioChannels.get()
    runCatching {
      // 非「反向立体声」的声道档位走 audio-channels 属性；
      // 反向立体声（pan）与夜间模式（dynaudnorm）都属于 af 链，统一由 AudioFilters 组装后写入，
      // 否则两者会各自 setPropertyString("af", ...) 而互相覆盖。
      if (channels.property != "af") {
        PlayerLib.setPropertyString(channels.property, channels.value)
      }
      app.marlboroadvance.mpvex.ui.player.engine.AudioFilters.applyAf(audioPreferences, channels)
    }.onFailure { e ->
      Log.e(TAG, "Error applying audio settings: $channels", e)
    }

    if (!serviceBound) {
      audioFocus.create()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean = audioFocus.requestAudioFocus()

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      pipHelper.enterPipMode()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")
    if (!mpvInitialized) {
      super.onDestroy()
      return
    }

    kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
      loemby.finish("quit", this@PlayerActivity)
    }
    localLoadGeneration++
    localLoadJob?.cancel()
    // 播放页退出：把 Window 从 HDR 色彩模式复位，别把系统亮度留在 HDR 区间。
    // 普通 SDR 播放不碰 Window（reset 内部判过 appliedHdr）。
    PlaybackColorPolicy.reset(this)

    // 页面都没了就别再倒计时切集了
    cancelAutoplayCountdown()

    // 片头片尾盯梢一起停（不取消的话切到后台它还每隔 0.5 秒读一次 mpv 属性）
    cancelSkipSegmentWatcher()

    // 弹幕：取消在途的匹配请求（绘制线程由 DanmakuOverlay 的 DisposableEffect 负责 release）。
    danmakuState?.dispose()
    danmakuState = null

    runCatching {
      // OPTIMIZATION: Prevent any further UI updates or callbacks
      isReady = false

      // Only stop the service if we're not doing manual background playback
      if ((isUserFinishing || isFinishing) && !isManualBackgroundPlayback) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      }

      // 存档协程正在读 mpv 属性并写 Room。原先这里 `runBlocking { job.join() }`
      // **无限等它在主线程跑完** —— 那是 ANR 级的卡顿（Room 首次建表 + WAL fsync
      // 可以到几百毫秒），而它要防的竞态其实有更省的办法：
      // 竞态的本质是「destroy() 之后 job 还去读 mpv 属性」。所以只要**先冻结 mpv**
      // （下面 cleanupMPV 里的 `player.isExiting = true` + `removeObserver`），
      // job 读到的是什么就已经不重要了 —— 它写的要么是完整快照，要么是半截快照，
      // 两种都不会让程序崩。
      // 真正需要保证的是「别丢进度」，而那个由 onPause 的 `isSync` 分支负责
      // （退出时那次是同步写的，见 saveVideoPlaybackState）。这里的 job 是
      // onPause 之后的**异步补写**，它和 onPause 那次写的是同一份数据，
      // 晚一点到账不会丢，只是时序上晚一点。
      // 所以这里仍然等一下，但**必须有上限**：万一它真的卡住（磁盘满 / 数据库锁），
      // 也不能让用户看着「退出应用」卡死在那儿。
      savePlaybackStateJob?.let { job ->
        if (job.isActive) {
          Log.d(TAG, "Waiting for pending playback state save (max ${SAVE_JOIN_BUDGET_MS}ms)...")
          runCatching {
            kotlinx.coroutines.runBlocking {
              withTimeoutOrNull(SAVE_JOIN_BUDGET_MS) { job.join() }
            }
          }.onFailure { Log.w(TAG, "Join playback state job failed", it) }
          if (job.isActive) {
            Log.w(TAG, "Playback state save still running after budget; releasing MPV anyway")
            // 不 join 了：让这个协程随 Activity 一起结束。
            // 它后续读 mpv 会拿到 null（内核已销毁），runCatching 会吞掉。
            job.cancel()
          }
        }
      }

      cleanupMPV()
      cleanupAudio()
      // PiP receiver 被 activity 强引用；「PiP 中划掉应用」这条路不会走
      // onPictureInPictureModeChanged(false)，所以必须在这里显式反注册（清单 #20）
      // `pipHelper` 是 `lateinit var`（非可空），所以判「有没有初始化过」而不是判 null；
      // 输入校验失败等提前 return 的路径上它还没被赋值。
      if (::pipHelper.isInitialized) {
        pipHelper.release()
      }
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    super.onDestroy()
  }

  /**
   * 释放 mpv 内核（清单 #20）。
   *
   * ## 这里原先有两个 bug，都已修掉
   *
   * 1. **`command("quit")` 从未发出**（功能性 bug，可能原生崩溃）：
   *    原来的判断写的是 `if (isReady)`，而 `onDestroy` 开头已经把 `isReady = false`
   *    了 —— 于是 `quit` 与后面的等待整段都是**死代码**，直接从 `removeObserver`
   *    跳到 `destroy()`。而硬解的 UI 线程此时可能正在访问被销毁的互斥量/队列。
   *    现在改用 [mpvInitialized] 判断：它表示「内核有没有初始化过」，
   *    不受 `onDestroy` 里那行 `isReady = false` 影响，语义也对。
   *
   * 2. **主线程 `Thread.sleep(100)`**：
   *    每次退出播放页硬卡 100ms，而且「等固定时长」本身就不可靠（慢机器不够）。
   *    现在改成轮询 [PlayerLib.isIdleActive] 到真正空闲为止，且**有上限**，
   *    最坏情况才退化成原来的固定等待，不会无限卡住。
   *
   * ## 为什么这个等待不能搬到协程里
   *
   * `destroy()` 必须在等待完成**之后**同步调用（它是 native 的、不可挂起的）。
   * 把等待挪到后台再回来调用 `destroy()` 会留下一个「内核还活着但没人管」的窗口，
   * 反而更容易崩。所以这里保持同步 —— 区别是**从「盲等 100ms」变成「最多等 100ms，通常几毫秒就返回」**。
   */
  private fun cleanupMPV() {
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      PlayerLib.removeObserver(playerObserver)

      // 暂停可以真正降低活跃度，让 mpv 更快进入 idle
      PlayerLib.setPropertyBoolean("pause", true)
      // quit 是**异步**命令：投进队列就返回，此刻内部线程还在跑
      PlayerLib.command("quit")

      // 等 mpv 真正空闲，而不是盲等固定时长
      awaitMpvIdle()

      // ⚠️ 关闸必须在 destroy() **之前**（清单 #26）：
      // destroy() 一执行句柄就失效，而后面 `cleanupAudio` / `releaseMediaSession`
      // 以及 `onDestroy` 余下代码里仍有零散的属性访问（如音量、播放状态回写）。
      // 门禁关上后那些调用会被静默挡掉；不关就是打在已释放的句柄上 —— native 崩溃。
      PlayerLib.markCoreGone()

      PlayerLib.destroy()
      mpvInitialized = false
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  /**
   * 等 mpv 进入空闲状态，带时间上限。
   *
   * 上限取 100ms：与原先的 `Thread.sleep(100)` 同量级，所以**最坏情况不比以前更差**，
   * 而典型情况（quit 很快被处理）只等几毫秒。轮询间隔 5ms —— 再密也不会更准，
   * 因为 mpv 处理命令的粒度远大于 5ms。
   *
   * 不用 `Thread.sleep` 而用 `SystemClock.sleep`：显式表达「我就是要阻塞在这里」，
   * 避免以后有人 review 时以为这里挂错了线程。
   */
  private fun awaitMpvIdle() {
    val deadline = SystemClock.uptimeMillis() + MPV_IDLE_WAIT_BUDGET_MS
    while (SystemClock.uptimeMillis() < deadline) {
      if (PlayerLib.isIdleActive()) return
      SystemClock.sleep(MPV_IDLE_POLL_INTERVAL_MS)
    }
    Log.w(TAG, "MPV did not go idle within ${MPV_IDLE_WAIT_BUDGET_MS}ms; destroying anyway")
  }

  override fun abandonAudioFocus() {
    audioFocus.abandon()
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    audioFocus.unregisterNoisyReceiver()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPause() {
    if (!mpvInitialized) {
      super.onPause()
      return
    }
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause = (!audioPreferences.automaticBackgroundPlayback.get() && !isManualBackgroundPlayback) || 
                        (isUserFinishing && !isManualBackgroundPlayback)

      // Save playback state before stopping MPV or finishing
      // When finishing, do it synchronously so database is updated before MainActivity resumes
      if (!isInPip || isFinishing || isUserFinishing) {
        saveVideoPlaybackState(fileName, isSync = isFinishing || isUserFinishing)
      }

      // OPTIMIZATION: Stop playback immediately if finishing to reduce cleanup overhead
      if (isFinishing && !isManualBackgroundPlayback) {
        viewModel.pause()
        // Tell MPV to stop processing to reduce busywork during cleanup
        PlayerLib.command("stop")
      } else if (!isInPip && shouldPause) {
        // 这里原本还往 wasPlayingBeforePause 记一笔「暂停前在不在播」，但那个字段
        // 在音频焦点逻辑搬进 PlayerAudioFocus（改用 isPaused 实时回调）后就没有
        // 任何读取点了，属于拆分残留，已删。
        viewModel.pause()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isManualBackgroundPlayback) {
        restoreSystemUI()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun finish() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  // finishAndRemoveTask() was added in API 21, but since our minSdk is 26, it's always available
  override fun finishAndRemoveTask() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    if (!mpvInitialized) {
      super.onStop()
      return
    }
    runCatching {
      pipHelper.onStop()
      if (!isFinishing && !isUserFinishing) {
        saveVideoPlaybackState(fileName)
      }

      audioFocus.unregisterNoisyReceiver()

      // Handle background playback based on preferences
      val shouldAllowBackgroundPlayback = isManualBackgroundPlayback || 
                                          audioPreferences.automaticBackgroundPlayback.get()
      
      // Pause playback if background playback is not enabled and user is finishing
      if (!shouldAllowBackgroundPlayback && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onStart() {
    super.onStart()
    if (!mpvInitialized) return

    runCatching {
      setupWindowFlags()
      setupSystemUI()

      audioFocus.registerNoisyReceiver()

      // Apply remembered brightness for the current series, falling back to the global value.
      if (playerPreferences.rememberBrightness.get() ||
        playerPreferences.rememberSeriesBrightness.get()
      ) {
        // 按剧优先于全局默认（见 PlayerPreferences.brightnessFor）：
        // 上次把这部片调暗了，这次开播第一帧就该是暗的，不能先套全局值闪一下。
        // ⚠️ 不写「两份都开时取按剧的」以外的分支 —— 由 brightnessFor 统一裁决。
        val brightness = playerPreferences.brightnessFor(currentSeriesKey())
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      // Reset manual background playback flag when returning to foreground
      isManualBackgroundPlayback = false
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupSystemUI() {
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    // Set status bar color for when it will be shown (with controls)
    if (playerPreferences.showSystemStatusBar.get()) {
      @Suppress("DEPRECATION")
      window.statusBarColor = android.graphics.Color.parseColor("#80000000") // Semi-transparent black
    }

    // Always start with status bar hidden - it will show when controls are shown
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to setup system UI insets", e)
    }

    // Don't use LOW_PROFILE if we plan to show status bar with controls
    // LOW_PROFILE causes only icons to show without background
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun restoreSystemUI() {
    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  /**
   * 准备 mpv 的配置文件与脚本。
   *
   * ## 必须在 `player.initialize()` 之前、且在主线程调用
   *
   * 做三件纯文件 IO：
   * - `Utils.copyAssets`：从 assets 拷 mpv.conf / input.conf / scripts / shaders / fonts
   * - `syncFromUserMpvDirectory`：用户自建 mpv 目录（SAF）+ 字体目录同步
   * - `prepareAnime4KOnIoThread`：Anime4K shader（内部有 `.assets_copied` 标记文件的快路径，
   *   所以第二次起几乎不耗时间）
   *
   * ## 为什么这一段还是同步的（清单 #26 的教训）
   *
   * 曾经把它整块搬进 `lifecycleScope.launch { withContext(Dispatchers.IO) { … } }`，
   * 结果 `onCreate` 在内核 create 之前就返回，而 `MPVView` 是 `SurfaceView` ——
   * 首帧 traversal 触发的 `surfaceCreated → MPVLib.attachSurface()` 是 native 调用，
   * 打在还没 create 的句柄上 = SIGSEGV，JVM 拦不住 ⇒ 点播放必闪退。
   * 详见 `onCreate` 里那段注释。
   *
   * 真正该做的是「在 Application 启动时预热拷贝」，而不是把内核 create 推迟。
   *
   * 内核初始化本身在 [initializeMpvAndFinishSetup]（也是主线程，且必须如此：
   * `setOptionString` / `SurfaceView` 都要在主线程）。
   */
  private fun prepareMpvFilesOnIoThread() {
    // Copy essential files FIRST, before MPV initialization
    runCatching {
      Utils.copyAssets(this@PlayerActivity)
      // 用户自建 mpv 目录（SAF）+ 字体目录同步。
      // 逻辑已抽到 MpvAssetSync（清单 1.5 拆分第 1 刀）—— 它是纯文件 IO，
      // 不碰任何播放器状态，留在 Activity 里只会挡住真正的初始化流程。
      MpvAssetSync(
        context = this@PlayerActivity,
        advancedPreferences = advancedPreferences,
        subtitlesPreferences = subtitlesPreferences,
        fileManager = fileManager,
      ).syncFromUserMpvDirectory()
      // Anime4K 的 shader 也一起备好（清单 #20 必修 2）。
      // 必须在 `player.initialize()` 之前，因为 `glsl-shaders` 是 setOptionString，
      // 过了初始化期再设就不生效。
      player.prepareAnime4KOnIoThread()
      Log.d(TAG, "MPV config and scripts prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and scripts", e)
    }
  }

  override fun onResume() {
    super.onResume()
    if (!mpvInitialized) return
    if (!isInPictureInPictureMode) setOrientation()
    if (runCatching { currentPlaybackServer() }.isFailure) {
      PlayerLib.command("stop")
      viewModel.showPlaybackError("播放服务器配置已失效，请返回重新选择")
      return
    }
    updateVolume()
    // 诊断日志跟着「详细日志」开关走：这样用户在设置里改完开关、回到播放页就生效，
    // 不用重启应用。顺带把日志目录写进日志本身，省得还要翻代码找路径。
    PlayerDiagLog.configure(this, advancedPreferences.verboseLogging.get())
    PlayerDiagLog.log("session", "diag dir=${PlayerDiagLog.dirPath(this)}")
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    viewModel.synchronizeSystemVolume(volume)
    if (volume < viewModel.maxVolume) {
      viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
    }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return
    addSubtitlesFromExtras(extras)
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs")
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")
    val server = runCatching { currentPlaybackServer() }.getOrElse { return }
    val source = intent

    lifecycleScope.launch(Dispatchers.Default) {
      for (suburi in subList) {
        val subfile = app.marlboroadvance.mpvex.utils.media.SubtitleOps.readablePath(
          this@PlayerActivity, suburi, server = server,
        ) ?: continue
        if (intent !== source) return@launch
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        PlayerLib.command("sub-add", subfile, flag)
      }
    }
  }

  /** Bind the validated launch URL and its URL-scoped headers. */
  private fun bindPlaybackHeaders(source: Intent) {
    val input = EmbyPlaybackInput.read(source) ?: return
    playbackServerId = input.serverId
    playbackServer = input.resolveServer(embyRepository)
    playbackHeaders = PlayerHttpHeaders.parse(source.getStringArrayExtra(PlayerHandoff.EXTRA_HEADERS))
    playbackUrl = input.handoff.playlist.getOrNull(input.handoff.index)?.toString()
      ?: extractUriFromIntent(source)?.toString()
    viewModel.subtitleServer = playbackServer
  }

  /** Revalidate source/config identity, while allowing this row's memory token to refresh. */
  private fun currentPlaybackServer(): EmbyServer {
    val bound = playbackServer ?: throw IllegalStateException("播放服务器已不可用，请返回重新选择")
    return embyRepository.sessionServer(bound)
  }

  private fun httpHeadersForUrl(url: String, server: EmbyServer = currentPlaybackServer()): Map<String, String> =
    PlayerHttpHeaders.forUrl(url, playbackHeaders, server)

  /** Validate primary media before handing headers or a URL to the native player. */
  private fun setHttpHeadersForUri(uri: Uri): Boolean {
    if (!EmbyPlaybackInput.isPlayableUrl(uri.toString())) {
      viewModel.showPlaybackError("仅支持 Emby HTTP(S) 媒体播放")
      return false
    }
    PlayerLib.setPropertyString("http-header-fields", "")
    PlayerLib.setPropertyString("user-agent", EmbyClient.STREAM_USER_AGENT)
    if (loopback.isLocalPlayUrl(uri.toString())) {
      viewModel.subtitleServer = currentPlaybackServer()
      loembyPlaybackState.value = null
      playbackUrl = uri.toString()
      return true
    }
    viewModel.subtitleServer = null
    return try {
      val server = currentPlaybackServer()
      val headers = httpHeadersForUrl(uri.toString(), server)
      viewModel.subtitleServer = server
      playbackUrl = uri.toString()
      headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let {
        PlayerLib.setPropertyString("user-agent", it.value)
      }
      PlayerLib.setPropertyString("http-header-fields", PlayerHttpHeaders.mpvHeaderFields(headers))
      true
    } catch (_: Exception) {
      viewModel.showPlaybackError("播放服务器配置已失效，请返回重新选择")
      false
    }
  }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    return uri.lastPathSegment.orEmpty()
  }

  /**
   * 取播放下标对应的显示标题。
   *
   * @return 标题；下标越界或标题为空时返回 null，交由调用方回落到 URL 解析。
   */
  private fun getPlaylistTitleAt(index: Int): String? =
    playlistTitles.getOrNull(index)?.takeIf { it.isNotBlank() }

  /** Emby callers supply primary media through data or the internal queue. */
  private fun extractUriFromIntent(intent: Intent): Uri? = intent.data

  /** 返回当前媒体 URI：优先取内部队列当前项，否则从 intent 解析。供播放器交接使用。 */
  internal fun getCurrentPlayingUri(): Uri? {
    if (playlist.isNotEmpty() && playlistIndex in playlist.indices) return playlist[playlistIndex]
    return extractUriFromIntent(intent)
  }

  /** Validated Emby HTTP(S) input, without local file/FD/disc conversion. */
  private fun getPlayableUri(intent: Intent): String? =
    EmbyPlaybackInput.read(intent)?.uri?.toString()

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // ── 诊断埋点 ──
    // 「挂了外挂字幕时旋转会卡一下」这条线，在排除掉 Activity 重建 / 我们自己的旋转路径 /
    // 字体重解析 / 字幕恢复链重跑这四条之后，只剩 mpv 内部的 resize 路径，没有实机数据定不了案。
    // 这里在**旋转那一刻**与**1.2 秒后**各拍一行：播放位置走了多少、丢帧计数涨了多少 ——
    // 用来区分「画面真的停住」和「只是观感上卡」。只在设置里打开「详细日志」时才写文件。
    PlayerDiagLog.log("rotate", "orient=${orientationName(newConfig)} ${playbackProbe()}")
    lifecycleScope.launch {
      kotlinx.coroutines.delay(1200)
      PlayerDiagLog.log("rotate+1200ms", playbackProbe())
    }
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /** 诊断用：横竖屏名，只为日志好读。 */
  private fun orientationName(config: Configuration): String =
    if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

  /**
   * 诊断用：把「这一刻播到哪儿、丢了多少帧、字幕选的是哪条」拍成一行。
   *
   * `time-pos` 两次采样的差值 + `frame-drop-count` 的增量，是判断旋转时**画面是否真的停住**
   * 的客观依据 —— 光靠「感觉卡」定位不了（这也是为什么要专门把日志落盘：
   * 非 root 手机上第三方 logcat 应用读不到别的应用的日志）。
   */
  private fun playbackProbe(): String =
    runCatching {
      val pos = PlayerLib.getPropertyDouble("time-pos")
      val drops = PlayerLib.getPropertyInt("frame-drop-count")
      val sid = PlayerLib.getPropertyInt("sid")
      val vo = PlayerLib.getPropertyString("current-vo")
      "pos=%.3f drops=%d sid=%d vo=%s".format(pos ?: -1.0, drops ?: -1, sid ?: -2, vo ?: "?")
    }.getOrElse { "probe-failed=${it.message}" }

  /**
   * 诊断用：**稳态**下的解码 / 渲染实况（首帧之后 3 秒才拍）。
   *
   * 与 [playbackProbe] 的分工：那个是「某一瞬间画面停没停」，这个是「这条片子到底
   * 跑在什么路径上、卡在哪一级」。四个问题一次答完：
   *  · `hwdec-current` —— 用户以为在硬解，实际拿到的是 `mediacodec-copy` 还是 `no`。
   *    本项目 `hwdec=mediacodec,mediacodec-copy,no` 配合 `vo=gpu-next`，第一档
   *    （零拷贝 mediacodec）**永远选不上**，能拿到的上限就是拷贝回读；
   *    驱动拒绝这个分辨率/帧率时（4K60 竖屏 AVC 很常见）会静默掉到 `no` = ffmpeg 软解。
   *  · `estimated-vf-fps` vs `container-fps` —— 解码侧到底交出了多少帧；
   *  · `decoder-frame-drop-count` vs `frame-drop-count` —— 瓶颈在解码器还是在 vo；
   *  · `display-fps` —— 屏幕刷新率，用来判断 60fps 片源是不是撞上 60Hz vsync 的抖动。
   */
  private fun steadyStateProbe(): String =
    runCatching {
      val hwdec = PlayerLib.getPropertyString("hwdec-current") ?: "?"
      val vo = PlayerLib.getPropertyString("current-vo") ?: "?"
      val srcFps = PlayerLib.getPropertyDouble("container-fps") ?: -1.0
      val outFps = PlayerLib.getPropertyDouble("estimated-vf-fps") ?: -1.0
      val dispFps = PlayerLib.getPropertyDouble("display-fps") ?: -1.0
      val decDrop = PlayerLib.getPropertyInt("decoder-frame-drop-count") ?: -1
      val voDrop = PlayerLib.getPropertyInt("frame-drop-count") ?: -1
      val vw = PlayerLib.getPropertyInt("width") ?: -1
      val vh = PlayerLib.getPropertyInt("height") ?: -1
      "hwdec=$hwdec vo=$vo ${vw}x$vh src=%.2ffps out=%.2ffps disp=%.2ffps decDrop=$decDrop voDrop=$voDrop"
        .format(srcFps, outFps, dispFps)
    }.getOrElse { "steady-probe-failed=${it.message}" }

  /**
   * 首帧之后 3 秒做一次「稳态体检」。两件事：
   *
   *  ① 把 [steadyStateProbe] 的读数落盘（详细日志关着时 `PlayerDiagLog.log` 自己短路）；
   *  ② **直出模式的兜底**：用户开了直出、但硬解没真的立起来时，`vo=mediacodec_embed`
   *    只认硬解帧，结果是**纯黑屏且一条报错都没有**。这种情况必须当场告诉用户
   *    去把直出模式关掉 —— 否则他只会以为 App 播不了这个视频。
   *
   * 为什么延后 3 秒：`PLAYBACK_RESTART` 那一刻计数器还全是 0，`hwdec-current`
   * 也可能没落定（硬解重协商要走一次 reconfig）。等到稳态才是有意义的读数。
   */
  private fun watchSteadyState() {
    lifecycleScope.launch {
      kotlinx.coroutines.delay(3000)
      if (isFinishing || isDestroyed) return@launch

      val probe = steadyStateProbe()
      PlayerDiagLog.log("steady", probe)

      if (!decoderPreferences.isDirectOutput()) return@launch
      val hwdec = runCatching { PlayerLib.getPropertyString("hwdec-current") }.getOrNull().orEmpty()
      val vo = runCatching { PlayerLib.getPropertyString("current-vo") }.getOrNull().orEmpty()
      val zeroCopy = hwdec.startsWith("mediacodec") && vo == "mediacodec_embed"
      // 这两行**特意绕过**「详细日志」开关：把日志开起来本身就会加 msg-level=all=v
      // 和 log-file 落盘，用它去测「还卡不卡」等于带着沙袋跑步。这里一次播放只写一行。
      PlayerDiagLog.forceLog(if (zeroCopy) "direct-ok" else "direct-failed", probe)

      // 直出模式是用户主动开的，就把读数直接摆出来 —— 否则「到底走没走到零拷贝」
      // 只能靠猜（`hwdec-current` 是 mediacodec 还是 mediacodec-copy 差着一个数量级的拷贝量）。
      runOnUiThread {
        if (!isFinishing && !isDestroyed) {
          val head =
            getString(
              if (zeroCopy) {
                app.marlboroadvance.mpvex.R.string.player_direct_output_ok
              } else {
                app.marlboroadvance.mpvex.R.string.player_direct_output_failed
              },
            )
          android.widget.Toast.makeText(
            this@PlayerActivity,
            "$head\n$probe",
            android.widget.Toast.LENGTH_LONG,
          ).show()
        }
      }
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    PlayerDiagLog.log("rotate-handled", "pip=$isInPictureInPictureMode")
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h",
      "video-params/rotate" -> {
        // Safety check: don't access MPV during cleanup.
        if (isFinishing || !mpvInitialized || player.isExiting) return

        val aspect = currentVideoAspect()
        Log.d(TAG, "Video dimension changed: $property, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()
        lockOrientationFromVideo()

        // Re-apply Anime4K shaders (check for resolution limit).
        player.applyAnime4KShaders()

        // 解码器重协商（例如 P5 从硬解降软解）会再报一次 video-params/w，
        // 此刻色彩元数据才稳定 ⇒ 补一次策略判定（幂等，状态没变就不碰 Window）。
        PlaybackColorPolicy.apply(this, decoderPreferences)
      }
    }
  }

  /** 当前视频宽高比：走 MPVView（含 rotate 修正） */
  private fun currentVideoAspect(): Double? = player.getVideoOutAspect()

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady) {
          isReady = true
        }
      }
      "eof-reached" -> if (value) loemby.eofReached(this)
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }
  }

  /** 自动连播倒计时的盯梢协程；切集 / 取消 / 退出时都要 cancel，避免播完后还偷偷切集 */
  private var autoplayCountdownJob: kotlinx.coroutines.Job? = null

  /**
   * 用户在倒计时卡片上点过「取消」：本集播完不再自动切，停在最后一帧。
   * 只影响当前这一集，切集 / 开播下一集时清零。
   */
  private var autoplayCancelled = false

  /**
   * 用户在本集点过「下一集」悬停卡上的 ×：本集内不再弹这张卡。
   *
   * ⚠️ 必须有这个标记，不能只把 `nextUpVisible` 置 false ——
   * 片尾盯梢每 500ms 复算一次出现窗口，只要剩余时长还在窗口内就会把 visible 重新置 true，
   * 于是「叉掉半秒后又弹出来」⇒ × 形同虚设。只在每集开播时清零（见 loadFile 里
   * `startAutoplayCountdownWatcher()` 的调用点），因此语义是「这一集别再烦我，下一集照常提示」。
   */
  private var nextUpDismissed = false

  // ==================== 片头片尾跳过（b2+a1） ====================

  /**
   * 片头 / 片尾盯梢协程：拉精准分段 + 每 0.5 秒看一次播放头在哪。
   * 切集（重新起一轮）/ 退出时 cancel。
   */
  private var skipSegmentJob: kotlinx.coroutines.Job? = null

  /** 片头区间（毫秒，闭开区间 start..<end）；null = 这一段没有数据 */
  private var introRangeMs: Pair<Long, Long>? = null

  /** 片尾区间（毫秒）；null = 这一段没有数据 */
  private var outroRangeMs: Pair<Long, Long>? = null

  /** 本集是否已经自动跳过片头 / 片尾过一次（跳完置 true，用户往回拖也不会再跳） */
  private var introSkipped = false
  private var outroSkipped = false

  /**
   * 每集开播时起一轮「片头片尾」处理。
   *
   * 两步：
   * 1. **拉精准数据**：Emby 4.7+ 的 `/Users/{UserId}/Items/{Id}/Intros`。
   *    拿不到（非 Emby 播放 / 服务端没扫描 / 网络断）就退到固定时长兜底，
   *    见 [PlayerPreferences.autoSkipIntro] 的 KDoc。
   * 2. **轮询**：0.5 秒精度（同连播盯梢，不观察 time-pos —— 每帧回调的 JNI
   *    开销在这里不值）。自动跳开关开着 ⇒ 播放头进入区间且没暂停就跳到段尾；
   *    开关关着 ⇒ 把「跳过片头 / 跳过片尾」小胶囊的文案写进
   *    [PlayerViewModel.skipSegmentLabel]，等用户自己点（手动精准跳）。
   *
   * 注意**两个开关都关着盯梢照样跑**：手动胶囊只需要精准数据，不需要开关。
   * 代价是每集一次小 GET（纯本地播放没有这个请求）。
   */
  private fun startSkipSegmentWatcher() {
    skipSegmentJob?.cancel()
    viewModel.skipSegmentLabel.value = null
    introRangeMs = null
    outroRangeMs = null
    introSkipped = false
    outroSkipped = false

    val autoIntro = playerPreferences.autoSkipIntro.get()
    val autoOutro = playerPreferences.autoSkipOutro.get()
    val fixedMs = playerPreferences.customSkipDuration.get().coerceAtLeast(1) * 1000L

    skipSegmentJob =
      lifecycleScope.launch {
        // 1) 精准分段（失败 / 没有都算正常，静默退兜底）
        val serverId = currentEmbyServerId()
        val itemId = currentEmbyItemId()
        if (serverId != null && !itemId.isNullOrBlank()) {
          val server = runCatching { embyRepository.getServer(serverId) }.getOrNull()
          if (server != null) {
            val result = runCatching { embyRepository.getItemIntros(server, itemId) }.getOrNull()
            result?.Segments?.forEach { seg ->
              if (!seg.isValid) return@forEach
              when (seg.Type?.lowercase()) {
                "intro" -> introRangeMs = seg.startMs to seg.endMs
                "outro" -> outroRangeMs = seg.startMs to seg.endMs
              }
            }
          }
        }

        // 2) 轮询播放头
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
          kotlinx.coroutines.delay(500)
          if (isFinishing) break
          val durationMs = ((PlayerLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
          if (durationMs <= 0) continue
          val posMs = ((PlayerLib.getPropertyDouble("time-pos") ?: continue) * 1000).toLong()
          val paused = PlayerLib.getPropertyBoolean("pause") ?: false

          // 没有精准数据时按固定时长兜底（**只在自动跳开着时兜底** ——
          // 关着时只认精准数据，否则每集开头都挂个「跳过片头」纯属打扰，
          // 控制条上本来就有固定时长的快进钮）。
          // 短片（总长 ≤ 2×兜底值）不兜底：几分钟的片子不可能有 90 秒片头。
          val fixedIntroUsable = durationMs > fixedMs * 2
          val intro = introRangeMs ?: if (autoIntro && fixedIntroUsable) 0L to fixedMs else null
          val outro = outroRangeMs ?: if (autoOutro && fixedIntroUsable) (durationMs - fixedMs) to durationMs else null

          // 手动胶囊：开关关着、播放头在区间里 ⇒ 给个可点的提示
          val label =
            when {
              !autoIntro && intro != null && posMs in intro.first until intro.second -> "跳过片头"
              !autoOutro && outro != null && posMs >= outro.first && posMs < outro.second -> "跳过片尾"
              else -> null
            }
          viewModel.skipSegmentLabel.value = label

          // 自动跳：只跳一次、且不趁用户暂停时跳
          if (!paused && !introSkipped && autoIntro && intro != null && posMs in intro.first until intro.second) {
            introSkipped = true
            viewModel.skipSegmentLabel.value = null
            runCatching { PlayerLib.command("seek", (intro.second / 1000.0).toString(), "absolute") }
          }
          if (!paused && !outroSkipped && autoOutro && outro != null && posMs >= outro.first && posMs < outro.second) {
            outroSkipped = true
            viewModel.skipSegmentLabel.value = null
            // 跳到结尾前 0.2 秒： seek 到报告的 duration 有时差一点点到不了 EOF
            runCatching {
              PlayerLib.command("seek", ((durationMs - 200) / 1000.0).toString(), "absolute")
            }
          }
        }
      }
  }

  /** 切集 / 退出时停掉片头片尾盯梢（不 cancel 的话旧条目的分段会一直生效） */
  private fun cancelSkipSegmentWatcher() {
    skipSegmentJob?.cancel()
    skipSegmentJob = null
    viewModel.skipSegmentLabel.value = null
  }

  /**
   * 每集开播时起一个轻量轮询：**快播完的那几秒**把倒计时卡片推到屏幕上。
   *
   * 不用「播完再倒数」的做法 —— 那样视频已经停在最后一帧了，
   * 用户还要干等几秒才切，纯粹是浪费时间。现在卡片在片尾提前出现，
   * 真正切集仍然发生在播完的那一刻（[onPlaybackEndedAutoplay]），一秒都不多等。
   *
   * 轮询而不是观察 mpv 的 time-remaining：后者每帧回调一次、JNI 开销明显，
   * 而这里只需要 0.5 秒精度。
   */
  private fun startAutoplayCountdownWatcher() {
    autoplayCountdownJob?.cancel()
    val total = playerPreferences.autoplayNextCountdownSeconds.get()
    // 下一集卡片（Yamby 的 next_up_sec）：比倒计时更早出现，给用户「要不要继续看」的决策时间。
    val nextUpTotal = if (playerPreferences.showNextUpCard.get()) {
      playerPreferences.nextUpShowSeconds.get().coerceIn(0, 300)
    } else {
      0
    }
    if (total <= 0 && nextUpTotal <= 0) {
      autoplayCountdownJob = null
      return
    }
    val startIndex = playlistIndex
    autoplayCountdownJob = lifecycleScope.launch {
      while (kotlinx.coroutines.currentCoroutineContext().isActive) {
      kotlinx.coroutines.delay(500)
      // 用户自己切了集 / 退出了 / 在片尾取消了：这次盯梢作废
      if (playlistIndex != startIndex || isFinishing || autoplayCancelled) break
      val nextIdx = peekNextIndex()
      if (nextIdx == null) break // 没有下一集
      val left = wallClockSecondsToEnd() ?: continue
      // 下一集卡片：比倒计时早 nextUpTotal 秒出现。
      // 用户点过 × ⇒ 本集不再进入这个分支，交给下面的 else 分支确保它保持收起。
      if (nextUpTotal > 0 && !nextUpDismissed && left in 0.1..nextUpTotal.toDouble()) {
        if (viewModel.nextUpTitle.value == null) {
          viewModel.nextUpTitle.value = nextIdx?.let { playlistTitles.getOrNull(it) }
        }
        viewModel.nextUpVisible.value = true
      } else if (viewModel.nextUpVisible.value) {
        // 三种情况都收起来：用户叉掉了 / 往后拖出了窗口 / 设置里关了这一项
        viewModel.nextUpVisible.value = false
        viewModel.nextUpTitle.value = null
      }
      // 进入最后 total 秒：把卡片推出来，之后每 0.5 秒刷新一次，数字自然往下走
      if (total > 0 && left in 0.1..total.toDouble()) {
        if (viewModel.autoplayNextTitle.value == null) {
          viewModel.autoplayNextTitle.value = peekNextIndex()?.let { playlistTitles.getOrNull(it) }
        }
        viewModel.autoplayCountdown.value = kotlin.math.ceil(left).toInt().coerceAtLeast(1)
      } else if (total > 0 && left > total && viewModel.autoplayCountdown.value > 0) {
        // 用户往后拖了进度条：卡片收起来，等再次接近片尾再出现
        viewModel.autoplayCountdown.value = 0
        viewModel.autoplayNextTitle.value = null
      }
    }
  }
}

/**
 * 距离本集播完还剩多少**真实**秒数（考虑倍速）。
 *
 * mpv 的 duration / time-pos 都是媒体时间，2 倍速下剩 10 秒片尾实际只要 5 秒就放完，
 * 所以要除以当前倍速，否则卡片会早一倍时间弹出来。
 */
private fun wallClockSecondsToEnd(): Double? {
  val duration = PlayerLib.getPropertyDouble("duration") ?: return null
  val position = PlayerLib.getPropertyDouble("time-pos") ?: return null
  val speed = PlayerLib.getPropertyDouble("speed") ?: 1.0
  if (duration <= 0 || speed <= 0) return null
  return (duration - position) / speed
}

/**
 * 播完一集时调用：默认直接切下一集（一秒都不等），
 * 除非用户刚才在卡片上点了「取消」——那就停在最后一帧，等他手动操作。
 *
 * 取消标记用完即清，只影响「这一集」，下一集重新给机会。
 */
private fun onPlaybackEndedAutoplay() {
  val cancelled = autoplayCancelled
  autoplayCancelled = false
  autoplayCountdownJob?.cancel()
  autoplayCountdownJob = null
  viewModel.autoplayCountdown.value = 0
  viewModel.autoplayNextTitle.value = null
  if (cancelled) return
  playNext()
}

/** 取消倒计时：卡片上的「取消」按钮，以及用户手动切集 / 退出时都要调用 */
private fun cancelAutoplayCountdown() {
  autoplayCountdownJob?.cancel()
  autoplayCountdownJob = null
  viewModel.autoplayCountdown.value = 0
  viewModel.autoplayNextTitle.value = null
  viewModel.nextUpVisible.value = false
  viewModel.nextUpTitle.value = null
}

  /** 下一个要播的条目下标（不切换，供片尾提示取标题）。 */
  private fun peekNextIndex(): Int? =
    playlist.takeIf { it.isNotEmpty() && playlistIndex < it.lastIndex }?.let { playlistIndex + 1 }

  /**
   * 当前这一集对应的「记忆键」：列表播放按下标取，单文件播放用 intent 里那个。
   *
   * 取不到就返回 null，此时播放速度 / 音轨记忆整体跳过（本地播放就是这种情况）。
   */
  private fun currentSeriesKey(): String? =
    playlistSeriesKeys.getOrNull(playlistIndex) ?: singleSeriesKey

  /**
   * 当前这一集所属**剧**的名字；没有剧名时（电影 / 本地文件 / 音乐）退回 [fileName]。
   *
   * ⚠️ 仅供弹幕自动匹配使用 —— 它要搜的是整部番，不是某一集的标题。
   *
   * @param index 目标条目在队列里的下标
   */
  private fun danmakuMatchTitle(index: Int = playlistIndex): String {
    val series = playlistSeriesNames.getOrNull(index) ?: singleSeriesName
    return if (!series.isNullOrBlank()) series else fileName
  }

  /**
   * 通知弹幕状态机「现在是哪一集」。
   *
   * ## 为什么要封装
   *
   * 有两个时机需要通知：切集（[loadPlaylistItemInternal]）和**首次起播**
   * （[handleFileLoaded]，那条路径是 `playFile` 直通，不经队列装载）。
   * 两处的参数口径必须完全一致 —— 尤其是集号要用 [currentEpisodeIndex] 而不是队列下标，
   * 否则「从第 5 集点进详情页播放」会被当成第 1 集。
   *
   * ⚠️ 队列下标必须**显式传**：切集时调用方手里的 `index` 才是目标条目，
   * 而 `handleFileLoaded` 时 `playlistIndex` 已经更新过了。默认参数取 `playlistIndex`，
   * 两个时机都成立。
   *
   * @param index 目标条目在队列里的下标；默认当前正在播的那一条
   */
  private fun notifyDanmakuEpisodeChanged(index: Int = playlistIndex) {
    danmakuState?.onEpisodeChanged(
      currentSeriesKey(),
      danmakuMatchTitle(index),
      currentEpisodeIndex(index),
    )
  }

  /**
   * 当前这一条在**剧集里的真实集号（0-based）**，供弹幕「自动按当前集挑」使用。
   *
   * 弹幕面板手动选番时要按「当前是第几集」取那一集，而 [playlistIndex] 是队列下标 ——
   * 从第 5 集点播时队列只有 1 条，用下标会取成第 1 集。
   */
  fun currentEpisodeIndexForDanmaku(): Int = currentEpisodeIndex()

  /**
   * 当前这一条在**剧集里的真实集号（0-based）**，供弹幕匹配使用。
   *
   * 之所以不能直接用 [playlistIndex]：
   * - 从第 5 集点进详情页播放时，队列只有 1 条 ⇒ 下标 0 ⇒ 会被当成第 1 集；
   * - 单文件播放（`launchSingle`）根本没有队列，下标恒为 0。
   *
   * 优先用 intent 里下发的 Emby 原始序号（`S02E05` → `2005` → 2004）；
   * 拿不到（本地播放 / 网盘）才退回队列下标。
   *
   * @param index 要查的队列下标；默认取当前的 [playlistIndex]。
   */
  private fun currentEpisodeIndex(index: Int = playlistIndex): Int {
    val encoded = playlistEpisodeNumbers.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return index
    val number = encoded.toIntOrNull() ?: return index
    // 编码是 season*1000 + episode：整除得季、取余得集。
    // 集号从 1 开始，转成 0-based 后再交给 DanmakuMatcher（它内部再 +1）。
    val episode = number % 1000
    return if (episode > 0) episode - 1 else index
  }

  /**
   * 当前这一条对应的「多版本名」（如 "1080P HEVC"）；不是多版本条目就返回 null。
   *
   * 供「更多 → 媒体信息」弹窗显示「现在播的是哪个版本」—— 不再拼进顶栏片名
   * （片名 + 版本名一长，标题栏会更挤）。
   * 读 [playlistIndex]（Compose 状态）⇒ 切集后会自动跟着换。
   */
  fun currentMediaSourceName(): String? =
    playlistMediaSourceNames.getOrNull(playlistIndex)?.takeIf { it.isNotBlank() }

  /**
   * 当前播放条目的 Emby 服务器 Id；不是 Emby 播放（本地文件 / 网盘）返回 null。
   */
  fun currentEmbyServerId(): Long? =
    intent
      .getLongExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_SERVER_ID,
        -1L,
      )
      .takeIf { it > 0 }

  /**
   * 当前播放条目的 Emby ItemId；不是 Emby 播放 / 解析不出来返回 null。
   *
   * 优先从 **mpv 当前的 `path`** 反查；预解析直链无法反查时，
   * 才按 [playlistIndex] 读取内部队列对应的 ItemId。
   */
  fun currentEmbyItemId(): String? {
    return loembyPlaybackState.value?.spec?.itemId
      ?: playlistItemIds.getOrNull(playlistIndex)?.takeIf { it.isNotBlank() }
  }

  /**
   * 当前播放条目**实际在播的那个版本**的媒体源 Id；单版本 / 非 Emby 返回 null。
   *
   * 查的是 [embyMediaSourceByItemId]（ItemId → MediaSourceId 的 Map），
   * 与队列顺序无关，正常或随机切集都会取当前条目对应的媒体源。
   */
  fun currentEmbyMediaSourceId(): String? =
    currentEmbyItemId()
      ?.let { embyMediaSourceByItemId[it] }
      ?.takeIf { it.isNotBlank() }

  /**
   * 从 intent 里取出「ItemId → 媒体源 Id」映射（多版本条目才有值）。
   *
   * intent 里 `emby_item_ids` 与 `emby_media_source_id` 是**同序同长**的两串，
   * 这里把它们 zip 成 Map；空串（该条没有多版本）直接丢掉，不入表。
   */
  private fun buildEmbyMediaSourceMap(intent: Intent): Map<String, String> {
    val ids =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
      ).orEmpty()
    val sources =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID,
      ).orEmpty()
    if (ids.isEmpty() || sources.isEmpty()) return emptyMap()
    return buildMap<String, String> {
      ids.forEachIndexed { index, id ->
        val source = sources.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        if (id.isNotBlank()) put(id, source)
      }
    }
  }

  /**
   * 当前播放条目的「按版本进度键」；非 Emby / 单版本 / 该条没选版本时返回 null。
   *
   * 键形状与详情页算的完全一致（同一份 [EmbyVersionProgress]），两个页面各算各的、
   * 不需要互通。详见该对象的注释：Emby 服务端的进度是按条目共享的，多版本条目只能本地分开记。
   *
   * ItemId 从 **mpv 当前路径**反查，确保进度键对应实际在播的条目。
   */
  private fun currentEmbyVersionProgressKey(): String? {
    val serverId =
      intent.getLongExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_SERVER_ID,
        -1L,
      )
    if (serverId <= 0) return null
    val itemId = currentEmbyItemId() ?: return null
    val sourceId = embyMediaSourceByItemId[itemId]?.takeIf { it.isNotBlank() } ?: return null
    return app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress.key(serverId, itemId, sourceId)
  }

  /**
   * 切集后把记忆键同步给 ViewModel —— 用户在这一集里改的速度 / 音轨要记到「这一部剧」名下。
   */
  private fun refreshSeriesKey() {
    viewModel.seriesKey = currentSeriesKey()
  }

  /**
   * 应用「本剧上次记住的播放速度 / 音轨」。
   *
   * 只在开关打开且有记忆键时生效；音轨按指纹匹配（见 [PlaybackMemory]），
   * 匹配不到就什么都不做，交给 TrackSelector 的默认语言逻辑。
   */
  private fun applyRememberedPlaybackSettings() {
    val key = currentSeriesKey()
    viewModel.seriesKey = key
    // b4 全局倍速记忆作为**兜底**：先套全局上次倍速，再让每剧记忆覆盖。
    // 顺序不能反 —— 每剧记忆优先级更高（同剧切集沿用本剧调过的速度）。
    if (playerPreferences.rememberGlobalSpeed.get()) {
      val last = playerPreferences.lastPlaybackSpeed.get()
      if (last > 0f) {
        runCatching { PlayerLib.setPropertyDouble("speed", last.toDouble()) }
      }
    }
    if (key.isNullOrBlank()) return
    if (playerPreferences.rememberSpeedPerSeries.get()) {
      val speed = PlaybackMemory.speedFor(playerPreferences, key)
      if (speed != null && speed > 0f) {
        runCatching { PlayerLib.setPropertyDouble("speed", speed.toDouble()) }
      }
    }
    if (loopback.isLocalPlayUrl(playbackUrl.orEmpty())) return
    if (playerPreferences.rememberAudioTrackPerSeries.get()) {
      val fingerprint = PlaybackMemory.audioFingerprintFor(playerPreferences, key)
      if (!fingerprint.isNullOrBlank()) {
        val trackId = PlaybackMemory.findAudioTrackId(fingerprint)
        if (trackId != null && trackId > 0) {
          runCatching { PlayerLib.setPropertyInt("aid", trackId) }
        }
      }
    }
    if (playerPreferences.rememberSubtitleTrackPerSeries.get()) {
      val fingerprint = PlaybackMemory.subtitleFingerprintFor(playerPreferences, key)
      if (!fingerprint.isNullOrBlank()) {
        val trackId = PlaybackMemory.findSubtitleTrackId(fingerprint)
        if (trackId != null && trackId > 0) {
          runCatching { PlayerLib.setPropertyInt("sid", trackId) }
        }
      }
    }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (remoteIsoRejected) return
    if (isEof) {
      // 诊断埋点（v162）：EOF 时「停在哪儿」比「有没有 EOF」有用得多。
      // 若 `pos` 比 `duration` 小了明显一截，这条流是**被提前掐断**的
      // （上游的问题，不是片子真播完了）。
      PlayerDiagLog.log(
        "eof",
        "pos=${PlayerLib.getPropertyDouble("time-pos")} " +
          "duration=${PlayerLib.getPropertyDouble("duration")}",
      )
      // Handle playlist playback
      if (playlist.isNotEmpty()) {
        val hasNextItem = playlistIndex < playlist.size - 1

        // Check if autoplay next video is enabled
        val autoplayEnabled = playerPreferences.autoplayNextVideo.get()
        if (hasNextItem && autoplayEnabled) {
          // 卡片在片尾倒数阶段已经弹过了，这里直接切，不再多等；
          // 只有用户在卡片上点过「取消」才会停住不动。
          onPlaybackEndedAutoplay()
        } else if (!hasNextItem && playerPreferences.closeAfterReachingEndOfVideo.get()) {
          // 只有"真的没有下一集可播"时才退出播放器（整个队列/单文件播放结束）。
          // autoplay 关闭但队列里还有后续时，属于"等用户手动切"，不能算播放结束，
          // 因此停住而不是退出 —— 否则会和"自动下一集"抢同一段判断。
          // isReady 兜底：从来没真正起播过（例如备用内核打开失败）时不能退出，
          // 否则表现就是"进播放器转两下就被踢回上一页"。
          if (isReady) finishAndRemoveTask()
        }
        // 其余情况（autoplay 关 且 队列还有后续）：停在当前视频，等用户手动切集
      } else {
        // Single video playback (no playlist)
        // 同样要求真的起播过：备用内核打开失败时不能把用户踢回上一页。
        if (playerPreferences.closeAfterReachingEndOfVideo.get() && isReady) {
          finishAndRemoveTask()
        }
      }
    }
  }

  /** 时长看守协程（每片一份，换片即取消） */
  private var durationWatchdogJob: Job? = null

  /**
   * 时长看守：mpv 报的时长**不可信**时，用别的来源把它救回来（v163 起，v164 扩到「报小了」）。
   *
   * ## mpv 在 `.mts` 上会怎么坏
   *
   * MPEG-TS **没有内嵌索引**，时长只能靠「seek 到文件尾、读最后一个 PTS」算。取流层
   * 一旦不能随机读取（或读到的那一段不是真尾部），mpv 的 `duration` 有两种坏法：
   *  · **恒为 0** ⇒ 进度条 00:00、拖不动；
   *  · **报一个很小的值** ⇒ 播到 15 秒了总时长还写着 8 秒，进度条一开就顶到 100%。
   *
   * ## 判据只认物理事实
   *
   * 不再按「mpv 报 0 才探」这种窄条件，而是每秒看一眼：
   * **已经播到的位置 > 声称的总时长 + 1s，且还没到 EOF** ⇒ 这个时长一定是错的。
   * 位置超过总时长是不需要讨论的矛盾，任何估算都解释不了它；
   * 反过来，播放正常推进、位置始终落在总时长之内，就当它是对的，立刻收工。
   *
   * ## 两条后备来源（按可信度排序）
   *
   *  ① [getVideoDurationFromUri] → `MediaMetadataRetriever`：**与 GSY 同源**
   *     （ExoPlayer / MediaExtractor 用「文件长度 ÷ 平均码率」估算，不需要 seek），
   *     GSY 显示多少这里就探到多少。用户认 GSY 的值，那就照它的口径来。
   *  ② 兜底再兜底：`file-size × 8 ÷ (video-bitrate + audio-bitrate)` ——
   *     用 mpv 自己报的码率反推。VBR 会有偏差，但量级一定对，
   *     比「播到 15 秒总时长 8 秒」强得多。
   *
   * ⚠️ 两条都要求结果 **大于已经播过的位置**（否则就是又一次同样的错），探不到就下一轮再来
   * （多播几秒后码率估得更准），最多 [MAX_DURATION_PROBES] 轮。
   * ⚠️ 探到 0 / 探不到 / 真直播 ⇒ 什么都不写，UI 如实显示，绝不编一个假时长出来。
   */
  private fun startDurationWatchdog() {
    durationWatchdogJob?.cancel()
    // 换片先清：不清的话新片会顶着上一部片的时长画进度条
    viewModel.resetProbedDuration()
    durationWatchdogJob =
      lifecycleScope.launch {
        var attempts = 0
        while (true) {
          delay(DURATION_WATCHDOG_INTERVAL_MS)
          if (PlayerLib.getPropertyBoolean("eof-reached") == true) return@launch
          val pos = PlayerLib.getPropertyDouble("time-pos") ?: continue
          val mpvDuration = PlayerLib.getPropertyDouble("duration") ?: 0.0
          // 每秒把 mpv 当前值喂回 ViewModel（v179）：
          //  ① 换片时 `resetProbedDuration` 会清掉 `_preciseDuration`，而 StateFlow
          //     不会因为「值没变」重发 ⇒ 只能靠这条轮询把时长补回去；
          //  ② 顺带走一遍「离谱值」闸门（见 `PlayerViewModel.acceptMpvDuration`）。
          if (mpvDuration > 0.0) viewModel.acceptMpvDuration(mpvDuration)
          val effective = viewModel.effectiveDurationSeconds().toDouble()

          // ── 判据（三个，都只认事实）──
          //  · overflow：**有时长，但已播位置跑出去了** ⇒ 这个时长一定是错的。
          //    注意「8 秒 vs 播到 15 秒」这种值在起播头一两秒看是**自洽的**
          //    （位置 1s < 8s），所以**绝不能一自洽就收工**，必须一直盯着。
          //  · missing：**根本没有时长**，且已经播过起步宽限 ⇒ 该探了。
          //  · oversize：**时长比这份文件物理上能装下的还大一个量级** ⇒ 巨值（v179）。
          //    这条专治「放着放着变成几百小时」：overflow 在巨值面前永远不会触发
          //    （几小时的位置追不上几百小时），必须有这道独立的门。
          val overflow = effective > 0.0 && pos > effective + DURATION_WATCHDOG_TOLERANCE_SEC
          val missing = effective <= 0.0 && pos >= DURATION_PROBE_GRACE_SEC
          val oversize = effective > 0.0 && isDurationOversized(effective)
          if (!overflow && !missing && !oversize) continue
          if (attempts >= MAX_DURATION_PROBES) return@launch
          attempts++

          // 探到的值至少要大于已播位置，否则就是把同一个错再犯一次
          val floor = maxOf(pos, DURATION_PROBE_GRACE_SEC)
          // ⚠️ 只有 overflow / oversize 才「证伪」mpv：missing 时 mpv 只是**还没算出来**，
          // 它稍后报出的真值仍然更可信，不能提前把它钉死在探针值上。
          val kind =
            when {
              overflow -> "overflow"
              oversize -> "oversize"
              else -> "missing"
            }
          if (overflow || oversize) viewModel.markDurationUntrusted()
          val probed = probeDurationSeconds(floor)
          if (probed != null) {
            viewModel.applyProbedDuration(probed)
            dipLog(
              "duration-probe",
              "applied=${probed}s floor=$floor mpv=$mpvDuration pos=$pos kind=$kind",
            )
          } else {
            dipLog(
              "duration-probe",
              "failed floor=$floor mpv=$mpvDuration pos=$pos kind=$kind",
            )
          }
        }
      }
  }

  /**
   * 声称的时长是否**大得离谱**（v179）。
   *
   * 判据只有一条：`file-size × 8 ÷ (视频码率 + 音频码率)` 是这份文件**物理上**
   * 能装下的时长，声称值超过它的 [DURATION_OVERSIZE_FACTOR] 倍就不是估算误差能解释的。
   *
   * 为什么需要这条独立的门：mpv 的 `duration` 不是「报出来就定死」的属性。TS /
   * 直播录制这类容器没有内嵌索引，时长靠「seek 到尾部读最后一个 PTS」算，那次 seek
   * 一旦落在坏包上、或上游给的 `Content-Length` 是错的，它会重算出荒唐的值。
   * 而 overflow 判据是「位置超过时长」，在「时长几百小时」面前永远不成立 ⇒
   * 巨值一旦写进去就再也不会被推翻，进度条右端一直挂着几百小时。
   *
   * 拿不到 `file-size` 或码率时返回 false（不猜）：直播 / 长度未知的流本来就没有
   * 「能装下多少」这回事，交给 overflow 判据。
   */
  private fun isDurationOversized(claimedSeconds: Double): Boolean {
    val size = runCatching { PlayerLib.getPropertyDouble("file-size") ?: 0.0 }.getOrDefault(0.0)
    if (size <= 0.0) return false
    val bitrate =
      runCatching {
        (PlayerLib.getPropertyDouble("video-bitrate") ?: 0.0) +
          (PlayerLib.getPropertyDouble("audio-bitrate") ?: 0.0)
      }.getOrDefault(0.0)
    if (bitrate <= 0.0) return false
    val capacity = size * 8.0 / bitrate
    return capacity > 0.0 && claimedSeconds > capacity * DURATION_OVERSIZE_FACTOR
  }

  /**
   * 探一条「可信时长」（秒），要求结果大于 [floorSeconds]。
   *
   * 两条来源、按可信度排序，理由见 [startDurationWatchdog]。返回 null = 都没探到，
   * 调用方会隔一秒再来（多播几秒，码率就估得更准）。
   */
  private suspend fun probeDurationSeconds(floorSeconds: Double): Float? {
    // ① 与 GSY 同源：Android 原生解析
    val uri = getCurrentPlayingUri()
    if (uri != null) {
      val ms =
        withContext(Dispatchers.IO) {
          runCatching { getVideoDurationFromUri(uri) }.getOrDefault(0L)
        }
      val seconds = ms / 1000.0
      dipLog("duration-probe", "src=mmr value=${seconds}s floor=$floorSeconds uri=${redactUrlSecrets(uri.toString())}")
      if (seconds > floorSeconds) return seconds.toFloat()
    }
    // ② 用 mpv 自己报的码率反推（TS 没有索引时这是唯一还算靠谱的口径）
    val size = runCatching { PlayerLib.getPropertyDouble("file-size") ?: 0.0 }.getOrDefault(0.0)
    val bitrate =
      runCatching {
        (PlayerLib.getPropertyDouble("video-bitrate") ?: 0.0) +
          (PlayerLib.getPropertyDouble("audio-bitrate") ?: 0.0)
      }.getOrDefault(0.0)
    if (size > 0.0 && bitrate > 0.0) {
      val seconds = size * 8.0 / bitrate
      dipLog("duration-probe", "src=bitrate value=${seconds}s size=$size bitrate=$bitrate floor=$floorSeconds")
      if (seconds > floorSeconds) return seconds.toFloat()
    }
    return null
  }

  /** 诊断日志的短别名：只在设置里开「详细日志」时才落盘。 */
  private fun dipLog(
    tag: String,
    message: String,
  ) {
    runCatching { PlayerDiagLog.log(tag, message) }
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    loemby.propertyChanged(property, value, this)
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup.
        if (isFinishing || !mpvInitialized || player.isExiting) return

        val aspect = currentVideoAspect()
        Log.d(TAG, "video-params/aspect changed: $aspect")
        pipHelper.updatePictureInPictureParams()
        lockOrientationFromVideo()
      }
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   *
   * This method is called when an MPV property (with String value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    // Currently no String properties are handled
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(
    eventId: Int,
    data: MPVNode? = null,
  ) {
    // A late event from the stopped previous file must not clear the terminal ISO error.
    if (remoteIsoRejected || !mpvInitialized || player.isExiting) return
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
        val nativePath = PlayerLib.getPropertyString("path")
        val nativeUrl = loopback.playUrlForNativePath(nativePath)
        val nativeIndex = nativeUrl?.let { url -> playlist.indexOfFirst { it.toString() == url } }
          ?.takeIf { it >= 0 }
          ?: PlayerLib.getPropertyInt("playlist-pos")
        if (nativeIndex != null && nativeIndex in playlist.indices) {
          if (nativeIndex != playlistIndex) synchronizeNativePlaylistIndex(nativeIndex)
          val expectedUrl = playlist[nativeIndex].toString()
          playbackUrl = expectedUrl
          intent.data = Uri.parse(expectedUrl)
        }
        val itemId = playlistItemIds.getOrNull(nativeIndex ?: playlistIndex)
        if (itemId != orientationItemId) {
          orientationItemId = itemId
          resetAutomaticOrientationForFile()
        }
      }
    }
    if (data != null) loemby.event(eventId, data, this)
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        if (!loopback.ownsMediaUrl(playbackUrl.orEmpty(), PlayerLib.getPropertyString("path"))) return
        loemby.fileLoaded(this)
        handleFileLoaded()
        isReady = true
        // 文件刚起来、画面还什么都没有 ⇒ 重新显示加载层。
        // ⚠️ 必须在每次 loadfile 后复位：`isReady` 换片时不会清零，
        //    拿它当加载判据会导致「只有第一集有加载动画」。
        viewModel.onLoadingNewFile()

        // 换片：track-list 此刻已就绪 ⇒ 先判 DV Profile 5（硬解颜色修正），
        // 同时把上一部 HDR 片的 Window 色彩模式复位（video-params 还没出来，
        // 这次判定必然是 SDR）。等首帧后再判一次就会把 HDR 片切回去。
        PlaybackColorPolicy.apply(this, decoderPreferences)
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
        // 首帧已经上屏 ⇒ 撤掉「出画面前」的加载层。
        // 判据为什么用这个事件而不是 vo-configured，见 PlayerViewModel.firstFrameRendered 的注释。
        viewModel.onFirstFrameRendered()
        watchSteadyState()

        // 首帧出来后 video-params（gamma / sig-peak）才可读 ⇒ 这里才是
        // 「要不要让系统切进 HDR 显示模式」的正式判定时机。
        PlaybackColorPolicy.apply(this, decoderPreferences)
      }

      MPVLib.MpvEvent.MPV_EVENT_END_FILE -> handleEndFileWithError(data)
    }
  }

  /**
   * mpv 放弃了这个文件（`end-file` 且原因是出错）。
   *
   * ## 为什么需要它
   *
   * 损坏的本地视频有一个非常尴尬的中间态：容器头是好的（mpv 认得出格式、也愿意打开），
   * 但解复用读到坏包就再也出不来画面，于是 mpv 发一条 `end-file(reason=error)` 收摊。
   * 我们原来对这个事件**什么都不做** ⇒ 界面上既没有画面也没有提示，加载层一圈一圈
   * 转到天荒地老，用户只能以为 App 卡死了。
   *
   * 现在把它翻成那张 [PlaybackErrorCard]：告诉用户「文件坏了」，并给出「重试 / 返回」。
   *
   * ## 为什么两道闸
   *
   *  ① **只认 `reason=error`**：`eof`（正常播完，走 `eof-reached`）、`stop` / `quit`
   *     （退出播放页）、`redirect`（换片）都不是失败，误报会天天弹卡片。
   *  ② **只在这一条一帧都没出过时才弹**：已经出过画面的文件半路挂掉属于「播放中断」，
   *     那是另一个问题；这里只负责「打不开」。
   *
   * ⚠️ `reason` 的节点形态由 libmpv 的 JNI 层拼出来，字符串 / 整数两种都见过 ⇒ 两种都认，
   *    认不出来就当没发生（宁可少弹一张卡，也不能把正常播放判成失败）。
   */
  private fun handleEndFileWithError(data: MPVNode?) {
    if (isFinishing || isDestroyed) return

    val reasonNode = runCatching { data?.asMap()?.get("reason") }.getOrNull()
    val reasonText = runCatching { reasonNode?.asString() }.getOrNull()
    val reasonInt = runCatching { reasonNode?.asInt() }.getOrNull()
    // mpv_end_file_reason: EOF=0, STOP=2, QUIT=3, ERROR=4, REDIRECT=5
    val failed = reasonText?.contains("error", ignoreCase = true) == true || reasonInt == 4L
    // 认不出形态时留一条诊断，下次有用户报「文件坏了没提示」能直接对上
    dipLog(
      "end-file",
      "reason=${reasonText ?: reasonInt} failed=$failed " +
        "firstFrame=${viewModel.firstFrameRendered.value} node=${runCatching { data?.toJson() }.getOrNull()}",
    )
    if (!failed) return
    if (viewModel.firstFrameRendered.value) return

    viewModel.showPlaybackError(
      "这个文件的内容可能已经损坏：播放内核连一帧画面都没能解出来。\n" +
        "可以重试一次，或者换一份文件。",
    )
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    // 文件真的起来了 ⇒ 之前那次「无法播放」的判定作废（可能是地址临时过期，
    // 重试后拿到了新的直链）。不清的话错误卡片会一直压在画面上。
    viewModel.clearPlaybackError()

    // ── 诊断埋点（v162）：「这次打开到底拿到了什么」──
    // `.mts` / MPEG-TS 这类容器**没有内嵌索引**，时长完全取决于「能不能 seek 到尾部
    // 读最后一个 PTS」。若这一行里 `format=mpegts` 而 `duration` 明显偏小、
    // `seekable=false`，那就说明是取流层没能随机读取（代理回的长度不对 / 上游不支持
    // Range），而不是进度条算错了 —— 一眼就能分清该修哪边。
    // 只在设置里开「详细日志」时落盘（关着时 PlayerDiagLog 直接短路）。
    PlayerDiagLog.log(
      "file-loaded",
      "format=${PlayerLib.getPropertyString("file-format")} " +
        "duration=${PlayerLib.getPropertyDouble("duration")} " +
        "size=${PlayerLib.getPropertyDouble("file-size")} " +
        "seekable=${PlayerLib.getPropertyBoolean("seekable")}",
    )

    // 时长看守（v163 起，v164 扩到「报小了」）：mpv 的 duration 不可信时用原生解析顶上。
    // ⚠️ 必须在**每次** file-loaded 都调：换片要清掉上一部片的兜底值。
    startDurationWatchdog()

    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    // Start media notification service (like YouTube - always show notification)
    // 服务经由 PlayerLib 门面驱动。
    startBackgroundPlayback()

    MpvNetSpeedSampler.reset()
    val diskCacheLimitMb = playerPreferences.diskCacheSizeMb.get()
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching { MpvDiskCache.trimIfNeeded(this@PlayerActivity, diskCacheLimitMb) }
    }
    setIntentExtras(intent.extras)
    // Initial playback and native queue selection converge here; automatic matching is idempotent per load.
    notifyDanmakuEpisodeChanged(playlistIndex)

    // mpv 已经换成新文件：轨道列表重建了，外挂字幕跟踪表必须跟着清。
    // 不清的话恢复流程会以为「这条字幕已经加过了」而跳过，字幕就再也加不回来。
    viewModel.resetExternalSubtitles()

    // 先落媒体标题、再进下面的恢复流程 —— 顺序很关键：
    // 1) setMediaTitle 会 clear() 外挂字幕列表。若它排在外挂字幕恢复**之后**，刚恢复的记录
    //    会被抹掉 → 退出时保存成空 → 下次打开无从恢复。这正是「外挂字幕记不住」的根因之一。
    // setMediaTitle also resets the retained session-only pinch zoom before state restoration.
    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    if (!isCurrentStreamM3U()) {
      PlayerLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state. 这几步的**顺序**是外挂字幕能记住的关键：
      // 1) loadVideoPlaybackState 里会把存档中的外挂字幕加回 mpv，并等轨道真正挂上；
      // 2) 「同名外挂字幕自动加载」与 TrackSelector 并行跑（网络目录列举可能很慢，
      //    不能挡住音轨偏好等其他恢复步骤）；有存档时不抢选择（autoSelectFirst = false）；
      // 3) 等自动加载也挂完，最后才把存档里的字幕选择套一次 —— 此时所有字幕轨都已就位。
      // Load playback state (will skip track restoration if preferred language configured)
      loadVideoPlaybackState(fileName)
      withContext(Dispatchers.Main) { applyRememberedPlaybackSettings() }

      // 起一个盯梢协程：片尾倒数阶段把「下一集」卡片推出来
      autoplayCancelled = false
      // 新的一集 ⇒ 用户上一集叉掉「下一集」卡片的决定作废，重新给一次提示机会
      nextUpDismissed = false
      withContext(Dispatchers.Main) { startAutoplayCountdownWatcher() }

      // 起片头片尾盯梢（b2+a1）：拉精准分段 + 自动跳 / 手动胶囊
      withContext(Dispatchers.Main) { startSkipSegmentWatcher() }

    }

    // The orientation state is owned by the video geometry/manual rotate action.
    setOrientation()

    applySubtitlePreferences()

    // 黑边自动裁切：开关打开则探测并裁掉四周黑边；关掉的路径也要清一次 ——
    // 裁切是挂在 mpv 的 vf 链上的，不会随换片自动消失，不清就会带着上一条的黑边偏移播。
    viewModel.applyAutoCropPolicy()

    viewModel.unpause()

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)

    // Capability URLs are intentionally local; their metadata is already known.
    if (!loopback.isLocalPlayUrl(playbackUrl.orEmpty())) fetchNetworkStreamTitle()
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the player UI, media session, and background notification when a better title is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) return@launch

        // Let MPV provide titles for playlists, and avoid needless requests for local capability URLs.
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") return@launch

        val url = uri.toString()
        if (EmbyMediaSourcePolicy.isRemoteIsoUrl(url)) return@launch
        val betterFilename = HttpUtils.extractFilenameFromUrl(
          url,
          server = currentPlaybackServer(),
          headers = httpHeadersForUrl(url),
        )
        if (betterFilename.isNullOrBlank() ||
          betterFilename == fileName ||
          betterFilename == uri.host ||
          betterFilename == "Network Stream"
        ) {
          return@launch
        }

        fileName = betterFilename
        withContext(Dispatchers.Main) {
          PlayerLib.setPropertyString("force-media-title", fileName)
          viewModel.setMediaTitle(fileName)
          val durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
          updateMediaSessionMetadata(title = fileName, durationMs = durationMs)
        }

        if (serviceBound && mediaPlaybackService != null) {
          val artist = runCatching { PlayerLib.getPropertyString("metadata/artist") }.getOrNull().orEmpty()
          val thumbnail = withContext(Dispatchers.IO) {
            runCatching { PlayerLib.grabThumbnail(NOTIFICATION_THUMBNAIL_SIZE) }.getOrNull()
          }
          withContext(Dispatchers.Main) {
            if (serviceBound) {
              mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    // Typography settings
    PlayerLib.setPropertyString("sub-font", subtitlesPreferences.font.get())
    PlayerLib.setPropertyString("secondary-sub-font", subtitlesPreferences.font.get())
    PlayerLib.setPropertyInt("sub-font-size", subtitlesPreferences.fontSize.get())
    PlayerLib.setPropertyBoolean("sub-bold", subtitlesPreferences.bold.get())
    PlayerLib.setPropertyBoolean("sub-italic", subtitlesPreferences.italic.get())
    PlayerLib.setPropertyString("sub-justify", subtitlesPreferences.justification.get().value)
    PlayerLib.setPropertyString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    PlayerLib.setPropertyInt("sub-outline-size", subtitlesPreferences.borderSize.get())
    PlayerLib.setPropertyInt("sub-shadow-offset", subtitlesPreferences.shadowOffset.get())

    // Color settings
    PlayerLib.setPropertyString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    PlayerLib.setPropertyString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    PlayerLib.setPropertyString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())

    // Miscellaneous settings
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    PlayerLib.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
    PlayerLib.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")

    // ── 字号基准：跟随画面 ──
    // mpv 的 sub-font-size 单位是「窗口高 720 时的缩放像素」，实际大小随**窗口高度**走。
    // 只设 sub-scale-by-window 时 mpv 默认还开着 sub-scale-with-window，于是
    // sd_ass.c 里 `set_font_scale *= dim->h / 画面在窗口内的高度`：竖屏窗口高、画面只占
    // 中间一条 → 字号被放大 3~4 倍；横屏画面铺满 → 约 1 倍。这正是
    // 「横屏调好竖屏太大、竖屏调好横屏太小」的根因。
    // 关掉 sub-scale-with-window 后字号改为**按画面高度**等比（mpv 手册原话：
    // "To make the font size scale with video size instead, set only
    //  --sub-scale-with-window to no"），横竖屏占画面的比例由此一致。
    val followPicture = subtitlesPreferences.followPictureScale.get()
    val withWindow = if (followPicture) "no" else "yes"
    PlayerLib.setPropertyString("sub-scale-with-window", withWindow)
    PlayerLib.setPropertyString("secondary-sub-scale-with-window", withWindow)
    // sub-scale-by-window 保持 mpv 默认 yes：它只管「窗口变化时要不要重算」，不影响基准。
    PlayerLib.setPropertyString("sub-scale-by-window", "yes")
    PlayerLib.setPropertyString("secondary-sub-scale-by-window", "yes")

    // ── 位置基准：贴画面底部 ──
    // 关掉 sub-use-margins 后字幕不再往上下黑边里跑，横竖屏都紧贴画面下沿。
    // 配合上面的「跟随画面」，字号与位置一起随画面走，方向一变不会互相打架。
    PlayerLib.setPropertyString("sub-use-margins", "no")
    PlayerLib.setPropertyString("secondary-sub-use-margins", "no")

    PlayerLib.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
    PlayerLib.setPropertyInt("sub-pos", subtitlesPreferences.subPos.get())

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  @OptIn(ExperimentalStdlibApi::class)
  private fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

  /**
   * Saves the current playback state to the database.
   *
   * @param mediaTitle The title of the media being played
   * @param isSync If true, executes synchronously on the calling thread with runBlocking.
   *               Essential during onPause when finishing so the state is written before MainActivity resumes.
   */
  private fun saveVideoPlaybackState(mediaTitle: String, isSync: Boolean = false) {
    if (mediaIdentifier.isBlank()) return

    // Cancel any previous pending save operation
    savePlaybackStateJob?.cancel()

    // Read values from MPV immediately while it is still loaded (before stop/destroy or coroutine delay)
    val currentPos = (PlayerLib.getPropertyInt("time-pos") ?: viewModel.pos ?: 0).coerceAtLeast(0)
    // ⚠️ 三档回落，别只读 mpv：`.mts` 这类容器在取流层不可随机读时 mpv 的 `duration`
    // 恒为 0（见 startDurationWatchdog），只读它会把「已看/剩余时间」整条链路打成 0。
    val currentDuration =
      (
        PlayerLib.getPropertyInt("duration")?.takeIf { it > 0 }
          ?: viewModel.effectiveDurationSeconds().toInt().takeIf { it > 0 }
          ?: (viewModel.duration ?: 0)
        ).coerceAtLeast(0)
    val speed = PlayerLib.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED
    val currentSid = PlayerLib.getPropertyInt("sid") ?: -1
    val currentSecondarySid = PlayerLib.getPropertyInt("secondary-sid") ?: -1
    val (effectiveSid, effectiveSecondarySid) = if (currentSid <= 0 && currentSecondarySid > 0) {
      currentSecondarySid to -1
    } else {
      currentSid to currentSecondarySid
    }
    val subDelay = ((PlayerLib.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val subSpeed = PlayerLib.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED
    val aid = PlayerLib.getPropertyInt("aid") ?: -1
    val audioDelay = ((PlayerLib.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val externalSubs = viewModel.externalSubtitles.joinToString("|")
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val saveOnQuit = playerPreferences.savePositionOnQuit.get()
    // 多版本媒体：这一条属于哪个「版本」？键由 (服务器, 条目, 媒体源) 拼出，详情页用同一个键读数。
    // ⚠️ 必须在**主线程、mpv 还活着**的时候读（要读 mpv 当前路径），与上面那批属性一起取。
    // 只有「记住播放位置」开着才写：按版本进度是本地续播的一部分，开关关掉就该整体不生效。
    val embyVersionKey = if (saveOnQuit) currentEmbyVersionProgressKey() else null

    val saveBlock: suspend () -> Unit = {
      runCatching {
        val oldState = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
        Log.d(TAG, "Saving playback state for: $mediaTitle (identifier: $mediaIdentifier)")

        val duration = if (currentDuration > 0) {
          currentDuration
        } else {
          (oldState?.lastPosition ?: 0) + (oldState?.timeRemaining ?: 0)
        }

        val lastPosition = if (!saveOnQuit) {
          oldState?.lastPosition ?: 0
        } else if (duration > 0 && currentPos >= duration - 1) {
          0
        } else {
          currentPos
        }

        val timeRemaining = if (duration > lastPosition) duration - lastPosition else 0

        val hasBeenWatched = run {
          val durationSeconds = duration.toFloat()
          val isFinished = (durationSeconds > 0) && (currentPos >= durationSeconds - 1)
          val progress = if (durationSeconds > 0) currentPos.toFloat() / durationSeconds else 0f
          val isCurrentlyWatched = progress >= (watchedThreshold / 100f)
          val oldProgress = if (durationSeconds > 0) lastPosition.toFloat() / durationSeconds else 0f
          val wasWatchedThisSession = oldProgress >= (watchedThreshold / 100f)

          isCurrentlyWatched || isFinished || wasWatchedThisSession || (oldState?.hasBeenWatched == true)
        }

        val entity =
          PlaybackStateEntity(
            mediaTitle = mediaIdentifier,
            lastPosition = lastPosition,
            playbackSpeed = speed,
            sid = effectiveSid,
            secondarySid = effectiveSecondarySid,
            subDelay = subDelay,
            subSpeed = subSpeed,
            aid = aid,
            audioDelay = audioDelay,
            timeRemaining = timeRemaining,
            externalSubtitles = externalSubs,
            hasBeenWatched = hasBeenWatched,
          )
        playbackStateRepository.upsert(entity)
        // 多版本媒体：再按「版本」记一份。
        // Emby 服务端的进度是按**条目**存的 —— 4K 与 1080P 共用一个续播点，服务端层面无解。
        // 所以在本地多记一条（键见 EmbyVersionProgress），详情页就能让每个版本各算各的进度。
        embyVersionKey?.let { playbackStateRepository.upsert(entity.copy(mediaTitle = it)) }
        Log.d(TAG, "Playback state saved successfully: pos=$lastPosition, duration=$duration, remaining=$timeRemaining, watched=$hasBeenWatched")
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state", e)
      }
    }

    if (isSync) {
      // 退出路径（`isFinishing` / `isUserFinishing`）原先是
      // `runBlocking(Dispatchers.IO) { saveBlock() }` —— 看着「切了 IO 就不卡主线程」，
      // 其实**完全误解了**：`runBlocking` 阻塞的是**调用线程**，
      // 写成 `Dispatchers.IO` 只是让 `saveBlock` 在 IO 线程执行，
      // 主线程照样原地等到磁盘写完。按返回键退出时每次都卡一下，
      // 数据库慢一点（首次建表 / WAL fsync / 磁盘忙）就是 ANR。
      // 关键在于**该保住的东西已经保住了**：上面 `currentPos` / `currentDuration` /
      // `speed` / `sid` … 全都在**主线程同步从 mpv 读完了**（mpv 属性读取是纳秒级的
      // 内存读，不涉及 IO），`saveBlock` 内部只剩 `getVideoDataByTitle` + `upsert`
      // 两个 Room 调用。所以改成异步**不会丢任何数据** ——
      // 进度值已经以本地变量的形式被捕获进闭包了。
      // 那 `isSync` 这个参数还需要吗？需要，但要重新定义它：
      // 它的意义从「同步写库」变成「**持有这个 Job 引用**」——
      // 退出时把 job 记进 [savePlaybackStateJob]，onDestroy 里好去 join/取消。
      savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO) {
        saveBlock()
      }
    } else {
      savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO) {
        saveBlock()
      }
    }
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(mediaTitle: String): Boolean {
    // 「从头播放」是一次性标记：这里读出后立刻清掉，避免影响后面的剧集。
    // 「整场从头播放」（随机播放入口）是会话级标记：整个会话都不清，切到哪个视频都从头放。
    val playFromStart = playFromStartOnce || playFromStartAllSession
    playFromStartOnce = false

    // 先清掉上一集留下的「待恢复字幕选择」，避免 mediaIdentifier 为空直接 return 时
    // 把上一集的 sid 误套到本集上（会连累 applyRestoredSubtitleSelection）。
    restoredSubtitleSid = NO_SAVED_SUBTITLE_SELECTION
    restoredSecondarySubtitleSid = NO_SAVED_SUBTITLE_SELECTION

    if (mediaIdentifier.isBlank()) return false

    return runCatching {
      val state = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)

      applyPlaybackState(state, playFromStart)
      applyDefaultSettings(state)

      state != null
    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * 本次加载从存档里读到的字幕选择（主 / 副）。哨兵值见 [NO_SAVED_SUBTITLE_SELECTION]。
   * 先记下来、等所有字幕来源都挂上之后再统一套 —— 见 [applyRestoredSubtitleSelection]。
   */
  private var restoredSubtitleSid: Int = NO_SAVED_SUBTITLE_SELECTION

  private var restoredSecondarySubtitleSid: Int = NO_SAVED_SUBTITLE_SELECTION

  /**
   * 把存档里的字幕选择**最后一次**确定地套到播放器上。
   *
   * 必须等「存档外挂字幕 + 同名自动加载 + 下载目录扫描」都做完之后再调：这些来源都是
   * 异步挂轨的，谁先挂上不确定，而「上次选的是哪条」只能以存档为准 ——
   *   · 早一步设 `sid`，轨道可能还不存在，mpv 会把这次选择丢掉（外挂字幕就是这样丢的）；
   *   · 晚一步又会被自动加载的 `select` 抢走。
   * 所以统一收口在这里，一次套准。三种情况：选中某条 / 只选了副字幕 / 上次就没字幕。
   */
  private fun applyRestoredSubtitleSelection() {
    // 外部应用经 intent 显式指定要启用的字幕（subs.enable）优先级最高 —— 那是用户在
    // 文件管理器里点「用影屿播放 + 带上这条字幕」，不该被存档里的旧选择盖掉。
    if (intent.hasExtra("subs.enable")) return

    val sid = restoredSubtitleSid
    if (sid == NO_SAVED_SUBTITLE_SELECTION) return
    val secondarySid = restoredSecondarySubtitleSid

    when {
      sid > 0 -> {
        if (player.sid != sid) player.sid = sid
        if (secondarySid > 0 && secondarySid != sid) {
          if (player.secondarySid != secondarySid) player.secondarySid = secondarySid
        } else if (player.secondarySid > 0) {
          player.secondarySid = -1
        }
        Log.d(TAG, "Restored subtitle selection: sid=$sid, secondary=$secondarySid")
      }

      secondarySid > 0 -> {
        // 只有副字幕：提到主字幕（单条字幕必须贴底显示）
        player.secondarySid = -1
        player.sid = secondarySid
        Log.d(TAG, "Promoted saved secondary subtitle track $secondarySid to primary (single subtitle must stay at bottom)")
      }

      else -> {
        // 上次没有选中任何字幕：保持关闭 —— 否则「同名自动加载」或文件自带的默认轨会把它
        // 重新打开，用户会以为「我把字幕关了」这个选择没被记住。
        if (player.sid > 0) {
          player.sid = -1
          Log.d(TAG, "Restored 'subtitles off' state from saved state")
        }
        if (player.secondarySid > 0) player.secondarySid = -1
      }
    }
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio track selection, and playback speed.
   * 字幕的**选中**不在这里做（见 [applyRestoredSubtitleSelection]），本函数只负责把
   * 存档里的外挂字幕加回 mpv 并等它们真正挂上。
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   * @param playFromStart true 表示用户显式点了「从头播放」，本地续播位置一律不恢复
   */
  private suspend fun applyPlaybackState(
    state: PlaybackStateEntity?,
    playFromStart: Boolean = false,
  ) {
    if (state == null) {
      // 没有存档：字幕交给 TrackSelector 按偏好决定，这里不记任何「待恢复的选择」
      restoredSubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      restoredSecondarySubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      return
    }

    // The local play route owns resume and track selection; retain only Android visual/audio adjustments.
    if (loopback.isLocalPlayUrl(playbackUrl.orEmpty())) {
      restoredSubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      restoredSecondarySubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      PlayerLib.setPropertyDouble("sub-delay", state.subDelay / DELAY_DIVISOR)
      PlayerLib.setPropertyDouble("secondary-sub-delay", state.subDelay / DELAY_DIVISOR)
      PlayerLib.setPropertyDouble("audio-delay", state.audioDelay / DELAY_DIVISOR)
      PlayerLib.setPropertyDouble("speed", state.playbackSpeed)
      PlayerLib.setPropertyDouble("sub-speed", state.subSpeed)
      PlayerLib.setPropertyDouble("secondary-sub-speed", state.subSpeed)
      return
    }

    // 先把存档里的字幕选择记下来，等所有字幕来源（存档外挂字幕 / 同名自动加载 /
    // 下载目录扫描）都挂上之后，再由 applyRestoredSubtitleSelection() 统一定夺。
    restoredSubtitleSid = state.sid
    restoredSecondarySubtitleSid = state.secondarySid

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first.
    // ⚠️ 这里必须**等轨道真正挂进 track-list** 再往下走：`sub-add` 是异步命令，
    // 命令返回时轨道还没建出来，紧接着设 `sid` 会被 mpv 当成「不存在的轨道」直接丢弃
    // —— 外挂字幕「重开 app 就没了」正是这个顺序造成的。
    // （内嵌字幕轨在文件加载时就已经存在，所以只有外挂字幕会中招。）
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      // 已经被跟踪的（例如刚被下载目录扫描加过）不会再下发 sub-add，等轨道数时要按
      // 实际会新增的条数算，否则会白等一个超时。
      val pendingCount = externalSubUris.count { !viewModel.externalSubtitles.contains(it) }
      val tracksBefore = PlayerLib.getPropertyInt("track-list/count") ?: 0

      for (subUri in externalSubUris) {
        // 用可挂起的版本：加完再往下，不再即发即忘
        viewModel.loadSubtitle(Uri.parse(subUri), select = false, silent = true)
      }

      if (pendingCount > 0) {
        viewModel.awaitTrackCountAtLeast(tracksBefore + pendingCount)
      }
    }

    // 字幕轨的选中**不在这里做**：此刻「同名外挂字幕自动加载」可能还没挂上，
    // 统一留到 applyRestoredSubtitleSelection()（排在自动加载之后）一次套准。
    // 音轨不依赖异步添加，照旧在这里恢复。
    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    PlayerLib.setPropertyDouble("sub-delay", subDelay)
    PlayerLib.setPropertyDouble("secondary-sub-delay", subDelay)
    PlayerLib.setPropertyDouble("speed", state.playbackSpeed)
    PlayerLib.setPropertyDouble("audio-delay", audioDelay)
    PlayerLib.setPropertyDouble("sub-speed", state.subSpeed)
    PlayerLib.setPropertyDouble("secondary-sub-speed", state.subSpeed)

    when {
      // 显式「从头播放」：本地续播记录一律不生效（详情页的「从头播放」按钮）
      playFromStart -> {
        PlayerLib.setPropertyInt("time-pos", 0)
        Log.d(TAG, "play_from_start: ignore saved position (${state.lastPosition}s), seek to 0")
      }

      playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0 -> {
        val saved = state.lastPosition.toDouble()
        val duration = PlayerLib.getPropertyDouble("duration")
        // 续播位置离片尾太近（例如上一次是"播完/假播完"退出的，位置就停在 duration 上）：
        // 直接从头放。否则一开局就被 seek 到结束点，IJK 立刻回调播放完成，
        // 上层把它当成「播放结束」自动退出 —— 表现就是"一进播放器就被踢回详情页"。
        val tooCloseToEnd = duration != null && duration > 0.0 && duration - saved < RESUME_TAIL_GUARD_SEC
        if (tooCloseToEnd) {
          Log.w(
            TAG,
            "saved position ${saved}s is within $RESUME_TAIL_GUARD_SEC s of the end ($duration s); restart from 0",
          )
          PlayerLib.setPropertyInt("time-pos", 0)
        } else {
          PlayerLib.setPropertyInt("time-pos", state.lastPosition)
        }
      }
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubDelay = subtitlesPreferences.defaultSubDelay.get() / 1000.0
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      PlayerLib.setPropertyDouble("sub-delay", defaultSubDelay)
      PlayerLib.setPropertyDouble("secondary-sub-delay", defaultSubDelay)
      PlayerLib.setPropertyDouble("sub-speed", defaultSubSpeed)
      PlayerLib.setPropertyDouble("secondary-sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private fun enterPipUIMode() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    binding.controls.alpha = 0f

    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /** Safe before mpv initialization: retain the physical orientation without enabling sensors. */
  private fun setOrientation() {
    applyMpvOrientation(
      when (val state = orientationState) {
        is PlayerOrientationLock.Automatic -> state.orientation
        is PlayerOrientationLock.Manual -> state.orientation
        PlayerOrientationLock.WaitingForVideo -> AppOrientationPolicy.MPV
      },
    )
  }

  private fun resetAutomaticOrientationForFile() {
    if (orientationState !is PlayerOrientationLock.Manual) {
      orientationState = PlayerOrientationLock.WaitingForVideo
      applyMpvOrientation(AppOrientationPolicy.MPV)
    }
  }

  private fun lockOrientationFromVideo() {
    if (orientationState !is PlayerOrientationLock.WaitingForVideo || isInPictureInPictureMode) return
    val orientation = AppOrientationPolicy.orientationForVideo(currentVideoAspect()) ?: return
    orientationState = PlayerOrientationLock.Automatic(orientation)
    applyMpvOrientation(orientation)
  }

  override fun rotatePlayerScreen() {
    val current = when (val state = orientationState) {
      is PlayerOrientationLock.Automatic -> state.orientation
      is PlayerOrientationLock.Manual -> state.orientation
      PlayerOrientationLock.WaitingForVideo -> when (resources.configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      }
    }
    val rotated = AppOrientationPolicy.rotatedOrientation(current)
    orientationState = PlayerOrientationLock.Manual(rotated)
    applyMpvOrientation(rotated)
  }

  private fun applyMpvOrientation(orientation: Int) {
    super.setRequestedOrientation(AppOrientationPolicy.constrainMpvRequest(orientation))
  }

  override fun setRequestedOrientation(requestedOrientation: Int) {
    applyMpvOrientation(requestedOrientation)
  }

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
        // 偏好见 设置 → 手势 → 媒体键（mediaPlayGesture）
        viewModel.handleMediaPlayPause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_NEXT -> {
        viewModel.handleMediaNext()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
        viewModel.handleMediaPrevious()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  /** 建立系统媒体会话。真正的实现在 [PlayerMediaSession]。 */
  private fun setupMediaSession() {
    mediaSession.create()
  }

  /** 上报播放/暂停状态给系统。 */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    mediaSession.updatePlaybackState(isPlaying)
  }

  /** 上报标题与时长给系统（锁屏 / 通知栏显示）。 */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    mediaSession.updateMetadata(title, durationMs)
  }

  /** 释放系统媒体会话。 */
  private fun releaseMediaSession() {
    mediaSession.release()
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        serviceBound = true
        Log.d(TAG, "Service connected")
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return
    }

    // Prevent starting service multiple times
    if (serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
// Get media info before starting service
    val artist = runCatching { PlayerLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""

    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }

    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
    }

    // 缩略图异步抓（清单 #20 必修 4）。
    // 原来这里是主线程同步 `grabThumbnail(1080)`：一次约 2.6MB，
    // 而且 `MPVLib.grabThumbnail` 内部会把当前帧从解码器拉出来做像素转换、跨 JNI 传回，
    // **同步阻塞调用方** —— 也就是说在 UI 线程上等一次硬解 + 一次大块内存分配。
    // 现在改成：先把服务起起来（通知先出来，封面可以晚几百毫秒），
    // 抓图丢后台，回来再挂上。
    // 用 `setStaticThumbnail` 而不是 `mediaPlaybackService?.setMediaInfo(...)`：
    // 此刻可能还没绑定上（`bindService` 是异步回调），拿不到实例。
    // 那个方法里判了 `!==`，所以「重新起服务时传的同一张」不会被误 recycle。
    lifecycleScope.launch {
      val thumbnail = withContext(Dispatchers.IO) {
     runCatching { PlayerLib.grabThumbnail(NOTIFICATION_THUMBNAIL_SIZE) }.getOrNull()
   }
      if (isFinishing || isDestroyed) return@launch
      MediaPlaybackService.setStaticThumbnail(thumbnail)
      // 服务这会儿大概率已经绑上了，让它把新封面刷进通知里
      if (serviceBound) {
        mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
      }
    }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /** API-neutral entry for Compose; the Activity itself already requires P for playback setup. */
  fun requestBackgroundPlayback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) triggerBackgroundPlayback()
  }

  /**
   * Manually triggers background playback when the user clicks the background playback button.
   * This works independently of the automaticBackgroundPlayback preference.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  fun triggerBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot trigger background playback: video not ready")
      return
    }

    Log.d(TAG, "User triggered background playback")
    
    // Set flag to enable background playback (same logic as automatic)
    isManualBackgroundPlayback = true
    
    // Restore system UI before going to background
    restoreSystemUI()
    
    // Move to background by going to home screen (same behavior as automatic)
    val intent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = AppOrientationPolicy.constrainMpvRequest(value)
    }

  // ==================== Playlist Management ====================

  fun hasNext(): Boolean = playlist.isNotEmpty() && playlistIndex < playlist.lastIndex

  fun hasPrevious(): Boolean = playlist.isNotEmpty() && playlistIndex > 0

  internal fun hasPlaylistItems(): Boolean = playlist.isNotEmpty()

  internal fun queueSizeForControls(): Int = playlist.size

  fun playNext() {
    if (hasNext()) loadPlaylistItem(playlistIndex + 1)
  }

  fun playPrevious() {
    if (hasPrevious()) loadPlaylistItem(playlistIndex - 1)
  }

  internal fun playPlaylistIndex(index: Int) {
    if (index == playlistIndex || index !in playlist.indices) return
    loadPlaylistItem(index)
  }

  internal fun playlistUrls(): List<String> = playlist.map(Uri::toString)

  internal fun playlistTitleAt(index: Int): String? = getPlaylistTitleAt(index)

  internal fun playlistItemIds(): List<String> = playlistItemIds.toList()

  internal fun currentPlaybackServerForUi(): EmbyServer? = runCatching { currentPlaybackServer() }.getOrNull()

  private fun synchronizeNativePlaylistIndex(index: Int) {
    val uri = playlist.getOrNull(index) ?: return
    playlistIndex = index
    intent.data = uri
    intent.putExtra(PlayerHandoff.EXTRA_INDEX, index)
    playbackUrl = uri.toString()
    fileName = getPlaylistTitleAt(index) ?: getFileNameFromUri(uri)
    mediaIdentifier = getMediaIdentifierFromUri(uri, fileName)
    refreshSeriesKey()
    notifyDanmakuEpisodeChanged(index)
    viewModel.onLoadingNewFile()
    PlayerLib.setPropertyString("force-media-title", fileName)
    viewModel.setMediaTitle(fileName)
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    val uri = playlist[index]
    if (!EmbyPlaybackInput.isPlayableUrl(uri.toString()) || playlistItemIds.getOrNull(index).isNullOrBlank()) {
      viewModel.showPlaybackError("仅支持 Emby HTTP(S) 媒体播放")
      return
    }
    if (rejectRemoteIso(uri.toString())) return

    // 诊断：切集阶段落盘。
    // 「切下一集偶发闪退」这类问题在设备上是查不出堆栈的 —— 手机上的 logcat 应用
    // 非 root 读不到别的应用的日志（见 PlayerDiagLog 注释）。所以关键阶段各记一笔，
    // 崩溃后看最后一条就知道停在「发 loadfile 之前 / 之后 / 首帧之前」哪一段。
    PlayerDiagLog.log(
      "switch",
      "load index=$index/${playlist.size} prev='$fileName' uri=${playlist[index]}",
    )

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
    }

    val playableUri = uri.toString()
    remoteIsoRejected = false
    viewModel.clearPlaybackError()

    // Update playlist index before deriving the next item's metadata and memory key.
    playlistIndex = index
    intent.data = uri
    intent.putExtra(PlayerHandoff.EXTRA_INDEX, index)
    refreshSeriesKey()

    // Extract and set the new file name
    // 优先用发起方给的标题（Emby 等网络流 URL 末段是 "stream"，直译会得到假标题）
    fileName = getPlaylistTitleAt(index) ?: getFileNameFromUri(uri)
    // Generate new media identifier for playback state
    mediaIdentifier = getMediaIdentifierFromUri(uri, fileName)

    // 弹幕：换集 = 重新匹配。内部会先取消上一次加载，
    // 否则上一集的慢响应会回来覆盖这一集（竞态）。
    // ⚠️ 第三个参数是「**集号**」，不是队列下标 —— 见 [currentEpisodeIndex]。
    notifyDanmakuEpisodeChanged(index)

    // Set HTTP headers (including referer) for network streams
    if (!setHttpHeadersForUri(uri)) return

    // Select the already registered native entry. Bind the coordinator first so
    // the old entry's END_FILE(stop) cannot trigger a second queue advance.
    viewModel.onLoadingNewFile()
    playNativePlaylistItem(index, playableUri)

    // Update media title (this will trigger UI update)
    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    val isM3U = uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u")
    if (!isM3U) {
      PlayerLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
    }
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Used as a fallback when MPV hasn't set the media-title property yet.
   * For m3u/m3u8 streams, returns the raw media-title from MPV instead of parsing.
   */
  fun getTitleForControls(): String {
    // For m3u/m3u8 streams, use MPV's raw media-title directly.
    // mpv 在拿不到片名时会回传**整条播放直链**（Emby 的链里带 api_key），
    // 而这里的结果会被顶栏 / 交接给另一个内核的标题用到 —— 全部先打码。
    // 打码只发生在展示层，真正的播放地址不受影响。
    if (isCurrentStreamM3U()) {
      val rawTitle = PlayerLib.getPropertyString("media-title")
      if (!rawTitle.isNullOrBlank()) {
        return redactUrlSecrets(rawTitle).orEmpty()
      }
    }
    return fileName
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private fun getVideoDurationFromUri(uri: Uri): Long {
    if (loopback.isLocalPlayUrl(uri.toString())) {
      return loembyPlaybackState.value?.resolved?.source?.RunTimeTicks?.div(10_000L) ?: 0L
    }
    if (!EmbyPlaybackInput.isPlayableUrl(uri.toString())) return 0L
    return runCatching {
      val retriever = android.media.MediaMetadataRetriever()
      try {
        retriever.setDataSource(uri.toString(), httpHeadersForUrl(uri.toString()))
        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      } finally {
        runCatching { retriever.release() }
      }
    }.getOrDefault(0L)
  }

  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    val uri = getCurrentPlayingUri() ?: intent.data ?: return fileName
    return getMediaIdentifierFromUri(uri, fileName)
  }

  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    val spec = loopback.specForUrl(uri.toString())
    return if (spec != null) "emby:${spec.server.sourceKey}:${spec.server.remoteId}:${spec.itemId}"
      else "${fileName}_${uri.toString().hashCode()}"
  }

  companion object {
    /**
     * 「本次加载没有字幕存档」的哨兵值。用 [Int.MIN_VALUE] 而不是 0 / -1：
     * mpv 的空轨道（`sid=no`）读回来就是 -1，必须和「压根没有存档」区分开 ——
     * 前者要如实恢复成「字幕关着」，后者则完全不该碰字幕选择。
     */
    private const val NO_SAVED_SUBTITLE_SELECTION = Int.MIN_VALUE

    /**
     * 通知栏封面截图的宽度（像素）（清单 #20 必修 4）。
     *
     * 原来是 `grabThumbnail(1080)` —— **按高度还是宽度不确定**，但无论哪个，
     * 一张 1080 级的图解码成 ARGB_8888 都在 2MB 以上（1080 宽×约 600 高×4B ≈ 2.6MB），
     * 而 `MediaPlaybackService.thumbnail` 是静态字段，一路上还有多处引用。
     *
     * 640 是个够用的值：Android 通知栏的大图标在标准分辨率下也就是
     * `Notification.largeIcon` 那一档，实际显示尺寸通常 < 300dp，
     * 640 宽在 xxhdpi 上仍然绰绰有余，视觉上看不出差别。
     *
     * 省下的是实打实的 4 倍内存 + 4 倍拷贝时间，而这份内存是**要一直挂在进程上**的。
     */
    private const val NOTIFICATION_THUMBNAIL_SIZE = 640

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * `onDestroy` 里等存档协程收尾的上限（毫秒）（清单 #20）。
     *
     * 原本是 `runBlocking { job.join() }` **无限等**，在慢机器 / 数据库锁 /
     * 磁盘满的时候会直接 ANR。留一个上限后最坏情况是「进度晚一点到账」，
     * 而晚到账本身不会丢数据（onPause 退出那次已经同步写过一遍）。
     *
     * 取 500ms：要能覆盖 Room 首次建表 + WAL fsync 的典型耗时，
     * 又不至于让用户看着「退出应用」明显卡顿。
     */
    private const val SAVE_JOIN_BUDGET_MS = 500L

    /**
     * 等 mpv 进入 idle 的上限（毫秒）（清单 #20）。
     *
     * 与原先的 `Thread.sleep(100)` 同量级 —— **最坏情况不比以前更差**，
     * 典型情况（quit 很快被处理）只等几毫秒。
     */
    private const val MPV_IDLE_WAIT_BUDGET_MS = 100L

    /**
     * 上面那个轮询的间隔（毫秒）。
     *
     * 5ms 足够：mpv 处理命令的粒度远大于 5ms，再密只是白烧 CPU。
     */
    private const val MPV_IDLE_POLL_INTERVAL_MS = 5L

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * 时长看守的采样间隔。
     *
     * 1 秒一拍：判据是「播到的位置有没有跑出总时长」，TS 报错的那些值通常小到
     * 几秒就露馅，慢一拍就多看一眼 100% 的进度条。开销可以忽略（一次 `time-pos`
     * 读 + 两次数值比较），而且一旦自洽就立刻退出，正常的片子上只多跑一两拍。
     */
    private const val DURATION_WATCHDOG_INTERVAL_MS = 1000L

    /**
     * 判「时长自洽」的容差（秒）。
     *
     * 位置落在 `总时长 - 1s` 以内就当 mpv 说得对：片尾那一下 `time-pos` 与
     * `duration` 本来就贴得很近，容差太小会把正确的时长误判成错的。
     */
    private const val DURATION_WATCHDOG_TOLERANCE_SEC = 1.0

    /**
     * 起步宽限（秒）：位置走到这个数之前不判 mpv 的「0 时长」是错的。
     *
     * `.mts` 的时长要靠 seek 到尾部扫 PTS，本身就要几百毫秒到一两秒（网络源更慢），
     * 太早判会白读一段流去探。
     */
    private const val DURATION_PROBE_GRACE_SEC = 3.0

    /**
     * 一部片最多探几轮。
     *
     * 给 3：第一轮常在刚起播时，码率还没估稳；多播几秒后再探一次往往就准了。
     * 3 轮还探不出来，说明这条流确实没有可用长度（直播 / 长度未知），
     * 继续探只是白白多读数据。
     */
    private const val MAX_DURATION_PROBES = 3

    /**
     * 判「时长离谱」的倍数（v179）：声称的时长超过
     * `file-size × 8 ÷ 码率`（这份文件物理上能装下的时长）这么多倍，就不是估算误差。
     *
     * 取 8 而不是 2、4：`video-bitrate` 在 VBR 片源上报的可能是**瞬时峰值**，
     * 高估两三倍很正常，容量因此被低估两三倍。而真正要拦的巨值是**一个量级以上**
     * 的偏差（用户实测 40 分钟 → 几百小时 ≈ 400 倍），8 倍的门既兜得住，
     * 又不会把码率抖动误判成错误。详见 `isDurationOversized`。
     */
    private const val DURATION_OVERSIZE_FACTOR = 8.0

    /**
     * 续播位置离片尾小于这个秒数就视为"已经看完"，从头开始播。
     *
     * 防止把 `-1` 秒级的续播位置写进播放器：那样会让 mpv 一开局就 seek 到结束点，
     * IJK 立刻回调播放完成，上层按「播放结束」把 Activity 关掉。
     */
    private const val RESUME_TAIL_GUARD_SEC = 5.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvex"
  }
}

/**
 * 「出画面前」的加载层：从进入播放器到首帧真正上屏之间显示加载动画。
 *
 * 之前这段只有一片黑（视频 Surface 已创建但还没有内容），用户分不清是在加载还是卡死了。
 *
 * ## 判据为什么是 `MPV_EVENT_PLAYBACK_RESTART` 而不是 mpv 的 `vo-configured`
 *
 * mpv 文档对 `vo-configured` 的定义是「VO 是否已配置好，通常对应视频窗口是否可见」，
 * 听起来正好合适 —— 但它在 `vo_reconfig2()` 返回时就为 yes，**早于第一帧 `flip_page`**。
 * 拿它当判据 = 加载动画先消失、再黑一下、画面才浮现，**比不显示更难看**。
 * `PLAYBACK_RESTART` 在日志顺序上排在 `first video frame after restart shown` 之后。
 *
 * 这与视界流那条踩过的坑是同一个（见 `FeedViewModel.buffered` 的注释：
 * 判据早一步就会「转圈消失 → 黑屏 → 画面浮现」）。
 *
 * ## 为什么淡出而不是硬切
 *
 * 硬切的话，哪怕只差一两个 vsync 也会被看成「闪了一下」。
 * 淡出期间画面从动画背后浮上来，观感上就是无缝衔接。
 *
 * ⚠️ 必须是**顶层函数**（放在类的右花括号之外），不是类的方法 ——
 *   写在 `companion object` 里的话，普通 `private fun` 拿不到 `@Composable` 的
 *   调用环境，编译会报一串「@Composable invocations can only happen from…」。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlayerFirstFrameLoading(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  // ⚠️ 不能从 ViewModel 拿偏好：`PlayerViewModel.playerPreferences` 是 **private**
  //   （PlayerViewModel.kt:87）。这里自己注入一份 —— 偏好是 Koin 单例，读的是同一个值
  //   （PlayerControls.kt:858 也是就地注入后读的）。
  val playerPrefs = koinInject<PlayerPreferences>()
  // ⚠️ 三个同名 `collectAsState`（红线 38）：
  //   ① Compose 的 `StateFlow.collectAsState(initial)`（本文件顶部 import 的就是它，
  //      上面 `firstFrameRendered` 用的这个）；
  //   ② 本项目的 `Flow.collectAsState(scope, initial)`（返回 T?，PlayerViewModel.kt:2232）；
  //   ③ 本项目的 `Preference<T>.collectAsState()`（**无参**扩展，
  //      preferences/preference/Preference.kt:43）—— `showLoadingCircle` 是
  //      `Preference<Boolean>`，要的是这个。
  //    同文件 import 两个同名函数不冲突：靠接收者类型重载分辨
  //    （MainActivity.kt:35+59 同时 import 了两个，同样用法）。
  val showLoadingCircle by playerPrefs.showLoadingCircle.collectAsState()

  val firstFrameRendered by viewModel.firstFrameRendered.collectAsState()

  if (!showLoadingCircle) return

  AnimatedVisibility(
    visible = !firstFrameRendered,
    enter = fadeIn(tween(120)),
    exit = fadeOut(tween(260)),
    modifier = modifier,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      // 96dp 是**基准**尺寸（还会乘用户设的缩放系数，默认 1.1×）—— 与缓冲转圈
      // （PlayerControls.kt:866）同基准 ⇒ 首帧层与缓冲层接力时画面不跳。
      // ⚠️ 用 size 参数而不是 Modifier.size，否则「加载动画大小」缩放对它不生效。
      // 压在画面上，所以固定白色半透明（主题色在视频上基本看不见）。
      app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
        size = 96.dp,
        tint = Color.White.copy(alpha = 0.85f),
      )
      // ── 文字提示（原来只有转圈）──
      // 只有动画时用户分不清「在加载」和「卡死了」，尤其网络播放要等好几秒。
      // 第二行是**兜底说明**：死链在起播前已被拦掉（见 EmbyClient.resolveStreamUrl），
      // 但「地址有效却极慢」还是会长时间停在这一屏，得给用户一句能对应上的话。
      Spacer(Modifier.height(14.dp))
      Text(
        text = "正在加载…",
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 13.sp,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "若长时间停在这里，可能是网络不稳或直链已过期",
        color = Color.White.copy(alpha = 0.42f),
        fontSize = 11.sp,
      )
    }
  }
}
