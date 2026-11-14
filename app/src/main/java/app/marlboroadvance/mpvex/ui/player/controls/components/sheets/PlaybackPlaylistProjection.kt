package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef

/** Credential-free metadata retained from a process-local playback capability. */
internal data class PlaylistPlaybackMetadata(
  val itemId: String,
  val title: String,
  val itemName: String,
  val runTimeTicks: Long?,
  val primaryImageItemId: String?,
  val primaryImageTag: String?,
  val backdropImageTag: String?,
  val explicitMediaSourceId: String?,
  val selectPolicy: String,
  val versionSelect: Int,
)

internal fun PlaybackSpec.toPlaylistPlaybackMetadata(): PlaylistPlaybackMetadata = PlaylistPlaybackMetadata(
  itemId = itemId,
  title = title,
  itemName = itemName,
  runTimeTicks = runTimeTicks,
  primaryImageItemId = primaryImageItemId,
  primaryImageTag = primaryImageTag,
  backdropImageTag = backdropImageTag,
  explicitMediaSourceId = explicitMediaSourceId,
  selectPolicy = selectPolicy,
  versionSelect = versionSelect,
)

/** One queue position before optional remote metadata hydration. */
internal data class PlaylistDisplaySeed(
  val queueTitle: String,
  val queueItemId: String?,
  val playback: PlaylistPlaybackMetadata?,
)

internal enum class PlaylistImageType(val embyName: String) {
  Backdrop("Backdrop"),
  Primary("Primary"),
}

/** An Emby image reference contains identifiers only, never a token-bearing URL. */
internal data class PlaylistImageRef(
  val itemId: String,
  val type: PlaylistImageType,
  val tag: String?,
)

/** UI-ready queue row with no capability URL, server object, or credentials. */
internal data class PlaylistDisplayEntry(
  val itemId: String?,
  val title: String,
  val runTimeTicks: Long?,
  val image: PlaylistImageRef?,
  val fallbackImage: PlaylistImageRef?,
)

/**
 * Keeps one result per seed and in seed order. [hydratedItems] can be partial, reordered, or empty.
 */
internal fun projectPlaylistDisplayEntries(
  seeds: List<PlaylistDisplaySeed>,
  hydratedItems: List<EmbyItem>,
): List<PlaylistDisplayEntry> {
  val itemsById = hydratedItems.mapNotNull { item ->
    item.Id?.takeIf(String::isNotBlank)?.let { it to item }
  }.toMap()

  return seeds.map { seed ->
    val playback = seed.playback
    val itemId = playback?.itemId?.takeIf(String::isNotBlank)
      ?: seed.queueItemId?.takeIf(String::isNotBlank)
    val item = itemId?.let(itemsById::get)
    val primary = primaryImage(item, playback)
    val backdrop = backdropImage(item, playback)

    PlaylistDisplayEntry(
      itemId = itemId,
      title = firstNonBlank(seed.queueTitle, playback?.title, playback?.itemName, item?.Name),
      runTimeTicks = selectedSource(item, playback)?.RunTimeTicks.positiveTicks()
        ?: item?.RunTimeTicks.positiveTicks()
        ?: playback?.runTimeTicks.positiveTicks(),
      image = backdrop ?: primary,
      fallbackImage = primary.takeIf { backdrop != null },
    )
  }
}

private fun primaryImage(
  item: EmbyItem?,
  playback: PlaylistPlaybackMetadata?,
): PlaylistImageRef? {
  val hydrated = item?.primaryImageRef()?.let {
    PlaylistImageRef(it.itemId, PlaylistImageType.Primary, it.tag)
  }
  val fromPlayback = playback?.primaryImageItemId?.takeIf(String::isNotBlank)?.let {
    PlaylistImageRef(it, PlaylistImageType.Primary, playback.primaryImageTag)
  }

  // A tagged or borrowed hydrated primary is authoritative. If hydration omitted image fields,
  // retain the richer registration-time primary rather than replacing it with an untagged guess.
  return if (item?.ImageTags?.containsKey("Primary") == true || !item?.PrimaryImageItemId.isNullOrBlank()) {
    hydrated
  } else {
    fromPlayback ?: hydrated
  }
}

private fun backdropImage(
  item: EmbyItem?,
  playback: PlaylistPlaybackMetadata?,
): PlaylistImageRef? {
  val hydratedTag = item?.BackdropImageTags?.firstOrNull()?.takeIf(String::isNotBlank)
  if (hydratedTag != null) {
    val owner = item.Id?.takeIf(String::isNotBlank)
    if (owner != null) return PlaylistImageRef(owner, PlaylistImageType.Backdrop, hydratedTag)
  }
  return playback?.backdropImageTag?.takeIf(String::isNotBlank)?.let { tag ->
    playback.itemId.takeIf(String::isNotBlank)?.let { owner ->
      PlaylistImageRef(owner, PlaylistImageType.Backdrop, tag)
    }
  }
}

private fun selectedSource(
  item: EmbyItem?,
  playback: PlaylistPlaybackMetadata?,
): EmbyMediaSource? {
  val sources = item?.MediaSources.orEmpty()
  if (sources.isEmpty()) return null
  val explicitId = playback?.explicitMediaSourceId?.takeIf(String::isNotBlank)
  if (explicitId != null) return sources.firstOrNull { it.Id == explicitId }
  if (playback?.selectPolicy == "manual") return sources.getOrNull(playback.versionSelect - 1)
  return sources.firstOrNull()
}

private fun Long?.positiveTicks(): Long? = this?.takeIf { it > 0L }

private fun firstNonBlank(vararg values: String?): String =
  values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
