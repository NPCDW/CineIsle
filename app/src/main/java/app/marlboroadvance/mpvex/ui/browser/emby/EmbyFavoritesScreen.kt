package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.CompositionLocalProvider

import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.ScrollToTopOnOrderChange
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.koin.compose.koinInject

/**
 * 收藏页的内容类型。影片可播放；演员 / 类型 / 工作室是「点进去看作品清单」的入口，
 * 本身不可播放。
 */
private enum class FavoriteTab(val label: String) {
  MOVIE("影片"),
  ACTOR("演员"),
  GENRE("类型"),
  STUDIO("工作室"),
  ;
}

/** 收藏影片每页条数。 */
private const val MOVIE_PAGE_SIZE = 100

/**
 * 搜索时最多自动往后补拉几页。
 *
 * 本地过滤只能看见已加载的页，不补拉就搜不到后面才出现的片名；但收藏几千部时全翻完
 * 太费流量，故设上限（10 页 ≈ 1000 项），更多内容仍可正常滚动到底加载。
 */
private const val SEARCH_AUTOFILL_MAX_PAGES = 10

/**
 * Emby 收藏页：展示服务器上标记为收藏的媒体、演员、类型与工作室。
 *
 * 标题右侧下拉切换 tab（默认影片），各 tab 列表各自缓存一份，来回切换不重复发请求；
 * 顶栏刷新按钮只重拉当前 tab 并作废其缓存。交互与首页一致：单击进详情 / 作品清单，长按影片播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyFavoritesScreen(onBackToServers: (() -> Unit)? = null) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val server by viewModel.currentServer.collectAsState()
  CompositionLocalProvider(LocalEmbyImageServer provides server) {
  val scope = rememberCoroutineScope()

  // 当前 tab 落盘到偏好而非 rememberSaveable：需要跨冷启动恢复（重启后还停在上次选的 tab），
  // 而 saveable 只防旋转。两者同时维护会双写同一状态，出现不一致时更难排查。
  val browserPreferences = koinInject<BrowserPreferences>()
  // 每行个数跟随媒体库设置，避免两页栏数不一致
  val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()
  // 底栏高度由宿主下发；+16 为呼吸空间
  val favoritesBottomPadding = LocalNavigationBarHeight.current + 16.dp
  var tab by remember {
    mutableStateOf(
      FavoriteTab.entries.firstOrNull { it.name == browserPreferences.embyFavoritesTab.get() }
        ?: FavoriteTab.MOVIE,
    )
  }

  // ── 各 tab 各自的数据 ──
  // 影片走 [PagedList]（分页）；其余三个 tab 一次拉全量，沿用同一套 list 缓存，按 server.id 区分。
  // ⚠️⚠️ 这三份列表必须整体替换（`value = 新 list`），不能用 mutableStateListOf 就地改 ——
  // 原因见下方 filteredItems 处：SnapshotStateList 实例永远不变，当 remember 的 key 等于没有 key。
  var actorItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val actorCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  var genreItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val genreCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  var studioItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val studioCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  /** 影片 tab 的分页缓存：serverId → 已加载页数（0 = 没加载过） */
  val movieCache = remember { mutableStateOf<Pair<Long, Int>?>(null) }
  /** 上次成功拉取时用的排序口径（[sortKey]）。与缓存里的顺序不一致 ⇒ 缓存作废 */
  var movieCacheSortKey by remember { mutableStateOf<String?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  /** 收藏影片的分页状态；筛选不同即使用不同快照，旧请求不会写进当前列表。 */
  val fallbackMoviePage = remember { PagedList<EmbyItem>(MOVIE_PAGE_SIZE, keyOf = { it.Id ?: it.Name ?: "" }) }

  /** 展示列表 = 当前 tab 对应的那一份 */
  val nonMovieItems: List<EmbyItem> = when (tab) {
    FavoriteTab.MOVIE -> emptyList()
    FavoriteTab.ACTOR -> actorItems
    FavoriteTab.GENRE -> genreItems
    FavoriteTab.STUDIO -> studioItems
  }

  // ── 搜索 / 排序 ──
  // 搜索是**本地过滤**，只覆盖已加载的页（不够时自动补拉，见 SEARCH_AUTOFILL_MAX_PAGES）；
  // 排序不同：名称 / 年份 / 评分 / 入库时间四档都下推服务端（分页与排序同口径），
  // 随机浏览在已加载列表中使用固定种子重排。
  var mediaFilter by rememberSaveable { mutableStateOf(FavoriteMediaFilter.ALL) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var sort by remember {
    mutableStateOf(
      FavoriteSort.entries.firstOrNull { it.name == browserPreferences.embyFavoritesSort.get() }
        ?: FavoriteSort.NAME,
    )
  }
  // 方向同样落盘：只存排序项的话，返回页面后方向会被打回默认，比整体重置更让人困惑
  var sortAsc by remember { mutableStateOf(browserPreferences.embyFavoritesSortAsc.get()) }
  // 随机种子只在「切到随机 / 再点一次随机」时变
  // ⚠️ 不能每次重组现洗 —— 敲一个搜索字都会重组，列表会在手指底下乱跳
  var randomSeed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }

  /**
   * 排序口径的字符串键。
   *
   * 它同时是两件事的判据：
   * 1. **页内缓存是否过期** —— 服务端排序参与分页切分，口径一变，缓存里的顺序就作废；
   * 2. 换排序时那条重拉 effect 的 key。
   */
  val sortKey = "${sort.name}:$sortAsc:${mediaFilter.name}"
  val queryServer = server
  val cachedMoviePage = if (queryServer != null) {
    rememberBrowsePage(queryServer, "favorites-v2:$sortKey")
  } else {
    null
  }
  val activeMoviePage = cachedMoviePage ?: fallbackMoviePage
  val items = if (tab == FavoriteTab.MOVIE) activeMoviePage.items else nonMovieItems

  // ⚠️⚠️ `remember(items, ...)` 成立的前提是 items 每次更新都为新的 List 实例。
  // 若用 mutableStateListOf，实例不变 ⇒ key 不变 ⇒ 缓存死在首次（空列表）那一版，
  // 表现为一进页面就弹「没有匹配「」的收藏」且网格空白。
  val filteredItems = remember(items, searchQuery, mediaFilter, tab) {
    items.filter {
      (tab != FavoriteTab.MOVIE || mediaFilter.accepts(it)) &&
        (searchQuery.isBlank() || it.Name?.contains(searchQuery.trim(), ignoreCase = true) == true)
    }
  }
  val visibleItems = remember(filteredItems, sort, sortAsc, randomSeed) {
    sort.apply(filteredItems, sortAsc, randomSeed)
  }

  /**
   * 收藏影片的分页加载器（首屏 / 续拉共用，区别只在起始下标）。
   *
   * 必须显式标注 `suspend (Int, Int) -> PagedResult<EmbyItem>?`：靠推导得到的类型里
   * `loadFavorites` 不带 suspend，编译器会拒绝该调用。
   */
  val queryFilter = mediaFilter
  val querySort = sort
  val querySortAsc = sortAsc
  fun activeMoviePageLoader(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer):
    suspend (Int, Int) -> PagedResult<EmbyItem>? = { start, limit ->
    // 排序口径必须与分页口径一致：分页是按服务端 SortBy 切的，本地再排一遍
    // 补不回没加载的条目（见 FavoriteSort 的 KDoc）。
    // 随机浏览走默认分页口径，再对已加载列表按固定种子重排。
    val page = viewModel.loadFavorites(
      server = current,
      startIndex = start,
      limit = limit,
      sortBy = querySort.serverSortBy,
      sortOrder = if (querySort.hasDirection) (if (querySortAsc) "Ascending" else "Descending") else null,
      includeItemTypes = queryFilter.includeItemTypes,
    )
    // 网格 key = Id ?: Name：服务端可能重复返回同一条目，重复 key 会撞崩 LazyVerticalGrid
    PagedResult(
      items = page.items.filter(queryFilter::accepts).distinctBy { it.Id ?: it.Name ?: "" },
      totalCount = null,
      pageSize = limit,
      exhausted = page.totalCount > 0 && start + page.consumedCount >= page.totalCount,
      consumedCount = page.consumedCount,
    )
  }

  suspend fun loadMovies(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) {
    // 只有本服务器、且**同排序口径**已拉过至少一页才跳过请求（切 tab 回来不再发一次）。
    // ⚠️ 少了 sortKey 的判断，换排序后切回影片 tab 会命中旧缓存 ——
    // 服务端按旧口径切的分页 + 新排序本地排一遍，又回到「排序像没生效」的老毛病。
    val cached = movieCache.value
    if (
      !force &&
        cached != null &&
        cached.first == current.id &&
        activeMoviePage.items.isNotEmpty() &&
        movieCacheSortKey == sortKey
    ) {
      return
    }
    runCatching { activeMoviePage.reload(activeMoviePageLoader(current)) }
      .onFailure { error = it.message ?: "加载收藏失败" }
    if (activeMoviePage.error != null) {
      error = activeMoviePage.error
      movieCache.value = null
      movieCacheSortKey = null
      return
    }
    error = null
    movieCache.value = current.id to 1
    movieCacheSortKey = sortKey
  }

  /**
   * 滚到底：续拉下一页收藏影片，并把页数并进 [movieCache]，
   * 改搜索条件时据此判断还要不要继续往后翻。
   */
  suspend fun loadMoreMovies(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer) {
    if (!activeMoviePage.hasMore) return
    val before = activeMoviePage.items.size
    runCatching { activeMoviePage.loadMore(activeMoviePageLoader(current)) }
      .onFailure { error = it.message ?: "加载更多失败" }
    if (activeMoviePage.items.size > before) {
      movieCache.value = current.id to ((movieCache.value?.second ?: 0) + 1)
    }
  }

  /**
   * 「一次性拉全量」类 tab（演员 / 类型 / 工作室）的通用加载器：命中缓存则直接摊平，
   * 否则请求一次、按 server.id 落缓存并灌进列表。
   *
   * @param cache  该 tab 的页内缓存（serverId → 名单）
   * @param target 列表赋值器（必须整体替换，理由见其声明处）
   * @param fetch  真正发请求的挂起函数
   */
  suspend fun loadAll(
    current: app.marlboroadvance.mpvex.domain.emby.EmbyServer,
    force: Boolean,
    cache: androidx.compose.runtime.MutableState<Pair<Long, List<EmbyItem>>?>,
    target: (List<EmbyItem>) -> Unit,
    failureMessage: String,
    fetch: suspend () -> List<EmbyItem>,
  ) {
    val cached = cache.value
    if (!force && cached != null && cached.first == current.id) {
      target(cached.second)
      return
    }
    runCatching { fetch() }
      .onSuccess { list ->
        // 按同一 key 口径去重，防网格崩溃
        val deduped = list.distinctBy { it.Id ?: it.Name ?: "" }
        cache.value = current.id to deduped
        target(deduped)
      }
      .onFailure { error = it.message ?: failureMessage }
  }

  suspend fun loadActors(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = actorCache,
      target = { actorItems = it },
      failureMessage = "加载收藏演员失败",
      fetch = { viewModel.loadFavoritePersons(current, forceRefresh = force) },
    )

  suspend fun loadGenres(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = genreCache,
      target = { genreItems = it },
      failureMessage = "加载收藏类型失败",
      fetch = { viewModel.loadFavoriteGenres(current, forceRefresh = force) },
    )

  suspend fun loadStudios(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = studioCache,
      target = { studioItems = it },
      failureMessage = "加载收藏工作室失败",
      fetch = { viewModel.loadFavoriteStudios(current, forceRefresh = force) },
    )

  suspend fun load(force: Boolean) {
    // 冷启动时当前服务器可能还没恢复，这里等一下，避免误判成「没有服务器」
    val current = viewModel.currentServerOrAwait() ?: run {
      isLoading = false
      return
    }
    isLoading = true
    error = null
    when (tab) {
      FavoriteTab.MOVIE -> loadMovies(current, force)
      FavoriteTab.ACTOR -> loadActors(current, force)
      FavoriteTab.GENRE -> loadGenres(current, force)
      FavoriteTab.STUDIO -> loadStudios(current, force)
    }
    isLoading = false
  }

  LaunchedEffect(server?.id, tab, mediaFilter) { load(force = false) }

  /**
   * 换排序 / 反转方向 → **按新口径重拉首页**。
   *
   * 为什么不只是本地重排：分页的切法跟着服务端 `SortBy` 走，本地排只能动「已经加载的
   * 那几页」。选「入库时间 ↓」时若只本地排，得到的是「名字最靠前的 100 条里最晚入库的」，
   * 真正最晚入库的片子可能还没被拉下来 —— 这正是原来「排序看着不太对劲」的根因。
   *
   * ⚠️ `appliedSortKey` 初值就是当前口径 ⇒ 首次组合不触发，避免进页面就请求两次。
   */
  var appliedSortKey by remember { mutableStateOf(sortKey) }
  LaunchedEffect(sortKey, tab, server?.id) {
    if (appliedSortKey == sortKey) return@LaunchedEffect
    appliedSortKey = sortKey
    val current = server ?: return@LaunchedEffect
    if (tab != FavoriteTab.MOVIE) return@LaunchedEffect
    isLoading = true
    loadMovies(current, force = true)
    isLoading = false
  }

  // 下拉刷新：绕过本页缓存与 ViewModel 缓存强制重拉当前 tab
  val isRefreshing = remember { mutableStateOf(false) }
  val gridState = rememberLazyGridState()

  // 换排序（换项 / 反转 / 随机换一批）就回顶：视口停在原处会看到完全另一批内容，
  // 让人以为排序没生效。与媒体库页行为一致。
  // ⚠️ 统一走 ScrollToTopOnOrderChange（内部 requestScrollToItem），直接 scrollToItem(0)
  //    会被 LazyGrid 的 key 锚定抵掉、停在列表中间 —— 见 ui/utils/ScrollToTop.kt。
  ScrollToTopOnOrderChange(
    key = Triple(sort, sortAsc, randomSeed),
    gridState = gridState,
  )

  // ── 触底自动续拉 ──
  // 预取距离跟随「预测性懒加载」开关；演员等 tab 不分页，只在 MOVIE 生效。
  val reachBottomServer = server
  val gridSource = gridState.pagingSource()
  val prefetchDistance = rememberPredictivePreloadDistance(browserPreferences)
  activeMoviePage.AutoLoadMore(
    source = gridSource,
    enabled = server != null && tab == FavoriteTab.MOVIE,
    distance = prefetchDistance,
    loader = { s, l -> activeMoviePageLoader(server!!)(s, l) },
  )

  // ── 搜索时自动补拉后面的页 ──
  // 本地过滤只覆盖已加载的页，不补拉则「第 5 页才有的片名」永远搜不出；
  // 每敲一个字最多补 SEARCH_AUTOFILL_MAX_PAGES 页，避免收藏几千部时跑满流量。
  LaunchedEffect(searchQuery, tab, reachBottomServer?.id) {
    val target = reachBottomServer ?: return@LaunchedEffect
    if (tab != FavoriteTab.MOVIE || searchQuery.isBlank()) return@LaunchedEffect
    var guard = 0
    while (activeMoviePage.hasMore && guard < SEARCH_AUTOFILL_MAX_PAGES) {
      val before = activeMoviePage.items.size
      loadMoreMovies(target)
      // 一页都没新增 = 服务端开始重复返回同一批，再拉也是白拉
      if (activeMoviePage.items.size == before) break
      guard++
    }
  }

  // Scaffold + TopAppBar：自动为状态栏留出安全区域
  Scaffold(
    topBar = {
        EmbyFavoritesTopBar(
          onBackToServers = onBackToServers,
          tab = tab,
          onTabChange = {
            if (it != tab) {
              tab = it
              // 写回偏好：返回 / 重启都停在上次选的类型
              browserPreferences.embyFavoritesTab.set(it.name)
            }
          },
          onRefresh = { scope.launch { load(force = true) } },
        )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      // 搜索 + 排序：只作用于当前 tab 的已加载列表，本地过滤不发请求
      FavoriteSearchSortRow(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        sort = sort,
        asc = sortAsc,
        onSortChange = { newSort, newAsc ->
          sort = newSort
          sortAsc = newAsc
          browserPreferences.embyFavoritesSort.set(newSort.name)
          browserPreferences.embyFavoritesSortAsc.set(newAsc)
          // 切到随机时给新种子，否则沿用旧种子洗出来的还是同一批
          if (newSort == FavoriteSort.RANDOM) randomSeed = Random.nextInt()
        },
        onReshuffle = { randomSeed = Random.nextInt() },
      )
      if (tab == FavoriteTab.MOVIE) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FavoriteMediaFilter.entries.forEach { filter ->
            androidx.compose.material3.FilterChip(
              selected = mediaFilter == filter,
              onClick = { mediaFilter = filter },
              label = { Text(filter.label) },
            )
          }
        }
      }
      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        onRefresh = { load(force = true) },
        gridState = gridState,
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
      when {
        // 加载未结束时不下「没有服务器」的判定，避免冷启动闪一下空状态
        server == null && !isLoading -> EmbyEmptyState(
          message = "当前服务器不可用",
          buttonText = "返回服务器列表",
          onAction = { onBackToServers?.invoke() },
          modifier = Modifier.align(Alignment.Center),
        )

        isLoading && items.isEmpty() ->
          // 骨架屏替代转圈：先摆出网格版式，列表落下时不会整屏跳变
          EmbySkeletonGrid(columns = gridColumns.coerceIn(2, 6), ratio = 3f / 4f)

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load(force = true) } },
          isError = true,
          modifier = Modifier.align(Alignment.Center),
        )

        items.isEmpty() -> EmbyEmptyState(
          message = when (tab) {
            FavoriteTab.MOVIE -> "还没有收藏任何媒体"
            FavoriteTab.ACTOR -> "还没有收藏任何演员"
            FavoriteTab.GENRE -> "还没有收藏任何类型"
            FavoriteTab.STUDIO -> "还没有收藏任何工作室"
          },
          buttonText = "刷新",
          onAction = { scope.launch { load(force = true) } },
          modifier = Modifier.align(Alignment.Center),
        )

        // 有收藏但被搜索滤空不在这里判，见网格后的空态叠加：
        // 让网格常驻，否则每敲一个字都可能卸载重建整个网格、封面集体重载。

        tab == FavoriteTab.MOVIE -> LazyVerticalGrid(
          // 与媒体库页同口径：跟随「每行个数」设置（2~6）
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { item ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = viewModel.displayTitle(item),
              contentDescription = item.Name,
              subtitle = item.ProductionYear?.toString(),
              imageUrl = viewModel.imageUrl(currentServer, item, "Primary", 480),
              fallbackImageUrl = viewModel.imageUrl(currentServer, item, "Backdrop", 480),
              progress = itemProgressOf(item),
              isFavorite = true,
              onClick = {
                val id = item.Id
                if (id != null) backStack.add(EmbyDetailScreen(id, viewModel.displayTitle(item)))
              },
              onLongClick = {
                scope.launch {
                  viewModel.play(
                    currentServer,
                    item,
                    EmbyTicks.ticksToSeconds(item.UserData?.PlaybackPositionTicks ?: 0L),
                  )
                }
              },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              // 条目位移动画：切排序时平滑重排
              modifier = Modifier.animateItem(),
            )
          }
          // 底部状态行：续拉中 / 出错可重试 / 已到底
          // span = maxLineSpan 让它横跨整行（否则只占一格宽度）
          item(span = { GridItemSpan(maxLineSpan) }) {
            EmbyPagingFooter(
              appending = activeMoviePage.appending,
              hasMore = activeMoviePage.hasMore,
              error = activeMoviePage.error,
              loadedCount = activeMoviePage.items.size,
              endText = null,
              onRetry = { scope.launch { server?.let { loadMoreMovies(it) } } },
            )
          }
        }

        tab == FavoriteTab.ACTOR -> LazyVerticalGrid(
          // 与媒体库「演员」分类同一套版式，同样跟随「每行个数」
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { person ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = person.Name.orEmpty(),
              subtitle = null,
              // 人物只有 Primary（头像），人形图标兜底
              imageUrl = viewModel.imageUrl(currentServer, person, "Primary", 480),
              progress = null,
              isFavorite = true,
              placeholder = Icons.Default.Person,
              onClick = {
                val pid = person.Id ?: return@EmbyMediaCard
                // 进该演员的作品清单页（与详情页点演职员头像同一条路由）
                backStack.add(
                  EmbyPersonScreen(
                    personId = pid,
                    personName = person.Name.orEmpty(),
                    personImageTag = person.ImageTags["Primary"],
                  ),
                )
              },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              modifier = Modifier.animateItem(),
            )
          }
        }

        // 类型 / 工作室：点卡片进「该类型 / 工作室下的作品」清单页（与详情页 chip 同一路由）
        else -> LazyVerticalGrid(
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { facet ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = facet.Name.orEmpty(),
              subtitle = null,
              // 类型 / 工作室通常没有 Primary 图，交给卡片默认占位
              imageUrl = viewModel.imageUrl(currentServer, facet, "Primary", 480),
              progress = null,
              isFavorite = true,
              onClick = {
                val name = facet.Name ?: return@EmbyMediaCard
                // 带上条目 Id：结果页顶栏的收藏红心要用它取收藏状态，免得按名字反查。
                // 清单本身的筛选口径不变 —— 类型仍按名字筛（见 EmbyTagItemsScreen）。
                backStack.add(
                  EmbyTagItemsScreen(
                    keyword = name,
                    kind = if (tab == FavoriteTab.STUDIO) "studio" else "genre",
                    filterId = facet.Id.orEmpty(),
                  ),
                )
              },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              modifier = Modifier.animateItem(),
            )
          }
        }
      }

        // ── 搜索滤空的提示：叠在网格上层，不参与上面的 when 二选一 ──
        // ⚠️ 若做成 when 分支与网格互斥，中间态滤出 0 条时整个网格会被卸载重建，
        // 卡片全部重新组合、封面重新加载，表现为搜索时封面闪一下。
        // ⚠️ 只在真的敲了搜索词时提示：空搜索词下滤空只可能是代码 bug。
        if (visibleItems.isEmpty() && items.isNotEmpty() && error == null && server != null && searchQuery.isNotBlank()) {
          EmbyEmptyState(
            message = "没有匹配「${searchQuery.trim()}」的收藏",
            buttonText = "清空搜索",
            onAction = { searchQuery = "" },
            modifier = Modifier.align(Alignment.Center),
          )
        }
      }
    }
    }
  }

  }
}

