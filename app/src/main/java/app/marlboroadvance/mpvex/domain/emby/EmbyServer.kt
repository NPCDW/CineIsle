package app.marlboroadvance.mpvex.domain.emby

/** Shared PostgreSQL configuration. [id] is a device-local positive identity, never a remote UUID. */
data class EmbyServer(
  val id: Long = 0,
  val name: String,
  val host: String,
  val port: Int = 8096,
  val useHttps: Boolean = false,
  val username: String,
  /** Shared plaintext credential; only the PostgreSQL connection configuration uses device encryption. */
  val password: String,
  val userId: String = "",
  val apiToken: String = "",
  val serverName: String = "",
  val version: String = "",
  val createdAt: Long = System.currentTimeMillis(),
  val lastConnected: Long = 0,
  val remoteId: String = "",
  val sourceKey: String = "",
  /** In-process config-save generation, guards delayed login across source switches. */
  val sourceRevision: Long = -1,
  /** Authoritative full URL, including path prefixes. Empty for a newly constructed form. */
  val configuredBaseUrl: String = "",
  val client: String = "loembyA",
  val device: String = "Android",
  val deviceId: String = "",
  val clientVersion: String = "1.0",
  val userAgent: String = "loembyA/1.0",
  val orderBy: Int = 0,
  val disabled: Boolean = false,
  val lineId: String = "",
  val lines: List<EmbyServerLine> = emptyList(),
  val reverseProxyId: String = "no",
  /** Read-only reverse_proxy_server projection. */
  val reverseProxyName: String = "",
  val reverseProxyUrl: String = "",
  /** Emby SystemInfo server id; distinct from the shared configuration row UUID. */
  val embyRemoteServerId: String = "",
  /** Optional desktop icon; only same-server HTTP URLs receive this row's identity headers. */
  val iconUrl: String = "",
) {
  val baseUrl: String
    get() = configuredBaseUrl.ifEmpty {
      val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
      "${if (useHttps) "https" else "http"}://$bracketed:$port"
    }.trimEnd('/')

  val hostUrl: String
    get() {
      val url = java.net.URI(baseUrl)
      return "${url.scheme}://${url.rawAuthority}"
    }

  val isLoggedIn: Boolean get() = apiToken.isNotEmpty() && userId.isNotEmpty()
  val authIdentity: EmbyAuthIdentity get() = EmbyAuthIdentity(client, device, deviceId, clientVersion, userAgent)
  val currentLineName: String get() = lines.firstOrNull { it.id == lineId }?.name.orEmpty()
  /** The applied server row is authoritative; a selected line is never substituted locally. */
  val connectionError: String?
    get() = when {
      disabled -> "服务器已被禁用"
      lineId.isNotEmpty() && lineId != "no" && lines.none { it.id == lineId } -> "共享配置中的当前线路不存在，请在桌面端修正"
      reverseProxyId.isNotEmpty() && reverseProxyId != "no" && reverseProxyUrl.isBlank() -> "共享配置中的反向代理引用不存在或地址为空，请在桌面端修正"
      else -> runCatching {
        embyApiBaseUrl(baseUrl)
        if (reverseProxyId.isNotEmpty() && reverseProxyId != "no") normalizeReverseProxyUrl(reverseProxyUrl)
        null
      }.getOrElse { "共享配置中的服务器或反向代理地址无效，请在桌面端修正" }
    }
  /** Full API URL: original API prefix first, then loemby reverse-prefix concatenation. */
  val effectiveBaseUrl: String get() = EmbyEndpoints.apiBaseUrl(this)

  override fun toString(): String = "EmbyServer(id=$id, remoteId=$remoteId, disabled=$disabled, credentials=[redacted])"
}

data class EmbyAuthIdentity(
  val client: String,
  val device: String,
  val deviceId: String,
  val clientVersion: String,
  val userAgent: String,
)

data class EmbyServerLine(
  val id: String,
  val name: String,
  val baseUrl: String,
  val reverseProxyId: String = "no",
)
