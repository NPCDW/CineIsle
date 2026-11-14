package app.marlboroadvance.mpvex.ui.player.feed

import android.os.Handler
import android.os.Looper
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.ui.player.LoembyMpvTransport
import app.marlboroadvance.mpvex.ui.player.LoembyPlaybackCallbacks
import app.marlboroadvance.mpvex.ui.player.LoembyPlaybackCoordinator

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.MPVView
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import `is`.xyz.mpv.Utils as MpvUtils

/**
 * 视界流里的 mpv 播放引擎。整个 feed 只创建这一份实例，翻页时通过 [play] 换源。
 *
 * 界面（标题、进度、按钮、手势）仍然由 [FeedScreen] 用 Compose 绘制；本类负责画面、
 * loopback 注册以及 coordinator 生命周期。
 *
 * ── 生命周期 ──
 * [ensureHost] 必须在**主线程**调用，且要在画面 View 被加进窗口**之前**完成
 * `initialize()` —— 那个方法内部才会执行 `MPVLib.create()`，而 View 一旦 attach，
 * 首帧就会回调 `surfaceCreated → MPVLib.attachSurface()`；在 create 之前调用它
 * 等于解引用空指针（native SIGSEGV，Java 层的 runCatching 拦不住）。
 * 这正是 PlayerActivity 里那段「内核初始化必须同步完成」注释的同一个坑。
 */
