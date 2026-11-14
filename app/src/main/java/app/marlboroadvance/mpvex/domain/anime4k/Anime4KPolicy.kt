package app.marlboroadvance.mpvex.domain.anime4k

import java.io.File

/** A shader recipe independent of Android and mpv. */
data class Anime4KShaderChain(
  val fileNames: List<String>,
) {
  fun resolveIn(directory: File): ResolvedAnime4KShaderChain =
    ResolvedAnime4KShaderChain(fileNames.map { File(directory, it) })
}

/**
 * Absolute shader paths in both formats consumed by mpv.
 *
 * Startup options use the platform path-list separator (`:` on Android), while
 * `change-list glsl-shaders set` expects the semicolon form used by input.conf.
 */
data class ResolvedAnime4KShaderChain(
  val files: List<File>,
) {
  val startupOptionValue: String
    get() = files.joinToString(":") { it.absolutePath }

  val startupOption: String
    get() = startupOptionValue

  val runtimeCommandValue: String
    get() = files.joinToString(";") { it.absolutePath }

  val runtimeShaderList: String
    get() = runtimeCommandValue

  val isAvailable: Boolean
    get() = files.isNotEmpty() && files.all { it.isFile && it.length() > 0L }
}

/** Exact Anime4K Fast/HQ recipes used by loemby's mpv input.conf templates. */
object Anime4KShaderCatalog {
  private const val ASSET_SET_VERSION = 2

  // Pin the assets newly copied from the loemby reference so replacing one also
  // invalidates an installed marker. Existing packaged assets remain versioned by
  // ASSET_SET_VERSION and the manifest name list.
  private val assetRevisionTokens = mapOf(
    "Anime4K_AutoDownscalePre_x4.glsl" to
      "5af62d8cd844916dc1126613e13bad3beab195787f93a71200b47c6ec78f2e41",
    "Anime4K_Restore_CNN_VL.glsl" to
      "35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e",
    "Anime4K_Restore_CNN_Soft_VL.glsl" to
      "094334b0e20c1a201fe4941c7c68de72451e5aee9efb5524d7fb82b12dca64b9",
    "Anime4K_Upscale_CNN_x2_VL.glsl" to
      "5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d",
    "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl" to
      "359c48fe5a317fbc6b706ce368401eef496e84ed98abac7a43efebca2b65d79b",
  )

  private val fastChains = mapOf(
    Anime4KManager.Mode.A to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
    Anime4KManager.Mode.B to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_Soft_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
    Anime4KManager.Mode.C to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
    Anime4KManager.Mode.A_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
      "Anime4K_Restore_CNN_S.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
    Anime4KManager.Mode.B_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_Soft_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Restore_CNN_Soft_S.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
    Anime4KManager.Mode.C_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Restore_CNN_S.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
    ),
  )

  private val hqChains = mapOf(
    Anime4KManager.Mode.A to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_VL.glsl",
      "Anime4K_Upscale_CNN_x2_VL.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
    Anime4KManager.Mode.B to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_Soft_VL.glsl",
      "Anime4K_Upscale_CNN_x2_VL.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
    Anime4KManager.Mode.C to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
    Anime4KManager.Mode.A_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_VL.glsl",
      "Anime4K_Upscale_CNN_x2_VL.glsl",
      "Anime4K_Restore_CNN_M.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
    Anime4KManager.Mode.B_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Restore_CNN_Soft_VL.glsl",
      "Anime4K_Upscale_CNN_x2_VL.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Restore_CNN_Soft_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
    Anime4KManager.Mode.C_PLUS to chain(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_AutoDownscalePre_x4.glsl",
      "Anime4K_Restore_CNN_M.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
    ),
  )

  val requiredAssetNames: List<String> =
    (fastChains.values + hqChains.values)
      .flatMap(Anime4KShaderChain::fileNames)
      .distinct()
      .sorted()

  /**
   * Marker validity is intentionally manifest-based rather than marker-existence-based.
   * The manager deletes a stale marker before copying and writes this value only last.
   */
  val assetMarkerValue: String =
    buildString {
      append("anime4k-assets-v")
      append(ASSET_SET_VERSION)
      append('\n')
      append(requiredAssetNames.joinToString("\n") { name ->
        assetRevisionTokens[name]?.let { "$name:$it" } ?: name
      })
    }

  fun chain(
    mode: Anime4KManager.Mode,
    quality: Anime4KManager.Quality,
  ): Anime4KShaderChain {
    if (mode == Anime4KManager.Mode.OFF) return EMPTY_CHAIN

    // BALANCED remains a valid persisted enum name. Loemby defines Fast and HQ
    // recipes, so the legacy middle value safely aliases the lower-cost recipe.
    val recipes = if (quality == Anime4KManager.Quality.HIGH) hqChains else fastChains
    return recipes.getValue(mode)
  }

  private fun chain(vararg fileNames: String) = Anime4KShaderChain(fileNames.toList())

  private val EMPTY_CHAIN = Anime4KShaderChain(emptyList())
}

