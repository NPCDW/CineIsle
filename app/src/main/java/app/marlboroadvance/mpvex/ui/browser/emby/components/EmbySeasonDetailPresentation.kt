package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun isSeasonLibraryEntry(types: List<String>?, folderDive: Boolean, personId: String? = null): Boolean =
  !folderDive && personId == null && types?.size == 1 && types.first().equals("Episode", ignoreCase = true)

internal fun seasonSeriesCandidates(season: EmbyItem): List<String> =
  listOfNotNull(season.SeriesId, season.ParentId)
    .filter { it.isNotBlank() && it != season.Id }.distinct()

internal suspend fun resolveSeasonSeries(season: EmbyItem, load: suspend (String) -> EmbyItem): EmbyItem {
  for (id in seasonSeriesCandidates(season)) {
    currentCoroutineContext().ensureActive()
    val parent = try {
      load(id)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      continue
    }
    if (parent.Type.equals("Series", ignoreCase = true) && !parent.Id.isNullOrBlank()) return parent
  }
  error("无法加载所属剧集")
}

internal fun seasonSeriesTitle(season: EmbyItem, series: EmbyItem?): String =
  series?.Name?.trim()?.takeIf { it.isNotEmpty() }
    ?: season.SeriesName?.trim()?.takeIf { it.isNotEmpty() }
    ?: "剧集"

internal fun seasonEpisodeTitle(episode: EmbyItem): String =
  episode.displayTitle().ifEmpty { "未命名" }

internal fun seasonEpisodeDuration(episode: EmbyItem): String? {
  val seconds = episode.RunTimeTicks?.takeIf { it > 0 }?.let { EmbyTicks.ticksToSeconds(it) } ?: return null
  if (seconds <= 0) return null
  val hours = seconds / 3600
  val minutes = seconds % 3600 / 60
  return when {
    hours > 0 -> if (minutes > 0) "${hours}小时${minutes}分钟" else "${hours}小时"
    minutes > 0 -> "${minutes}分钟"
    else -> "${seconds}秒"
  }
}
