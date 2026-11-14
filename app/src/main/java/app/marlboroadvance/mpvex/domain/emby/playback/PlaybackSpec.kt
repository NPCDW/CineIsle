package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import kotlinx.serialization.Serializable

/** In-memory registration; credentials are never put in Intent/SavedState or in the local URL. */
data class PlaybackSpec(
  val server: EmbyServer,
  val itemId: String,
  val seriesId: String? = null,
  val itemName: String = "",
  val seriesName: String? = null,
  val title: String = "",
  val itemType: String? = null,
  val seasonNumber: Int? = null,
  val episodeNumber: Int? = null,
  val runTimeTicks: Long? = null,
  val primaryImageItemId: String? = null,
  val primaryImageTag: String? = null,
  val backdropImageTag: String? = null,
  val positionTicks: Long = 0,
  val useDirectLink: Boolean = true,
  val selectPolicy: String = "auto",
  val versionSelect: Int = 1,
  val videoSelect: Int = 0,
  val audioSelect: Int = 0,
  val subtitleSelect: Int = 0,
  val playlistIndex: Int = 1,
  val playlistTotal: Int = 1,
  val explicitMediaSourceId: String? = null,
  val overrideUrl: String? = null,
) {
  /** Isolate the four manual choices to the clicked item; old callers retain queue-wide choices. */
  fun forPlaylistEntry(isCurrent: Boolean, selectionCurrentItemOnly: Boolean): PlaybackSpec {
    if (!selectionCurrentItemOnly) return this
    val manualCurrent = isCurrent && selectPolicy == "manual"
    return copy(
      selectPolicy = if (manualCurrent) "manual" else "auto",
      versionSelect = if (manualCurrent) versionSelect else 1,
      videoSelect = if (manualCurrent) videoSelect else 0,
      audioSelect = if (manualCurrent) audioSelect else 0,
      subtitleSelect = if (manualCurrent) subtitleSelect else 0,
      explicitMediaSourceId = if (manualCurrent) explicitMediaSourceId else null,
      overrideUrl = null,
    )
  }

  override fun toString(): String = "PlaybackSpec(itemId=$itemId, playlist=$playlistIndex/$playlistTotal, server=[redacted])"
}

@Serializable
data class LoopbackPlaybackInfo(
  val MediaSources: List<EmbyMediaSource> = emptyList(),
  val PlaySessionId: String = "",
  val ErrorCode: String? = null,
)

data class ResolvedPlayback(
  val id: String,
  val mediaSourceSelect: Int,
  val spec: PlaybackSpec,
  val source: EmbyMediaSource,
  val info: LoopbackPlaybackInfo,
  val sourceIndex: Int,
  val videoUrl: String,
  val config: PlaybackConfiguration,
)
