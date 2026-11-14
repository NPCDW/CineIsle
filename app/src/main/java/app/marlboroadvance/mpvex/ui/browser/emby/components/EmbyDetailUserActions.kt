package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 详情页状态按钮只在服务端确认后更新；透明按钮不遮住图片。 */
@Composable
internal fun DetailUserActions(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  enabled: Boolean = true,
  onUserDataChanged: (EmbyUserData) -> Unit = {},
  onPlayedChanged: () -> Unit = {},
  onBusyChanged: (Boolean) -> Unit = {},
  foregroundColor: Color = Color.White,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var data by remember(item.Id, server) { mutableStateOf(item.UserData ?: EmbyUserData()) }
  var favoriteBusy by remember(item.Id, server) { mutableStateOf(false) }
  var playedBusy by remember(item.Id, server) { mutableStateOf(false) }
  val busy = favoriteBusy || playedBusy
  LaunchedEffect(item.UserData) { item.UserData?.let { data = it } }
  LaunchedEffect(busy) { onBusyChanged(busy) }
  DisposableEffect(item.Id, server) { onDispose { onBusyChanged(false) } }

  fun update(favorite: Boolean) {
    if (!enabled || busy || item.Id.isNullOrBlank()) return
    if (favorite) favoriteBusy = true else playedBusy = true
    onBusyChanged(true)
    scope.launch {
      try {
        val updated = if (favorite) viewModel.setMediaFavorite(server, item.copy(UserData = data), !data.IsFavorite)
          else viewModel.setMediaPlayed(server, item.copy(UserData = data), !data.Played)
        // 收藏响应可能省略进度字段，不要用 DTO 默认值覆盖已有播放进度。
        data = if (favorite) data.copy(IsFavorite = updated.IsFavorite) else updated
        onUserDataChanged(data)
        if (!favorite) onPlayedChanged()
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) {
        Toast.makeText(context, "状态未确认，请刷新后重试", Toast.LENGTH_SHORT).show()
      } finally {
        if (favorite) favoriteBusy = false else playedBusy = false
        onBusyChanged(false)
      }
    }
  }
  Row {
    IconButton(onClick = { update(true) }, enabled = enabled && !busy && !item.Id.isNullOrBlank()) {
      if (favoriteBusy) CircularProgressIndicator(Modifier.size(20.dp), color = foregroundColor, strokeWidth = 2.dp)
      else Icon(if (data.IsFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
        contentDescription = if (data.IsFavorite) "取消收藏" else "收藏",
        tint = if (data.IsFavorite) MaterialTheme.colorScheme.primary else foregroundColor)
    }
    IconButton(onClick = { update(false) }, enabled = enabled && !busy && !item.Id.isNullOrBlank()) {
      if (playedBusy) CircularProgressIndicator(Modifier.size(20.dp), color = foregroundColor, strokeWidth = 2.dp)
      else Icon(if (data.Played) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
        contentDescription = if (data.Played) "标记为未播放" else "标记为已播放",
        tint = if (data.Played) MaterialTheme.colorScheme.primary else foregroundColor)
    }
  }
}
