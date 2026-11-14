package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.CompositionLocalProvider

import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.LibraryOrdering
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryOrderDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryVisibilityMenu
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyPosterCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.OopsErrorAnimation
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonHome
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarRecommendationViewModel
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Emby 首页。
 *
 * 结构参照 Emby 官方客户端：
 * 1. 顶部横向媒体库卡片（电影 / 剧集 / 音乐 …）
 * 2. 继续观看（横向宽卡片）
 * 3. 最新加入（横向海报卡片）
 *
 * 交互约定：**单击直接播放，长按查看媒体详情**。
 *
 * @param onOpenLibrary 点击媒体库入口（下钻到库内）
 * @param onOpenDetail 长按媒体项：打开详情页
 * @param onManageServers 打开 Emby 服务器维护（顶栏用 Emby 官方图标标识）
 * @param onOpenSettings 打开 App 设置页（顶栏齿轮）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyHomeScreen(
  onOpenLibrary: (EmbyItem) -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  onManageServers: () -> Unit,
  onOpenSettings: () -> Unit,
  onBackToServers: (() -> Unit)? = null,
  /**
   * 打开「猜你喜欢 / 即混」独立页（清单 #14）。
   *
   * 同样只传回调不自己 push：导航栈在 [MainScreen] 手里。
   */
  onOpenDiscover: () -> Unit = {},
  /**
   * 打开「合集」管理页（清单 #15）。
   *
   * 合集浏览使用独立入口，不修改服务端媒体内容。
   */
  onOpenCollections: () -> Unit = {},
) {
  val context = LocalContext.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as android.app.Application),
  )

  // 「随便看看」区块用的推荐 ViewModel（清单 #14 的一部分）。
  // 首页只取**即混**这一块 —— 它不需要用户先选种子（没有种子时退化成随机一批），
  // 相似推荐和续播仍然留在独立页：前者要挑种子，后者首页已经有「继续观看」了。
  val mixViewModel: SimilarRecommendationViewModel = viewModel(
    factory = SimilarRecommendationViewModel.factory(
      context.applicationContext as android.app.Application,
    ),
  )
  val mixItems by mixViewModel.mix.collectAsState()

  val server by viewModel.currentServer.collectAsState()
  CompositionLocalProvider(LocalEmbyImageServer provides server) {
  LaunchedEffect(viewModel) { viewModel.refreshHome() }
  val serverConfigState by viewModel.serverLoadState.collectAsState()
  val libraries by viewModel.libraries.collectAsState()
  val resumeItems by viewModel.resumeItems.collectAsState()
  val latestItems by viewModel.latestItems.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()

  // 从播放器 / 详情页回到首页时重拉「继续观看」。
  // 播放器是**另一个 Activity**，首页这层 Composable 在播放期间并不销毁 ⇒ `LaunchedEffect`
  // 不会重跑；而播放进度是异步落到服务器的 ⇒ 不补这一下，退回来看到的还是进去之前那条
  // 旧进度（「刚看完的片子还在原来的位置」）。延迟 800ms 与详情页同口径：等播放器退出时
  // 那次 Stopped 先落库再拉。去抖在 ViewModel 侧（刚整页拉过就不再问）。
  val homeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  val homeRefreshScope = rememberCoroutineScope()
  androidx.compose.runtime.DisposableEffect(homeLifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        homeRefreshScope.launch {
          kotlinx.coroutines.delay(800)
          viewModel.refreshResumeQuietly()
        }
      }
    }
    homeLifecycleOwner.lifecycle.addObserver(observer)
    onDispose { homeLifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // 顶栏「更多」菜单（猜你喜欢 / 合集）
  var moreMenuExpanded by remember { mutableStateOf(false) }

  // ── 下拉刷新 ──
  // 首页是「媒体库 + 继续观看 + 最新加入」三块的组合，服务端上新了片子不会自己冒出来，
  // 所以要给用户一个手动重拉的入口。刷新期间转圈一直转到这批请求真的回来（refreshHomeAndWait），
  // 否则手指一松动画就没了，看不出到底刷没刷。
  val isRefreshing = remember { mutableStateOf(false) }
  // 只有列表停在顶部时才允许下拉刷新：滚到中间往下拉，列表自己会往上滚，不该触发刷新
  val homeListState = rememberLazyListState()

  // ── 「继续观看」这一行的滚动状态（v227 / v230）──
  // 原来这行没传 state，用的是 Compose 内部那个 `rememberLazyListState`：横向偏移会一直
  // 留着 —— 下拉刷新把内容换掉了，位置还停在原处，用户看到的就是「刷了但没回到第一条」。
  //
  // v230：回开头的时机从「刷新结束」挪到「刷新开始」。
  // `PullRefreshCore` 在 `onRefresh()` 跑完之后还要 `delay(delayAfterRefresh = 800ms)` 才把
  // `isRefreshing` 置 false（那段是「别让转圈一闪就没」的观感补偿）⇒ key 挂在 false 上就等于
  // 让用户在**数据已经换好**之后再干看 800ms 的旧横向位置，然后才「咔」地跳回第一条。
  // 这正是「滚到 item(0) 有延迟」。挪到 true 之后：手指一松、转圈刚起来，这一行就已经在
  // 第 0 格了，随后新内容在 0 格上原地换掉，全程看不到回位动作。
  val resumeRowState = rememberLazyListState()
  LaunchedEffect(isRefreshing.value) {
    if (isRefreshing.value) resumeRowState.scrollToItem(0)
  }
  // 静默刷新（从播放器返回、onResume 补拉那种）不走 isRefreshing，靠「第一条换了」兜住。
  // ⚠️ 只拿「第一条的 Id」当 key，不做整表 —— 否则列表里任何一条的进度更新都会把用户
  //    正在横滑的位置弹回去。
  val resumeLeadId = resumeItems.firstOrNull()?.Id
  LaunchedEffect(resumeLeadId) {
    if (resumeLeadId != null) resumeRowState.scrollToItem(0)
  }

  // 长按媒体库卡片 → 通知服务器扫描该库（异步任务，只发指令不等结果）
  val scanScope = rememberCoroutineScope()
  // 长按选中的媒体库：非空时弹操作框，用户点「扫描媒体库」后才真的发请求
  /** 长按位置（root 坐标）：菜单锚在手指旁边展开 */
  var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

  // 长按「继续观看」卡片 → 弹确认框，确认后从服务器与本机列表一起移除

  // ── 媒体库的置顶 / 顺序号 ──
  // 两条偏好都按服务器分开存，切换服务器时各用各的；用 serverId（没服务器时取 0）
  // 当 remember 的键，切服务器会重新取到那个服务器自己的偏好。
  val browserPreferences = koinInject<BrowserPreferences>()
  val serverId = server?.id ?: 0L

  // 「随便看看」区块的显示开关与条数。默认值见 BrowserPreferences。
  // 关掉时下面那次推荐请求也一起停（LaunchedEffect 的 key 里带了 showHomeMix）。
  val showHomeMix by browserPreferences.embyHomeShowMix.collectAsState()
  val homeMixCount by browserPreferences.embyHomeMixCount.collectAsState()

  val pinsPreference = remember(serverId) { browserPreferences.embyLibraryPins(serverId) }
  val numbersPreference = remember(serverId) { browserPreferences.embyLibraryNumbers(serverId) }
  val hiddenPreference = remember(serverId) { browserPreferences.embyHiddenLibraries(serverId) }
  val pinsRaw by pinsPreference.collectAsState()
  val numbersRaw by numbersPreference.collectAsState()
  val hiddenRaw by hiddenPreference.collectAsState()
  val pins = remember(pinsRaw) { LibraryOrdering.parsePins(pinsRaw) }
  val numbers = remember(numbersRaw) { LibraryOrdering.parseNumbers(numbersRaw) }
  // 「隐藏」与置顶用同一种序列化（逗号分隔的库 Id）—— 两者本来就是同一形状的数据
  val hiddenIds = remember(hiddenRaw) { LibraryOrdering.parsePins(hiddenRaw).toSet() }
  // 显示顺序 = 置顶优先，其次序号（规则见 LibraryOrdering 的类注释）
  val orderedLibraries = remember(libraries, pins, numbers) {
    LibraryOrdering.sortLibraries(libraries, pins, numbers)
  }
  // 首页那一眼真正要摆出来的：全部有序库 **减去**被隐藏的。
  // ⚠️ 隐藏只是**首页这一处**的过滤，「调整顺序号」弹窗和长按菜单仍传 orderedLibraries
  //   （全部）—— 否则被隐藏的库就再也没法取消隐藏了。
  val visibleLibraries = remember(orderedLibraries, hiddenIds) {
    orderedLibraries.filterNot { it.Id != null && it.Id in hiddenIds }
  }
  // 长按顶栏 Emby 图标弹出的「媒体库显示 / 隐藏」小菜单
  var visibilityMenuExpanded by remember { mutableStateOf(false) }

  // 新出现的媒体库（服务端新加了库 / 换了服务器）补一个序号，落在队尾。
  // 只在真的缺号时才写盘，避免每次重组都往偏好里塞一遍同样的值。
  LaunchedEffect(libraries, serverId) {
    if (serverId == 0L || libraries.isEmpty()) return@LaunchedEffect
    val current = LibraryOrdering.parseNumbers(numbersPreference.get())
    val updated = LibraryOrdering.ensureNumbers(libraries.mapNotNull { it.Id }, current)
    if (updated != current) {
      numbersPreference.set(LibraryOrdering.serializeNumbers(updated))
    }
  }

  // 拉「随便看看」那一批。
  // 只跟服务器 id 走：下拉刷新不再重复拉这一批（首页本来就有三个区块在刷），
  // 想换内容点区块标题上的「换一批」即可。
  // ⚠️ `showHomeMix` 进 key：用户在设置里关掉这个开关后，这里要**立刻停掉请求**，
  // 不是「下次进首页才生效」。所以开关是 LaunchedEffect 的 key 之一，
  // 关掉时 effect 重跑并在第一行就 return。
  // 排在首页三请求**之后**（2026-10-15）：这一行横滑在页面最底部，用户得滚好久
  // 才看得见，没道理跟「媒体库 / 继续观看 / 最新加入」抢服务端。十万级条目的库上
  // 每一发都可能是数百毫秒到数秒，四发同时打过去只会让首屏更晚出来
  // （详见 EmbyViewModel.loadHome / awaitHomeLoaded 的说明）。
  LaunchedEffect(serverId, showHomeMix) {
    if (!showHomeMix) return@LaunchedEffect
    val current = server ?: return@LaunchedEffect
    if (serverId == 0L) return@LaunchedEffect
    viewModel.awaitHomeLoaded()
    mixViewModel.loadMix(current, seed = null, limit = homeMixCount)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    TopAppBar(
      navigationIcon = {
        if (onBackToServers != null) IconButton(onClick = onBackToServers) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.app_back_to_servers))
        }
      },
      title = {
        Column {
          Text(
            text = server?.name ?: "Emby",
            style = MaterialTheme.typography.titleLarge,
            // ⚠️ 服务器名是用户自己起的，长度不可控（默认还带「未登录」第二行）。
            //   TopAppBar 的 actions 会挤占标题宽度，名字长就该**省略**而不是
            //   硬撑把顶栏撑开 —— 单行 + Ellipsis 才不会把按钮挤出可视区。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (server != null && server?.isLoggedIn != true) {
            Text(
              text = "未登录",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              maxLines = 1,
            )
          }
        }
      },
      actions = {
        // ⚠️ **这里原来还有一个 `Icons.Default.Dns`「切换服务器」按钮，已删**。
        //   切服务器在服务器管理页里本来就能做，而顶栏每个图标都在挤压标题里的
        //   **服务器名称**（用户反馈「服务器名称都放不下了」）⇒ 不如把这个位置还给标题。
        //   需要切换时：Emby 维护 → 服务器管理。
        // 下载管理：有任务在跑时角标显示数量。
        // 高频（用户在下载页和首页之间来回切），所以留在顶栏不进「更多」。
        // Emby 维护：用官方 Emby 图标（品牌绿），与下方齿轮明确区分。
        // 同理常驻顶栏 —— 服务器掉线、认证过期、任务异常都得从这里处理。
        // **长按**这个图标弹「媒体库显示」小菜单（勾选决定首页媒体库横滑里显示哪些）。
        // 复用长按媒体库卡片那套 `DropdownMenu` 版式，两个长按菜单看起来是同一家族。
        // 单击照旧进服务器维护 —— 长按是额外的一层，不改动原有的单击语义。
        Box {
          EmbyMaintainButton(
            onClick = onManageServers,
            onLongClick = { visibilityMenuExpanded = true },
          )
          // 列的是**全部**已排序库（不是 visibleLibraries）：被隐藏的那几个也得
          // 出现在菜单里，否则用户没法把它们勾回来。
          EmbyLibraryVisibilityMenu(
            expanded = visibilityMenuExpanded,
            libraries = orderedLibraries,
            hiddenIds = hiddenIds,
            onToggle = { id ->
              val next = if (id in hiddenIds) hiddenIds - id else hiddenIds + id
              hiddenPreference.set(LibraryOrdering.serializePins(next.toList()))
            },
            // 全部勾选 = 一个都不隐藏（写空串，回到默认）
            onSelectAll = { hiddenPreference.set("") },
            // 全部取消 = 全部隐藏
            onDeselectAll = {
              hiddenPreference.set(
                LibraryOrdering.serializePins(libraries.mapNotNull { it.Id }),
              )
            },
            onDismissRequest = { visibilityMenuExpanded = false },
          )
        }
        // ── 更多（清单：顶栏精简）────────────────────────────────────
        // 低频的只读推荐和合集浏览集中到更多菜单，保留服务器名称空间。
        Box {
          IconButton(onClick = { moreMenuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多功能")
          }
          DropdownMenu(
            expanded = moreMenuExpanded,
            onDismissRequest = { moreMenuExpanded = false },
          ) {
            DropdownMenuItem(
              text = { Text("猜你喜欢 / 连播") },
              leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
              onClick = {
                moreMenuExpanded = false
                onOpenDiscover()
              },
            )
            DropdownMenuItem(
              text = { Text("合集") },
              leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
              onClick = {
                moreMenuExpanded = false
                onOpenCollections()
              },
            )
            }
        }
        // 设置：放在最后且永不折叠 —— 用户要的就是「设置按钮别再被挤没」。
        // 播放器 / 外观 / 解码等偏好都在这里。
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Default.Settings, contentDescription = "设置")
        }
      },
    )

    when {
      // 加载尚未结束时不判定「没有服务器」，避免冷启动（服务器还在异步恢复）闪一下空状态
      server == null && !isLoading && error == null -> EmbyEmptyState(
        message = if ((serverConfigState as? app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState.Ready)?.servers?.isNotEmpty() == true) {
          stringResource(R.string.emby_shared_no_usable_server)
        } else {
          stringResource(R.string.emby_shared_empty)
        },
        buttonText = stringResource(R.string.cine_drawer_server_manage),
        onAction = onManageServers,
      )

      error != null && libraries.isEmpty() -> EmbyEmptyState(
        message = error ?: "加载失败",
        buttonText = "重试",
        onAction = { viewModel.refreshHome() },
        // 连不上服务器 / 请求失败 ⇒ 给 Oops 指示牌（「这里坏了」而不是「这里没东西」）
        isError = true,
      )

      isLoading && libraries.isEmpty() -> EmbySkeletonHome()

      else -> {
        PullRefreshBox(
          isRefreshing = isRefreshing,
          onRefresh = { viewModel.refreshHomeAndWait() },
          modifier = Modifier.fillMaxSize(),
          listState = homeListState,
        ) {
          LazyColumn(
            state = homeListState,
            modifier = Modifier.fillMaxSize(),
            // 底栏高度由 MainScreen 下发（首页是 tab 之一，一定在 Provide 内）；
            // +16dp 与原 96dp 等值，底栏高度若变则自动跟随。
            contentPadding = PaddingValues(bottom = LocalNavigationBarHeight.current + 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            // ⚠️ 用 visibleLibraries 而不是 libraries：被长按菜单隐藏掉的库
            //   连「媒体库」这个区块标题一起收掉（全隐藏时整块消失是预期行为）。
            // ⚠️ 聚合库不参与「隐藏」：它是本 App 本地的定义，没有服务端扫描之类的
            //   副作用，也没有「隐藏一个库」的用户心智；要收起来去管理页删。
            if (visibleLibraries.isNotEmpty()) {
              item {
                EmbySectionHeader(
                  title = stringResource(R.string.emby_section_libraries),
                  modifier = Modifier.padding(top = 10.dp),
                )
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(visibleLibraries, key = { it.Id ?: it.Name ?: "" }) { library ->
                    val libraryName = library.Name ?: "媒体库"
                    EmbyLibraryCard(
                      name = libraryName,
                      itemCount = library.ChildCount,
                      imageUrl = viewModel.imageUrl(server!!, library, "Primary", 300),
                      icon = libraryIcon(library.CollectionType),
                      // 置顶的在卡片右上角钉一枚小图钉，用户一眼能看出哪两个被钉住了
                      pinned = library.Id != null && library.Id in pins,
                      onClick = { onOpenLibrary(library) },
                      // 长按 = 弹出操作框（扫描媒体库等）。扫描是作用在服务器上的异步任务、
                      // 发出去撤不回来，所以不直接执行，先让用户确认
                    )
                  }
                  // 聚合库卡片：与真实库并列，排在其后。
                  // 图标用 Layers（与真实库的 PhotoLibrary / Tv 明显错开），
                  // 角标写「N 库聚合」—— 用户明确要求「一眼能看出这是聚合库」。
                }
              }
            }

            if (resumeItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = stringResource(R.string.emby_section_resume))
                LazyRow(
                  state = resumeRowState,
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(resumeItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    EmbyWideCard(
                      title = viewModel.displayTitle(item),
                      contentDescription = item.Name,
                      subtitle = item.SeriesName ?: item.ProductionYear?.toString(),
                      imageUrl = viewModel.imageUrl(server!!, item, "Backdrop", 640),
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Primary", 640),
                      progress = playbackProgress(item),
                      remainingText = remainingLabel(item),
                      // 单击是进详情页而非直接播放，去掉居中的播放三角，避免误导
                      showPlayButton = false,
                      isFavorite = item.UserData?.IsFavorite == true,
                      // 单击进入详情页，播放由详情页发起
                      onClick = { onOpenDetail(item) },
                      // 长按 → 从「继续观看」移除（清服务器播放位置 + 本机列表摘掉）
                    )
                  }
                }
              }
            }

            if (latestItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = stringResource(R.string.emby_section_latest))
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(latestItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    EmbyPosterCard(
                      title = viewModel.displayTitle(item),
                      contentDescription = item.Name,
                      subtitle = item.ProductionYear?.toString(),
                      imageUrl = viewModel.imageUrl(server!!, item, "Primary", 480),
                      // 单集等条目可能没有 Primary（海报），回退到 Thumb（剧照/缩略图）
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Thumb", 480),
                      progress = playbackProgress(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      // 单击进入详情页，播放由详情页发起
                      onClick = { onOpenDetail(item) },
                    )
                  }
                }
              }
            }

            // 「随便看看」：即混的一批，不占独立页也能有点击。
            // 放在「最新加入」下面 —— 那块是「刚出的新片」，这块是「点开就有惊喜」，
            // 语义不重复。整块只给一行横滑，点卡片进详情，点标题右侧按钮换一批。
            // `showHomeMix &&` 是设置里那个开关：关掉时整块消失（请求也已经停了）。
            if (showHomeMix && mixItems.isNotEmpty()) {
              item {
                EmbySectionHeader(
                  title = stringResource(R.string.emby_section_mix),
                  action = {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      SectionActionPill(onClick = {
                        val current = server ?: return@SectionActionPill
                        // 换一批沿用当前条数设置，不然用户设了 12 条、
                        // 点一下「换一批」突然变成 100 条，视觉上像是坏了
                        mixViewModel.loadMix(current, seed = null, limit = homeMixCount)
                      }) { Text(stringResource(R.string.emby_action_refresh_batch)) }
                      SectionActionPill(onClick = onOpenDiscover) {
                        Text(stringResource(R.string.emby_action_more))
                      }
                    }
                  },
                )
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(mixItems, key = { it.Id ?: it.hashCode().toString() }) { item ->
                    EmbyPosterCard(
                      title = viewModel.displayTitle(item),
                      contentDescription = item.Name,
                      subtitle = item.ProductionYear?.toString() ?: item.Type,
                      imageUrl = viewModel.imageUrl(server!!, item, "Primary", 480),
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Thumb", 480),
                      progress = playbackProgress(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      onClick = { onOpenDetail(item) },
                    )
                  }
                }
              }
            }

            // 聚合库也要算进「有内容」：本地建了聚合库、服务端真实库却为空时，
            // 摆一句「还没有可显示的媒体」在聚合卡片下面很违和
            if (libraries.isEmpty() &&
              resumeItems.isEmpty() && latestItems.isEmpty() && !isLoading
            ) {
              item {
                EmbyEmptyState(
                  message = "这个服务器上还没有可显示的媒体",
                  buttonText = "刷新",
                  onAction = { viewModel.refreshHome() },
                  modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                )
              }
            }
        }
        }
      }
    }

  }
  }
}

