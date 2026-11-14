package app.marlboroadvance.mpvex.ui.browser.emby
import app.marlboroadvance.mpvex.ui.player.PlayerDiagLog
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.marlboroadvance.mpvex.domain.emby.EmbyLog
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * Emby 播放进度上报器。
 *
 * 职责：把 mpv 的播放状态实时同步回 Emby 服务器——
 * - 开始播放：POST /Sessions/Playing
 * - 播放中 / 暂停：POST /Sessions/Playing/Progress
 * - 切换下一集：上一集 Stopped + 新一集 Start（服务端"正在播放"随之更新）
 * - 退出播放页：POST /Sessions/Playing/Stopped
 *
 * 实现方式：直接读 MPVLib 的静态属性（time-pos / pause / path），
 * 以轮询的方式工作，因此不需要改动 PlayerActivity 内部的播放控制逻辑，
 * 只需在 Activity 上挂一个 lifecycle 观察者即可。
 */
object EmbyPlaybackReporter {
  private const val TAG = "EmbyReporter"

  /** 上报间隔：Emby 官方客户端也是每 10 秒一次进度上报 */
  private const val PROGRESS_INTERVAL_MS = 10_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var pollingJob: Job? = null

  private var serverId: Long = -1L
  private var itemIds: List<String> = emptyList()

  /**
   * 当前正在上报的条目 Id。
   *
   * ⚠️ `@Volatile`：写它的是轮询协程（IO），读它的可能是 [stop] 里的另一条协程，
   * 不加就可能读到旧值，把 Stopped 发到上一集上。
   */
  @Volatile private var currentItemId: String? = null

  @Volatile private var playSessionId: String = ""
  @Volatile private var lastReportedTicks: Long = 0L

  /**
   * 本场（或本条）的**起点**，单位 ticks：`start()` 取 intent 里的续播位置，切集时归 0。
   *
   * 只服务于 [guardAgainstReset]：续播起播后的头十几秒里，播放器经常还没落到续播位置
   * （mpv 的 `time-pos` 先给 0），此刻读到的低值一旦上报，服务端已有的续播点就被冲掉了。
   */
  @Volatile private var sessionStartTicks = 0L

  /** 本场（或本条）开始时刻（`SystemClock.elapsedRealtime()`），配合 [sessionStartTicks] 使用。 */
  @Volatile private var sessionStartedAt = 0L

  /**
   * 最近一次「Start 请求」的发出时刻（`SystemClock.elapsedRealtime()`）；0 = 没有在飞。
   *
   * 用途只有一个：别在 Start 还在路上时判定「没有 session」又补发一次 —— 那会变成
   * 同一场播放两个 session，服务端上就是两条并行的「正在播放」。
   */
  @Volatile private var startRequestedAt: Long = 0L

  /**
   * 已经为哪个条目补发过 Start。
   *
   * 补发是**兜底**不是重试机制，所以同一条目只准补一次：否则每次轮询都会发现
   * 「还是空串」再发一次，10 秒一条 Start 打到服务器上，比不修还糟。
   */
  @Volatile private var sessionRepairFor: String? = null

  /**
   * [playSessionId] 对应的那次 Start 是否**被服务端成功接收过**（2xx / 204 都算）。
   *
   * ⚠️ 判据必须是「请求成败」，不能是「有没有拿到 PlaySessionId」—— Emby 的
   * `/Sessions/Playing` 正常就返回 204 / 空 JSON，那个字段压根不存在。曾经拿空串当
   * 失败判据，结果每场只补一次 Start、之后整场一条 Progress 都不发（0% 的元凶）。
   */
  @Volatile private var startAcknowledged = false

