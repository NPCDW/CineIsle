package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Android-free prepared-SQL and source-isolation regression tests; never connects to a database. */
class PostgresEmbyBackendTest {
  private val config = PostgresConfig("db.example", database = "emby", username = "user", password = "private")

  @Test fun `source fingerprint excludes password and isolates every other source field`() {
    val key = config.sourceKey()
    assertEquals(key, config.copy(password = "different").sourceKey())
    listOf(config.copy(host = "other.example"), config.copy(port = 5555), config.copy(database = "other"),
      config.copy(username = "other"), config.copy(sslMode = PostgresSslMode.REQUIRE)).forEach { assertNotEquals(key, it.sourceKey()) }
    assertEquals(64, key.length)
    assertFalse(key.contains(config.host))
  }

  @Test fun `full URL identity plaintext nullable credentials and selected lines roundtrip`() {
    val fixture = Fixture()
    fixture.row["base_url"] = "https://[2001:db8::1]:8920/proxy/emby"
    fixture.row["username"] = null
    fixture.row["password"] = "p\\\"\n"
    fixture.row["auth_token"] = "enc:v1:not-device-encrypted"
    fixture.row["disabled"] = 1
    val servers = PostgresEmbyStore(fixture.connection).getAll("source")
    val server = servers.single()
    assertEquals("https://[2001:db8::1]:8920/proxy/emby", server.baseUrl)
    assertEquals("https://[2001:db8::1]:8920", server.hostUrl)
    assertEquals("2001:db8::1", server.host)
    assertEquals("", server.username)
    assertEquals(fixture.row["password"], server.password)
    assertEquals(fixture.row["auth_token"], server.apiToken)
    assertEquals("Desktop", server.authIdentity.client)
    assertTrue(server.disabled)
    assertEquals(0, server.lastConnected)
    assertEquals("", server.version)
    assertTrue(fixture.sql.any {
      it == "SELECT ${SERVER_COLUMNS.joinToString(", ")} FROM emby_server ORDER BY order_by, create_time, id"
    })
    assertFalse(fixture.dataQueries().any { it.contains("SELECT *") })
    assertFalse(fixture.dataQueries().any { it.contains("browse_proxy_id") || it.contains("play_proxy_id") })
    assertEquals(5, fixture.queryTimeout)
  }

  @Test fun `optional icon column maps unchanged in both queries and null or missing use default`() {
    val fixture = Fixture()
    val icon = "data:image/png;base64,iVBORw0KGgo="
    fixture.row["icon_url"] = icon
    val store = PostgresEmbyStore(fixture.connection)
    val server = store.getAll("source").single()
    assertEquals(icon, server.iconUrl)
    assertEquals(icon, store.getByRemoteId(server.remoteId, "source")?.iconUrl)
    fixture.row["icon_url"] = null
    assertEquals("", PostgresEmbyStore(fixture.connection).getAll("source").single().iconUrl)
    fixture.row.remove("icon_url")
    val legacy = PostgresEmbyStore(fixture.connection)
    assertEquals("", legacy.getAll("source").single().iconUrl)
    assertEquals("", legacy.getByRemoteId(server.remoteId, "source")?.iconUrl)
    assertTrue(fixture.sql.all { it.startsWith("SELECT ") })
    assertEquals(0, fixture.commits)
  }

