package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.domain.emby.buildEmbyVersionMediaInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress
import app.marlboroadvance.mpvex.domain.emby.playback.LoembyMediaSelection
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.ui.browser.emby.AutoLoadMore
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyBrowseCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailQueries
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPagingFooter
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import app.marlboroadvance.mpvex.ui.browser.emby.pagingSource
import app.marlboroadvance.mpvex.ui.browser.emby.rememberDetailPage
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import org.koin.compose.koinInject
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import app.marlboroadvance.mpvex.ui.utils.scrollToTop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val EpisodeSelectionSaver = listSaver<EpisodeSelection?, Int>(
  save = { value -> value?.let { listOf(it.version, it.video, it.audio, it.subtitle) }.orEmpty() },
  restore = { if (it.isEmpty()) null else EpisodeSelection(it[0], it[1], it[2], it[3]) },
)

/** Movie／Episode 共用的单媒体详情；季上下文只在 Episode 模式创建。 */
@Composable
internal fun EmbyMediaDetailContent(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  onBack: () -> Unit,
  onOpenEpisode: (EmbyItem) -> Unit,
  onOpenSeries: (EmbyItem) -> Unit,
  onOpenPerson: (EmbyPerson) -> Unit,
  modifier: Modifier = Modifier,
) {
  val isEpisode = item.Type.equals("Episode", ignoreCase = true)
  val isMovie = item.Type.equals("Movie", ignoreCase = true)
  require(isEpisode || isMovie) { "仅支持 Movie 或 Episode 详情" }
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val scope = rememberCoroutineScope()
  val preloadDistance = rememberPredictivePreloadDistance(koinInject<BrowserPreferences>())
  val sources = item.MediaSources.orEmpty()
  val sourceIdentity = sources.map { it.Id to it.MediaStreams.orEmpty().map { stream -> stream.Type to stream.Index } }.toString()
  var baseline by rememberSaveable(item.Id, server.sourceKey, server.remoteId, sourceIdentity, stateSaver = EpisodeSelectionSaver) {
    mutableStateOf<EpisodeSelection?>(null)
  }
  var selection by rememberSaveable(item.Id, server.sourceKey, server.remoteId, sourceIdentity, stateSaver = EpisodeSelectionSaver) {
    mutableStateOf<EpisodeSelection?>(null)
  }
  var configurationFailed by remember(item.Id, server) { mutableStateOf(false) }
  var configRetry by remember(item.Id, server) { mutableIntStateOf(0) }
  val itemId = requireNotNull(item.Id)
  var parents by remember(itemId, server) {
    mutableStateOf(
      EmbyBrowseCache.results.peek<EpisodeParents>(
        EmbyBrowseCache.owner(server),
        EmbyDetailQueries.parentsForEpisode(itemId),
      ),
    )
  }
  var parentsLoading by remember(itemId, server) { mutableStateOf(isEpisode && parents == null) }
  var parentsFailed by remember(item.Id, server) { mutableStateOf(false) }
  var parentsRetry by remember(item.Id, server) { mutableIntStateOf(0) }
  var userData by remember(item.Id, server) { mutableStateOf(item.UserData ?: EmbyUserData()) }
  var actionsBusy by remember(item.Id, server) { mutableStateOf(false) }
  var launching by remember(item.Id, server) { mutableStateOf(false) }
  var more by remember(item.Id) { mutableStateOf(false) }
  var versionResumeSeconds by remember(item.Id, server) { mutableStateOf<Long?>(null) }
  val playbackStateRepository = koinInject<PlaybackStateRepository>()
  val listState = rememberLazyListState()
  val episodesState = rememberLazyListState()
  val resolvedParents = parents
  val page = if (resolvedParents != null) rememberDetailPage(
    server,
    requireNotNull(resolvedParents.series.Id),
    resolvedParents.seasonId,
  ) else remember(itemId, server) { PagedList<EmbyItem>(keyOf = { it.Id ?: it }) }
  var locating by remember(item.Id, server) { mutableStateOf(false) }
  val source = selection?.version?.let { sources.getOrNull(it - 1) }
  val supportsDirect = source?.let(LoembyMediaSelection::supportsDirectLink) == true
  val currentItem = item.copy(UserData = userData)
  val effectivePositionTicks = versionResumeSeconds?.let(EmbyTicks::secondsToTicks) ?: userData.PlaybackPositionTicks
  val resumeItem = currentItem.copy(UserData = userData.copy(PlaybackPositionTicks = effectivePositionTicks))
  val resume = episodeHasResume(resumeItem, source)
  fun switchEpisode(episode: EmbyItem) {
    if (actionsBusy || launching || episode.Id.isNullOrBlank() || episode.Id == item.Id) return
    focusManager.clearFocus(force = true)
    listState.scrollToTop()
    onOpenEpisode(episode)
  }
  val groups = remember(item.MediaSources, item.Id) { buildEmbyVersionMediaInfo(item) }
  val mediaInfoHeight = rememberDetailMediaInfoHeight(groups)
  val people = item.People.orEmpty().filter { !it.Name.isNullOrBlank() }
  val tags = remember(item.ProviderIds) { seriesExternalTags(item) }
  val links = remember(item.ExternalUrls, item.ProviderIds, server) { seriesExternalLinks(item, server.baseUrl) }
  val artwork = remember(item.Id, item.ImageTags, item.BackdropImageTags, server, isMovie) {
    mediaArtworkUrls(server, item, backdropFirst = isMovie)
  }

  LaunchedEffect(item.UserData) { item.UserData?.let { userData = it } }
  LaunchedEffect(item.Id, server.id, source?.Id, sources.size) {
    val itemId = item.Id
    val sourceId = source?.Id
    versionResumeSeconds = if (sources.size <= 1 || itemId.isNullOrBlank() || sourceId.isNullOrBlank()) {
      null
    } else {
      playbackStateRepository.getVideoDataByTitle(EmbyVersionProgress.key(server.id, itemId, sourceId))?.lastPosition?.toLong()
        ?: if (playbackStateRepository.getVideoDataByTitlePrefix(EmbyVersionProgress.itemPrefix(server.id, itemId)) != null) 0L else null
    }
  }
  LaunchedEffect(item.Id, sourceIdentity, server, configRetry) {
    if (baseline != null || sources.isEmpty()) return@LaunchedEffect
    configurationFailed = false
    try {
      val config = viewModel.loadEpisodePlaybackConfiguration(server)
      val initial = initialEpisodeSelection(sources, server, config.automaticVersionPolicy)
      baseline = initial
      selection = initial
    } catch (error: CancellationException) { throw error } catch (_: Exception) { configurationFailed = true }
  }
  LaunchedEffect(item.Id, server, parentsRetry, isEpisode) {
    if (!isEpisode) {
      parents = null
      parentsLoading = false
      parentsFailed = false
      return@LaunchedEffect
    }
    val hadParents = parents != null
    if (!hadParents) parentsLoading = true
    parentsFailed = false
    try {
      parents = EmbyBrowseCache.results.load(
        owner = EmbyBrowseCache.owner(server),
        query = EmbyDetailQueries.parentsForEpisode(itemId),
        force = parentsRetry > 0,
      ) {
        resolveEpisodeParents(item) { id ->
          EmbyDetailCache.loadItem(server, id) { viewModel.loadItemDetail(server, id) }
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      if (!hadParents) parentsFailed = true
    } finally {
      parentsLoading = false
    }
  }
  val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = remember(parents, server) {
    { start, limit ->
      val owner = requireNotNull(parents)
      val seriesId = requireNotNull(owner.series.Id)
      EmbyDetailCache.loadEpisodes(server, seriesId, owner.seasonId, start, limit) {
        val result = viewModel.loadEpisodes(server, seriesId, owner.seasonId, start, limit)
        PagedResult(
          result.items.filter { it.Type.equals("Episode", ignoreCase = true) },
          result.totalCount.takeIf { it > 0 },
          limit,
          consumedCount = result.consumedCount,
        )
      }
    }
  }
  suspend fun locateCurrent() {
    locating = true
    try {
      val index = locateEpisodeInSeason(page, item.Id, loader)
      if (index >= 0) episodesState.scrollToItem(index)
    } finally { locating = false }
  }
  LaunchedEffect(parents, server, isEpisode) {
    if (isEpisode && parents != null) {
      page.refresh(loader)
      locateCurrent()
    }
  }
  if (isEpisode) {
    page.AutoLoadMore(episodesState.pagingSource(), enabled = parents != null && !locating && !page.loading && page.error == null,
      distance = preloadDistance, loader = loader)
  }

  fun startPlayback(fromStart: Boolean = false, forceDirect: Boolean = false) {
    val selected = selection ?: return
    val initial = baseline ?: return
    if (launching || actionsBusy) return
    launching = true
    val policy = episodeSelectionPolicy(selected, initial)
    if (policy == "manual" && source?.Id.isNullOrBlank()) {
      launching = false
      Toast.makeText(context, "当前版本缺少有效 ID，无法应用手动选择", Toast.LENGTH_SHORT).show()
      return
    }
    scope.launch {
      try {
        viewModel.play(
          server = server, item = currentItem,
          resumeSeconds = if (!fromStart && resume) EmbyTicks.ticksToSeconds(effectivePositionTicks) else 0L,
          mediaSourceId = if (policy == "manual") source?.Id else null,
          mediaSourceLabel = if (policy == "manual" && source != null) {
            episodeVersionOption(source, selected.version - 1).label
          } else null,
          fromStart = fromStart,
          videoSelect = selected.video, audioSelect = selected.audio, subtitleSelect = selected.subtitle,
          selectPolicy = policy, versionSelect = selected.version,
          useDirectLink = forceDirect && supportsDirect, selectionCurrentItemOnly = true,
        )
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) { Toast.makeText(context, "播放失败，请重试", Toast.LENGTH_SHORT).show() }
      finally { launching = false }
    }
  }
  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
      item(key = "episode-hero") {
        val confirmedSeries = parents?.series?.takeIf { !it.Id.isNullOrBlank() }
        val heroItem = if (isEpisode) confirmedSeries ?: EmbyItem(Name = item.SeriesName ?: "剧集") else item
        SeriesHero(
          heroItem,
          server,
          artworkUrls = artwork,
          onTitleClick = confirmedSeries?.let { series -> { onOpenSeries(series) } },
        )
        if (isEpisode) {
          Text(
            seasonEpisodeTitle(item),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).longPressToCopy(item.Name, "集名"),
          )
        }
        val facts = listOfNotNull(
          item.PremiereDate?.takeIf { it.isNotBlank() }?.substringBefore('T'),
          episodeVersionDuration(source),
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(16.dp))
      }
      if (isEpisode && parentsFailed) item(key = "episode-parent-error") { DetailRetry("所属剧集加载失败", onRetry = { parentsRetry++ }) }
      item(key = "episode-selectors") {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (configurationFailed) DetailRetry("播放选择配置加载失败", onRetry = { configRetry++ })
          else if (selection == null && sources.isNotEmpty()) DetailSelectorsSkeleton()
          val selected = selection
          if (selected != null && source != null) {
            EpisodeSelector("版本", sources.mapIndexed { index, value -> episodeVersionOption(value, index) },
              selected.version, enabled = !launching && !actionsBusy, onSelect = { selection = changeEpisodeVersion(sources, server, it) })
            EpisodeSelector("视频", episodeTrackOptions(source, "Video"), selected.video, !launching && !actionsBusy,
              onSelect = { selection = selected.copy(video = it) })
            EpisodeSelector("音频", episodeTrackOptions(source, "Audio"), selected.audio, !launching && !actionsBusy,
              onSelect = { selection = selected.copy(audio = it) })
            EpisodeSelector("字幕", episodeTrackOptions(source, "Subtitle"), selected.subtitle, !launching && !actionsBusy,
              onSelect = { selection = selected.copy(subtitle = it) })
          }
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { startPlayback() }, enabled = selected != null && !launching && !actionsBusy,
              modifier = Modifier.weight(1f)) { Text(if (resume) "继续播放" else "播放") }
            Box {
              IconButton(
                onClick = { more = true },
                enabled = selected != null && (resume || supportsDirect) && !launching && !actionsBusy,
              ) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多播放选项")
              }
              DropdownMenu(expanded = more && !actionsBusy, onDismissRequest = { more = false }) {
                if (resume) DropdownMenuItem(text = { Text("从头播放") }, onClick = { more = false; startPlayback(fromStart = true) })
                if (supportsDirect) DropdownMenuItem(text = { Text("直链播放") }, onClick = { more = false; startPlayback(forceDirect = true) })
              }
            }
          }
          if (sources.isEmpty()) Text("没有可用版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      item.Overview?.trim()?.takeIf { it.isNotEmpty() }?.let { overview ->
        item(key = "episode-overview") { DetailSection("简介") { Text(overview, modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
      }
      if (isEpisode) {
      item(key = "episode-season") {
        DetailSection("当前季所有集") {
          if ((parentsLoading && parents == null) || (page.loading && page.items.isEmpty())) {
            DetailRowSkeleton(cardWidth = 168, cardHeight = 90)
          }
          LazyRow(state = episodesState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(page.items, key = { index, episode -> episode.Id ?: "episode-$index" }) { _, episode ->
              val current = episode.Id == item.Id
              Column(modifier = Modifier.width(168.dp).clip(RoundedCornerShape(12.dp))
                .border(if (current) 2.dp else 0.dp, if (current) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(enabled = !current && !launching && !actionsBusy && episode.Id != null) {
                  switchEpisode(episode)
                }.padding(4.dp)) {
                EmbyImage(server = server, url = viewModel.imageUrl(server, episode, "Primary", 480),
                  fallbackUrl = viewModel.imageUrl(server, episode, "Backdrop", 480), contentDescription = episode.Name,
                  modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)))
                Text(seasonEpisodeTitle(episode), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                  overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(top = 8.dp))
              }
            }
          }
          if (parents != null) {
            EmbyPagingFooter(page.appending, page.hasMore, page.error, page.items.size,
              onRetry = { scope.launch { page.retry(loader); locateCurrent() } }, endText = "已加载全部 ${page.items.size} 集")
            if (page.hasMore && page.error == null && !page.appending && !locating) {
              TextButton(onClick = { scope.launch { page.loadMore(loader) } }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("加载更多") }
            }
          }
        }
      }
      }
      if (people.isNotEmpty()) item(key = "episode-people") { DetailPeople(people, server, onOpenPerson) }
      if (links.isNotEmpty()) item(key = "episode-links") { DetailExternalLinks(links) }
      if (tags.isNotEmpty()) item(key = "episode-tags") { DetailExternalTags(tags) }
      groups.forEachIndexed { index, group ->
        item(key = "episode-info-$index") {
          DetailMediaInfoGroup(group, mediaInfoHeight)
        }
      }
    }
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
      }
      Spacer(Modifier.weight(1f))
      DetailUserActions(
        item = currentItem,
        server = server,
        viewModel = viewModel,
        enabled = !launching,
        onUserDataChanged = { updated ->
          userData = updated
          EmbyDetailCache.invalidateItem(server, itemId)
          val cachedItem = item.copy(UserData = updated)
          EmbyDetailCache.updateUserData(server, cachedItem)
          val owner = parents
          if (owner != null) {
            page.updateFirst({ it.Id == itemId }) { it.copy(UserData = updated) }
            EmbyDetailCache.putEpisodes(
              server,
              requireNotNull(owner.series.Id),
              owner.seasonId,
              page.snapshot(),
            )
          }
        },
        onBusyChanged = { actionsBusy = it },
      )
    }
    if (launching) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
  }
}

@Composable
private fun EpisodeSelector(label: String, options: List<EpisodeTrackOption>, value: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
  var expanded by remember(label) { mutableStateOf(false) }
  val canChoose = enabled && episodeSelectorCanChoose(options)
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(48.dp))
    Box(modifier = Modifier.weight(1f)) {
      OutlinedButton(onClick = { expanded = true }, enabled = canChoose, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        EpisodeOptionText(options.firstOrNull { it.value == value } ?: EpisodeTrackOption("自动", 0), Modifier.weight(1f))
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = expanded && canChoose, onDismissRequest = { expanded = false }) {
        options.forEach { option -> DropdownMenuItem(text = { EpisodeOptionText(option) },
          onClick = { expanded = false; onSelect(option.value) }) }
      }
    }
  }
}

@Composable
private fun EpisodeOptionText(option: EpisodeTrackOption, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    option.supportingText?.let { details ->
      Text(
        details,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun DetailRetry(message: String, onRetry: () -> Unit) {
  Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
    TextButton(onClick = onRetry) { Text("重试") }
  }
}
