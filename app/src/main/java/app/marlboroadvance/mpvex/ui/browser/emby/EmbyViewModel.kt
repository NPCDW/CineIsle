package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyEpisodeOperationException
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfigurationRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyItemsResult
import app.marlboroadvance.mpvex.domain.emby.EmbyRecentPlays
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyScanChunk
import app.marlboroadvance.mpvex.domain.emby.EmbyScanQuery
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.StreamProbe
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerHandoff
import app.marlboroadvance.mpvex.ui.player.PlayerHttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject

/** 全库搜索没选任何筛选时的默认类型集：只保留可播放的视频本体 */
private const val PERSON_TYPE = "Person"

private val GLOBAL_SEARCH_DEFAULT_TYPES =
  listOf("Movie", "Series", "Episode", "Video", "MusicVideo")

/**
 * **自身没有视频文件**的条目类型 —— 对它们请求 `/Videos/{id}/stream` 必崩。
 *
 * `Series` / `Season` 是容器（内容在子条目上）；`BoxSet` / `Folder` / `CollectionFolder`
 * 是目录；相册 / 歌单 / 音乐人同理。缺了这条判据的表现就是「点播放直接闪退」。
 */
private val CONTAINER_TYPES = setOf(
  "Series",
  "Season",
  "BoxSet",
  "Folder",
  "CollectionFolder",
  "PhotoAlbum",
  "MusicAlbum",
  "MusicArtist",
  "Book",
  "AudioBook",
)

private const val EPISODE_PLAYLIST_PAGE_SIZE = 100
private const val EPISODE_PLAYLIST_MAX_PAGES = 1_000
private const val EPISODE_PLAYLIST_FIRST_PAGE_WARNING = "获取整季分集失败，将仅播放当前集"
private const val EPISODE_PLAYLIST_PARTIAL_WARNING = "分集列表加载不完整，将播放已加载的分集"

internal data class EpisodePlaylistBuildResult(
  val items: List<EmbyItem>,
  val warning: String? = null,
)

/** Auto mode has no trustworthy source until fresh PlaybackInfo is selected by the loopback backend. */
internal fun requireExplicitPlaybackSource(
  item: EmbyItem,
  mediaSourceId: String?,
  url: String? = null,
) {
  if (mediaSourceId != null) EmbyMediaSourcePolicy.requirePlayableSource(item, mediaSourceId, url)
}

/** Pure pagination core used by playback and JVM tests. Offsets always follow raw server consumption. */
internal suspend fun collectEpisodePlaylist(
  current: EmbyItem,
  completeSeason: Boolean,
  pageSize: Int = EPISODE_PLAYLIST_PAGE_SIZE,
  maxPages: Int = EPISODE_PLAYLIST_MAX_PAGES,
  loadPage: suspend (startIndex: Int, limit: Int) -> EmbyItemsResult,
): EpisodePlaylistBuildResult {
  require(pageSize > 0 && maxPages > 0)
  val currentId = current.Id?.takeIf { it.isNotBlank() } ?: return EpisodePlaylistBuildResult(listOf(current))
  val ordered = ArrayList<EmbyItem>()
  val seenIds = HashSet<String>()
  val pageSignatures = HashSet<List<String?>>()
  var startIndex = 0
  var totalCount = 0
  var pageCount = 0
  var exhausted = false
  var warning: String? = null

  while (pageCount < maxPages) {
    val page = try {
      loadPage(startIndex, pageSize)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      warning = if (pageCount == 0) EPISODE_PLAYLIST_FIRST_PAGE_WARNING else EPISODE_PLAYLIST_PARTIAL_WARNING
      break
    }
    pageCount++
    if (page.TotalRecordCount > 0) totalCount = maxOf(totalCount, page.TotalRecordCount)
    val signature = page.Items.map { it.Id }
    if (!pageSignatures.add(signature)) {
      warning = EPISODE_PLAYLIST_PARTIAL_WARNING
      break
    }
    page.Items.forEach { episode ->
      val id = episode.Id?.takeIf { it.isNotBlank() } ?: return@forEach
      if (EmbyItemPolicy.isSupported(episode) && seenIds.add(id)) ordered += episode
    }

    val consumed = page.consumedCount
    if (consumed <= 0) {
      exhausted = true
      break
    }
    val next = startIndex.toLong() + consumed.toLong()
    if (next > Int.MAX_VALUE || next <= startIndex) {
      warning = EPISODE_PLAYLIST_PARTIAL_WARNING
      break
    }
    startIndex = next.toInt()
    if (totalCount > 0 && startIndex >= totalCount) {
      exhausted = true
      break
    }
  }
  if (!exhausted && warning == null && pageCount >= maxPages) warning = EPISODE_PLAYLIST_PARTIAL_WARNING

  val currentIndex = ordered.indexOfFirst { it.Id == currentId }
  if (currentIndex >= 0) ordered[currentIndex] = current else ordered += current
  val result = if (completeSeason) ordered else {
    val index = ordered.indexOfFirst { it.Id == currentId }
    if (index >= 0) ordered.drop(index) else listOf(current)
  }
  return EpisodePlaylistBuildResult(result, warning)
}

/**
 * Emby 模块共享 ViewModel。
 *
 * 负责：当前服务器、媒体库/继续观看/最新加入数据加载、收藏、删除、播放启动。
 * [EmbyRepository] 是 Koin 单例，因此多个页面拿到的当前服务器是一致的。
 */
