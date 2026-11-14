package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Fake JDBC only. No credentials, network, PostgreSQL process, or migrations. */
class PostgresAutomaticPlaybackWriterTest {
  @Test fun `start inserts metadata title timestamps zero duration and correct pinned using narrow write factory`() = runBlocking {
    val storage = AutomaticStorage()
    val db = AutomaticJdbc()
    val writer = writer(storage, db)
    writer.started(server(storage), episode())
    val row = db.rows.single()
    assertEquals("episode", row["item_id"])
    assertEquals("S1E2. Raw name", row["item_name"])
    assertEquals("Desktop", row["emby_server_name"])
    assertEquals("series", row["series_id"])
    assertEquals(0L, row["played_duration"])
    assertEquals(0, row["pinned"])
    val insert = db.calls.single { it.first.startsWith("INSERT ") }
    assertEquals(9, insert.second.size)
    assertTrue(insert.first.contains("clock_timestamp()"))
    assertEquals(listOf("false" to "always"), db.properties)
    assertFalse(db.readOnly)
    assertEquals(1, db.commits)
    assertEquals(0, db.rollbacks)
    assertEquals("true", PostgresConnectionFactory.connectionProperties(storage.config).getProperty("readOnly"))
    assertTrue(db.calls.first().first.endsWith("FOR UPDATE"))
  }