/**
 * 收藏页顶部标题栏：标题右侧为 tab 下拉切换（用 Surface + DropdownMenu，
 * ExposedDropdownMenuBox 在 TopAppBar 标题区点击判定容易出问题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyFavoritesTopBar(
  onRefresh: () -> Unit,
  onBackToServers: (() -> Unit)? = null,
  /** null = 旧签名调用方（不显示切换器），仅渲染标题与动作键 */
  tab: FavoriteTab? = null,
  onTabChange: (FavoriteTab) -> Unit = {},
) {
  val backStack = LocalBackStack.current
  TopAppBar(
    navigationIcon = {
      if (onBackToServers != null) IconButton(onClick = onBackToServers) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.app_back_to_servers))
      }
    },
    title = {
      if (tab == null) {
        Text("收藏")
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("收藏")
          Spacer(modifier = Modifier.width(10.dp))
          FavoriteTabSwitch(current = tab, onSelected = onTabChange)
        }
      }
    },
    actions = {
      IconButton(onClick = onRefresh) {
        Icon(Icons.Default.Refresh, contentDescription = "刷新")
      }
      // 与首页保持一致：Emby 图标进服务器维护，齿轮进 App 设置
      if (onBackToServers != null) EmbyMaintainButton(onClick = onBackToServers)
      IconButton(onClick = { backStack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen) }) {
        Icon(Icons.Default.Settings, contentDescription = "设置")
      }
    },
  )
}

