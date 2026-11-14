package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.net.Uri
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.net.URI

/** The only video input accepted by either player. URLs need not share the Emby API host. */
internal class EmbyPlaybackInput private constructor(
  val handoff: PlayerHandoff,
  val uri: Uri,
  val serverId: Long,
) {
  /** Non-blocking lookup followed by source, credentials and supported-connection validation. */
  fun resolveServer(repository: EmbyRepository): EmbyServer? = runCatching {
    repository.sessionSnapshotById(serverId)?.let(repository::sessionServer)
  }.getOrNull()

  companion object {
    private val legacyInputs = setOf(
      "file_path", "uri", "gsy_url", "stream", "text", "url", "path",
      Intent.EXTRA_STREAM, Intent.EXTRA_TEXT,
    )

    /** Contentless notification/PiP wake-ups must not replace an active playback intent. */
    fun hasPlaybackContent(intent: Intent): Boolean = runCatching {
      intent.data != null || intent.hasExtra(PlayerHandoff.EXTRA_PLAYLIST) ||
        legacyInputs.any(intent::hasExtra)
    }.getOrDefault(true)

    /** Fail closed on malformed extras; metadata is never inferred from a stream URL. */
    fun read(intent: Intent): EmbyPlaybackInput? = runCatching {
      if (intent.action != Intent.ACTION_VIEW || legacyInputs.any(intent::hasExtra)) return null
      val handoff = PlayerHandoff.read(intent)
      val serverId = handoff.embyServerId?.takeIf { it > 0 } ?: return null
      val data = intent.data
      if (data != null && !isPlayableUrl(data.toString())) return null
      val uri = if (handoff.playlist.isNotEmpty()) {
        if (handoff.index !in handoff.playlist.indices ||
          handoff.playlist.size != handoff.embyItemIds.size ||
          handoff.playlist.any { !isPlayableUrl(it.toString()) }
        ) return null
        handoff.playlist[handoff.index].also { if (data != null && data != it) return null }
      } else {
        if (handoff.index != 0 || handoff.embyItemIds.size != 1) return null
        data ?: return null
      }
      if (handoff.embyItemIds.any { it.isBlank() }) return null
      EmbyPlaybackInput(handoff, uri, serverId)
    }.getOrNull()

    /** Strict HTTP(S), including ordinary CDN and loopback, but never known remote ISO. */
    fun isPlayableUrl(url: String): Boolean = runCatching {
      val parsed = URI(url)
      (parsed.scheme.equals("http", ignoreCase = true) ||
        parsed.scheme.equals("https", ignoreCase = true)) &&
        !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
        (parsed.port == -1 || parsed.port in 1..65535) &&
        !EmbyMediaSourcePolicy.isRemoteIsoUrl(url)
    }.getOrDefault(false)
  }
}
