package app.marlboroadvance.mpvex.domain.emby

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Immutable URL value. No persisted alternate endpoints, network probes or automatic switching. */
data class EmbyEndpoint(
  val host: String,
  val port: Int,
  val useHttps: Boolean,
  val configuredBaseUrl: String = "",
) {
  val baseUrl: String
    get() = configuredBaseUrl.ifBlank {
      val authority = if (':' in host && !host.startsWith('[')) "[$host]" else host
      "${if (useHttps) "https" else "http"}://$authority:$port"
    }.trimEnd('/')
  val apiBaseUrl: String get() = embyApiBaseUrl(baseUrl)

  companion object {
    fun fromUrl(url: String): EmbyEndpoint {
      val parsed = url.toHttpUrlOrNull() ?: throw EmbyApiException(0, "服务器地址无效")
      if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
        throw EmbyApiException(0, "服务器地址无效")
      }
      return EmbyEndpoint(parsed.host, parsed.port, parsed.isHttps, url.trimEnd('/'))
    }
  }
}

/** Bare origins retain /emby; an explicit path already is the API prefix. Preserve raw URLs. */
internal fun embyApiBaseUrl(baseUrl: String): String {
  val parsed = baseUrl.toHttpUrlOrNull() ?: throw EmbyApiException(0, "服务器地址无效")
  EmbyEndpoint.fromUrl(baseUrl)
  val base = baseUrl.trimEnd('/')
  return if (parsed.encodedPath.trim('/').isEmpty()) "$base/emby" else base
}

/** Matches loemby's normalize_url: trim and append one slash only if missing. */
internal fun normalizeReverseProxyUrl(value: String): String {
  val url = value.trim()
  EmbyEndpoint.fromUrl(url)
  return if (url.endsWith('/')) url else "$url/"
}

fun EmbyServer.requireSupportedConnection() {
  connectionError?.let { throw EmbyApiException(0, it) }
}

fun EmbyServer.primaryEndpoint(): EmbyEndpoint {
  requireSupportedConnection()
  return EmbyEndpoint.fromUrl(effectiveBaseUrl)
}

/** Pure URL helpers. Only emby_server's already-applied base_url/reverse_proxy_id drive routing. */
object EmbyEndpoints {
  fun apiBaseUrl(server: EmbyServer): String {
    server.requireSupportedConnection()
    return reverse(server, embyApiBaseUrl(server.baseUrl))
  }

  private fun reverse(server: EmbyServer, original: String): String =
    if (server.reverseProxyId.isNotEmpty() && server.reverseProxyId != "no") {
      normalizeReverseProxyUrl(server.reverseProxyUrl) + original
    } else original

  /** Relative API paths and same-origin absolute URLs are routed; external CDN URLs stay untouched. */
  fun resolve(server: EmbyServer, url: String): String {
    server.requireSupportedConnection()
    if (isReverseUrl(server, url)) return url
    val absolute = url.toHttpUrlOrNull()
    val origin = server.baseUrl.toHttpUrlOrNull() ?: throw EmbyApiException(0, "服务器地址无效")
    val relative = if (absolute != null) {
      if (absolute.scheme != origin.scheme || absolute.host != origin.host || absolute.port != origin.port) return url
      absolute.encodedPath + (absolute.encodedQuery?.let { "?$it" } ?: "") + (absolute.encodedFragment?.let { "#$it" } ?: "")
    } else if (url.startsWith("//")) {
      return resolve(server, "${origin.scheme}:$url")
    } else url
    val base = embyApiBaseUrl(server.baseUrl)
    val prefix = base.toHttpUrlOrNull()!!.encodedPath.trimEnd('/')
    val path = "/" + relative.trimStart('/')
    val pathOnly = path.substringBefore('?').substringBefore('#')
    val original = if (pathOnly == prefix || pathOnly.startsWith("$prefix/")) {
      server.hostUrl + path
    } else base + if (path.startsWith("/emby/", true)) path.substring(5) else path
    return reverse(server, original)
  }

  /** Rewrite only redirects back to this row's API; CDN and directory-relative locations keep HTTP semantics. */
  internal fun resolveRedirect(server: EmbyServer, requestUrl: String, location: String): String {
    val request = requestUrl.toHttpUrlOrNull() ?: return location
    val target = request.resolve(location) ?: return location
    if (isReverseUrl(server, target.toString())) return location
    val rootRelative = location.startsWith('/') && !location.startsWith("//")
    val absolute = location.toHttpUrlOrNull() != null || location.startsWith("//")
    if (!rootRelative && !absolute) return location
    if (rootRelative) {
      if (!belongsToServer(server, requestUrl) ||
        (!isReverseUrl(server, requestUrl) && !isApiPath(server, request.encodedPath))) return location
    } else if (!belongsToServer(server, target.toString())) return location
    if (!isApiPath(server, target.encodedPath)) return location
    val path = target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: "") +
      (target.encodedFragment?.let { "#$it" } ?: "")
    return resolve(server, if (rootRelative) path else target.toString())
  }

  private fun isApiPath(server: EmbyServer, path: String): Boolean {
    val prefix = embyApiBaseUrl(server.baseUrl).toHttpUrlOrNull()!!.encodedPath.trimEnd('/')
    if (path == prefix || path.startsWith("$prefix/")) return true
    val endpoint = path.lowercase().removePrefix("/").removePrefix("emby/").substringBefore('/')
    return endpoint in API_ROOTS
  }

  private val API_ROOTS = setOf("items", "videos", "audio", "users", "sessions", "system", "persons",
    "shows", "movies", "genres", "studios", "artists", "musicgenres", "years", "search", "collections",
    "displaypreferences", "scheduledtasks", "notifications", "branding", "plugins")

  private fun isReverseUrl(server: EmbyServer, url: String): Boolean =
    server.reverseProxyId.isNotEmpty() && server.reverseProxyId != "no" &&
      url.startsWith(normalizeReverseProxyUrl(server.reverseProxyUrl) + server.hostUrl + "/")

  /** Whether it is safe to attach this server's API token to a URL (never to an external CDN). */
  fun belongsToServer(server: EmbyServer, url: String): Boolean {
    if (isReverseUrl(server, url)) return true
    val original = server.baseUrl.toHttpUrlOrNull() ?: return false
    val target = url.toHttpUrlOrNull() ?: return false
    return original.scheme == target.scheme && original.host == target.host && original.port == target.port
  }

  fun hostUrl(server: EmbyServer): String {
    server.requireSupportedConnection()
    return reverse(server, server.hostUrl)
  }
}
