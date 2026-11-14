package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing
import app.marlboroadvance.mpvex.ui.player.controls.LocalPlayerButtonsClickEvent

@Suppress("ModifierClickableOrder")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlsButton(
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String,
  color: Color? = null,
  showBackground: Boolean = true,
  /** 为 false 时按钮置灰且不响应点击（用于播放队列首尾 / 请求进行中） */
  enabled: Boolean = true,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val effectiveColor = if (enabled) color else (color ?: controlColor).copy(alpha = 0.38f)

  val clickEvent = LocalPlayerButtonsClickEvent.current
  Surface(
    modifier =
      modifier
        .clip(CircleShape)
        .combinedClickable(
          enabled = enabled,
          onClick = {
            clickEvent()
            onClick()
          },
          onLongClick = onLongClick,
          interactionSource = interactionSource,
          indication = ripple(),
        ),
    shape = CircleShape,
    color = if (showBackground) PlayerControlGlassFill else Color.Transparent,
    contentColor = effectiveColor ?: controlColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = if (showBackground) BorderStroke(1.dp, PlayerControlGlassBorder) else null,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = effectiveColor ?: controlColor,
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.small)
          .size(20.dp),
    )
  }
}

/**
 * 播放器控件的「玻璃底」：近黑半透明 + 极细高光描边。
 *
 * 之前用的是 `surfaceContainer` 55% 不透明度，压在视频上看起来就是一个个实心灰圆盘；
 * 换成近黑低透明度后，底下的画面会透出来，才像官方那种「浮在视频之上的玻璃按钮」。
 * 各页面共用同一组常量，改一处即可全局生效。
 */
val PlayerControlGlassFill = Color.Black.copy(alpha = 0.32f)

/** 播放器控件玻璃描边（极细白色高光），让圆边在明暗两种画面上都能被看清 */
val PlayerControlGlassBorder = Color.White.copy(alpha = 0.14f)

@Preview
@Composable
private fun PreviewControlsButton() {
  ControlsButton(
    Icons.Default.CatchingPokemon,
    title = "Preview",
    onClick = {},
  )
}
