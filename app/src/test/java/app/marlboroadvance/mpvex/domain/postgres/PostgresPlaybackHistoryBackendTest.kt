package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.database.repository.PostgresPlaybackHistoryRepository
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Fake JDBC only: these tests never open a socket, read real credentials, or run server migrations. */
class PostgresPlaybackHistoryBackendTest {
  @Test fun `page uses fixed fields bound filters long count seconds conversion and readonly rollback`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    jdbc.total = 5_000_000_000L
    val properties = mutableListOf<Pair<String, String>>()
    val executor = JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, props ->
      properties += props.getProperty("readOnly") to props.getProperty("readOnlyMode"); jdbc.connection
    })
    val repository = PostgresPlaybackHistoryRepository(executor, HistoryNoopWriter())
    val filters = PostgresPlaybackHistoryFilters("server-uuid", "a' OR 1=1 --%", "episode_2")
    val page = repository.loadPage(filters, Int.MAX_VALUE)
    assertEquals(5_000_000_000L, page.total)
    assertEquals(2147483647000L, page.rows.single().playedDurationMs)
    assertEquals(1234L, page.rows.single().createTimeMs)
    assertNull(page.rows.single().updateTimeMs)
    assertEquals(storage.token, page.rows.single().source)
    assertEquals(listOf("true" to "always"), properties)
    assertTrue(jdbc.readOnly)
    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, jdbc.isolation)
    assertFalse(jdbc.autoCommit)
    assertEquals(0, jdbc.commits)
    assertEquals(1, jdbc.rollbacks)
    assertEquals(2, jdbc.calls.size)
    assertTrue(jdbc.calls.all { it.first.startsWith("SELECT ") && !it.first.contains("SELECT *") && !it.first.contains("a'") })
    assertTrue(jdbc.calls.last().first.endsWith("ORDER BY pinned DESC, update_time DESC, id DESC LIMIT ? OFFSET ?"))
    assertEquals(listOf("server-uuid", "%a' OR 1=1 --%%", "%episode_2%", 30, 166666666L * 30), jdbc.calls.last().second)
    assertEquals(166666667, page.pageNumber)
    assertEquals(5, jdbc.timeout)
  }

  @Test fun `only approved pinned cycle is written with optimistic row server and old pin predicate`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, props ->
      assertEquals("false", props.getProperty("readOnly"))
      assertEquals("always", props.getProperty("readOnlyMode"))
      jdbc.connection
    })
    for ((index, transition) in listOf(0 to 1, 1 to -1, -1 to 0).withIndex()) {
      val (old, next) = transition
      val returnedUpdateTimeMs = 7_000L + index
      jdbc.markUpdateTimeMs = returnedUpdateTimeMs
      val row = jdbc.historyRow(storage.token).copy(pinned = old)
      assertEquals(
        PostgresPlaybackHistoryMarkResult.Committed(next, returnedUpdateTimeMs),
        writer.cycleMark(row),
      )
      writer.awaitSettlement(row)
      assertEquals(listOf(next, row.id, row.serverUuid, old), jdbc.calls.last().second)
      assertEquals(returnedUpdateTimeMs, (jdbc.values["update_time"] as Timestamp).time)
    }
    assertEquals(3, jdbc.commits)
    assertEquals(0, jdbc.rollbacks)
    assertEquals(3, jdbc.markQueryCalls)
    assertFalse(jdbc.readOnly)
    assertTrue(jdbc.calls.all {
      it.first == "UPDATE play_history SET update_time = clock_timestamp(), pinned = ? " +
        "WHERE id = ? AND emby_server_id = ? AND pinned = ? RETURNING update_time"
    })
    assertEquals(5, jdbc.timeout)
    // Narrow write opening cannot change the factory's normal open behavior.
    assertEquals("true", PostgresConnectionFactory.connectionProperties(storage.config).getProperty("readOnly"))
  }

  @Test fun `zero and excess returned rows conflict roll back and never commit`() = runBlocking {
    for (affected in listOf(0, 2)) {
      val storage = HistoryTestStorage()
      val jdbc = HistoryJdbcFixture().apply { this.affected = affected }
      val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })
      assertEquals(PostgresPlaybackHistoryMarkResult.Conflict, writer.cycleMark(jdbc.historyRow(storage.token)))
      assertEquals(1, jdbc.rollbacks)
      assertEquals(0, jdbc.commits)
      assertEquals(1, jdbc.markQueryCalls)
      assertEquals(1, jdbc.calls.size)
    }
  }

  @Test fun `missing returned timestamp is schema incompatible and rolls back without commit`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture().apply { markTimestampMissing = true }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })

    assertEquals(
      PostgresPlaybackHistoryMarkResult.Failed(PostgresFailure.SCHEMA_INCOMPATIBLE),
      writer.cycleMark(jdbc.historyRow(storage.token)),
    )
    assertEquals(1, jdbc.rollbacks)
    assertEquals(0, jdbc.commits)
    assertEquals(1, jdbc.calls.count { it.first.startsWith("UPDATE ") })
  }

  @Test fun `mark schema 42703 is classified without commit or replay`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture().apply { updateQueryState = "42703" }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })
    val row = jdbc.historyRow(storage.token)

    assertEquals(
      PostgresPlaybackHistoryMarkResult.Failed(PostgresFailure.SCHEMA_INCOMPATIBLE),
      writer.cycleMark(row),
    )
    assertEquals(1, jdbc.rollbacks)
    assertEquals(0, jdbc.commits)
    assertEquals(1, jdbc.calls.count { it.first.startsWith("UPDATE ") })
  }

  @Test fun `same key changed revision rejected before connection and during update rolled back`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    var opens = 0
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> opens++; jdbc.connection })
    val old = jdbc.historyRow(storage.token)
    storage.changePassword()
    assertEquals(PostgresPlaybackHistoryMarkResult.SourceChanged, writer.cycleMark(old))
    assertEquals(0, opens)
    val current = jdbc.historyRow(storage.token)
    jdbc.onUpdate = { storage.changePassword() }
    assertEquals(PostgresPlaybackHistoryMarkResult.SourceChanged, writer.cycleMark(current))
    assertEquals(1, jdbc.rollbacks)
    assertEquals(0, jdbc.commits)
  }

  @Test fun `postcommit source change never claims committed success`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture().apply { onCommit = { storage.changePassword() } }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })
    assertEquals(PostgresPlaybackHistoryMarkResult.SourceChanged, writer.cycleMark(jdbc.historyRow(storage.token)))
    assertEquals(1, jdbc.commits)
    assertEquals(0, jdbc.rollbacks)
  }

  @Test fun `commit exception is unknown no rollback or retry and recovery uses only SELECT`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture().apply { commitError = true }
    val factory = PostgresConnectionFactory { _, _ -> jdbc.connection }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, factory)
    val repository = PostgresPlaybackHistoryRepository(JdbcPostgresExecutor(storage, factory), writer)
    val row = jdbc.historyRow(storage.token)
    assertEquals(PostgresPlaybackHistoryMarkResult.CommitUnknown, repository.cycleMark(row))
    assertEquals(1, jdbc.commits)
    assertEquals(0, jdbc.rollbacks)
    val observation = repository.reconcile(row)
    assertEquals(1, observation?.pinned)
    assertEquals(jdbc.markUpdateTimeMs, observation?.updateTimeMs)
    assertEquals(2, jdbc.calls.size)
    assertEquals(1, jdbc.calls.count { it.first.startsWith("UPDATE ") })
    assertTrue(jdbc.calls.last().first.startsWith("SELECT "))
    assertTrue(jdbc.calls.last().first.endsWith("WHERE id = ? AND emby_server_id = ?"))
    assertEquals(listOf(row.id, row.serverUuid), jdbc.calls.last().second)
    assertEquals(1, jdbc.commits)
  }

  @Test fun `repository expected revision guards even executor with key only checks`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    val repository = PostgresPlaybackHistoryRepository(JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, _ -> jdbc.connection }), HistoryNoopWriter())
    val expected = storage.token
    jdbc.onQuery = { storage.changePassword() }
    try { repository.loadPage(expectedSource = expected); fail("stale read must fail") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.SOURCE_CHANGED, error.reason) }
    assertEquals(0, jdbc.commits)
    assertTrue(jdbc.rollbacks > 0)
    jdbc.onQuery = {}
    try { repository.loadPage(expectedSource = expected); fail("old revision must fail before SELECT") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.SOURCE_CHANGED, error.reason) }
  }

  @Test fun `schema missing columns and invalid mark fail without writes`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    val factory = PostgresConnectionFactory { _, _ -> jdbc.connection }
    val repository = PostgresPlaybackHistoryRepository(JdbcPostgresExecutor(storage, factory), HistoryNoopWriter())
    jdbc.queryState = "42703"
    try { repository.loadPage(); fail("Missing required column must fail") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.SCHEMA_INCOMPATIBLE, error.reason) }
    jdbc.queryState = null
    jdbc.values["pinned"] = 2
    try { repository.loadPage(); fail("Unknown pinned value must fail") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.SCHEMA_INCOMPATIBLE, error.reason) }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, factory)
    assertEquals(PostgresPlaybackHistoryMarkResult.Failed(PostgresFailure.SCHEMA_INCOMPATIBLE), writer.cycleMark(jdbc.historyRow(storage.token)))
    assertEquals(0, jdbc.commits)
    assertTrue(jdbc.calls.all { it.first.startsWith("SELECT ") })
  }

  @Test fun `normal read open remains always readonly after same factory handled history mark`() = runBlocking {
    val storage = HistoryTestStorage()
    val jdbc = HistoryJdbcFixture()
    val properties = mutableListOf<Pair<String, String>>()
    val factory = PostgresConnectionFactory { _, props ->
      properties += props.getProperty("readOnly") to props.getProperty("readOnlyMode"); jdbc.connection
    }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, factory)
    val executor = JdbcPostgresExecutor(storage, factory)
    assertEquals(
      PostgresPlaybackHistoryMarkResult.Committed(1, jdbc.markUpdateTimeMs),
      writer.cycleMark(jdbc.historyRow(storage.token)),
    )
    assertEquals("read", executor.read { "read" })
    assertEquals(listOf("false" to "always", "true" to "always"), properties)
    assertTrue(jdbc.readOnly)
    assertEquals(1, jdbc.commits)
    assertEquals(1, jdbc.rollbacks)
  }

  @Test fun `cancel before commit rolls back timestamp and pin after worker settles`() = runBlocking {
    val storage = HistoryTestStorage()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val jdbc = HistoryJdbcFixture().apply { onUpdate = { entered.countDown(); awaitUninterruptibly(release) } }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })
    val row = jdbc.historyRow(storage.token)
    var cancelled = false
    val job = launch(Dispatchers.Default) { try { writer.cycleMark(row) } catch (_: CancellationException) { cancelled = true } }
    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    job.cancel()
    job.join()
    assertTrue(cancelled)
    release.countDown()
    writer.awaitSettlement(row)
    assertEquals(0, jdbc.commits)
    assertEquals(1, jdbc.rollbacks)
    assertEquals(0, jdbc.values["pinned"])
    assertNull(jdbc.values["update_time"])
  }

  @Test fun `cancel during commit reports unknown recovery waits for late worker and does not replay`() = runBlocking {
    val storage = HistoryTestStorage()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val jdbc = HistoryJdbcFixture().apply { onCommit = { entered.countDown(); awaitUninterruptibly(release) } }
    val writer = JdbcPostgresPlaybackHistoryMarkWriter(storage, PostgresConnectionFactory { _, _ -> jdbc.connection })
    val row = jdbc.historyRow(storage.token)
    var outcome: PostgresPlaybackHistoryMarkResult? = null
    val job = launch(Dispatchers.Default) { outcome = writer.cycleMark(row) }
    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    job.cancel()
    job.join()
    assertEquals(PostgresPlaybackHistoryMarkResult.CommitUnknown, outcome)
    assertEquals(PostgresPlaybackHistoryMarkResult.CommitUnknown, writer.cycleMark(row))
    release.countDown()
    writer.awaitSettlement(row)
    assertEquals(1, jdbc.commits)
    assertEquals(0, jdbc.rollbacks)
    assertEquals(1, jdbc.values["pinned"])
    assertEquals(jdbc.markUpdateTimeMs, (jdbc.values["update_time"] as Timestamp).time)
    assertEquals(1, jdbc.calls.size)
  }
}