// playbackProgress / remainingLabel 已抽到同包 EmbyItemProgress.kt（首页与媒体库共用一份）。

/** 按媒体库类型挑选图标 */
private fun libraryIcon(collectionType: String?): ImageVector = when (collectionType) {
  "tvshows" -> Icons.Default.Tv
  else -> Icons.Default.PhotoLibrary
}

/**
 * 空态 / 错误态的通用占位（全 Emby 模块共用）。
 *
 * @param isError **true = 这是出错了**（连不上服务器、加载失败），不是「本来就没内容」。
 *   这两种情况外观必须分开：出错给 [OopsErrorAnimation] 动态指示牌，
 *   一眼就知道「这里坏了、可以重试」；单纯的空态仍然只给一个静态图标 ——
 *   给空态也挂 Oops 是在骗用户「你遇到问题了」。
 */
@Composable
fun EmbyEmptyState(
  message: String,
  buttonText: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (isError) {
      OopsErrorAnimation(
        modifier = Modifier.size(width = 128.dp, height = 132.dp),
        caption = "哎呀，出了点小问题",
      )
    } else {
      Icon(
        imageVector = Icons.Default.Dns,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = message,
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onAction) {
      Text(buttonText)
    }
  }
}

/** 列表里的错误横幅（有内容但某一页失败了）。同样给 Oops 指示牌，只是小一号。 */
@Composable
fun EmbyErrorBanner(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    OopsErrorAnimation(
      modifier = Modifier
        .size(width = 112.dp, height = 116.dp)
        .padding(bottom = 8.dp),
      caption = "哎呀，出了点小问题",
    )
    Text(
      text = message,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry) {
      Text("重试")
    }
  }
}

