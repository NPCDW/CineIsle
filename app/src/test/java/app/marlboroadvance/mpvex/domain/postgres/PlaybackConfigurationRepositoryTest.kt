package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackConfigurationRepositoryTest {
  @Test fun `allowlist is exact 23 Android effective keys and excludes ordinary proxies`() {
    assertEquals(23, ANDROID_GLOBAL_CONFIG_ALLOWLIST.size)
    assertEquals(EXPECTED_KEYS, ANDROID_GLOBAL_CONFIG_ALLOWLIST)
    assertTrue(ANDROID_GLOBAL_CONFIG_ALLOWLIST.intersect(FORWARD_PROXY_KEYS).isEmpty())
    assertFalse(ANDROID_GLOBAL_CONFIG_ALLOWLIST.any {
      it in setOf("mpv_volume", "cache_speed_threshold", "app_proxy_id", "log_level")
    })
  }

  @Test fun `snapshot uses one repeatable read transaction explicit allowlist and all required shared tables`() = runBlocking {
    val f = Fixture()
    f.values["desktop_only_secret"] = "must-not-return"
    val configuration = f.repository.snapshot(f.server())

    assertEquals(f.storage.token, configuration.source)
    assertEquals("server", configuration.serverUuid)
    assertEquals(4, f.queries.size)
    assertTrue(f.queries.all { it.startsWith("SELECT ") })
    assertTrue(f.queries.first().contains("WHERE config_key IN"))
    assertFalse(f.queries.first().contains("SELECT config_key, config_value FROM global_config ORDER"))
    assertEquals(EXPECTED_KEYS, f.globalBindings.toSet())
    assertFalse(f.queries.any { it.contains("FROM proxy_server") })
    assertFalse(f.queries.any { sql -> FORWARD_PROXY_COLUMNS.any(sql::contains) })
    assertTrue(f.queries.any { it.contains("FROM emby_server") })
    assertTrue(f.queries.any { it.contains("FROM emby_line") })
    assertTrue(f.queries.any { it.contains("FROM reverse_proxy_server") })
    assertEquals("true", f.readOnlyProperty)
    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, f.transactionIsolation)
    assertEquals(0, f.commits)
    assertEquals(1, f.rollbacks)
  }

  @Test fun `typed parser preserves desktop defaults and runtime behavior`() {
    val source = PostgresSourceToken("source", 7)
    val defaults = playbackConfigurationForTest(source = source)
    assertEquals("high-resolution", defaults.automaticVersionPolicy)
    assertFalse(defaults.prefetchPlaylist)
    assertTrue(defaults.isPlayback)
    assertFalse(defaults.dangerAcceptInvalidCertificates)
    assertEquals(300L * 1024 * 1024, defaults.cache.forward.bytesFor(8_000_000L))
    assertEquals(200L * 1024 * 1024, defaults.cache.backward.bytesFor(null))
    assertEquals(1L, defaults.subtitleCache.storedDays)
    assertFalse(defaults.trakt.enabled)

    val configured = playbackConfigurationForTest(mapOf(
      "play_version_auto_select_policy" to "high-bitrate",
      "prefetch_playlist" to "yes",
      "play_param_IsPlayback" to "false",
      "mpv_cache_seconds" to "20",
      "mpv_cache_min_bytes" to "2",
      "mpv_cache_max_bytes" to "10",
      "mpv_cache_back_seconds" to "broken",
      "danger_accept_invalid_certs" to "true",
      "subtitleStoredDays" to "3",
      "lastCleanSubtitleTime" to "123",
      "trakt_sync_switch" to "on",
      "trakt_username" to "user",
      "trakt_access_token" to "access",
      "trakt_refresh_token" to "refresh",
      "trakt_expires_in" to "999",
      "trakt_redirect_uri" to "http://127.0.0.1:123/trakt_auth",
      "simkl_sync_switch" to "off",
      "simkl_username" to "simkl-user",
      "yamtrack_sync_url" to "https://yam.example/hook",
    ))
    assertEquals("high-bitrate", configured.automaticVersionPolicy)
    assertTrue(configured.prefetchPlaylist)
    assertFalse(configured.isPlayback)
    assertEquals(10L * 1024 * 1024, configured.cache.forward.bytesFor(8_000_000L))
    assertEquals(200L, configured.cache.backward.seconds)
    assertTrue(configured.dangerAcceptInvalidCertificates)
    assertEquals(3L, configured.subtitleCache.storedDays)
    assertEquals(123L, configured.subtitleCache.sharedLastCleanEpochSeconds)
    assertTrue(configured.trakt.enabled)
    assertFalse(configured.simkl.enabled)
    assertTrue(configured.yamTrack.enabled)
  }

  @Test fun `forward proxy rows and credentials are never queried or read`() = runBlocking {
    val f = Fixture()
    f.repository.snapshot(f.server())

    assertEquals("proxy-user-canary", f.ordinaryProxyRow["username"])
    assertEquals("proxy-password-canary", f.ordinaryProxyRow["password"])
    assertFalse(f.queries.any { it.contains("proxy_server") && !it.contains("reverse_proxy_server") })
    assertTrue(f.readColumns.intersect(FORWARD_PROXY_COLUMNS).isEmpty())
    assertTrue(f.readColumns.intersect(setOf("proxy_type", "addr")).isEmpty())
    assertTrue(f.readCredentials.none { it in setOf("proxy-user-canary", "proxy-password-canary") })
  }

  @Test fun `source revision cache ttl force refresh and singleflight are source bound`() = runBlocking {
    val f = Fixture()
    val snapshots = kotlinx.coroutines.coroutineScope { List(20) { async { f.repository.snapshot(f.server()) } }.awaitAll() }
    assertTrue(snapshots.all { it.source == snapshots.first().source && it.serverUuid == snapshots.first().serverUuid })
    assertEquals(4, f.queries.size)
    f.now = 59_000_000_000L
    f.repository.snapshot(f.server())
    assertEquals(4, f.queries.size)
    f.now = 60_000_000_000L
    f.repository.snapshot(f.server())
    assertEquals(8, f.queries.size)

    val source = f.storage.token
    f.repository.rawSnapshot(source, forceRefresh = true)
    assertEquals(12, f.queries.size)
    f.storage.change()
    assertFailure(PostgresFailure.SOURCE_CHANGED) { f.repository.rawSnapshot(source) }
    f.repository.snapshot(f.server())
    assertEquals(16, f.queries.size)
  }

  @Test fun `source guarded before read during read and failure never serves expired runtime cache`() = runBlocking {
    val f = Fixture()
    val old = f.server()
    f.storage.change()
    assertFailure(PostgresFailure.SOURCE_CHANGED) { f.repository.snapshot(old) }
    assertTrue(f.queries.isEmpty())

    val current = f.server()
    f.onQuery = { f.storage.change() }
    assertFailure(PostgresFailure.SOURCE_CHANGED) { f.repository.snapshot(current) }
    assertEquals(0, f.commits)

    val failed = Fixture()
    failed.repository.snapshot(failed.server())
    failed.now = 60_000_000_000L
    failed.fail = true
    assertFailure(PostgresFailure.SCHEMA_INCOMPATIBLE) { failed.repository.snapshot(failed.server()) }
  }

  @Test fun `sensitive runtime models redact toString canaries`() {
    val canaries = listOf("access-CANARY", "refresh-CANARY", "webhook-CANARY")
    val config = playbackConfigurationForTest(
      values = mapOf(
        "trakt_username" to "person",
        "trakt_access_token" to canaries[0],
        "trakt_refresh_token" to canaries[1],
        "yamtrack_sync_url" to "https://${canaries[2]}.example/hook",
      ),
    )
    val rendered = listOf(config, config.trakt, config.simkl, config.yamTrack).joinToString("|")
    canaries.forEach { assertFalse(rendered.contains(it)) }
    assertTrue(rendered.contains("redacted"))
  }

  private companion object {
    val EXPECTED_KEYS = linkedSetOf(
      "play_version_auto_select_policy", "prefetch_playlist", "play_param_IsPlayback",
      "mpv_cache_seconds", "mpv_cache_min_bytes", "mpv_cache_max_bytes",
      "mpv_cache_back_seconds", "mpv_cache_back_min_bytes", "mpv_cache_back_max_bytes",
      "danger_accept_invalid_certs", "subtitleStoredDays", "lastCleanSubtitleTime",
      "trakt_sync_switch", "trakt_username", "trakt_access_token", "trakt_refresh_token",
      "trakt_expires_in", "trakt_redirect_uri",
      "simkl_sync_switch", "simkl_username", "simkl_access_token",
      "yamtrack_sync_switch", "yamtrack_sync_url",
    )
    val FORWARD_PROXY_KEYS = setOf(
      "global_browse_proxy_id",
      "global_play_proxy_id",
      "trakt_proxy_id",
      "simkl_proxy_id",
      "yamtrack_proxy_id",
    )
    val FORWARD_PROXY_COLUMNS = setOf("browse_proxy_id", "play_proxy_id")
  }
}