class EmbyViewModel(application: Application, private val boundServer: EmbyServer? = null) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val serverRepository by inject<app.marlboroadvance.mpvex.database.repository.EmbyServerRepository>(
    app.marlboroadvance.mpvex.database.repository.EmbyServerRepository::class.java,
  )
  private val loopback by inject<LoopbackPlaybackServer>(LoopbackPlaybackServer::class.java)
  private val browserPreferences by inject<BrowserPreferences>(BrowserPreferences::class.java)
  val servers: StateFlow<List<EmbyServer>> = repository.servers
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val serverLoadState: StateFlow<EmbyServerLoadState> = repository.loadState
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmbyServerLoadState.Loading)
  private val _managementError = MutableStateFlow<String?>(null)
  val managementError: StateFlow<String?> = _managementError.asStateFlow()

  fun refreshServers() {
    viewModelScope.launch {
      try {
        repository.refreshServers()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        _managementError.value = safeServerError(failure)
      }
    }
  }

  private val boundRef = boundServer?.let(EmbyServerRef::from)
  private val boundCurrent = MutableStateFlow(boundServer?.let {
    runCatching { repository.sessionServer(it) }.getOrNull()
  })
  val currentServer: StateFlow<EmbyServer?> = if (boundServer == null) repository.currentServer else boundCurrent.asStateFlow()

  init {
    if (boundServer != null) viewModelScope.launch {
      kotlinx.coroutines.flow.combine(serverRepository.loadState, serverRepository.sourceChanged) { state, source -> state to source }
        .collect { (state, source) ->
          // A transient refresh failure hides the entry, but must not erase its visit or session.
          boundCurrent.value = nextBoundEmbyServer(
            boundServer, boundCurrent.value, state, source, serverRepository::isCurrent,
          ) { original -> runCatching { repository.sessionServer(original) }.getOrNull() }
        }
    }
  }

  private fun requireBoundServer(server: EmbyServer) {
    if (boundRef != null && (boundCurrent.value == null || !boundRef.matches(server) ||
        resolveEmbyServerBinding(boundRef, serverRepository.loadState.value, serverRepository::isCurrent) == null)) {
      throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    }
    if (boundServer != null) repository.sessionServer(boundServer)
  }

  private val _libraries = MutableStateFlow<List<EmbyItem>>(emptyList())
  val libraries: StateFlow<List<EmbyItem>> = _libraries.asStateFlow()

  private val _resumeItems = MutableStateFlow<List<EmbyItem>>(emptyList())
  val resumeItems: StateFlow<List<EmbyItem>> = _resumeItems.asStateFlow()

  private val _latestItems = MutableStateFlow<List<EmbyItem>>(emptyList())
  val latestItems: StateFlow<List<EmbyItem>> = _latestItems.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  /**
   * 首页数据加载完成过几次（每次 [loadHome] 结束 +1，含失败与空服务器分支）。
   *
   * 用途只有一个：「随便看看」那一行横滑要排在首页三请求**之后**再拉
   * （见 [awaitHomeLoaded]）。它不驱动任何 UI 状态，所以做成计数器而不是
   * Boolean —— 下拉刷新后不能再变回 false，否则那一行会被反复重拉。
   */
  private val _homeLoads = MutableStateFlow(0)

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private fun safeServerError(failure: Throwable): String =
    getApplication<Application>().getString(embyServerErrorResource(failure))

  fun switchServer(server: EmbyServer) {
    if (boundRef != null) return
    repository.selectServerAsUser(server)
    viewModelScope.launch { refreshHome() }
  }

  suspend fun relogin(server: EmbyServer): EmbyServer? {
    try {
      return repository.relogin(server)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      _error.value = safeServerError(failure)
      return null
    }
  }

  // ==================== 数据加载 ====================

  /**
   * 首页加载的**代数**：只有最新一代允许写状态。
   *
   * 为什么非要这个：`OkHttp` 的阻塞调用**不会**被协程取消真正掐断，旧请求仍会在
   * 十几秒后带着超时结果回来。它要是还能写 `_libraries` / `_error`，
   * 就会把新一代刚拉到的数据冲成「空列表 + 加载失败」—— 表现就是
   * 「切完地址首页刷新了一下，又变回空的」。
   */
  @Volatile
  private var homeGeneration = 0

  /** 上一次首页加载的协程 */
  private var homeJob: Job? = null

  /** 加载首页所需的全部数据：媒体库 + 继续观看 + 最新加入 */
  fun refreshHome() {
    // ⚠️ 显式取消上一次：它很可能正卡在**已经切走**的旧地址上。
    // 光发新请求不够 —— 旧的那条要等 socket 超时（15s 级）才结束，期间
    // 骨架屏不会消失、用户看到的就是「切了地址还是拉不出来」。
    val gen = ++homeGeneration
    homeJob?.cancel()
    homeJob = viewModelScope.launch { loadHome(gen) }
  }

  /**
   * [refreshHome] 的可等待版本：下拉刷新要用它 —— 刷新指示器得等这批请求真的回来才收起，
   * 否则手指刚松开动画就没了，用户看不出到底刷没刷。
   */
  suspend fun refreshHomeAndWait() {
    val gen = ++homeGeneration
    homeJob?.cancel()
    loadHome(gen)
  }

  /** 上一次同步「继续观看」的时刻（`elapsedRealtime`），用于 [refreshResumeQuietly] 去抖 */
  @Volatile
  private var lastResumeSyncAt = 0L

  /**
   * 只重拉「继续观看」，不动媒体库与最新加入（首页 onResume 用）。
   *
   * ## 为什么要单独一条路
   *
   * 播放器是**另一个 Activity**，首页这层 Composable 在播放期间不销毁 ⇒ 从播放器退回来时
   * `LaunchedEffect` 不会重跑，而播放进度是**异步**落到服务器的，于是「继续观看」就停在
   * 进去之前那条旧快照上。整页走 [refreshHome] 能解决，代价是骨架屏闪一下、还得多发
   * `/Views` 与 `/Items/Latest` 两发大请求 —— 只为了一块几十条的小数据不值得。
   *
   * @param minIntervalMs 去抖：刚才已经同步过就不再打。详情页返回、播放器返回、切 tab
   *   回来都可能连着触发 ON_RESUME，没必要每次都问服务器。
   */
  fun refreshResumeQuietly(minIntervalMs: Long = 1_500L) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastResumeSyncAt < minIntervalMs) return
    lastResumeSyncAt = now
    viewModelScope.launch {
      // 记下代：拉到一半用户切了服务器，这份数据属于上一代，写下去会冲掉新的
      val gen = homeGeneration
      val server = runCatching {
        currentServerOrAwait()
      }.getOrNull() ?: return@launch
      val resume = runCatching { fetchResume(server, limit = 20) }.getOrNull()
        ?: return@launch
      if (gen != homeGeneration) return@launch
      // ⚠️ 静默：失败不写 `_error`。首页已经有内容了，为「续播没刷新出来」把整页
      //    标成加载失败是本末倒置（同 loadHome 里那两发失败的处理）。
      _resumeItems.value = resume
      lastResumeSyncAt = android.os.SystemClock.elapsedRealtime()
    }
  }

  /**
   * 首页数据加载。
   *
   * ## ⚠️ 三发**各自独立**，不要合并成一个 `runCatching`（这条是用户真机报出来的）
   *
   * 原来三句是串在同一个 `runCatching` 里的：`getVirtualFolders` 一抛异常，
   * 后面两发根本不会发，于是「继续观看」「最新加入」也跟着一起消失 ——
   * 服务端只要有一小部分查询能力出问题（比如它自己的 SQLite 库报错），
   * 整个首页就全空了。
   *
   * 现在每一发自己 `runCatching`：
   * - **媒体库**挂了才算首页失败（没有库就确实什么也点不了，进错误态）；
   * - 继续观看 / 最新加入挂了就只是那一块空着，其余照常显示。
   *
   * 顺带说明：`SQLitePCL.pretty.SQLiteException` 是**服务端**（.NET）的 SQLite
   * 驱动抛的，不是本机数据库。本项目这三个请求的参数一个都没改过，
   * 所以真出现这个错要去查服务端（库被锁 / 磁盘满 / 库损坏），不是改客户端能绕过的。
   *
   * ## ⭐ 三发**并行**（2026-10-15，大媒体库下首页慢的修复）
   *
   * 原来是串行的：媒体库 → 继续观看 → 最新加入 一发一发等，首页骨架屏要转到
   * **三者耗时之和**。十万级条目的库上，每一发都可能是几百毫秒到数秒（尤其是
   * `/Items/Latest` 不带 `ParentId` 要跨库扫，`SortBy=Random` 更是没法走索引），
   * 加起来就是用户看到的「打开首页特别慢，有时候还超时」。
   *
   * 这三发互不依赖（各查各的维度），所以改成 `coroutineScope { async {} }` 同时发，
   * 首页耗时从「和」变成「最大者」。`isLoading` 仍然等三发全部结束才置 false ——
   * 语义不变，只是不再把时间白白串起来。
   *
   * ⚠️ 并行**不能**改掉上面那条铁律：三发依旧是各自独立的 `runCatching`，
   * 一发放鸽子不能拖垮另外两块。
   */
  private suspend fun loadHome(gen: Int) {
    _isLoading.value = true
    _error.value = null
    try {
      // 冷启动时服务器是异步恢复的，这里必须等它恢复完，否则会误判成「没有服务器」
      val server = currentServerOrAwait()
      // 等服务器的这一段也可能被新的加载取代（切服务器 / 切入口），过期就别往下走
      if (gen != homeGeneration) return
      if (server == null) {
        _libraries.value = emptyList()
        _resumeItems.value = emptyList()
        _latestItems.value = emptyList()
        return
      }

      coroutineScope {
        val libsDeferred = async { runCatching { repository.getVirtualFolders(server) } }
        val resumeDeferred = async { runCatching { fetchResume(server, limit = 20) } }
        val latestDeferred = async { runCatching { repository.getLatestItems(server, limit = 24) } }

        val libs = libsDeferred.await()
        // ⚠️ 每一处写入前都要判代：旧代（已经切了地址的那一次）拿回来的多半是超时，
        // 写下去会把新代刚拉到的数据冲成「空 + 加载失败」。
        if (gen == homeGeneration) {
          _libraries.value = libs.getOrElse { e ->
            _error.value = safeServerError(e)
            emptyList()
          }
        }

        // 下面两发失败只留空列表，不碰 _error —— 首页已经有内容了，
        // 为「继续观看没加载出来」把整页标成失败是本末倒置。
        val resume = resumeDeferred.await()
        if (gen == homeGeneration) {
          _resumeItems.value = resume.getOrElse { emptyList() }
          // 记一笔：首页刚整页拉过，紧接着那次 ON_RESUME 不必再问一遍服务器
          lastResumeSyncAt = android.os.SystemClock.elapsedRealtime()
        }
        val latest = latestDeferred.await()
        if (gen == homeGeneration) {
          _latestItems.value = latest.getOrElse { emptyList() }
        }

      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      if (gen == homeGeneration) _error.value = safeServerError(failure)
    } finally {
      // 只有最新一代能收尾：旧代把 isLoading 置 false 会让新代的骨架屏提前消失
      if (gen == homeGeneration) {
        _isLoading.value = false
        _homeLoads.value = _homeLoads.value + 1
      }
    }
  }

  /**
   * 等首页数据至少加载过一次（含失败 / 没有服务器的情况 —— 那些也算「加载完了」，
   * 不然首页出不来时，下面那些区块会跟着一起永远不出现）。
   *
   * 给「随便看看」那一行横滑用：它在首页最底部，用户得滚好久才看得见，
   * 没道理跟「媒体库 / 继续观看 / 最新加入」那三发抢服务端。
   * 十万级条目的库上每一发都可能是数百毫秒到数秒，四发同时打过去只会让首屏更晚出来。
   *
   * @param timeoutMs 最长等多久。**必须有**：首页三发万一卡在服务端（读超时是 60s，
   *   还会重试），不能连带这一行也永远不出现 —— 到点就放行，让它自己发。
   */
  suspend fun awaitHomeLoaded(timeoutMs: Long = HOME_LOAD_WAIT_MS) {
    if (_homeLoads.value > 0) return
    withTimeoutOrNull(timeoutMs) { _homeLoads.first { it > 0 } }
  }

  /**
   * 取当前服务器；若启动恢复尚未完成则先等待。
   *
   * 各个二级页面（媒体库/收藏/历史/详情）的首屏加载都应使用它，
   * 直接用 [currentServer] 的 value 会在冷启动时拿到 null 而静默不加载。
   */
  suspend fun currentServerOrAwait(): EmbyServer? {
    if (boundRef == null) return repository.ensureLoggedIn(repository.awaitCurrentServer())
    val target = boundCurrent.value ?: return null
    requireBoundServer(target)
    val loggedIn = repository.ensureLoggedIn(target)
    requireBoundServer(target)
    boundCurrent.value = loggedIn
    return loggedIn
  }

  /** 加载指定父级下的媒体（供库浏览页使用） */
  suspend fun loadItems(
    server: EmbyServer,
    parentId: String?,
    includeItemTypes: List<String>? = null,
    filters: List<String>? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    excludeItemTypes: List<String>? = null,
    /** 类型筛选，多选取并集（Emby 的 Genres 参数） */
    genres: List<String>? = null,
    /** 发行年份筛选，多选取并集 */
    years: List<Int>? = null,
    /** 最低社区评分，例如 7f 表示只要 7 分以上 */
    minCommunityRating: Float? = null,
    /** 标签筛选，多选取并集 */
    tags: List<String>? = null,
    /** 类型 / 标签的 Id 筛选（逗号分隔）：优先于按名字的 [genres] / [tags] */
    genreIds: List<String>? = null,
    tagIds: List<String>? = null,
    /** 官方分级筛选，多选取并集 */
    officialRatings: List<String>? = null,
    /** true 只返回收藏，false 只返回未收藏，null 不限 */
    isFavorite: Boolean? = null,
    /** 只返回指定 MediaType（Video / Audio / Photo / Book）的条目；null 不限 */
    mediaTypes: List<String>? = null,
    /** false = 只要能直接播放的媒体本体，容器（Folder / Series / Season / BoxSet）全不要 */
    isFolder: Boolean? = null,
    /** 演员 / 导演的 PersonId 列表，多选取并集 */
    personIds: List<String>? = null,
    /** true 只要已看，false 只要未看，null 不限 */
    isPlayed: Boolean? = null,
    /** true 只要高清，false 只要标清 */
    isHD: Boolean? = null,
    /** true 只要 3D，false 排除 3D */
    is3D: Boolean? = null,
    /** true 只要有字幕，false 只要没字幕 */
    hasSubtitles: Boolean? = null,
    /** 工作室 Id 列表，多选取并集 */
    studioIds: List<String>? = null,
  ): EmbyItemsPage {
    val result = repository.getItems(
      server = server,
      parentId = parentId,
      sortBy = sortBy,
      sortOrder = sortOrder,
      filters = filters,
      includeItemTypes = includeItemTypes,
      startIndex = startIndex,
      limit = limit,
      recursive = recursive,
      excludeItemTypes = excludeItemTypes,
      genres = genres,
      years = years,
      minCommunityRating = minCommunityRating,
      tags = tags,
      genreIds = genreIds,
      tagIds = tagIds,
      officialRatings = officialRatings,
      isFavorite = isFavorite,
      mediaTypes = mediaTypes,
      isFolder = isFolder,
      personIds = personIds,
      isPlayed = isPlayed,
      isHD = isHD,
      is3D = is3D,
      hasSubtitles = hasSubtitles,
      studioIds = studioIds,
    )
    return EmbyItemsPage(result.Items, result.TotalRecordCount, result.consumedCount)
  }

  /**
   * 全量扫描：客户端筛选（如按路径判断的「中文字幕」）需要遍历整个库时用。
   *
   * 逐页拉完 [query] 命中的全部条目，每页回调一次 [onChunk] —— 回调在 IO 线程上执行，
   * 里面做正则过滤这类纯 CPU 活不会卡 UI；写 Compose 状态记得切回主线程。
   * 调用方所在协程被取消（换筛选条件 / 退出页面）时，扫描会在一页之内停下。
   */
  suspend fun scanItems(
    server: EmbyServer,
    query: EmbyScanQuery,
    onChunk: suspend (EmbyScanChunk) -> Unit,
  ): Int = repository.scanItems(server, query, onChunk = onChunk)

  /** 取某媒体库的筛选可选项（类型 / 标签 / 年份 / 分级），供筛选面板使用。 */
  suspend fun loadFilterOptions(
    server: EmbyServer,
    parentId: String?,
  ): EmbyFilterOptions = withContext(Dispatchers.IO) {
    runCatching { repository.getFilterOptions(server, parentId) }
      .getOrDefault(EmbyFilterOptions())
  }

  /**
   * 季列表。
   *
   * [limit] 默认给一个上限（清单 1.1）：原先不传 Limit，服务端一次把全部季连同
   * People / Studios / MediaSources 一起返回，长剧（几十季）白拉一大堆字段。
   * 季数超过上限的剧（几乎没有）会在日志里留痕，不静默截断。
   */
  suspend fun loadSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int = 50,
  ): EmbyItemsPage {
    val result = repository.getSeasons(server, seriesId, startIndex, limit)
    if (result.TotalRecordCount > startIndex + result.consumedCount) {
      android.util.Log.w(
        "EmbyViewModel",
        "季列表被分页截断：共 ${result.TotalRecordCount} 季，本次只取到 ${result.Items.size}",
      )
    }
    return EmbyItemsPage(result.Items, result.TotalRecordCount, result.consumedCount)
  }

  /** 某一季下的剧集列表（分页参数见 [loadSeasons]） */
  suspend fun loadEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String?,
    startIndex: Int = 0,
    limit: Int = EPISODE_PAGE_SIZE,
  ): EmbyItemsPage {
    val result = repository.getEpisodes(server, seriesId, seasonId, startIndex, limit)
    return EmbyItemsPage(result.Items, result.TotalRecordCount, result.consumedCount)
  }

  /**
   * 收藏列表的一页。
   *
   * [sortBy] / [sortOrder] 必须与收藏页当前的排序口径**一致**：分页是按服务端排序切的，
   * 本地再排一遍无法把还没加载的条目补进来（详见 EmbyClient.getFavoriteItems 的 KDoc）。
   * 传 null 走服务端默认（`SortName Ascending`），供不关心顺序的调用方沿用。
   */
  suspend fun loadFavorites(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
    includeItemTypes: List<String>? = null,
  ): EmbyItemsPage {
    val result = repository.getFavoriteItems(server, startIndex, limit, sortBy, sortOrder, includeItemTypes)
    return EmbyItemsPage(result.Items, result.TotalRecordCount, result.consumedCount)
  }

  /**
   * 本机账本里记过的「收藏时刻」（itemId → epoch millis）。
   *
   * 收藏页的「收藏时间」排序用它。读失败 / 没有记录都返回空表 —— 排序会退回
   * 入库时间当近似值，不影响页面可用。
   */
  /**
   * 收藏的演员列表（收藏页「演员」tab）。
   *
   * 按服务器做了一份进程内缓存：收藏页在 影片/演员 两个 tab 之间来回切时
   * 不重复发请求（[EmbyClient.getFavoritePersons] 是全量拉一遍再过滤，开销不小）。
   *
   * 缓存失效：演员被收藏 / 取消收藏时（长按菜单、演员作品页红心）必须调
   * [invalidateFavoritePersonsCache]，否则回到收藏页看到的还是旧列表。
   * 另外 60 秒自动过期 —— 兜底 App 其它入口（比如以后加的）改了收藏状态却忘了通知。
   */
  suspend fun loadFavoritePersons(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoritePersonsCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoritePersons(server) }.getOrDefault(emptyList())
    }
    favoritePersonsCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏演员列表的进程内缓存：serverId → (缓存时间, 名单) */
  private var favoritePersonsCache: Triple<Long, Long, List<EmbyItem>>? = null

  /** 演员收藏状态发生变化时调用：把缓存作废，收藏页下次进来重新拉 */
  fun invalidateFavoritePersonsCache() {
    favoritePersonsCache = null
  }

  /**
   * 收藏的**类型**（Genre）列表（收藏页「类型」tab）。
   *
   * 口径与 [loadFavoritePersons] 一致：按服务器做进程内缓存 + 60 秒自动过期，
   * 收藏页在几个 tab 之间来回切时不重复发请求。收藏状态变化时调
   * [invalidateFavoriteFacetCache] 作废。
   */
  suspend fun loadFavoriteGenres(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoriteGenresCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoriteGenres(server).Items }.getOrDefault(emptyList())
    }
    favoriteGenresCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏**工作室**（Studio）列表，口径同 [loadFavoriteGenres]。 */
  suspend fun loadFavoriteStudios(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoriteStudiosCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoriteStudios(server).Items }.getOrDefault(emptyList())
    }
    favoriteStudiosCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏类型 / 工作室列表的进程内缓存：serverId → (缓存时间, 名单) */
  private var favoriteGenresCache: Triple<Long, Long, List<EmbyItem>>? = null
  private var favoriteStudiosCache: Triple<Long, Long, List<EmbyItem>>? = null

  /** 类型 / 工作室收藏状态变化时调用：把缓存作废，收藏页下次进来重新拉 */
  fun invalidateFavoriteFacetCache() {
    favoriteGenresCache = null
    favoriteStudiosCache = null
  }

  /**
   * 取「继续观看」= 服务端结果 + 本地最近播放账本。
   *
   * ## 为什么要另加账本（v231）
   *
   * 服务端 `Items/Resume` **是按 `LastPlayedDate` 降序排的**（实测首页前 8 条时间戳严格递减），
   * 所以排序本身没问题；问题在**写入端**：条目只有在那次播放刷新了 `LastPlayedDate`
   * 才会被顶到前面，而我们的上报过去只写了播放位置（详情页有进度、位次不动）。
   * 实测有一条卡的正是第 21 位 —— 首页只要 20 条，差一位就整条消失。
   *
   * 治本那条已经在做（上报改用 PlaybackInfo 的服务端会话），但服务端「什么时候盖时间戳」
   * 不是我们能保证的，所以客户端再兜一层：最近播过的、服务端那批结果里没有的，
   * 用一次 `Ids=` 批量请求补回来，并按本地时间排到最前。
   *
   * 任何一步失败都退回纯服务端结果 —— 这条兜底绝不能让首页变空或报错。
   */
  private suspend fun fetchResume(server: EmbyServer, limit: Int): List<EmbyItem> {
    val fromServer = repository.getResumeItems(server, limit)
    return mergeRecentPlays(server, fromServer, limit)
  }

  private suspend fun mergeRecentPlays(
    server: EmbyServer,
    serverItems: List<EmbyItem>,
    limit: Int,
  ): List<EmbyItem> {
    if (limit <= 0) return serverItems
    return runCatching {
      val recent = EmbyRecentPlays.recentIds(
        raw = browserPreferences.embyRecentPlays(server.id).get(),
        limit = RECENT_MERGE_LIMIT,
      )
      if (recent.isEmpty()) return@runCatching serverItems
      val present = serverItems.mapNotNull { it.Id }.toSet()
      // 只补「账本里有、服务端这批里没有」的，一次 Ids= 请求取完
      val missing = recent.filterNot { it in present }.take(RECENT_FETCH_MAX)
      val fetched =
        if (missing.isEmpty()) emptyList()
        else repository.getItemsByIds(server, missing).filter { isResumableItem(it) }
      if (fetched.isEmpty() && recent.none { it in present }) return@runCatching serverItems
      val pool = (serverItems + fetched).mapNotNull { item -> item.Id?.let { it to item } }.toMap()
      // 账本顺序 = 最近在前；已看完（位置归零 / 标记已看）的账本条目不算数
      val ordered = recent.mapNotNull { pool[it] }.filter { isResumableItem(it) }
      val orderedIds = ordered.mapNotNull { it.Id }.toSet()
      val rest = serverItems.filterNot { item -> item.Id != null && item.Id in orderedIds }
      (ordered + rest).distinctBy { it.Id ?: it.Name ?: "" }.take(limit)
    }.getOrElse { serverItems }
  }

  /** 还能续播：有非零播放位置且没被标记已看 */
  private fun isResumableItem(item: EmbyItem): Boolean {
    val data = item.UserData ?: return false
    return !data.Played && data.PlaybackPositionTicks > 0
  }

  suspend fun loadRandom(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): List<EmbyItem> = repository.getRandomItems(server, parentId, includeItemTypes, limit, isFavorite).Items

  suspend fun loadItemDetail(server: EmbyServer, itemId: String): EmbyItem =
    repository.getItem(server, itemId)

  suspend fun setMediaFavorite(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    episodeRequest(server, "媒体收藏操作失败，请刷新核对状态") {
      repository.setMediaFavorite(server, item, enabled)
    }

  suspend fun setMediaPlayed(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    episodeRequest(server, "媒体已看操作失败，请刷新核对状态") {
      repository.setMediaPlayed(server, item, enabled)
    }

  suspend fun loadEpisodePlaybackConfiguration(server: EmbyServer): PlaybackConfiguration =
    episodeRequest(server, "播放配置加载失败，请重试") {
      val configurations = org.koin.java.KoinJavaComponent.get<PlaybackConfigurationRepository>(PlaybackConfigurationRepository::class.java)
      val target = repository.sessionServer(server)
      val configuration = configurations.snapshot(target)
      if (!configurations.isCurrent(configuration) || configuration.serverUuid != target.remoteId ||
        configuration.source.sourceKey != target.sourceKey || configuration.source.revision != target.sourceRevision) {
        throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      }
      configuration
    }

  private suspend fun <T> episodeRequest(server: EmbyServer, fallback: String, action: suspend () -> T): T {
    try {
      requireBoundServer(server)
      repository.sessionServer(server)
      val result = action()
      currentCoroutineContext().ensureActive()
      requireBoundServer(server)
      repository.sessionServer(server)
      _error.value = null
      return result
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      val message = when (failure) {
        is EmbyEpisodeOperationException -> failure.message ?: fallback
        is PostgresException -> safeServerError(failure)
        else -> fallback
      }
      _error.value = message
      throw EmbyEpisodeOperationException(message)
    }
  }

  /**
   * 库内搜索。
   *
   * [sortBy] / [sortOrder] 由媒体库工具行上的排序按钮给出：Emby 允许
   * SearchTerm 与 SortBy 组合，所以搜索完再切排序能真的改变结果顺序。
   * `sortBy = "Random"` 就是「在搜索结果里随机播」用的。
   */
  suspend fun search(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>? = null,
    sortBy: String? = "SortName",
    sortOrder: String? = null,
    limit: Int = 60,
    /**
     * 搜索范围：null = 全服（默认，既有调用不受影响）；
     * 非 null 时只在该目录下找，配合 [recursive] = false 即「仅本目录」。
     */
    parentId: String? = null,
    recursive: Boolean = true,
  ): List<EmbyItem> {
    // 「仅本目录」下不查演员：Person 是全局实体，并不挂任何目录下。
    // 把它一起返回等于绕过了范围限定 —— 搜索框明写着「仅本目录」却蹦出全库演员。
    val scoped = parentId != null
    // 演员必须走 /Persons：`/Users/{id}/Items` 不返回 Person，
    // 勾了「演员」筛选却搜不到人就是因为原先二者混在同一条请求里。
    val persons = if (scoped) emptyList() else searchPersons(server, term, limit, itemTypes)
    val mediaTypes = itemTypes?.filter { it != PERSON_TYPE }?.takeIf { it.isNotEmpty() }
    // 只勾了「演员」：媒体那一路就不用查了
    if (itemTypes != null && mediaTypes == null) return persons
    val media = runCatching {
      repository.searchItems(
        server = server,
        term = term,
        includeItemTypes = mediaTypes,
        limit = limit,
        sortBy = sortBy,
        sortOrder = sortOrder,
        parentId = parentId,
        recursive = recursive,
      ).Items
    }.getOrDefault(emptyList())
    return persons + media
  }

  /**
   * 演员走 `/Persons`：勾了「演员」筛选时才发这一路，否则不打扰服务端。
   *
   * 两层兜底：
   * 1. 先按名字问服务端 `/Persons?SearchTerm`，有结果直接用 —— 这是主路径，
   *    快且准，官方 Emby 与绝大多数兼容服务端都支持；
   * 2. 拿不到就退回「拉一份全量演员名单、在本地按名字做包含匹配」。
   *    这一层是给「服务端不认 `SearchTerm` 或匹配口径对不上」的兼容层准备的保险。
   *    全量名单按服务器缓存（见 [actorsCache]），所以用户一个字一个字地打，
   *    也只会产生一次全量请求。
   *
   * 注意：媒体库内搜不到演员**另有原因**（列表那层「是否可播放」的本地兜底把
   * Person 剔掉了，见 EmbyLibraryScreen 的 visibleItems），不在这里。
   */
  private suspend fun searchPersons(
    server: EmbyServer,
    term: String,
    limit: Int,
    itemTypes: List<String>?,
  ): List<EmbyItem> {
    if (itemTypes != null && PERSON_TYPE !in itemTypes) return emptyList()
    val direct = runCatching { repository.searchPersons(server, term, null, limit) }
      .getOrDefault(emptyList())
    if (direct.isNotEmpty()) return direct
    return allActors(server)
      .filter { person -> person.Name?.contains(term, ignoreCase = true) == true }
      .take(limit)
  }

  /** 全量演员名单的缓存：serverId → 名单。同一台服务器只拉一次。 */
  private var actorsCache: Pair<Long, List<EmbyItem>>? = null

  private suspend fun allActors(server: EmbyServer): List<EmbyItem> {
    actorsCache?.let { (cachedId, list) -> if (cachedId == server.id) return list }
    val list = withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getActors(server, null) }.getOrDefault(emptyList())
    }
    if (list.isNotEmpty()) actorsCache = server.id to list
    return list
  }

  /**
   * 首页的「全库搜索」：不传 ParentId，Emby 会跨所有媒体库检索。
   *
   * 和库内搜索 [search] 用的是同一个 /Items?SearchTerm 接口，区别只在两点：
   * 不传 ParentId（所以覆盖全部库），以及**默认**限定只看可播放类型
   * ——否则文件夹、合集这些容器会混进结果里，点进去还要再下钻一层。
   *
   * [itemTypes] 由搜索框下方的筛选条给出：用户显式选了「合集」「演员」这类时，
   * 就按他选的类型查，不再套用上面的默认值。
   *
   * ⚠️ **这里刻意不做分页**（清单 1.1 的例外）：结果由**两条独立流**拼成 ——
   * 人物走 `/Persons`、媒体走 `/Items`，两者的 offset 空间互不相通，
   * 拼起来的混合列表根本没有「下一页」这个概念（要分页得维护两套游标再归并，
   * 复杂度远大于收益）。所以这里取一个偏大的上限，[GLOBAL_SEARCH_LIMIT]，
   * 并在 [EmbyGlobalSearchResult.totalCount] 里报服务端总数，让 UI 老实告诉用户
   * 「只显示前 N 条」，而不是让人以为搜全了。
   */
  suspend fun searchGlobal(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>? = null,
  ): EmbyGlobalSearchResult =
    withContext(Dispatchers.IO) {
      // 与 [search] 同理：Person 只能从 `/Persons` 拿，`/Items` 那条端点搜不出人；
      // 并且这里同样带上「服务端不认 SearchTerm 就本地过滤」的兜底
      val persons = searchPersons(server, term, GLOBAL_SEARCH_LIMIT, itemTypes)
      val mediaTypes = itemTypes?.filter { it != PERSON_TYPE }?.takeIf { it.isNotEmpty() }
        ?: GLOBAL_SEARCH_DEFAULT_TYPES.takeIf { itemTypes == null }
      val media = if (mediaTypes.isNullOrEmpty()) {
        EmbyItemsResult(emptyList(), 0)
      } else {
        runCatching {
          repository.searchItems(
            server = server,
            term = term,
            includeItemTypes = mediaTypes,
            limit = GLOBAL_SEARCH_LIMIT,
          )
        }.getOrDefault(EmbyItemsResult(emptyList(), 0))
      }
      EmbyGlobalSearchResult(
        items = persons + media.Items,
        // 只能拿媒体侧的总数（`/Persons` 那条走 [searchPersons] 时总数拿不到 ——
        // 它有「服务端搜不到就退回全量演员本地过滤」的兜底路径，那种情况下没有权威总数）。
        // 所以 totalCount 是**媒体部分的**总数，UI 提示截断时要说明「至少还有这么多条」。
        totalCount = media.TotalRecordCount,
        personCount = persons.size,
        consumedCount = media.consumedCount,
      )
    }


  /**
   * 预解析「302 直链」：拿 `/Videos/{id}/stream` 背后跳转到的真实地址。
   *
   * 失败返回 null，调用方回退到原始流地址 —— 解析失败绝不挡播放。
   *
   * ## 为什么必须限时（v139 修的「第一次播放卡在加载中」）
   *
   * 调用方是 [play]，它**拿到本函数的返回值之后才拉起播放器** —— 这条请求因此
   * 直接决定「点播放到播放页出现」要等多久。而 strm 条目在服务端还没 probe 过
   * 源文件时（详情页「没有媒体信息」正是这个状态），`/Videos/{id}/stream` 会先让
   * 服务端去读远端文件、把大小/时长写库，这一次能挂到分钟级。
   *
   * 于是有了那个典型现象：
   * · **第一次**点播放 → 卡在这里，播放器一直不出现；
   * · 返回上一级重新进详情页 → 服务端 probe 已完成、媒体信息补上了，探测秒回 → 立马能播。
   *
   * 两道闸：单次调用 3 秒（[EmbyClient.resolveStreamUrl] 的 `callTimeoutMs`），
   * 且**只发一次不重试** —— 慢路径重试注定还是慢，只是把等待翻倍。
   * 超时按 [StreamProbe.Unknown] 处理，地址原样交给播放器去跟 302。
   */
  suspend fun resolveDirectUrl(
    server: EmbyServer,
    itemId: String,
    /** 多版本媒体要指定的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): StreamProbe = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.resolveStreamUrl(
        server = server,
        itemId = itemId,
        // 起播路径上不重试：见上面的说明。
        attempts = 1,
        mediaSourceId = mediaSourceId,
      )
    }.getOrElse { StreamProbe.Unknown }
  }

  /** 是否开启「预解析直链」（设置项）。播放路径据此决定要不要多走一次解析。 */
  fun shouldResolveDirectLink(): Boolean = browserPreferences.embyResolveDirectLink.get()

  // ==================== 播放 ====================

  /**
   * 启动播放器播放 Emby 媒体。
   *
   * 剧集（Episode）会自动把同季后续剧集一起交给播放器作为播放列表，
   * 从而在播完当前集后自动接着播下一集。
   *
   * @param resumeSeconds >0 时从指定秒数续播，否则从头开始
   */
  suspend fun play(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long = 0,
    /**
     * 多版本媒体要播放的那个版本（`item.MediaSources[i].Id`）。
     * null = 不指定，由服务端挑默认版本（单版本媒体照旧走这条路）。
     */
    mediaSourceId: String? = null,
    /**
     * [mediaSourceId] 对应的**显示名**（与详情页下拉框里显示的一模一样）。
     * 由 UI 传进来而不是在这里重算 —— 保证「选的」和「播的时候顶栏显示的」是同一个标签。
     */
    mediaSourceLabel: String? = null,
    /**
     * 电视剧「从头播放」：**强制**落在第一集、并且忽略进度。
     *
     * ⚠️ 必须单独一个开关，不能靠 `resumeSeconds == 0` 推断：
     *   普通「播放」在没看过时也是 0，但那种情况该挑「第一集未看的」；
     *   而「从头播放」的语义是「第一集开头」，哪怕第 1 集已经看完。
     */
    fromStart: Boolean = false,
    videoSelect: Int = 0,
    audioSelect: Int = 0,
    subtitleSelect: Int = 0,
    selectPolicy: String = "auto",
    versionSelect: Int = 1,
    useDirectLink: Boolean? = null,
    selectionCurrentItemOnly: Boolean = false,
  ) {
    requireBoundServer(server)
    repository.sessionServer(server)
    if (!EmbyItemPolicy.isSupported(item)) return
    val itemId = item.Id?.takeIf { it.isNotBlank() } ?: return

    // ⚠️ 容器类条目**绝不能**直接交给播放器。
    // `Series` / `Season` / 文件夹自身没有媒体文件，`/Videos/{id}/stream` 会回一个
    // 404 或空 body；播放器拿到「HTTP 成功但 0 字节」不会当场报错，而是一路推进到
    // mpv 初始化，最后在没有可解码帧那条路径上崩掉 —— 用户看到的就是「点播放闪退」。
    // 电视剧的正确行为是「点封面 = 从上一集没看完的地方接着看」：
    // 先取分集列表，优先挑有进度的第一集，其次挑第一集未看的，最后退回首集。
    val playTarget: EmbyItem = if (isContainerOnly(item) && item.Type == "Series") {
      val episodes = runCatching {
        withContext(Dispatchers.IO) { repository.getEpisodes(server, itemId, null, 0, null).Items }
      }.getOrDefault(emptyList())
      if (fromStart) {
        // 「从头播放」= 第一集开头，哪怕第 1 集早看完了。
        episodes.firstOrNull() ?: return
      } else {
        episodes.firstOrNull { (it.UserData?.PlaybackPositionTicks ?: 0L) > 0L }
          ?: episodes.firstOrNull { it.UserData?.Played != true }
          ?: episodes.firstOrNull()
          ?: return  // 一集都没有（还没刮削完）：什么都不做，比崩掉好
      }
    } else if (isContainerOnly(item)) {
      // 季节 / 文件夹 / 合集：不知道用户想要哪一集，只能安静返回。
      // 详情页已把这类条目的播放按钮改成「选择分集」，走到这里属于兜底。
      return
    } else {
      item
    }
    if (!EmbyItemPolicy.isSupported(playTarget)) return
    val itemIdToPlay = playTarget.Id ?: return
    val playItem = playTarget
    val explicitSourceId = if (selectionCurrentItemOnly && selectPolicy != "manual") null else mediaSourceId
    try {
      requireExplicitPlaybackSource(playItem, explicitSourceId)
    } catch (error: IllegalArgumentException) {
      showPlaybackError(error.message ?: "此媒体源不可播放")
      return
    }
    // 「从头播放」一律从头：哪怕调用方顺手传了续播秒数也不认。
    // （分集里的「从头播放本季」走 onPlayEpisode(ep, 0)，本就不带秒数，
    //   这里兜的是「Series 大按钮 + 老版本调用方」这类组合。）
    val effectiveResume = if (fromStart) 0L else resumeSeconds

    // mpv registers the complete season through loopback PlaybackSpec entries, without resolving
    // every remote URL before launch. PlaybackInfo/version selection happens on demand.
    try {
      val queue = if (playItem.Type == "Episode") buildEpisodePlaylist(server, playItem, completeSeason = true)
        else listOf(playItem)
      launchPlaylistAwait(
        server = server, items = queue, resumeSeconds = effectiveResume,
        firstMediaSourceId = explicitSourceId, firstMediaSourceName = mediaSourceLabel,
        startItemId = itemIdToPlay, videoSelect = videoSelect, audioSelect = audioSelect,
        subtitleSelect = subtitleSelect, selectPolicy = selectPolicy, versionSelect = versionSelect,
        useDirectLink = useDirectLink, selectionCurrentItemOnly = selectionCurrentItemOnly,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      showPlaybackError("本地播放服务不可用，请重试")
    }
  }

  /** Fetch the complete season in bounded pages. Partial failures remain playable and visible. */
  private suspend fun buildEpisodePlaylist(
    server: EmbyServer,
    item: EmbyItem,
    completeSeason: Boolean = false,
  ): List<EmbyItem> {
    val seriesId = item.SeriesId?.takeIf { it.isNotBlank() } ?: return listOf(item)
    val result = collectEpisodePlaylist(item, completeSeason) { startIndex, limit ->
      repository.getEpisodes(server, seriesId, item.SeasonId, startIndex, limit)
    }
    result.warning?.let(::showPlaybackError)
    return result.items
  }

  /** Playback failures are surfaced before launching the mpv page when possible. */
  private fun showPlaybackError(message: String) {
    val app = getApplication<Application>()
    val show = Runnable { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    if (Looper.myLooper() == Looper.getMainLooper()) show.run()
    else Handler(Looper.getMainLooper()).post(show)
  }

  /** Bind headers to this server's current memory session and the actual target URL. */
  private fun Intent.putStreamHeaders(server: EmbyServer, url: String) {
    requireBoundServer(server)
    val session = repository.sessionServer(server)
    putExtra(
      PlayerHandoff.EXTRA_HEADERS,
      PlayerHttpHeaders.toPairs(PlayerHttpHeaders.forUrl(url, server = session)),
    )
  }

  /** 播放一组媒体（剧集连播 / 随机播放）。 */
  /**
   * 「随机播放 / 随机播放收藏 / 混合推荐连播 / 演员作品连播」的统一启动入口。
   * 所有条目注册为本地 loopback PlaybackSpec，由 mpv 按需解析。
   */
  fun launchRandomPlayback(
    server: EmbyServer,
    items: List<EmbyItem>,
  ) {
    if (items.isEmpty()) return
    viewModelScope.launch { launchRandomPlaybackAwait(server, items) }
  }

  /** [launchRandomPlayback] 的可等待版本：返回时播放页已经启动。 */
  suspend fun launchRandomPlaybackAwait(
    server: EmbyServer,
    items: List<EmbyItem>,
  ) {
    val items = EmbyItemPolicy.filterSupported(items).filter { it.Id != null }
    if (items.isEmpty()) return
    launchPlaylistAwait(server, items, playFromStartAll = true)
  }

  fun launchPlaylist(
    server: EmbyServer,
    items: List<EmbyItem>,
    resumeSeconds: Long = 0,
    /**
     * 整场从头播放（随机播放 / 随机播放收藏专用）。
     * 随机出来的列表里常混着看过的剧，若切集时恢复本地续播记录，
     * 已看完的会直接跳到片尾一秒就结束，所以本次会话内每个视频都从头放。
     */
    playFromStartAll: Boolean = false,
    /**
     * itemId → 预解析出来的直链。空 map = 全部由 PlaybackSpec 按需解析。
     */
    directUrls: Map<String, String> = emptyMap(),
    /**
     * 多版本媒体：**首条**（即用户在详情页点播的那一条）要用的版本 Id。
     * 只作用于首条，后面自动连播的条目各自用默认版本 —— 用户只为当前这条选了版本。
     */
    firstMediaSourceId: String? = null,
    /** [firstMediaSourceId] 对应的显示名；播放页顶栏据此显示「正在播的是哪个版本」。 */
    firstMediaSourceName: String? = null,
  ) {
    viewModelScope.launch {
      try {
        launchPlaylistAwait(server, items, resumeSeconds, playFromStartAll, directUrls,
          firstMediaSourceId, firstMediaSourceName)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        showPlaybackError("本地播放服务不可用，请重试")
      }
    }
  }

  private suspend fun launchPlaylistAwait(
    server: EmbyServer,
    items: List<EmbyItem>,
    resumeSeconds: Long = 0,
    playFromStartAll: Boolean = false,
    directUrls: Map<String, String> = emptyMap(),
    firstMediaSourceId: String? = null,
    firstMediaSourceName: String? = null,
    startItemId: String? = items.firstOrNull()?.Id,
    videoSelect: Int = 0,
    audioSelect: Int = 0,
    subtitleSelect: Int = 0,
    selectPolicy: String = "auto",
    versionSelect: Int = 1,
    useDirectLink: Boolean? = null,
    selectionCurrentItemOnly: Boolean = false,
  ) {
    requireBoundServer(server)
    val supported = EmbyItemPolicy.filterSupported(items).filter { !it.Id.isNullOrBlank() }
    val current = supported.firstOrNull { it.Id == startItemId } ?: return
    try {
      requireExplicitPlaybackSource(current, firstMediaSourceId, directUrls[current.Id])
    } catch (error: IllegalArgumentException) {
      showPlaybackError(error.message ?: "此媒体源不可播放")
      return
    }
    val items = supported.filter { candidate ->
      candidate.Id == startItemId || directUrls[candidate.Id]?.let { url ->
        !EmbyMediaSourcePolicy.isRemoteIsoUrl(url)
      } != false
    }
    val startIndex = items.indexOfFirst { it.Id == startItemId }.takeIf { it >= 0 } ?: return
    val uris = ArrayList<android.net.Uri>()
    val ids = ArrayList<String>()
    // 与 uris 下标一一对应的显示标题。播放器侧切集时用它，避免从
    // `/Videos/{id}/stream` 这种 URL 里猜出统一是 "stream" 的假标题。
    val titles = ArrayList<String>()
    // 与 uris 下标一一对应的「记忆键」：剧集用 SeriesId（整季共用一份速度 / 音轨），
    // 电影没有 SeriesId 就用自身 Id。播放器切集时按下标取，实现「同剧继承」。
    val seriesKeys = ArrayList<String>()
    // 与队列同序的媒体源 Id，供进度上报与版本信息使用。
    // 取不到时填空串占位，保证所有平行数组继续严格对齐。
    val mediaSourceIds = ArrayList<String>()
    // 与上面同序的版本显示名，供播放页顶栏显示「正在播的是哪个版本」。
    // 只有「用户点播的那一条」才会有值（用户只为这一条选了版本），其余一律空串占位。
    val mediaSourceNames = ArrayList<String>()
    // 与上面同序的「S*E*」原始序号，供弹幕按真实集号匹配（队列下标 ≠ 集号）。
    val episodeNumbers = ArrayList<String>()
    // 与上面同序的**剧名**（Emby 的 SeriesName）。弹幕自动匹配要搜的是剧名 ——
    // 拿分集名（item.Name，如「都市传说」）去搜番是搜不到的。电影没有 SeriesName ⇒ 空串，
    // 播放器侧会退回用显示名。
    val seriesNames = ArrayList<String>()
    items.forEachIndexed { index, item ->
      val id = item.Id ?: return@forEachIndexed
      // Only a manual choice is known before PlaybackInfo. Auto sources are filled by the coordinator.
      val sourceId = if (index == startIndex) firstMediaSourceId else null
      val url = run {
        requireBoundServer(server)
        loopback.register(PlaybackSpec(
          server = repository.sessionServer(server),
          itemId = id,
          seriesId = item.SeriesId,
          itemName = item.Name.orEmpty(),
          seriesName = item.SeriesName,
          title = displayTitle(item),
          itemType = item.Type,
          seasonNumber = item.ParentIndexNumber,
          episodeNumber = item.IndexNumber,
          runTimeTicks = item.RunTimeTicks,
          primaryImageItemId = item.primaryImageRef()?.itemId,
          primaryImageTag = item.primaryImageRef()?.tag,
          backdropImageTag = item.BackdropImageTags.firstOrNull(),
          positionTicks = if (index == startIndex && !playFromStartAll) {
            EmbyTicks.secondsToTicks(resumeSeconds)
          } else 0L,
          useDirectLink = when {
            index == startIndex && useDirectLink != null -> useDirectLink
            else -> shouldResolveDirectLink()
          },
          selectPolicy = selectPolicy,
          versionSelect = versionSelect,
          videoSelect = videoSelect,
          audioSelect = audioSelect,
          subtitleSelect = subtitleSelect,
          playlistIndex = index + 1,
          playlistTotal = items.size,
          explicitMediaSourceId = if (index == startIndex) firstMediaSourceId else null,
          overrideUrl = directUrls[id],
        ).forPlaylistEntry(index == startIndex, selectionCurrentItemOnly))
      }
      uris.add(android.net.Uri.parse(url))
      ids.add(id)
      titles.add(displayTitle(item))
      seriesKeys.add(item.SeriesId ?: id)
      // ⚠️ 剧名：弹幕自动匹配要搜的是「某科学的超电磁炮」，
      //    不是某一集的 Name（如「都市传说」）。电影没有 SeriesName，该项留空
      //    ⇒ 播放器侧会退回用显示名，行为不变。
      seriesNames.add(item.SeriesName.orEmpty())
      mediaSourceIds.add(sourceId ?: "")
      mediaSourceNames.add(
        if (index == startIndex) firstMediaSourceName.orEmpty() else "",
      )
      episodeNumbers.add(encodeEpisodeNumber(item))
    }
    if (uris.isEmpty()) return

    val first = items[startIndex]
    val intent = Intent(Intent.ACTION_VIEW, uris[startIndex]).apply {
      setClass(getApplication(), PlayerActivity::class.java)
      putExtra("internal_launch", true)
      putExtra("launch_source", "emby")
      putExtra("title", displayTitle(first))
      putExtra("filename", first.Name ?: displayTitle(first))
      if (resumeSeconds > 0) {
        putExtra("position", (resumeSeconds * 1000).toInt())
      } else {
        // 显式「从头播放」：告知播放页别再拿本地续播记录覆盖进度。
        // 否则 savePositionOnQuit 开启时，applyPlaybackState 会把上次停下
        // 的位置套回 time-pos，「从头播放」就变成了「继续播放」。
        putExtra("play_from_start", true)
      }
      // 随机播放入口：整个播放会话内切到哪个视频都从头放（不恢复本地续播）
      if (playFromStartAll) {
        putExtra("play_from_start_all", true)
      }
      putParcelableArrayListExtra("playlist", uris)
      putStringArrayListExtra("playlist_titles", titles)
      putStringArrayListExtra("playlist_series_keys", seriesKeys)
      putStringArrayListExtra("playlist_series_names", seriesNames)
      // 单文件/单条播放时也有剧名可用于匹配（电影为空串 ⇒ 退回显示名）
      putExtra("emby_series_name", first.SeriesName.orEmpty())
      putExtra("playlist_index", startIndex)
      // 播放列表的 ID 顺序与 uris 一致，切集时据此把"正在播放"同步给服务器
      putEmbyPlaybackExtras(server, ids, mediaSourceIds, mediaSourceNames, episodeNumbers)
      putStreamHeaders(server, uris[startIndex].toString())
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    requireBoundServer(server)
    getApplication<Application>().startActivity(intent)
  }

  /**
   * 这个条目**自身**是否真的有视频文件可播。
   *
   * ⚠️ 这正是一次线上闪退的直接原因：`Series` / `Season` / 文件夹 / 合集本身没有媒体文件，
   * 拿它们的 Id 去请求 `/Videos/{id}/stream`，服务端回 404 或一个空 body；
   * 播放器拿到「成功的 HTTP、0 字节」会一路走到 mpv 初始化，最后在
   * 「没有任何可解码帧」这条路径上崩掉 —— 现象就是「点播放 App 直接闪退」。
   *
   * 判据用白名单而不是黑名单：宁可漏判（退化成现在的行为）也不能误判，
   * 误判会把能播的东西拦下来。`Episode` 必须在内 —— 它才是电视剧真正的播放单元。
   */
  fun isContainerOnly(item: EmbyItem): Boolean = !EmbyItemPolicy.isSupported(item) ||
    (item.Type?.let { type -> CONTAINER_TYPES.any { it.equals(type.trim(), ignoreCase = true) } } ?: true)

  /**
   * 详情页的分集列表（电视剧用）。
   *
   * 一次拉全剧所有集（`SeasonId = null`），因为：
   *  · 详情页要按季分组显示，逐季发请求会让「第一屏」等 N 次往返；
   *  · Emby 的 `/Shows/{id}/Episodes` 本来就支持不带 SeasonId 全量返回。
   *
   * 失败**不抛**：分集区只是详情页的增强，拿不到就退回「只有播放按钮」的老样子，
   * 不能让整页变成错误态。
   */
  suspend fun loadSeriesEpisodes(server: EmbyServer, seriesId: String): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      runCatching { repository.getEpisodes(server, seriesId, null, 0, null).Items }.getOrDefault(emptyList())
    }

  /**
   * 按 Id 批量取条目（有序、缺项跳过）。
   *
   * 用途：单集详情页的「同季其它集」——`BaseItemDto.SiblingIds` 只给 Id 不给内容，
   * 而 `/Items?Ids=a,b,c` 一次就能把它们连同 UserData（进度/已看）一起拿回来。
   *
   * ⚠️ 不用 `Recursive` 参数：`Ids` 查询本身就是精确命中，加 `Recursive=true`
   * 在部分第三方服务端会把参数组合当成非法。
   */
  suspend fun loadItemsByIds(server: EmbyServer, ids: List<String>): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      if (ids.isEmpty()) return@withContext emptyList()
      // 服务端对超长 Ids 有 URL 长度限制，按 60 个一批（32 位 Id 约 2KB，安全）
      runCatching {
        ids.chunked(60).flatMap { chunk ->
          EmbyClient.getItems(
            server = server,
            ids = chunk,
            includeItemTypes = listOf("Episode"),
            limit = chunk.size,
            fields = "Overview,MediaSources,MediaStreams,People,Genres,Studios,BasicSyncInfo",
          ).Items.filter { it.Id != null && it.Id in chunk }
        }
      }.getOrDefault(emptyList())
    }

  /**
   * 把剧集的「S*E*」原始序号编码成一个字符串，随播放 intent 下发给播放页。
   *
   * 格式 `ParentIndexNumber*1000 + IndexNumber`，例如 S02E05 → `"2005"`；
   * 非剧集（电影 / 本地文件）返回空串 —— 播放页见到空串就退回队列下标。
   *
   * ⚠️ 为什么要编码而不是直接传 "S02E05"：播放页最终要的是一个**可比较的集号**
   * 去和弹幕库的 `episodeNumber` 对齐，字符串还要再解析一次；直接传数字最省事。
   * 乘 1000 是因为单季集数按惯例远小于 1000，跨季仍能保持单调递增。
   */
  private fun encodeEpisodeNumber(item: EmbyItem): String {
    val season = item.ParentIndexNumber ?: return ""
    val episode = item.IndexNumber ?: return ""
    return (season * 1000 + episode).toString()
  }

  /** Full titles use the shared loemby-compatible episode format. */
  fun displayTitle(item: EmbyItem): String = item.displayTitle()

  /** 构造播放地址（用于播放列表等只需要 URL 的场景） */
  fun getStreamUrl(
    server: EmbyServer,
    itemId: String,
    /** 多版本媒体要指定的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): String =
    repository.videoStreamUrl(server, itemId, mediaSourceId = mediaSourceId)

  fun imageUrl(
    server: EmbyServer,
    item: EmbyItem,
    imageType: String = "Primary",
    maxWidth: Int = 480,
    imageIndex: Int? = null,
  ): String? {
    // Primary 统一走 primaryImageRef()：相册 / 文件夹自身没有主图，真正带图的
    // 是 PrimaryImageItemId 指向的条目（见 domain/emby/EmbyPrimaryImage.kt）。
    if (imageType == "Primary") {
      val ref = item.primaryImageRef() ?: return null
      return repository.imageUrl(server, ref.itemId, imageType, ref.tag, maxWidth)
    }
    val id = item.Id ?: return null
    val tag = when (imageType) {
      "Backdrop" -> item.BackdropImageTags.getOrNull(imageIndex ?: 0)
      else -> item.ImageTags[imageType]
    }
    return repository.imageUrl(server, id, imageType, tag, maxWidth, imageIndex = imageIndex)
  }

  // ── 照片（图集）──
  // 全部是薄封装（UI 不直接摸 EmbyClient），IO 线程执行。
  // 相册 / 照片两个查询的参数语义见 EmbyClient.getPhotoAlbums / getPhotos。

  /**
   * 照片显示图 URL。原格式返回（HEIC 由 EmbyImageLoader 客户端解码）；
   * [format] = "jpg" 时让服务端转码，作为老系统 / AVIF 的兜底地址。
   */
  fun photoImageUrl(
    server: EmbyServer,
    item: EmbyItem,
    maxWidth: Int = 720,
    format: String? = null,
  ): String? {
    // 相册条目的预览图在 PrimaryImageItemId 上，按自身 Id 取必 404（见 EmbyPrimaryImage）
    val ref = item.primaryImageRef() ?: return null
    return EmbyClient.photoImageUrl(server, ref.itemId, ref.tag, maxWidth, format = format)
  }

  /** 照片原文件下载地址（「保存原图」用，不带缩放参数） */
  suspend fun loadPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPhotoAlbums(server, parentId, startIndex, limit, sortBy, sortOrder)
  }

  /** 照片（Photo）列表；sortBy = "Random" 即随机一批（随机播放入口用） */
  suspend fun loadPhotos(
    server: EmbyServer,
    parentId: String,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPhotos(server, parentId, startIndex, limit, sortBy, sortOrder, recursive)
  }

  /**
   * 某媒体库的演员列表（媒体库「演员」分类用）。
   *
   * 走 Emby 的 /Persons 端点，IO 线程执行 —— 库大时是几十上百个人物的网络请求。
   */
  suspend fun getActors(server: EmbyServer, parentId: String?): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getActors(server, parentId) }

  /**
   * 数每位演员在该库参与的作品数（PersonId → 条数）。
   *
   * /Persons 拿不到这个数（Fields 不支持 ChildCount），只能自己算，所以调用方要放在
   * 后台、别挡首屏。详见 [EmbyClient.getPersonWorkCounts]。
   *
   * ⚠️ 这条是**整库逐页扫描**（最坏 20 个请求），只给 [PersonMerge] / 孤立演员清理用；
   * 列表角标请走 [getPersonWorkCountsCached]（自适应 + 带缓存）。
   */
  suspend fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    withContext(Dispatchers.IO) { EmbyClient.getPersonWorkCounts(server, parentId) }

  /**
   * 演员角标用的作品数：**自适应 + 带缓存**（列表页专用，见 1.4 优化）。
   *
   * 原来的实现每次进「演员」分类都要把整库逐页拉一遍（500/页、上限 1 万条 ⇒ 最多
   * 20 次串行请求），而角标只是次要信息。现在：
   *
   * 1. 交给 [EmbyClient.getPersonWorkCountsAdaptive] 按库规模挑路 ——
   *    小库整库扫一次算齐，大库只逐个问前 60 个；
   * 2. 结果按 (服务器, 库) 缓存 [PERSON_WORK_COUNTS_TTL_MS] —— 切分类、进出页面、
   *    切tab 都不再重算。
   *
   * 缓存失效：刷新媒体库 / 扫描媒体库后调 [invalidatePersonWorkCountsCache]（角标会变）。
   */
  suspend fun getPersonWorkCountsCached(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> {
    val key = "${server.id}|$parentId"
    val hit = personWorkCountsCache
    if (hit != null && hit.first == key &&
      SystemClock.elapsedRealtime() - hit.second < PERSON_WORK_COUNTS_TTL_MS
    ) {
      // 缓存是整张表，直接切片给这一轮要的演员
      val ids = personIds.filter { it.isNotBlank() }.toSet()
      return if (ids.isEmpty()) emptyMap() else hit.third.filterKeys { it in ids }
    }
    val counts = withContext(Dispatchers.IO) {
      runCatching {
        EmbyClient.getPersonWorkCountsAdaptive(server, parentId, personIds)
      }.getOrDefault(emptyMap())
    }
    if (counts.isNotEmpty()) {
      personWorkCountsCache = Triple(key, SystemClock.elapsedRealtime(), counts)
    }
    return counts
  }

  /**
   * 演员作品数角标的进程内缓存：`(serverId|parentId)` → (缓存时间, PersonId → 条数)。
   *
   * 单独一份而不是并进 [actorsCache]：那份是「全量演员名单」（跨库、无 parentId），
   * 这份是「某个库里的作品数」，键不一样，失效时机也不一样。
   */
  private var personWorkCountsCache: Triple<String, Long, Map<String, Int>>? = null

  /** 媒体库内容变了（刷新 / 扫描 / 合并演职员）时调用：角标数字会变，缓存作废 */
  fun invalidatePersonWorkCountsCache() {
    personWorkCountsCache = null
  }

  suspend fun loadItem(server: EmbyServer, itemId: String): EmbyItem? =
    withContext(Dispatchers.IO) {
      runCatching { repository.getItem(server, itemId) }.getOrNull()
    }

  /** 条目当前挂着的图片清单（含尺寸与来源，即「图片源数据」） */
  suspend fun loadItemImages(server: EmbyServer, itemId: String): List<EmbyImageInfo> =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getItemImages(server, itemId) }.getOrDefault(emptyList())
    }

  /**
   * 按演员 / 导演查作品：走 Emby 的 PersonIds 过滤。
   *
   * [includeItemTypes] 放在最后且带默认值，所以既有调用不受影响 ——
   * 详情页的「推荐」只要正片（Movie / Series），不想混进单集和 MV，就单独传一个窄一点的集合。
   */
  suspend fun loadPersonItems(
    server: EmbyServer,
    personId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    includeItemTypes: List<String> = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    runCatching {
      // ⚠️ 必须分页拉全：高产演员能有几百部作品，只拉一页 60 条
      // 会让人以为「合并丢了一半作品」。TotalRecordCount 是服务端给的总数，
      // 循环到拉完为止；maxItems 是异常数据兜底，防止无限循环拖死设备。
      val out = ArrayList<EmbyItem>()
      val maxItems = 2_000
      var index = startIndex
      var total = Int.MAX_VALUE
      while (index < total && index - startIndex < maxItems) {
        val resp = EmbyClient.getItems(
          server = server,
          personIds = listOf(personId),
          includeItemTypes = includeItemTypes,
          sortBy = "SortName",
          sortOrder = "Ascending",
          recursive = true,
          startIndex = index,
          limit = limit,
          // 作品页要按年份 / 评分 / 最新添加做**本地排序**：这几个字段 Emby 默认不回传，
          // 必须显式索取，否则键值全是 null、排序退化成原顺序（用户反馈
          // 「只有名称排序和随机排序有效果」就是这么来的）
          fields = EmbyClient.ITEM_FIELDS_WITH_SORT_KEYS,
        )
        if (resp.consumedCount == 0) break
        out += resp.Items
        if (resp.TotalRecordCount > 0) total = resp.TotalRecordCount
        index += resp.consumedCount
      }
      out
    }.getOrDefault(emptyList())
  }

  /**
   * 按 Id 查单个人员（含 UserData.IsFavorite）。
   *
   * 演员作品页的收藏红心用：进来时查一次当前状态，收藏/取消收藏后据此刷新红心。
   * 查不到（服务端异常等）返回 null，红心保持未收藏态且不可点。
   */
  suspend fun loadPersonById(server: EmbyServer, personId: String): EmbyItem? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonById(server, personId) }

  /**
   * 读单个人员的收藏真值（true=已收藏 / false=未收藏 / null=读不到）。
   *
   * 两个用途：① 演员作品页红心的**初值兜底** —— [loadPersonById] 没带回 UserData 时
   * （服务端不回传 / `Ids` 不被支持）用它补一次，否则已收藏的演员进来红心是灰的；
   * ② [toggleFavorite] 收藏后的**回读**（见 [readbackFavoriteState]）。
   */
  suspend fun loadPersonFavoriteState(server: EmbyServer, personId: String): Boolean? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonFavoriteState(server, personId) }

  /**
   * 加载「类型 / 标签 / 工作室」结果页顶栏收藏红心所需的条目。
   *
   * 工作室用详情页传来的 Id（[filterId]）；类型优先用调用方给的 Id（收藏页的类型
   * 条目本身就在手上），拿不到再按名字反查 —— Emby 的类型条目是带 `UserData`
   * 的正式条目，可被收藏，但详情页的类型 chip 只给了名字，需要反查一次拿到 Id；
   * 标签没有独立条目 Id，**不支持收藏**，返回 null（UI 据此隐藏红心）。
   *
   * 拿到的是携带 `UserData` 的完整条目，红心的「是否已收藏」直接读 `UserData.IsFavorite`。
   */
  suspend fun loadFacetItem(
    server: EmbyServer,
    kind: String,
    keyword: String,
    filterId: String,
  ): EmbyItem? = withContext(Dispatchers.IO) {
    val id = when (kind) {
      "studio" -> filterId.takeIf { it.isNotBlank() }
      "genre" -> filterId.takeIf { it.isNotBlank() } ?: resolveGenreId(server, keyword)
      else -> null
    } ?: return@withContext null
    runCatching { EmbyClient.getItem(server, id) }.getOrNull()
  }

  /**
   * 按名字反查类型（Genre）条目的 Id：走官方 `/Genres` 聚合端点
   * （见 [EmbyClient.getGenreIdByName]）。
   *
   * ⚠️ 历史实现是 `/Items?IncludeItemTypes=Genre&SearchTerm=<名字>&Recursive=false`。
   * 不带 `ParentId` 时非递归查询只扫用户根，而用户根下只有媒体库视图、没有类型条目，
   * 于是**永远返回空** —— 「进类型页看不到顶栏收藏红心」就是这么来的。
   */
  private suspend fun resolveGenreId(server: EmbyServer, name: String): String? =
    if (name.isBlank()) null else EmbyClient.getGenreIdByName(server, name)

  suspend fun loadGenreItems(
    server: EmbyServer,
    genre: String,
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage = loadItemsByFacet(server, genres = listOf(genre), sortBy = sortBy, startIndex = startIndex, limit = limit)

  /** 按「标签」查作品：走 Emby 的 Tags 过滤，规则同 [loadGenreItems]。 */
  suspend fun loadTagItems(
    server: EmbyServer,
    tag: String,
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage = loadItemsByFacet(server, tags = listOf(tag), sortBy = sortBy, startIndex = startIndex, limit = limit)

  /**
   * 按「工作室」查作品。
   *
   * ⚠️ **优先用 Id**（`StudioIds`）：Emby 的 `BaseItemsRequest` 里它是官方过滤参数，
   * 且条目自带的 `Studios` 就是 `NameGuidPair`（有 Id），拿得到就不必按名字碰运气。
   * 只有 Id 缺失（少数服务端可能只回名字）时才退回按名字的 `Studios` 参数 ——
   * 它同样是官方参数（和 `Genres` / `Tags` 一样用 `|` 分隔），是个正经兜底。
   *
   * 两者都空时直接返回空页：传空值给服务端等于「不过滤」，结果页会列出一整库的片子，
   * 比空列表更误导用户。
   */
  suspend fun loadStudioItems(
    server: EmbyServer,
    studioId: String,
    studioName: String = "",
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage {
    val id = studioId.takeIf { it.isNotBlank() }
    val name = studioName.takeIf { it.isNotBlank() }
    if (id == null && name == null) return EmbyItemsPage(emptyList(), 0)
    return loadItemsByFacet(
      server = server,
      studioIds = id?.let { listOf(it) },
      studios = if (id == null) name?.let { listOf(it) } else null,
      sortBy = sortBy,
      startIndex = startIndex,
      limit = limit,
    )
  }

  /**
   * [loadGenreItems] / [loadTagItems] / [loadStudioItems] 的公共实现：
   * 只差 Genres / Tags / StudioIds / Studios 这几个过滤参数。
   */
  private suspend fun loadItemsByFacet(
    server: EmbyServer,
    genres: List<String>? = null,
    tags: List<String>? = null,
    studioIds: List<String>? = null,
    studios: List<String>? = null,
    sortBy: String = "SortName",
    startIndex: Int = 0,
    limit: Int = 120,
  ): EmbyItemsPage = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.getItems(
        server = server,
        genres = genres,
        tags = tags,
        studioIds = studioIds,
        studios = studios,
        includeItemTypes = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
        sortBy = sortBy,
        sortOrder = "Ascending",
        recursive = true,
        startIndex = startIndex,
        limit = limit,
      )
    }.fold(
      onSuccess = { EmbyItemsPage(it.Items, it.TotalRecordCount, it.consumedCount) },
      // ⚠️ 这里以前是 getOrDefault(emptyList())：网络一抖，标签页就变成「该标签下没有作品」，
      // 用户分不清是真没有还是拉失败。返回 total=0 的空页，让 UI 的错误提示有东西可显示。
      onFailure = { e ->
        android.util.Log.w(
          "EmbyViewModel",
          "按 Genres/Tags/Studios 取作品失败 genres=$genres tags=$tags studioIds=$studioIds studios=$studios",
          e,
        )
        EmbyItemsPage(emptyList(), 0)
      },
    )
  }

  /**
   * 一次取回「当前这一屏所有文件夹」的宫格封面，按文件夹 Id 分组。
   *
   * 存在的理由 —— 给服务端减压：容器条目（Folder / BoxSet / CollectionFolder）自身没有封面，
   * 只能拿内部子项的缩略图拼宫格。原先是**每个文件夹各发一次** /Items（见 [loadFolderCoverUrls]），
   * 一个「合集」列表摆 30 个合集就是 30 次请求。这里改成对当前父节点做 Recursive 查询、
   * 拿子项的 `ParentId` 当分组键 —— 一屏的封面共用这几次请求，请求数从 O(文件夹数) 降到 O(1~3)。
   *
   * 三处刻意的收窄（都是为了减压）：
   * 1. `Fields` 只留 `ParentId,ImageTags` —— 默认那份 `Fields` 带
   *    `MediaSources` / `Overview` / `People`，一页几百条的响应体能大十几倍，而拼封面一个都用不上。
   * 2. **多次取样**（每次 [FOLDER_COVER_BATCH_PAGE_SIZE] 条，最多 [FOLDER_COVER_BATCH_MAX_PAGES] 次），
   *    并且「每个文件夹都凑够 4 张」时立即收工 —— 子项在整棵子树里按名称全局排序时，
   *    一次取样不够会让排在后面的文件夹一条都摸不到。
   * 3. 两种取样口径（[random]）：
   *    - 顺序（`false`）：`SortBy=SortName` 翻页，结果稳定、靠翻页让每个文件夹都分得到；
   *    - 随机（`true`）：每次都让服务端 `SortBy=Random` **重抽一整批**（`StartIndex` 固定 0），
   *      所以每次取样拿到的都是**不同的一批条目** —— 这正是「下拉刷新能换一批封面」的关键。
   *      ⚠️ 随机模式千万别改成「按名称翻页 + 客户端洗牌」：那样每次拿到的是**同一批**条目，
   *      洗牌只换位置不换内容，用户看到的就是「没换图」（踩过这个坑）。
   *
   * ⚠️ 返回的 map **只含「凑满 `perFolder` 张」的文件夹**。不足的一律交白卷，由调用方退回
   * 单文件夹请求去补齐 —— 这一条是**正确性要求**，不是优化：半成品（1~2 张）一旦交出去，
   * 卡片看到「已经有封面」就不再兜底，于是明明能凑四个的文件夹只显示 1~2 个宫格。
   * ⚠️ 与 [loadFolderCoverUrls] 一样会跳过没封面的条目、一路往后凑，所以「前几个没图就整张空白」
   * 不会发生。
   *
   * @param parentId 当前浏览的父节点（媒体库 / 文件夹），即列表接口用的那个 parent
   * @param folderIds 需要拼封面的文件夹 Id（调用方已按「容器 && 无 Primary 图」筛过）
   * @param random true＝每个文件夹从候选里**洗牌**取几张（每切一次取样都不同）；
   *   false＝按名称顺序取前几张。两种都只交出凑满的文件夹。
   * @param perFolder 每个文件夹要凑几张，宫格用 4 张
   */
  suspend fun loadFolderCoverBatch(
    server: EmbyServer,
    parentId: String,
    folderIds: List<String>,
    random: Boolean = false,
    perFolder: Int = 4,
  ): Map<String, List<String>> = runCatching {
    if (folderIds.isEmpty()) return@runCatching emptyMap()
    val wanted = folderIds.toHashSet()
    // 每个文件夹最多留几张候选：只要 4 张，但随机模式要多留一点才有洗牌的余地。
    val poolSize = if (random) perFolder * FOLDER_COVER_BATCH_POOL_FACTOR else perFolder
    val pools = LinkedHashMap<String, ArrayList<String>>()
    // 同一个文件夹内按子项 Id 去重（服务端对 Recursive 查询偶尔会重复返回同一条）
    val seen = HashMap<String, HashSet<String>>()
    var startIndex = 0
    var attempt = 0
    while (attempt < FOLDER_COVER_BATCH_MAX_PAGES) {
      attempt++
      val res = repository.getItems(
        server = server,
        parentId = parentId,
        // 随机模式让服务端重抽（每次一批新样本 ⇒ 刷新真的换图）；顺序模式按名称翻页（稳定、覆盖全）。
        sortBy = if (random) "Random" else "SortName",
        sortOrder = if (random) null else "Ascending",
        includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
        isFolder = false,
        // ⚠️ 随机排序下 StartIndex 没有意义（每次返回的都是新的一批），固定 0 让服务端一次次重抽。
        startIndex = if (random) 0 else startIndex,
        limit = FOLDER_COVER_BATCH_PAGE_SIZE,
        recursive = true,
        fields = FOLDER_COVER_FIELDS,
      )
      val items = res.Items
      if (res.consumedCount == 0) break
      for (child in items) {
        val pid = child.ParentId ?: continue
        if (pid !in wanted) continue
        val pool = pools.getOrPut(pid) { ArrayList(poolSize) }
        if (pool.size >= poolSize) continue
        val id = child.Id ?: continue
        if (!seen.getOrPut(pid) { HashSet() }.add(id)) continue
        val url = folderCoverUrlFor(server, child, id) ?: continue
        pool.add(url)
      }
      if (!random) startIndex += res.consumedCount
      // 一个都没落进任何目标文件夹：说明这批条目根本不在这些文件夹下（典型是「合集」——
      // 它只是虚拟分组，片子的 ParentId 指向各自的媒体库，不指向合集），再取也是白取。
      if (pools.isEmpty() && items.isNotEmpty()) break
      // 每个文件夹都凑够 4 张了就收工，别把整库翻完
      if (pools.count { it.value.size >= perFolder } == wanted.size) break
      // 服务端可截断 Limit；以原始消费数量和权威总数判断末页。
      if (res.TotalRecordCount > 0 && startIndex >= res.TotalRecordCount && !random) break
    }
    // ⚠️ 只交出**凑满 perFolder 张**的文件夹，不足的宁可交白卷，让卡片走单文件夹兜底去补齐。
    //   以前把「这一批只扫到 1~2 张」的半成品也交出去，卡片看见 `cached != null` 就不再兜底，
    //   于是明明能凑四个的文件夹只显示 1~2 个宫格（ bug）。
    //   —— 交白卷的代价只是那一个文件夹多一次单文件夹请求，总次数仍远低于「每个文件夹各一次」。
    pools
      .filterValues { it.size >= perFolder }
      .mapValues { (_, pool) ->
        if (random) pool.shuffled().take(perFolder) else pool.take(perFolder)
      }
  }.getOrElse { e ->
    // ⚠️ 取消必须原样抛出，不能当成「没有封面」：调用方（卡片）拿到空列表会写进缓存，
    // 而「换代被取消」的旧请求正好会在刷新瞬间把封面覆盖成空 —— 就是用户看到的白闪。
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    emptyMap()
  }

  /**
   * 取文件夹内若干子项的封面图 URL，用于给「文件夹」条目拼多宫格封面。
   *
   * Emby 的 Folder / CollectionFolder / UserView 这类容器条目自身没有 Primary 图，
   * 直接请求图片接口会 404（也就是用户看到的「文件夹拿不到封面」）。
   * 这里改为查文件夹内部的**可播放子项**，取它们的缩略图交给客户端拼宫格。
   *
   * 优先 Thumb（横版缩略图，宫格排布更协调），没有则退回 Primary。
   *
   * ⚠️ 关键在于**凑足**：以前只取排序后的前 `count` 个，谁没封面就被滤掉 ——
   * 于是「前四个恰好都没封面」时整个文件夹退回占位图标（空白问题），
   * 而且第 5 个之后哪怕有图也永远看不到。现在改成：从更大的候选池里**跳过没封面的**，
   * 一路往后凑，直到凑满 `count` 张或把文件夹翻完为止。
   *
   * @param count 最多取几张，宫格用 4 张
   * @param random true＝随机取样（排序交给服务端的 Random，多次换样本）；false＝按名称顺序固定取前几张。
   *   两种模式都会跳过无封面条目，只是取样顺序不同。
   */
  suspend fun loadFolderCoverUrls(
    server: EmbyServer,
    folderId: String,
    count: Int = 4,
    random: Boolean = false,
  ): List<String> = runCatching {
    val urls = ArrayList<String>(count)
    // 跨页去重：随机模式靠它避免同一张图重复入选；顺序模式靠它扛服务端可能返回的重复项。
    val seen = HashSet<String>()
    // 已消费的**服务端**条目数（不是 [urls] 的大小 —— 去掉无封面的条目后两者不相等）。
    var consumed = 0
    var attempt = 0
    val maxAttempt = if (random) FOLDER_COVER_RANDOM_ATTEMPTS else FOLDER_COVER_MAX_PAGES
    while (urls.size < count && attempt < maxAttempt) {
      attempt++
      val page = repository.getItems(
        server = server,
        parentId = folderId,
        // 随机模式让服务端打乱；顺序模式按名称，结果稳定可预期。
        sortBy = if (random) "Random" else "SortName",
        sortOrder = if (random) null else "Ascending",
        includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
        // 容器（Folder / Series / Season / BoxSet）不能播放、也不该占宫格位置，挡掉。
        isFolder = false,
        // 随机模式每次都是新样本，从头取；顺序模式从上次消费处接着翻。
        startIndex = if (random) 0 else consumed,
        limit = if (random) FOLDER_COVER_RANDOM_POOL else FOLDER_COVER_PAGE_SIZE,
        recursive = true,
      )
      val items = page.Items
      if (page.consumedCount == 0) break
      val before = urls.size
      for (child in items) {
        if (urls.size >= count) break
        val id = child.Id ?: continue
        if (!seen.add(id)) continue
        val url = folderCoverUrlFor(server, child, id) ?: continue
        urls.add(url)
      }
      if (random) {
        // 这一批随机样本一张新图都没带来：要么文件夹里带图的本来就没几张，
        // 要么服务端忽略了 Random（每次都回同一批）。继续重抽也是白费请求，直接收工。
        if (urls.size == before && items.isNotEmpty()) break
        continue
      }
      consumed += page.consumedCount
      // 服务端可能截断 Limit；无总数时继续到原始空页或取样预算结束。
      if (page.TotalRecordCount > 0 && consumed >= page.TotalRecordCount) break
    }
    urls
  }.getOrElse { e ->
    // ⚠️ 取消原样抛出：这一条**极重要** —— 调用方拿到空列表会写进缓存，
    // 于是「下拉刷新时被取消的旧请求」会在新封面还没到之前先把封面覆盖成空（白闪）。
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    emptyList()
  }

  /**
   * 单个子项能用作宫格封面的图片地址：**优先 Thumb**（横版缩略图，宫格排布更协调），
   * 没有 Thumb 才退回 Primary；两者都没有返回 null（调用方负责跳过它、继续往后凑）。
   *
   * 抽出来是因为有两条取封面的路径（批量 [loadFolderCoverBatch] 与单文件夹 [loadFolderCoverUrls]），
   * 「Thumb 优于 Primary」这条偏好只能有一处，否则两边迟早选出不一样的脸。
   */
  private fun folderCoverUrlFor(
    server: EmbyServer,
    child: EmbyItem,
    id: String,
  ): String? {
    val thumb = child.ImageTags["Thumb"]
    val primary = child.ImageTags["Primary"]
    return when {
      thumb != null -> repository.imageUrl(server, id, "Thumb", thumb, maxWidth = 320)
      primary != null -> repository.imageUrl(server, id, "Primary", primary, maxWidth = 320)
      else -> null
    }
  }

  fun clearError() {
    _error.value = null
  }

  companion object {
    /** 收藏演员缓存的存活时间：超过就当过期重新拉，防止别处改了收藏状态这里还留着旧数据 */
    private const val FAVORITE_PERSONS_CACHE_TTL_MS = 60_000L

    /** 「继续观看」合并时，本地最近播放账本参与排前的最多条数 */
    private const val RECENT_MERGE_LIMIT = 20

    /**
     * 账本里有、服务端这批结果里没有时，一次最多补几条。
     *
     * 12 是「一屏看不到但翻了能翻回来」的量：补太多等于替用户决定看什么，
     * 补太少又救不回刚播的那条。补条目只发**一次** `Ids=` 请求，不逐条取。
     */
    private const val RECENT_FETCH_MAX = 12

    /**
     * 演员作品数角标缓存的存活时间。
     *
     * 比收藏演员那份（60 秒）长得多：角标数字只随「媒体库里多了/少了作品」而变，
     * 而那件事必然伴随一次刷新/扫描（会显式调invalidate），所以兜底过期可以很宽松，
     * 换取「来回切分类/进出页面」时零请求。
     */
    private const val PERSON_WORK_COUNTS_TTL_MS = 10 * 60_000L

    /**
     * 某一季的剧集一页拉多少条（清单 1.1）。
     *
     * 一季 50 集已覆盖绝大多数剧；长剧（一季 100+ 集，多见于动画）会分页续拉。
     */
    const val EPISODE_PAGE_SIZE = 100

    /**
     * 文件夹宫格封面：顺序模式下每页翻多少条。
     *
     * 比只需要 4 张的缺口大得多，是为了**跳过没封面的条目**后还有余量能凑满 ——
     * 一页 100 条足够覆盖绝大多数文件夹；极少数超大文件夹由 [FOLDER_COVER_MAX_PAGES] 兜底。
     */
    private const val FOLDER_COVER_PAGE_SIZE = 100

    /** 顺序模式最多翻几页（100 × 3 = 300 条扫描上限），防止超大文件夹把设备拖住。 */
    private const val FOLDER_COVER_MAX_PAGES = 3

    /** 随机模式每次抽样的池子大小：抽一批再从里头挑有封面的。 */
    private const val FOLDER_COVER_RANDOM_POOL = 48

    /** 随机模式最多重抽几次：随机样本有波动，一两次抽不到 4 张有封面的就再抽一批。 */
    private const val FOLDER_COVER_RANDOM_ATTEMPTS = 3

    /**
     * 批量取封面时每次取多少条。
     *
     * 之所以要**取多次**而不是「一次取够」：
     * 顺序模式下子项在整棵子树里按名称全局排序，一次取不够时排在后面的文件夹一条都摸不到；
     * 随机模式下每次都换一批样本，多取几次才能把每个文件夹都覆盖到。
     */
    private const val FOLDER_COVER_BATCH_PAGE_SIZE = 200

    /**
     * 批量取封面最多取样几次（顺序模式＝翻页，随机模式＝重抽）。
     *
     * 上限 `200 × 3 = 600` 条，兜住超大媒体库，同时不至于把库整个翻一遍。
     */
    private const val FOLDER_COVER_BATCH_MAX_PAGES = 3

    /**
     * 随机模式下每个文件夹多留几倍候选，供最后洗牌用。
     *
     * 与「凑满 4 张」无关 —— 4 张只是最终要的张数，多留几张才有随机可选的余地。
     */
    private const val FOLDER_COVER_BATCH_POOL_FACTOR = 3

    /**
     * 批量取封面时索要的字段 —— 只要「分组键 + 图片标记」。
     *
     * 这个参数的**唯一作用是把默认的大字段顶掉**：默认那份 `Fields` 带
     * `MediaSources` / `Overview` / `People` / `Path` 等，一页几百条时响应体会大出一个数量级，
     * 而拼一张宫格封面一个都用不上。`ImageTags` / `ParentId` 本身在基础 DTO 里就有，
     * 列出来只是为了让「分组靠 ParentId、取图靠 ImageTags」在请求里一眼可见。
     */
    private const val FOLDER_COVER_FIELDS = "ParentId,ImageTags"

    /**
     * 全库搜索一次取多少条（清单 1.1 的例外，理由见 [searchGlobal] 的 KDoc）。
     *
     * 比原来的 100 略高：搜索结果的「浏览价值」随条数快速衰减，取太多反而让网格难用。
     */
    const val GLOBAL_SEARCH_LIMIT = 120

    /**
     * [awaitHomeLoaded] 最长等多久（毫秒）。
     *
     * 正常局域网下首页三发并行也就一秒内回来；给到 8 秒是留给「服务端在扫大表」
     * 的余量。再往上就不等了 —— 那一行横滑在首页最底部，为它拖住用户不值当。
     */
    private const val HOME_LOAD_WAIT_MS = 8_000L

    fun factory(application: Application, boundServer: EmbyServer? = null): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyViewModel(application, boundServer) }
    }
  }
}

