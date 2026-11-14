package app.marlboroadvance.mpvex.ui.player

/** Keeps the app-owned Emby queue and libmpv's native playlist in the same order. */
internal class NativeMpvPlaylistController(
  private val transport: LoembyMpvTransport,
) {
  var entries: List<String> = emptyList()
    private set

  fun replace(
    urls: List<String>,
    startIndex: Int,
    prefetch: Boolean,
  ) {
    require(urls.isNotEmpty()) { "Native playlist cannot be empty" }
    require(startIndex in urls.indices) { "Invalid native playlist start index" }

    // stop clears the entire native playlist; playlist-clear would retain the
    // current entry and leave a stale duplicate after the insert-at loop.
    transport.setString("prefetch-playlist", "no")
    transport.command("stop")
    urls.forEachIndexed { index, url ->
      transport.command("loadfile", url, "insert-at", index.toString())
    }
    entries = urls.toList()
    transport.setString("prefetch-playlist", if (prefetch) "yes" else "no")
    transport.command("playlist-play-index", startIndex.toString())
  }

  fun play(index: Int) {
    require(index in entries.indices) { "Invalid native playlist index" }
    transport.command("playlist-play-index", index.toString())
  }

  /** Replace one entry without discarding the surrounding queue. */
  fun replaceCurrent(
    index: Int,
    url: String,
  ) {
    require(index in entries.indices) { "Invalid native playlist index" }
    transport.command("loadfile", url, "insert-at", index.toString())
    transport.command("playlist-remove", (index + 1).toString())
    transport.command("playlist-play-index", index.toString())
    entries = entries.toMutableList().also { it[index] = url }
  }

  fun reload(index: Int) {
    require(index in entries.indices) { "Invalid native playlist index" }
    transport.command("playlist-play-index", index.toString())
  }

  fun clear() {
    entries = emptyList()
  }
}

internal fun sharedPrefetchPlaylistEnabled(configuration: app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration): Boolean =
  configuration.prefetchPlaylist
