package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/** 普通媒体卡点击：单集先看详情，其余保留调用方原行为；显式播放操作不使用此分流。 */
fun openEmbyMediaCard(
  item: EmbyItem,
  onOpenDetail: (EmbyItem) -> Unit,
  onPlay: (EmbyItem) -> Unit,
) {
  if (item.Type.equals("Episode", ignoreCase = true)) {
    onOpenDetail(item)
  } else {
    onPlay(item)
  }
}
