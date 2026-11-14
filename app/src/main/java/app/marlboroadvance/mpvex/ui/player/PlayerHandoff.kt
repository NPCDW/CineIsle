package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter

/**
 * Validated playback metadata carried by the mpv launch intent.
 *
 * This is no longer a cross-engine handoff. It only normalizes the queue and Emby metadata used
 * by [EmbyPlaybackInput] and [PlayerActivity].
 */
internal data class PlayerHandoff(
  val playlist: List<Uri> = emptyList(),
  val index: Int = 0,
  val embyServerId: Long? = null,
  val embyItemIds: List<String> = emptyList(),
  val episodeNumbers: List<String> = emptyList(),
) {
  companion object {
    const val EXTRA_PLAYLIST = "playlist"
    const val EXTRA_TITLES = "playlist_titles"
    const val EXTRA_SERIES_KEYS = "playlist_series_keys"
    const val EXTRA_INDEX = "playlist_index"
    const val EXTRA_POSITION = "position"
    const val EXTRA_SERIES_KEY = "emby_series_key"
    const val EXTRA_HEADERS = "headers"
    const val EXTRA_SERIES_NAMES = "playlist_series_names"
    const val EXTRA_SERIES_NAME = "emby_series_name"

    /** Read the queue metadata without inferring identity from a media URL. */
    fun read(intent: Intent): PlayerHandoff {
      @Suppress("DEPRECATION")
      val playlist =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, Uri::class.java)
        } else {
          intent.getParcelableArrayListExtra(EXTRA_PLAYLIST)
        }) ?: emptyList<Uri>()

      return PlayerHandoff(
        playlist = playlist,
        index = intent.getIntExtra(EXTRA_INDEX, 0),
        embyServerId =
          intent.getLongExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, -1L).takeIf { it > 0 },
        embyItemIds =
          intent.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS) ?: emptyList(),
        episodeNumbers =
          intent.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS) ?: emptyList(),
      )
    }
  }
}
