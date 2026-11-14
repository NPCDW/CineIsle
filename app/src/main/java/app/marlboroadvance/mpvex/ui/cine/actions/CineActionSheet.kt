package app.marlboroadvance.mpvex.ui.cine.actions

import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CinePlayController

@Stable
class CineActionSheetState {
  var target by mutableStateOf<CineMediaUi?>(null)
    private set
  fun show(media: CineMediaUi) { target = media }
  fun dismiss() { target = null }
}

@Composable
fun rememberCineActionSheetState(): CineActionSheetState = remember { CineActionSheetState() }

/** Only navigation and playback are available on Android. */
@Composable
fun CineActionSheetHost(
  state: CineActionSheetState,
  server: EmbyServer?,
  viewModel: EmbyViewModel,
  playController: CinePlayController,
  onOpenDetail: (CineMediaUi) -> Unit,
  fromDetail: Boolean = false,
) {
  val media = state.target ?: return
  if (server == null) return
  DropdownMenu(expanded = true, onDismissRequest = state::dismiss) {
    if (!viewModel.isContainerOnly(media.item) || media.item.Type == "Series") {
      DropdownMenuItem(text = { Text("播放") }, onClick = {
        state.dismiss(); playController.play(media.item, media.resumeSeconds)
      })
      DropdownMenuItem(text = { Text("从头播放") }, onClick = {
        state.dismiss(); playController.playFromStart(media.item)
      })
    }
    if (!fromDetail) DropdownMenuItem(text = { Text("详情") }, onClick = {
      state.dismiss(); onOpenDetail(media)
    })
  }
}