private class Fixture {
  val storage = AutomaticStorage()
  val values = linkedMapOf(
    "trakt_access_token" to "access-secret",
  )
  val ordinaryProxyRow = mapOf(
    "id" to "ordinary-proxy",
    "proxy_type" to "http",
    "addr" to "proxy.example:8080",
    "username" to "proxy-user-canary",
    "password" to "proxy-password-canary",
  )
  val servers = mutableListOf<MutableMap<String, Any?>>(linkedMapOf(
    "id" to "server", "create_time" to Timestamp(1_000L), "base_url" to "https://media.example/prefix",
    "username" to "viewer", "password" to "server-password", "server_name" to "Media",
    "auth_token" to "server-token", "user_id" to "user-id", "client" to "loemby", "device" to "Android",
    "device_id" to "device-id", "client_version" to "1", "user_agent" to "agent", "order_by" to 1,
    "disabled" to 0, "icon_url" to "",
    "line_id" to "line", "reverse_proxy_id" to "reverse",
  ))
  private val lines = listOf(mapOf("id" to "line", "name" to "Primary", "emby_server_id" to "server"))
  private val reverse = listOf(mapOf("id" to "reverse", "name" to "Gateway", "url" to "https://gateway.example/r/"))
  val queries = mutableListOf<String>()
  val globalBindings = mutableListOf<String>()
  val readColumns = mutableSetOf<String>()
  val readCredentials = mutableSetOf<String>()
  var onQuery: () -> Unit = {}
  var fail = false
  var readOnlyProperty = ""
  var transactionIsolation = -1
  var commits = 0
  var rollbacks = 0
  var now = 0L

