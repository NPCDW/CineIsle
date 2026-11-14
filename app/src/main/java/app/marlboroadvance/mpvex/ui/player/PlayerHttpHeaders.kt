package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.domain.emby.EmbyEndpoints
import app.marlboroadvance.mpvex.domain.emby.EmbyRequestHeaders
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Shared intent-header codec and URL-scoped policy for both playback cores and helpers. */
object PlayerHttpHeaders {
  /** Complete name/value pairs only; header names are case-insensitive, last value wins. */
  fun parse(pairs: Array<String>?): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    pairs?.asList()?.chunked(2)?.filter { it.size == 2 }?.forEach { (name, value) ->
      if (name.isNotBlank()) put(headers, name, value)
    }
    return headers
  }

  fun toPairs(headers: Map<String, String>): Array<String> =
    headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()

  /** Keep the bound row's identity across media URLs; unrelated local proxies stay credential-free. */
  fun forUrl(
    url: String,
    headers: Map<String, String> = emptyMap(),
    server: EmbyServer? = null,
  ): Map<String, String> {
    val target = url.toHttpUrlOrNull() ?: return emptyMap()
    val local = target.host == "localhost" || target.host.endsWith(".localhost") ||
      target.host.startsWith("127.") || target.host == "::1" || target.host == "0.0.0.0"
    if (local && (server == null || !EmbyEndpoints.belongsToServer(server, url))) return emptyMap()
    val result = LinkedHashMap<String, String>()
    headers.forEach { (name, value) ->
      if (!name.startsWith("X-Emby-", ignoreCase = true) &&
        (server == null || (!name.equals("User-Agent", true) && !name.equals("Referer", true)))
      ) put(result, name, value)
    }
    server?.let { row ->
      EmbyRequestHeaders.headers(row, url).forEach { (name, value) -> put(result, name, value) }
    }
    return result
  }

  /** mpv uses a separate UA property; every other field belongs in http-header-fields. */
  fun mpvHeaderFields(headers: Map<String, String>): String =
    headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
      .map { (name, value) -> "$name: ${value.replace("\\", "\\\\").replace(",", "\\,")}" }
      .joinToString(",")

  private fun put(headers: MutableMap<String, String>, name: String, value: String) {
    headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
    headers[name] = value
  }
}
