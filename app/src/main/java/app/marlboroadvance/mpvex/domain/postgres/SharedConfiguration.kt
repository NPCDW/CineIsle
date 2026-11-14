package app.marlboroadvance.mpvex.domain.postgres

import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import java.net.IDN
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.StateFlow

/** UI-only status values. No secret value is represented by this type. */
enum class SharedSessionSource { SHARED_TOKEN, PROCESS_RELOGIN, NOT_LOGGED_IN }

data class SharedConfigurationItem(
  val label: String,
  val value: String,
  val info: String? = null,
)

data class SharedConfigurationSection(
  val title: String,
  val summary: String? = null,
  val items: List<SharedConfigurationItem>,
)

data class SharedConfigurationServerSnapshot(
  val title: String,
  val summary: String,
  val sections: List<SharedConfigurationSection>,
)

/**
 * UI-only, already-sanitized data. This class intentionally implements neither Serializable nor Parcelable.
 * Its string representation reports counts only so accidental logging cannot reveal display data.
 */
class SharedConfigurationSnapshot internal constructor(
  val source: PostgresSourceToken,
  val sections: List<SharedConfigurationSection>,
  val servers: List<SharedConfigurationServerSnapshot>,
) {
  override fun toString(): String =
    "SharedConfigurationSnapshot(source=$source, sections=${sections.size}, servers=${servers.size}, values=[redacted])"
}

interface SharedConfigurationDataSource {
  val sourceChanges: StateFlow<PostgresSourceState>
  suspend fun currentSource(): PostgresSourceToken
  suspend fun snapshot(expected: PostgresSourceToken, forceRefresh: Boolean = false): SharedConfigurationSnapshot
}

/** Source-bound UI repository. It shares the runtime repository's one-transaction 60-second raw snapshot. */
class SharedConfigurationRepository(
  private val configurations: PlaybackConfigurationRepository,
  private val emby: EmbyRepository,
) : SharedConfigurationDataSource {
  override val sourceChanges get() = configurations.sourceChanges
  override suspend fun currentSource(): PostgresSourceToken = configurations.currentSource()

  override suspend fun snapshot(expected: PostgresSourceToken, forceRefresh: Boolean): SharedConfigurationSnapshot {
    if (configurations.currentSource() != expected) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    val raw = configurations.rawSnapshot(expected, forceRefresh)
    val sessionServers = raw.servers.associate { row ->
      row.id to emby.sessionSnapshotByRemoteId(row.id, raw.source)
    }
    return SharedConfigurationSanitizer.sanitize(raw, sessionServers)
  }
}

