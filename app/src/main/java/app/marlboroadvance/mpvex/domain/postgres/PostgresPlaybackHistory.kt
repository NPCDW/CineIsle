package app.marlboroadvance.mpvex.domain.postgres

/** Credential-free shared history projection. Desktop played_duration is seconds; UI units are milliseconds. */
data class PostgresPlaybackHistoryRow(
  val source: PostgresSourceToken,
  val id: String,
  val serverUuid: String,
  val serverName: String,
  val itemType: String,
  val itemId: String,
  val itemName: String,
  val seriesId: String?,
  val seriesName: String?,
  val createTimeMs: Long,
  val updateTimeMs: Long?,
  val playedDurationMs: Long,
  val pinned: Int,
) {
  val key: String get() = "${source.sourceKey}/${source.revision}/$serverUuid/$id"
}

data class PostgresPlaybackHistoryFilters(
  val serverUuid: String? = null,
  val seriesName: String = "",
  val itemName: String = "",
)

data class PostgresPlaybackHistoryPage(
  val source: PostgresSourceToken,
  val rows: List<PostgresPlaybackHistoryRow>,
  val total: Long,
  val pageNumber: Int,
) {
  val pageSize: Int get() = POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE
}

const val POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE = 30

/** A cycle is the entire write API: no SQL callback, arbitrary target value/timestamp, or duration write. */
interface PostgresPlaybackHistoryMarkWriter {
  suspend fun cycleMark(row: PostgresPlaybackHistoryRow): PostgresPlaybackHistoryMarkResult
  /** Wait for a cancelled/uncertain JDBC worker to close before a recovery SELECT can permit another action. */
  suspend fun awaitSettlement(row: PostgresPlaybackHistoryRow) = Unit
}

sealed interface PostgresPlaybackHistoryMarkResult {
  data class Committed(val pinned: Int, val updateTimeMs: Long) : PostgresPlaybackHistoryMarkResult
  data object Conflict : PostgresPlaybackHistoryMarkResult
  data object CommitUnknown : PostgresPlaybackHistoryMarkResult
  data object SourceChanged : PostgresPlaybackHistoryMarkResult
  data class Failed(val reason: PostgresFailure) : PostgresPlaybackHistoryMarkResult
}