internal class FeedMpvEngine(
  private val context: Context,
  private val onError: (String) -> Unit = {},
) : KoinComponent {

  private val loopback: LoopbackPlaybackServer by inject()
  private val coordinator: LoembyPlaybackCoordinator by inject()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var registration: Job? = null
  @Volatile private var generation = 0L
  private var active = false
  private var finished = false
  private var released = false
  @Volatile private var coreAlive = false
  @Volatile private var observer: MPVLib.EventObserver? = null

  private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
    "Feed mpv access must run on Main"
  }

  private fun requireCore() {
    requireMain()
    check(coreAlive) { "Feed mpv core is not ready" }
  }

  /** Coordinator and the feed share exactly the initialized MPVLib singleton. */
  private val transport = object : LoembyMpvTransport {
    override fun command(vararg args: String) {
      requireCore()
      if (args.firstOrNull() == "loadfile") {
        require(args.getOrNull(1)?.let(loopback::isLocalPlayUrl) == true) {
          "Feed mpv only loads registered local /play_media URLs"
        }
      }
      MPVLib.command(*args)
    }
    override fun getDouble(name: String): Double? { requireCore(); return MPVLib.getPropertyDouble(name) }
    override fun propertyString(name: String): String? { requireCore(); return MPVLib.getPropertyString(name) }
    override fun setDouble(name: String, value: Double) { requireCore(); MPVLib.setPropertyDouble(name, value) }
    override fun setString(name: String, value: String) { requireCore(); MPVLib.setPropertyString(name, value) }
    override fun setBoolean(name: String, value: Boolean) { requireCore(); MPVLib.setPropertyBoolean(name, value) }
    override fun getBoolean(name: String): Boolean? { requireCore(); return MPVLib.getPropertyBoolean(name) }
    override fun getNode(name: String): MPVNode? { requireCore(); return MPVLib.getPropertyNode(name) }
  }

  private fun registerObserver() {
    requireCore()
    val events = object : MPVLib.EventObserver {
      private fun onMain(block: () -> Unit) {
        val ticket = generation
        mainHandler.post {
          // Ignore queued events from a stopped request or a destroyed/recreated singleton.
          if (coreAlive && observer === this && active && generation == ticket) {
            runCatching(block).onFailure { Log.w("FeedMpv", "mpv event ignored") }
          }
        }
      }
      override fun eventProperty(property: String) {
        onMain {
          if (property == "eof-reached" && transport.getBoolean(property) == true) {
            coordinator.eofReached(this@FeedMpvEngine)
          }
        }
      }
      override fun eventProperty(property: String, value: Boolean) {
        onMain { if (property == "eof-reached" && value) coordinator.eofReached(this@FeedMpvEngine) }
      }
      override fun eventProperty(property: String, value: Double) {
        onMain { coordinator.propertyChanged(property, value, this@FeedMpvEngine) }
      }
      override fun eventProperty(property: String, value: Long) {}
      override fun eventProperty(property: String, value: String) {}
      override fun eventProperty(property: String, value: MPVNode) {}
      override fun event(eventId: Int, data: MPVNode) {
        onMain {
          when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> coordinator.event(eventId, data, this@FeedMpvEngine)
          }
        }
      }
    }
    observer = events
    MPVLib.addObserver(events)
    MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    MPVLib.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
  }

  /** 画面 View（外层那层 FrameLayout）；null = 还没建（或建失败） */
  private var host: View? = null

  private var mpvView: MPVView? = null

  /** 初始化走到了不可恢复的失败 —— 不再重试，避免反复碰 native */
  private var broken = false

  /** A bound request is deduplicated before asynchronous local registration. */
  private var currentSpec: PlaybackSpec? = null

  /** Only a registered local /play_media URL may wait for Surface readiness. */
  private var pendingLocalUrl: String? = null

  /**
   * `initOptions` 选定的视频输出（`gpu` 或 `gpu-next`）。
   *
   * ⚠️ 必须自己记：`BaseMPVView.surfaceDestroyed()` 会把 `vo` 置成 `null`，
   * 而只有在「没有待加载文件」时它才会在 `surfaceCreated` 里恢复 —— 我们总会带着
   * 待加载文件，于是恢复这一步被跳过，重挂载后 `vo=null` ⇒ 界面黑着但有声音。
   * 这里在建层时就把它记下来，[pump] 每次挂好表面都核对一遍。
   */
  private var voName: String? = null

  /** 是否已经成功初始化（之后才允许碰任何 `MPVLib` 属性） */
  val ready: Boolean get() { requireMain(); return coreAlive }

  /** 已建好的画面 View（交给 Compose 的 AndroidView 托管用） */
  fun hostView(): View? = host

  /**
   * 建好画面 View 并完成内核初始化。返回 null 表示这条路走不通（调用方应摆错误卡片）。
   *
   * ⚠️ 调用时机见类注释：[ensureHost] 返回后调用方才可以把返回的 View 交给 Compose。
   */
  fun ensureHost(inflater: LayoutInflater): View? {
    requireMain()
    if (released) return null
    host?.let { return it }
    if (broken || PlayerLib.isCoreReady) return null

    // ① 脚本 / 字体 / shader 先就位：`glsl-shaders`、`sub-fonts-dir` 这类都是
    //    setOptionString，过了 initialize 再设就不生效了（与 PlayerActivity 的
    //    prepareMpvFilesOnIoThread 同一个原因）。拷失败不致命 —— 少的是自定义脚本，
    //    不影响到「能不能解码」这件事，所以只记日志不判死。
    runCatching { MpvUtils.copyAssets(context) }

    val root = runCatching { inflater.inflate(R.layout.view_feed_mpv, null, false) }
      .getOrNull() ?: run {
      broken = true
      return null
    }
    val view = root.findViewById<MPVView>(R.id.feedMpv) ?: run {
      broken = true
      return null
    }

    // ② create + 选项 + init 全在这次调用里（AAR 的实现），必须在 attach 之前完成
    val inited = runCatching { view.initialize(context.filesDir.path, context.cacheDir.path) }
      .isSuccess
    if (!inited) {
      broken = true
      return null
    }

    mpvView = view
    coreAlive = true
    PlayerLib.markCoreReady()
    host = root
    runCatching { registerObserver() }.onFailure {
      broken = true
      release()
      return null
    }
    // 记下 initOptions 选定的输出（见 [voName] 的说明）：surfaceDestroyed 会把它
    // 置成 null，而我们不会走 AAR 那条「无待加载文件才恢复」的分支。
    voName = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()

    // Keep the final frame; Coordinator applies its EOF guard before the feed advances.

    return root
  }

  /** Register asynchronously; native mpv only receives the registered local URL. */
  fun play(spec: PlaybackSpec) {
    requireMain()
    if (!ready || released) return
    if (active && spec == currentSpec) {
      setPaused(false)
      return
    }
    stop()
    currentSpec = spec
    active = true
    finished = false
    val ticket = generation
    registration = scope.launch {
      var local: String? = null
      try {
        local = loopback.register(spec)
        if (!active || generation != ticket || !ready) return@launch
        coordinator.prepare(local, transport, object : LoembyPlaybackCallbacks {
          private fun ownsRequest() = active && coreAlive && generation == ticket
          override fun onError(message: String) {
            if (ownsRequest()) this@FeedMpvEngine.onError(message)
          }
          override fun onFinished(reason: String) {
            if (!ownsRequest()) return
            // Only Coordinator can accept EOF. Active stop is disabled before finish below.
            finished = reason == "next" || reason == "end-of-playlist"
            if (reason == "error") {
              active = false
              generation++
              currentSpec = null
              pendingLocalUrl = null
              registration?.cancel()
              runCatching { transport.command("stop") }
              this@FeedMpvEngine.onError("Emby 播放失败，请重试或返回")
            }
          }
        }, owner = this@FeedMpvEngine)
        if (!active || generation != ticket || !ready) return@launch
        pendingLocalUrl = local
        pump()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (active && generation == ticket && ready) {
          stop()
          onError("获取 Emby 本地播放地址失败，请检查服务器配置")
        }
      } finally {
        if (!active || generation != ticket || !ready) local?.let(loopback::release)
      }
    }
  }

  /**
   * 把「已点名但还没打开」的地址真正送进 mpv —— Surface 没就绪就继续等。
   *
   * ## 为什么不用 `BaseMPVView.playFile()`（这是 v143 黑屏的根因）
   *
   * 反编译 AAR 可以看到，`playFile()` 的实现只有一句 `this.filePath = path`；
   * 真正的 `command("loadfile", …)` 写在 `surfaceCreated()` 里，而且读一次就把
   * `filePath` 清空。也就是说：
   *
   * · 只有在 **Surface 还没创建**时调用它，文件才会被加载；
   * · Surface 已经就绪之后再调 ⇒ 只是把路径存进一个再也没人读的字段，
   *   mpv 那边**什么都没发生**（没有 path、没有 duration、没有画面）。
   *
   * 我们的画面层在切到 mpv 时早就 attach 过了（滑回来、重载都是同一层），
   * 走 `playFile` 就是那个「什么都没发生」的分支 —— 现象正是「黑屏 + 一直加载中」。
   * 所以这里一律走 `MPVLib.command("loadfile", …, "replace")`：它不看 Surface 状态，
   * 就绪后立刻生效。
   */
  fun pump() {
    requireMain()
    val view = mpvView ?: return
    val local = pendingLocalUrl ?: return
    if (!active || !ready) return
    val surfaceReady = runCatching { view.holder?.surface?.isValid == true }.getOrDefault(false)
    if (!surfaceReady) return
    // ① 先把 vo 恢复回来：surfaceDestroyed 会把 vo 与 force-window 一起关掉，
    //    不恢复就是「挂上了 Surface 也不出画面」—— 有声音、全黑。
    ensureVo(view)
    // Identity belongs exclusively to LoopbackHttpPool's upstream requests.
    transport.setString("user-agent", "")
    transport.setString("http-header-fields", "")
    pendingLocalUrl = null
    // A single logical entry; reloads from the EOF guard use this same guarded transport.
    transport.command("loadfile", local, "replace")
    transport.setBoolean("pause", false)
    Log.w("FeedMpv", "loadfile")
  }

  /**
   * 把视频输出恢复成可用状态。
   *
   * 三条路径都要走对，少一条就是黑屏：
   * · `vo` 读写口径 —— 建层时 `initOptions` 里用的是 `setOptionString`（初始化**前**才
   *   生效），而运行期改 `vo` 只有 `setPropertyString` 管用；AAR 的 `surfaceDestroyed`
   *   也正是用 `setPropertyString("vo", "null")` 把它关掉的，对称着写回来。
   * · AAR 内部的 `voInUse` —— `setVo` 会更新它，之后 AAR 自己的 `surfaceCreated`
   *   恢复分支（`filePath == null` 时 `setPropertyString("vo", voInUse)`）才拿得到正确值。
   * · `force-window` —— `surfaceDestroyed` 顺手把它置成 `no`，重挂表面后不还原，
   *   mpv 会认为「没有窗口要画」。
   */
  private fun ensureVo(view: MPVView) {
    val current = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()
    val usable = !current.isNullOrBlank() && current != "null" && current != "no"
    if (usable) {
      // 第一次成功出画面时把真实生效的 vo 记下来：`initOptions` 之后立刻读，mpv 可能
      // 还没建 VO（返回 null），那时 [voName] 是空的，这里补一次自愈。
      if (voName == null) voName = current
      runCatching { MPVLib.setPropertyString("force-window", "yes") }
      return
    }
    val target = voName ?: return
    runCatching { view.setVo(target) } // 同步 AAR 内部的 voInUse
    runCatching { MPVLib.setPropertyString("vo", target) } // 运行期真正恢复输出
    runCatching { MPVLib.setPropertyString("force-window", "yes") }
  }

  /**
   * 画面表面是否已经就绪。
   *
   * 调用方用它区分两种「一直加载中」：表面没就绪是**渲染链路**没起来（View 没挂上 /
   * 被裁掉），表面就绪却拿不到时长才是**源**的问题。两者的错误文案不该一样。
   */
  fun surfaceReady(): Boolean =
    runCatching { mpvView?.holder?.surface?.isValid == true }.getOrDefault(false)

  /** Main.immediate/UNDISPATCHED runs Coordinator cancellation before native teardown. */
  private fun finishPlayback(reason: String, destroy: Boolean = false) {
    requireMain()
    active = false
    finished = false
    generation++
    registration?.cancel()
    registration = null
    currentSpec = null
    pendingLocalUrl = null
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        coordinator.finish(reason = reason, owner = this@FeedMpvEngine)
      } finally {
        if (coreAlive) {
          runCatching { transport.command("stop") }
          runCatching { transport.setString("user-agent", "") }
          runCatching { transport.setString("http-header-fields", "") }
          if (destroy) {
            observer?.let { runCatching { MPVLib.removeObserver(it) } }
            observer = null
            coreAlive = false
            PlayerLib.markCoreGone()
            runCatching { MPVLib.destroy() }
            mpvView = null
            host = null
          }
        }
        if (destroy) scope.cancel()
      }
    }
  }

  /** Stop immediately on Main; explicit stop must not trigger the feed's auto-next. */
  fun stop() = finishPlayback("stop")

  fun setPaused(paused: Boolean) {
    if (!ready) return
    runCatching { MPVLib.setPropertyBoolean("pause", paused) }
  }

  fun isPaused(): Boolean =
    if (!ready) true else runCatching { MPVLib.getPropertyBoolean("pause") ?: true }
      .getOrDefault(true)

  fun positionSec(): Double =
    if (!ready) 0.0 else runCatching { MPVLib.getPropertyDouble("time-pos") ?: 0.0 }
      .getOrDefault(0.0)

  fun durationSec(): Double =
    if (!ready) 0.0 else runCatching { MPVLib.getPropertyDouble("duration") ?: 0.0 }
      .getOrDefault(0.0)

  fun seekTo(sec: Double) {
    if (!ready) return
    // v217：显式 `+keyframes`。mpv 是进程级单例 —— 主播放器的 PlayerViewModel 会
    // 按时长**动态改写全局 `hr-seek`**（小于 2 分钟的片子会被切成精确 seek），
    // 视界流的短 clip 恰好全落在这个区间里，等于被那条逻辑误伤：拖一下每条
    // seek 都要「解到精确帧」。命令级 flag 优先于全局选项，写死 keyframes
    // 才不受污染；对短视频拖动来说，落点差不到一个 GOP，用户感知不出。
    runCatching { transport.command("seek", sec.coerceAtLeast(0.0).toString(), "absolute+keyframes") }
  }

  /**
   * 画面出来了没有 —— 加载圈撤不撤的判据。
   *
   * 用 `video-params/w` 而不是 `video-params/aspect`：前者只有**真正解出第一帧**之后
   * 才有值，后者在某些容器上容器头一读完就能读到，拿它撤圈会撤出一个黑屏。
   */
  fun hasPicture(): Boolean =
    if (!ready) false else runCatching {
      (MPVLib.getPropertyInt("video-params/w") ?: 0) > 0
    }.getOrDefault(false)

  fun isBuffering(): Boolean =
    if (!ready) false else runCatching {
      MPVLib.getPropertyBoolean("paused-for-cache") ?: false
    }.getOrDefault(false)

  fun reachedEnd(): Boolean { requireMain(); return ready && finished }

  /**
   * 释放内核。
   *
   * ⚠️ 只调 `MPVLib.destroy()`，**不**调 `BaseMPVView.destroy()` —— 与 PlayerActivity
   * 的 `PlayerLib.destroy()` 同一条路径（那条路径是线上验证过的）。View 侧随窗口销毁
   * 自己会 detachSurface，重复销毁反而容易碰上已释放的句柄。
   */
  fun release() {
    requireMain()
    if (released) return
    released = true
    finishPlayback("quit", destroy = true)
  }
}
