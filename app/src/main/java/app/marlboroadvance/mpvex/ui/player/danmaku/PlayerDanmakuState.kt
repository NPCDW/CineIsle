package app.marlboroadvance.mpvex.ui.player.danmaku

import android.util.Log
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuMatcher
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Canonical rendering values for the retained automatic danmaku renderer. */
data class DanmakuRenderParams(
  val enabled: Boolean = false,
  val alphaPercent: Int = CANONICAL_ALPHA_PERCENT,
  val fontScale: Float = CANONICAL_FONT_SCALE,
  val speedScale: Float = CANONICAL_SPEED_SCALE,
  val areaRatio: Float = CANONICAL_AREA_RATIO,
  val maxOnScreen: Int = CANONICAL_MAX_ON_SCREEN,
  val preventOverlapping: Boolean = CANONICAL_PREVENT_OVERLAPPING,
) {
  companion object {
    const val CANONICAL_ALPHA_PERCENT = 100
    const val CANONICAL_FONT_SCALE = 1f
    const val CANONICAL_SPEED_SCALE = 1f
    const val CANONICAL_AREA_RATIO = 0.5f
    const val CANONICAL_MAX_ON_SCREEN = 30
    const val CANONICAL_PREVENT_OVERLAPPING = true
  }
}

/** Automatic matching and renderer state; the removed in-player sheet owns no state here. */
class PlayerDanmakuState(
  private val preferences: DanmakuPreferences,
  private val scope: CoroutineScope,
) {
  private companion object {
    const val TAG = "PlayerDanmaku"
  }

  private val _comments = MutableStateFlow<List<DanmakuComment>>(emptyList())
  val comments: StateFlow<List<DanmakuComment>> = _comments.asStateFlow()

  private val _episodeKey = MutableStateFlow("")
  val episodeKey: StateFlow<String> = _episodeKey.asStateFlow()

  private val _renderParams = MutableStateFlow(DanmakuRenderParams())
  val renderParams: StateFlow<DanmakuRenderParams> = _renderParams.asStateFlow()

  private var loadJob: Job? = null

  private fun host(): String = preferences.host.get().ifBlank { DanmakuClient.DEFAULT_HOST }

  private fun credentials() = DanmakuClient.Credentials(
    appId = preferences.appId.get(),
    appSecret = preferences.appSecret.get(),
  )

  private fun refreshRenderParams() {
    _renderParams.value = DanmakuRenderParams(
      enabled = preferences.enabled.get() && _comments.value.isNotEmpty(),
    )
  }

  fun onEpisodeChanged(seriesKey: String?, title: String, episodeIndex: Int) {
    if (title.isBlank() && episodeIndex == 0) {
      Log.w(TAG, "Skipping danmaku match: title and episode are unavailable (seriesKey=$seriesKey)")
      return
    }

    loadJob?.cancel()
    loadJob = null
    _comments.value = emptyList()
    _episodeKey.value = if (title.isBlank() && episodeIndex == 0) "" else "${seriesKey ?: title}|$episodeIndex"
    refreshRenderParams()

    if (!preferences.enabled.get() || !preferences.autoMatch.get()) return
    loadForTitle(title, episodeIndex)
  }

  /** Retained automatic title matching; canonical comments are sent directly to the renderer. */
  fun loadForTitle(title: String, episodeIndex: Int) {
    loadJob?.cancel()
    loadJob = scope.launch {
      val matched = DanmakuMatcher.autoMatch(
        host = host(),
        title = title,
        episodeIndex = episodeIndex,
        credentials = credentials(),
        withRelated = false,
      )
      _comments.value = matched.comments
      matched.episode?.takeIf { it.episodeId > 0 }?.let { episode ->
        _episodeKey.value = "${matched.anime?.animeTitle ?: title}#${episode.episodeId}"
      }
      refreshRenderParams()
      Log.i(
        TAG,
        "Danmaku auto-match finished: ${matched.comments.size} comments (${matched.error ?: "ok"}), key=${_episodeKey.value}",
      )
    }
  }

  fun dispose() {
    loadJob?.cancel()
    loadJob = null
    _comments.value = emptyList()
    refreshRenderParams()
  }
}