  /**
   * 由播放页提供的「当前正在播的是哪一条」。
   *
   * ## 为什么需要这个口子（v223）
   *
   * 本类是个 object，拿不到播放页的 `playlistIndex` —— 那是 Compose 状态，而且被
   * 「拖动重排队列 / 随机播放」改过，与 intent 里的原始顺序早就错位了。播放页的
   * `PlayerActivity.currentEmbyItemId()` 已经把「路径反查 + 按当前下标兜底」两条路
   * 都实现了（302 直链、ISO 本地代理两种 path 都覆盖），这里直接借用，不复制逻辑。
   *
   * ⚠️ 不接这个口子的后果就是 v222 及之前的线上现象：开了「预解析直链（302）」后
   * mpv 的 `path` 是网盘 CDN 域名，`/Videos/{id}/stream` 正则恒不命中 ⇒
   * [readCurrentItemId] 恒 null ⇒ 永远退回起播那一条 ⇒ 连播时检测不到切集，
   * 上一集不报 Stopped、新一集不报 Start，新一集的时间被以第一条的 itemId 写出去。
   * 用户侧看到的就是「随机播放后首页继续观看不更新」。
   *
   * 返回 null 是正常情况（非 Emby 播放 / 列表还没就绪），此时退回路径反查。
   */
  @Volatile
  var idProvider: (() -> String?)? = null

  /**
   * 当前这场播放的令牌。
   *
   * 即使页面生命周期交错，旧页面的 [stop] 也不能结算新页面刚建立的会话。
   */
  @Volatile
  private var sessionToken: Any? = null

  /** 与 [itemIds] **同序同长**的媒体源 Id，用于逐集上报实际版本。 */
  private var mediaSourceIds: List<String> = emptyList()

  /**
   * 结算「上一场还没收尾的播放」。
   *
   * 启动新会话前先结算本单例尚未收尾的上一场播放。
   * 位置取 [lastReportedTicks]，避免新页面的状态覆盖旧会话。
   */
  private fun settlePreviousSession() {
    val itemId = currentItemId ?: return
    val sid = serverId
    val sessionId = playSessionId
    val sourceId = mediaSourceIdOf(itemId)
    val ticks = lastReportedTicks
    currentItemId = null
    playSessionId = ""
    if (ticks <= 0L) return
    scope.launch {
      runCatching {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(sid) ?: return@launch
        repository.reportPlaybackStopped(
          server = server,
          itemId = itemId,
          playSessionId = sessionId,
          positionTicks = ticks,
          mediaSourceId = sourceId,
        )
      }
    }
  }