internal class HistoryTestStorage : PostgresConfigStorage {
  var config = PostgresConfig("fake.example", database = "fake", username = "fake", password = "not-a-real-secret")
  override val sourceChanges = kotlinx.coroutines.flow.MutableStateFlow(PostgresSourceState(0L, config.sourceKey()))
  val token get() = PostgresSourceToken(sourceChanges.value.sourceKey, sourceChanges.value.revision)
  override suspend fun load() = config
  override suspend fun save(config: PostgresConfig) { this.config = config; sourceChanges.value = PostgresSourceState(sourceChanges.value.revision + 1, config.sourceKey()) }
  fun changePassword() { config = config.copy(password = "changed"); sourceChanges.value = PostgresSourceState(sourceChanges.value.revision + 1, config.sourceKey()) }
}

internal class HistoryNoopWriter : PostgresPlaybackHistoryMarkWriter {
  override suspend fun cycleMark(row: PostgresPlaybackHistoryRow) = PostgresPlaybackHistoryMarkResult.Committed(1, 2_000L)
}

internal class HistoryJdbcFixture {
  private data class PendingMark(
    val row: MutableMap<String, Any?>,
    val pinned: Int,
    val updateTime: Timestamp?,
  )

  val values = linkedMapOf<String, Any?>("id" to "history-uuid", "emby_server_id" to "server-uuid", "emby_server_name" to "Desktop name",
    "item_type" to "Episode", "item_id" to "item-123", "item_name" to "Episode 2", "series_id" to "series-456", "series_name" to "Series",
    "create_time" to Timestamp(1234L), "update_time" to null, "played_duration" to Int.MAX_VALUE.toLong(), "pinned" to 0)
  val rows = mutableListOf<MutableMap<String, Any?>>(values)
  var total = 1L
  var affected = 1
  var markUpdateTimeMs = 2_000L
  var markTimestampMissing = false
  var markQueryCalls = 0
  var commits = 0
  var rollbacks = 0
  var timeout = 0
  var autoCommit = true
  var readOnly = false
  var isolation = 0
  var commitError = false
  var queryError = false
  var queryState: String? = null
  var updateQueryState: String? = null
  var missingRow = false
  var onUpdate: () -> Unit = {}
  var onQuery: () -> Unit = {}
  var onCommit: () -> Unit = {}
  private var pendingMark: PendingMark? = null
  val calls = mutableListOf<Pair<String, List<Any?>>>()
  val connection = historyProxy(Connection::class.java) { name, args -> when (name) {
    "prepareStatement" -> prepared(args!![0] as String)
    "setAutoCommit" -> { autoCommit = args!![0] as Boolean; null }
    "getAutoCommit" -> autoCommit
    "setReadOnly" -> { readOnly = args!![0] as Boolean; null }
    "setTransactionIsolation" -> { isolation = args!![0] as Int; null }
    "commit" -> {
      commits++
      onCommit()
      pendingMark?.let { mark ->
        mark.row["pinned"] = mark.pinned
        mark.row["update_time"] = mark.updateTime
      }
      pendingMark = null
      if (commitError) throw SQLException("driver detail must not escape", "08006")
      null
    }
    "rollback" -> { rollbacks++; pendingMark = null; null }
    "isClosed" -> false
    "close" -> null
    else -> null
  } }
  fun historyRow(source: PostgresSourceToken) = PostgresPlaybackHistoryRow(source, "history-uuid", "server-uuid", "Desktop name", "Episode", "item-123", "Episode 2",
    "series-456", "Series", 1234L, null, 2147483647000L, values["pinned"] as Int)

