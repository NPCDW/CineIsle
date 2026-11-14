package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.utils.cache.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 封面取色缓存；同一来源、同一地址的消费者复用结果。 */
internal object EmbyColorCache {
  private val dominant = LruCache<String, Color>(128)

  @Synchronized fun getDominant(key: String): Color? = dominant[key]

  @Synchronized fun putDominant(key: String, color: Color) {
    dominant[key] = color
  }
}

/** 旧详情页强调色；电影等既有消费者继续沿用原来的量化和柔化规则。 */
@Composable
fun rememberDominantColor(
  url: String?,
  server: EmbyServer? = LocalEmbyImageServer.current,
): Color? {
  val resolvedServer = remember(server) {
    server?.let { runCatching { EmbyHttp.sessionServer(it) }.getOrNull() }
  }
  val key = if (resolvedServer != null && !url.isNullOrBlank()) EmbyImageLoader.cacheKey(resolvedServer, url) else null
  val color by produceState<Color?>(initialValue = key?.takeIf(NetworkImageCachePolicy::memoryFresh)?.let(EmbyColorCache::getDominant), key1 = key) {
    value = key?.takeIf(NetworkImageCachePolicy::memoryFresh)?.let(EmbyColorCache::getDominant)
    if (resolvedServer == null || url.isNullOrBlank() || key == null || value != null) return@produceState
    try {
      extractDominantColor(url, resolvedServer)?.let { extracted ->
        EmbyColorCache.putDominant(key, extracted)
        value = extracted
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      value = null
    }
  }
  return color
}

private suspend fun extractDominantColor(url: String, server: EmbyServer): Color? {
  val source = EmbyImageLoader.load(server, url, 48) ?: return null
  return withContext(Dispatchers.Default) {
    var readable = source
    var ownsReadable = false
    var small: Bitmap? = null
    try {
      if (source.config == Bitmap.Config.HARDWARE) {
        readable = source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
        ownsReadable = true
      }
      small = runCatching { Bitmap.createScaledBitmap(readable, 24, 24, true) }.getOrNull() ?: readable
      val counts = HashMap<Int, Int>()
      for (y in 0 until small.height) {
        for (x in 0 until small.width) {
          val pixel = small.getPixel(x, y)
          if ((pixel ushr 24) and 0xFF < 128) continue
          val r = (pixel shr 16) and 0xFF
          val g = (pixel shr 8) and 0xFF
          val b = pixel and 0xFF
          val max = maxOf(r, g, b)
          val min = minOf(r, g, b)
          if (max < 32 || min > 232 || max - min < 20) continue
          val quantized = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
          counts[quantized] = (counts[quantized] ?: 0) + 1
        }
      }
      val best = counts.maxByOrNull { it.value }?.key ?: return@withContext null
      soften(Color(((best shr 8) and 0xF) * 17, ((best shr 4) and 0xF) * 17, (best and 0xF) * 17))
    } finally {
      if (small != null && small !== readable) small.recycle()
      if (ownsReadable) readable.recycle()
    }
  }
}

private fun soften(color: Color): Color {
  val mix = 0.45f
  val gain = 1.18f
  return Color(
    red = ((color.red * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    green = ((color.green * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    blue = ((color.blue * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    alpha = 1f,
  )
}
