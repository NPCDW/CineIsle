package app.marlboroadvance.mpvex.preferences

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.theme.AppTheme
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.theme.spacing
import app.marlboroadvance.mpvex.ui.cine.UiStyle
import kotlinx.collections.immutable.ImmutableList

class AppearancePreferences(
  preferenceStore: PreferenceStore,
) {
  val darkMode = preferenceStore.getEnum("dark_mode", DarkMode.System)
  val appTheme = preferenceStore.getEnum("app_theme", AppTheme.Dynamic)

  /**
   * 界面风格（`Classic` = 现有界面，`Cine` = 新的图像优先界面）。
   *
   * ⚠️ 默认必须是 [UiStyle.Classic]：老用户升级后界面一像素都不能变。
   * 也不能做成「首次启动让用户选」—— 新界面目前只覆盖首页，直接甩给所有人
   * 会得到「怎么一半新一半旧」的困惑，得让用户主动去外观设置里切。
   *
   * ⚠️ `getEnum` 存的是枚举 `name`（`Classic` / `Cine`），发布后不可改名，
   * 否则老用户的选择会静默失效、退回默认值。
   */
  val uiStyle = preferenceStore.getEnum("ui_style", UiStyle.Classic)

  val amoledMode = preferenceStore.getBoolean("amoled_mode", false)

  /**
   * 隐藏底部导航的文字标题，只留图标（首页 / 收藏 / 历史）。
   *
   * 两套界面都要遵守：Classic 的 `NavigationBar` 走 `label = null`，
   * Cine 的胶囊 Dock / 窄屏 Rail 只是不画那行文字。开关是「隐藏」而不是「显示」，
   * 默认 false ⇒ 升级后界面一个像素都不变。
   */
  val hideNavigationBarLabels = preferenceStore.getBoolean("hide_nav_labels", false)

  /**
   * 加载动画（见 `ui/theme/LoadingAnimation.kt`）。
   *
   * ⚠️ 存的是枚举的 `storageKey` 字符串，不是 `name` —— 与别处 `getEnum`（用 name）不同，
   *    所以这里手动读写，**发布后不可改这些 key 字符串**。
   * 默认 `system`（不启用 Lottie）：列表里每屏可能有几十个占位，
   * 全都跑 Lottie 会明显掉帧，所以动画默认只作用于「整屏加载」这类一次性场景。
   */
  val loadingAnimation = preferenceStore.getString("loading_animation", "system")

  /**
   * 加载动画的显示大小（缩放系数，1.0 = 各调用点写死的基准尺寸）。
   *
   * 默认 **1.1**：，但具体多大因人而异
   * （有人嫌列表里 48dp 够用，有人要看视频缓冲时更醒目的）⇒ 给一个略大的默认值，
   * 再放开到 0.5×~2.0× 让用户自己缩放。
   *
   * ⚠️ 存的是 Float 系数而不是 dp 绝对值：调用点的基准尺寸本就不同
   *    （列表整页 48dp、播放器画面层 96dp、行内 40dp），乘同一个系数才能
   *    「哪都变大、比例不变」；存绝对值就得给每个场景各配一个偏好。
   * ⚠️ 消费方是 `AppLoadingIndicator`（ui/theme/AppLoadingIndicator.kt）——
   *    它乘在 `size` 参数上，所以**调用方传了带尺寸的 modifier 时不吃缩放**
   *    （那种场景尺寸由布局决定，组件不该二次约束）。
   */
  val loadingAnimationScale = preferenceStore.getFloat("loading_animation_scale", 1.1f)
}

@Composable
fun MultiChoiceSegmentedButton(
  choices: ImmutableList<String>,
  selectedIndices: ImmutableList<Int>,
  onClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  MultiChoiceSegmentedButtonRow(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(MaterialTheme.spacing.medium),
  ) {
    choices.forEachIndexed { index, choice ->
      SegmentedButton(
        checked = selectedIndices.contains(index),
        onCheckedChange = { onClick(index) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
      ) {
        Text(text = choice)
      }
    }
  }
}
