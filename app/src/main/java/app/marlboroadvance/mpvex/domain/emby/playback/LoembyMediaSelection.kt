package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.formatBitrate
import app.marlboroadvance.mpvex.domain.emby.formatFileSize
import java.net.URI

/** The two lines shown for one Emby media version in mpv and detail selectors. */
data class LoembyVersionPresentation(val title: String, val details: String)

/** Direct port of loemby's version/track rules, not Android's old preference/selector policies. */
object LoembyMediaSelection {
  data class TrackSelection(val video: Int, val audio: Int, val subtitle: Int)
  data class TrackTitles(val video: List<String>, val audio: List<String>, val sub: List<String>)

  fun selectSourceIndex(
    sources: List<EmbyMediaSource>, spec: PlaybackSpec, mediaSourceSelect: Int = 0,
    autoPolicy: String = "high-resolution",
  ): Int {
    require(sources.isNotEmpty()) { "Emby Playback Info Error: 没有可用的媒体源" }
    if (mediaSourceSelect != 0) return if (mediaSourceSelect in 1..sources.size) mediaSourceSelect - 1 else 0
    spec.explicitMediaSourceId?.let { id ->
      return sources.indexOfFirst { it.Id == id }.also {
        require(it >= 0) { "所选媒体版本不存在，请重新选择播放版本" }
      }
    }
    if (spec.selectPolicy == "manual" && spec.versionSelect in 1..sources.size) return spec.versionSelect - 1
    // Rust's stable sort keeps response order for equal ranks and unknown policies.
    return when (autoPolicy) {
      "high-bitrate" -> sources.indices.sortedWith(compareByDescending<Int> { sources[it].Size ?: 0 }).first()
      "high-resolution" -> sources.indices.sortedWith(
        compareByDescending<Int> { resolutionLevel(sources[it]) }.thenByDescending { sources[it].Size ?: 0 },
      ).first()
      else -> 0
    }
  }

  fun resolutionLevel(source: EmbyMediaSource): Int {
    val video = source.MediaStreams.orEmpty().firstOrNull { it.Type == "Video" }
    if (video?.Width != null && video.Height != null) {
      val w = video.Width; val h = video.Height
      return when {
        w >= 7680 || h >= 4320 -> 8
        w >= 3840 || h >= 2160 -> 7
        w >= 2560 || h >= 1440 -> 6
        w >= 1920 || h >= 1080 -> 5
        w >= 1280 || h >= 720 -> 4
        w >= 854 || h >= 480 -> 3
        w >= 640 || h >= 360 -> 2
        else -> 1
      }
    }
    val name = "${video?.DisplayTitle.orEmpty()} ${source.Name.orEmpty()}".lowercase()
    return when {
      "8k" in name || "4320p" in name -> 8
      "4k" in name || "2160p" in name -> 7
      "2k" in name || "1440p" in name -> 6
      "1080p" in name -> 5
      "720p" in name -> 4
      "480p" in name -> 3
      "360p" in name -> 2
      "240p" in name -> 1
      else -> 0
    }
  }

  fun resolution(source: EmbyMediaSource): String = when (resolutionLevel(source)) {
    8 -> "8K"; 7 -> "4K"; 6 -> "2K"; 5 -> "1080p"; 4 -> "720p"
    3 -> "480p"; 2 -> "360p"; 1 -> "240p"; else -> "Unknown"
  }

  fun displayTitle(source: EmbyMediaSource): String = source.MediaStreams.orEmpty()
    .firstOrNull { it.Type == "Video" }?.let { "${source.Name.orEmpty()} / ${it.DisplayTitle.orEmpty()}" }
    ?: source.Name.orEmpty()

  fun versionPresentation(source: EmbyMediaSource, index: Int): LoembyVersionPresentation {
    val video = source.MediaStreams.orEmpty().firstOrNull { it.Type == "Video" }
    val title = if (source.Name.isNullOrBlank() && video?.DisplayTitle.isNullOrBlank()) {
      "版本 ${index + 1}"
    } else {
      displayTitle(source)
    }
    val details = listOf(
      resolution(source),
      formatFileSize(source.Size ?: 0L),
      formatBitrate(source.Bitrate ?: 0L),
    ).joinToString(" / ")
    return LoembyVersionPresentation(title, details)
  }

