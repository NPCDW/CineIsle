package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem

internal enum class FavoriteMediaFilter(val label: String, val includeItemTypes: List<String>) {
  ALL("全部", listOf("Movie", "Series", "Season", "Episode", "Video")),
  MOVIE("电影", listOf("Movie")),
  SERIES("电视剧", listOf("Series"));

  fun accepts(item: EmbyItem): Boolean = includeItemTypes.any { it.equals(item.Type, ignoreCase = true) }
}
