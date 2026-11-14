package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import `is`.xyz.mpv.Utils

@Composable
fun PlayerMediaDetailsSheet(
  onDismissRequest: () -> Unit,
) {
  val activity = LocalActivity.current as? PlayerActivity ?: return
  val state by activity.loembyPlaybackState.collectAsState()
  val item = state?.item
  val series = state?.series
  val server = state?.spec?.server ?: activity.currentPlaybackServerForUi()

  PlayerSheet(onDismissRequest, customMaxWidth = 760.dp) {
    if (item == null || server == null) {
      androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
      ) { Text(stringResource(R.string.player_media_details_unavailable)) }
      return@PlayerSheet
    }

    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val imageType = if (item.BackdropImageTags.isNotEmpty()) "Backdrop" else "Primary"
      val imageTag = item.BackdropImageTags.firstOrNull() ?: item.ImageTags["Primary"]
      val imageUrl = item.Id?.let { EmbyClient.imageUrl(server, it, imageType, imageTag, 720) }
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 520.dp) {
          Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EmbyImage(
              url = imageUrl,
              server = server,
              contentDescription = item.Name,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
            )
            MediaDetailsHeaderText(item, series, state?.resolved?.source?.RunTimeTicks)
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            EmbyImage(
              url = imageUrl,
              server = server,
              contentDescription = item.Name,
              contentScale = ContentScale.Crop,
              modifier = Modifier.width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
            )
            MediaDetailsHeaderText(
              item = item,
              series = series,
              sourceRunTimeTicks = state?.resolved?.source?.RunTimeTicks,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
      item.Overview?.trim()?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
      }
      val people = item.People.orEmpty().filter { !it.Name.isNullOrBlank() }
      if (people.isNotEmpty()) {
        Text(stringResource(R.string.cine_detail_cast), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          items(people) { person ->
            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              EmbyImage(
                url = person.Id?.let { EmbyClient.imageUrl(server, it, "Primary", person.PrimaryImageTag, 240) },
                server = server,
                contentDescription = person.Name,
                placeholder = Icons.Default.Person,
                modifier = Modifier.size(72.dp).clip(CircleShape),
              )
              Spacer(Modifier.height(6.dp))
              Text(
                person.Name.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MediaDetailsHeaderText(
  item: EmbyItem,
  series: EmbyItem?,
  sourceRunTimeTicks: Long?,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
    val seriesTitle = series?.Name ?: item.SeriesName
    if (!seriesTitle.isNullOrBlank()) {
      Text(
        seriesTitle,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
    }
    Text(
      item.displayTitle(),
      style = if (seriesTitle.isNullOrBlank()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
      fontWeight = if (seriesTitle.isNullOrBlank()) FontWeight.Bold else FontWeight.Normal,
    )
    val activeDuration = sourceRunTimeTicks ?: item.RunTimeTicks
    val facts = listOfNotNull(
      item.CommunityRating?.let { "★ %.1f".format(java.util.Locale.US, it) },
      item.PremiereDate?.substringBefore('T')?.takeIf(String::isNotBlank),
      activeDuration?.takeIf { it > 0 }?.let {
        Utils.prettyTime(EmbyTicks.ticksToSeconds(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
      },
    )
    if (facts.isNotEmpty()) {
      Text(
        facts.joinToString("  "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
