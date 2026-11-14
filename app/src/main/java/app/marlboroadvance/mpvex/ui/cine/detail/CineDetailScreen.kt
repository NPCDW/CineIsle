package app.marlboroadvance.mpvex.ui.cine.detail

import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySeriesDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySeasonDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyTagItemsScreen
import app.marlboroadvance.mpvex.ui.browser.emby.replaceTopEmbyDetailEntry
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberDominantColor
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarRecommendationViewModel
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineActionSheetHost
import app.marlboroadvance.mpvex.ui.cine.actions.rememberCineActionSheetState
import app.marlboroadvance.mpvex.ui.cine.components.CineFloatingBackButton
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterRow
import app.marlboroadvance.mpvex.ui.cine.components.CineSectionHeader
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineGlass
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.rememberCinePlayController
import app.marlboroadvance.mpvex.ui.cine.rememberCineScreenHeightDp
import app.marlboroadvance.mpvex.ui.cine.rememberCineWide
import app.marlboroadvance.mpvex.ui.cine.toCineMedia
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineColors
import app.marlboroadvance.mpvex.ui.cine.tokens.cineColorsForPoster
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.pressDownScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 详情页（v134 版式）：剧照 + 居中信息块 + 横滑分集。
 *
 * ## 版式来源与本地化取舍
 * 信息结构与主流流媒体客户端的详情页对齐（剧照渐隐 → 评分 → 规格 → 类型 →
 * 全宽主按钮 → 次操作 → 简介 → 分集 → 演职员），**材质**沿用 Cine 自己的玻璃语言。
 * 两处刻意的本地化：
 * 1. 参考版没有文字标题（海报自带艺术字）。这里做成「有 ClearLogo 用 Logo、
 *    没有则居中文字标题」—— Emby 库里相当一部分条目没有 Logo，全去掉会认不出片子。
 * 2. 参考版右上角有搜索。详情页里调全局搜索会打断上下文，这里只保留「更多」。
 *
 * ## 数据都提到本层
 * 分集 / 相似推荐都放在这里而不是各自的区块里：LazyColumn 滚出视口会销毁组合，
 * 请求写在区块内部的话，上下滑一趟就会把整部剧的分集重复拉好几轮（Classic 踩过）。
 *
 * ## 功能对齐 Classic
 * 「更多」菜单复用 [CineActionSheetHost]（长按菜单同款）。
 */
