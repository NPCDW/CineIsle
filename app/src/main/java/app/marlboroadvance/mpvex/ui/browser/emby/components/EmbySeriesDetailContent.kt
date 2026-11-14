package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyStudio
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailCache
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailQueries
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.rememberCachedItems
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal val SERIES_HERO_IMAGE_HEIGHT = 336.dp
private val SERIES_LOGO_HEIGHT = 96.dp
private val SERIES_LOGO_OVERLAP = SERIES_LOGO_HEIGHT / 2

/** Classic / Cine 共用的剧集详情；播放入口留在季和单集页面。 */
@Composable
internal fun EmbySeriesDetailContent(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  onBack: () -> Unit,
  onOpenSeason: (EmbyItem) -> Unit,
  onOpenPerson: (EmbyPerson) -> Unit,
  onOpenStudio: (EmbyStudio) -> Unit,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
  showBackButton: Boolean = true,
) {
  val seriesId = requireNotNull(item.Id)
  val seasonState = rememberCachedItems(server, EmbyDetailQueries.seasons(seriesId))
  val seasons = seasonState.items
  val seasonsLoading = seasonState.loading
  val seasonsFailed = seasonState.error != null && seasons.isEmpty()
  var retry by remember(item.Id, server) { mutableIntStateOf(0) }
  var galleryIndex by remember(item.Id, server) { mutableStateOf<Int?>(null) }
  val gallery = remember(item.BackdropImageTags) { seriesGalleryImages(item) }
  val tags = remember(item.ProviderIds) { seriesExternalTags(item) }
  val links = remember(item.ExternalUrls, item.ProviderIds, server) { seriesExternalLinks(item, server.baseUrl) }
  val people = item.People.orEmpty().filter { !it.Name.isNullOrBlank() }
  val studios = item.Studios.orEmpty().filter { !it.Name.isNullOrBlank() }

  LaunchedEffect(item, server, retry) {
    seasonState.refresh {
      EmbyDetailCache.loadItems(server, EmbyDetailQueries.seasons(seriesId), force = seasonState.loaded) {
        val loaded = mutableListOf<EmbyItem>()
        var offset = 0
        var finished = false
        for (pageIndex in 0 until 50) {
          currentCoroutineContext().ensureActive()
          val page = viewModel.loadSeasons(server, seriesId, startIndex = offset)
          loaded.addAll(page.items)
          offset += page.consumedCount
          if (page.consumedCount == 0 ||
            (page.totalCount > 0 && offset >= page.totalCount) ||
            (page.totalCount <= 0 && page.consumedCount < 50)
          ) {
            finished = true
            break
          }
        }
        check(finished)
        loaded.distinctBy { it.Id }
      }
    }
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().navigationBarsPadding(),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      item(key = "series-hero") { SeriesHero(item, server) }
      if (seasonsLoading || seasonsFailed || seasons.isNotEmpty()) {
        item(key = "series-seasons") {
          SeriesSection("季列表") {
            if (seasonsLoading && seasons.isEmpty()) {
              DetailRowSkeleton()
            } else if (seasonsFailed) {
              Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("季列表加载失败", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("重试") }
              }
            }
            if (seasons.isNotEmpty()) {
              LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(seasons) { _, season ->
                  Column(modifier = Modifier.width(112.dp).clickable { onOpenSeason(season) }) {
                    EmbyImage(
                      server = server,
                      url = viewModel.imageUrl(server, season),
                      contentDescription = season.Name,
                      modifier = Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(season.Name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
          }
        }
      }
      if (people.isNotEmpty()) {
        item(key = "series-people") { DetailPeople(people, server, onOpenPerson) }
      }
      if (gallery.isNotEmpty()) {
        item(key = "series-gallery") {
          SeriesSection("相册") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              itemsIndexed(gallery) { index, image ->
                EmbyImage(
                  server = server,
                  url = seriesImageUrl(server, item, image, 720),
                  contentDescription = "剧照 ${index + 1}",
                  modifier = Modifier.width(224.dp).height(126.dp).clip(RoundedCornerShape(12.dp)).clickable { galleryIndex = index },
                )
              }
            }
          }
        }
      }
      if (tags.isNotEmpty()) {
        item(key = "series-tags") { DetailExternalTags(tags) }
      }
      if (studios.isNotEmpty()) {
        item(key = "series-studios") {
          SeriesSection("工作室") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              itemsIndexed(studios) { _, studio ->
                SuggestionChip(onClick = { onOpenStudio(studio) }, label = { Text(studio.Name.orEmpty()) })
              }
            }
          }
        }
      }
      if (links.isNotEmpty()) {
        item(key = "series-links") { DetailExternalLinks(links) }
      }
    }
    if (showBackButton) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
      }
    }
  }
  galleryIndex?.let { index ->
    SeriesGalleryDialog(item, server, gallery, index, onDismiss = { galleryIndex = null })
  }
}