/** Only this object can cross from credential-bearing raw data into UI display data. */
internal object SharedConfigurationSanitizer {
  private const val MAX_TEXT = 160
  private const val MAX_NAME = 80
  private val controls = Regex("[\\p{Cc}\\p{Cf}\\u202A-\\u202E\\u2066-\\u2069]")
  private val endpointSchemes = setOf("http", "https")
  private val timeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  fun sanitize(
    raw: SharedConfigurationRawSnapshot,
    sessionServers: Map<String, app.marlboroadvance.mpvex.domain.emby.EmbyServer?> = emptyMap(),
  ): SharedConfigurationSnapshot {
    require(raw.values.keys.all { it in ANDROID_GLOBAL_CONFIG_ALLOWLIST })
    val values = raw.values

    val playback = listOf(
      item("自动版本策略", clean(values["play_version_auto_select_policy"] ?: "high-resolution")),
      item("预加载下一集", yesNo(values["prefetch_playlist"] == "yes")),
      item("PlaybackInfo IsPlayback", yesNo(values["play_param_IsPlayback"]?.let { it == "true" } ?: true)),
      item("前向缓存秒数", number(values["mpv_cache_seconds"], "0")),
      item("前向最小缓存 MiB", number(values["mpv_cache_min_bytes"], "0")),
      item("前向最大缓存 MiB", number(values["mpv_cache_max_bytes"], "0")),
      item("后向缓存秒数", number(values["mpv_cache_back_seconds"], "0")),
      item("后向最小缓存 MiB", number(values["mpv_cache_back_min_bytes"], "0")),
      item("后向最大缓存 MiB", number(values["mpv_cache_back_max_bytes"], "0")),
    )
    val network = listOf(
      item("忽略 TLS 证书错误", yesNo(values["danger_accept_invalid_certs"] == "true"), "高风险".takeIf { values["danger_accept_invalid_certs"] == "true" }),
      item("字幕保留天数", number(values["subtitleStoredDays"], "1")),
      item("桌面端上次字幕清理", epochSeconds(values["lastCleanSubtitleTime"])),
    )
    val trakt = listOf(
      item("同步", enabled(values["trakt_sync_switch"])),
      item("用户名", maskAccount(values["trakt_username"])),
      item("Access Token", status(values["trakt_access_token"])),
      item("Refresh Token", status(values["trakt_refresh_token"])),
      item("Token 到期时间", epochSeconds(values["trakt_expires_in"])),
      item("Redirect URI", sanitizeEndpoint(values["trakt_redirect_uri"])),
    )
    val simkl = listOf(
      item("同步", enabled(values["simkl_sync_switch"])),
      item("用户名", maskAccount(values["simkl_username"])),
      item("Access Token", status(values["simkl_access_token"])),
    )
    val yamTrack = listOf(
      item("同步", enabled(values["yamtrack_sync_switch"])),
      item("Webhook", sanitizeEndpoint(values["yamtrack_sync_url"])),
    )
    val reverseProxies = raw.reverseProxies.values.filter { proxy -> raw.servers.any { it.reverseProxyId == proxy.id } }.map { proxy ->
      item(clean(proxy.name, MAX_NAME).ifEmpty { "未命名反向代理" }, sanitizeEndpoint(proxy.url), "已引用")
    }

    val sections = buildList {
      add(SharedConfigurationSection("播放与缓存", items = playback))
      add(SharedConfigurationSection("网络与字幕", items = network))
      add(SharedConfigurationSection("Trakt", items = trakt))
      add(SharedConfigurationSection("Simkl", items = simkl))
      add(SharedConfigurationSection("YamTrack", items = yamTrack))
      if (reverseProxies.isNotEmpty()) add(SharedConfigurationSection("被引用的反向代理", items = reverseProxies))
    }

    val lines = raw.lines.groupBy { it.serverId }
    val servers = raw.servers.map { server ->
      val selectedLine = lines[server.id].orEmpty().firstOrNull { it.id == server.lineId }
      val lineValid = server.lineId.isBlank() || server.lineId == "no" || selectedLine != null
      val reverse = raw.reverseProxies[server.reverseProxyId]
      val reverseValid = server.reverseProxyId.isBlank() || server.reverseProxyId == "no" || reverse != null
      val runtime = sessionServers[server.id]
      val source = when {
        runtime == null || !runtime.isLoggedIn -> SharedSessionSource.NOT_LOGGED_IN
        runtime.apiToken != server.authToken || runtime.userId != server.userId -> SharedSessionSource.PROCESS_RELOGIN
        else -> SharedSessionSource.SHARED_TOKEN
      }
      val identity = listOf(
        item("配置 ID", maskIdentifier(server.id)),
        item("创建时间", epochMillis(server.createdAtMillis)),
        item("服务器地址", sanitizeEndpoint(server.baseUrl)),
        item("服务器名称", clean(server.serverName, MAX_NAME).ifEmpty { "未设置" }),
        item("排序", server.orderBy.toString()),
        item("状态", if (server.disabled) "桌面端已禁用" else "已启用"),
        item("图标地址", sanitizeEndpoint(server.iconUrl)),
      )
      val credentials = listOf(
        item("用户名", maskAccount(server.username)),
        item("密码", status(server.password)),
        item("共享 Token", status(server.authToken)),
        item("User ID", status(server.userId)),
        item("Client", clean(server.client)),
        item("Device", clean(server.device)),
        item("Client Version", clean(server.clientVersion)),
        item("Device ID", status(server.deviceId)),
        item("User-Agent", status(server.userAgent)),
        item("当前会话来源", sessionSource(source)),
      )
      val routing = listOf(
        item("当前线路", when {
          server.lineId.isBlank() || server.lineId == "no" -> "未选择"
          selectedLine != null -> "${clean(selectedLine.name, MAX_NAME)} (${maskIdentifier(selectedLine.id)})"
          else -> "引用无效 (${maskIdentifier(server.lineId)})"
        }, if (lineValid) "引用有效" else "引用无效"),
        item("反向代理", when {
          server.reverseProxyId.isBlank() || server.reverseProxyId == "no" -> "未使用"
          reverse != null -> "${clean(reverse.name, MAX_NAME)} · ${sanitizeEndpoint(reverse.url)}"
          else -> "引用无效 (${maskIdentifier(server.reverseProxyId)})"
        }, if (reverseValid) "引用有效" else "引用无效"),
        item("线路字段说明", "桌面端已应用到服务器行；Android 不用线路 URL 或反向代理覆盖服务器行"),
      )
      SharedConfigurationServerSnapshot(
        title = clean(server.serverName, MAX_NAME).ifEmpty { sanitizeEndpoint(server.baseUrl) },
        summary = maskIdentifier(server.id),
        sections = listOf(
          SharedConfigurationSection("服务器", items = identity),
          SharedConfigurationSection("凭据与客户端", items = credentials),
          SharedConfigurationSection("线路与反向代理", items = routing),
        ),
      )
    }
    return SharedConfigurationSnapshot(raw.source, sections, servers)
  }