@Composable
fun CineDetailScreen(
  itemId: String,
  title: String,
  detailEntryId: String,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val errorTitle = stringResource(R.string.cine_state_error_title)
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val similarViewModel: SimilarRecommendationViewModel = viewModel(
    factory = SimilarRecommendationViewModel.factory(app),
  )
  val backStack = LocalBackStack.current
  val replaceEpisode: (EmbyItem) -> Unit = { episode ->
    episode.Id?.takeIf { it.isNotBlank() }?.let { id ->
      replaceTopEmbyDetailEntry(backStack, detailEntryId, id, episode.displayTitle())
    }
  }
  val scope = rememberCoroutineScope()
  val server by viewModel.currentServer.collectAsState()
  CompositionLocalProvider(LocalEmbyImageServer provides server) {
  val prefs = koinInject<BrowserPreferences>()
  val showRecommendations by prefs.embyShowRecommendations.collectAsState()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val baseColors = CineTheme.colors
  val screenHeight = rememberCineScreenHeightDp()

  val initialServer = server
  var item by remember(itemId, initialServer) {
    mutableStateOf(initialServer?.let { EmbyDetailCache.item(it, itemId) })
  }
  var loading by remember(itemId, initialServer) { mutableStateOf(item == null) }
  var error by remember(itemId) { mutableStateOf<String?>(null) }
  var reloadTick by remember(itemId) { mutableIntStateOf(0) }

  var episodes by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var children by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var seasonIndex by remember(itemId) { mutableIntStateOf(0) }
  // 正序 / 倒序：长篇番剧追更时倒序更实用，且只影响本地展示
  var descending by remember(itemId) { mutableStateOf(false) }

  var overrideTick by remember { mutableIntStateOf(0) }
  var overviewExpanded by remember(itemId) { mutableStateOf(false) }

  val similar by similarViewModel.similar.collectAsState()
  val play = rememberCinePlayController()
  val failedText = stringResource(R.string.cine_favorite_failed)

  // ── 更多菜单（= 完整功能集） ──
  val actionSheet = rememberCineActionSheetState()

  suspend fun load(force: Boolean = false) {
    val current = viewModel.currentServerOrAwait() ?: return
    val hadItem = item != null
    if (!hadItem) loading = true
    // Direct detail routes must pass the browse/play policy before exposing any actions.
    runCatching {
      EmbyDetailCache.loadItem(current, itemId, force) {
        viewModel.loadItemDetail(current, itemId).also { EmbyItemPolicy.requireSupported(it) }
      }
    }
      .onSuccess {
        item = it
        error = null
      }
      .onFailure {
        if (!hadItem) {
          item = null
          episodes = emptyList()
          children = emptyList()
          error = it.message ?: errorTitle
        }
      }
    loading = false
  }

  LaunchedEffect(itemId, initialServer, reloadTick) { load(force = item != null || reloadTick > 0) }

  // 剧集/季使用共用专属页面；这里仅加载合集的直接子项。
  LaunchedEffect(item?.Id, item?.Type, server?.id) {
    episodes = emptyList()
    children = emptyList()
    val current = item ?: return@LaunchedEffect
    val currentServer = server ?: return@LaunchedEffect
    val id = current.Id ?: return@LaunchedEffect
    when (current.Type) {
      // Series 使用共用详情页中的真实季列表，不预取全剧分集。
      "BoxSet" -> children = runCatching {
        val supportedChildren = mutableListOf<EmbyItem>()
        var offset = 0
        var pagesConsumed = 0
        // Bound hidden-only scans when the server omits totals or ignores type exclusions.
        while (supportedChildren.size < 100 && pagesConsumed < 50) {
          currentCoroutineContext().ensureActive()
          val page = viewModel.loadItems(
            server = currentServer,
            parentId = id,
            recursive = false,
            startIndex = offset,
            limit = 100,
          )
          pagesConsumed++
          if (page.consumedCount == 0) break
          supportedChildren.addAll(page.items)
          offset += page.consumedCount
          // A hidden-only page is not an empty server page: keep consuming raw offsets.
          if (page.totalCount > 0) {
            if (offset >= page.totalCount) break
          } else if (page.consumedCount < 100) {
            break
          }
        }
        supportedChildren.take(100)
      }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        emptyList()
      }
    }
  }

  // 相似推荐：与 Classic 同一套算法与开关（设置里关掉就连请求都不发）
  LaunchedEffect(item?.Id, server?.id, showRecommendations) {
    val current = item ?: return@LaunchedEffect
    val currentServer = server ?: return@LaunchedEffect
    if (showRecommendations && !current.Type.equals("Series", ignoreCase = true) &&
      !current.Type.equals("Season", ignoreCase = true) && !current.Type.equals("Episode", ignoreCase = true) &&
      !current.Type.equals("Movie", ignoreCase = true)
    ) {
      similarViewModel.loadSimilar(currentServer, current)
    }
  }

  // 从播放器返回：进度是异步上报的，延迟 800ms 等落库再拉，否则进度条停在旧值
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, itemId) {
    var initialResume = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        if (initialResume) {
          initialResume = false
        } else if (item != null) {
          scope.launch {
            delay(800)
            load(force = true)
          }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val mediaUi: CineMediaUi? = remember(server, item, overrideTick) {
    val current = server
    val loaded = item
    if (current == null || loaded == null) {
      null
    } else {
      val ui = viewModel.toCineMedia(current, loaded)
      ui
    }
  }

  // 共用详情页直接继承 Cine 基底；仅旧 fallback 详情保留海报强调色。
  val sharedDetail = item?.Type.equals("Series", ignoreCase = true) ||
    item?.Type.equals("Season", ignoreCase = true) || item?.Type.equals("Episode", ignoreCase = true) ||
    item?.Type.equals("Movie", ignoreCase = true)
  val posterColor = if (sharedDetail) null else rememberDominantColor(mediaUi?.heroUrl ?: mediaUi?.posterUrl)
  val colors = remember(baseColors, posterColor) {
    cineColorsForPoster(baseColors, posterColor)
  }

  val listState = rememberLazyListState()
  val backdropHeight: Dp = if (wide) {
    minOf(340.dp, (screenHeight * 0.42f).dp).coerceAtLeast(240.dp)
  } else {
    minOf(386.dp, (screenHeight * 0.45f).dp).coerceAtLeast(260.dp)
  }
  val collapse by remember {
    derivedStateOf {
      if (listState.firstVisibleItemIndex > 0) {
        1f
      } else {
        (listState.firstVisibleItemScrollOffset / 220f).coerceIn(0f, 1f)
      }
    }
  }

  /**
   * 集号条要定位到的那一集：优先「有进度且没看完」的最近一集，其次第一个未看的。
   *
   * 用 `LastPlayedDate` 排序而不是 `PlaybackPositionTicks` 大小 —— 后者跟「看了多久」
   * 成正比，会和「最近看的」脱钩（看过一半的第 3 集 vs 刚点开 10 秒的第 9 集）。
   */
  val activeEpisodeId = remember(episodes) {
    episodes
      .filter {
        (it.UserData?.PlaybackPositionTicks ?: 0L) > 0L && it.UserData?.Played != true
      }
      .maxByOrNull { it.UserData?.LastPlayedDate.orEmpty() }
      ?.Id
      ?: episodes.filter { it.UserData?.Played != true }
        .minByOrNull { it.IndexNumber ?: Int.MAX_VALUE }
        ?.Id
  }

  // 染色只作用于详情页这一棵子树。派生规则全在 cineColorsForPoster 里，
  // 组件依旧只读 CineTheme.colors —— 没有一处散着写色值。
  // 弹层（功能菜单 / 外部播放器选择）刻意留在 provider 之外：它们不是「这部片的页面」。
  CompositionLocalProvider(LocalCineColors provides colors) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .background(colors.surface),
    ) {
    when {
      loading && item == null -> CineDetailSkeleton(
        backdropHeight = backdropHeight,
        modifier = Modifier.fillMaxSize(),
      )

      item == null -> CineStatePanel(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.cine_state_error_title),
        description = error,
        isError = true,
        primaryLabel = stringResource(R.string.cine_state_error_action),
        onPrimary = { reloadTick++ },
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding(),
      )

      else -> {
        val loaded = item!!
        val ui = mediaUi
        val cast = remember(loaded.People) {
          loaded.People.orEmpty().filter { it.Type == "Actor" && it.Id != null }.take(12)
        }
        val seasonGroups = remember(episodes) { groupEpisodesBySeason(episodes) }
        val castAvatarUrl: (EmbyPerson) -> String? = { person ->
          val current = server
          val personId = person.Id
          if (current == null || personId == null) {
            null
          } else {
            EmbyClient.imageUrl(current, personId, "Primary", person.PrimaryImageTag, 240)
          }
        }
        val episodeThumbUrl: (EmbyItem) -> String? = { episode ->
          val current = server
          if (current == null) {
            null
          } else {
            viewModel.imageUrl(current, episode, "Backdrop", 480)
              ?: viewModel.imageUrl(current, episode, "Primary", 480)
          }
        }

        if (loaded.Type.equals("Series", ignoreCase = true)) {
          server?.let { currentServer ->
            EmbySeriesDetailContent(
              item = loaded,
              server = currentServer,
              viewModel = viewModel,
              listState = listState,
              showBackButton = false,
              modifier = Modifier.cineHazeSource(LocalCineHaze.current),
              onBack = { backStack.removeLastOrNull() },
              onOpenSeason = { season ->
                season.Id?.let {
                  backStack.add(EmbyDetailScreen(it, season.Name.orEmpty()))
                }
              },
              onOpenPerson = { person ->
                person.Id?.let { backStack.add(EmbyPersonScreen(it, person.Name.orEmpty(), person.PrimaryImageTag)) }
              },
              onOpenStudio = { studio ->
                backStack.add(EmbyTagItemsScreen(studio.Name.orEmpty(), "studio", studio.Id.orEmpty()))
              },
            )
          }
        } else if (loaded.Type.equals("Season", ignoreCase = true)) {
          server?.let { currentServer ->
            EmbySeasonDetailContent(
              season = loaded,
              server = currentServer,
              viewModel = viewModel,
              modifier = Modifier,
              onBack = { backStack.removeLastOrNull() },
              onOpenEpisode = { episode -> episode.Id?.let { backStack.add(EmbyDetailScreen(it, episode.displayTitle())) } },
            )
          }
        } else if (loaded.Type.equals("Episode", ignoreCase = true) || loaded.Type.equals("Movie", ignoreCase = true)) {
          server?.let { currentServer ->
            EmbyMediaDetailContent(
              item = loaded,
              server = currentServer,
              viewModel = viewModel,
              modifier = Modifier,
              onBack = { backStack.removeLastOrNull() },
              onOpenEpisode = replaceEpisode,
              onOpenSeries = { series -> series.Id?.let { backStack.add(EmbyDetailScreen(it, series.Name.orEmpty())) } },
              onOpenPerson = { person -> person.Id?.let { backStack.add(EmbyPersonScreen(it, person.Name.orEmpty(), person.PrimaryImageTag)) } },
            )
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxSize()
              // 详情页顶栏两态都是玻璃（图上悬浮钮 / 滚过 backdrop 后的玻璃条），
              // 模糊源就是这一列 —— 一定要挂在滚动容器上，挂在 item 上滚走就没底了
              .cineHazeSource(LocalCineHaze.current)
              .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
          ) {
            item(key = "hero") {
              CineDetailHero(
                item = loaded,
                media = ui,
                backdropHeight = backdropHeight,
                wide = wide,
                cast = cast,
                castAvatarUrl = castAvatarUrl,
                overviewExpanded = overviewExpanded,
                onPlay = {
                  if (viewModel.isContainerOnly(loaded)) {
                    play.playFromStart(loaded)
                  } else {
                    play.play(loaded, ui?.resumeSeconds ?: 0L)
                  }
                },
                onToggleOverview = { overviewExpanded = !overviewExpanded },
                onOpenPerson = onPerson@{ person ->
                  val personId = person.Id ?: return@onPerson
                  backStack.add(
                    EmbyPersonScreen(
                      personId = personId,
                      personName = person.Name ?: "",
                      personImageTag = person.PrimaryImageTag,
                    ),
                  )
                },
              )
            }

            if (seasonGroups.isNotEmpty()) {
              item(key = "episodes") {
                CineEpisodeSection(
                  groups = seasonGroups,
                  selectedIndex = seasonIndex.coerceIn(0, seasonGroups.lastIndex),
                  onSelectSeason = { seasonIndex = it },
                  currentEpisodeId = activeEpisodeId,
                  thumbUrl = episodeThumbUrl,
                  descending = descending,
                  onToggleSort = { descending = !descending },
                  onPlayEpisode = { episode ->
                    episode.Id?.let { backStack.add(EmbyDetailScreen(it, episode.displayTitle())) }
                  },
                )
              }
            }

            if (children.isNotEmpty()) {
              item(key = "children") {
                // remember 必须留在 item 的 composable 作用域里：
                // LazyListScope 的 if 分支不是 composable 上下文
                val childUi = remember(server, children) {
                  val current = server ?: return@remember emptyList<CineMediaUi>()
                  children.map { viewModel.toCineMedia(current, it) }
                }
                Column {
                  Spacer(Modifier.height(space.sectionGap))
                  CineSectionHeader(title = stringResource(R.string.cine_detail_contents))
                  CinePosterRow(
                    items = childUi,
                    cardWidth = space.posterWidth(wide),
                    wide = wide,
                    onClick = { media ->
                      if (viewModel.isContainerOnly(media.item) || media.item.Type.equals("Episode", ignoreCase = true)) {
                        backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
                      } else {
                        play.play(media.item, media.resumeSeconds)
                      }
                    },
                  )
                }
              }
            }

            if (showRecommendations && similar.isNotEmpty()) {
              item(key = "similar") {
                val similarUi = remember(server, similar) {
                  val current = server ?: return@remember emptyList<CineMediaUi>()
                  similar.take(12).map { viewModel.toCineMedia(current, it.item) }
                }
                Column {
                  Spacer(Modifier.height(space.sectionGap))
                  CineSectionHeader(title = stringResource(R.string.cine_detail_similar))
                  CinePosterRow(
                    items = similarUi,
                    cardWidth = space.posterWidth(wide),
                    wide = wide,
                    onClick = { media ->
                      backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
                    },
                  )
                }
              }
            }
          }
        }
      }
    }

    // 顶栏两态：压在剧照上 = 浮动圆形按钮；滚过 Backdrop = 玻璃条
    if (!item?.Type.equals("Season", ignoreCase = true) && !item?.Type.equals("Episode", ignoreCase = true) &&
      !item?.Type.equals("Movie", ignoreCase = true)) {
      DetailTopBar(
        collapse = if (item == null) 1f else collapse,
        title = item?.displayTitle()?.takeIf { it.isNotBlank() } ?: title,
        onBack = { backStack.removeLastOrNull() },
        onMore = mediaUi?.let { media -> { actionSheet.show(media) } },
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
    }
  }

  // ── 只读功能菜单 ──
  CineActionSheetHost(
    state = actionSheet,
    server = server,
    viewModel = viewModel,
    playController = play,
    // fromDetail = true 时「详情」项不画，所以这个回调不会被调用
    onOpenDetail = {},
    fromDetail = true,
  )

  }
}

