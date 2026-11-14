package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.ui.browser.emby.AutoLoadMore
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyBrowseCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailQueries
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPagingFooter
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import app.marlboroadvance.mpvex.ui.browser.emby.pagingSource
import app.marlboroadvance.mpvex.ui.browser.emby.rememberDetailPage
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 季页的两种密度共用同一分页状态，切换不触发请求或改变播放队列。 */
@Composable
internal fun EmbySeasonDetailContent(
  season: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  onBack: () -> Unit,
  onOpenEpisode: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
  showBackButton: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val prefs = koinInject<BrowserPreferences>()
  val distance = rememberPredictivePreloadDistance(prefs)
  val seasonId = requireNotNull(season.Id)
  var series by remember(seasonId, server) {
    mutableStateOf(
      EmbyBrowseCache.results.peek<EmbyItem>(
        EmbyBrowseCache.owner(server),
        EmbyDetailQueries.seriesForSeason(seasonId),
      ),
    )
  }
  var seriesLoading by remember(seasonId, server) { mutableStateOf(series == null) }
  var seriesFailed by remember(seasonId, server) { mutableStateOf(false) }
  var reload by remember(seasonId, server) { mutableIntStateOf(0) }
  var compact by rememberSaveable(seasonId, server.id) { mutableStateOf(false) }
  val gridState = rememberLazyGridState()
  val resolvedSeriesId = series?.Id
  val page = if (resolvedSeriesId != null) rememberDetailPage(server, resolvedSeriesId, seasonId)
    else remember(seasonId, server) { PagedList<EmbyItem>(keyOf = { it.Id ?: it }) }
  val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = remember(series?.Id, seasonId, server) {
    { start, limit ->
      val seriesId = requireNotNull(series?.Id)
      EmbyDetailCache.loadEpisodes(server, seriesId, seasonId, start, limit) {
        val result = viewModel.loadEpisodes(server, seriesId, seasonId, start, limit)
        PagedResult(
          items = result.items.filter { it.Type.equals("Episode", ignoreCase = true) },
          totalCount = result.totalCount.takeIf { it > 0 },
          pageSize = limit,
          consumedCount = result.consumedCount,
        )
      }
    }
  }

  LaunchedEffect(season.SeriesId, season.ParentId, seasonId, server, reload) {
    val hadSeries = series != null
    if (!hadSeries) seriesLoading = true
    seriesFailed = false
    try {
      series = EmbyBrowseCache.results.load(
        owner = EmbyBrowseCache.owner(server),
        query = EmbyDetailQueries.seriesForSeason(seasonId),
        force = reload > 0,
      ) {
        resolveSeasonSeries(season) { id ->
          EmbyDetailCache.loadItem(server, id) { viewModel.loadItemDetail(server, id) }
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      if (!hadSeries) seriesFailed = true
    } finally {
      seriesLoading = false
    }
  }
  LaunchedEffect(series?.Id, seasonId, server) {
    if (series != null) page.refresh(loader)
  }
  page.AutoLoadMore(
    source = gridState.pagingSource(),
    enabled = series != null && !page.loading && !seriesLoading && page.error == null,
    distance = distance,
    loader = loader,
  )

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, seasonId, server, loader) {
    var initialResume = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        if (initialResume) {
          initialResume = false
        } else if (series != null && page.loaded) {
          scope.launch { delay(800); page.refresh(loader) }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    LazyVerticalGrid(
      columns = if (compact) GridCells.Adaptive(112.dp) else GridCells.Fixed(1),
      state = gridState,
      modifier = Modifier.fillMaxSize().navigationBarsPadding(),
      contentPadding = PaddingValues(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "season-hero", span = { GridItemSpan(maxLineSpan) }) {
        val title = seasonSeriesTitle(season, series)
        SeriesHero(series?.copy(Name = title) ?: EmbyItem(Name = title), server)
      }
      item(key = "season-tools", span = { GridItemSpan(maxLineSpan) }) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(season.Name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
          IconButton(onClick = {
            val anchor = gridState.firstVisibleItemIndex
            compact = !compact
            scope.launch { gridState.scrollToItem(anchor) }
          }) {
            Icon(
              imageVector = if (compact) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
              contentDescription = if (compact) "切换为详细列表" else "切换为小图标",
            )
          }
        }
      }
      if ((seriesLoading && series == null) || (page.loading && page.items.isEmpty())) {
        item(key = "season-loading", span = { GridItemSpan(maxLineSpan) }) {
          SeasonEpisodesSkeleton(compact)
        }
      }
      if (seriesFailed) {
        item(key = "season-series-error", span = { GridItemSpan(maxLineSpan) }) {
          Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("所属剧集加载失败", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { reload++ }) { Text("重试") }
          }
        }
      }
      itemsIndexed(page.items, key = { index, episode -> episode.Id ?: "episode-$index" }) { _, episode ->
        SeasonEpisodeCard(
          episode = episode,
          server = server,
          viewModel = viewModel,
          compact = compact,
          enabled = !episode.Id.isNullOrBlank(),
          onOpenDetail = { onOpenEpisode(episode) },
        )
      }
      if (series != null && !page.loading) {
        if (page.items.isEmpty() && page.error == null && !page.appending && !page.hasMore) {
          item(key = "season-empty", span = { GridItemSpan(maxLineSpan) }) {
            Text("此季没有分集", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        item(key = "season-footer", span = { GridItemSpan(maxLineSpan) }) {
          EmbyPagingFooter(page.appending, page.hasMore, page.error, page.items.size,
            onRetry = { scope.launch { page.retry(loader) } }, endText = "已加载全部 ${page.items.size} 集")
        }
      }
    }
    if (showBackButton) {
      IconButton(onClick = onBack,
        modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
      }
    }
  }
}

@Composable
private fun SeasonEpisodeCard(
  episode: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  compact: Boolean,
  enabled: Boolean,
  onOpenDetail: () -> Unit,
) {
  Column(
    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 16.dp)
      .clip(RoundedCornerShape(12.dp))
      .combinedClickable(enabled = enabled, onClick = onOpenDetail, onLongClick = onOpenDetail),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    EmbyImage(
      server = server,
      url = viewModel.imageUrl(server, episode, "Primary", if (compact) 480 else 1280),
      fallbackUrl = viewModel.imageUrl(server, episode, "Backdrop", if (compact) 480 else 1280),
      contentDescription = episode.Name,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
      maxWidth = if (compact) 480 else 1280,
    )
    Text(seasonEpisodeTitle(episode), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    if (!compact) {
      seasonEpisodeDuration(episode)?.let { duration ->
        Text(duration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      episode.Overview?.trim()?.takeIf { it.isNotEmpty() }?.let { overview ->
        Text(overview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(Modifier.height(4.dp))
  }
}

/** 仅旧 Episode 库路由需要查父项，确认是 Season 后原地渲染，不额外压栈。 */
@Composable
internal fun LegacySeasonLibraryContent(
  libraryId: String,
  server: EmbyServer?,
  viewModel: EmbyViewModel,
  modifier: Modifier = Modifier,
): Boolean {
  var parent by remember(libraryId, server) { mutableStateOf<EmbyItem?>(null) }
  var loading by remember(libraryId, server) { mutableStateOf(true) }
  var failed by remember(libraryId, server) { mutableStateOf(false) }
  var retry by remember(libraryId, server) { mutableIntStateOf(0) }
  val backStack = LocalBackStack.current
  LaunchedEffect(libraryId, server, retry) {
    if (server == null) return@LaunchedEffect
    loading = true
    failed = false
    try {
      parent = viewModel.loadItemDetail(server, libraryId).also { EmbyItemPolicy.requireSupported(it) }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      failed = true
    } finally {
      loading = false
    }
  }
  if (!loading && !failed && !parent?.Type.equals("Season", ignoreCase = true)) return false
  val current = parent
  if (current != null && server != null && !loading && !failed) {
    EmbySeasonDetailContent(current, server, viewModel,
      onBack = { backStack.removeLastOrNull() },
      onOpenEpisode = { episode -> episode.Id?.let { backStack.add(EmbyDetailScreen(it, episode.displayTitle())) } },
      modifier = modifier)
  } else {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
      if (failed) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text("加载失败", color = MaterialTheme.colorScheme.error)
          TextButton(onClick = { retry++ }) { Text("重试") }
        }
      } else CircularProgressIndicator()
      IconButton(onClick = { backStack.removeLastOrNull() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
      }
    }
  }
  return true
}
