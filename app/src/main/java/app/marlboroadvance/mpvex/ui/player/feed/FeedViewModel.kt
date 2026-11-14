package app.marlboroadvance.mpvex.ui.player.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30
private const val LOAD_MORE_THRESHOLD = 5
private val PLAYABLE_TYPES = listOf("Movie", "Episode", "Video", "MusicVideo", "TvProgram")

/** 视界流状态；播放本身由 Activity 中唯一的 mpv 实例负责。 */
class FeedViewModel(
  initialItems: List<FeedItem>,
  private val embyRepository: EmbyRepository,
  private val libraryId: String?,
  private val favoritesOnly: Boolean,
  private val server: EmbyServer,
  initialConsumedCount: Int = initialItems.size,
) : ViewModel() {

  internal fun playbackServer(): EmbyServer = embyRepository.sessionServer(server)

  val items = mutableStateListOf<FeedItem>().apply { addAll(initialItems.filter(::isFeedItemPlayable)) }
  var index by mutableIntStateOf(if (items.isEmpty()) -1 else 0)

  var positionSec by mutableDoubleStateOf(0.0)
  var durationSec by mutableDoubleStateOf(0.0)
  var paused by mutableStateOf(false)
  var buffering by mutableStateOf(false)
  var buffered by mutableStateOf(false)
  var controlsVisible by mutableStateOf(true)
  val landscape: Boolean = true
  val errors = mutableStateMapOf<Int, String>()

  var playToken by mutableIntStateOf(0)
    private set
  private var reloadSeekSec by mutableDoubleStateOf(0.0)

  private var exhausted = false
  private var loadingMore = false
  private var nextStartIndex = initialConsumedCount

  val current: FeedItem? get() = items.getOrNull(index)
  val hasNext: Boolean get() = index in 0 until items.lastIndex
  val hasPrev: Boolean get() = index > 0

  fun goNext(): Boolean {
    if (!hasNext) {
      maybeLoadMore()
      return false
    }
    maybeLoadMore()
    moveTo(index + 1)
    return true
  }

  fun goPrev(): Boolean {
    if (!hasPrev) return false
    moveTo(index - 1)
    return true
  }

  private fun moveTo(newIndex: Int) {
    if (newIndex == index || newIndex !in items.indices) return
    index = newIndex
    resetPlaybackState(newIndex)
  }

  internal fun resetPlaybackState(newIndex: Int) {
    if (newIndex !in items.indices) return
    positionSec = 0.0
    durationSec = 0.0
    paused = false
    buffering = false
    buffered = false
    errors.remove(newIndex)
  }

  fun syncFromPager(newIndex: Int) {
    if (newIndex in items.indices && newIndex != index) moveTo(newIndex)
    if (newIndex >= items.lastIndex - 3) maybeLoadMore()
  }

  fun requestReload() {
    reloadSeekSec = positionSec.takeIf { it > 1.0 } ?: 0.0
    resetPlaybackState(index)
    playToken++
  }

  fun consumeReloadSeek(): Double = reloadSeekSec.also { reloadSeekSec = 0.0 }

  private fun maybeLoadMore() {
    if (exhausted || loadingMore || libraryId == null) return
    if (index < items.lastIndex - LOAD_MORE_THRESHOLD) return
    loadingMore = true
    viewModelScope.launch {
      try {
        runCatching {
          val server = embyRepository.ensureLoggedIn(playbackServer()) ?: return@runCatching
          val existing = items.mapTo(HashSet()) { it.itemId }
          repeat(50) {
            currentCoroutineContext().ensureActive()
            val result = embyRepository.getItems(
              server = server,
              parentId = libraryId,
              includeItemTypes = PLAYABLE_TYPES,
              sortBy = "Random",
              sortOrder = "Ascending",
              startIndex = nextStartIndex,
              limit = PAGE_SIZE,
              recursive = true,
              mediaTypes = listOf("Video"),
              isFolder = false,
              isFavorite = if (favoritesOnly) true else null,
            )
            nextStartIndex += result.consumedCount
            val added = result.Items.mapNotNull { item ->
              val id = item.Id ?: return@mapNotNull null
              if (id in existing) return@mapNotNull null
              val feed = feedPlaybackItemOf(item, item.Name ?: "未命名") ?: return@mapNotNull null
              existing.add(id)
              feed
            }
            exhausted = result.consumedCount == 0 ||
              (result.TotalRecordCount > 0 && nextStartIndex >= result.TotalRecordCount)
            if (added.isNotEmpty()) {
              items.addAll(added)
              return@runCatching
            }
            if (exhausted) return@runCatching
          }
        }.onFailure {
          if (it is CancellationException) throw it
          exhausted = true
        }
      } finally {
        loadingMore = false
      }
    }
  }

  fun errorOf(idx: Int): String? = errors[idx]

  companion object {
    fun factory(
      initialItems: List<FeedItem>,
      embyRepository: EmbyRepository,
      libraryId: String?,
      favoritesOnly: Boolean,
      server: EmbyServer,
      initialConsumedCount: Int = initialItems.size,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FeedViewModel(initialItems, embyRepository, libraryId, favoritesOnly, server, initialConsumedCount) as T
    }
  }
}
