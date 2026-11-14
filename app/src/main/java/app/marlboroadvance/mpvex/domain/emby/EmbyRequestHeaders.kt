package app.marlboroadvance.mpvex.domain.emby

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** One row-scoped identity policy for API, images, subtitles and every media redirect hop. */
object EmbyRequestHeaders {
  private val identityHeaders = listOf(
    "X-Emby-Token", "X-Emby-Client", "X-Emby-Device-Name", "X-Emby-Device-Id", "X-Emby-Client-Version",
    "X-Emby-Authorization", "X-Emby-Device", // Also strip the obsolete device-name alias from older callers.
  )

  fun headers(server: EmbyServer, url: String, includeReferer: Boolean = true): Map<String, String> =
    headersForServer(EmbyHttp.sessionServer(server), url, includeReferer)

  fun request(
    server: EmbyServer,
    url: String,
    extraHeaders: Map<String, String> = emptyMap(),
  ): Request.Builder {
    val fresh = EmbyHttp.sessionServer(server)
    fresh.requireSupportedConnection()
    val builder = Request.Builder().url(url).tag(EmbyServer::class.java, fresh)
    extraHeaders.forEach { (name, value) -> builder.header(name, value) }
    return route(builder.build(), fresh).newBuilder()
  }

  /** Run again as a network interceptor: OkHttp redirects can remove or inherit request headers. */
  internal fun route(request: Request, server: EmbyServer): Request {
    val url = request.url.toString()
    val own = EmbyEndpoints.belongsToServer(server, url)
    val builder = request.newBuilder().tag(EmbyServer::class.java, server)
    identityHeaders.forEach(builder::removeHeader)
    builder.removeHeader("User-Agent").removeHeader("Referer")
    headersForServer(server, url, includeReferer = true).forEach { (name, value) -> builder.header(name, value) }
    // Never introduce api_key on header-only requests or change a CDN's own signed query.
    if (own && request.url.queryParameter("api_key") != null) {
      builder.url(request.url.newBuilder().setQueryParameter("api_key", server.apiToken).build())
    }
    return builder.build()
  }

  /** Keep the bound identity on CDN redirects too; unrelated local proxies are exempt. */
  private fun headersForServer(server: EmbyServer, url: String, includeReferer: Boolean): Map<String, String> {
    val own = EmbyEndpoints.belongsToServer(server, url)
    val target = url.toHttpUrlOrNull() ?: return emptyMap()
    // A loopback Emby (including a configured reverse proxy) is valid; only unrelated local proxies are exempt.
    if (!own && (target.host.equals("localhost", true) || target.host.endsWith(".localhost", true) ||
        target.host == "::1" || target.host.startsWith("127."))) return emptyMap()
    return buildMap {
      put("User-Agent", server.userAgent)
      put("X-Emby-Token", server.apiToken) // Empty is meaningful, including before authentication.
      put("X-Emby-Client", server.client)
      put("X-Emby-Device-Name", server.device)
      put("X-Emby-Device-Id", server.deviceId)
      put("X-Emby-Client-Version", server.clientVersion)
      put("X-Emby-Authorization", EmbyClient.authorizationHeader(server.authIdentity))
      if (includeReferer) put("Referer", server.hostUrl)
    }
  }
}