  @Test fun `series pinned transfer and accumulated duration are transactional and preserve create time`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    db.rows += row("previous", 1, 20)
    db.rows += row("episode", -1, 100)
    val writer = writer(storage, db)
    writer.started(server(storage), episode())
    assertEquals(0, db.rows.first()["pinned"])
    assertEquals(1, db.rows.last()["pinned"])
    assertEquals(100L, db.rows.last()["played_duration"])
    val handoff = db.calls.single { it.first.startsWith("UPDATE play_history SET pinned") }
    assertEquals("UPDATE play_history SET pinned = 0 WHERE pinned = 1 AND emby_server_id = ? AND series_id = ?", handoff.first)
    assertFalse(handoff.first.contains("update_time"))
    writer.stopped(server(storage), episode().copy(Name = "New metadata"), 300)
    assertEquals(400L, db.rows.last()["played_duration"])
    assertEquals(1, db.rows.last()["pinned"])
    assertEquals("S1E2. New metadata", db.rows.last()["item_name"])
    assertEquals(0, db.serverUpdates)
    writer.stopped(server(storage), episode().copy(Name = "New metadata"), 301)
    assertEquals(701L, db.rows.last()["played_duration"])
    assertEquals(1, db.serverUpdates)
    assertEquals("original", db.rows.last()["create_time"])
    assertTrue(db.calls.filter { it.first.startsWith("UPDATE play_history SET update_time") }.all { !it.first.contains("create_time") })
  }

  @Test fun `standalone pin retained and empty series does not transfer`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    db.rows += row("episode", -1, 40).apply { this["series_id"] = null }
    writer(storage, db).started(
      server(storage),
      episode().copy(Name = "Movie", Type = "Movie", SeriesId = null, SeriesName = null),
    )
    assertEquals(-1, db.rows.single()["pinned"])
    assertEquals(40L, db.rows.single()["played_duration"])
    assertFalse(db.calls.any { it.first.startsWith("UPDATE play_history SET pinned") })
  }

  @Test fun `stop concurrent writers cannot lose cumulative seconds`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    db.rows += row("episode", 1, 100)
    val a = writer(storage, db); val b = writer(storage, db)
    coroutineScope {
      launch(Dispatchers.Default) { a.stopped(server(storage), episode(), 31) }
      launch(Dispatchers.Default) { b.stopped(server(storage), episode(), 47) }
    }
    assertEquals(178L, db.rows.single()["played_duration"])
    assertEquals(2, db.commits)
    assertEquals(1, db.rows.single()["pinned"])
  }

  @Test fun `missing stop row and missing server fail without partial commit`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    assertFailure(PostgresFailure.NOT_FOUND) { writer(storage, db).stopped(server(storage), episode(), 301) }
    assertEquals(0, db.commits); assertEquals(0, db.serverUpdates)
    db.serverExists = false
    assertFailure(PostgresFailure.NOT_FOUND) { writer(storage, db).started(server(storage), episode()) }
    assertEquals(0, db.commits)
  }

  @Test fun `missing fields rollback pin transfer and invalid source never opens`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    db.rows += row("previous", 1, 3)
    db.failSqlPrefix = "INSERT"
    assertFailure(PostgresFailure.SCHEMA_INCOMPATIBLE) { writer(storage, db).started(server(storage), episode()) }
    assertEquals(1, db.rows.single()["pinned"])
    assertEquals(0, db.commits)
    val old = server(storage)
    storage.change()
    val opens = db.opens
    assertFailure(PostgresFailure.SOURCE_CHANGED) { writer(storage, db).started(old, episode()) }
    assertEquals(opens, db.opens)
  }

  @Test fun `source changed during write rolls back no commit`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc()
    db.onUpdate = { storage.change() }
    assertFailure(PostgresFailure.SOURCE_CHANGED) { writer(storage, db).started(server(storage), episode()) }
    assertEquals(0, db.commits); assertTrue(db.rows.isEmpty()); assertEquals(1, db.rollbacks)
  }

  @Test fun `unknown commit is never replayed on same source but new revision can proceed`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc(); val writer = writer(storage, db)
    db.commitThrows = true
    assertFailure(PostgresFailure.COMMIT_UNKNOWN) { writer.started(server(storage), episode()) }
    assertEquals(1, db.commits); assertEquals(0, db.rollbacks)
    val opens = db.opens
    assertFailure(PostgresFailure.COMMIT_UNKNOWN) { writer.started(server(storage), episode()) }
    assertFailure(PostgresFailure.COMMIT_UNKNOWN) { writer.stopped(server(storage), episode(), 99) }
    assertEquals(opens, db.opens); assertEquals(1, db.commits)
    db.commitThrows = false; storage.change()
    writer.started(server(storage), episode())
    assertEquals(2, db.commits)
  }

  @Test fun `postcommit source switch reports unknown does not falsely claim rollback`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc(); val writer = writer(storage, db)
    db.onCommit = { storage.change() }
    val old = server(storage)
    assertFailure(PostgresFailure.COMMIT_UNKNOWN) { writer.started(old, episode()) }
    assertEquals(1, db.commits); assertEquals(0, db.rollbacks)
    assertFailure(PostgresFailure.SOURCE_CHANGED) { writer.stopped(old, episode(), 30) }
    assertEquals(1, db.commits)
  }

  @Test fun `negative seconds rejects without opening and rollback before commit cancellation never replays`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc(); val writer = writer(storage, db)
    try { writer.stopped(server(storage), episode(), -1); fail() } catch (_: IllegalArgumentException) { }
    assertEquals(0, db.opens)
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
    db.onUpdate = {
      entered.countDown()
      var interrupted = false
      while (true) {
        try { check(release.await(5, TimeUnit.SECONDS)); break } catch (_: InterruptedException) { interrupted = true }
      }
      exited.countDown()
      if (interrupted) Thread.currentThread().interrupt()
    }
    var cancelled = false
    val job = launch(Dispatchers.Default) {
      try { writer.started(server(storage), episode()) } catch (_: CancellationException) { cancelled = true }
    }
    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    job.cancelAndJoin()
    assertTrue(cancelled); assertEquals(0, db.commits)
    release.countDown()
    withContext(Dispatchers.IO) { assertTrue(exited.await(5, TimeUnit.SECONDS)) }
    // Acquiring the fake row lock waits for the cancelled worker's rollback/settlement.
    withContext(Dispatchers.IO) { db.lock.lock(); db.lock.unlock() }
    assertEquals(0, db.commits); assertEquals(1, db.rollbacks)
    assertTrue(db.rows.isEmpty())
  }

  @Test fun `cancellation during commit is unknown and blocks replay while late worker settles`() = runBlocking {
    val storage = AutomaticStorage(); val db = AutomaticJdbc(); val writer = writer(storage, db)
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
    db.onCommit = {
      entered.countDown()
      var interrupted = false
      while (true) {
        try { check(release.await(5, TimeUnit.SECONDS)); break } catch (_: InterruptedException) { interrupted = true }
      }
      exited.countDown()
      if (interrupted) Thread.currentThread().interrupt()
    }
    var failure: PostgresFailure? = null
    val job = launch(Dispatchers.Default) {
      try { writer.started(server(storage), episode()) } catch (e: PostgresException) { failure = e.reason }
    }
    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    job.cancelAndJoin()
    assertEquals(PostgresFailure.COMMIT_UNKNOWN, failure)
    assertFailure(PostgresFailure.COMMIT_UNKNOWN) { writer.started(server(storage), episode()) }
    release.countDown()
    withContext(Dispatchers.IO) { assertTrue(exited.await(5, TimeUnit.SECONDS)) }
    assertEquals(1, db.commits)
  }

  private fun writer(storage: AutomaticStorage, db: AutomaticJdbc) = PostgresAutomaticPlaybackWriter(storage, db.factory())
  private fun episode() = EmbyItem(
    Id = "episode",
    Name = "Raw name",
    Type = "Episode",
    SeriesId = "series",
    SeriesName = "Series",
    ParentIndexNumber = 1,
    IndexNumber = 2,
  )
  private fun server(storage: AutomaticStorage) = EmbyServer(name = "Name", host = "fake.example", username = "fake", password = "fake", serverName = "Desktop", remoteId = "server", sourceKey = storage.token.sourceKey, sourceRevision = storage.token.revision)
  private fun row(id: String, pinned: Int, seconds: Long) = linkedMapOf<String, Any?>("id" to "history-$id", "emby_server_id" to "server", "item_id" to id, "series_id" to "series", "pinned" to pinned, "played_duration" to seconds, "create_time" to "original")
}

