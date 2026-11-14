package app.marlboroadvance.mpvex.ui.player.controls

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import app.marlboroadvance.mpvex.ui.player.Decoder
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AudioTracksSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.DecodersSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaybackSpeedSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaybackPlaylistSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlayerMediaDetailsSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlayerMoreSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.SubtitlesSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
fun PlayerSheets(
  sheetShown: Sheets,
  subtitles: ImmutableList<TrackNode>,
  onAddSubtitle: (Uri) -> Unit,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  onDisableSubtitles: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  audioTracks: ImmutableList<TrackNode>,
  onSelectAudio: (TrackNode) -> Unit,
  onDisableAudio: () -> Unit,
  decoder: Decoder,
  onUpdateDecoder: (Decoder) -> Unit,
  speed: Float,
  speedPresets: List<Float>,
  onSpeedChange: (Float) -> Unit,
  onAddSpeedPreset: (Float) -> Unit,
  onRemoveSpeedPreset: (Float) -> Unit,
  onResetSpeedPresets: () -> Unit,
  onMakeDefaultSpeed: (Float) -> Unit,
  onResetDefaultSpeed: () -> Unit,
  onOpenPanel: (Panels) -> Unit,
  onDismissRequest: () -> Unit,
) {
  when (sheetShown) {
    Sheets.None -> {}
    Sheets.SubtitleTracks -> {
      val subtitlesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it == null) return@rememberLauncherForActivityResult
        onAddSubtitle(it)
      }
      SubtitlesSheet(
        tracks = subtitles.toImmutableList(),
        onToggleSubtitle = onToggleSubtitle,
        isSubtitleSelected = isSubtitleSelected,
        onAddSubtitle = { subtitlesPicker.launch(arrayOf("*/*")) },
        onDisableSubtitles = onDisableSubtitles,
        onRemoveSubtitle = onRemoveSubtitle,
        onOpenSubtitleSettings = { onOpenPanel(Panels.SubtitleSettings) },
        onOpenSubtitleDelay = { onOpenPanel(Panels.SubtitleDelay) },
        onDismissRequest = onDismissRequest,
      )
    }
    Sheets.AudioTracks -> AudioTracksSheet(
      tracks = audioTracks,
      onSelect = onSelectAudio,
      onDisable = onDisableAudio,
      onOpenDelayPanel = { onOpenPanel(Panels.AudioDelay) },
      onDismissRequest,
    )
    Sheets.Decoders -> DecodersSheet(
      selectedDecoder = decoder,
      onSelect = onUpdateDecoder,
      onDismissRequest,
    )
    Sheets.More -> PlayerMoreSheet(onDismissRequest)
    Sheets.Playlist -> PlaybackPlaylistSheet(onDismissRequest)
    Sheets.MediaDetails -> PlayerMediaDetailsSheet(onDismissRequest)
    Sheets.PlaybackSpeed -> PlaybackSpeedSheet(
      speed,
      onSpeedChange = onSpeedChange,
      speedPresets = speedPresets,
      onAddSpeedPreset = onAddSpeedPreset,
      onRemoveSpeedPreset = onRemoveSpeedPreset,
      onResetPresets = onResetSpeedPresets,
      onMakeDefault = onMakeDefaultSpeed,
      onResetDefault = onResetDefaultSpeed,
      onDismissRequest = onDismissRequest,
    )
  }
}