/**
 * 分区标题右侧的小操作钮（「换一批」「更多」）。
 *
 * 形态：胶囊 + 主题色 12% 底 + 主题色文字，**无描边**。
 * - 不用描边钮（`OutlinedButton`）：在这个位置读起来像「一个带框的输入框」，
 *   细边线和旁边海报卡的边线互相打架，整行显得毛糙。
 * - 不用实心 `primary` 填充：那是主行动条的重量；分区标题旁边摆两块实心，
 *   「换一批」会比标题还抢眼。
 * - 12% 的主题色底足够让人看出「这是一块可点的东西」，又不至于压过标题。
 *
 * ⚠️ 弹窗里的「确定 / 取消」**不要**用这个 —— 那两处走 `TextButton`（平台惯例），
 * 早期把两者混成一个组件，结果是弹窗按钮像个分区工具。
 */
@Composable
private fun SectionActionPill(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = modifier,
    shape = RoundedCornerShape(percent = 50),
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.primary,
  ) {
    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
      )
    }
  }
}

/** 把 tick 时长转成「1小时23分」这类中文可读文本 */
fun formatDuration(ticks: Long?): String {
  val totalMinutes = EmbyTicks.ticksToSeconds(ticks ?: 0) / 60
  if (totalMinutes <= 0) return ""
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