internal suspend fun assertFailure(reason: PostgresFailure, block: suspend () -> Unit) {
  try { block(); fail("Expected $reason") } catch (e: PostgresException) { assertEquals(reason, e.reason) }
}

internal class AutomaticStorage : PostgresConfigStorage {
  val config = PostgresConfig("fake.example", database = "fake", username = "fake", password = "fake")
  override val sourceChanges = MutableStateFlow(PostgresSourceState(0, config.sourceKey()))
  val token get() = PostgresSourceToken(config.sourceKey(), sourceChanges.value.revision)
  override suspend fun load() = config
  override suspend fun save(config: PostgresConfig) { change() }
  fun change() { sourceChanges.value = sourceChanges.value.copy(revision = sourceChanges.value.revision + 1) }
}

internal class AutomaticJdbc {
  val rows = mutableListOf<MutableMap<String, Any?>>()
  val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>() )
  val properties = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
  val lock = ReentrantLock()
  var commits = 0; var rollbacks = 0; var opens = 0; var serverUpdates = 0
  var serverExists = true; var commitThrows = false; var readOnly = true
  var failSqlPrefix: String? = null
  var onCommit: () -> Unit = {}; var onUpdate: () -> Unit = {}
  fun factory() = PostgresConnectionFactory { _, props ->
    synchronized(this) { opens++; properties += props.getProperty("readOnly") to props.getProperty("readOnlyMode") }
    var locked = false
    var working = mutableListOf<MutableMap<String, Any?>>()
    fun unlock() { if (locked && lock.isHeldByCurrentThread) { locked = false; lock.unlock() } }
    proxy(Connection::class.java) { name, args -> when (name) {
      "setReadOnly" -> { readOnly = args!![0] as Boolean; null }
      "setAutoCommit", "setTransactionIsolation" -> null
      "rollback" -> { rollbacks++; unlock(); null }
      "commit" -> {
        commits++; onCommit(); rows.clear(); rows.addAll(working); unlock()
        if (commitThrows) throw SQLException("sensitive detail", "08006")
        null
      }
      "close" -> { unlock(); null }
      "prepareStatement" -> {
        val sql = args!![0] as String
        val bound = sortedMapOf<Int, Any?>()
        proxy(PreparedStatement::class.java) { method, params -> when (method) {
          "setString", "setInt", "setLong" -> { bound[params!![0] as Int] = params[1]; null }
          "setQueryTimeout" -> { assertEquals(5, params!![0]); null }
          "close", "cancel" -> null
          "executeQuery" -> {
            calls += sql to bound.values.toList()
            val selected = if (sql.startsWith("SELECT id FROM emby_server")) {
              lock.lock(); locked = true; working = rows.map { it.toMutableMap() }.toMutableList()
              if (serverExists) listOf(mapOf("id" to "server")) else emptyList()
            } else working.filter { it["emby_server_id"] == bound[1] && it["item_id"] == bound[2] }
            resultSet(selected)
          }
          "executeUpdate" -> {
            calls += sql to bound.values.toList()
            if (failSqlPrefix?.let { sql.startsWith(it) } == true) throw SQLException("sensitive schema detail", "42703")
            val affected = when {
              sql.startsWith("INSERT") -> {
                working += linkedMapOf("id" to bound[1], "emby_server_id" to bound[2], "emby_server_name" to bound[3], "item_type" to bound[4], "item_name" to bound[5], "series_id" to bound[6], "series_name" to bound[7], "pinned" to bound[8], "item_id" to bound[9], "played_duration" to 0L)
                1
              }
              sql.startsWith("UPDATE play_history SET pinned") -> {
                val matched = working.filter { it["pinned"] == 1 && it["emby_server_id"] == bound[1] && it["series_id"] == bound[2] }
                matched.forEach { it["pinned"] = 0 }; matched.size
              }
              sql.startsWith("UPDATE play_history") -> {
                val matched = working.filter { it["id"] == bound[7] && it["emby_server_id"] == bound[8] && it["item_id"] == bound[9] }
                matched.forEach {
                  it["emby_server_name"] = bound[1]; it["item_type"] = bound[2]; it["item_name"] = bound[3]; it["series_id"] = bound[4]; it["series_name"] = bound[5]
                  if (sql.contains("COALESCE")) it["played_duration"] = (it["played_duration"] as Number).toLong() + (bound[6] as Number).toLong() else it["pinned"] = bound[6]
                }; matched.size
              }
              sql.startsWith("UPDATE emby_server") -> { serverUpdates++; 1 }
              else -> error("Unexpected SQL")
            }
            onUpdate(); affected
          }
          else -> null
        } }
      }
      else -> null
    } }
  }
}

internal fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
  var index = -1; var wasNull = false
  return proxy(ResultSet::class.java) { name, args -> when (name) {
    "next" -> { index++; index < rows.size }
    "getString", "getInt" -> {
      val value = rows[index][args!![0]]; wasNull = value == null
      if (name == "getInt") (value as? Number)?.toInt() ?: 0 else value
    }
    "wasNull" -> wasNull
    else -> null
  } }
}
internal fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
  type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args) })
