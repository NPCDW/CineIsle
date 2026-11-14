package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.CompositionLocalProvider

import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer

import android.app.Application
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyStudio
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry
import app.marlboroadvance.mpvex.domain.emby.MediaSourcePreference
import app.marlboroadvance.mpvex.domain.emby.buildEmbyMediaInfoEntries
import app.marlboroadvance.mpvex.domain.emby.effectiveEmbyMediaSourceId
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.embyMediaSourceLabel
import app.marlboroadvance.mpvex.domain.emby.formatEmbyDurationFull
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySeriesDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySeasonDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaDetailContent
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonDetail
import app.marlboroadvance.mpvex.ui.browser.emby.components.EpisodeListSection
import app.marlboroadvance.mpvex.ui.browser.emby.components.RelatedEpisodes
import app.marlboroadvance.mpvex.ui.browser.emby.components.RelatedEpisodesSection
import app.marlboroadvance.mpvex.ui.browser.emby.components.isSpecial
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberDominantColor
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 媒体详情页。
 *
 * 布局（沉浸式）：
 * 1. 顶部透明栏浮在剧照之上：左侧返回
 * 2. 剧照（Backdrop）从屏幕顶端一直延伸到按钮下方，标题叠在剧照上
 * 3. 剧照下方依次是播放/删除按钮、简介、演职员，最后是完整的媒体编码信息
 */
