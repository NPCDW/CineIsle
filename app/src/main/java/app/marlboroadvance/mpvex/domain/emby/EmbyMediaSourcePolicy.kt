package app.marlboroadvance.mpvex.domain.emby

import java.net.URI
import java.net.URLDecoder

/** Offline source-level playback guard. Browsing and unknown/STRM sources remain supported. */
object EmbyMediaSourcePolicy {
  const val ISO_PLAYBACK_ERROR = "不支持远程 ISO 原盘播放，请选择普通视频版本"

  /** Only HTTP(S) path suffixes, not query/fragment text or arbitrary loopback URLs. */
  fun isRemoteIsoUrl(url: String?): Boolean {
    val uri = url?.trim()?.takeIf { it.isNotEmpty() }
      ?.let { runCatching { URI(it.replace(" ", "%20")) }.getOrNull() } ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true) &&
      !uri.scheme.equals("https", ignoreCase = true)
    ) return false
    if (uri.rawAuthority.isNullOrBlank()) return false
    val path = decodePath(uri.rawPath.orEmpty())
    return path.endsWith(".iso", ignoreCase = true) ||
      (uri.host == "127.0.0.1" && OLD_ISO_PROXY_PATH.matches(path))
  }

  fun isIsoSource(source: EmbyMediaSource?): Boolean = source != null &&
    (source.Container?.split(',')?.any { it.trim().removePrefix(".").equals("iso", ignoreCase = true) } == true ||
      isIsoPath(source.Path))

  /**
   * Explicit IDs never fall back to another version. Without an ID, Emby's existing source order
   * defines the default (first source); other versions must not affect that source's decision.
   */
  fun sourceForPlayback(item: EmbyItem, mediaSourceId: String? = null): EmbyMediaSource? =
    if (mediaSourceId != null) item.MediaSources?.firstOrNull { it.Id == mediaSourceId }
    else item.MediaSources?.firstOrNull()

  /**
   * Throws a user-visible [IllegalArgumentException] on known ISO or an invalid explicit selection.
   * Missing metadata is not evidence of ISO; this function never performs network/sector probes.
   */
  fun requirePlayableSource(
    item: EmbyItem,
    mediaSourceId: String? = null,
    url: String? = null,
  ) {
    val source = sourceForPlayback(item, mediaSourceId)
    require(mediaSourceId == null || item.MediaSources.isNullOrEmpty() || source != null) {
      "所选媒体版本不存在，请重新选择播放版本"
    }
    val knownIso = isIsoSource(source) ||
      (mediaSourceId == null && item.MediaSources.isNullOrEmpty() && isIsoPath(item.Path))
    require(!isRemoteIsoUrl(url) && !knownIso) { ISO_PLAYBACK_ERROR }
  }

  /** Validate the requested first item before filtering later entries; never silently advance it. */
  fun filterPlayableQueue(
    items: List<EmbyItem>,
    firstMediaSourceId: String? = null,
    directUrls: Map<String, String> = emptyMap(),
  ): List<EmbyItem> {
    val first = items.firstOrNull() ?: return emptyList()
    requirePlayableSource(first, firstMediaSourceId, first.Id?.let(directUrls::get))
    return listOf(first) + items.drop(1).filter { item ->
      runCatching { requirePlayableSource(item, url = item.Id?.let(directUrls::get)) }.isSuccess
    }
  }

  private fun isIsoPath(path: String?): Boolean {
    val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true)
    ) return isRemoteIsoUrl(value)
    return decodePath(value.substringBefore('?').substringBefore('#'))
      .endsWith(".iso", ignoreCase = true)
  }

  // URLDecoder normally turns '+' into a space, which is wrong for URL/file paths.
  private fun decodePath(path: String): String =
    runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

  private val OLD_ISO_PROXY_PATH = Regex("/iso[0-9]+")
}