/**
 * 顶栏两态：压在剧照上是浮动圆钮，滚过之后是玻璃条（与首页同一套语言）。
 *
 * @param onMore 右上角「更多」。传 null 时不画（数据还没到时没有可操作的对象）
 */
@Composable
private fun DetailTopBar(
  collapse: Float,
  title: String,
  onBack: () -> Unit,
  onMore: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val c = collapse.coerceIn(0f, 1f)

  Box(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = c }
        // 真模糊：滚过 backdrop 之后这条栏压在剧照 / 海报上，平涂色会显脏
        .cineGlass(
          shape = RectangleShape,
          elevation = 0.dp,
          blurRadius = 20.dp,
          border = false,
        )
        .statusBarsPadding()
        .height(space.topBarHeight)
        .padding(start = 4.dp, end = space.screenH),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CineIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = null,
        tint = colors.onSurface,
        onClick = onBack,
      )
      Text(
        text = title,
        style = type.pageBarTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .padding(start = 2.dp),
      )
      if (onMore != null) {
        CineIconButton(
          icon = Icons.Rounded.MoreHoriz,
          contentDescription = stringResource(R.string.cine_detail_more),
          tint = colors.onSurface,
          onClick = onMore,
        )
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = 1f - c }
        .statusBarsPadding()
        .padding(start = space.screenH, end = space.screenH, top = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CineFloatingBackButton(onClick = onBack)
      if (onMore != null) {
        CineGlassRoundButton(
          icon = Icons.Rounded.MoreHoriz,
          contentDescription = stringResource(R.string.cine_detail_more),
          onClick = onMore,
        )
      }
    }
  }
}

