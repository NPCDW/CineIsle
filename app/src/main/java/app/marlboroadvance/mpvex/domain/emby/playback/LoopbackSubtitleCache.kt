package app.marlboroadvance.mpvex.domain.emby.playback

import android.content.Context
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.SubtitleCacheConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The route is read-through only; only pre-play metadata snapshots initiate cache writes. */
class LoopbackSubtitleCache internal constructor(
  cacheDirectory: File,
  private val http: LoopbackHttpPool,
  private val creationTime: (File) -> Long? = { file ->
    runCatching { java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis() }.getOrNull()
  },
) {
  constructor(context: Context, http: LoopbackHttpPool) : this(context.cacheDir, http)
  private val directory = File(cacheDirectory, "loemby/subtitle")
  private val active = ConcurrentHashMap<String, Job>()
  private val lock = Any()

  fun fileForUrl(url: String): File = File(directory, "${digest(url)}.ass")

  fun precache(
    scope: CoroutineScope, server: EmbyServer, url: String, invalidCerts: Boolean = false,
    onFailure: (Throwable) -> Unit = {},
  ): Job? = synchronized(lock) {
    val target = fileForUrl(url)
    if (target.isFile) return@synchronized null
    active[url]?.let { return@synchronized it }
    scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
      val temporary = File(directory, "${target.name}.tmp")
      try {
        if (target.isFile) return@launch
        http.execute(server, url, kind = LoopbackHttpPool.Kind.IMAGE,
          dangerAcceptInvalidCerts = invalidCerts).use { response ->
          if (response.code != 200) throw IOException("cache subtitle request failed ${response.code}")
          if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create subtitle cache")
          FileOutputStream(temporary).use { output ->
            response.body.byteStream().use { input -> input.copyTo(output) }
            output.flush()
            output.fd.sync()
          }
          if (!temporary.renameTo(target)) throw IOException("Cannot rename subtitle cache")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        onFailure(failure)
      } finally {
        temporary.delete()
        synchronized(lock) { active.remove(url) }
      }
    }.also { active[url] = it; it.start() }
  }

  /** Cancel writers before deleting their .tmp files. Automatic cleanup never touches active writes. */
  suspend fun clear() {
    val jobs = synchronized(lock) { active.values.toList().also { it.forEach(Job::cancel) } }
    jobs.forEach { it.join() }
    withContext(Dispatchers.IO) { directory.listFiles()?.forEach { if (!it.delete()) throw IOException("Cannot clear subtitle cache") } }
  }

  @Volatile private var lastCleanMillis: Long? = null

  /** Desktop keeps files by creation time; unavailable creation metadata means keep, not mtime fallback. */
  suspend fun clean(
    configuration: SubtitleCacheConfiguration = SubtitleCacheConfiguration.DEFAULT,
    force: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
  ) = withContext(Dispatchers.IO) {
    synchronized(lock) {
      val sharedLast = configuration.sharedLastCleanEpochSeconds?.let { Math.multiplyExact(it, 1000L) }
      val last = listOfNotNull(lastCleanMillis, sharedLast).maxOrNull()
      if (!force && last != null && nowMillis - last < CLEAN_PERIOD_MILLIS) return@synchronized
      val days = configuration.storedDays
      require(days in 0..4294967295L) { "Invalid subtitleStoredDays" }
      val cutoff = nowMillis - Math.multiplyExact(days, CLEAN_PERIOD_MILLIS)
      val writing = active.keys.map { fileForUrl(it).name }.toSet()
      fun cleanDirectory(dir: File) {
        for (file in dir.listFiles().orEmpty()) {
          if (file.isDirectory) { cleanDirectory(file); continue }
          if (file.name.removeSuffix(".tmp") in writing) continue
          if (force || creationTime(file)?.let { it < cutoff } == true) {
            if (!file.delete()) throw IOException("Cannot clean subtitle cache")
          }
        }
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
      }
      cleanDirectory(directory)
      lastCleanMillis = nowMillis // Platform-only bookkeeping, never a shared global_config write.
    }
  }

  companion object {
    const val CLEAN_PERIOD_MILLIS = 24L * 60 * 60 * 1000
    fun digest(url: String): String = MessageDigest.getInstance("SHA-256")
      .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
  }
}