  /**
   * 播放页创建时调用：上报开始并启动轮询。
   *
   * @param token 本场播放的令牌；同一个观察者的 [EmbyPlaybackLifecycleObserver.onDestroy]
   *   会带同一个令牌回来，用来区分「这是不是我那一场」（见 [sessionToken]）。
   * @return true 表示这次播放来自 Emby，已接管上报
   */
  fun start(intent: Intent?, token: Any? = null): Boolean {
    val ids = intent?.getStringArrayListExtra(EXTRA_ITEM_IDS)
    val sid = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
    if (ids.isNullOrEmpty() || sid <= 0) return false

    // 上一场还没收尾时先结算它，再认领这一场。
    // 顺序不能反：settlePreviousSession 读的是「当前字段」，一旦下面开始覆盖就晚了。
    if (sessionToken != null && sessionToken !== token) settlePreviousSession()
    // 认领这一场：之后只有拿着同一令牌的 stop 才能收尾
    sessionToken = token
    serverId = sid
    itemIds = ids
    currentItemId = ids.firstOrNull()
    lastReportedTicks = 0L
    // ⚠️ 会话 id **由客户端自己生成**，不等服务端给。
    //    Emby 的 `POST /Sessions/Playing` 正常返回 204（空 body）或 `{}`，里面根本没有
    //    `PlaySessionId` 字段 —— 靠响应取等于永远空串，而 Progress / Stopped 拿着空串
    //    去对账，服务端记不住这次播放（「退出后已观看 0%」就是这么来的）。
    playSessionId = newPlaySessionId()
    startAcknowledged = false
    sessionRepairFor = null
    mediaSourceIds =
      intent.getStringArrayListExtra(EXTRA_MEDIA_SOURCE_ID)?.toList() ?: emptyList()

    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return false

    val firstId = currentItemId ?: return false
    // 本地「最近播放」记一笔（起播即记，不等退出：崩溃 / 强杀也算看过）。
    // 为什么必须自己记：这个服务端的 `Items/Resume` 不按播放时间排序，服务端上也没有
    // `LastPlayedDate` 可用，首页只能靠这份账本把刚播的排到最前。详见 EmbyRecentPlays。
    recordRecentPlay(sid, firstId)
    // 用启动时指定的续播位置作为起点
    val startPositionTicks =
      EmbyTicks.secondsToTicks((intent.getIntExtra("position", 0) / 1000).toLong())
    // ⚠️⚠️ 起点必须跟着续播位置走，**不能**留 0。
    //   `lastReportedTicks` 是「读不到实时位置时的兜底值」，它要是在续播场景里等于 0，
    //   「续播 → 立刻退出」这条路就会把 0 当位置发 Stopped，服务端 30 分钟的续播点当场清零 ——
    //   用户看到的正是「继续播放后马上返回，进度变成 0」。
    lastReportedTicks = startPositionTicks
    sessionStartTicks = startPositionTicks
    sessionStartedAt = android.os.SystemClock.elapsedRealtime()
    val firstSourceId = mediaSourceIdOf(firstId)
    val sessionForStart = playSessionId
    startRequestedAt = android.os.SystemClock.elapsedRealtime()
    scope.launch {
      try {
        runCatching {
          val server = repository.getServer(sid) ?: return@runCatching
          // ⚠️⚠️ 先用 **服务端签发的** 会话 id 再发 Start（v231）。
          //   自生成的 UUID 服务端没有对应会话，位置照写（详情页有进度），但收尾那步
          //   「盖最后播放时间」会被跳过 ⇒ 条目永远留在原来的位次上，刚播完也不往
          //   「继续观看」前面走。Yamby 是先 PlaybackInfo 再上报，所以它播过的会到最前。
          //   PlaybackInfo 失败就退回自生成的那个（v226 的老行为）：宁可时间戳不刷新，
          //   也不能让整场上报断掉。
          val issued = runCatching { repository.requestPlaybackSession(server, firstId) }
            .getOrNull()
          // 取会话期间页面可能已经被下一场接管（singleTask 复用），那就别再往下写了
          if (sessionToken !== token) return@runCatching
          val sessionId = issued?.first?.takeIf { it.isNotBlank() } ?: sessionForStart
          val sourceId = issued?.second?.takeIf { it.isNotBlank() } ?: firstSourceId
          if (sessionId != sessionForStart) playSessionId = sessionId
          PlayerDiagLog.log(
            "report",
            "Start item=$firstId ticks=$startPositionTicks session=$sessionId " +
              "issued=${issued != null}",
          )
          playSessionId = repository.reportPlaybackStart(
            server = server,
            itemId = firstId,
            positionTicks = startPositionTicks,
            mediaSourceId = sourceId,
            playSessionId = sessionId,
          )
          // 判据是「请求成功返回」（2xx / 204 都算），**不是**「有没有拿到 session id」
          startAcknowledged = true
        }
      } finally {
        startRequestedAt = 0L
      }
    }

    startPolling()
    return true
  }