/** 收藏类型切换器：当前值胶囊 + 下拉菜单。 */
@Composable
private fun FavoriteTabSwitch(
  current: FavoriteTab,
  onSelected: (FavoriteTab) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    Surface(
      onClick = { expanded = true },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      ) {
        Text(
          text = current.label,
          style = MaterialTheme.typography.labelLarge,
        )
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "切换收藏类型",
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      FavoriteTab.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          leadingIcon = {
            // 选中项打勾；未选中放同宽占位，保持文字左对齐
            if (option == current) {
              Icon(Icons.Default.Check, contentDescription = null)
            } else {
              Spacer(modifier = Modifier.width(24.dp))
            }
          },
          onClick = {
            expanded = false
            onSelected(option)
          },
        )
      }
    }
  }
}

@Composable
private fun EmptyText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun itemProgressOf(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 收藏页的排序方式。
 *
 * ## 「服务端排序」与「本地排序」的分界（两处都改了才自洽）
 *
 * 收藏列表是**分页**的，而且分页的切法跟着服务端的 `SortBy` 走。原来一律按
 * `SortName Ascending` 拿页、再在本地重排，得到的是「名字最靠前的 N 条里再排一遍」——
 * 用户选了「最新入库」却看不到真正最晚入库的那部片（它名字靠后，根本没被拉下来）。
 * 所以凡服务端有对应排序的（名称 / 年份 / 评分 / 入库时间），一律**下推**，
 * 见 [serverSortBy]；换排序时会把首页重拉（收藏页里那段 LaunchedEffect）。
 *
 */
private enum class FavoriteSort(
  val label: String,
  /** 升序时的方向提示 */
  val ascHint: String,
  /** 降序时的方向提示 */
  val descHint: String,
  /** 没手动切过方向时的自然方向（与媒体库页同一套规则） */
  val naturalAsc: Boolean,
  /**
   * 下推给服务端的 `SortBy` 取值。null = 服务端没有这个排序（只能本地排）。
   * 取值必须是 Emby `ItemSortBy` 的合法值，写错服务端会 400。
   */
  val serverSortBy: String? = null,
) {
  NAME("名称", "A → Z", "Z → A", naturalAsc = true, serverSortBy = "SortName"),
  YEAR("年份", "旧 → 新", "新 → 旧", naturalAsc = false, serverSortBy = "ProductionYear"),
  RATING("评分", "低 → 高", "高 → 低", naturalAsc = false, serverSortBy = "CommunityRating"),
  // 原来叫「最新添加」，但它排的是媒体的入库时间（DateCreated），与「收藏」无关 ——
  // 名字必须说实话，否则用户会把它当成「我最近收藏的」。
  ADDED("入库时间", "旧 → 新", "新 → 旧", naturalAsc = false, serverSortBy = "DateCreated"),
  // 随机：不分升降序，再点一次换一批
  RANDOM("随机", "再点一次换一批", "再点一次换一批", naturalAsc = true),
  ;

  /** 是否带方向（随机没有） */
  val hasDirection: Boolean get() = this != RANDOM

  /** 本地排序（聚合类 tab 与随机浏览共用）。 */
  fun apply(
    list: List<EmbyItem>,
    asc: Boolean,
    seed: Int = 0,
  ): List<EmbyItem> = when (this) {
    // ⚠️ 随机必须用固定种子：直接 shuffled() 会在每次重组时洗出不同顺序
    RANDOM -> list.shuffled(Random(seed))
    NAME -> sortByKey(list, asc) { (it.SortName ?: it.Name)?.lowercase() }
    YEAR -> sortByKey(list, asc) { it.ProductionYear }
    RATING -> sortByKey(list, asc) { it.CommunityRating }
    // DateCreated 是 ISO-8601 串，字典序即时间序（同格式下成立）
    ADDED -> sortByKey(list, asc) { it.DateCreated }
  }
}

/**
 * 按可空键排序，键值缺失的条目一律沉底（无论升 / 降序）。
 *
 * 不能简单对整体 `reversed()`：那会把无值组一起翻到最前面。故先 partition 出有值与无值
 * 两段，只对前者排序，再把无值段接在末尾。
 *
 * @param key 取排序键；null 表示该条目没有这个值
 */
private inline fun <T : Comparable<T>> sortByKey(
  list: List<EmbyItem>,
  asc: Boolean,
  crossinline key: (EmbyItem) -> T?,
): List<EmbyItem> {
  val (withKey, withoutKey) = list.partition { key(it) != null }
  val comparator = compareBy(key)
  // sortedWith 是稳定排序：键值相同的条目保持原相对顺序
  val sorted = if (asc) withKey.sortedWith(comparator) else withKey.sortedWith(comparator.reversed())
  return sorted + withoutKey
}

/**
 * 顶部的「搜索框 + 排序」一行。搜索是即时的本地过滤（只作用在已加载的页上），
 * 不打服务器所以无需防抖；已加载页不够时由外层副作用自动往后补拉。
 */
@Composable
private fun FavoriteSearchSortRow(
  query: String,
  onQueryChange: (String) -> Unit,
  sort: FavoriteSort,
  asc: Boolean,
  /** 选了某个排序项 + 方向（重复点当前项被本函数判定为「反转方向」，不会走到这里） */
  onSortChange: (FavoriteSort, Boolean) -> Unit,
  /** 已经是随机排序时再点一次：换一批 */
  onReshuffle: () -> Unit,
) {
  var sortMenu by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      modifier = Modifier.weight(1f),
      singleLine = true,
      shape = MaterialTheme.shapes.large,
      placeholder = { Text("搜索收藏") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      trailingIcon = {
        if (query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
          }
        }
      },
    )
    Spacer(modifier = Modifier.width(8.dp))
    Box {
      // 触发区直接标出当前排序，不用点开就知道
      Surface(
        modifier = Modifier.clickable {
          // ⚠️ 展开前先收掉输入框焦点：否则菜单关闭会把焦点还给它，每切一次排序弹一次软键盘
          focusManager.clearFocus()
          sortMenu = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Sort,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(text = sort.label, style = MaterialTheme.typography.labelLarge)
          // 带上方向图标：不看菜单就知道升还是降（随机没有方向，给骰子图标）
          Icon(
            imageVector = if (!sort.hasDirection) {
              Icons.Default.Shuffle
            } else if (asc) {
              Icons.Default.ArrowUpward
            } else {
              Icons.Default.ArrowDownward
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
        FavoriteSort.entries.forEach { option ->
          val selected = option == sort
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  // 选中项直接标出当前方向（↑ 升 / ↓ 降），不单列「升序 / 降序」两个选项
                  text = buildString {
                    append(option.label)
                    if (selected && option.hasDirection) {
                      append(if (asc) " ↑" else " ↓")
                    }
                  },
                  color = if (selected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                )
                // 方向副标题：说明这项在该方向下具体怎么排
                Text(
                  text = if (!option.hasDirection) {
                    option.ascHint
                  } else if (selected) {
                    // 已选中：副标题跟随当前方向
                    if (asc) option.ascHint else option.descHint
                  } else {
                    // 未选中：预告点下去会变成哪个方向（= 该项的自然方向）
                    if (option.naturalAsc) option.ascHint else option.descHint
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            },
            // 勾放尾部（M3 惯例），再用主色文字强化，不靠位置飘忽的首位图标表达选中
            trailingIcon = {
              if (selected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "当前排序",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            },
            onClick = {
              sortMenu = false
              when {
                // 再点一次已选中的项 = 只反转方向（随机则是换一批），不换排序项
                selected && option.hasDirection -> onSortChange(option, !asc)
                selected -> onReshuffle()
                // 换排序项：方向重置为该项的自然方向
                else -> onSortChange(option, option.naturalAsc)
              }
            },
          )
        }
      }
    }
  }
}