/**
 * 压在剧照上的玻璃圆钮（与 [CineFloatingBackButton] 同一套材质）。
 *
 * 不直接复用 `CineFloatingBackButton`：那个组件内部写死了返回箭头，
 * 加个 icon 参数会让「返回」这个语义被稀释 —— 返回键在别的页也在用。
 */
@Composable
private fun CineGlassRoundButton(
  icon: ImageVector,
  contentDescription: String?,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .cineGlass(
        shape = CircleShape,
        elevation = 0.dp,
        blurRadius = 12.dp,
        flatAlpha = 0.46f,
        tintOverride = Color.Black.copy(alpha = 0.42f),
        borderColor = Color.White.copy(alpha = 0.22f),
      )
      .pressDownScale()
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = Color.White,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** 详情骨架：剧照 + 信息块轮廓，与真实版面同高，数据落地不跳位 */
@Composable
private fun CineDetailSkeleton(
  backdropHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(backdropHeight)
        .background(colors.skeleton),
    )
    Column(modifier = Modifier.padding(horizontal = CineTheme.space.screenH, vertical = 20.dp)) {
      Box(
        modifier = Modifier
          .fillMaxWidth(0.6f)
          .height(26.dp)
          .background(colors.skeleton),
      )
      Spacer(Modifier.height(10.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth(0.4f)
          .height(13.dp)
          .background(colors.skeleton),
      )
      Spacer(Modifier.height(22.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .background(colors.skeleton),
      )
    }
  }
}
