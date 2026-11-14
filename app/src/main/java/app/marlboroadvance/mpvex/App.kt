package app.marlboroadvance.mpvex

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import app.marlboroadvance.mpvex.di.DatabaseModule
import app.marlboroadvance.mpvex.di.FileManagerModule
import app.marlboroadvance.mpvex.di.PreferencesModule
import app.marlboroadvance.mpvex.preferences.LegacyPlayerPreferenceCleanup
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.presentation.crash.GlobalExceptionHandler
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.annotation.KoinExperimentalAPI
import java.io.File

@OptIn(KoinExperimentalAPI::class)
class App : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private var instance: App? = null

    /**
     * MainActivity 首帧成功渲染后调用，写入 boot_ok.txt。
     * 若上次启动在 Application 阶段就崩溃（最可能是 FastThumbnails 原生崩溃，SIGSEGV 不经过 JVM handler），
     * 则 boot_started 存在而 boot_ok 不存在 -> 下次进入安全模式跳过原生初始化，打断死循环。
     */
    fun markBootOk() {
      instance?.runCatching {
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, "boot_ok.txt").writeText("${System.currentTimeMillis()}")
      }
    }
  }

  /**
   * 启动轨迹：写到外部 files 目录 + logcat，便于无 adb 时定位卡死/崩溃发生在哪一步。
   * 路径：Android/data/app.marlboroadvance.mpvex.debug/files/startup_trace.txt
   */
  private fun trace(msg: String) {
    Log.i("StartupTrace", msg)
    runCatching {
      val dir = getExternalFilesDir(null) ?: filesDir
      File(dir, "startup_trace.txt").appendText("${System.currentTimeMillis()} $msg\n")
    }
  }

  /**
   * 启动双标记（写到外部 files 目录）：
   *  - boot_started.txt：App.onCreate 一开始即写入（覆盖式），表示「本次启动已走到 Application 阶段」。
   *  - boot_ok.txt：MainActivity 首帧渲染成功后由 markBootOk() 写入。
   * 若下次冷启动发现 boot_started 存在但 boot_ok 不存在，说明上次在 Application 阶段就崩了
   * （最可能是 FastThumbnails 加载 libmpv 的原生 SIGSEGV，它不经过 JVM 异常处理器），
   * 此时进入「安全模式」跳过 FastThumbnails 的原生初始化，打断「原生崩溃→被杀→重拉→再崩」的死循环。
   * 该机制对原生崩溃有效，而旧版依赖 handler 写标记的方式对原生崩溃完全失效。
   */
  private fun bootStartedFile(): File = File(getExternalFilesDir(null) ?: filesDir, "boot_started.txt")
  private fun bootOkFile(): File = File(getExternalFilesDir(null) ?: filesDir, "boot_ok.txt")

  private fun cleanupLegacyPlayerPreferences() {
    val preferences = PreferenceManager.getDefaultSharedPreferences(this)
    val plan = LegacyPlayerPreferenceCleanup.plan(preferences.all) ?: return
    val editor = preferences.edit()
    plan.decoderModeToSeed?.let { editor.putString(LegacyPlayerPreferenceCleanup.DECODER_MODE_KEY, it) }
    plan.keysToRemove.forEach(editor::remove)
    editor.putInt(LegacyPlayerPreferenceCleanup.MARKER_KEY, plan.markerVersion)
    // Synchronous by design: Koin starts immediately after this method returns.
    check(editor.commit()) { "Failed to persist legacy player preference cleanup" }
  }

  override fun onCreate() {
    instance = this
    trace("App.onCreate start")
    super.onCreate()
    trace("super.onCreate done")

    // 启动双标记：写 start、清 ok，并清除此前的「崩溃页已显示」防循环标记（每次冷启动都是新会话）。
    val lastBootStuck = runCatching { bootStartedFile().exists() && !bootOkFile().exists() }.getOrDefault(false)
    runCatching { bootStartedFile().writeText("${System.currentTimeMillis()}") }
    runCatching { bootOkFile().delete() }
    runCatching { File(filesDir, "crash_shown").delete() }
    trace("boot markers written; lastBootStuck=$lastBootStuck")

    // This must complete before Koin constructs preference objects. The plan seeds the replacement
    // decoder enum before deleting its two legacy booleans, then removes only approved dead keys.
    cleanupLegacyPlayerPreferences()
    trace("legacy player preferences cleaned")

    try {
      startKoin {
        androidContext(this@App)
        modules(
          PreferencesModule,
          DatabaseModule,
          FileManagerModule,
          app.marlboroadvance.mpvex.di.domainModule,
        )
      }
      trace("startKoin done")
    } catch (e: Throwable) {
      trace("startKoin FAILED: ${e.stackTraceToString()}")
      throw e
    }

    val playbackServer: app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer by inject()
    playbackServer.start()
    applicationScope.launch {
      runCatching { playbackServer.awaitReady() }
        .onSuccess { trace("loopback playback server ready on 127.0.0.1:$it") }
        .onFailure { trace("loopback playback server failed: ${it.javaClass.simpleName}") }
    }
    trace("loopback playback server starting")

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
    trace("uncaught handler set")

    // Emby 封面图的磁盘缓存层（下载后 30 天新鲜期 / 上限 256MB，读取不续期）
    app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader.init(this)

    // FastThumbnails 会加载 native 库（libmpv 等），在某些设备/ABI 上可能原生崩溃或抛 UnsatisfiedLinkError。
    // 用 try/catch 包住：即便它失败也只是视频缩略图不可用，绝不该把整个启动砸死。
    // 若检测到上次启动卡在 Application 阶段（lastBootStuck，通常是它自身原生崩溃），则进入安全模式跳过，
    // 打断「原生崩溃→被杀→重拉→再崩」的死循环，让应用至少能进首页、让我们能从 startup_trace 看到真相。
    trace("before FastThumbnails.initialize")
    if (lastBootStuck) {
      trace("lastBootStuck -> 安全模式：跳过 FastThumbnails.initialize，避免再次原生崩溃死循环")
    } else {
      try {
        FastThumbnails.initialize(this)
        trace("after FastThumbnails.initialize")
      } catch (e: Throwable) {
        trace("FastThumbnails.initialize FAILED (non-fatal): ${e.stackTraceToString()}")
      }
    }

    // 解码能力探测：设置里「自动检测」两档要据此决定走硬解还是软解。
    // MediaCodecList 枚举全机编解码器是几十毫秒量级的活，**不能**等到
    // MPVView.initOptions（主线程）再算，开机就丢到后台预热，播放器侧只读结果。
    // 探测没跑完时消费方按「有硬解」处理（＝旧版固定行为），所以晚一点也不要紧。
    applicationScope.launch {
      app.marlboroadvance.mpvex.ui.player.DecoderCapability.probe(this@App)
    }

    trace("App.onCreate done")
  }

}
