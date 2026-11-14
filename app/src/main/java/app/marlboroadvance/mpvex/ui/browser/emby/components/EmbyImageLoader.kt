package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyRequestHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Emby 封面图加载器。
 *
 * 项目不引入第三方图片库，这里用已有的 OkHttp + BitmapFactory 实现，三层结构：
 *
 *  1. 内存 LRU（进程内，列表滚动不重复解码）；
 *  2. **磁盘缓存**（[diskDir]/<cache-key>.img，30 天新鲜期，容量上限
 *     [DISK_LIMIT_BYTES]，超限按最旧淘汰）—— 跨启动复用，减少 Emby 服务端的
 *     图片缩放开销（每个封面都是一次服务端缩放 + 网络传输）；
 *  3. 网络（OkHttp，缩放失败自动退回原图重试）。
 *
 * 磁盘层需要 [init] 注入 Context（App.onCreate 里调一次）；未 init 时磁盘层静默
 * 关闭，行为退化为纯内存缓存。
 */
object EmbyImageLoader {
  private const val TAG = "EmbyImageLoader"

  /**
   * 走 [EmbyHttp.images]（图片档：超时短、并发高，且与其余 5 个客户端**共享连接池**）。
   *
   * 原来这里自建一个（connect 10s / read 20s），现在档位挪到 [EmbyHttp.images] 统一管。
   */
  private val client: OkHttpClient = EmbyHttp.images

