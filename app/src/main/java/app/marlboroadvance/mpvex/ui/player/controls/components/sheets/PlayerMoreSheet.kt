package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.LoembyVersionOption
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.controls.isVersionsRowEnabled

private enum class MorePage { Main, Versions, Anime4K }

@Composable
fun PlayerMoreSheet(
  onDismissRequest: () -> Unit,
) {
  val activity = LocalActivity.current as? PlayerActivity ?: return
  val playback by activity.loembyPlaybackState.collectAsState()
  var page by remember { mutableStateOf(MorePage.Main) }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    customMaxWidth = 620.dp,
    landscapeSide = true,
    landscapeSideWidth = 420.dp,
  ) {
    when (page) {
      MorePage.Main -> MoreMenu(
        versionsEnabled = isVersionsRowEnabled(playback?.versions.orEmpty()),
        onVersions = { page = MorePage.Versions },
        onRotate = {
          activity.rotatePlayerScreen()
          onDismissRequest()
        },
        onAnime4K = { page = MorePage.Anime4K },
        onAudioOnly = {
          activity.requestBackgroundPlayback()
          onDismissRequest()
        },
        onStatistics = {
          activity.togglePlaybackStatistics()
          onDismissRequest()
        },
      )
      MorePage.Versions -> VersionMenu(
        versions = playback?.versions.orEmpty(),
        activeSourceId = playback?.resolved?.source?.Id,
        onBack = { page = MorePage.Main },
        onSelect = {
          activity.selectLoembyVersion(it)
          onDismissRequest()
        },
      )
      MorePage.Anime4K -> Anime4KMenu(
        selectedMode = activity.currentAnime4KMode(),
        onBack = { page = MorePage.Main },
        onSelect = { mode ->
          activity.applyAnime4KMode(mode)?.let(activity::showPlayerToast)
          onDismissRequest()
        },
      )
    }
  }
}

@Composable
private fun MoreMenu(
  versionsEnabled: Boolean,
  onVersions: () -> Unit,
  onRotate: () -> Unit,
  onAnime4K: () -> Unit,
  onAudioOnly: () -> Unit,
  onStatistics: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    MoreRow(
      Icons.Default.PlaylistPlay,
      stringResource(R.string.player_more_versions),
      onVersions,
      enabled = versionsEnabled,
    )
    MoreRow(Icons.Default.RotateRight, stringResource(R.string.player_more_rotate), onRotate)
    MoreRow(Icons.Outlined.AutoAwesome, stringResource(R.string.player_more_anime4k), onAnime4K)
    MoreRow(Icons.Default.Headphones, stringResource(R.string.player_more_audio_only), onAudioOnly)
    MoreRow(Icons.Default.Equalizer, stringResource(R.string.player_more_statistics), onStatistics)
  }
}

@Composable
private fun MoreRow(
  icon: ImageVector,
  title: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 15.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = contentColor)
    Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
  }
}

@Composable
private fun VersionMenu(
  versions: List<LoembyVersionOption>,
  activeSourceId: String?,
  onBack: () -> Unit,
  onSelect: (LoembyVersionOption) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    SubmenuHeader(stringResource(R.string.player_more_versions), onBack)
    if (versions.isEmpty()) {
      Text(
        stringResource(R.string.player_more_no_versions),
        modifier = Modifier.padding(20.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyColumn {
        items(versions, key = { it.url }) { option ->
          ChoiceRow(
            title = option.title,
            subtitle = option.hint,
            selected = option.sourceId == activeSourceId,
            onClick = { onSelect(option) },
          )
        }
      }
    }
  }
}

@Composable
private fun Anime4KMenu(
  selectedMode: Anime4KManager.Mode,
  onBack: () -> Unit,
  onSelect: (Anime4KManager.Mode) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    SubmenuHeader(stringResource(R.string.player_more_anime4k), onBack)
    LazyColumn {
      items(Anime4KManager.Mode.entries, key = { it.name }) { mode ->
        ChoiceRow(
          title = stringResource(mode.titleRes),
          subtitle = if (mode == Anime4KManager.Mode.OFF) null else "Fast",
          selected = mode == selectedMode,
          onClick = { onSelect(mode) },
        )
      }
    }
  }
}

@Composable
private fun SubmenuHeader(title: String, onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.player_more_back))
    Text(title, style = MaterialTheme.typography.titleLarge)
  }
}

@Composable
private fun ChoiceRow(
  title: String,
  subtitle: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Column(Modifier.weight(1f).padding(start = 10.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      subtitle?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
