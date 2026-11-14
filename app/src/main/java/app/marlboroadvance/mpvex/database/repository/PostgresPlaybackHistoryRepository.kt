package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.domain.postgres.PostgresExecutor
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryFilters
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkResult
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkWriter
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryPage
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryRow
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryStore
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken

/** Directly injectable, no Room/Emby/session dependencies and no automatic network requests. */
class PostgresPlaybackHistoryRepository(
  private val executor: PostgresExecutor,
  private val writer: PostgresPlaybackHistoryMarkWriter,
) {
  val sourceChanges get() = executor.sourceChanges
  suspend fun currentSource(): PostgresSourceToken = executor.currentSource()
  fun isCurrent(source: PostgresSourceToken): Boolean = executor.isCurrent(source)

  suspend fun loadPage(
    filters: PostgresPlaybackHistoryFilters = PostgresPlaybackHistoryFilters(),
    pageNumber: Int = 1,
    expectedSource: PostgresSourceToken? = null,
  ): PostgresPlaybackHistoryPage {
    val expected = expectedSource ?: currentSource()
    checkSource(expected)
    val result = executor.read(expected.sourceKey) { session ->
      if (session.source != expected) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      checkSource(expected)
      PostgresPlaybackHistoryStore(session.connection, session::track).page(expected, filters, pageNumber).also { checkSource(expected) }
    }
    checkSource(expected)
    return result
  }

  suspend fun cycleMark(row: PostgresPlaybackHistoryRow): PostgresPlaybackHistoryMarkResult {
    if (!isCurrent(row.source)) return PostgresPlaybackHistoryMarkResult.SourceChanged
    val result = writer.cycleMark(row)
    return if (!isCurrent(row.source)) PostgresPlaybackHistoryMarkResult.SourceChanged else result
  }

  /** Always SELECT-only. Conflicts and uncertain commits are observed, never replayed. */
  suspend fun reconcile(row: PostgresPlaybackHistoryRow): PostgresPlaybackHistoryRow? {
    checkSource(row.source)
    writer.awaitSettlement(row)
    checkSource(row.source)
    val result = executor.read(row.source.sourceKey) { session ->
      if (session.source != row.source) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      checkSource(row.source)
      PostgresPlaybackHistoryStore(session.connection, session::track)
        .byId(row.source, row.id, row.serverUuid).also { checkSource(row.source) }
    }
    checkSource(row.source)
    return result
  }

  private fun checkSource(source: PostgresSourceToken) {
    if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
  }
}
