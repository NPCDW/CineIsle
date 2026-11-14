package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.formatEmbyDurationFull
import app.marlboroadvance.mpvex.domain.emby.playback.LoembyMediaSelection
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class EpisodeSelection(val version: Int, val video: Int, val audio: Int, val subtitle: Int)
internal data class EpisodeTrackOption(val label: String, val value: Int, val supportingText: String? = null)
internal data class EpisodeParents(val series: EmbyItem, val seasonId: String)

internal fun initialEpisodeSelection(sources: List<EmbyMediaSource>, server: EmbyServer, policy: String): EpisodeSelection {
  val index = LoembyMediaSelection.selectSourceIndex(sources, PlaybackSpec(server, ""), autoPolicy = policy)
  return changeEpisodeVersion(sources, server, index + 1)
}

internal fun changeEpisodeVersion(sources: List<EmbyMediaSource>, server: EmbyServer, version: Int): EpisodeSelection {
  val tracks = LoembyMediaSelection.selectTracks(sources[version - 1], PlaybackSpec(server, ""))
  return EpisodeSelection(version, tracks.video, tracks.audio, tracks.subtitle)
}

internal fun episodeSelectionPolicy(current: EpisodeSelection, baseline: EpisodeSelection): String =
  if (current == baseline) "auto" else "manual"

internal fun episodeVersionOption(source: EmbyMediaSource, index: Int): EpisodeTrackOption {
  val presentation = LoembyMediaSelection.versionPresentation(source, index)
  return EpisodeTrackOption(presentation.title, index + 1, presentation.details)
}

internal fun episodeTrackOptions(source: EmbyMediaSource, type: String): List<EpisodeTrackOption> {
  val streams = source.MediaStreams.orEmpty().filter { it.Type == type }
  if (streams.isEmpty()) return listOf(EpisodeTrackOption("自动", 0))
  return streams.mapIndexed { index, stream ->
    val label = stream.DisplayTitle?.takeIf { it.isNotBlank() }
      ?: listOfNotNull(stream.Title, stream.Codec?.uppercase(), stream.DisplayLanguage ?: stream.Language,
        if (type == "Video" && stream.Width != null && stream.Height != null) "${stream.Width}×${stream.Height}" else null)
        .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "轨道 ${index + 1}" }
    EpisodeTrackOption(label + if (stream.IsExternal == true) "（外置）" else "", index + 1)
  } + EpisodeTrackOption("关闭", -1)
}

internal fun episodeSelectorCanChoose(options: List<EpisodeTrackOption>): Boolean =
  options.size > 1

internal fun episodeVersionDuration(source: EmbyMediaSource?): String? =
  source?.RunTimeTicks?.takeIf { it > 0 }?.let(::formatEmbyDurationFull)

internal fun episodeHasResume(item: EmbyItem, source: EmbyMediaSource?): Boolean {
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  val duration = source?.RunTimeTicks?.takeIf { it > 0 } ?: item.RunTimeTicks?.takeIf { it > 0 }
  return position > 0 && (duration == null || position < duration)
}

internal suspend fun locateEpisodeInSeason(
  page: PagedList<EmbyItem>,
  itemId: String?,
  loader: suspend (Int, Int) -> PagedResult<EmbyItem>,
): Int {
  var index = page.items.indexOfFirst { it.Id == itemId }
  var attempts = 0
  while (index < 0 && page.hasMore && page.error == null && attempts < 50) {
    currentCoroutineContext().ensureActive()
    page.loadMore(loader)
    index = page.items.indexOfFirst { it.Id == itemId }
    attempts++
  }
  return index
}

internal suspend fun resolveEpisodeParents(episode: EmbyItem, load: suspend (String) -> EmbyItem): EpisodeParents {
  suspend fun lookup(id: String?): EmbyItem? {
    if (id.isNullOrBlank() || id == episode.Id) return null
    currentCoroutineContext().ensureActive()
    return try { load(id) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
  }
  val season = listOfNotNull(episode.SeasonId, episode.ParentId).distinct().asSequence()
  var foundSeason: EmbyItem? = null
  for (id in season) {
    val parent = lookup(id)
    if (parent?.Type.equals("Season", ignoreCase = true) && !parent?.Id.isNullOrBlank()) {
      foundSeason = parent
      break
    }
  }
  val seriesCandidates = listOfNotNull(episode.SeriesId, foundSeason?.SeriesId, foundSeason?.ParentId).distinct()
  for (id in seriesCandidates) {
    val parent = lookup(id)
    if (parent?.Type.equals("Series", ignoreCase = true) && !parent?.Id.isNullOrBlank()) {
      val seasonId = foundSeason?.Id
      if (seasonId != null) return EpisodeParents(parent, seasonId)
    }
  }
  error("无法加载所属剧集或季")
}
