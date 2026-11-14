package app.marlboroadvance.mpvex.domain.anime4k

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Copies Anime4K shaders from assets and resolves the selected mpv shader list. */
class Anime4KManager(private val context: Context) {

  companion object {
    private const val SHADER_DIR = "shaders"
    private const val DONE_MARKER = ".assets_copied"
  }

  // Names are persisted preferences; do not rename them.
  enum class Quality(val suffix: String, val titleRes: Int) {
    FAST("S", app.marlboroadvance.mpvex.R.string.anime4k_quality_fast),
    BALANCED("M", app.marlboroadvance.mpvex.R.string.anime4k_quality_balanced),
    HIGH("L", app.marlboroadvance.mpvex.R.string.anime4k_quality_high),
  }

  // Names are persisted preferences; the *_PLUS values represent A+A, B+B and C+A.
  enum class Mode(
    val titleRes: Int,
    val displayName: String,
  ) {
    OFF(app.marlboroadvance.mpvex.R.string.anime4k_mode_off, "Off"),
    A(app.marlboroadvance.mpvex.R.string.anime4k_mode_a, "A"),
    B(app.marlboroadvance.mpvex.R.string.anime4k_mode_b, "B"),
    C(app.marlboroadvance.mpvex.R.string.anime4k_mode_c, "C"),
    A_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_a_plus, "A+A"),
    B_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_b_plus, "B+B"),
    C_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_c_plus, "C+A"),
  }

  @Volatile
  private var shaderDir: File? = null

  @Volatile
  private var isInitialized = false

  /**
   * Copies the exact assets used by the Fast/HQ recipes. Call this on an IO thread.
   *
   * The marker contains a versioned manifest and is written only after atomic copies
   * complete. An old empty `.assets_copied` marker therefore cannot hide shaders added
   * by an app update without adding per-asset work to every cold start.
   */
  @Synchronized
  fun prepareOnIoThread(): Boolean {
    if (isInitialized) return true

    return try {
      val dir = File(context.filesDir, SHADER_DIR)
      if (!dir.exists() && !dir.mkdirs()) return false

      val marker = File(dir, DONE_MARKER)
      val markerValue =
        marker.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
      // Fast path deliberately avoids stat'ing every shader on each cold start. The
      // marker is versioned and written only after atomic copies have completed.
      val prepared = markerValue == Anime4KShaderCatalog.assetMarkerValue
      if (!prepared) {
        // Invalidate first. If the process dies during a copy, the next launch must
        // recopy rather than trusting a current marker plus a non-empty partial file.
        if (marker.exists() && !marker.delete()) return false
        for (fileName in Anime4KShaderCatalog.requiredAssetNames) {
          if (!copyShaderFromAssets(fileName, dir)) return false
        }
        // Assets are ready only after the versioned manifest is durable. Otherwise a
        // later call in this process could skip validation through isInitialized.
        val markerMatches = runCatching {
          marker.writeText(Anime4KShaderCatalog.assetMarkerValue)
          marker.readText() == Anime4KShaderCatalog.assetMarkerValue
        }.getOrDefault(false)
        if (!markerMatches) return false
      }

      shaderDir = dir
      isInitialized = true
      true
    } catch (_: Exception) {
      isInitialized = false
      false
    }
  }

  /** Compatibility entry point; it performs IO and must not be called on the main thread. */
  fun initialize(): Boolean = prepareOnIoThread()

  /**
   * Resolves the startup-option form (`:` separated) used by the existing MPVView API.
   * Runtime changes should use [resolvePreparedChain] and its semicolon form instead.
   */
  fun resolveChain(
    isEnabled: Boolean,
    gpuNextActive: Boolean,
    useVulkan: Boolean,
    modeStr: String,
    qualityStr: String,
  ): String {
    if (!isEnabled || !isInitialized) return ""

    // The startup caller cannot switch VO. Runtime wiring uses Anime4KRuntimePolicy
    // to request a session-only gpu fallback instead of changing the preference.
    if (gpuNextActive && !useVulkan) return ""

    val mode = runCatching { Mode.valueOf(modeStr) }.getOrNull() ?: return ""
    val quality = runCatching { Quality.valueOf(qualityStr) }.getOrDefault(Quality.BALANCED)
    return getShaderChain(mode, quality)
  }

  /** Returns a validated pure representation with both startup and runtime list forms. */
  fun resolvePreparedChain(
    mode: Mode,
    quality: Quality,
  ): ResolvedAnime4KShaderChain? {
    if (!isInitialized) return null
    val dir = shaderDir?.takeIf(File::isDirectory) ?: return null
    if (mode == Mode.OFF) return Anime4KShaderCatalog.chain(mode, quality).resolveIn(dir)
    val chain = Anime4KShaderCatalog.chain(mode, quality).resolveIn(dir)
    return chain.takeIf { it.isAvailable }
  }

  /** Startup compatibility helper. Runtime mpv commands must use `runtimeCommandValue`. */
  fun getShaderChain(mode: Mode, quality: Quality): String =
    resolvePreparedChain(mode, quality)?.startupOptionValue.orEmpty()

  private fun copyShaderFromAssets(fileName: String, dir: File): Boolean {
    val destination = File(dir, fileName)
    val temporary = File(dir, ".$fileName.tmp")

    return try {
      if (temporary.exists() && !temporary.delete()) return false
      context.assets.open("$SHADER_DIR/$fileName").use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      if (!temporary.isFile || temporary.length() <= 0L) return false
      if (destination.exists() && !destination.delete()) return false
      temporary.renameTo(destination)
    } catch (_: Exception) {
      false
    } finally {
      temporary.delete()
    }
  }
}