  /**
   * 播放页退出时调用：上报停止并结束轮询。
   *
   * ## 调用时机（v227 起的约定）
   *
   * **先 `onStop`、后 `onDestroy`**，两次都调，靠令牌去重：
   * - `onStop` 里只在「Activity 正在 finish」（返回手势 / 返回键 / 点关闭）时报一次 ——
   *   此刻内核**还活着**，位置读得准，而且比 `onDestroy` 早一个生命周期，
   *   进程被系统回收前有充裕时间把请求发出去；
   * - `onDestroy` 是兜底（按 Home 后从最近任务划掉、PiP 关闭等）：那时位置多半已经
   *   读不到，只能退回 [lastReportedTicks]。
   *
   * @param token 本场播放的令牌。与当前会话不符时**直接返回** —— 那是「上一个内核的
   *   播放页」在退场，它要结算的是它自己那一集，不能拿新页面刚写进来的字段去收尾。
   */
  fun stop(token: Any? = null) {
    if (token != null && token !== sessionToken) return

    // Read the live mpv position before cleanup closes the native handle.
    val itemId = currentItemId
    val liveTicks = if (itemId != null) readPositionTicks() else null

    sessionToken = null
    pollingJob?.cancel()
    pollingJob = null
    // Drop the Activity callback before asynchronous cleanup continues.
    idProvider = null

    if (itemId == null) return
    // 实时读数优先；读不到才用「最后一次成功上报的位置」（别让 Stopped 把续播点写成 0）。
    // 再过一道 guardAgainstReset：续播起播初期播放器可能还在 0 秒，直接用会把续播点冲掉。
    val ticks = guardAgainstReset(liveTicks ?: lastReportedTicks)
    val stopSourceId = mediaSourceIdOf(itemId)
    val sid = serverId
    // ⚠️ session 取**快照**：下面那段是异步发出去的，等它真跑起来时字段可能已经被
    //    下一场播放改写了（快速退出再进入就是这个时序）。
    val sessionId = playSessionId
    currentItemId = null
    playSessionId = ""

    scope.launch {
      runCatching {
        PlayerDiagLog.log(
          "report",
          "Stopped item=$itemId ticks=$ticks live=${liveTicks != null} session=$sessionId",
        )
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(sid) ?: return@launch
        repository.reportPlaybackStopped(
          server = server,
          itemId = itemId,
          playSessionId = sessionId,
          positionTicks = ticks,
          mediaSourceId = stopSourceId,
        )
      }.onFailure { EmbyLog.warn("上报 Stopped 失败（item=$itemId）", it) }
    }
  }

  /**
   * 往本地「最近播放」账本记一笔。
   *
   * 记账失败必须无后果：拿不到偏好就静默跳过，首页退化成服务端顺序（也就是加这个功能
   * 之前的行为），绝不能因为记一笔账把播放搞出问题。
   */
  private fun recordRecentPlay(serverId: Long, itemId: String) {
    runCatching {
      val prefs = get<app.marlboroadvance.mpvex.preferences.BrowserPreferences>(
        app.marlboroadvance.mpvex.preferences.BrowserPreferences::class.java,
      )
      val ledger = prefs.embyRecentPlays(serverId)
      ledger.set(
        app.marlboroadvance.mpvex.domain.emby.EmbyRecentPlays.record(
          raw = ledger.get(),
          itemId = itemId,
          now = System.currentTimeMillis(),
        ),
      )
    }.onFailure { EmbyLog.warn("记录最近播放失败（item=$itemId）", it) }
  }

  /**
   * "Start 已经发出去了但还没回来" 的宽限期。
   *
   * 轮询间隔 10 秒，给两轮的时间：超过这个时长还没有 `PlaySessionId` 就认为那次
   * Start 真的失败了（没网 / 服务端 500 / 回包字段缺失），[ensureSession] 才补发。
   * 不设这道闸的话，第一轮轮询时 Start 十有八九还在路上，会白白多开一个 session。
   */
  private const val SESSION_START_GRACE_MS = 20_000L

  /**
   * 「起播后多久之内，位置读数不被采信为『用户往回拖了』」。
   *
   * 续播起播时播放器要先 seek 到续播点：mpv 在 seek 落地之前，`time-pos` 给的是 **0**（或
   * 上一次内核残留的值），网络片源上这个窗口能到十几秒。这段时间里把读到的低值报出去，
   * 服务端就把续播点改成了那个低值 —— 典型现象是「继续播放 → 立刻返回 → 进度变成 0」。
   *
   * 20 秒 = 两个上报周期。超过这个窗口还往回走，只可能是用户自己拖回去的，照发。
   */
  private const val START_SETTLE_MS = 20_000L

  /**
   * 把「起播初期的假位置」抬回本场起点。
   *
   * 判据只有一条：**起播 [START_SETTLE_MS] 之内、读数比本场起点还小** —— 那不是用户拖回去的，
   * 是 seek 还没落地。过了这个窗口就一律照发（尊重用户回拖）。
   */
  private fun guardAgainstReset(ticks: Long): Long {
    if (ticks >= sessionStartTicks) return ticks
    val sinceStart = android.os.SystemClock.elapsedRealtime() - sessionStartedAt
    if (sinceStart in 0 until START_SETTLE_MS) {
      PlayerDiagLog.log(
        "report",
        "起播初期位置回落被抬回：raw=$ticks floor=$sessionStartTicks since=${sinceStart}ms",
      )
      return sessionStartTicks
    }
    return ticks
  }