  private val factory = PostgresConnectionFactory { _, props ->
    readOnlyProperty = props.getProperty("readOnly")
    jdbcProxy(Connection::class.java) { name, args -> when (name) {
      "setTransactionIsolation" -> { transactionIsolation = args!![0] as Int; null }
      "commit" -> { commits++; null }
      "rollback" -> { rollbacks++; null }
      "prepareStatement" -> {
        val sql = args!![0] as String
        val bindings = sortedMapOf<Int, String>()
        jdbcProxy(PreparedStatement::class.java) { method, parameters -> when (method) {
          "setString" -> { bindings[parameters!![0] as Int] = parameters[1] as String; null }
          "executeQuery" -> {
            queries += sql
            onQuery()
            if (fail) throw SQLException("private details", "42P01")
            when {
              sql.contains("FROM global_config") -> {
                globalBindings += bindings.values
                configResultSet(
                  values.filterKeys { it in bindings.values }.map { mapOf("config_key" to it.key, "config_value" to it.value) },
                  readColumns,
                  readCredentials,
                )
              }
              sql.contains("FROM emby_server") -> configResultSet(servers, readColumns, readCredentials)
              sql.contains("FROM emby_line") -> configResultSet(lines, readColumns, readCredentials)
              sql.contains("FROM reverse_proxy_server") -> configResultSet(reverse, readColumns, readCredentials)
              else -> error("Unexpected query")
            }
          }
          else -> null
        } }
      }
      else -> null
    } }
  }
  val repository = PlaybackConfigurationRepository(JdbcPostgresExecutor(storage, factory)) { now }

  fun server() = EmbyServer(
    name = "Media", host = "fake.example", username = "viewer", password = "server-password",
    userId = "user-id", apiToken = "server-token", remoteId = "server",
    sourceKey = storage.token.sourceKey, sourceRevision = storage.token.revision,
    configuredBaseUrl = "https://media.example/prefix", client = "loemby", device = "Android",
    deviceId = "device-id", clientVersion = "1", userAgent = "agent", reverseProxyId = "reverse",
  )
}

private fun configResultSet(
  rows: List<Map<String, Any?>>,
  readColumns: MutableSet<String>,
  readCredentials: MutableSet<String>,
): ResultSet {
  var index = -1
  return jdbcProxy(ResultSet::class.java) { name, args -> when (name) {
    "next" -> { index++; index < rows.size }
    "getString" -> (args!![0] as String).let { column ->
      readColumns += column
      rows[index][column]?.toString()?.also { if (column in setOf("username", "password")) readCredentials += it }
    }
    "getInt" -> (args!![0] as String).let { column ->
      readColumns += column
      (rows[index][column] as? Number)?.toInt() ?: 0
    }
    "getTimestamp" -> (args!![0] as String).let { column ->
      readColumns += column
      rows[index][column] as? Timestamp
    }
    else -> null
  } }
}

private fun <T> jdbcProxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
  type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args) })