/** Session-only state; it is deliberately separate from the persisted decoder preferences. */
data class Anime4KRuntimeState(
  val videoOutputToRestore: String? = null,
)

sealed interface Anime4KRuntimeDecision {
  /** Set this chain, optionally after switching the current mpv VO for this session only. */
  data class Apply(
    val chain: Anime4KShaderChain,
    val clearExistingShaders: Boolean = true,
    val temporaryVideoOutput: String?,
    val nextState: Anime4KRuntimeState,
  ) : Anime4KRuntimeDecision

  /** Clear `glsl-shaders`, then restore [restoreVideoOutput] when it is non-null. */
  data class Disable(
    val clearShaders: Boolean = true,
    val restoreVideoOutput: String?,
    val nextState: Anime4KRuntimeState = Anime4KRuntimeState(),
  ) : Anime4KRuntimeDecision

  /** Leave the active renderer and shader list unchanged. */
  data class Rejected(
    val reason: RejectionReason,
    val message: String,
    val nextState: Anime4KRuntimeState,
  ) : Anime4KRuntimeDecision
}

enum class RejectionReason {
  DIRECT_OUTPUT_BYPASSES_SHADER_PIPELINE,
}

/** Pure policy for the future PlayerActivity runtime wiring. */
object Anime4KRuntimePolicy {
  const val GPU_VIDEO_OUTPUT = "gpu"
  const val GPU_NEXT_VIDEO_OUTPUT = "gpu-next"
  const val DIRECT_VIDEO_OUTPUT = "mediacodec_embed"
  const val DIRECT_OUTPUT_REASON =
    "Anime4K requires the GPU shader pipeline; mediacodec_embed/direct output bypasses it."

  fun decide(
    mode: Anime4KManager.Mode,
    currentVideoOutput: String?,
    gpuNextUsesVulkan: Boolean,
    directOutput: Boolean = currentVideoOutput.normalizedVideoOutput() == DIRECT_VIDEO_OUTPUT,
    state: Anime4KRuntimeState = Anime4KRuntimeState(),
    quality: Anime4KManager.Quality = Anime4KManager.Quality.FAST,
  ): Anime4KRuntimeDecision {
    val current = currentVideoOutput.normalizedVideoOutput()
    val directOutputRequested = directOutput || current == DIRECT_VIDEO_OUTPUT
    if (mode == Anime4KManager.Mode.OFF) {
      return Anime4KRuntimeDecision.Disable(
        restoreVideoOutput = state.videoOutputToRestore,
      )
    }

    if (directOutputRequested) {
      return Anime4KRuntimeDecision.Rejected(
        reason = RejectionReason.DIRECT_OUTPUT_BYPASSES_SHADER_PIPELINE,
        message = DIRECT_OUTPUT_REASON,
        nextState = state,
      )
    }

    val needsOpenGlFallback =
      current == GPU_NEXT_VIDEO_OUTPUT &&
        !gpuNextUsesVulkan &&
        state.videoOutputToRestore == null
    val savedVideoOutput =
      if (needsOpenGlFallback) current else state.videoOutputToRestore

    return Anime4KRuntimeDecision.Apply(
      chain = Anime4KShaderCatalog.chain(mode, quality),
      temporaryVideoOutput = GPU_VIDEO_OUTPUT.takeIf { needsOpenGlFallback },
      nextState = Anime4KRuntimeState(savedVideoOutput),
    )
  }

  private fun String?.normalizedVideoOutput(): String? =
    this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
}
