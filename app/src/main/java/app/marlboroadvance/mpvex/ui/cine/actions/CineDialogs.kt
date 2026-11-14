package app.marlboroadvance.mpvex.ui.cine.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * Cine 的二级表单外壳（玻璃底上划抽屉）。
 *
 * ## 与 Classic 的 `EmbyActionSheet` 是什么关系
 * 结构一一对应（标题 + 内容 + 取消/确定），但**视觉层完全重画**：
 * 玻璃底、26dp 上圆角、自绘拖拽条、Cine 字阶、药丸按钮。
 * 不直接复用 Classic 那套的原因很直白：它的皮是 M3 默认 Surface + 16dp 内边距，
 * 长按菜单已经是玻璃了，点进二级表单突然变回一块不透明的白/黑板子，
 * 视觉上等于「菜单和表单不是同一个 App」。
 *
 * ⚠️ 逻辑一个都不在这里 —— 表单只负责收集值，写服务器仍然由调用方
 * （`CineActionSheetHost`）用 `viewModel.viewModelScope` 去跑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CineSheetFrame(
  title: String,
  onDismissRequest: () -> Unit,
  confirmLabel: String? = null,
  onConfirm: (() -> Unit)? = null,
  confirmEnabled: Boolean = true,
  confirmError: Boolean = false,
  dismissLabel: String? = "取消",
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = colors.glassSurface,
    scrimColor = colors.surface.copy(alpha = 0.52f),
    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    tonalElevation = 0.dp,
    // 自绘拖拽条：M3 默认那条是实色，压在玻璃上像一道脏印
    dragHandle = {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.glassBorder),
        )
      }
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp),
    ) {
      Text(
        text = title,
        style = type.pageBarTitle,
        color = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(16.dp))

      content()

      if (onConfirm != null) {
        Spacer(Modifier.height(20.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (dismissLabel != null) {
            CineSheetButton(
              label = dismissLabel,
              onClick = onDismissRequest,
              modifier = Modifier.weight(1f),
            )
          }
          CineSheetButton(
            label = confirmLabel ?: "确定",
            onClick = onConfirm,
            enabled = confirmEnabled,
            danger = confirmError,
            modifier = Modifier.weight(1f),
          )
        }
      } else if (dismissLabel != null) {
        // 纯展示型表单（候选列表 / 图片管理）没有「确定」，但必须留一个出口。
        // 早期这里直接落到 else，dismissLabel 被静默丢弃 ⇒ 那两屏只能靠手势下滑关，
        // 而候选列表还要兼作「返回上一步」，没有按钮等于走不回去。
        Spacer(Modifier.height(20.dp))
        CineSheetButton(
          label = dismissLabel,
          onClick = onDismissRequest,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Spacer(Modifier.height(8.dp))
      }
    }
  }
}

/** 玻璃表单里的一个输入框：透明底 + 细边框，聚焦时描边换 primary。 */
@Composable
fun CineSheetTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  singleLine: Boolean = true,
  minLines: Int = 1,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(12.dp) }

  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label, style = type.cardMeta) },
    singleLine = singleLine,
    minLines = minLines,
    shape = shape,
    textStyle = type.body,
    modifier = modifier.fillMaxWidth(),
    colors = OutlinedTextFieldDefaults.colors(
      // 容器保持透明：玻璃底透出来才是「同一层材质」，填白/填黑会把玻璃挡住
      focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
      unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
      focusedBorderColor = colors.progressFill,
      unfocusedBorderColor = colors.glassBorder,
      focusedLabelColor = colors.progressFill,
      unfocusedLabelColor = colors.onSurfaceVariant,
      cursorColor = colors.progressFill,
      focusedTextColor = colors.onSurface,
      unfocusedTextColor = colors.onSurface,
    ),
  )
}

/** 可整行点击的复选项（点文字也能切换，不用非戳小方框）。 */
@Composable
fun CineSheetCheckRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  title: String,
  subtitle: String,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = CheckboxDefaults.colors(
        checkedColor = colors.progressFill,
        checkmarkColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
        uncheckedColor = colors.onSurfaceVariant,
      ),
    )
    Column(modifier = Modifier.padding(start = 6.dp)) {
      Text(text = title, style = type.itemTitle, color = colors.onSurface)
      Text(text = subtitle, style = type.cardMeta, color = colors.onSurfaceVariant)
    }
  }
}

/** 药丸按钮：主操作实心、次操作玻璃描边。46dp 与 Hero 的播放按钮同一手感。 */
@Composable
private fun CineSheetButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  danger: Boolean = false,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = androidx.compose.material3.MaterialTheme.colorScheme
  val shape = remember { RoundedCornerShape(50) }
  val container = when {
    danger -> scheme.error
    else -> colors.progressFill
  }

  Box(
    modifier = modifier
      .height(46.dp)
      .clip(shape)
      .background(if (enabled) container else container.copy(alpha = 0.4f))
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = type.button,
      color = if (danger) scheme.onError else scheme.onPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
