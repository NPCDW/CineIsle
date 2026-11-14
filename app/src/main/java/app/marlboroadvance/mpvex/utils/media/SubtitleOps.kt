package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import app.marlboroadvance.mpvex.domain.emby.EmbyEndpoints
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyRequestHeaders
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.player.openContentFd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads explicit subtitle sources only; never scans folders or connects to file protocols. */
object SubtitleOps {
  private const val MAX_SUBTITLE_BYTES = 16L * 1024 * 1024
  private const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000
  private val subtitleClient by lazy {
    EmbyHttp.streaming.newBuilder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .callTimeout(15, TimeUnit.SECONDS)
      .build()
  }

  suspend fun readablePath(
    context: Context,
    uri: Uri,
    server: EmbyServer? = null,
  ): String? = withContext(Dispatchers.IO) {
    when (uri.scheme?.lowercase(java.util.Locale.ROOT)) {
      "content" -> uri.openContentFd(context)
      "http", "https" -> {
        val url = uri.toString()
        if (server != null && EmbyEndpoints.belongsToServer(server, url)) {
          // Never reuse the video's native HTTP headers for a different subtitle URL.
          downloadToFile(url, server, File(context.cacheDir, "emby_subtitles"))?.absolutePath
        } else url
      }
      else -> null
    }
  }

  /** Bounded, row-scoped fetch; injection keeps tests entirely off the network. */
  internal suspend fun downloadToFile(
    url: String,
    server: EmbyServer,
    directory: File,
    client: OkHttpClient = subtitleClient,
  ): File? {
    if (!EmbyEndpoints.belongsToServer(server, url)) return null
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
    directory.listFiles()?.filter { it.name.startsWith("sub-") && it.lastModified() < cutoff }
      ?.forEach { it.delete() }
    val extension = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
      ?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
      ?.takeIf { it in setOf("srt", "ass", "ssa", "vtt", "sub", "sup", "txt") } ?: "srt"
    val file = File.createTempFile("sub-", ".$extension", directory)
    try {
      val request = EmbyRequestHeaders.request(server, url).get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Subtitle HTTP ${response.code}")
        val body = response.body
        if (body.contentLength() > MAX_SUBTITLE_BYTES) throw IOException("Subtitle too large")
        body.byteStream().use { input ->
          file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              total += count
              if (total > MAX_SUBTITLE_BYTES) throw IOException("Subtitle too large")
              output.write(buffer, 0, count)
            }
            if (total == 0L) throw IOException("Empty subtitle")
          }
        }
      }
      return file
    } catch (error: Exception) {
      file.delete()
      if (error is kotlinx.coroutines.CancellationException) throw error
      // URLs and exception messages may contain credentials; don't log them.
      return null
    }
  }
}