  /** 缓存上限取可用堆内存的 1/8 */
  private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtMost(Int.MAX_VALUE),
  ) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  // ── 磁盘缓存 ──

  /** 磁盘缓存目录名（cacheDir 下） */
  private const val DISK_DIR_NAME = "emby_image_cache"

  /** 磁盘缓存容量上限 */
  private const val DISK_LIMIT_BYTES = 256L * 1024 * 1024

  @Volatile
  private var diskDir: File? = null

  private val trimMutex = Mutex()
  private val trimRequested = AtomicBoolean(false)
  private val trimRunning = AtomicBoolean(false)
  private val diskBytesEstimate = AtomicLong(-1L)
  private val identityFingerprints = Collections.synchronizedMap(WeakHashMap<EmbyServer, String>())
  private val HEX = "0123456789abcdef".toCharArray()

  /** App.onCreate 注入 Context，启用磁盘缓存层 */
  fun init(context: Context) {
    diskDir = File(context.cacheDir, DISK_DIR_NAME)
    diskBytesEstimate.set(-1L)
  }

  /** Shared by bitmap/Coil caches and in-flight downloads; never identifies a row from a URL. */
  fun cacheKey(server: EmbyServer, url: String): String = sha256(identityFingerprint(server), url)

  private fun identityFingerprint(server: EmbyServer): String = synchronized(identityFingerprints) {
    identityFingerprints[server] ?: sha256(
      server.sourceKey, server.sourceRevision.toString(), server.remoteId, server.id.toString(),
      server.username, server.password, server.userId, server.apiToken,
      server.client, server.device, server.deviceId, server.clientVersion, server.userAgent,
      server.baseUrl, server.reverseProxyId, server.reverseProxyUrl,
    ).also { identityFingerprints[server] = it }
  }

  internal fun sha256(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
      val bytes = part.toByteArray(Charsets.UTF_8)
      updateLength(digest, bytes.size)
      digest.update(bytes)
    }
    val bytes = digest.digest()
    return CharArray(bytes.size * 2).also { output ->
      bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = HEX[value ushr 4]
        output[index * 2 + 1] = HEX[value and 0x0f]
      }
    }.concatToString()
  }

  private fun updateLength(digest: MessageDigest, size: Int) {
    digest.update((size ushr 24).toByte())
    digest.update((size ushr 16).toByte())
    digest.update((size ushr 8).toByte())
    digest.update(size.toByte())
  }

  /** 磁盘缓存当前占用（字节），设置页展示用 */
  fun diskCacheSizeBytes(): Long =
    diskDir?.listFiles()?.sumOf { it.length() }?.also(diskBytesEstimate::set) ?: 0L

  /** 清空磁盘缓存 + 内存缓存，返回清除的字节数 */
  fun clearDiskCache(): Long {
    var freed = 0L
    diskDir?.listFiles()?.forEach { f ->
      freed += f.length()
      runCatching { f.delete() }
    }
    cache.evictAll()
    NetworkImageCachePolicy.clear()
    diskBytesEstimate.set(0L)
    return freed
  }

  /** 容量超限时按「最旧优先」淘汰，直到回到上限以内。完整扫描串行且合并重复请求。 */
  private suspend fun trimDisk(dir: File) {
    trimRequested.set(true)
    if (!trimRunning.compareAndSet(false, true)) return
    do {
      try {
        trimMutex.lock()
        try {
          while (trimRequested.getAndSet(false)) {
            val files = dir.listFiles() ?: continue
            var total = files.sumOf { it.length() }
            diskBytesEstimate.set(total)
            if (total <= DISK_LIMIT_BYTES) continue
            files.sortedBy { it.lastModified() }.forEach { f ->
              if (total <= DISK_LIMIT_BYTES) return@forEach
              val len = f.length()
              if (f.delete()) total -= len
            }
            diskBytesEstimate.set(total)
          }
        } finally {
          trimMutex.unlock()
        }
      } finally {
        trimRunning.set(false)
      }
    } while (trimRequested.get() && trimRunning.compareAndSet(false, true))
  }

  // ── 下载节流 ──

  /**
   * 同时进行的封面下载上限。
   *
   * 照片库动辄 10 万张，Lazy 网格滚动时若不限并发，会瞬间向 Emby 服务器打出
   * 几十个图片请求（每个都是一次服务端缩放 + 传输），小服务器直接被打满。
   * 4 同时落在 [EmbyHttp.images] 连接池与 OkHttp 默认 maxRequestsPerHost=5 之内。
   */
  private const val MAX_CONCURRENT_DOWNLOADS = 4

  /** 下载并发闸门 */
  private val downloadGate = Semaphore(MAX_CONCURRENT_DOWNLOADS)

  /**
   * URL → 在途下载（同一 URL 的并发请求合并成一次网络往返）。
   *
   * 典型场景：快速回滑 / 重组导致同一张卡片在第一次下载完成前再次请求，
   * 后来者直接等待已有那次下载，不会重复打服务器。
   */
  private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

  /**
   * 加载图片；失败返回 null。
   *
   * @param maxWidth 客户端解码的目标宽度上限，>0 时按需降采样，避免大图占用过多内存。
   *                 即使服务端已按尺寸返回也建议给一个上限，因为「服务端缩放失败→取原图」
   *                 这条兜底路径拿到的可能是很大的原图。
   */
  suspend fun load(server: EmbyServer, url: String, maxWidth: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
    val fresh = EmbyHttp.sessionServer(server)
    loadResolved(fresh, url, maxWidth)
  }

  private suspend fun loadResolved(server: EmbyServer, url: String, maxWidth: Int): Bitmap? {
    val key = cacheKey(server, url)
    val now = System.currentTimeMillis()
    if (NetworkImageCachePolicy.memoryFresh(key, now)) {
      cache.get(key)?.let { return it }
    } else {
      cache.remove(key)
      NetworkImageCachePolicy.forget(key)
    }

    val diskFile = diskDir?.let { File(it, "$key.img") }
    if (diskFile != null && diskFile.isFile) {
      val writtenAt = diskFile.lastModified()
      val bytes = if (NetworkImageCachePolicy.fresh(writtenAt, now)) {
        runCatching { diskFile.readBytes() }.getOrNull()
      } else null
      val bitmap = bytes?.let { decode(it, maxWidth) }
      if (bitmap != null) {
        cache.put(key, bitmap)
        NetworkImageCachePolicy.record(key, writtenAt)
        return bitmap
      }
      deleteDiskEntry(diskFile, key)
    }

    val bytes = downloadBytesResolved(server, url) ?: return null
    if (NetworkImageCachePolicy.memoryFresh(key)) {
      cache.get(key)?.let { return it }
    }

    val bitmap = decode(bytes, maxWidth)
    if (bitmap == null) {
      deleteDiskEntry(diskFile, key)
      Log.w(TAG, "封面解码失败（bytes=${bytes.size}）")
      return null
    }

    val writtenAt = System.currentTimeMillis()
    if (diskFile != null) {
      val oldLength = diskFile.length()
      val written = runCatching {
        diskFile.parentFile?.mkdirs()
        diskFile.writeBytes(bytes)
        if (!diskFile.setLastModified(writtenAt)) throw IllegalStateException("Unable to timestamp image cache")
        true
      }.getOrElse {
        runCatching { diskFile.delete() }
        false
      }
      if (written) {
        val previous = diskBytesEstimate.get()
        val total = if (previous < 0) diskFile.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L
        else diskBytesEstimate.addAndGet(diskFile.length() - oldLength)
        diskBytesEstimate.set(total)
        if (total > DISK_LIMIT_BYTES) diskFile.parentFile?.let { trimDisk(it) }
      }
    }
    cache.put(key, bitmap)
    NetworkImageCachePolicy.record(key, writtenAt)
    return bitmap
  }

  private fun deleteDiskEntry(file: File?, key: String) {
    if (file != null) {
      val length = file.length()
      if (runCatching { file.delete() }.getOrDefault(false)) {
        diskBytesEstimate.updateAndGet { current -> if (current < 0) current else (current - length).coerceAtLeast(0) }
      }
    }
    cache.remove(key)
    NetworkImageCachePolicy.forget(key)
  }

  /**
   * **同步**取内存缓存里的位图（没有就返回 null，不碰磁盘、不发请求）。
   *
   * 存在的意义是消除「封面闪一下」：图片组件（[app.marlboroadvance.mpvex.ui.browser
   * .emby.components.EmbyImage]）以往第一帧永远是占位图标，要等一次协程调度 +
   * `withContext(IO)` 才拿到图 —— 哪怕这张图就在内存缓存里（筛选 / 搜索后卡片重新
   * 组合是最典型的场景）。组合时先同步看一眼缓存，命中就**第一帧直接上图**，
   * 中间那帧占位图标没有了，闪也就没有了。
   *
   * ⚠️ 只查内存层：磁盘 / 网络都涉及 IO，绝不能在组合阶段同步做。
   */
  fun peek(server: EmbyServer, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    return runCatching {
      val key = cacheKey(EmbyHttp.sessionServer(server), url)
      if (NetworkImageCachePolicy.memoryFresh(key)) cache.get(key) else {
        cache.remove(key)
        NetworkImageCachePolicy.forget(key)
        null
      }
    }.getOrNull()
  }

  /**
   * 取图片字节（带节流）：同一 URL 合并为一次下载，下载数受 [downloadGate] 限制。
   *
   * 等待者**不占**信号量（先在 [inFlight] 等，等不到才自己去下载）——
   * 否则「等下载的人占着坑、下载的人没坑可用」会死锁。
   *
   * ⚠️ 原来这里是 private。照片链路换成 Coil 之后（EmbyPhotoFetcher）也要走它：
   * 节流、双鉴权、缩放失败退回原图这套逻辑**只该有一份实现**（红线 58），
   * 所以对外开放而不是在照片那边另写一个下载。
   */
  suspend fun downloadBytes(server: EmbyServer, url: String): ByteArray? =
    downloadBytesResolved(EmbyHttp.sessionServer(server), url)

  private suspend fun downloadBytesResolved(server: EmbyServer, url: String): ByteArray? {
    val key = cacheKey(server, url)
    inFlight[key]?.let { return it.await() }
    val deferred = CompletableDeferred<ByteArray?>()
    val existing = inFlight.putIfAbsent(key, deferred)
    if (existing != null) return existing.await()
    try {
      val bytes = downloadGate.withPermit {
        withContext(Dispatchers.IO) { fetch(server, url) ?: fetchOriginal(server, url) }
      }
      deferred.complete(bytes)
      return bytes
    } catch (e: CancellationException) {
      // 发起方被取消（滑走了）：别让等待者吊死在永不完结的 Deferred 上
      deferred.complete(null)
      throw e
    } catch (e: Exception) {
      deferred.complete(null)
      throw e
    } finally {
      inFlight.remove(key, deferred)
    }
  }

  /** 发起一次请求取字节；失败返回 null（内部已打日志）。 */
  private fun fetch(server: EmbyServer, url: String): ByteArray? = runCatching {
    client.newCall(EmbyRequestHeaders.request(server, url).build()).execute().use { response ->
      if (!response.isSuccessful) {
        // 400/401/403 多为鉴权问题，404 多为确实没有这张图，5xx 多为服务端处理失败
        Log.w(TAG, "加载封面失败 HTTP ${response.code}")
        return@use null
      }
      response.body.bytes()
    }
  }.onFailure {
    Log.w(TAG, "加载封面异常")
  }.getOrNull()

  /**
   * 兜底：去掉 maxWidth / maxHeight / quality 再取一次原图。
   *
   * 有些 Emby 服务端只在「需要缩放」时才失败（500），取原图反而是好的；
   * 客户端 [decode] 会做降采样，所以取原图没有额外代价。
   */
  private fun fetchOriginal(server: EmbyServer, url: String): ByteArray? {
    val raw = stripResizeParams(url)
    if (raw == url) return null
    Log.w(TAG, "改用原图重试")
    return fetch(server, raw)
  }

  /** 去掉图片缩放相关的 query 参数；没有这些参数时原样返回。 */
  private fun stripResizeParams(url: String): String {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return url
    val parts = query.split('&')
    val kept = parts.filterNot { param ->
      param.startsWith("maxWidth=") ||
        param.startsWith("maxHeight=") ||
        param.startsWith("quality=")
    }
    if (kept.size == parts.size) return url
    val base = url.substringBefore('?')
    return if (kept.isEmpty()) base else "$base?" + kept.joinToString("&")
  }

  private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
    // HEIC/AVIF（iPhone 照片常见）BitmapFactory 解不了，走 ImageDecoder；
    // 仍然解不了就返回 null，由调用方改用服务端 format=jpg 兜底
    sniffModernMime(bytes)?.let { mime -> return decodeModern(bytes, mime, maxWidth) }
    if (maxWidth <= 0) {
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // 先只解析边界拿到原始尺寸，再按目标宽度计算采样率
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val width = bounds.outWidth
    if (width <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxWidth) {
      sampleSize *= 2
    }

    return BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
  }

  // ── HEIC / AVIF（现代图片格式） ──

  /**
   * 识别 HEIC / AVIF（ISO-BMFF 容器，ftyp box 的 brand 字段）。
   *
   * iPhone 拍的照片默认就是 HEIC；Emby 扫库后按原格式存，取回来的字节流不是
   * JPEG/PNG。返回 `image/heic` / `image/avif`；不是这两种返回 null
   * （JPEG/PNG/WebP/GIF 一概不认，走原有 BitmapFactory 路径，零改动）。
   */
  private fun sniffModernMime(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
      bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
    ) {
      return null
    }
    // 兼容 brand 列表是一串 4 字节 chunk，从偏移 8 扫到 64 足够覆盖常见文件
    var isAvif = false
    var isHeic = false
    var i = 8
    val limit = minOf(bytes.size, 64)
    while (i + 4 <= limit) {
      when (String(bytes, i, 4, Charsets.US_ASCII)) {
        "avif", "avis" -> isAvif = true
        "heic", "heix", "hevc", "hevx", "mif1", "mif2", "msf1" -> isHeic = true
      }
      i += 4
    }
    return when {
      isAvif -> "image/avif"
      isHeic -> "image/heic"
      else -> null
    }
  }

  /**
   * 用 [ImageDecoder] 解 HEIC/AVIF（API 28+ 原生支持 HEIF；AVIF 要 API 31+）。
   *
   * 不支持 / 解失败一律返回 null —— 照片链路的调用方（[EmbyImage] 的
   * fallbackUrl）会改用服务端 `format=jpg` 重新取图，失败不会暴露给用户。
   */
  private fun decodeModern(bytes: ByteArray, mime: String, maxWidth: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      !ImageDecoder.isMimeTypeSupported(mime)
    ) {
      return null
    }
    return try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        // setTargetSize 会放大图，只在原图确实更大时缩
        if (maxWidth > 0 && info.size.width > maxWidth) {
          val height = (maxWidth.toLong() * info.size.height / info.size.width).toInt()
          decoder.setTargetSize(maxWidth, height.coerceAtLeast(1))
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "$mime 解码失败（${e.message}）")
      null
    }
  }

  /**
   * 把服务端返回的图片字节转码成 JPEG（「另存为 JPEG」用）。
   *
   * 已是 JPEG 的调用方应直接写原字节（见 [isJpeg]），不必过这里。解码失败返回 null。
   *
   * @param rotationDegrees 顺时针旋转角度（90 的整数倍）。浏览器里用户转过图之后
   *   「另存为 JPEG」要存成转过的样子，所以转码时一并旋转；0 表示不转。
   */
  suspend fun transcodeToJpeg(bytes: ByteArray, rotationDegrees: Int = 0): ByteArray? =
    withContext(Dispatchers.IO) {
      val decoded = decode(bytes, 0) ?: return@withContext null
      val bitmap = rotateBitmap(decoded, rotationDegrees)
      val out = ByteArrayOutputStream()
      runCatching {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
      }.getOrNull()
    }

  /**
   * 按 90° 的整数倍旋转位图；0（或非 90 倍数归一化后为 0）时**原样返回不复制**。
   *
   * 刻意不 recycle 原图：调用方那边可能还有引用（宁可多占一次内存，
   * 也别整出「用已回收的 Bitmap」这种偶发崩溃）。
   */
  private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return src
    val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
    return runCatching {
      Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }.getOrNull() ?: src
  }

  /** Magic bytes accepted by the Android photo decoders used by this loader and Coil. */
  internal fun hasSupportedImageSignature(bytes: ByteArray): Boolean {
    fun starts(vararg values: Int): Boolean =
      bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 0xff) == values[it] }
    return isJpeg(bytes) || starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
      starts(0x47, 0x49, 0x46, 0x38) || starts(0x42, 0x4d) ||
      (starts(0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))) ||
      sniffModernMime(bytes) != null
  }

  /** Magic bytes 判定是否已是 JPEG（FF D8 FF）；「另存为 JPEG」时省一次转码。 */
  fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
      bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

}
