package app.marlboroadvance.mpvex.domain.emby

private const val PLAYBACK_HISTORY_TITLE_LIMIT = 255
private val EPISODE_TITLE_PREFIX = Regex("^S(-?\\d+)E(-?\\d+)(?:\\.\\s*(.*))?$", RegexOption.IGNORE_CASE)

/** Full episode title shared by playback, history, and full-title UI surfaces. */
fun EmbyItem.displayTitle(): String {
  val name = Name?.trim().orEmpty()
  if (!Type.equals("Episode", ignoreCase = true)) return name
  val season = ParentIndexNumber?.takeIf { it >= 0 } ?: return name
  val episode = IndexNumber?.takeIf { it >= 0 } ?: return name
  val marker = "S${season}E${episode}"
  val prefixed = EPISODE_TITLE_PREFIX.matchEntire(name)
  if (prefixed != null && prefixed.groupValues[1].toIntOrNull() == season &&
    prefixed.groupValues[2].toIntOrNull() == episode) {
    val suffix = prefixed.groupValues[3].trim()
    return if (suffix.isEmpty()) marker else "$marker. $suffix"
  }
  return if (name.isEmpty()) marker else "$marker. $name"
}

/** PostgreSQL play_history.item_name is VARCHAR(255); avoid splitting a Unicode code point. */
fun EmbyItem.playbackHistoryTitle(): String {
  val title = displayTitle()
  if (title.codePointCount(0, title.length) <= PLAYBACK_HISTORY_TITLE_LIMIT) return title
  return title.substring(0, title.offsetByCodePoints(0, PLAYBACK_HISTORY_TITLE_LIMIT))
}