internal fun embyPostgresErrorResource(reason: PostgresFailure): Int = when (reason) {
  PostgresFailure.INVALID_CONFIG -> R.string.emby_shared_error_config
  PostgresFailure.CONNECTION -> R.string.emby_shared_error_connection
  PostgresFailure.TIMEOUT -> R.string.emby_shared_error_timeout
  PostgresFailure.AUTHENTICATION -> R.string.emby_shared_error_auth
  PostgresFailure.DATABASE -> R.string.emby_shared_error_database
  PostgresFailure.TLS -> R.string.emby_shared_error_tls
  PostgresFailure.STORAGE -> R.string.emby_shared_error_storage
  PostgresFailure.LOAD -> R.string.emby_shared_error_load
  PostgresFailure.SCHEMA_INCOMPATIBLE -> R.string.emby_shared_error_schema
  PostgresFailure.SOURCE_CHANGED -> R.string.emby_shared_error_source_changed
  PostgresFailure.NOT_FOUND -> R.string.emby_shared_error_not_found
  PostgresFailure.COMMIT_UNKNOWN -> R.string.emby_shared_error_commit_unknown
  PostgresFailure.UNKNOWN -> R.string.emby_shared_error_unknown
}

internal fun embyServerErrorResource(failure: Throwable): Int {
  var candidate: Throwable? = failure
  repeat(10) {
    val current = candidate ?: return R.string.emby_shared_operation_failed
    if (current is PostgresException) return embyPostgresErrorResource(current.reason)
    candidate = current.cause
  }
  return R.string.emby_shared_operation_failed
}

/** 分页加载结果 */
data class EmbyItemsPage(
  val items: List<EmbyItem>,
  val totalCount: Int,
  /** Raw server positions consumed, including hidden/duplicate items. */
  val consumedCount: Int = items.size,
)

/**
 * 全库搜索的结果（人物 + 媒体两条流拼成）。
 *
 * @param items 拼好的结果：人物在前、媒体在后（沿用旧行为）
 * @param totalCount **媒体部分**的服务端总数；人物那部分拿不到权威总数，见 [EmbyViewModel.searchGlobal]
 * @param personCount 本次实际返回的人物条数
 */
data class EmbyGlobalSearchResult(
  val items: List<EmbyItem>,
  val totalCount: Int,
  val personCount: Int,
  val consumedCount: Int = items.size - personCount,
) {
  /**
   * 结果是不是被上限截断了。
   *
   * UI 据此显示「只显示前 N 条，请输入更精确的关键词」——
   * 静默截断会让用户以为「搜全了就是这个结果」，从而错过想找的片。
   */
  val truncated: Boolean get() = totalCount > 0 && totalCount > consumedCount
}
