package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CancellationException

@Composable
fun PlaybackPlaylistSheet(
  onDismissRequest: () -> Unit,
) {
  val activity = LocalActivity.current as? PlayerActivity ?: return
  val initialPlaylistIndex = remember { activity.playlistIndex }
  val listState = rememberLazyListState()
  val server = activity.currentPlaybackServerForUi()
  val urls = activity.playlistUrls()
  val ids = activity.playlistItemIds()
  val loopback = remember {
    org.koin.java.KoinJavaComponent.get<LoopbackPlaybackServer>(LoopbackPlaybackServer::class.java)
  }
  val seeds = remember(urls, ids) {
    urls.mapIndexed { index, url ->
      PlaylistDisplaySeed(
        queueTitle = activity.playlistTitleAt(index).orEmpty(),
        queueItemId = ids.getOrNull(index),
        playback = loopback.specForUrl(url)?.toPlaylistPlaybackMetadata(),
      )
    }
  }
  var hydratedItems by remember(seeds, server) { mutableStateOf(emptyList<EmbyItem>()) }
  var hydrationFailed by remember(seeds, server) { mutableStateOf(false) }

  // Position this sheet opening once. Hydration only replaces row metadata and must
  // not pull the list back after the user has started scrolling.
  LaunchedEffect(Unit) {
    if (initialPlaylistIndex in seeds.indices) {
      listState.scrollToItem(initialPlaylistIndex)
    }
  }

  LaunchedEffect(seeds, server) {
    hydratedItems = emptyList()
    hydrationFailed = false
    val hydrationIds = seeds.mapNotNull { seed ->
      (seed.playback?.itemId ?: seed.queueItemId)?.takeIf(String::isNotBlank)
    }.distinct()
    if (server == null || hydrationIds.isEmpty()) return@LaunchedEffect

    try {
      val repository = org.koin.java.KoinJavaComponent.get<app.marlboroadvance.mpvex.domain.emby.EmbyRepository>(
        app.marlboroadvance.mpvex.domain.emby.EmbyRepository::class.java,
      )
      hydratedItems = repository.getItemsByIds(server, hydrationIds)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      hydrationFailed = true
    }
  }

  val entries = remember(seeds, hydratedItems) {
    projectPlaylistDisplayEntries(seeds, hydratedItems)
  }

  PlayerSheet(onDismissRequest, customMaxWidth = 620.dp, landscapeSide = true, landscapeSideWidth = 520.dp) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
      Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(Icons.Default.PlaylistPlay, contentDescription = null)
        Text(stringResource(R.string.player_control_playlist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      }
      if (hydrationFailed) {
        Text(
          stringResource(R.string.player_playlist_metadata_failed),
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          color = MaterialTheme.colorScheme.error,
        )
      }
      PlaylistEntries(entries, server, activity, listState, onDismissRequest)
    }
  }
}

@Composable
private fun PlaylistEntries(
  entries: List<PlaylistDisplayEntry>,
  server: EmbyServer?,
  activity: PlayerActivity,
  listState: LazyListState,
  onDismissRequest: () -> Unit,
) {
  LazyColumn(state = listState) {
    itemsIndexed(entries, key = { index, entry -> "$index:${entry.itemId.orEmpty()}" }) { index, entry ->
      PlaylistEntry(
        entry = entry,
        server = server,
        selected = index == activity.playlistIndex,
        onClick = {
          activity.playPlaylistIndex(index)
          onDismissRequest()
        },
      )
    }
  }
}

@Composable
private fun PlaylistEntry(
  entry: PlaylistDisplayEntry,
  server: EmbyServer?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val imageUrl = server?.let { current -> entry.image?.toUrl(current) }
  val fallbackUrl = server?.let { current -> entry.fallbackImage?.toUrl(current) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = !selected, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 4.dp),
    shape = RoundedCornerShape(14.dp),
    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent,
  ) {
    Row(
      Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      EmbyImage(
        url = imageUrl,
        fallbackUrl = fallbackUrl,
        server = server,
        contentDescription = entry.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.width(152.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          entry.title,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
        entry.runTimeTicks?.let { ticks ->
          Text(
            Utils.prettyTime(EmbyTicks.ticksToSeconds(ticks).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun PlaylistImageRef.toUrl(server: EmbyServer): String = EmbyClient.imageUrl(
  server = server,
  itemId = itemId,
  imageType = type.embyName,
  tag = tag,
  maxWidth = 480,
)