  private fun item(label: String, value: String, info: String? = null) =
    SharedConfigurationItem(clean(label, MAX_NAME), clean(value), info?.let { clean(it, MAX_NAME) })

  private fun clean(value: String?, max: Int = MAX_TEXT): String =
    controls.replace(value.orEmpty(), "").replace(Regex("\\s+"), " ").trim().take(max)

  private fun yesNo(value: Boolean) = if (value) "是" else "否"
  private fun configured(value: Boolean) = if (value) "已配置" else "未配置"
  private fun status(value: String?) = configured(!value.isNullOrBlank())
  private fun enabled(value: String?) = if (value == "off") "关闭" else "开启"
  private fun number(value: String?, fallback: String) = value?.toLongOrNull()?.toString() ?: fallback
  private fun sessionSource(value: SharedSessionSource) = when (value) {
    SharedSessionSource.SHARED_TOKEN -> "共享 Token"
    SharedSessionSource.PROCESS_RELOGIN -> "本进程重登录"
    SharedSessionSource.NOT_LOGGED_IN -> "未登录"
  }

  internal fun maskAccount(value: String?): String {
    val clean = clean(value, MAX_NAME)
    if (clean.isEmpty()) return "未配置"
    val chars = clean.codePoints().toArray()
    return when (chars.size) {
      1 -> "*"
      2 -> String(chars, 0, 1) + "*"
      else -> String(chars, 0, 1) + "***" + String(chars, chars.lastIndex, 1)
    }
  }

  private fun maskIdentifier(value: String): String {
    val clean = clean(value, MAX_NAME)
    if (clean.length <= 8) return if (clean.isBlank()) "未配置" else "***"
    return "${clean.take(4)}…${clean.takeLast(4)}"
  }

  internal fun sanitizeEndpoint(value: String?): String {
    val input = clean(value)
    if (input.isBlank()) return "未配置"
    return try {
      val uri = URI(input)
      val scheme = uri.scheme?.lowercase()?.takeIf { it in endpointSchemes } ?: return "地址无效"
      if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return "地址无效"
      val host = sanitizeHost(uri.host)
      val port = if (uri.port >= 0) ":${uri.port}" else ""
      val hasPath = uri.rawPath?.let { it.isNotBlank() && it != "/" } == true
      val path = if (hasPath) " · 含路径前缀" else " · 无路径前缀"
      "$scheme://$host$port$path"
    } catch (_: Exception) {
      "地址无效"
    }
  }

  private fun sanitizeHost(value: String): String {
    val host = value.removeSurrounding("[", "]")
    if (':' in host) return "[IPv6]"
    val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host).lowercase()
    val labels = ascii.split('.').filter(String::isNotBlank)
    return when {
      labels.isEmpty() -> "host"
      labels.size == 1 -> maskLabel(labels.single())
      else -> "${maskLabel(labels.first())}.${labels.drop(1).joinToString(".") { clean(it, 32) }}"
    }
  }

  private fun maskLabel(label: String) = when {
    label.length <= 1 -> "*"
    label.length == 2 -> "${label.first()}*"
    else -> "${label.first()}***${label.last()}"
  }

  private fun epochSeconds(value: String?): String = value?.toLongOrNull()?.let {
    runCatching { timeFormatter.format(Instant.ofEpochSecond(it)) }.getOrNull()
  } ?: "未配置"

  private fun epochMillis(value: Long): String =
    runCatching { timeFormatter.format(Instant.ofEpochMilli(value)) }.getOrDefault("无效时间")
}
