package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

internal object EmbyDetailQueries {
  fun detail(itemId: String): String = EmbyBrowseCache.query("detail-v1", itemId)
  fun seasons(seriesId: String): String = EmbyBrowseCache.query("seasons-v1", seriesId)
  fun seriesForSeason(seasonId: String): String = EmbyBrowseCache.query("season-series-v1", seasonId)
  fun parentsForEpisode(episodeId: String): String = EmbyBrowseCache.query("episode-parents-v1", episodeId)
  fun episodes(seriesId: String, seasonId: String): String =
    EmbyBrowseCache.query("episodes-v1", seriesId, seasonId)
}

internal object EmbyDetailCache {
  fun item(server: EmbyServer, itemId: String): EmbyItem? =
    EmbyBrowseCache.results.peek(EmbyBrowseCache.owner(server), EmbyDetailQueries.detail(itemId))

  suspend fun loadItem(
    server: EmbyServer,
    itemId: String,
    force: Boolean = false,
    loader: suspend () -> EmbyItem,
  ): EmbyItem = EmbyBrowseCache.results.load(
    EmbyBrowseCache.owner(server), EmbyDetailQueries.detail(itemId), force, loader,
  )

  suspend fun loadItems(
    server: EmbyServer,
    query: String,
    force: Boolean = false,
    loader: suspend () -> List<EmbyItem>,
  ): List<EmbyItem> = EmbyBrowseCache.results.load(
    EmbyBrowseCache.owner(server), "items:$query", force, loader,
  )

  suspend fun loadEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String,
    startIndex: Int,
    limit: Int,
    loader: suspend () -> PagedResult<EmbyItem>,
  ): PagedResult<EmbyItem> = EmbyBrowseCache.results.load(
    owner = EmbyBrowseCache.owner(server),
    query = EmbyBrowseCache.query("episode-page-v1", seriesId, seasonId, startIndex, limit),
    loader = loader,
  )

  fun putEpisodes(server: EmbyServer, seriesId: String, seasonId: String, snapshot: PagedListSnapshot<EmbyItem>) {
    EmbyBrowseCache.results.put(
      EmbyBrowseCache.owner(server),
      "page:${EmbyDetailQueries.episodes(seriesId, seasonId)}",
      snapshot,
    )
  }

  fun invalidateItem(server: EmbyServer, itemId: String) {
    EmbyBrowseCache.results.invalidateQuery(
      EmbyBrowseCache.owner(server),
      EmbyDetailQueries.detail(itemId),
    )
  }

  fun updateUserData(server: EmbyServer, item: EmbyItem) {
    val itemId = item.Id ?: return
    val owner = EmbyBrowseCache.owner(server)
    EmbyBrowseCache.results.put(owner, EmbyDetailQueries.detail(itemId), item)
  }
}

@Composable
internal fun rememberDetailPage(server: EmbyServer, seriesId: String, seasonId: String): PagedList<EmbyItem> =
  rememberBrowsePage(server, EmbyDetailQueries.episodes(seriesId, seasonId))

@Composable
internal fun rememberCachedItems(server: EmbyServer, query: String): CachedItemsState {
  val owner = EmbyBrowseCache.owner(server)
  val key = "items:$query"
  val cached = EmbyBrowseCache.results.peek<List<EmbyItem>>(owner, key)
  return remember(owner, key) {
    CachedItemsState(
      initial = cached.orEmpty(),
      initiallyLoaded = cached != null,
      onChanged = { items -> EmbyBrowseCache.results.put(owner, key, items) },
    )
  }
}

internal class CachedItemsState(
  initial: List<EmbyItem>,
  initiallyLoaded: Boolean,
  private val onChanged: (List<EmbyItem>) -> Unit,
) {
  var items by androidx.compose.runtime.mutableStateOf(initial)
    private set
  var loading by androidx.compose.runtime.mutableStateOf(!initiallyLoaded)
    private set
  var refreshing by androidx.compose.runtime.mutableStateOf(false)
    private set
  var error by androidx.compose.runtime.mutableStateOf<String?>(null)
    private set
  var loaded by androidx.compose.runtime.mutableStateOf(initiallyLoaded)
    private set

  suspend fun refresh(loader: suspend () -> List<EmbyItem>) {
    val hadSnapshot = loaded
    if (hadSnapshot) refreshing = true else loading = true
    error = null
    try {
      items = loader()
      loaded = true
      onChanged(items)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      error = failure.message ?: "加载失败"
    } finally {
      loading = false
      refreshing = false
    }
  }
}
