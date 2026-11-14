package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

/** 只缓存已加载范围，不预取整库。重新进入立即恢复，再由页面的 reload 静默更新。 */
@Composable
internal fun rememberBrowsePage(server: EmbyServer, query: String): PagedList<EmbyItem> {
  val owner = EmbyBrowseCache.owner(server)
  val key = "page:$query"
  val page = androidx.compose.runtime.remember(owner, query) {
    PagedList<EmbyItem>(keyOf = { it.Id ?: it }).also { list ->
      EmbyBrowseCache.results.peek<PagedListSnapshot<EmbyItem>>(owner, key)?.let(list::restore)
    }
  }
  val revision = androidx.compose.runtime.remember(owner, query) { EmbyBrowseCache.results.revision(owner) }
  LaunchedEffect(page, owner, key) {
    snapshotFlow { listOf(page.items, page.total, page.hasMore, page.loaded) }.collect {
      // 仅监听不可变列表引用和分页元数据，避免快照监听重复复制整份列表。
      val snapshot = page.snapshot()
      if (snapshot.loaded) EmbyBrowseCache.results.put(owner, key, snapshot, revision)
    }
  }
  DisposableEffect(page, owner, key) {
    onDispose { page.snapshot().takeIf { it.loaded }?.let { EmbyBrowseCache.results.put(owner, key, it, revision) } }
  }
  return page
}