@Serializable
data class EmbyDetailScreen(
  val itemId: String,
  val title: String,
  val detailEntryId: String = UUID.randomUUID().toString(),
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val replaceEpisode: (EmbyItem) -> Unit = { episode ->
      episode.Id?.takeIf { it.isNotBlank() }?.let { id ->
        replaceTopEmbyDetailEntry(backStack, detailEntryId, id, episode.displayTitle())
      }
    }
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()
    CompositionLocalProvider(LocalEmbyImageServer provides server) {
    val scope = rememberCoroutineScope()

    val initialServer = server
    var item by remember(itemId, initialServer) {
      mutableStateOf(initialServer?.let { EmbyDetailCache.item(it, itemId) })
    }
    var isLoading by remember(itemId, initialServer) { mutableStateOf(item == null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 下拉刷新的转圈状态（转完由 PullRefreshBox 自己收起）
    val isRefreshing = remember { mutableStateOf(false) }
    // 收藏请求进行中：挡住连点。否则「点了取消、请求还没回来又点一下」会拿旧状态
    // 再算一次方向，把刚取消的又收藏回去 —— 看起来就像「只能点亮、取消不了」。
    // 点播放 → 播放器真正起来之间有一段**实在的等待**：
    // 要解析直链、探测 ISO、拼 intent、再拉起 Activity，冷启动时尤其明显。
    // 这段空窗期什么都不显示的话，用户会以为「点了没反应」而反复点。
    // ⇒ 置 true 后立刻把播放键换成转圈 + 禁用，Activity 一起来就由页面被覆盖自然结束。
    var playLaunching by remember { mutableStateOf(false) }

    // ── 多版本媒体：当前选中的版本 ──
    // null = 还没选，等价于「服务端默认版本」（= MediaSources 的第一条）。
    // 按 itemId 记忆：同一条目在页面存活期间保留选择，换条目则重置。
    var selectedMediaSourceId by remember(itemId) { mutableStateOf<String?>(null) }

    // ── 多版本媒体：当前版本自己的续播进度（本地）──
    // Emby 服务端的 `UserData.PlaybackPositionTicks` 是**按条目**存的 —— 4K / 1080P /
    // 导演剪辑版共用一个续播点，服务端层面无解。所以多版本条目按版本各记各的本地进度
    // （播放页退出时按版本写入，键见 [EmbyVersionProgress]），这里读出来覆盖服务端进度。
    // null = 不覆盖（单版本条目 / 本地还没按版本记过进度 ⇒ 沿用服务端进度）。
    var versionResumeSeconds by remember(itemId) { mutableStateOf<Long?>(null) }
    val playbackStateRepository = koinInject<PlaybackStateRepository>()
    // 「多版本优先选择」规则：用户在播放入口（含按版本存续播进度）
    // 没手动选版本时据此自动挑。用 .get() 而不是 collectAsState 是**刻意的**：
    // 这是一次性取值（点播放的那一刻决定用哪个版本），不需要在改设置时让整页重组。
    // ⚠️ 位置很要紧：下面的 LaunchedEffect 与后面的播放/媒体信息都要用它，
    // Kotlin 属性按源码顺序初始化（红线 17），定义晚了这里会拿到 null。
    val browserPrefsForSource = koinInject<BrowserPreferences>()
    val autoSourcePreference =
      remember(browserPrefsForSource.embyMediaSourcePreference.get()) {
        MediaSourcePreference.fromKey(browserPrefsForSource.embyMediaSourcePreference.get())
      }
    LaunchedEffect(itemId, selectedMediaSourceId, item, server?.id) {
      val current = item
      val currentServer = server
      versionResumeSeconds =
        if (current == null || currentServer == null) {
          null
        } else {
          loadVersionResumeSeconds(
            repository = playbackStateRepository,
            serverId = currentServer.id,
            item = current,
            selectedMediaSourceId = selectedMediaSourceId,
            preference = autoSourcePreference,
          )
        }
    }

    suspend fun load(force: Boolean = false) {
      // 冷启动时当前服务器可能还没恢复，这里等一下，避免静默不加载
      val current = viewModel.currentServerOrAwait() ?: return
      val hadItem = item != null
      if (!hadItem) isLoading = true
      // Direct detail routes must pass the same policy as browse/play before exposing actions.
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
            error = it.message ?: "加载详情失败"
          }
        }
      isLoading = false
    }

    /**
     * 从本页压栈一个子页面（演员作品 / 类型结果页 / 下一层详情页…）。
     *
     * ⚠️ 必须走它而不是直接 `backStack.add`：压栈前要给本条的推荐缓存打一个
     * 「有子页面」标记，子页面返回后本页重新组合时才认得出「这是返回」而不是
     * 「重新进页面」，从而复用上次那批推荐、不重新随机。详见 [EmbyRecommendCache]。
     */
    fun pushChild(screen: Screen) {
      val sid = server?.id
      if (sid != null) EmbyRecommendCache.markChildPushed(sid, item?.Id ?: itemId)
      backStack.add(screen)
    }

    LaunchedEffect(itemId, initialServer) { load(force = item != null) }

    // 剧集详情只展示季列表；单集播放队列由相关分集提供。
    val seriesEpisodes = emptyList<EmbyItem>()

    // 从播放器返回时刷新详情：播放器的进度/已看上报是异步落到服务器的，而本页组合
    // 在播放期间并未销毁（播放器是另一个 Activity），返回时 LaunchedEffect 不会重跑，
    // 不刷新的话进度条就停在进来之前的旧值。延迟 800ms 等上报先落库再拉。
    val detailLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(detailLifecycleOwner, itemId) {
      var initialResume = true
      val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
          if (initialResume) {
            initialResume = false
          } else if (item != null) {
            scope.launch {
              kotlinx.coroutines.delay(800)
              load(force = true)
            }
          }
        }
      }
      detailLifecycleOwner.lifecycle.addObserver(observer)
      onDispose { detailLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 折叠头部的状态（提到这里是因为「下拉刷新」要用到它）──
    // 提到 EmbyDetailScreen 这一层、而不是留在 DetailBody 里，是因为下拉刷新挂在
    // DetailBody **外面**（PullRefreshBox 包着它），而「封面收着的时候禁止下拉刷新」
    // 这个判断需要读到折叠进度。详见下面 PullRefreshBox 处的注释。
    val listState = rememberLazyListState()
    // 详情页推荐区开关：关闭后「推荐」与「同类型推荐」两块整块不渲染，也不发那几次请求。
    val recommendPreferences = koinInject<BrowserPreferences>()
    val showRecommendations by recommendPreferences.embyShowRecommendations.collectAsState()
    /** 「同类型推荐」每次进入是否随机挑一个类型（关掉则固定用第一个类型）。 */
    val randomGenreRecommend by recommendPreferences.embyRandomGenreRecommend.collectAsState()
    // 折叠状态：**只是列表滚动位置的函数**（没有任何自定义手势 / 动画状态）。
    val collapse = rememberHeaderCollapse(
      listState = listState,
      maxHeight = BACKDROP_HEADER_MAX_HEIGHT_DP,
      toolbarHeight = TOOLBAR_HEIGHT_DP,
    )

    // ── 推荐区数据：**提升到本层**（别挪回推荐区内部，见下方注释）──
    // 背景：两个推荐区是 LazyColumn 里的 `item {}`。LazyColumn 会把滚出视口的 item
    // **销毁组合**、滚回来时**重新组合**；而请求原先挂在推荐区内部的 LaunchedEffect 上 ——
    // 键（item.Id / server）虽然没变，但**组合重建本身**就会让 LaunchedEffect 重跑。
    // 于是「从上滑到底再滑回来」会把两个推荐区各重新请求好几轮，用户看到的就是
    // 「滚动时推荐在重复刷新」。
    // 提升到本层后，加载只由这两个键决定：
    //   · item.Id 变化        ⇒ 重新进（或切到另一部片的）详情页
    //   · recommendReloadKey  ⇒ 下拉刷新（见 PullRefreshBox 的 onRefresh）
    // 滚动不在键里，所以无论怎么滚都不会再触发。
    var recommendItems by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var genreRecommendLabel by remember(itemId) { mutableStateOf<String?>(null) }
    var genreRecommendItems by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var recommendReloadKey by remember(itemId) { mutableStateOf(0) }

    // Episode 使用共用页面的当前季分页，不再查询旧的相关分集与演员作品。
    val relatedEpisodes = RelatedEpisodes()

    LaunchedEffect(itemId, item?.Id, server?.id, showRecommendations, recommendReloadKey) {
      if (!showRecommendations) return@LaunchedEffect
      val current = item ?: return@LaunchedEffect
      if (current.Type in listOf("Series", "Season", "Episode", "Movie")) return@LaunchedEffect
      val currentServer = server ?: return@LaunchedEffect
      val currentId = current.Id ?: itemId

      // ── 先试「复用」──
      // 若本次组合是「从本页 push 出去的下一层页面返回」造成的（压栈前留过标记，
      // 见 [EmbyRecommendCache]），就直接拿回上次那批推荐，不再请求、不再随机 ——
      // 用户视角是「我只是点进去看了一眼又回来，推荐不该换」。
      // 从列表 / 首页重新进本页时没有标记 ⇒ 走下面的正常加载，「每次进页面换一批」照旧。
      val restored = EmbyRecommendCache.takeForRestore(currentServer.id, currentId)
      if (restored != null) {
        recommendItems = restored.items
        genreRecommendLabel = restored.genreLabel
        genreRecommendItems = restored.genreItems
        return@LaunchedEffect
      }

      // 清空再取：换条目 / 下拉刷新时不该让用户先看到上一批推荐再被换掉
      recommendItems = emptyList()
      genreRecommendLabel = null
      genreRecommendItems = emptyList()
      // 两块推荐互不依赖，并发取 —— 别让「同类型」等「按人推荐」跑完才开始
      coroutineScope {
        launch {
          recommendItems =
            loadRecommendations(item = current, server = currentServer, viewModel = viewModel)
        }
        launch {
          val genres = current.Genres
          // 候选类型：随机开关打开 → 本片类型打乱后的顺序；关闭 → 只用第一个。
          // 打乱放在这里（effect 每次按上面的键重跑一次），于是「每次进页面 / 每次下拉刷新
          // 都换一批类型」，而滚动期间不会变。
          val candidates = when {
            genres.isEmpty() -> emptyList()
            randomGenreRecommend -> genres.shuffled()
            else -> listOf(genres.first())
          }
          // 逐个试：随机挑到的冷门类型可能查不到同类，命中第一个有结果的就停
          for (g in candidates) {
            val found = loadGenreRecommendations(currentServer, g, current.Id)
            if (found.isNotEmpty()) {
              genreRecommendLabel = g
              genreRecommendItems = found
              break
            }
          }
        }
      }
      // 回写缓存：下次从子页面返回时复用这一批（含「查不到 → 空」的情况，
      // 否则那种条目返回时还会再请求一轮）。
      EmbyRecommendCache.put(
        serverId = currentServer.id,
        itemId = currentId,
        items = recommendItems,
        genreLabel = genreRecommendLabel,
        genreItems = genreRecommendItems,
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        isLoading && item == null -> EmbySkeletonDetail()

        error != null && item == null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          isError = true,
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val current = item ?: return@Box
          val currentServer = server ?: return@Box
          if (current.Type.equals("Season", ignoreCase = true)) {
            EmbySeasonDetailContent(
              season = current,
              server = currentServer,
              viewModel = viewModel,
              onBack = { backStack.removeLastOrNull() },
              onOpenEpisode = { episode -> episode.Id?.let { pushChild(EmbyDetailScreen(it, episode.displayTitle())) } },
            )
            return@Box
          }
          if (current.Type.equals("Episode", ignoreCase = true) || current.Type.equals("Movie", ignoreCase = true)) {
            EmbyMediaDetailContent(
              item = current,
              server = currentServer,
              viewModel = viewModel,
              onBack = { backStack.removeLastOrNull() },
              onOpenEpisode = replaceEpisode,
              onOpenSeries = { series -> series.Id?.let { pushChild(EmbyDetailScreen(it, series.Name.orEmpty())) } },
              onOpenPerson = { person -> person.Id?.let { pushChild(EmbyPersonScreen(it, person.Name.orEmpty(), person.PrimaryImageTag)) } },
            )
            return@Box
          }
          // 多版本媒体：真正要播放的版本 Id。只有存在多个版本时才非空 ——
          // 单版本媒体一律 null，播放链路与改动前完全一致。
          // 用户没在下拉框里选过时，按设置里的「多版本优先选择」规则自动挑。
          val playSourceId =
            effectiveEmbyMediaSourceId(current, selectedMediaSourceId, autoSourcePreference)
          // 版本显示名（与下拉框里显示的**一模一样**）——播放页顶栏据此显示「正在播的是哪个版本」。
          // 单版本条目为 null：那时片名后面不该多出一截。
          // ⚠️ 这个名字必须由 UI 传下去，不能让播放页自己重算：两边算出不同标签就会出现
          //   「下拉里写着 1080P、播放页顶栏写着别的」。
          val playSourceLabel = playSourceId
            ?.let { id -> current.MediaSources.orEmpty().firstOrNull { it.Id == id } }
            ?.let { embyMediaSourceLabel(it) }
          // ── 下拉刷新 ──
          // 详情页的数据（进度、已看状态、演员、推荐）都是进页面那一次拉回来的，
          // 从播放器返回时虽然有 800ms 延迟的自动刷新，但用户手动拉一下仍然是最直接的重取方式。
          // 新版折叠头部已经**不再消费任何手势**（封面高度只是列表滚动位置的函数），
          // 所以这里只要老老实实把 listState 传进去 + 加一道「封面已完全展开」的闸门，
          // 两者就不会再抢手势：
          //  - `listState` ⇒ PullRefreshBox 只在列表贴顶时才接管下拉；
          //  - `enabled = collapse.isExpanded` ⇒ 封面还没完全放回来时，下拉继续归列表
          //    （那段下拉本来就是「把封面滚回来」用的）。
          PullRefreshBox(
            isRefreshing = isRefreshing,
            // 下拉刷新：详情 + 两块推荐一起重取（推荐靠 recommendReloadKey 触发上面那个
            // LaunchedEffect；不加这一句的话下拉只会刷新详情，推荐原地不动）。
            onRefresh = {
              load()
              recommendReloadKey++
            },
            enabled = collapse.isExpanded,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
          ) {
            if (current.Type.equals("Series", ignoreCase = true)) {
              EmbySeriesDetailContent(
                item = current,
                server = currentServer,
                viewModel = viewModel,
                listState = listState,
                onBack = { backStack.removeLastOrNull() },
                onOpenSeason = { season ->
                  season.Id?.let {
                    pushChild(EmbyDetailScreen(it, season.Name.orEmpty()))
                  }
                },
                onOpenPerson = { person ->
                  person.Id?.let { pushChild(EmbyPersonScreen(it, person.Name.orEmpty(), person.PrimaryImageTag)) }
                },
                onOpenStudio = { studio ->
                  pushChild(EmbyTagItemsScreen(studio.Name.orEmpty(), "studio", studio.Id.orEmpty()))
                },
              )
            } else {
              DetailBody(
                item = current,
                server = currentServer,
                listState = listState,
                collapse = collapse,
                showRecommendations = showRecommendations,
                // 剧照当封面区的整张背景图用，所以要按屏幕级别的宽度取（1080 足够铺满常见机型）：
                // 之前取 128px 是为了「放大即虚化」的糊底效果，用户不喜欢 —— 现在直接看清晰大图。
                // EmbyImageLoader 的 maxWidth 只做客户端降采样、不会改写 URL，
                // 所以尺寸必须从源头（这个 URL）给定。
                backdropUrl = viewModel.imageUrl(currentServer, current, "Backdrop", 1080),
                posterUrl = viewModel.imageUrl(currentServer, current, "Primary", 600),
                onPlay = { resume ->
                  // 已经点过一次就别再放一次（连点会起两个播放页）
                  if (!playLaunching) {
                    playLaunching = true
                    scope.launch {
                      try {
                        viewModel.play(
                          currentServer,
                          current,
                          resume,
                          selectedMediaSourceId,
                          mediaSourceLabel = playSourceLabel,
                        )
                      } finally {
                        // ⚠️ 只在**没成功拉起播放器**时复位（比如解析失败 / 抛异常），
                        //  成功拉起时本页已经不显示了，复位也会随重组被丢掉；
                        //  但若这里无条件复位，慢路径下会让转圈提前消失、用户又能连点。
                        playLaunching = false
                      }
                    }
                  }
                },
                playLaunching = playLaunching,
                // 电视剧「从头播放」：走独立的入口，不受大按钮「挑有进度的那集」影响。
                onPlayFromStart = {
                  if (!playLaunching) {
                    playLaunching = true
                    scope.launch {
                      try {
                        viewModel.play(
                          currentServer,
                          current,
                          0L,
                          selectedMediaSourceId,
                          mediaSourceLabel = playSourceLabel,
                          fromStart = true,
                        )
                      } finally {
                        playLaunching = false
                      }
                    }
                  }
                },
                selectedMediaSourceId = selectedMediaSourceId,
                onSelectMediaSource = { selectedMediaSourceId = it },
                versionResumeSeconds = versionResumeSeconds,
                onBack = { backStack.removeLastOrNull() },
                onPersonClick = { pid, pname, tag ->
                  pushChild(
                    EmbyPersonScreen(personId = pid, personName = pname, personImageTag = tag),
                  )
                },
                // 点类型 / 标签 / 工作室 chip：进「按该维度找片」的结果页
                onMetaClick = { keyword, kind, filterId ->
                  pushChild(
                    EmbyTagItemsScreen(keyword = keyword, kind = kind, filterId = filterId),
                  )
                },
                // 推荐（按演员 / 导演）与同类型推荐的数据：由本层加载好后传进去，
                // 详情区内只负责画 —— 避免滚动时 item 被回收 / 重组而反复发请求。
                recommendItems = recommendItems,
                genreRecommendLabel = genreRecommendLabel,
                genreRecommendItems = genreRecommendItems,
                // 点推荐卡片：同一位演员 / 导演的另一部片子，直接再开一层详情页
                onRecommendClick = { id, name ->
                  pushChild(EmbyDetailScreen(itemId = id, title = name))
                },
                episodes = seriesEpisodes,
                relatedEpisodes = relatedEpisodes,
                // 点某一集 = 播这一集（并带后续连播）。
                // ⚠️ 这里**不要**再走详情页的 playLaunching 闸门：那个闸门是给
                //    「点播放按钮」防连点用的，而分集卡片各自独立，连点不同集是正常操作。
                onPlayEpisode = { episode, resume ->
                  val currentServer = server
                  // 服务器尚未解析出来时点集：不静默吞掉点击，也不带着 null 往下走
                  //（launchPlaylist 的 server 不可空）。此时页面下方就是加载态，
                  // 用户再点一次即可 —— 比「点下去什么都没发生」好排查。
                  if (currentServer != null) {
                    scope.launch {
                      // 点某一集时把**整季**带进播放队列（从这一集起），而不是只播这一条：
                      // - 队列里有兄弟集 ⇒ 片尾能自动连播、切集时标题对得上；
                      // - 队列里每条都带着自己的集号 ⇒ 弹幕按真实集号匹配，
                      //   否则单独播第 5 集时队列只有 1 条，弹幕会取成第 1 集。
                      val startAt = seriesEpisodes.indexOfFirst { it.Id == episode.Id }
                        .takeIf { it >= 0 } ?: 0
                      // 单集详情页没有 seriesEpisodes，队列只有这一条 ⇒ 追「同季」的后续：
                      // 否则从第 3 集点进去就再也连播不到第 4 集（片尾只能退出）。
                      val base = seriesEpisodes.ifEmpty {
                        relatedEpisodes.sameSeason.ifEmpty { listOf(episode) }
                      }
                      val from = base.indexOfFirst { it.Id == episode.Id }.takeIf { it >= 0 } ?: 0
                      val queue = base.drop(from).ifEmpty { listOf(episode) }
                      viewModel.launchPlaylist(
                        server = currentServer,
                        items = queue,
                        resumeSeconds = resume,
                      )
                    }
                  }
                },
                // 长按某一集 = 进这一集的详情页（看简介 / 换版本 / 换音轨）
                onOpenEpisode = { epId, epName ->
                  pushChild(EmbyDetailScreen(itemId = epId, title = epName))
                },
                // 分集卡上的「下载这一集」：状态直接从已有的 downloadTasks 里查，
                // 不额外发请求 —— 下载管理页与本页看的是同一份 Flow。
                // 整季批量下载：只挑**还没下过、没在队列里**的集，重复入队会刷一堆
                // 「已在下载列表中」的提示，而这里用户是明确要「补齐这一季」。
                // 全部下过 → 直接给一句提示，不要静默什么都不发生。
              )
            }
          }
        }
      }
    }

    // ── 「正在打开播放器」整页加载层 ──────────────────────────────
    // 详情页点播放 → 播放器真正起来之间有段网络 Prep（解析直链 / ISO 探测 /
    // 建连播列表，慢的时候要好几秒）。原来只有播放按钮里一枚 20dp 小转圈，
    // 用户视线不在按钮上就等于「点了没反应」。
    // ⇒ 整页居中渲染大加载动画（走统一入口，「外观 → 加载动画」在这里也生效）；
    //   按钮内的小转圈随之撤掉（见 PlayButton），免得两处同时转、互相抢注意力。
    // ⚠️ 挂在**内容 Box 之后**（同一个 Content() 体层级）：后声明的子节点画在
    //   上层，才盖得住页面内容。
    // ⚠️ 这里**拿不到 BoxScope**（不在内容 Box 的 lambda 里），用不了
    //   `Modifier.align` ⇒ 自己套一个 `Box(contentAlignment = Center)`。
    // ⚠️ 尺寸必须写进 modifier —— AppLoadingIndicator 的 `size` 参数只在
    //   **完全没传 modifier** 时生效（`if (modifier == Modifier)`），传了
    //   modifier 就得自带 size，否则 Lottie 分支会按 composition 固有像素渲染
    //   （yamby 那批是 500×500px ≈ 170dp，直接变成巨物）。
    // ⚠️ `playLaunching` 的复位在 onPlay 的 finally 里：解析失败才会留下来
    //   （供用户重点一次），成功拉起时本页随即被播放页盖住。
    if (playLaunching) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        // ⚠️ 用 size 参数（基准 96dp，还会乘用户设的缩放系数）而不是
        //   Modifier.size —— 后者不走「加载动画大小」缩放。
        app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
          size = 96.dp,
        )
      }
    }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBody(
  item: EmbyItem,
  server: EmbyServer?,
  /**
   * 列表状态 + 折叠状态。由 [EmbyDetailScreen] 持有并传进来 ——
   * 因为「下拉刷新」挂在 Body 外面，需要在那边读到折叠进度（见 `PullRefreshBox` 调用处）。
   */
  listState: androidx.compose.foundation.lazy.LazyListState,
  collapse: HeaderCollapse,
  /** 详情页推荐区开关。由 [EmbyDetailScreen] 读出后传进来。 */
  showRecommendations: Boolean,
  backdropUrl: String?,
  posterUrl: String?,
  /** resumeSeconds = 续播秒数（0 = 从头）。 */
  onPlay: (resumeSeconds: Long) -> Unit,
  /**
   * 电视剧专用：从**第一集开头**播放（不受「上次看到哪」影响）。
   *
   * 与 [onPlay] 的区别是「选哪一集」：`onPlay` 会挑有进度的那一集续播，
   * 而这里固定落在这些集里的第一集、并强制从头。两处联动：
   * - 播放按钮旁的小按钮（`PlaySection`）
   * - 分集区展开后的「从头播放本季」
   */
  onPlayFromStart: () -> Unit,
  onBack: () -> Unit,
  /** 点演职员头像：进「演员作品」页（personId / 名字 / 头像 tag） */
  onPersonClick: (personId: String, personName: String, imageTag: String?) -> Unit,
  /**
   * 点类型 / 标签 / 工作室 chip：进「按该维度找片」的结果页。
   * @param keyword 显示名（就是 chip 上的字）
   * @param kind "genre" / "tag" / "studio"
   * @param filterId 过滤用的 Id（工作室需要；类型 / 标签传空串，按名字过滤）
   */
  onMetaClick: (keyword: String, kind: String, filterId: String) -> Unit,
  /**
   * 两块推荐区的数据。**由 [EmbyDetailScreen] 加载好后传进来**，这里只负责画 ——
   * 请求留在区块内部的话，LazyColumn 回收 / 重组 item 时会被反复触发（见那边的注释）。
   */
  recommendItems: List<EmbyItem>,
  genreRecommendLabel: String?,
  genreRecommendItems: List<EmbyItem>,
  /** 是否正在拉起播放器：播放键转圈 + 禁点（转给 [PlaySection]）。 */
  playLaunching: Boolean = false,
  /** 多版本媒体：当前选中的版本 Id（null = 默认版本）。转给 [PlaySection] 画下拉框。 */
  selectedMediaSourceId: String? = null,
  /** 多版本媒体：用户在下拉框里选了某个版本。 */
  onSelectMediaSource: (String) -> Unit = {},
  /**
   * 多版本媒体：当前版本的**本地**续播秒数（null = 按服务端进度）。
   *
   * 服务端进度按条目存、各版本共享，所以多版本条目要按版本各算各的（见 [loadVersionResumeSeconds]）。
   * 转给 [PlaySection] 决定「继续播放 · hh:mm:ss / 已观看 x%」显示的是哪个进度。
   */
  versionResumeSeconds: Long? = null,
  /** 点推荐卡片：打开对应媒体的详情页（itemId / 标题） */
  onRecommendClick: (String, String) -> Unit,
  /** 全剧分集（非 Series 恒为空）。数据由 [EmbyDetailScreen] 加载，这里只负责画。 */
  episodes: List<EmbyItem> = emptyList(),
  /** 点某一集：直接播（resumeSeconds = 那一集自己的续播位置，0 = 从头） */
  onPlayEpisode: (EmbyItem, Long) -> Unit = { _, _ -> },
  /** 长按某一集：进这一集的详情页 */
  onOpenEpisode: (String, String) -> Unit = { _, _ -> },
  /** 取某一集的离线下载状态（null = 没下过） */
  /** 点分集卡上的下载按钮 */
  /** 点「下载本季」：参数是当前季的所有集（由组件按季筛好） */
  /**
   * 「相关分集」区：同季其它集 / 特别篇 / 同剧其它集。
   *
   * 只有**从单集进详情页**时非空 —— 此时用户看到的是「某一集」，而不是整部剧，
   * 分集区（[episodes]）是空的，于是没有任何地方能切到兄弟集。
   * 数据由 [EmbyDetailScreen] 加载好后传进来（同 [recommendItems] 的理由：不能在区块内部发请求）。
   */
  relatedEpisodes: RelatedEpisodes = RelatedEpisodes(),
) {
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true
  // 多版本媒体：真正生效的版本 Id（单版本媒体为 null）。下面「媒体信息」也要按它取源，
  // 否则容器 / 文件大小 / 码率 / 音视频轨道会一直显示第一个版本，与选中的版本对不上。
  // ⚠️ 必须与播放侧用**同一条规则**（autoSourcePreference 同一个值），
  // 否则会出现「顶栏写着一个版本、媒体信息里写着另一个」。
  val browserPrefsForSource = koinInject<BrowserPreferences>()
  val effectiveSourceId =
    effectiveEmbyMediaSourceId(
      item,
      selectedMediaSourceId,
      MediaSourcePreference.fromKey(browserPrefsForSource.embyMediaSourcePreference.get()),
    )

  // ── 折叠头部：高度**完全由列表滚动位置推导**，没有任何自定义手势 / 嵌套滚动 ──
  // 此前这里同时挂着两套纵向滚动的消费者（自定义 NestedScrollConnection + 下拉刷新），
  // 两者抢同一个手势，就是「卡手 / 时灵时不灵」的根源。现在整块删掉，只留一条规则：
  //     封面有多高 = 列表滚了多少（见 [rememberHeaderCollapse]）
  // 手势只有 LazyColumn 一个消费者；下拉刷新只在列表贴顶时接住溢出量 —— 结构上不可能再打架。
  // 因为播放区一直贴着封面底边一起上移，封面收完的那一刻播放区正好停在工具栏下方，
  //    所以天然就是「滑到播放按钮就折叠」，不需要任何触发距离常量。
  val density = LocalDensity.current
  val headerHeightDp = with(density) { collapse.headerHeightPx.toDp() }
  val headerMaxHeightPx = with(density) { BACKDROP_HEADER_MAX_HEIGHT_DP.toPx() }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 96.dp),
    ) {
      // 第 0 项是「封面占位稿」：高度 = 封面最大高度，本身不可见（被上层的封面盖住）。
      // ⚠️ 用**真实 item** 而不是 `contentPadding.top`，是为了避开一个语义坑：
      // `firstVisibleItemIndex` / `firstVisibleItemScrollOffset` 与
      // `visibleItemsInfo` 的 offset 对 contentPadding 的处理并不一致（前者「不含内容内边距」），
      // 拿它推折叠量会在某些情况下差一个 padding 的高度。改用真实 item 后，
      // 它的 offset 就是「相对视口的真实 y」，从 0 一路减到 −最大高度，毫无歧义。
      item { Spacer(modifier = Modifier.height(BACKDROP_HEADER_MAX_HEIGHT_DP)) }

      // ── 播放 ──
      item {
        PlaySection(
          item = item,
          onPlay = onPlay,
          playLaunching = playLaunching,
          selectedMediaSourceId = selectedMediaSourceId,
          onSelectMediaSource = onSelectMediaSource,
          versionResumeSeconds = versionResumeSeconds,
          onPlayFromStart = onPlayFromStart,
          episodes = episodes,
        )
      }

      // ── 分集（电视剧 / 综艺）──
      // 紧跟播放区：用户点进一部剧，最先要的就是「从第几集开始」。
      // 放在简介 / 演职员之前，是因为那些是「了解这部剧」，而分集是「开始看」。
      // 点一集 = 直接播（带后续连播）；长按 = 进那一集的详情（换版本 / 看简介）。
      if (episodes.isNotEmpty()) {
        item {
          EpisodeListSection(
            episodes = episodes,
            // 高亮「上次看到的那一集」，让用户一眼找到该从哪儿继续。
            currentEpisodeId = episodes
              .firstOrNull { (it.UserData?.PlaybackPositionTicks ?: 0L) > 0L }
              ?.Id,
            onClick = { ep, resume -> onPlayEpisode(ep, resume) },
            onLongClick = { ep -> ep.Id?.let { onOpenEpisode(it, ep.Name ?: "") } },
          )
        }
      }

      // ── 相关分集（单集详情页专用）──
      // 只有当前条目是 Episode 时非空：同季 / 特别篇 / 演员相关三分区。
      // 放在播放区之后、简介之前，和上面的「分集」区同一个位置逻辑（先能开始看）。
      // ⚠️ 点卡片的语义与分集区一致：点 = 直接播这一集，长按 = 进那一集详情。
      if (!relatedEpisodes.isEmpty) {
        item {
          RelatedEpisodesSection(
            related = relatedEpisodes,
            currentEpisodeId = item.Id,
            onClick = { ep, resume -> onPlayEpisode(ep, resume) },
            onLongClick = { ep -> ep.Id?.let { onOpenEpisode(it, ep.Name ?: "") } },
          )
        }
      }

      // ── 类型 / 标签 / 工作室 / 外部链接 ──
      // 四组都是「点一下进按该维度找片的结果页」的 chip，共用 [MetaChipRow] 排版：
      // 左边一个分组名，右边横向滚动的一排 chip（分组名固定宽度 ⇒ 各组的 chip 起始边对齐）。
      // 类型 / 标签按**名字**过滤（Emby 的 Genres / Tags 参数，可用）；
      // 工作室优先按 **Id** 过滤（`StudioIds`）—— 条目自带的 Studios 就是 NameGuidPair，
      // 拿得到 Id 就按 Id 走；万一只回了名字，结果页会自动退回按名字的 `Studios` 参数。
      val genreList = item.Genres.filter { it.isNotBlank() }
      // ⚠️ 标签**优先 TagItems、为空才退回 Tags**：`Tags` 是纯名字数组、`TagItems` 每项带 Id，
      //    但有些服务端（用户环境就是）只写 `TagItems` 而把 `Tags` 留空甚至整个字段不返回
      //    ⇒ 只看 `Tags` 会一条标签都显示不出来。两份是同一份数据的两种形态，取到即可。
      val tagList = item.TagItems.mapNotNull { it.Name?.takeIf { n -> n.isNotBlank() } }
        .ifEmpty { item.Tags.filter { it.isNotBlank() } }
      val studioList = item.Studios.orEmpty().filter { !it.Name.isNullOrBlank() }
      // 外部链接：**首选服务端给的 `ExternalUrls`**，它取不到才退回用 ProviderIds 现拼。
      // 为什么首选它：`ExternalUrls[].Url` 是**服务端 / 元数据插件已经
      // 拼好的完整地址**（用户环境里 MetaTube 给的是
      // `http://192.168.2.124:8080/?redirect=JavBus:ACZD-218`）—— 直接用，打开的就是
      // Emby Web 与 Yamby 打开的同一条链接。
      // 退回分支（内置元数据源不产生 ExternalUrls）按 `<服务器根>/?redirect=<Provider>:<Id>`
      // 现拼，细节见 [externalLinkUrl]。
      // ⚠️ 曾经**只**按 `"$baseUrl?redirect=$value"` 现拼，真机结果是「跳到了 Emby 首页」：
      //    少了根路径的 `/`，且 `redirect` 的参数是 `Provider:Id` 而不是裸 Id。
      val externalLinks = item.ExternalUrls
        .mapNotNull { e ->
          val name = e.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          val url = e.Url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          name to url
        }
        .ifEmpty {
          item.ProviderIds.filterValues { it.isNotBlank() }
            .mapNotNull { (name, value) -> externalLinkUrl(server?.baseUrl, name, value) }
        }
        // 同名只留第一条：chip 是按名字渲染的，重名会让两个 chip 点出不同地址
        .distinctBy { it.first }
        .toMap()
      if (
        genreList.isNotEmpty() || tagList.isNotEmpty() ||
        studioList.isNotEmpty() || externalLinks.isNotEmpty()
      ) {
        item {
          // Composable 才能取到 UriHandler；LazyListScope 的 content lambda 不是 composable，
          // 所以必须写在 item{} 里面。
          val uriHandler = LocalUriHandler.current
          Column(modifier = Modifier.padding(vertical = 4.dp)) {
            MetaChipRow(
              label = "类型",
              chips = genreList,
              onClick = { onMetaClick(it, "genre", "") },
            )
            MetaChipRow(
              label = "标签",
              chips = tagList,
              onClick = { onMetaClick(it, "tag", "") },
            )
            MetaChipRow(
              label = "工作室",
              chips = studioList.mapNotNull { it.Name },
              onClick = { name ->
                // chips 是按名字渲染的，回查 Id 交给 onMetaClick
                val id = studioList.firstOrNull { it.Name == name }?.Id.orEmpty()
                onMetaClick(name, "studio", id)
              },
            )
            // chip 上显示的是链接名（服务端 ExternalUrls 的 `Name`，如 MetaTube；退回分支里
            // 就是 ProviderIds 的键），点一下交给系统浏览器打开那条地址
            MetaChipRow(
              label = "外部链接",
              chips = externalLinks.keys.toList(),
              onClick = { label ->
                externalLinks[label]?.let { uriHandler.openUri(it) }
              },
            )
          }
        }
      }

      // ── 宣传语 / 导演 ──
      // 宣传语（Taglines）在用户环境里是一条短句（如「配信開始日 2025-04-11」），单独一行；
      // 紧跟其下一行显示导演 —— **用户指定的格式就是「导演：名字」**，名字取 People 里
      // `Type=Director` 的条目（与 Emby Web 的「导演」同源）。
      // 两者都不是筛选维度，所以是**纯文本**而不是 chip —— 做了 chip 却没反应就是「死项」。
      val taglines = item.Taglines.filter { it.isNotBlank() }
      val directors = item.People.orEmpty()
        .filter { it.Type.equals("Director", ignoreCase = true) }
        .mapNotNull { it.Name?.takeIf { name -> name.isNotBlank() } }
      if (taglines.isNotEmpty() || directors.isNotEmpty()) {
        item {
          Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            taglines.forEach { line ->
              Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.longPressToCopy(line, "宣传语"),
              )
            }
            if (directors.isNotEmpty()) {
              val names = directors.joinToString("、")
              Text(
                text = "导演：$names",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.longPressToCopy(names, "导演"),
              )
            }
          }
        }
      }

      // ── 简介 ──
      item.Overview?.takeIf { it.isNotBlank() }?.let { overview ->
        item { OverviewSection(overview) }
      }

      // ── 演职员 ──
      item.People?.takeIf { it.isNotEmpty() }?.let { people ->
        item {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("演职员", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              people.take(20).forEach { person ->
                Column(
                  modifier = Modifier.width(72.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Card(
                    modifier = Modifier
                      .size(56.dp)
                      .clip(RoundedCornerShape(28.dp))
                      .clickable {
                        val pid = person.Id
                        if (pid != null) {
                          onPersonClick(pid, person.Name ?: "", person.PrimaryImageTag)
                        }
                      },
                    shape = RoundedCornerShape(28.dp),
                  ) {
                    val avatarUrl = remember(person.Id, person.PrimaryImageTag, server) {
                      server?.let { s ->
                        person.Id?.let { id ->
                          EmbyClient.imageUrl(
                            server = s,
                            itemId = id,
                            imageType = "Primary",
                            tag = person.PrimaryImageTag,
                            maxWidth = 240,
                          )
                        }
                      }
                    }
                    EmbyImage(
                      server = server,
                      url = avatarUrl,
                      contentDescription = person.Name,
                      modifier = Modifier.fillMaxSize(),
                      contentScale = ContentScale.Crop,
                      placeholder = Icons.Default.Person,
                      maxWidth = 240,
                    )
                  }
                  Text(
                    text = person.Name ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    // 这一栏是「演职员」，混着演员 / 导演 / 编剧，复制提示用中性的「姓名」
                    modifier = Modifier.longPressToCopy(person.Name, "姓名"),
                  )
                  person.Role?.let {
                    Text(
                      text = it,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // ── 推荐：同一位演员 / 导演参与的其他影片 ──
      // 放在演职员之后：看完了主演阵容，顺势往下推荐 TA 的片子，上下文是连着的。
      // 设置里关掉「详情页推荐」后整块不渲染（连带省掉上面的网络请求）。
      // ⚠️ 这里只是**把上层加载好的数据画出来**：数据与请求都在 [EmbyDetailScreen]
      // 那一层（不随滚动被回收），所以滚出视口再滚回来不会重新请求。
      if (showRecommendations) {
        item {
          RecommendationSection(
            server = server,
            recommendations = recommendItems,
            onOpenItem = onRecommendClick,
          )
        }

        // ── 同类型推荐：按本片某个类型（Genres）横向推荐 ──
        // 与上面的「演员 / 导演」推荐互补：那边按人找，这边按题材找。
        item {
          GenreRecommendationSection(
            server = server,
            genre = genreRecommendLabel,
            picks = genreRecommendItems,
            onOpenItem = onRecommendClick,
          )
        }
      }

      // ── 媒体信息（含完整视频 / 音频编码信息）──
      item {
        MediaInfoSection(item = item, selectedMediaSourceId = effectiveSourceId)
      }
    }

    // ── 封面区（浮在下层内容之上，随滚动折叠）──
    // 它不放在 LazyColumn 里当 item，是因为要能做视差位移、且高度随滚动变化；
    // 对应 XML 里 AppBarLayout 套 ImageView(layout_collapseMode="parallax") + Toolbar(pin)。
    // 高度来自 [collapse]，而 [collapse] 只是列表滚动位置的函数 —— 这里只负责画。
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(headerHeightDp)
        .align(Alignment.TopCenter)
        .graphicsLayer {
          // 收到最后阶段整体淡出，避免和 toolBar 的文字挤在一起。
          // ⚠️ 用**线性**的 collapse.progress 而不是缓动后的值 —— 淡出是「退场」，
          // 缓动会让它拖到最后一刻才消失，那时标题已经压上来了。
          alpha = (1f - collapse.progress * 1.6f).coerceIn(0f, 1f)
        },
    ) {
      BackdropHeader(
        item = item,
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        // 视差位移按**缓动后**的刻度走，跟容器的收缩节奏一致
        parallaxPx = collapse.collapsedPx * easeInQuad(collapse.progress),
        // 底图按头部宽度反向缩放：容器变矮多少，图就放大多少，
        // 保证它在折叠过程中**始终维持 CenterCrop**，不会被压扁变形。
        // 高度只剩 56dp 时 widthScale ≈ 5.7，但那时整块 alpha 已经归零，不会看到过放大的画面。
        widthScale = if (collapse.headerHeightPx > 0f) {
          headerMaxHeightPx / collapse.headerHeightPx
        } else {
          1f
        },
      )
    }

    // ── 顶部浮层操作栏 ──
    // 展开时透明 + 白图标（压在封面上），折叠后过渡到实体的工具栏底色 + 常规前景色，
    // 并把标题淡入进来 —— 对应 XML 里 `layout_collapseMode="pin"` 那条 Toolbar。
    val barProgress = easeInQuad(collapse.progress)
    val barBg = lerp(
      Color.Transparent,
      MaterialTheme.colorScheme.surface,
      barProgress,
    )
    // 图标颜色跟着背景走：透明时是白色，实体背景时切回常规前景色
    val barContentColor = lerp(
      Color.White,
      MaterialTheme.colorScheme.onSurface,
      barProgress,
    )
    TopAppBar(
      title = {
        Text(
          text = item.displayTitle(),
          // 顶栏标题也支持长按复制：片名常有生僻字 / 外文，用户要拿去别处搜
          modifier = Modifier.longPressToCopy(item.Name, "片名"),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          // 折叠加深到一定程度才把标题放出来，避免展开时和封面区的大标题重复
          color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = ((barProgress - 0.5f) / 0.5f).coerceIn(0f, 1f),
          ),
        )
      },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = barContentColor,
          )
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(
        // 背景从全透明渐变到实体底色：还没折叠时能看见封面，折完了就是一条正常的工具栏
        containerColor = barBg,
        scrolledContainerColor = barBg,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
      ),
    )
  }
}

/**
 * 详情页头部：**一整张剧照（Backdrop）铺满**，标题压在图上。
 *
 * 上一版是「左侧 2:3 小封面 + 128px 剧照放大当虚化底」，用户反馈太糊、不喜欢 ——
 * 现在回到整版背景图：
 *
 * - **底图**：向服务器要屏幕级别的剧照（1080 宽，尺寸由调用方 [backdropUrl] 给定），
 *   Crop 铺满整个头部，**不做任何模糊、也不压半透明**，保持清晰。
 * - **文字**：贴着底部排（标题 / 原名 / 信息角标），靠下面那层渐变把亮度压下来保证可读，
 *   上方留白是纯图 —— 也就是常见的详情页大图观感。
 *
 * [parallaxPx] 是已折叠的像素数，底图跟着做半速位移 —— 对应 XML 里的
 * `app:layout_collapseMode="parallax"`。
 *
 * **注意：本函数的高度由调用方决定**（折叠时容器会变矮），所以这里一律 [Modifier.fillMaxSize]。
 */
/**
 * 把 [accent] 按 [weight] 混进 [base]。
 *
 * 用于「海报主色渐变」：直接拿封面主色铺背景容易和下面的主题底色打架，
 * 掺进主题色里既带上封面的调子，又保证深浅风格一致。
 */
private fun mixColor(base: Color, accent: Color, weight: Float): Color {
  val w = weight.coerceIn(0f, 1f)
  return Color(
    red = base.red * (1f - w) + accent.red * w,
    green = base.green * (1f - w) + accent.green * w,
    blue = base.blue * (1f - w) + accent.blue * w,
    alpha = base.alpha,
  )
}

@Composable
private fun BackdropHeader(
  item: EmbyItem,
  backdropUrl: String?,
  posterUrl: String?,
  parallaxPx: Float,
  /**
   * 底图的横向放大倍率。
   *
   * 容器高度随折叠变小，若底图仍铺满容器就会被**纵向压扁** —— 人物越来越矮胖，
   * 慢滚时一帧帧变形，这是「压迫感」的主要来源。
   * 按 `展开高度 / 当前高度` 反向放大横向，底图就始终维持原比例（CenterCrop），
   * 表现成「缓慢推近」而不是「被压扁」。
   */
  widthScale: Float = 1f,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      // 折叠过程中容器变矮，不裁剪的话标题会溢出到下方内容区上
      .clipToBounds(),
  ) {
    // ① 剧照整版铺底（只有这一层做视差位移）
    if (backdropUrl != null || posterUrl != null) {
      EmbyImage(
        url = backdropUrl,
        // 很多单集 / 音乐只有封面没有剧照，退回到封面顶上，免得整个头部是一片灰底
        fallbackUrl = posterUrl,
        contentDescription = null,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            translationY = parallaxPx * 0.5f
            // 横向放大「N 倍再缩回去」：pivot 取中上（0.5, 0）——
            // 竖屏裁切时标题本来就压在底部、人物多在中上部，锚在这里裁掉的最少。
            scaleX = widthScale
            transformOrigin = TransformOrigin(0.5f, 0f)
          },
        contentScale = ContentScale.Crop,
        // 0 = 不降采样：URL 已经跟服务器要了 1080，客户端再降一次只会白白变糊
        maxWidth = 0,
      )
    }

    // ② 暗化渐变：顶上有白图标、底部压着白字，无论剧照多亮都要看得清。
    //    最下面两层现在掺入**海报主色**（需求里的「海报主色渐变」）：
    //    整页往下过渡到封面自己的色调，比直接切成中性主题底色更有整体感。
    //    取不到主色（图挂了 / 图片太灰）时回退成原来的主题底色，观感不变差；
    //    混合时以主题底色为主 —— 否则头部一片暖色、下面列表还是冷色，衔接处会断成两截。
    val surface = MaterialTheme.colorScheme.surface
    val dominant = rememberDominantColor(backdropUrl ?: posterUrl)
    val bottomTint = dominant?.let { mixColor(surface, it, 0.45f) } ?: surface
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Black.copy(alpha = 0.45f),
              Color.Transparent,
              Color.Transparent,
              bottomTint.copy(alpha = 0.75f),
              bottomTint,
            ),
          ),
        ),
    )

    // ③ 标题区：顶部给 Toolbar 让位，文字贴着头部底边排
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(start = 16.dp, end = 16.dp, top = TOOLBAR_HEIGHT_DP, bottom = 16.dp),
      verticalArrangement = Arrangement.Bottom,
    ) {
      Text(
        text = item.displayTitle(),
        style = MaterialTheme.typography.headlineSmall.copy(
          shadow = Shadow(
            color = Color.Black.copy(alpha = 0.65f),
            blurRadius = 10f,
          ),
        ),
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      // 原名只在「实质不同」时才显示（去首尾空白、忽略大小写），并加「原名：」前缀，
      // 否则标题下面再跟一行差不多的文字，看起来就像标题显示了两遍
      item.OriginalTitle
        ?.takeIf {
          it.isNotBlank() && !it.trim().equals(item.Name?.trim(), ignoreCase = true)
        }
        ?.let {
          Text(
            text = "原名：$it",
            style = MaterialTheme.typography.bodyMedium.copy(
              shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
            ),
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.longPressToCopy(it, "原名"),
          )
        }
      // 媒体 ID：排障与「App 里看到的条目到底是不是服务器上那一条」对照用，长按复制。
      item.Id?.takeIf { it.isNotBlank() }?.let { id ->
        Text(
          text = "媒体 ID：$id",
          style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
          ),
          color = Color.White.copy(alpha = 0.6f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.longPressToCopy(id, "媒体 ID"),
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      // 年份 / 时长 / 评分 / 分级：包成圆角角标，压在剧照上也看得清。
      // 用 FlowRow 而不是 Row —— 片名长、角标多的时候会自动换行，不会把角标挤出屏幕边缘。
      val chips = metaChips(item)
      if (chips.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          chips.forEach { chip ->
            Surface(
              shape = RoundedCornerShape(50),
              color = Color.Black.copy(alpha = 0.42f),
              contentColor = Color.White,
            ) {
              Text(
                text = chip,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }
  }
}

/** 剧照上的角标内容：年份 / 时长 / 评分 / 官方分级，每项单独渲染成一个圆角角标 */
/** 把「、」/ 逗号 / 分号分隔的输入切成列表：去空白、去空项、保序去重。空输入返回空列表。 */
private fun parseMetaList(raw: String): List<String> =
  raw
    .split("、", ",", "，", ";", "；")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

/** 剧照上的一行摘要：年份 · 时长 · 评分 · 官方分级，渲染成圆角角标 */
private fun metaChips(item: EmbyItem): List<String> {
  val parts = mutableListOf<String>()
  item.ProductionYear?.let { parts.add(it.toString()) }
  formatDuration(item.RunTimeTicks).takeIf { it.isNotBlank() }?.let { parts.add(it) }
  item.CommunityRating?.let { parts.add("★ %.1f".format(it)) }
  item.OfficialRating?.let { parts.add(it) }
  return parts
}

/**
 * 播放区：主按钮「播放 / 继续播放 · mm:ss」，电视剧有已看记录时增加「从头播放」。
 *
 * 进度说明**始终显示**：
 * `已观看 24% · 剩余 1小时12分 · 总时长 1小时35分`。
 */
@Composable
private fun PlaySection(
  item: EmbyItem,
  /** resumeSeconds = 续播秒数（0 = 从头）。 */
  onPlay: (resumeSeconds: Long) -> Unit,
  /** 下载填充进度 0..1；null = 没有下载任务（不画填充） */
  /** 是否正在拉起播放器：播放键转圈 + 禁点。 */
  playLaunching: Boolean = false,
  /** 多版本媒体：当前选中的版本 Id（null = 默认版本）。 */
  selectedMediaSourceId: String? = null,
  /** 多版本媒体：用户切换了版本。 */
  onSelectMediaSource: (String) -> Unit = {},
  /**
   * 多版本媒体：当前版本的本地续播秒数（null = 用服务端进度）。
   *
   * Emby 的 `UserData.PlaybackPositionTicks` 按**条目**存、各版本共享，
   * 所以多版本条目必须让「当前版本自己的进度」盖过它 —— 否则切成 1080P 会从
   * 4K 看到的位置继续。null（单版本条目）时行为与改动前完全一致。
   */
  versionResumeSeconds: Long? = null,
  /**
   * 电视剧专用：从头播放（第一集 / 第一集开头）。
   *
   * 只有 `item.Type == "Series"` 且分集里确实有「看过的痕迹」时才显示小按钮 ——
   * 一部新番里没有进度时，「继续播放」和「从头播放」是同一个动作，
   * 摆两个按钮只会让人以为它们不一样。
   */
  onPlayFromStart: () -> Unit = {},
  /** 分集列表：只用来判「这部剧是不是追过」（决定要不要露「从头播放」小按钮）。 */
  episodes: List<EmbyItem> = emptyList(),
) {
  val serverPositionTicks = item.UserData?.PlaybackPositionTicks ?: 0L
  val positionTicks =
    versionResumeSeconds?.let { EmbyTicks.secondsToTicks(it) } ?: serverPositionTicks
  val runTimeTicks = item.RunTimeTicks ?: 0L
  // 多版本媒体：MediaSources 就是各版本；只有一个（或 Id 缺失）时不算多版本。
  val mediaSources = item.MediaSources.orEmpty().filter { !it.Id.isNullOrBlank() }
  val resumeSeconds = EmbyTicks.ticksToSeconds(positionTicks)
  val remainingTicks = (runTimeTicks - positionTicks).coerceAtLeast(0L)
  val hasResume = resumeSeconds > 10 && runTimeTicks > 0 &&
    (positionTicks.toFloat() / runTimeTicks.toFloat()) < 0.95f
  // 电视剧的「从头播放」小按钮：
  // ⚠️ Series 自身 RunTimeTicks = 0 ⇒ hasResume 恒为 false，不能拿它判。
  //   改看**分集**里有没有看过 —— 播放按钮此时文案是「开始播放」（会挑有进度的那集），
  //   一旦真的追过剧，旁边就该给一个「回到第一集」的出口。
  val seriesFromStartVisible = item.Type == "Series" &&
    (item.UserData?.Played == true || episodes.any { it.UserData?.Played == true })
  val progressPercent = if (runTimeTicks > 0) {
    (positionTicks.toFloat() / runTimeTicks.toFloat() * 100).toInt().coerceIn(0, 100)
  } else {
    0
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      // 播放（电视剧另有从头播放），8dp 间距，图标键 48dp。
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 播放按钮：有进度即续播，常规播放固定进入 mpv。
      // 主播放按钮自己绘制，以便叠加观看进度并在拉起期间禁用点击。
      // 占满除「从头播放」动作键以外的全部宽度。
      PlayButton(
        // Series 自身没有 RunTimeTicks，`hasResume` 恒为 false ⇒ 这里得单独给文案。
        // 「开始播放」比光秃秃的「播放」更能说明「点了会从该看的那一集开始」，
        // 具体挑哪一集由 EmbyViewModel.play 负责（优先未看完的那一集）。
        label = when {
          item.Type == "Series" -> "开始播放"
          hasResume -> "继续播放 · ${formatClock(resumeSeconds)}"
          else -> "播放"
        },
        onClick = { onPlay(if (hasResume) resumeSeconds else 0) },
        // 拉起播放器期间：转圈 + 禁点。
        // 单看"转圈"还不够 —— 不挡掉点击的话，用户等得不耐烦会再点，起两个播放页。
        loading = playLaunching,
        // 有续播进度时，按钮里垫一块「已看多少」的填充带（画法与右边下载按钮一致）。
        // 出现条件与按钮文案严格对齐（都用 hasResume）—— 看到填充 ⟺ 看到「继续播放」，
        // 这样不会出现「按钮写着 播放、里面却填了一半」这种自相矛盾的画面。
        progressFraction = if (hasResume && runTimeTicks > 0) {
          (positionTicks.toFloat() / runTimeTicks.toFloat()).coerceIn(0f, 1f)
        } else {
          null
        },
        modifier = Modifier
          .weight(1f)
          .height(56.dp),
      )

      // 「从头播放」小按钮（电视剧专用，追过才露）。
      // ⚠️ 为什么是**小按钮**而不是长按大按钮 / 二级菜单：
      //   「继续看上次那一集」和「回第一集重刷」是并列的两个意图，没有主次。
      //   把「从头」藏进长按或菜单里，用户想重刷时会先点到大按钮被带到第 8 集，
      //   再拖回来 —— 这一趟白走。给它一枚 48dp 的图标键，一眼可见、一指点到。
      // ⚠️ 图标用 Replay：语义就是「倒回去重放」，和播放键的三角一眼分得开。
      if (seriesFromStartVisible) {
        Box(
          modifier =
            Modifier
              .size(48.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(MaterialTheme.colorScheme.primaryContainer)
              .then(
                // 拉起播放器期间同样禁点：否则「继续」和「从头」能各起一个播放页
                if (playLaunching) Modifier else Modifier.clickable(onClick = onPlayFromStart),
              ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Replay,
            contentDescription = "从头播放",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
    }

    // ── 多版本媒体：版本下拉框 ──
    // Emby 把同一个片子的多个版本（4K / 1080P / 不同压制组…）放在 MediaSources 里，
    // 这里给一个与网页端一致的版本切换入口：选中的版本会用于播放和媒体信息。
    // 只有真的存在多个版本时才渲染 —— 单版本媒体（绝大多数）界面上完全看不出变化。
    if (mediaSources.size > 1) {
      MediaSourceSelector(
        sources = mediaSources,
        selectedId = selectedMediaSourceId,
        onSelect = onSelectMediaSource,
      )
    }

    // 进度：已观看百分比 + 剩余时长 + 总时长，三个数一起给，避免只看到一个百分比
    if (runTimeTicks > 0) {
      Text(
        text = buildString {
          append("已观看 $progressPercent%")
          append(" · 剩余 ${formatEmbyDurationFull(remainingTicks)}")
          append(" · 总时长 ${formatEmbyDurationFull(runTimeTicks)}")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
    }

    // 有续播进度时给一个「从头播放」（蓝色主键此时是续播，两者各司其职）。
    // 「继续上次」不单独出现 —— 蓝色主键就是续播，再放一个是冗余的。
    if (hasResume) {
      FilledTonalButton(onClick = { onPlay(0) }) {
        Text("从头播放")
      }
    }
  }
}
/**
 * 多版本条目「当前选中版本」的本地续播秒数；返回 null 表示不要覆盖服务端进度。
 *
 * 规则（三个分支，逐一对应下面三种真实情形）：
 *  1. 这个版本本地有记录 → 用它（**各版本互不干扰**，这就是本功能的全部意义）；
 *  2. 没记录，但**该条目下任一版本**有记录 → 这条版本本地确实没播过 ⇒ 0（从头）；
 *  3. 该条目一条本地记录都没有 → 退回服务端进度（可能是在别的 Emby 客户端看的，
 *     那套进度我们这边无从得知，只能照用）。
 *
 * 单版本条目直接返回 null —— 走服务端进度，行为与改动前完全一致。
 */
private suspend fun loadVersionResumeSeconds(
  repository: PlaybackStateRepository,
  serverId: Long,
  item: EmbyItem,
  selectedMediaSourceId: String?,
  /** 设置里的「多版本优先选择」规则；必须与播放侧同一个值，否则读错版本的进度 */
  preference: MediaSourcePreference,
): Long? {
  if (serverId <= 0) return null
  val itemId = item.Id ?: return null
  val sourceId =
    effectiveEmbyMediaSourceId(item, selectedMediaSourceId, preference) ?: return null

  repository.getVideoDataByTitle(EmbyVersionProgress.key(serverId, itemId, sourceId))?.let {
    return it.lastPosition.toLong()
  }
  val anyVersion =
    repository.getVideoDataByTitlePrefix(EmbyVersionProgress.itemPrefix(serverId, itemId))
  return if (anyVersion != null) 0L else null
}

/**
 * 用 ProviderIds 现拼一条「外部链接」：`<服务器根>/?redirect=<Provider>:<Id>`。
 *
 * 什么时候会走到这里：服务端**没有**回 `ExternalUrls`（内置元数据源不带这个字段，
 * 只有 MetaTube 这类插件型数据源才有），只能自己拼。回的是
 * `名字 to 地址`（[MetaChipRow] 的 chip 文案与点击地址），服务器地址缺失时回 null（不显示这一项）。
 *
 * ## 两个坑（都是真机踩出来的）
 * 1. **必须落在服务器根路径上**：`?redirect=` 是 Emby Web 在**站点根**处理的跳转
 *    （用户环境里的正确地址就是 `http://192.168.2.124:8080/?redirect=JavBus:ACZD-218`）。
 *    写成 `$baseUrl?redirect=...`（baseUrl 是 `http://host:port`，没有结尾斜杠）时
 *    浏览器拿到的是「无路径 + query」，Emby Web 认不出来 ⇒ **跳回首页**。
 *    所以这里统一补上 `/`。
 * 2. **参数是 `Provider:Id` 两段式**，不是裸 Id：Emby 靠前面那段去认「这是哪个外部站点」
 *    （Providers 的 `Key`，见 [EmbyExternalIdInfo] 的 `UrlFormatString`）。
 *    有些数据源自己就把值存成了 `JavBus:ACZD-218` 这种已带前缀的形态 ⇒ 那时**不能再补**
 *    前缀，否则会拼出 `MetaTube:JavBus:ACZD-218` 这种双前缀。这就是下面那句
 *    `contains(':')` 判断的由来。
 */
internal fun externalLinkUrl(
  baseUrl: String?,
  providerName: String,
  providerId: String,
): Pair<String, String>? {
  // baseUrl 的形态是 `scheme://host:port`（见 EmbyServer.baseUrl），去掉可能的结尾斜杠，
  // 免得拼出 `http://host:8080//?redirect=` 这种双斜杠
  val origin = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
  val qualified = if (providerId.contains(':')) providerId else "$providerName:$providerId"
  return providerName to "$origin/?redirect=$qualified"
}

/**
 * 详情页的一行「分组元数据」：左边分组名，右边一排可点的 chip，超出屏宽横向滚动。
 *
 * ⚠️ 分组名用**固定宽度**（[META_LABEL_WIDTH]）而不是自适应：各行名字字数不同
 * （类型 / 标签 / 工作室 / 外部链接），不固定的话每行 chip 的起始边都不一样，整块看着就是歪的。
 *
 * [chips] 为空时整行不渲染 —— 免得出现「只有分组名、右边空着」的噪音行。
 */
@Composable
private fun MetaChipRow(
  label: String,
  chips: List<String>,
  onClick: (String) -> Unit,
) {
  if (chips.isEmpty()) return
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      modifier = Modifier.width(META_LABEL_WIDTH),
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      chips.forEach { value ->
        SuggestionChip(
          onClick = { onClick(value) },
          label = { Text(value) },
          colors = SuggestionChipDefaults.suggestionChipColors(),
        )
      }
    }
  }
}

/**
 * 分组名的固定宽度：容得下最长的一个。
 *
 * 最长的是「外部链接」四个字 —— `titleSmall`（14sp）下约 56dp，取 76dp 留出余量，
 * 这样用户把系统字号调大一两档时这个标签也不会被挤断（此前的 64dp 只够三个字）。
 */
private val META_LABEL_WIDTH = 76.dp

/**
 * 多版本媒体的版本下拉框：当前版本胶囊 + 下拉菜单。
 *
 * 与收藏页的类型切换器同一套写法（小 Surface + DropdownMenu）—— Compose 下 M3
 * 没有现成的 Spinner，ExposedDropdownMenuBox 的展开态在可滚动列表里点击判定容易出问题；
 * 这个小组合最稳，观感也与项目里其它下拉一致。
 */
@Composable
private fun MediaSourceSelector(
  sources: List<EmbyMediaSource>,
  selectedId: String?,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val current = sources.firstOrNull { it.Id == selectedId } ?: sources.first()

  Box {
    Surface(
      onClick = { expanded = true },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        // 版本名不可控（有的压制组会把整串信息塞进 Name），不封顶的话一个超长名字
        // 能把整行撑到换行、把下面一排按钮挤变形。
        // weight(1f, fill = false) 让中间的标题吃掉剩余空间 ⇒ 箭头稳定停在最右边，
        // 不再随版本名长短左右晃。
        modifier = Modifier
          .widthIn(min = 140.dp, max = 240.dp)
          .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
      ) {
        Text(
          text = "版本：${embyMediaSourceLabel(current)}",
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          // 吃掉整行剩余宽度（fill 默认 true）⇒ 箭头被顶到最右端，不随版本名长短左右晃。
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "切换版本",
          modifier = Modifier.size(20.dp),
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      // 菜单默认跟着锚点（那个小 Surface）的宽度走 —— 版本名一长就整条被省略号截掉，
      // 用户根本分不清两个版本哪个是哪个。这里单独给一个更宽的区间，
      // 配合下面「最多 3 行」让长名字**换行显示完整**，而不是截成一条看不懂的半句。
      modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
    ) {
      sources.forEach { source ->
        val id = source.Id ?: return@forEach
        DropdownMenuItem(
          text = {
            Text(
              text = embyMediaSourceLabel(source),
              // 不截断：宁可换行把名字显示全。3 行是上限，再多菜单会高得离谱。
              maxLines = 3,
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          leadingIcon = {
            // 选中项打勾；未选中放一个同宽占位，两行文字左对齐
            if (id == current.Id) {
              Icon(Icons.Default.Check, contentDescription = null)
            } else {
              Spacer(modifier = Modifier.width(24.dp))
            }
          },
          onClick = {
            expanded = false
            onSelect(id)
          },
        )
      }
    }
  }
}
/**
 * 主播放按钮。常规播放固定进入 mpv。
 */
@Composable
private fun PlayButton(
  label: String,
  onClick: () -> Unit,
  /**
   * 拉起播放器期间为 true：图标换成转圈、并**禁掉点击**。
   *
   * 禁点比转圈更重要 —— 只转圈不挡，用户等得不耐烦会再点一次，
   * 结果是起了两个播放页（而且后一个会把前一个顶掉，播放进度也乱）。
   */
  loading: Boolean = false,
  /**
   * 已观看进度（0..1）；null = 不画。
   *
   * **自左向右**垫一块色带（进度条形态），fraction=1 时正好铺满整枚按钮 ——
   * 播放进度是「一条时间轴」，横向走更符合直觉。
   * 填充层声明在内容层**之前** ⇒ 永远垫在图标 / 文字底下，不会糊住字。
   */
  progressFraction: Float? = null,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.primary)
        .clickable(
          enabled = !loading,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    val animatedPlayFill by animateFloatAsState(
      targetValue = progressFraction ?: 0f,
      animationSpec = tween(durationMillis = 300),
      label = "playProgressFill",
    )
    if (!loading && progressFraction != null && animatedPlayFill > 0f) {
      Box(
        modifier =
          Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(animatedPlayFill.coerceIn(0f, 1f))
            // 底色本身就是 primary，所以这里反过来用 **onPrimary 的浅色叠加**：
            // 下载按钮是「primary 叠在浅底上」，这里是「浅色叠在 primary 上」，同一个思路。
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      if (loading) {
        // ⚠️ 原来这里还有一枚 20dp 小转圈，已撤掉：等待期间主动效改成
        //   **整页居中**的大加载动画（见 EmbyDetailScreen 根 Box 末尾的
        //   `playLaunching` 覆盖层，走「外观 → 加载动画」统一入口）。
        //   按钮这一小块只留文字说明状态 —— 两处同时转会互相抢注意力。
        //   禁点仍然保留（clickable 的 enabled = !loading）：
        //   连点会起两个播放页，这个比转圈更重要。
        Text(
          text = "正在打开播放器…",
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = label,
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelLarge,
          // 动作键多了一个之后播放按钮更窄，「继续播放 · 1:23:45」得能优雅收尾
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

// ════════════════════════════════════════════════════════════════════════
// 媒体信息：基本信息 + 视频/音频/字幕轨道编码信息
// 数据组装（buildEmbyMediaInfoEntries / MediaInfoEntry）已抽到
// domain/emby/EmbyMediaInfo.kt —— 播放页「更多 → 媒体信息」弹窗用的是同一份，
// 两个页面不会再各写一套。这里只负责把 entries 画成详情页里的静态分区。
// ════════════════════════════════════════════════════════════════════════

@Composable
private fun MediaInfoSection(
  item: EmbyItem,
  /** 当前选中的版本 Id；多版本媒体下「容器 / 文件大小 / 码率 / 音视频轨道」都跟着它走 */
  selectedMediaSourceId: String? = null,
) {
  val entries =
    remember(item, selectedMediaSourceId) { buildEmbyMediaInfoEntries(item, selectedMediaSourceId) }

  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Default.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("媒体信息", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))

    if (entries.isEmpty()) {
      Text(
        text = "服务器没有返回可用的媒体信息",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(vertical = 6.dp)) {
        entries.forEach { entry ->
          when (entry) {
            is MediaInfoEntry.Section -> {
              Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
              )
            }

            is MediaInfoEntry.Row -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Text(
                  text = entry.label,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.width(76.dp),
                )
                Text(
                  text = entry.value,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}

/** 秒数转 hh:mm:ss */
private fun formatClock(seconds: Long): String {
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部的尺寸常量
// 对应 XML 里 AppBarLayout 的展开高度与 Toolbar 的 pin 高度。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 头部大图展开时的高度（静止时封面的高度）。
 *
 * 它同时是：① 封面能撑到的最大高度；② 列表第 0 项「封面占位稿」的高度。
 * 折叠时封面按滚动量线性缩矮，收到 [TOOLBAR_HEIGHT_DP] 为止。
 *
 * 想改「折叠触发点」就调这个值：调高 = 封面撑得更久、要滚更多才收完（触发点更靠后）；
 * 调低 = 收得更早更快。**不需要另外维护任何「触发距离」常量** —— 折叠点由几何关系决定。
 */
private val BACKDROP_HEADER_MAX_HEIGHT_DP = 320.dp

/** 折叠完成后 toolbar 剩下的高度（`layout_collapseMode="pin"` 那一条）。取值对齐 M3 TopAppBar。 */
private val TOOLBAR_HEIGHT_DP = 56.dp

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部：把「滚动位置」变成「封面高度」
// 这是整个折叠头部**唯一的状态来源**。设计上刻意不引入任何自定义手势：
// 列表滚动是唯一的输入，封面高度是它的纯函数。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 折叠头部的当前形态（由 [rememberHeaderCollapse] 每帧算出）。
 *
 * - `collapsedPx`：已经收掉的像素数（0 = 完全展开）
 * - `maxCollapsePx`：可收掉的总量 = 最大高度 − 工具栏高度
 * - `headerHeightPx`：当前封面高度（工具栏高度 .. 最大高度）
 */
@Immutable
private class HeaderCollapse(
  val collapsedPx: Float,
  val maxCollapsePx: Float,
  val headerHeightPx: Float,
) {
  /** 0（完全展开）~ 1（完全收起）。驱动淡出、视差、顶栏过渡。 */
  val progress: Float
    get() = if (maxCollapsePx <= 0f) 0f else (collapsedPx / maxCollapsePx).coerceIn(0f, 1f)

  /** 封面是否已经完全展开。下拉刷新只在此时接管下拉手势。 */
  val isExpanded: Boolean
    get() = collapsedPx <= 0.5f
}

/**
 * 由列表滚动位置推导封面形态。
 *
 * **原理**：列表第 0 项是一个「封面占位稿」（高度 = [maxHeight]），封面盖在它上面。
 * 占位稿的 y 从 0 一路减到 `−maxHeight`，取它的负值就是「已经滚了多远」，也就是封面该收多少。
 * 因为播放区紧跟着占位稿，所以**内容顶部永远贴着封面底边一起上移**：
 *  - 滚到「播放区到达工具栏下方」时封面正好收完（`collapsedPx == maxHeight − toolbarHeight`）；
 *  - 再往下滚，封面固定为工具栏高度，内容从它下面滑过。
 *
 * 于是「滑到播放按钮就折叠」是几何上自然成立的，不需要任何触发距离常量；
 * 也不需要在滚动时抢手势 —— 只有列表一个消费者，就没有手势冲突。
 *
 * ⚠️ 这里刻意读 `visibleItemsInfo` 里**真实 item** 的 `offset`，而不是
 * `firstVisibleItemScrollOffset`：后者与 contentPadding 的关系有歧义
 * （官方文档：`firstVisibleItemIndex` 不含内容内边距区），拿它推折叠量会差一截。
 * `visibleItemsInfo[i].offset` 是相对视口的真实布局位置，没有这个问题。
 */
@Composable
private fun rememberHeaderCollapse(
  listState: androidx.compose.foundation.lazy.LazyListState,
  maxHeight: Dp,
  toolbarHeight: Dp,
): HeaderCollapse {
  val density = LocalDensity.current
  val maxPx = with(density) { maxHeight.toPx() }
  val toolbarPx = with(density) { toolbarHeight.toPx() }

  val collapsedPx by
    remember(listState, maxPx) {
      derivedStateOf {
        // 占位稿已经滚出第一屏 ⇒ 封面收到最扁
        if (listState.firstVisibleItemIndex > 0) return@derivedStateOf maxPx
        val spacerY = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.offset
          // 首帧还没量出来：先当完全展开，避免封面闪一下
          ?: return@derivedStateOf 0f
        (-spacerY).toFloat().coerceIn(0f, maxPx)
      }
    }

  return HeaderCollapse(
    collapsedPx = collapsedPx,
    maxCollapsePx = (maxPx - toolbarPx).coerceAtLeast(0f),
    headerHeightPx = (maxPx - collapsedPx).coerceAtLeast(toolbarPx),
  )
}

// ══════════════════════════════════════════════════════════════════════════// 折叠动效参数
// 折叠**位置**由滚动驱动（见 [rememberHeaderCollapse]）—— 这里只放「外观怎么变」的参数：
// 封面反压扁（底图横向反向放大、维持 CenterCrop 的「缓慢推近」）+ 缓动曲线。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 折叠进度的缓动曲线：先慢后快。
 *
 * 匀速的「刻度感」本身就显廉价、也显压迫；`t²` 让画面一开始很从容、
 * 接近收起时才加速，收尾干脆。开方（`sqrt`）则是反效果 —— 一上来就窜，更压迫。
 */
private fun easeInQuad(t: Float): Float = t * t

// ══════════════════════════════════════════════════════════════════════════
// 简介（可展开 / 收起）
// ══════════════════════════════════════════════════════════════════════════

/** 收起时简介最多显示几行。5 行 ≈ 手机上 150~180 个字，太短了会「还没看出讲什么就被截掉」。 */
private const val OVERVIEW_COLLAPSED_LINES = 5

/**
 * 简介区。
 *
 * 长简介默认只显示 [OVERVIEW_COLLAPSED_LINES] 行 + 省略号，正文下方给一个
 * 「展开 / 收起」的文字按钮（带箭头图标，点标题或箭头整行都能点）。
 *
 * ⚠️ **按钮只在「真的被截断」时才显示**：判断依据是 `onTextLayout` 回调里的
 * `hasVisualOverflow`（布局器事后给出的真实结论），而不是「字数超过 N 个」这种估算。
 * 估算在中文 / 英文 / 不同字号 / 不同屏宽下必然误判 —— 短简介会无端多出一个点了没反应的
 * 「展开」，长简介又可能该给按钮却没给。见记忆 `ui-patterns.md`「文本截断」条。
 *
 * 展开状态用 `rememberSaveable`，转屏 / 返回再进不丢。
 */
@Composable
private fun OverviewSection(overview: String) {
  // key 带上正文：同一屏里条目被换掉（推荐区点进另一部片）时要重新收起 + 重新判定截断
  var expanded by rememberSaveable(overview) { mutableStateOf(false) }
  var overflowed by remember(overview) { mutableStateOf(false) }

  Column(modifier = Modifier.padding(16.dp)) {
    Text("简介", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = overview,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      // 展开后不限行数，让简介自然撑开
      maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_COLLAPSED_LINES,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { result ->
        // 展开态下 onTextLayout 报的是「没截断」，别用它把 overflowed 抹掉 ——
        // 否则一展开按钮就消失、再也没法收起。
        if (!expanded) overflowed = result.hasVisualOverflow
      },
    )
    if (overflowed) {
      Spacer(modifier = Modifier.height(2.dp))
      // 靠左、贴着正文起始边：简介正文本来就从这里开始，按钮跟它对齐读起来才是一体的。
      // 居中反而像一枚独立控件，且整行铺开会白占一行宽度。
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { expanded = !expanded }
          .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (expanded) "收起" else "展开",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Icon(
          imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 推荐：同一位演员 / 导演的其他影片
// ══════════════════════════════════════════════════════════════════════════

/** 推荐区实际展示几条 */
private const val RECOMMEND_COUNT = 6

/** 每个人最多取多少部候选作品回来再洗牌 —— 取少了随机不出来 */
private const val RECOMMEND_PERSON_LIMIT = 60

/** 推荐区要的条目类型：只要正片，不要单集 / MV */
private val RECOMMEND_ITEM_TYPES = listOf("Movie", "Series")

/** 一个人凑不满时，最多再往后看几个人 */
private const val RECOMMEND_MAX_PERSONS = 5

/**
 * 详情页的推荐区（纯渲染）。
 *
 * **随机在客户端做**：Emby 的 `/Items` 虽然有 `SortBy=Random`，但它跟 `SearchTerm`
 * 这类参数组合起来是否生效并不保证（媒体库搜索那趟已经验证过一次），
 * 所以一次多取一些候选，最后 `shuffled().take(6)` —— 每次进详情页看到的都不一样。
 *
 * **作者不足就往下补**：冷门片里排第一的演员可能只有一两部戏，所以只要还没凑够
 * [RECOMMEND_COUNT]，就顺着候选名单继续取下一个人的作品，补满为止。
 *
 * ⚠️ 这里**不自己发请求**：数据（含那次随机的取样）由 [EmbyDetailScreen] 加载好后作为
 * [recommendations] 传进来。请求留在这里的话，LazyColumn 把滚出视口的 item 回收、滚回来
 * 再重组时，LaunchedEffect 会跟着重跑 —— 表现就是「滚动也在刷推荐」。详见那边的注释。
 *
 * @param recommendations 已经加载好、且已经随机取样过的推荐列表；空则不渲染整块
 * @param onOpenItem 打开推荐项的详情页（itemId / 标题）
 */
@Composable
private fun RecommendationSection(
  server: EmbyServer?,
  recommendations: List<EmbyItem>,
  onOpenItem: (String, String) -> Unit,
) {
  // 查不到就整块不显示，不留一个空标题占地方
  if (recommendations.isEmpty()) return

  Column(modifier = Modifier.padding(top = 16.dp)) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("推荐", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(
        items = recommendations,
        key = { it.Id ?: it.Name.orEmpty() },
      ) { rec ->
        RecommendationCard(
          server = server,
          item = rec,
          onClick = {
            val id = rec.Id ?: return@RecommendationCard
            onOpenItem(id, rec.Name ?: "")
          },
        )
      }
    }
  }
}

/**
 * 详情页的「同类型」推荐区（纯渲染）。
 *
 * 与 [RecommendationSection]（按演员 / 导演推荐）互补 —— 那边按「人」找，这边按「题材」找；
 * 冷门片常常凑不出足够的同演员作品，但题材相同的片子通常管够。
 *
 * **用哪个类型**：一部片子常同时属于多个类型（剧情 / 惊悚 / 犯罪…）。固定取第一个
 * 的话，同一部片每次打开看到的推荐都一模一样。开关 `embyRandomGenreRecommend` 打开时
 * 每次进入把本片的类型**随机打乱**再取，于是每次打开题材都不同；关闭时退回「取第一个」。
 *
 * 随机容易挑到没几部同类的冷门类型，所以加载侧按打乱后的顺序**逐个尝试、取第一个查得到
 * 同类的**，避免「随机之后整块推荐消失」。全查不到（或服务端没刮削）才整块不显示。
 *
 * ⚠️ 与 [RecommendationSection] 同理：这里只画，不请求（原因见那边的注释）。
 *
 * @param genre 最终选中的类型名（显示在标题里）；null = 还没选出来，整块不渲染
 * @param picks 已经加载好的同类影片；空则整块不渲染
 */
@Composable
private fun GenreRecommendationSection(
  server: EmbyServer?,
  genre: String?,
  picks: List<EmbyItem>,
  onOpenItem: (String, String) -> Unit,
) {
  val activeGenre = genre
  if (picks.isEmpty() || activeGenre == null) return

  Column(modifier = Modifier.padding(top = 16.dp)) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("同类型 · $activeGenre", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(
        items = picks,
        key = { it.Id ?: it.Name.orEmpty() },
      ) { rec ->
        RecommendationCard(
          server = server,
          item = rec,
          onClick = {
            val id = rec.Id ?: return@RecommendationCard
            onOpenItem(id, rec.Name ?: "")
          },
        )
      }
    }
  }
}

/**
 * 一条推荐：竖版封面 + 片名 + 年份。
 *
 * 只显示 Primary 图，没有封面就不占位 —— 这种小卡片缺封面很常见，
 * 用占位图把整行撑起来反而更难看。
 */
@Composable
private fun RecommendationCard(
  server: EmbyServer?,
  item: EmbyItem,
  onClick: () -> Unit,
) {
  val primaryTag = item.ImageTags["Primary"]
  val posterUrl = remember(item.Id, primaryTag, server) {
    server?.let { s ->
      item.Id?.let { id -> EmbyClient.imageUrl(s, id, "Primary", primaryTag, 360) }
    }
  }
  Column(
    modifier = Modifier
      .width(100.dp)
      .clickable(onClick = onClick),
  ) {
    Card(
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f),
    ) {
      EmbyImage(
        server = server,
        url = posterUrl,
        contentDescription = item.Name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 360,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = item.displayTitle(),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 2,
      minLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    item.ProductionYear?.let { year ->
      Text(
        text = year.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 取推荐列表：作者候选 → 逐个取作品 → 去重 → 客户端随机取样。
 *
 * 全程在 IO 线程，里面每次 [EmbyViewModel.loadPersonItems] 都是一次网络请求，
 * 所以「顺位补人」是有成本的 —— 因此缓存一 [RECOMMEND_MAX_PERSONS] 上限，
 * 并且一旦凑够 [RECOMMEND_COUNT] 就立刻停手，不再往下请求。
 */
private suspend fun loadRecommendations(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
): List<EmbyItem> = withContext(Dispatchers.IO) {
  val candidates = item.People.orEmpty()
    .filter { it.Id != null && (it.Type == "Actor" || it.Type == "Director") }
    // Actor 优先：演员参演的片子更容易是「同款想看」，导演排在后面补位
    .sortedBy { if (it.Type == "Actor") 0 else 1 }
    .take(RECOMMEND_MAX_PERSONS)

  if (candidates.isEmpty()) return@withContext emptyList<EmbyItem>()

  val selfId = item.Id
  // LinkedHashMap：保序 + 按 Id 去重（同一个条日可能从多位作者的查询里重复返回）
  val pool = LinkedHashMap<String, EmbyItem>()

  for (person in candidates) {
    if (pool.size >= RECOMMEND_COUNT) break
    val works = viewModel.loadPersonItems(
      server = server,
      personId = person.Id ?: continue,
      limit = RECOMMEND_PERSON_LIMIT,
      // 只要正片：单集（Episode）会把推荐区塞满同一部剧的几十集，MV 同理
      includeItemTypes = RECOMMEND_ITEM_TYPES,
    )
    works.forEach { work ->
      val id = work.Id ?: return@forEach
      // 把当前这部剔掉：一进详情页就推荐自己，看着像 bug
      if (id == selfId) return@forEach
      pool.putIfAbsent(id, work)
    }
  }

  if (pool.isEmpty()) return@withContext emptyList<EmbyItem>()
  pool.values.shuffled().take(RECOMMEND_COUNT)
}

/**
 * 取某个题材下的同类影片：`SortBy=Random` 由服务端排序（本区不需要客户端再随机），
 * 拿到后剔掉自己、按 Id 去重（服务端偶发重复条目），最后截到 [RECOMMEND_COUNT]。
 *
 * 只查一个题材、一趟请求，所以「逐个试候选题材」的循环交给调用方（见 [EmbyDetailScreen]
 * 里那块推荐加载逻辑）—— 那边命中第一个非空结果就停。
 */
private suspend fun loadGenreRecommendations(
  server: EmbyServer,
  genre: String,
  selfId: String?,
): List<EmbyItem> = withContext(Dispatchers.IO) {
  runCatching {
    EmbyClient.getItems(
      server = server,
      genres = listOf(genre),
      includeItemTypes = RECOMMEND_ITEM_TYPES,
      // 随机排序：每次进详情页看到的同类顺序不同，避免「永远是那几部」
      sortBy = "Random",
      recursive = true,
      startIndex = 0,
      limit = 60,
    ).Items
  }.getOrDefault(emptyList())
    // 剔掉自己，并按 Id 去重（服务端偶发重复条目）
    .filter { it.Id != null && it.Id != selfId }
    .distinctBy { it.Id }
    .take(RECOMMEND_COUNT)
}