  private fun startPolling() {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      // 等 mpv 加载完文件再读属性，避免读到空值
      delay(PROGRESS_INTERVAL_MS)
      while (isActive) {
        // ⚠️ **整轮兜住**。这里是 IO 协程的根：`scope` 用的是 `SupervisorJob` 且没有
        // CoroutineExceptionHandler ⇒ 任何一条漏兜的异常都会冒到线程的默认处理器，
        // 也就是「未捕获异常 ⇒ 进程直接死」。而本类恰好在**换片瞬间**读播放器状态
        // （mpv 正在销毁/重建 core），正是最容易抛的时刻 ——
        // 用户看到的就是「切下一集偶发闪退」。内部各步该兜的仍然各自兜，但兜漏一处
        // 不该由用户买单。
        runCatching { reportProgressOnce() }
          .onFailure { EmbyLog.warn("进度上报异常（已忽略，下一轮继续）", it) }
        delay(PROGRESS_INTERVAL_MS)
      }
    }
  }

  /**
   * 一次进度上报。
   *
   * 若发现当前播放的已经是播放列表里的另一项（播完自动切下一集，或用户手动切换），
   * 则先结束上一集再开始新一集，让服务端的"正在播放"跟着走。
   */
  private suspend fun reportProgressOnce() {
    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return
    val server = repository.getServer(serverId) ?: return

    // 内核还没就绪 / 读不到位置 ⇒ 这一轮整轮跳过（不能当 0 发，见 readPositionTicks 注释）
    val positionTicks = guardAgainstReset(readPositionTicks() ?: return)
    val isPaused = readIsPaused()
    val playingItemId = readCurrentItemId() ?: currentItemId ?: return
    val playingSourceId = mediaSourceIdOf(playingItemId)

    if (playingItemId != currentItemId) {
      // ── 切换了媒体：结束上一集，开始新一集 ──
      val previousItemId = currentItemId
      val previousTicks = lastReportedTicks
      val previousSessionId = playSessionId
      val previousSourceId = previousItemId?.let { mediaSourceIdOf(it) }
      currentItemId = playingItemId
      // ⚠️ 会话必须**紧跟着作废**：这一轮只要在中途抛了（下面几行都是外部调用），
      //    下一轮就会发现 `playingItemId == currentItemId` 而直接拿这个 sessionId 去发
      //    新一集的 Progress —— 上一集的会话记新一集的位置，服务端上两集全乱。
      playSessionId = ""
      // 换条目 ⇒ 上一场补发记录作废；新条目的 Start 马上就来
      sessionRepairFor = null
      if (previousItemId != null) {
        runCatching {
          repository.reportPlaybackStopped(
            server = server,
            itemId = previousItemId,
            playSessionId = previousSessionId,
            positionTicks = previousTicks,
            mediaSourceId = previousSourceId,
          )
        }
      }
      // ⚠️ 换一集就换一个会话 id：上一集的 Progress 绝不能记到新一集头上。
      //    这里**先生成再发**（不等服务端回话），Start 抛异常也不会留着旧的。
      val sessionForNext = newPlaySessionId()
      playSessionId = sessionForNext
      startAcknowledged = false
      runCatching {
        playSessionId = repository.reportPlaybackStart(
          server = server,
          itemId = playingItemId,
          positionTicks = 0,
          mediaSourceId = playingSourceId,
          playSessionId = sessionForNext,
        )
        startAcknowledged = true
      }
      lastReportedTicks = 0
      // 新一集从 0 秒起播（连播不续播）⇒ 本场起点跟着归零，回落保护自然失效
      sessionStartTicks = 0L
      sessionStartedAt = android.os.SystemClock.elapsedRealtime()
      return
    }

    // 手上没有可用 session 就先补一次 Start；补不上本轮跳过（带空串发出去等于白打）
    if (!ensureSession(repository, server, playingItemId, playingSourceId, positionTicks)) return

    lastReportedTicks = positionTicks
    runCatching {
      repository.reportPlaybackProgress(
        server = server,
        itemId = playingItemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
        mediaSourceId = playingSourceId,
      )
    }
  }

  /**
   * 确保 Start 至少被服务端接收过一次；**无论补发成败都放行本轮 Progress**。
   *
   * ## 为什么需要它
   *
   * [start] 里那次 Start 是**异步发**的，网络抖动 / 服务端 5xx 都可能让它没送到。
   * 这种情况要补一次，否则服务端上这场播放「没开始过」，后续 Progress 无处归账。
   *
   * ## ⚠️ 曾经的重大错误（v223~v225，用户报「退出后已观看 0%」）
   *
   * 那时用「`playSessionId` 是否为空」判断 Start 成没成功 —— 可 Emby 的
   * `/Sessions/Playing` 正常就返回 **204 / 空 JSON，里面没有这个字段**，于是每次都判成
   * 失败：每场只补一次，之后**整场一条 Progress 都不发**，Stopped 又只能拿
   * `lastReportedTicks`（从没更新过 = 0）结账 ⇒ 服务端记成「已观看 0%，剩余 = 总时长」。
   *
   * 现在会话 id 由客户端自己生成（[newPlaySessionId]），所以**不存在**「空串」这个失败
   * 信号；成败只看请求是否成功返回（[startAcknowledged]）。
   *
   * ## 约束
   *
   * · **不在飞时补**：Start 还在路上（[startRequestedAt] 非 0）就等下一轮，
   *   否则同一场播放会开出两个会话，服务端上就是两条并行的「正在播放」；
   * · **同一条目只补一次**（[sessionRepairFor]）：这是兜底不是重试，变成每 10 秒
   *   一条 Start 比不修更糟。补过之后不再拦 Progress。
   *
   * @return false 仅表示「Start 正在路上，这一轮先别发」
   */
  private suspend fun ensureSession(
    repository: EmbyRepository,
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    positionTicks: Long,
  ): Boolean {
    if (startAcknowledged) return true
    val requestedAt = startRequestedAt
    if (requestedAt > 0L && android.os.SystemClock.elapsedRealtime() - requestedAt < SESSION_START_GRACE_MS) {
      return false
    }
    if (sessionRepairFor == itemId) return true
    sessionRepairFor = itemId
    val session = newPlaySessionId()
    playSessionId = session
    runCatching {
      playSessionId = repository.reportPlaybackStart(
        server = server,
        itemId = itemId,
        positionTicks = positionTicks,
        mediaSourceId = mediaSourceId,
        playSessionId = session,
      )
      startAcknowledged = true
    }.onFailure {
      // 补不上也让本轮 Progress 照发：带会话 id 的 Progress 服务端能按 ItemId + 会话对账，
      // 比整场不上报好得多。
      EmbyLog.warn("补发 Start 失败（item=$itemId），本轮进度照发", it)
    }
    return true
  }

  /**
   * 生成一个播放会话 id。
   *
   * 形态跟 Emby 官方客户端一致：32 位十六进制（去掉 UUID 的连字符）。客户端生成、
   * 三处（Start / Progress / Stopped）共用同一个值，服务端据此把三次请求串成一条会话。
   */
  private fun newPlaySessionId(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").lowercase()

  // ─── mpv 属性读取 ───

  /** Current mpv position in Emby ticks; null means the native property is unavailable. */
  private fun readPositionTicks(): Long? {
    val seconds = runCatching { PlayerLib.getPropertyInt("time-pos") }.getOrNull() ?: return null
    return EmbyTicks.secondsToTicks(seconds.toLong())
  }

  private fun readIsPaused(): Boolean =
    runCatching { PlayerLib.getPropertyBoolean("pause") }.getOrNull() == true

  /**
   * 当前正在播的 Emby 条目 Id。
   *
   * ① **先问宿主**（[idProvider]）：播放页知道「现在是队列里的第几条」，它的
   *   `PlayerActivity.currentEmbyItemId()` 已经把「路径反查 + 按当前下标兜底」两条路
   *   都做了，302 直链与 ISO 本地代理两种 path 都覆盖；
   * ② 再退到从 mpv 的 `path` 反查（播放地址形如
   *   `/emby/Videos/{itemId}/stream?static=true&api_key=...`）。
   *
   * ⚠️ 只有 ② 是不够的：开了「预解析直链（302）」之后 `path` 是网盘 CDN 的真实地址，
   * 没有 `/Videos/{id}/stream` 可反查 ⇒ 恒 null ⇒ [reportProgressOnce] 退回
   * `currentItemId`（永远是起播那一条）⇒ 连播时永远检测不到切集。这个坑播放页早就
   * 踩过并修好了（见 `PlayerActivity.currentEmbyItemId()` 的 ② 号分支），本类 borrow 它。
   */
  private fun readCurrentItemId(): String? {
    // ⚠️ provider 是播放页的 lambda（里面读 `playlistIndex` 与 mpv 的 `path`）。
    //    换片那一瞬间 mpv 正在重建 core，它抛异常是完全可能的 —— 兜住，退回路径反查。
    //    这条兜底本身就是防「切下一集偶发闪退」的（异常冒到 IO 协程根会直接杀进程）。
    val fromHost = idProvider
      ?.let { provider -> runCatching { provider() }.getOrNull() }
      ?.takeIf { it.isNotBlank() }
    val resolved = fromHost
      ?: runCatching { parseItemIdFromPath(PlayerLib.getPropertyString("path")) }.getOrNull()
    // 只认可本次播放列表里的 ID，避免 mpv 自动连播到无关文件时误报
    return resolved?.takeIf { itemIds.contains(it) }
  }

  /**
   * 某条目**实际在播的那个版本**的媒体源 Id；单版本 / 不是本次队列里的条目返回 null。
   *
   * [mediaSourceIds] 与 [itemIds] **同序同长**（启动侧就是这么写的），所以按下标取。
   */
  private fun mediaSourceIdOf(itemId: String): String? {
    val idx = itemIds.indexOf(itemId)
    if (idx < 0) return null
    return mediaSourceIds.getOrNull(idx)?.takeIf { it.isNotBlank() }
  }

  /**
   * 从 mpv 当前播放路径里解析 Emby 媒体 ID；不是 Emby 流（或没解析出）返回 null。
   *
   * `public` 是给播放页用的：它要按「正在播的这一条」构造按版本的进度键，
   * 而**不能用 `playlistIndex` 去索引 intent 里的 itemIds** —— 用户拖动过播放队列后
   * 下标就与 intent 的原始顺序错位了。从路径反查 Id 与队列顺序无关，天然免疫这种错位。
   */
  fun parseItemIdFromPath(path: String?): String? =
    path?.let { ITEM_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
      ?.takeIf { it.isNotBlank() }

  private val ITEM_ID_REGEX = Regex("/Videos/([^/?]+)/stream")

  // ─── Intent extra 键（与 EmbyViewModel 启动播放器时写入的保持一致）───

  const val EXTRA_SERVER_ID = "emby_server_id"
  const val EXTRA_ITEM_IDS = "emby_item_ids"

  /**
   * 当前播放的媒体源 Id，用于进度上报与多版本播放状态。
   *
   * 为什么单独一个 extra 而不是让播放页反查：反查要走
   * `GET /Items/{Id}` 再从 `MediaSources` 里挑，播放页是**每切一集都变**的，
   * 而这个值在启动播放器时就已经知道了。多版本媒体（4K/1080P）选错时，
   * 服务端会把进度落到错误版本，所以必须由启动侧显式传。
   */
  const val EXTRA_MEDIA_SOURCE_ID = "emby_media_source_id"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的多版本名列表（取不到的用空串占位）。
   *
   * 播放页顶栏会在片名后面追加当前这一条的版本名（如「片名 · 1080P HEVC」）——
   * 用户在多版本条目里选了哪个版本，播起来得能看见，否则「切了版本」没有任何反馈。
   * 用列表而不是单值，理由同 [EXTRA_MEDIA_SOURCE_ID]：连播切集后版本会变。
   */
  const val EXTRA_MEDIA_SOURCE_NAMES = "emby_media_source_names"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的「原始剧目顺序号」，编码成
   * `ParentIndexNumber*1000 + IndexNumber`（任一为空则存空串）。
   *
   * ⚠️ 为什么弹幕非要这个：`PlayerActivity` 传给弹幕匹配的是「播放队列下标」，
   * 那是**队列里的第几个**，不是「第几集」。用户从第 5 集点进详情页播放时，
   * 队列只有 1 条 ⇒ 下标 0 ⇒ 会被当成第 1 集去取弹幕。
   * 有了这个字段，弹幕按真实集号匹配（跨季也成立：index 随 Season 累积）。
   *
   * 为什么不用 [EXTRA_ITEM_IDS] 反查服务端：那是异步的，而弹幕加载在切集瞬间就要用，
   * 多一次网络往返只在换集时失败率更高，还会与「预解析直链」的路径反查打架。
   */
  const val EXTRA_EPISODE_NUMBERS = "emby_episode_numbers"

  /**
   * 挂在 PlayerActivity 上的生命周期观察者。
   *
   * 用法：`lifecycle.addObserver(EmbyPlaybackLifecycleObserver(intent))`
   */
  class EmbyPlaybackLifecycleObserver(
    private val intent: Intent?,
  ) : DefaultLifecycleObserver {
    /**
     * 本场播放的令牌。
     *
     * 每个播放页一个。若页面生命周期交错，旧页面必须认出当前会话已被新页面接管，
     * 否则会拿新页面刚写进去的条目去发 Stopped。
     */
    private val token = Any()

    override fun onCreate(owner: LifecycleOwner) {
      start(intent, token)
    }

    /**
     * 退出播放页时**提前**报 Stopped（v227）。
     *
     * ## 为什么不能只靠 [onDestroy]
     *
     * 1. **内核已经拆了**：`PlayerActivity` 的 `super.onDestroy()` 在最后一行，那时
     *    `cleanupMPV()` 已经跑过，读不到实时位置，只能退回「最后一次上报的位置」。
     * 2. **进程随时可能被回收**：返回手势 / 从最近任务划掉之后，系统可以在 `onDestroy`
     *    刚结束时就杀掉进程，那条才 `launch` 出去的请求根本来不及发出。
     *
     * `onStop` 比 `onDestroy` 早一个生命周期，是「用户真的要走了」与「按 Home / 进小窗 /
     * 转屏」的分界：只有 Activity 已经在 finish 时才报，后三种 `isFinishing` 为 false，
     * 报上去反而会把「后台继续播放」这条会话掐掉。
     *
     * 与 [onDestroy] 不冲突：第一次调完 `sessionToken` 已置空，第二次在令牌那一步直接返回。
     */
    override fun onStop(owner: LifecycleOwner) {
      val activity = owner as? Activity ?: return
      if (activity.isFinishing) stop(token)
    }

    override fun onDestroy(owner: LifecycleOwner) {
      stop(token)
    }
  }
}

/**
 * 供 EmbyViewModel 调用：写入上报所需的 extra。
 *
 * @param mediaSourceIds 与 [itemIds] **同序同长**的媒体源 Id 列表；
 *   取不到的用空串占位，保证切集后仍可按 `playlistIndex` 对齐。
 */
internal fun Intent.putEmbyPlaybackExtras(
  server: EmbyServer,
  itemIds: List<String>,
  mediaSourceIds: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的版本显示名（空串 = 这一条没有多版本 / 没选版本）。
   * 播放页顶栏据此在片名后追加「· <版本名>」。
   */
  mediaSourceNames: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的「S*E*」原始序号，见 [EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS]。
   * 非剧集（电影 / 本地文件）传空表即可 —— 播放页拿不到就退回队列下标。
   */
  episodeNumbers: List<String> = emptyList(),
) {
  putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, server.id)
  putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(itemIds))
  if (episodeNumbers.isNotEmpty()) {
    putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS, ArrayList(episodeNumbers))
  }
  if (mediaSourceIds.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID,
      ArrayList(mediaSourceIds),
    )
  }
  if (mediaSourceNames.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_NAMES,
      ArrayList(mediaSourceNames),
    )
  }
}
