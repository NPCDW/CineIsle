package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 玻璃胶囊搜索框。
 *
 * 不用 `OutlinedTextField`：它自带 56dp 高度与浮动标签，放进 Cine 的 44dp 顶栏体系里
 * 会显得「厚一层」；这里只需要「一个能打字的胶囊」。
 */
@Composable
fun CineSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "搜索…",
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }
  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(44.dp)
      .clip(shape)
      .background(colors.chipSurface)
      .border(1.dp, colors.chipBorder, shape)
      .padding(start = 14.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.Search,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
    Box(
      modifier = Modifier
        .weight(1f)
        .padding(start = 10.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      if (query.isEmpty()) {
        Text(text = placeholder, style = type.body, color = colors.onSurfaceVariant)
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = type.body.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.progressFill),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester),
      )
    }
    CineIconButton(
      icon = Icons.Filled.Close,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      onClick = {
        keyboard?.hide()
        onClose()
      },
      size = 34.dp,
      iconSize = 17.dp,
    )
  }
}
