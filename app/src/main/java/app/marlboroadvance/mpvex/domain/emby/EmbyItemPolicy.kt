package app.marlboroadvance.mpvex.domain.emby

/** Android supports ordinary media browsing/playback, not server playlists or live television. */
object EmbyItemPolicy {
  // TvChannel/Program are BaseItemDto types. LiveTv* are compatibility aliases also used by
  // Emby's unrated-item model. VideoChannel and saved Recording items are not live television.
  val unsupportedTypes: List<String> = listOf(
    "Playlist", "TvChannel", "LiveTvChannel", "Program", "LiveTvProgram",
  )
  private val unsupportedTypeKeys = unsupportedTypes.map { it.lowercase() }.toSet()
  private val unsupportedCollectionTypes = setOf("livetv", "playlists")

  fun isSupported(type: String?): Boolean = type?.trim()?.lowercase() !in unsupportedTypeKeys

  fun isSupported(item: EmbyItem): Boolean =
    isSupported(item.Type) && item.CollectionType?.trim()?.lowercase() !in unsupportedCollectionTypes

  fun filterSupported(items: List<EmbyItem>): List<EmbyItem> = items.filter(::isSupported)

  /** Keep the server's offset space and total intact, even when an entire page is hidden. */
  fun filterSupported(result: EmbyItemsResult): EmbyItemsResult =
    result.copy(Items = filterSupported(result.Items), consumedCount = result.consumedCount)

  /** Single-item reads and playback entry points must reject these items, not just hide buttons. */
  fun requireSupported(item: EmbyItem): EmbyItem {
    require(isSupported(item)) { "此媒体类型不受支持" }
    return item
  }

  /** Merge rather than replace a caller's existing folder/type exclusions. */
  fun queryExclusions(excludeItemTypes: List<String>?): List<String> =
    (excludeItemTypes.orEmpty() + unsupportedTypes).distinctBy { it.trim().lowercase() }
}