  private fun prepared(sql: String): PreparedStatement {
    val bound = sortedMapOf<Int, Any?>()
    fun returnedMarkRows(): List<Map<String, Any?>> {
      updateQueryState?.let { throw SQLException("private details", it) }
      val matched = rows.filter {
        it["id"] == bound[2] && it["emby_server_id"] == bound[3] && it["pinned"] == bound[4]
      }
      onUpdate()
      if (affected != 1) {
        return List(affected.coerceAtLeast(0)) { mapOf("update_time" to Timestamp(markUpdateTimeMs)) }
      }
      if (matched.size != 1) return emptyList()
      val returnedTimestamp = if (markTimestampMissing) null else Timestamp(markUpdateTimeMs)
      pendingMark = PendingMark(matched.single(), bound[1] as Int, returnedTimestamp)
      return listOf(mapOf("update_time" to returnedTimestamp))
    }
    fun selectedRows(): List<Map<String, Any?>> {
      if (queryError || queryState != null) throw SQLException("private details", queryState ?: "08001")
      if (sql.contains("count(*)")) return listOf(mapOf("total" to total))
      if (missingRow) return emptyList()
      if (sql.endsWith("WHERE id = ? AND emby_server_id = ?")) {
        return rows.filter { it["id"] == bound[1] && it["emby_server_id"] == bound[2] }.map { it.toMap() }
      }
      return rows.map { it.toMap() }
    }
    return historyProxy(PreparedStatement::class.java) { name, args -> when (name) {
      "setObject", "setString", "setInt", "setLong" -> { bound[args!![0] as Int] = args[1]; null }
      "setQueryTimeout" -> { timeout = args!![0] as Int; null }
      "executeUpdate" -> error("History mark SQL must use executeQuery for RETURNING")
      "executeQuery" -> {
        calls += sql to bound.values.toList(); onQuery()
        val selected = if (sql.startsWith("UPDATE play_history SET update_time = clock_timestamp(), pinned = ?")) {
          markQueryCalls++
          returnedMarkRows()
        } else selectedRows()
        results(selected)
      }
      "close", "cancel" -> null
      else -> null
    } }
  }
  private fun results(rows: List<Map<String, Any?>>): ResultSet {
    var index = -1
    var wasNull = false
    return historyProxy(ResultSet::class.java) { name, args -> when (name) {
      "next" -> { index++; index < rows.size }
      "wasNull" -> wasNull
      "getString", "getLong", "getInt", "getTimestamp" -> {
        val value = rows[index][args!![0] as String]
        wasNull = value == null
        when (name) { "getLong" -> (value as? Number)?.toLong() ?: 0L; "getInt" -> (value as? Number)?.toInt() ?: 0; else -> value }
      }
      "close" -> null
      else -> null
    } }
  }
}

private fun awaitUninterruptibly(latch: CountDownLatch) {
  var interrupted = false
  while (true) {
    try { check(latch.await(5, TimeUnit.SECONDS)); break } catch (_: InterruptedException) { interrupted = true }
  }
  if (interrupted) Thread.currentThread().interrupt()
}

private fun <T> historyProxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
  type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args) })
