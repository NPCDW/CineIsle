package app.marlboroadvance.mpvex.domain.postgres

import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Isolated, typed write path. Never retries, inserts, deletes, updates progress, or exposes a transaction callback. */
class JdbcPostgresPlaybackHistoryMarkWriter(
  private val storage: PostgresConfigStorage,
  private val factory: PostgresConnectionFactory = PostgresConnectionFactory(),
) : PostgresPlaybackHistoryMarkWriter {
  private val settlements = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

  override suspend fun awaitSettlement(row: PostgresPlaybackHistoryRow) {
    val pending = settlements[row.key] ?: return
    if (withTimeoutOrNull(JdbcPostgresIo.TOTAL_TIMEOUT_MS) { pending.await(); true } != true) {
      throw PostgresException(PostgresFailure.COMMIT_UNKNOWN)
    }
    settlements.remove(row.key, pending)
  }

  private fun isCurrent(source: PostgresSourceToken): Boolean {
    val current = storage.sourceChanges.value
    return current.revision == source.revision && (current.sourceKey.isEmpty() || current.sourceKey == source.sourceKey)
  }

  override suspend fun cycleMark(row: PostgresPlaybackHistoryRow): PostgresPlaybackHistoryMarkResult {
    require(row.id.isNotEmpty() && row.serverUuid.isNotEmpty() && row.source.sourceKey.isNotEmpty())
    val next = when (row.pinned) {
      0 -> 1
      1 -> -1
      -1 -> 0
      else -> return PostgresPlaybackHistoryMarkResult.Failed(PostgresFailure.SCHEMA_INCOMPATIBLE)
    }
    // This flag also survives caller cancellation/overall timeout while COMMIT runs on the worker.
    val commitStarted = AtomicBoolean(false)
    val commitBoundary = Any()
    val cancelRequested = AtomicBoolean(false)
    val requiresRecovery = AtomicBoolean(false)
    val workerPhase = AtomicInteger(0) // 0 queued/not dispatched, 1 running, 2 settled/cancelled before dispatch
    val settled = CompletableDeferred<Unit>()
    synchronized(settlements) {
      // No second cycle until an unknown commit is explicitly observed with a fresh read.
      if (settlements.containsKey(row.key)) return PostgresPlaybackHistoryMarkResult.CommitUnknown
      settlements[row.key] = settled
    }
    try {
      if (!isCurrent(row.source)) return PostgresPlaybackHistoryMarkResult.SourceChanged
      val revision = storage.sourceChanges.value.revision
      val config = storage.load() ?: throw PostgresException(PostgresFailure.LOAD)
      val actual = PostgresSourceToken(config.sourceKey(), revision)
      if (actual != row.source || !isCurrent(actual)) return PostgresPlaybackHistoryMarkResult.SourceChanged
      return JdbcPostgresIo.run { control ->
        if (!workerPhase.compareAndSet(0, 1)) throw CancellationException()
        try {
          control.checkCancelled()
          if (!isCurrent(actual)) return@run PostgresPlaybackHistoryMarkResult.SourceChanged
          factory.openHistoryMark(config).use { connection ->
            control.connection.set(connection)
            control.checkCancelled()
            connection.autoCommit = false
            connection.isReadOnly = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
              if (!isCurrent(actual)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
              var returnedRows = 0
              var returnedUpdateTimeMs: Long? = null
              connection.prepareStatement(CYCLE_SQL).use { statement ->
                control.statement.set(statement)
                statement.queryTimeout = JdbcPostgresIo.QUERY_TIMEOUT_SECONDS
                statement.setInt(1, next)
                statement.setString(2, row.id)
                statement.setString(3, row.serverUuid)
                statement.setInt(4, row.pinned)
                statement.executeQuery().use { result ->
                  while (result.next()) {
                    returnedRows++
                    if (returnedRows == 1) {
                      returnedUpdateTimeMs = result.getTimestamp("update_time")?.time
                        ?: throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
                    }
                  }
                }
              }
              control.statement.set(null)
              control.checkCancelled()
              if (!isCurrent(actual)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
              if (returnedRows != 1) {
                connection.rollback()
                return@use PostgresPlaybackHistoryMarkResult.Conflict
              }
              val committedUpdateTimeMs = returnedUpdateTimeMs
                ?: throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
              // Check immediately before commit; cancellation after this boundary is necessarily uncertain.
              synchronized(commitBoundary) {
                control.checkCancelled()
                if (cancelRequested.get()) throw CancellationException()
                if (!isCurrent(actual)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
                commitStarted.set(true)
              }
              connection.commit()
              control.checkCancelled()
              if (!isCurrent(actual)) PostgresPlaybackHistoryMarkResult.SourceChanged
              else PostgresPlaybackHistoryMarkResult.Committed(next, committedUpdateTimeMs)
            } catch (error: Throwable) {
              // Never claim rollback undoes a potentially dispatched commit.
              if (!commitStarted.get()) runCatching { connection.rollback() }
              throw error
            }
          }
        } finally {
          workerPhase.set(2)
          settled.complete(Unit)
          if (cancelRequested.get() && !requiresRecovery.get() && !commitStarted.get()) {
            settlements.remove(row.key, settled)
          }
        }
      }.let { outcome ->
        if (!isCurrent(actual)) PostgresPlaybackHistoryMarkResult.SourceChanged else outcome
      }
    } catch (cancelled: CancellationException) {
      // Cancellation during COMMIT cannot be presented as a clean cancellation or retried write.
      if (synchronized(commitBoundary) { cancelRequested.set(true); commitStarted.get() }) {
        requiresRecovery.set(true)
        return PostgresPlaybackHistoryMarkResult.CommitUnknown
      }
      throw cancelled
    } catch (error: Exception) {
      if (synchronized(commitBoundary) { cancelRequested.set(true); commitStarted.get() }) {
        requiresRecovery.set(true)
        return PostgresPlaybackHistoryMarkResult.CommitUnknown
      }
      val reason = (error as? PostgresException)?.reason ?: JdbcPostgresConnectionChecker.classifyFailure(error)
      return if (reason == PostgresFailure.SOURCE_CHANGED) PostgresPlaybackHistoryMarkResult.SourceChanged
      else PostgresPlaybackHistoryMarkResult.Failed(reason)
    } finally {
      // A queued task cancelled before dispatch must not leave reconciliation waiting forever.
      if (workerPhase.compareAndSet(0, 2)) settled.complete(Unit)
      if (settled.isCompleted && !requiresRecovery.get()) settlements.remove(row.key, settled)
    }
  }

  companion object {
    private const val CYCLE_SQL =
      "UPDATE play_history SET update_time = clock_timestamp(), pinned = ? " +
        "WHERE id = ? AND emby_server_id = ? AND pinned = ? RETURNING update_time"
  }
}
