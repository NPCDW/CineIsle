package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import app.marlboroadvance.mpvex.ui.player.formatNetworkSpeed

/** Player cache-speed reading, sampled directly from mpv once per second. */
@Composable
fun NetworkSpeedLabel(
  speedBytesPerSec: Long?,
  modifier: Modifier = Modifier,
  tint: Color = app.marlboroadvance.mpvex.ui.theme.controlColor.copy(alpha = 0.85f),
) {
  if (speedBytesPerSec == null) return

  Text(
    text = formatNetworkSpeed(speedBytesPerSec),
    style = MaterialTheme.typography.labelMedium,
    fontFamily = FontFamily.Monospace,
    color = tint,
    maxLines = 1,
    modifier = modifier,
  )
}