  @Test fun `missing optional reverse proxy column is compatible but required columns fail`() {
    val fixture = Fixture()
    fixture.row.remove("reverse_proxy_id")
    assertEquals("no", PostgresEmbyStore(fixture.connection).getAll("source").single().reverseProxyId)
    fixture.row.remove("client")
    try { PostgresEmbyStore(fixture.connection).getAll("source"); fail("Expected schema failure") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.SCHEMA_INCOMPATIBLE, error.reason) }
  }

  @Test fun `successful memory serves repeated media lookup and failure retains last success without SQLite`() = runBlocking {
    val storage = Storage(config)
    val fixture = Fixture()
    var connections = 0
    var offline = false
    val executor = JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, _ ->
      connections++
      if (offline) throw SQLException("unsafe credentials private", "08001")
      fixture.connection
    })
    val scope = CoroutineScope(SupervisorJob())
    val repo = EmbyServerRepository(executor, IdentityMap(), scope)
    try {
      val first = repo.getAll().single()
      assertTrue(first.id >= EMBY_IDENTITY_MIN)
      repeat(10) { assertEquals(first, repo.getById(first.id)) }
      assertEquals(1, connections)
      offline = true
      repo.refresh()
      assertEquals(PostgresFailure.CONNECTION, (repo.loadState.value as EmbyServerLoadState.Failed).reason)
      assertEquals(listOf(first), (repo.loadState.value as EmbyServerLoadState.Failed).servers)
      assertEquals(first, repo.getById(first.id))
      try { repo.getById(first.id + 1); fail("Stale success cannot prove missing") }
      catch (error: PostgresException) { assertEquals(PostgresFailure.CONNECTION, error.reason) }
      storage.save(config.copy(database = "different"))
      assertNull(repo.snapshotById(first.id))
      assertTrue(repo.snapshotServers().isEmpty())
    } finally { scope.cancel() }
  }

  @Test fun `initial failed lookup throws failure rather than reporting missing`() = runBlocking {
    val storage = Storage(config)
    val scope = CoroutineScope(SupervisorJob())
    val executor = JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, _ -> throw SQLException("private", "08001") })
    val repo = EmbyServerRepository(executor, IdentityMap(), scope)
    try {
      try { repo.getById(1); fail("Expected explicit load failure") }
      catch (error: PostgresException) { assertEquals(PostgresFailure.CONNECTION, error.reason) }
      assertEquals(emptyList<EmbyServer>(), (repo.loadState.value as EmbyServerLoadState.Failed).servers)
    } finally { scope.cancel() }
  }

  @Test fun `source switch during query rolls back and cannot publish an old result`() = runBlocking {
    val storage = Storage(config)
    val fixture = Fixture()
    val executor = JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, _ -> fixture.connection })
    try {
      executor.read { storage.sourceChanges.value = PostgresSourceState(1, config.copy(database = "other").sourceKey()); "old" }
      fail("Expected source switch failure")
    } catch (error: PostgresException) { assertEquals(PostgresFailure.SOURCE_CHANGED, error.reason) }
    assertTrue(fixture.rollbacks > 0)
    assertEquals(0, fixture.commits)
  }

  @Test fun `read executor uses readonly transaction rollback and fixed readonly driver properties`() = runBlocking {
    val fixture = Fixture()
    val executor = JdbcPostgresExecutor(Storage(config), PostgresConnectionFactory { _, properties ->
      assertEquals("true", properties.getProperty("readOnly"))
      assertEquals("always", properties.getProperty("readOnlyMode"))
      fixture.connection
    })
    assertEquals("read", executor.read { "read" })
    assertTrue(fixture.readOnly)
    assertFalse(fixture.autoCommit)
    assertEquals(0, fixture.commits)
    assertEquals(1, fixture.rollbacks)
    try { executor.read { throw PostgresException(PostgresFailure.DATABASE) }; fail("Expected failure") }
    catch (error: PostgresException) { assertEquals(PostgresFailure.DATABASE, error.reason) }
    assertEquals(0, fixture.commits)
    assertEquals(2, fixture.rollbacks)
  }

  @Test fun `repository construction does not load configuration and source guards reject stale rows`() = runBlocking {
    val fixture = Fixture()
    val storage = Storage(config)
    val scope = CoroutineScope(SupervisorJob() + QueueDispatcher())
    val executor = JdbcPostgresExecutor(storage, PostgresConnectionFactory { _, _ -> fixture.connection })
    val repo = EmbyServerRepository(executor, IdentityMap(), scope)
    try {
      assertEquals(0, storage.loads)
      assertNull(repo.snapshotById(EMBY_IDENTITY_MIN))
      val server = repo.getAll().single()
      assertTrue(repo.isCurrent(server))
      storage.save(config.copy(password = "new"))
      assertFalse(repo.isCurrent(server))
      assertNull(repo.snapshotById(server.id))
    } finally { scope.cancel() }
  }

  @Test fun `all and UUID lookup issue only SELECT and attach reverse proxy without applying stale line settings`() {
    val fixture = Fixture()
    fixture.row["line_id"] = "selected"
    fixture.row["reverse_proxy_id"] = "reverse"
    fixture.lines = listOf(mapOf(
      "id" to "selected",
      "name" to "桌面当前线路",
      "base_url" to "https://stale.test",
      "emby_server_id" to fixture.row["id"],
      "reverse_proxy_id" to "old-reverse",
    ))
    fixture.proxies = listOf(mapOf("id" to "reverse", "name" to "只读反代", "url" to " https://relay.test/prefix "))
    val store = PostgresEmbyStore(fixture.connection)
    val row = store.getAll("source").single()
    assertEquals("桌面当前线路", row.currentLineName)
    assertEquals("只读反代", row.reverseProxyName)
    assertEquals("https://relay.test/prefix/https://emby.example/prefix/emby", row.effectiveBaseUrl)
    assertNull(row.connectionError)
    assertEquals(row, store.getByRemoteId(row.remoteId, "source"))
    assertTrue(fixture.sql.all { it.startsWith("SELECT ") && !it.contains("FOR UPDATE") && !it.contains("advisory") })
    assertTrue(fixture.calls.any {
      it.first == "SELECT ${SERVER_COLUMNS.joinToString(", ")} FROM emby_server WHERE id = ?" &&
        it.second == listOf(row.remoteId)
    })
    assertFalse(fixture.dataQueries().any { it.contains("browse_proxy_id") || it.contains("play_proxy_id") })
    assertEquals(0, fixture.commits)
  }

  @Test fun `missing selected line or reverse reference stays visible with an explicit error`() {
    val fixture = Fixture()
    fixture.row["line_id"] = "missing-line"
    val missingLine = PostgresEmbyStore(fixture.connection).getAll("source").single()
    assertTrue(missingLine.connectionError!!.contains("线路不存在"))
    fixture.row["line_id"] = ""
    fixture.row["reverse_proxy_id"] = "missing-reverse"
    val missingReverse = PostgresEmbyStore(fixture.connection).getAll("source").single()
    assertTrue(missingReverse.connectionError!!.contains("反向代理引用不存在"))
    assertEquals(fixture.row["base_url"], missingReverse.baseUrl)
  }

  private class QueueDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) { /* Deterministically park background reconciliation. */ }
  }

  private fun newServer(): EmbyServer = EmbyServer(name = "Custom", host = "emby.example", useHttps = true,
    username = "user", password = "secret", configuredBaseUrl = "https://emby.example/prefix/emby",
    remoteId = UUID.randomUUID().toString(), deviceId = UUID.randomUUID().toString(), lineId = UUID.randomUUID().toString())

  private class IdentityMap : EmbyServerIdentityMap {
    private val values = linkedMapOf<Pair<String, String>, Long>()
    override suspend fun localId(sourceKey: String, remoteId: String): Long = values.getOrPut(sourceKey to remoteId) { EMBY_IDENTITY_MIN + values.size }
  }

  private class Storage(var config: PostgresConfig) : PostgresConfigStorage {
    override val sourceChanges = MutableStateFlow(PostgresSourceState(0, config.sourceKey()))
    var loads = 0
    override suspend fun load(): PostgresConfig { loads++; return config }
    override suspend fun save(config: PostgresConfig) {
      this.config = config
      sourceChanges.value = PostgresSourceState(sourceChanges.value.revision + 1, config.sourceKey())
    }
  }

  private class Fixture {
    val row = linkedMapOf<String, Any?>("id" to UUID.randomUUID().toString(), "create_time" to Timestamp(1234),
      "base_url" to "https://emby.example/prefix/emby", "username" to "desktop-user", "password" to "desktop-pass",
      "server_name" to "Custom desktop name", "server_id" to "emby-uuid", "auth_token" to "old-token", "user_id" to "old-user",
      "client" to "Desktop", "device" to "DesktopDevice", "device_id" to "desktop-id", "client_version" to "2.0",
      "user_agent" to "Desktop/2.0", "order_by" to 7,
      "last_playback_time" to Timestamp(9988), "keep_alive_days" to 7, "disabled" to 0, "icon_url" to "icon", "line_id" to "",
      "reverse_proxy_id" to "no")
    val lineColumns = listOf("id", "create_time", "name", "emby_server_id", "emby_server_name", "base_url", "reverse_proxy_id")
    var lines: List<Map<String, Any?>> = emptyList()
    var proxies: List<Map<String, Any?>> = emptyList()
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val sql get() = calls.map { it.first }
    fun dataQueries() = sql.filterNot { it.contains(" LIMIT 0") }
    var queryTimeout = 0
    var affected = 1
    var commits = 0
    var rollbacks = 0
    var readOnly = false
    var autoCommit = true
    var commitFailure = false
    val connection = proxy(Connection::class.java) { name, args -> when (name) {
      "prepareStatement" -> prepared(args!![0] as String)
      "setAutoCommit" -> { autoCommit = args!![0] as Boolean; null }
      "getAutoCommit" -> autoCommit
      "commit" -> { commits++; if (commitFailure) throw SQLException("private", "08006"); null }
      "rollback" -> { rollbacks++; null }
      "setReadOnly" -> { readOnly = args!![0] as Boolean; null }
      "close", "setTransactionIsolation" -> null
      "isClosed" -> false
      else -> null
    } }

    private fun prepared(sql: String): PreparedStatement {
      val bound = linkedMapOf<Int, Any?>()
      return proxy(PreparedStatement::class.java) { name, args -> when (name) {
        "setQueryTimeout" -> { queryTimeout = args!![0] as Int; null }
        "setObject" -> { bound[args!![0] as Int] = args[1]; null }
        "execute", "executeUpdate", "executeQuery" -> {
          calls += sql to bound.values.toList()
          when (name) {
            "execute" -> error("Non-query attempted by read-only backend")
            "executeUpdate" -> error("Write attempted by read-only backend")
            else -> when {
              sql.contains("emby_line") -> results(lineColumns, lines)
              sql.contains("reverse_proxy_server") -> results(listOf("id", "name", "url"), proxies)
              sql.contains("LIMIT 0") -> results(row.keys.toList(), emptyList())
              sql.contains("FROM emby_server") -> results(SERVER_COLUMNS, listOf(row))
              else -> error("Unexpected query: $sql")
            }
          }
        }
        "close", "cancel" -> null
        else -> null
      } }
    }

    private fun results(columns: List<String>, data: List<Map<String, Any?>>): ResultSet {
      var index = -1
      val metadata = proxy(ResultSetMetaData::class.java) { name, args -> when (name) {
        "getColumnCount" -> columns.size
        "getColumnLabel" -> columns[(args!![0] as Int) - 1]
        else -> null
      } }
      return proxy(ResultSet::class.java) { name, args -> when (name) {
        "next" -> { index++; index < data.size }
        "getMetaData" -> metadata
        "getString" -> data[index][args!![0] as String] as String?
        "getInt" -> data[index][args!![0] as String] as? Int ?: 0
        "getTimestamp" -> data[index][args!![0] as String] as Timestamp?
        "close" -> null
        else -> null
      } }
    }
  }

  companion object {
    val SERVER_COLUMNS = listOf(
      "id", "create_time", "base_url", "username", "password", "server_name", "server_id", "auth_token", "user_id",
      "client", "device", "device_id", "client_version", "user_agent", "order_by", "disabled", "line_id", "icon_url",
      "reverse_proxy_id",
    )

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
      Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) })
  }
}
