package app.marlboroadvance.mpvex.utils.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyRequestHeaders
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.player.PlayerHttpHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.net.URLDecoder

object HttpUtils {
  private const val TAG = "HttpUtils"
  private const val CONNECTION_TIMEOUT = 3000
  private const val READ_TIMEOUT = 3000

  private const val CALL_TIMEOUT = 3000L
  private val filenameClient by lazy {
    EmbyHttp.streaming.newBuilder()
      .connectTimeout(CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
      .callTimeout(CALL_TIMEOUT, TimeUnit.MILLISECONDS)
      .build()
  }

  suspend fun extractFilenameFromUrl(
    url: String,
    server: EmbyServer? = null,
    headers: Map<String, String> = emptyMap(),
  ): String? = withContext(Dispatchers.IO) {
    try {
      val uri = Uri.parse(url)
      val filenameFromHeaders = getFilenameFromHttpHeaders(url, server, headers)
      if (filenameFromHeaders != null) {
        Log.d(TAG, "Extracted filename from headers: $filenameFromHeaders")
        return@withContext filenameFromHeaders
      }
      val filenameFromUrl = extractFilenameFromUrlPath(uri)
      Log.d(TAG, "Extracted filename from URL: $filenameFromUrl")
      return@withContext filenameFromUrl
    } catch (e: Exception) {
      Log.e(TAG, "Error extracting filename")
      null
    }
  }

  internal fun getFilenameFromHttpHeaders(
    url: String,
    server: EmbyServer? = null,
    headers: Map<String, String> = emptyMap(),
    client: OkHttpClient = filenameClient,
  ): String? = try {
    val safeHeaders = PlayerHttpHeaders.forUrl(url, headers, server)
    val builder = server?.let { EmbyRequestHeaders.request(it, url, safeHeaders) }
      ?: Request.Builder().url(url).apply {
        safeHeaders.forEach { (name, value) -> header(name, value) }
      }
    client.newCall(builder.head().build()).execute().use { response ->
      if (!response.isSuccessful) {
        Log.w(TAG, "Filename HEAD HTTP ${response.code}")
        null
      } else {
        response.header("Content-Disposition")?.let(::parseContentDisposition)
          ?: response.header("Content-Location")?.let { extractFilenameFromUrlPath(Uri.parse(it)) }
      }
    }
  } catch (_: Exception) {
    // Exceptions can contain credential-bearing URLs. The path fallback is enough here.
    Log.w(TAG, "Filename HEAD unavailable")
    null
  }

  private fun parseContentDisposition(contentDisposition: String): String? {
    try {
      val filenameStarPattern = Regex("""filename\*=(?:UTF-8|utf-8)?''?"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
      filenameStarPattern.find(contentDisposition)?.let { match ->
        val encodedFilename = match.groupValues[1]
        return try {
          URLDecoder.decode(encodedFilename, "UTF-8")
        } catch (e: Exception) {
          encodedFilename
        }
      }

      val filenamePattern = Regex("""filename="?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
      filenamePattern.find(contentDisposition)?.let { match ->
        return match.groupValues[1].trim()
      }

      return null
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing Content-Disposition: ${e.message}")
      return null
    }
  }

  private fun extractFilenameFromUrlPath(uri: Uri): String {
    val path = uri.path ?: return uri.host ?: "Network Stream"
    val lastSegment = path.substringAfterLast("/")

    if (lastSegment.isNotBlank()) {
      return try {
        URLDecoder.decode(lastSegment, "UTF-8")
          .substringBefore("?")
          .substringBefore("#")
          .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
      } catch (e: Exception) {
        lastSegment.substringBefore("?").substringBefore("#")
      }
    }

    return uri.host ?: "Network Stream"
  }

  fun isNetworkStream(uri: Uri?): Boolean {
    if (uri == null) return false
    val scheme = uri.scheme?.lowercase()
    return scheme in listOf("http", "https", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh", "ftp", "ftps")
  }

  /**
   * Extracts the referer domain from a Uri.
   * Returns the full origin (scheme + host + port) to be used as Referer header.
   * 
   * @param uri The Uri to extract the referer from
   * @return The referer origin string, or null if extraction fails
   */
  fun extractRefererDomain(uri: Uri?): String? {
    if (uri == null) return null
    
    return try {
      val scheme = uri.scheme ?: return null
      val host = uri.host ?: return null
      val port = uri.port
      
      // Build the referer origin
      if (port != -1 && port != 80 && port != 443) {
        // Include non-standard port
        "$scheme://$host:$port"
      } else {
        // Standard port or no port specified
        "$scheme://$host"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error extracting referer domain: ${e.message}")
      null
    }
  }
}
