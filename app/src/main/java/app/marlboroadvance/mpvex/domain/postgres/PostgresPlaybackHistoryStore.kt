package app.marlboroadvance.mpvex.domain.postgres

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/** SELECT-only store; count and records are called inside the executor's repeatable-read transaction. */
internal class PostgresPlaybackHistoryStore(
  private val connection: Connection,
  private val track: (Statement) -> Unit,
) {
  fun page(source: PostgresSourceToken, filters: PostgresPlaybackHistoryFilters, pageNumber: Int): PostgresPlaybackHistoryPage {
    require(pageNumber >= 1)
    val clauses = mutableListOf<String>()
    val values = mutableListOf<Any>()
    filters.serverUuid?.takeIf { it.isNotEmpty() }?.let { clauses += "emby_server_id = ?"; values += it }
    if (filters.seriesName.isNotEmpty()) { clauses += "series_name LIKE ?"; values += "%${filters.seriesName}%" }
    if (filters.itemName.isNotEmpty()) { clauses += "item_name LIKE ?"; values += "%${filters.itemName}%" }
    val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
    val total = prepared("SELECT count(*) AS total FROM play_history$where", values) { statement ->
      statement.executeQuery().use { result ->
        if (!result.next()) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
        result.getLong("total")
      }
    }
    val maxPage = ((total - 1L).coerceAtLeast(0L) / POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE) + 1L
    val effectivePage = pageNumber.toLong().coerceAtMost(maxPage).toInt()
    val rows = prepared("SELECT $FIELDS FROM play_history$where ORDER BY pinned DESC, update_time DESC, id DESC LIMIT ? OFFSET ?",
      values + listOf(POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE, (effectivePage.toLong() - 1L) * POSTGRES_PLAYBACK_HISTORY_PAGE_SIZE)) { statement ->
      statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result, source)) } }
    }
    return PostgresPlaybackHistoryPage(source, rows, total, effectivePage)
  }

  fun byId(source: PostgresSourceToken, id: String, serverUuid: String): PostgresPlaybackHistoryRow? =
    prepared("SELECT $FIELDS FROM play_history WHERE id = ? AND emby_server_id = ?", listOf(id, serverUuid)) { statement ->
      statement.executeQuery().use { result -> if (result.next()) map(result, source) else null }
    }

  private fun <T> prepared(sql: String, values: List<Any>, block: (PreparedStatement) -> T): T =
    connection.prepareStatement(sql).use { statement ->
      track(statement)
      statement.queryTimeout = JdbcPostgresIo.QUERY_TIMEOUT_SECONDS
      values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
      block(statement)
    }

  private fun map(result: ResultSet, source: PostgresSourceToken): PostgresPlaybackHistoryRow {
    val pinned = result.getInt("pinned")
    if (result.wasNull() || pinned !in -1..1) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
    val created = result.getTimestamp("create_time")?.time ?: throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
    val duration = result.getLong("played_duration")
    if (result.wasNull()) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
    return PostgresPlaybackHistoryRow(source, required(result, "id"), required(result, "emby_server_id"),
      required(result, "emby_server_name"), required(result, "item_type"), required(result, "item_id"), required(result, "item_name"),
      result.getString("series_id"), result.getString("series_name"), created, result.getTimestamp("update_time")?.time,
      Math.multiplyExact(duration, 1000L), pinned)
  }

  private fun required(result: ResultSet, field: String): String = result.getString(field)
    ?: throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)

  companion object {
    private const val FIELDS = "id, create_time, update_time, emby_server_id, emby_server_name, item_type, item_id, item_name, series_id, series_name, played_duration, pinned"
  }
}
