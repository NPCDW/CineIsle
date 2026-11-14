package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.playbackHistoryTitle
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The only automatic history write capability. No SQL/callback escape hatch and no credential writes. */
class PostgresAutomaticPlaybackWriter(
  private val storage: PostgresConfigStorage,
  private val factory: PostgresConnectionFactory = PostgresConnectionFactory(),
) {
  private val mutex = Mutex()
  private val workerActive = AtomicBoolean(false)
  // Constant-size guard: an uncertain COMMIT blocks further writes to that source for this instance's lifetime.
  // A new configuration generation may proceed, but a timed-out late worker must first settle.
  private val uncertainSource = AtomicReference<PostgresSourceToken?>()

  suspend fun started(server: EmbyServer, item: EmbyItem): Unit = write(server, item, null)
  suspend fun stopped(server: EmbyServer, item: EmbyItem, playedDurationSeconds: Long): Unit {
    require(playedDurationSeconds >= 0)
    write(server, item, playedDurationSeconds)
  }

  private fun isCurrent(source: PostgresSourceToken): Boolean {
    val current = storage.sourceChanges.value
    return source.revision == current.revision && (current.sourceKey.isEmpty() || current.sourceKey == source.sourceKey)
  }

  private suspend fun write(server: EmbyServer, item: EmbyItem, seconds: Long?) = mutex.withLock {
    val title = item.playbackHistoryTitle()
    val source = PostgresSourceToken(server.sourceKey, server.sourceRevision)
    require(server.remoteId.isNotBlank() && source.sourceKey.isNotBlank() && !item.Id.isNullOrBlank())
    if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    if (uncertainSource.get() == source) throw PostgresException(PostgresFailure.COMMIT_UNKNOWN)
    if (!workerActive.compareAndSet(false, true)) throw PostgresException(PostgresFailure.TIMEOUT)
    val phase = AtomicInteger(0)
    val commitStarted = AtomicBoolean(false)
    val cancelRequested = AtomicBoolean(false)
    val boundary = Any()
    try {
      val revision = storage.sourceChanges.value.revision
      val config = storage.load() ?: throw PostgresException(PostgresFailure.LOAD)
      if (PostgresSourceToken(config.sourceKey(), revision) != source || !isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      JdbcPostgresIo.run { control ->
        if (!phase.compareAndSet(0, 1)) throw CancellationException()
        try {
          control.checkCancelled()
          if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
          factory.openAutomaticPlayback(config).use { connection ->
            control.connection.set(connection)
            control.checkCancelled()
            connection.autoCommit = false
            connection.isReadOnly = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
              fun statement(sql: String): PreparedStatement {
                control.checkCancelled()
                if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
                return connection.prepareStatement(sql).also { control.statement.set(it); it.queryTimeout = JdbcPostgresIo.QUERY_TIMEOUT_SECONDS }
              }
              // A real server row lock serializes start/stop and concurrent Android writers without schema changes.
              statement("SELECT id FROM emby_server WHERE id = ? FOR UPDATE").use { st ->
                st.setString(1, server.remoteId)
                st.executeQuery().use { if (!it.next()) throw PostgresException(PostgresFailure.NOT_FOUND) }
              }
              var rowId: String? = null
              var oldPinned = 0
              statement("SELECT id, pinned FROM play_history WHERE emby_server_id = ? AND item_id = ? FOR UPDATE").use { st ->
                st.setString(1, server.remoteId); st.setString(2, item.Id)
                st.executeQuery().use { rows ->
                  if (rows.next()) {
                    rowId = rows.getString("id") ?: throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
                    oldPinned = rows.getInt("pinned")
                    if (oldPinned !in setOf(-1, 0, 1) || rows.next()) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
                  }
                }
              }
              var pinned = oldPinned
              if (seconds == null && !item.SeriesId.isNullOrBlank()) {
                statement("UPDATE play_history SET pinned = 0 WHERE pinned = 1 AND emby_server_id = ? AND series_id = ?").use { st ->
                  st.setString(1, server.remoteId); st.setString(2, item.SeriesId)
                  pinned = if (st.executeUpdate() > 0) 1 else 0
                }
              }
              if (rowId == null) {
                if (seconds != null) throw PostgresException(PostgresFailure.NOT_FOUND)
                statement(INSERT_SQL).use { st ->
                  st.setString(1, UUID.randomUUID().toString()); st.setString(2, server.remoteId)
                  bindMetadata(st, 3, server, item, title)
                  st.setInt(8, pinned); st.setString(9, item.Id)
                  if (st.executeUpdate() != 1) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
                }
              } else {
                statement(if (seconds == null) START_SQL else STOP_SQL).use { st ->
                  bindMetadata(st, 1, server, item, title)
                  if (seconds == null) st.setInt(6, pinned) else st.setLong(6, seconds)
                  st.setString(7, rowId); st.setString(8, server.remoteId); st.setString(9, item.Id)
                  if (st.executeUpdate() != 1) throw PostgresException(PostgresFailure.NOT_FOUND)
                }
              }
              if (seconds != null && seconds > 300) {
                statement("UPDATE emby_server SET last_playback_time = clock_timestamp() WHERE id = ?").use { st ->
                  st.setString(1, server.remoteId)
                  if (st.executeUpdate() != 1) throw PostgresException(PostgresFailure.NOT_FOUND)
                }
              }
              control.statement.set(null)
              synchronized(boundary) {
                control.checkCancelled()
                if (cancelRequested.get()) throw CancellationException()
                if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
                commitStarted.set(true)
              }
              connection.commit()
              control.checkCancelled()
              if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
            } catch (error: Throwable) {
              if (!commitStarted.get()) runCatching { connection.rollback() }
              throw error
            }
          }
        } finally {
          phase.set(2)
          workerActive.set(false)
        }
      }
    } catch (error: Throwable) {
      val unknown = synchronized(boundary) { cancelRequested.set(true); commitStarted.get() }
      if (unknown) {
        uncertainSource.set(source)
        throw PostgresException(PostgresFailure.COMMIT_UNKNOWN)
      }
      if (error is CancellationException) throw error
      throw if (error is PostgresException) error else PostgresException(JdbcPostgresConnectionChecker.classifyFailure(error))
    } finally {
      if (phase.compareAndSet(0, 2)) workerActive.set(false)
    }
  }

  private fun bindMetadata(st: PreparedStatement, offset: Int, server: EmbyServer, item: EmbyItem, title: String) {
    st.setString(offset, server.serverName)
    st.setString(offset + 1, item.Type)
    st.setString(offset + 2, title)
    st.setString(offset + 3, item.SeriesId)
    st.setString(offset + 4, item.SeriesName)
  }

  companion object {
    private const val INSERT_SQL = "INSERT INTO play_history (id, create_time, update_time, emby_server_id, emby_server_name, item_type, item_name, series_id, series_name, pinned, item_id, played_duration) VALUES (?, clock_timestamp(), clock_timestamp(), ?, ?, ?, ?, ?, ?, ?, ?, 0)"
    private const val START_SQL = "UPDATE play_history SET update_time = clock_timestamp(), emby_server_name = ?, item_type = ?, item_name = ?, series_id = ?, series_name = ?, pinned = ? WHERE id = ? AND emby_server_id = ? AND item_id = ?"
    private const val STOP_SQL = "UPDATE play_history SET update_time = clock_timestamp(), emby_server_name = ?, item_type = ?, item_name = ?, series_id = ?, series_name = ?, played_duration = COALESCE(played_duration, 0) + ? WHERE id = ? AND emby_server_id = ? AND item_id = ?"
  }
}
