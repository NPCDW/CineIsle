package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyServerLine
import java.net.URI
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/** Pure JVM SELECT-only mapping. Owns statements, not the read-only connection/rollback. */
class PostgresEmbyStore(
  private val connection: Connection,
  private val track: (PreparedStatement) -> Unit = {},
) {
  private val serverColumns by lazy { columns("emby_server") }
  private val lineColumns by lazy { columns("emby_line") }

  private val serverProjection: String get() = buildList {
    addAll(SERVER_REQUIRED)
    if ("icon_url" in serverColumns) add("icon_url")
    if ("reverse_proxy_id" in serverColumns) add("reverse_proxy_id")
  }.joinToString(", ")

  private val lineProjection: String get() = buildList {
    addAll(LINE_REQUIRED)
    if ("reverse_proxy_id" in lineColumns) add("reverse_proxy_id")
  }.joinToString(", ")

  /** Metadata-only compatibility probe; ordinary data reads use explicit projections below. */
  private fun columns(table: String): Set<String> = prepared("SELECT * FROM $table LIMIT 0") { statement ->
    statement.executeQuery().use { rows ->
      (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }.toSet()
    }
  }

  fun getAll(sourceKey: String): List<EmbyServer> {
    requireColumns()
    val proxies = reverseProxies()
    val lines = prepared("SELECT $lineProjection FROM emby_line ORDER BY create_time, id") { statement ->
      statement.executeQuery().use { rows ->
        buildList { while (rows.next()) add(rows.string("emby_server_id") to line(rows)) }
      }
    }.groupBy({ it.first }, { it.second })
    return prepared("SELECT $serverProjection FROM emby_server ORDER BY order_by, create_time, id") { statement ->
      statement.executeQuery().use { rows ->
        buildList { while (rows.next()) {
          val server = server(rows, sourceKey)
          add(attach(server, lines[server.remoteId].orEmpty(), proxies))
        } }
      }
    }
  }

  fun getByRemoteId(remoteId: String, sourceKey: String): EmbyServer? {
    requireColumns()
    val proxies = reverseProxies()
    val found = prepared("SELECT $serverProjection FROM emby_server WHERE id = ?", remoteId) { statement ->
      statement.executeQuery().use { rows -> if (rows.next()) server(rows, sourceKey) else null }
    } ?: return null
    val lines = prepared(
      "SELECT $lineProjection FROM emby_line WHERE emby_server_id = ? ORDER BY create_time, id",
      remoteId,
    ) { statement ->
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(line(rows)) } }
    }
    return attach(found, lines, proxies)
  }

  private data class ReverseProxy(val name: String, val url: String)

  private fun reverseProxies(): Map<String, ReverseProxy> {
    // Compatibility with older desktop schemas that have no reverse proxy feature at all.
    if ("reverse_proxy_id" !in serverColumns) return emptyMap()
    return prepared("SELECT id, name, url FROM reverse_proxy_server") { statement ->
      statement.executeQuery().use { rows ->
        buildMap { while (rows.next()) put(rows.string("id"), ReverseProxy(rows.string("name"), rows.string("url"))) }
      }
    }
  }

  private fun attach(server: EmbyServer, lines: List<EmbyServerLine>, proxies: Map<String, ReverseProxy>): EmbyServer {
    val proxy = proxies[server.reverseProxyId]
    // Desktop has already applied the selected line to emby_server. Never overwrite its URL or reverse route.
    return server.copy(lines = lines, reverseProxyName = proxy?.name.orEmpty(), reverseProxyUrl = proxy?.url.orEmpty())
  }

  private fun <T> prepared(sql: String, vararg values: Any?, block: (PreparedStatement) -> T): T = connection.prepareStatement(sql).use { statement ->
    track(statement)
    statement.queryTimeout = JdbcPostgresIo.QUERY_TIMEOUT_SECONDS
    values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    block(statement)
  }

  private fun requireColumns() {
    if (!serverColumns.containsAll(SERVER_REQUIRED) || !lineColumns.containsAll(LINE_REQUIRED)) throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE)
  }

  private fun server(rows: ResultSet, sourceKey: String): EmbyServer {
    val remoteId = rows.string("id")
    uuid(remoteId)
    val url = validatedUrl(rows.string("base_url"))
    val uri = URI(url)
    return EmbyServer(
      name = rows.string("server_name"), host = uri.host.removeSurrounding("[", "]"),
      port = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80,
      useHttps = uri.scheme.equals("https", true), username = rows.string("username"), password = rows.string("password"),
      userId = rows.string("user_id"), apiToken = rows.string("auth_token"),
      serverName = rows.string("server_name"), createdAt = rows.getTimestamp("create_time").time,
      remoteId = remoteId, sourceKey = sourceKey, configuredBaseUrl = url,
      client = rows.string("client"), device = rows.string("device"), deviceId = rows.string("device_id"),
      clientVersion = rows.string("client_version"), userAgent = rows.string("user_agent"),
      orderBy = rows.getInt("order_by"), disabled = rows.getInt("disabled") != 0, lineId = rows.string("line_id"),
      reverseProxyId = if ("reverse_proxy_id" in serverColumns) rows.string("reverse_proxy_id") else "no",
      embyRemoteServerId = rows.string("server_id"),
      iconUrl = if ("icon_url" in serverColumns) rows.string("icon_url") else "",
    )
  }

  private fun line(rows: ResultSet): EmbyServerLine = EmbyServerLine(
    rows.string("id"),
    rows.string("name"),
    rows.string("base_url"),
    if ("reverse_proxy_id" in lineColumns) rows.string("reverse_proxy_id") else "no",
  )
  private fun ResultSet.string(column: String): String = getString(column).orEmpty()
  private fun uuid(value: String) {
    try { require(UUID.fromString(value).toString().equals(value, true)) } catch (_: IllegalArgumentException) { throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE) }
  }

  companion object {
    private val SERVER_REQUIRED = listOf(
      "id", "create_time", "base_url", "username", "password", "server_name", "server_id", "auth_token", "user_id",
      "client", "device", "device_id", "client_version", "user_agent", "order_by", "disabled", "line_id",
    )
    private val LINE_REQUIRED = listOf("id", "create_time", "name", "emby_server_id", "base_url")

    fun validatedUrl(value: String): String {
      try {
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
        return value
      } catch (_: Exception) { throw PostgresException(PostgresFailure.SCHEMA_INCOMPATIBLE) }
    }
  }
}