  fun isInternalUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    if (host == "localhost" || host == "[::1]" || host == "::1" || host == "[0:0:0:0:0:0:0:1]") return true
    val bytes = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    if (bytes.size == 4) return bytes[0] == 10 || bytes[0] == 127 ||
      (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168) ||
      (bytes[0] == 169 && bytes[1] == 254)
    return listOf(".local", ".localhost", ".internal", ".intranet", ".lan", ".home", ".corp",
      ".office", ".localdomain", ".test").any(host::endsWith)
  }

  fun supportsDirectLink(source: EmbyMediaSource): Boolean = source.IsRemote == true &&
    source.Path?.let { "://" in it && !isInternalUrl(it) } == true

  fun halfDirectStreamUrl(direct: String): String = "/emby$direct"
  fun halfVideoStreamUrl(itemId: String, source: EmbyMediaSource, playSessionId: String): String =
    "/emby/Videos/$itemId/stream.${source.Container.orEmpty()}?Static=true&mediaSourceId=${source.Id.orEmpty()}&playSessionId=$playSessionId"

  fun halfAudioStreamUrl(itemId: String, source: EmbyMediaSource, stream: EmbyMediaStream): String {
    require(stream.IsExternal == true) { "media_streams audio not external" }
    return "/emby/Audio/${source.ItemId ?: itemId}/stream.${stream.Codec ?: "flac"}?Static=true&AudioStreamIndex=${requireNotNull(stream.Index)}"
  }

  fun halfSubtitleStreamUrl(itemId: String, source: EmbyMediaSource, stream: EmbyMediaStream): String {
    require(stream.IsExternal == true) { "media_streams Subtitles not external" }
    return "/emby/Videos/$itemId/${source.Id.orEmpty()}/Subtitles/${requireNotNull(stream.Index)}/Stream.${stream.Codec ?: "flac"}"
  }

  fun selectedVideoUrl(spec: PlaybackSpec, source: EmbyMediaSource, playSessionId: String): String =
    spec.overrideUrl ?: when {
      spec.useDirectLink && supportsDirectLink(source) -> source.Path!!
      source.DirectStreamUrl != null -> halfDirectStreamUrl(source.DirectStreamUrl)
      else -> halfVideoStreamUrl(spec.itemId, source, playSessionId)
    }

  fun selectTracks(source: EmbyMediaSource, spec: PlaybackSpec): TrackSelection {
    if (spec.selectPolicy == "manual") return TrackSelection(spec.videoSelect, spec.audioSelect, spec.subtitleSelect)
    var vid = 0; var aid = 0; var sid = 0
    var videos = 0; var audios = 0; var subtitles = 0; var maxScore = 0
    for (stream in source.MediaStreams.orEmpty()) when (stream.Type) {
      "Video" -> { videos++; if (stream.IsDefault == true) vid = videos }
      "Audio" -> { audios++; if (stream.IsDefault == true && aid == 0) aid = audios }
      "Subtitle" -> {
        subtitles++
        var score = (if (stream.IsDefault == true) 1 else 0) + (if (stream.IsExternal == true) 2 else 0)
        stream.DisplayLanguage?.let { language ->
          if ("Chinese" in language) { score += 3; if ("Simplified" in language) score++ }
          if ("中" in language) { score += 3; if ("简" in language) score++ }
        }
        if (score > maxScore) { maxScore = score; sid = subtitles }
      }
    }
    return TrackSelection(if (vid == 0 && videos > 0) 1 else vid,
      if (aid == 0 && audios > 0) 1 else aid, if (sid == 0 && subtitles > 0) 1 else sid)
  }

  fun trackProperty(value: Int): String = when (value) { -1 -> "no"; 0 -> "auto"; else -> value.toString() }
  fun trackTitles(source: EmbyMediaSource): TrackTitles {
    val streams = source.MediaStreams.orEmpty()
    return TrackTitles(
      streams.filter { it.Type == "Video" }.map { "${source.Name.orEmpty()} / ${it.DisplayTitle.orEmpty()}" },
      streams.filter { it.Type == "Audio" && it.IsExternal != true }.map { it.DisplayTitle.orEmpty() },
      streams.filter { it.Type == "Subtitle" && it.IsExternal != true }.map { it.DisplayTitle.orEmpty() },
    )
  }
}