@Composable
internal fun SeriesHero(
  item: EmbyItem,
  server: EmbyServer,
  artworkUrls: List<String>? = null,
  onTitleClick: (() -> Unit)? = null,
) {
  val bannerUrls = artworkUrls ?: remember(item, server) {
    seriesBannerImages(item).map { seriesImageUrl(server, item, it, 1600) }
  }
  val logoUrl = remember(item.ImageTags, item.Id, server) {
    item.ImageTags["Logo"]?.takeIf { it.isNotBlank() }?.let { tag ->
      item.Id?.let { EmbyClient.imageUrl(server, it, "Logo", tag, 960) }
    }
  }
  Box(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier.fillMaxWidth().height(SERIES_HERO_IMAGE_HEIGHT)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      SeriesArtwork(server, bannerUrls, item.Name, Modifier.fillMaxSize(), ContentScale.Crop)
      Box(
        modifier = Modifier.fillMaxWidth().height(88.dp).background(
          Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)),
        ),
      )
      Box(
        modifier = Modifier.fillMaxWidth().height(SERIES_LOGO_HEIGHT * 2).align(Alignment.BottomCenter).background(
          Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)),
        ),
      )
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(SERIES_HERO_IMAGE_HEIGHT - SERIES_LOGO_OVERLAP))
      Box(
        modifier = Modifier.fillMaxWidth().height(SERIES_LOGO_HEIGHT),
        contentAlignment = Alignment.Center,
      ) {
        if (logoUrl != null) {
          SeriesArtwork(server, listOf(logoUrl), "${item.Name.orEmpty()} Logo",
            Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(SERIES_LOGO_HEIGHT), ContentScale.Fit)
        }
      }
      Text(
        text = item.Name.orEmpty(),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
          .longPressToCopy(item.Name, "剧名", onTitleClick),
      )
    }
  }
}

@Composable
private fun SeriesArtwork(server: EmbyServer, urls: List<String>, description: String?, modifier: Modifier, scale: ContentScale) {
  val cached = remember(server, urls) { urls.firstNotNullOfOrNull { EmbyImageLoader.peek(server, it) } }
  val bitmap by produceState<android.graphics.Bitmap?>(cached, server, urls) {
    value = cached
    for (url in urls) {
      val loaded = EmbyImageLoader.load(server, url, 1600)
      if (loaded != null) {
        value = loaded
        break
      }
    }
  }
  Box(modifier = modifier) {
    bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = scale) }
  }
}

private fun seriesImageUrl(server: EmbyServer, item: EmbyItem, image: SeriesImageRef, maxWidth: Int): String =
  EmbyClient.imageUrl(server, item.Id.orEmpty(), image.type, image.tag, maxWidth, imageIndex = image.index)

@Composable
private fun SeriesSection(title: String, content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
    content()
  }
}

@Composable
private fun SeriesGalleryDialog(item: EmbyItem, server: EmbyServer, images: List<SeriesImageRef>, initialIndex: Int, onDismiss: () -> Unit) {
  val pager = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        EmbyImage(
          server = server,
          url = seriesImageUrl(server, item, images[page], 1920),
          contentDescription = "剧照 ${page + 1}",
          contentScale = ContentScale.Fit,
          maxWidth = 1920,
          modifier = Modifier.fillMaxSize(),
        )
      }
      IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
      }
      Text("${pager.currentPage + 1} / ${images.size}", color = Color.White,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp))
    }
  }
}
