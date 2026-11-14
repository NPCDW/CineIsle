package app.marlboroadvance.mpvex.ui.browser.emby.components

internal object NetworkImageCachePolicy {
  const val TTL_MS = 30L * 24 * 60 * 60 * 1000
  private const val MAX_MEMORY_TIMESTAMPS = 4096

  private val downloadedAt = object : LinkedHashMap<String, Long>(MAX_MEMORY_TIMESTAMPS + 1, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
      size > MAX_MEMORY_TIMESTAMPS
  }

  fun fresh(writtenAt: Long, now: Long = System.currentTimeMillis()): Boolean {
    val age = now - writtenAt
    return age >= 0 && age < TTL_MS
  }

  fun metadataTimestamp(value: String, now: Long = System.currentTimeMillis()): Long? =
    value.trim().toLongOrNull()?.takeIf { fresh(it, now) }

  @Synchronized
  fun record(key: String, time: Long = System.currentTimeMillis()) {
    downloadedAt[key] = time
  }

  @Synchronized
  fun recordedAt(key: String): Long? = downloadedAt[key]

  fun memoryFresh(key: String, now: Long = System.currentTimeMillis()): Boolean =
    recordedAt(key)?.let { fresh(it, now) } == true

  @Synchronized
  fun forget(key: String) {
    downloadedAt.remove(key)
  }

  @Synchronized
  fun clear() {
    downloadedAt.clear()
  }
}
