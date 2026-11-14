package app.marlboroadvance.mpvex.domain.anime4k

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4KPolicyTest {
  @Test
  fun `fast recipes match loemby input conf semantics for every mode`() {
    assertChains(
      Anime4KManager.Quality.FAST,
      mapOf(
        Anime4KManager.Mode.A to listOf(CLAMP, RESTORE_M, UPSCALE_M, DOWN_X2, DOWN_X4, UPSCALE_S),
        Anime4KManager.Mode.B to listOf(CLAMP, RESTORE_SOFT_M, UPSCALE_M, DOWN_X2, DOWN_X4, UPSCALE_S),
        Anime4KManager.Mode.C to listOf(CLAMP, DENOISE_M, DOWN_X2, DOWN_X4, UPSCALE_S),
        Anime4KManager.Mode.A_PLUS to listOf(CLAMP, RESTORE_M, UPSCALE_M, RESTORE_S, DOWN_X2, DOWN_X4, UPSCALE_S),
        Anime4KManager.Mode.B_PLUS to listOf(CLAMP, RESTORE_SOFT_M, UPSCALE_M, DOWN_X2, DOWN_X4, RESTORE_SOFT_S, UPSCALE_S),
        Anime4KManager.Mode.C_PLUS to listOf(CLAMP, DENOISE_M, DOWN_X2, DOWN_X4, RESTORE_S, UPSCALE_S),
      ),
    )
  }

  @Test
  fun `high recipes match loemby input conf semantics for every mode`() {
    assertChains(
      Anime4KManager.Quality.HIGH,
      mapOf(
        Anime4KManager.Mode.A to listOf(CLAMP, RESTORE_VL, UPSCALE_VL, DOWN_X2, DOWN_X4, UPSCALE_M),
        Anime4KManager.Mode.B to listOf(CLAMP, RESTORE_SOFT_VL, UPSCALE_VL, DOWN_X2, DOWN_X4, UPSCALE_M),
        Anime4KManager.Mode.C to listOf(CLAMP, DENOISE_VL, DOWN_X2, DOWN_X4, UPSCALE_M),
        Anime4KManager.Mode.A_PLUS to listOf(CLAMP, RESTORE_VL, UPSCALE_VL, RESTORE_M, DOWN_X2, DOWN_X4, UPSCALE_M),
        Anime4KManager.Mode.B_PLUS to listOf(CLAMP, RESTORE_SOFT_VL, UPSCALE_VL, DOWN_X2, DOWN_X4, RESTORE_SOFT_M, UPSCALE_M),
        Anime4KManager.Mode.C_PLUS to listOf(CLAMP, DENOISE_VL, DOWN_X2, DOWN_X4, RESTORE_M, UPSCALE_M),
      ),
    )
  }

  @Test
  fun `legacy names remain stable and plus names expose loemby labels`() {
    assertEquals(
      listOf("OFF", "A", "B", "C", "A_PLUS", "B_PLUS", "C_PLUS"),
      Anime4KManager.Mode.entries.map(Enum<*>::name),
    )
    assertEquals("A+A", Anime4KManager.Mode.A_PLUS.displayName)
    assertEquals("B+B", Anime4KManager.Mode.B_PLUS.displayName)
    assertEquals("C+A", Anime4KManager.Mode.C_PLUS.displayName)
    assertEquals(
      listOf("FAST", "BALANCED", "HIGH"),
      Anime4KManager.Quality.entries.map(Enum<*>::name),
    )
  }

  @Test
  fun `off has an empty chain and balanced keeps compatibility through fast recipe`() {
    assertTrue(
      Anime4KShaderCatalog.chain(Anime4KManager.Mode.OFF, Anime4KManager.Quality.FAST)
        .fileNames.isEmpty(),
    )
    assertFalse(
      Anime4KShaderCatalog.chain(Anime4KManager.Mode.OFF, Anime4KManager.Quality.FAST)
        .resolveIn(Files.createTempDirectory("anime4k-off").toFile())
        .isAvailable,
    )
    Anime4KManager.Mode.entries.filterNot { it == Anime4KManager.Mode.OFF }.forEach { mode ->
      assertEquals(
        mode.name,
        Anime4KShaderCatalog.chain(mode, Anime4KManager.Quality.FAST),
        Anime4KShaderCatalog.chain(mode, Anime4KManager.Quality.BALANCED),
      )
    }
  }

  @Test
  fun `resolved chain exposes startup colon and runtime semicolon forms`() {
    val directory = Files.createTempDirectory("anime4k-chain").toFile()
    val resolved = Anime4KShaderCatalog
      .chain(Anime4KManager.Mode.C_PLUS, Anime4KManager.Quality.FAST)
      .resolveIn(directory)

    assertFalse(resolved.isAvailable)
    resolved.files.forEach { it.writeText("shader") }
    assertTrue(resolved.isAvailable)
    assertEquals(
      resolved.files.joinToString(":") { it.absolutePath },
      resolved.startupOptionValue,
    )
    assertEquals(resolved.startupOptionValue, resolved.startupOption)
    assertEquals(
      resolved.files.joinToString(";") { it.absolutePath },
      resolved.runtimeCommandValue,
    )
    assertEquals(resolved.runtimeCommandValue, resolved.runtimeShaderList)
    assertTrue(resolved.runtimeCommandValue.contains(';'))
    if (File.pathSeparator != ";") {
      assertFalse(resolved.startupOptionValue.contains(';'))
    }
  }

  @Test
  fun `required assets exist are nonempty and are exactly the recipe union`() {
    val assets = assetsDirectory()
    val recipeUnion = Anime4KManager.Quality.entries
      .flatMap { quality ->
        Anime4KManager.Mode.entries.flatMap { mode ->
          Anime4KShaderCatalog.chain(mode, quality).fileNames
        }
      }
      .toSet()

    assertEquals(recipeUnion.sorted(), Anime4KShaderCatalog.requiredAssetNames)
    Anime4KShaderCatalog.requiredAssetNames.forEach { name ->
      val asset = File(assets, name)
      assertTrue("missing $name", asset.isFile)
      assertTrue("empty $name", asset.length() > 0L)
    }
    val copiedAssetHashes = mapOf(
      DOWN_X4 to "5af62d8cd844916dc1126613e13bad3beab195787f93a71200b47c6ec78f2e41",
      RESTORE_VL to "35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e",
      RESTORE_SOFT_VL to "094334b0e20c1a201fe4941c7c68de72451e5aee9efb5524d7fb82b12dca64b9",
      UPSCALE_VL to "5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d",
      DENOISE_VL to "359c48fe5a317fbc6b706ce368401eef496e84ed98abac7a43efebca2b65d79b",
    )
    copiedAssetHashes.forEach { (name, hash) ->
      assertEquals(name, hash, File(assets, name).sha256())
      assertTrue(name, Anime4KShaderCatalog.assetMarkerValue.contains("$name:$hash"))
    }
  }

  @Test
  fun `marker is versioned and identifies every required asset`() {
    assertNotEquals("", Anime4KShaderCatalog.assetMarkerValue)
    assertTrue(Anime4KShaderCatalog.assetMarkerValue.startsWith("anime4k-assets-v"))
    Anime4KShaderCatalog.requiredAssetNames.forEach { name ->
      assertTrue(name, Anime4KShaderCatalog.assetMarkerValue.contains(name))
    }
    assertTrue(Anime4KShaderCatalog.assetMarkerValue.contains(DOWN_X4))
  }

  @Test
  fun `off clears shaders and restores the saved video output`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.OFF,
      currentVideoOutput = Anime4KRuntimePolicy.GPU_VIDEO_OUTPUT,
      gpuNextUsesVulkan = false,
      state = Anime4KRuntimeState(Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT),
    ) as Anime4KRuntimeDecision.Disable

    assertTrue(decision.clearShaders)
    assertEquals(Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT, decision.restoreVideoOutput)
    assertNull(decision.nextState.videoOutputToRestore)
  }

  @Test
  fun `off without a saved fallback still clears shaders without a vo restore`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.OFF,
      currentVideoOutput = Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT,
      gpuNextUsesVulkan = false,
    ) as Anime4KRuntimeDecision.Disable

    assertNull(decision.restoreVideoOutput)
    assertNull(decision.nextState.videoOutputToRestore)
  }

  @Test
  fun `openGL gpu next gets a temporary gpu fallback without preference mutation data`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.A,
      currentVideoOutput = Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT,
      gpuNextUsesVulkan = false,
    ) as Anime4KRuntimeDecision.Apply

    assertTrue(decision.clearExistingShaders)
    assertEquals(Anime4KRuntimePolicy.GPU_VIDEO_OUTPUT, decision.temporaryVideoOutput)
    assertEquals(
      Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT,
      decision.nextState.videoOutputToRestore,
    )
    assertEquals(
      Anime4KShaderCatalog.chain(Anime4KManager.Mode.A, Anime4KManager.Quality.FAST),
      decision.chain,
    )
  }

  @Test
  fun `reapplying does not overwrite or re-request the original fallback`() {
    val state = Anime4KRuntimeState(Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT)
    listOf(
      Anime4KRuntimePolicy.GPU_VIDEO_OUTPUT,
      Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT,
    ).forEach { currentVideoOutput ->
      val decision = Anime4KRuntimePolicy.decide(
        mode = Anime4KManager.Mode.B_PLUS,
        currentVideoOutput = currentVideoOutput,
        gpuNextUsesVulkan = false,
        state = state,
      ) as Anime4KRuntimeDecision.Apply

      assertNull(currentVideoOutput, decision.temporaryVideoOutput)
      assertEquals(currentVideoOutput, state, decision.nextState)
    }
  }

  @Test
  fun `existing gpu path applies without fallback or restore state`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.B,
      currentVideoOutput = Anime4KRuntimePolicy.GPU_VIDEO_OUTPUT,
      gpuNextUsesVulkan = false,
    ) as Anime4KRuntimeDecision.Apply

    assertNull(decision.temporaryVideoOutput)
    assertNull(decision.nextState.videoOutputToRestore)
  }

  @Test
  fun `unknown video output is left unchanged while shaders apply`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.A,
      currentVideoOutput = "other-vo",
      gpuNextUsesVulkan = false,
    ) as Anime4KRuntimeDecision.Apply

    assertNull(decision.temporaryVideoOutput)
    assertNull(decision.nextState.videoOutputToRestore)
  }

  @Test
  fun `vulkan gpu next path stays on gpu next`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.C,
      currentVideoOutput = Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT,
      gpuNextUsesVulkan = true,
    ) as Anime4KRuntimeDecision.Apply

    assertNull(decision.temporaryVideoOutput)
    assertNull(decision.nextState.videoOutputToRestore)
  }

  @Test
  fun `direct output rejects with a clear reason and keeps session state`() {
    val state = Anime4KRuntimeState(Anime4KRuntimePolicy.GPU_NEXT_VIDEO_OUTPUT)
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.C_PLUS,
      currentVideoOutput = Anime4KRuntimePolicy.DIRECT_VIDEO_OUTPUT,
      gpuNextUsesVulkan = false,
      state = state,
    ) as Anime4KRuntimeDecision.Rejected

    assertEquals(RejectionReason.DIRECT_OUTPUT_BYPASSES_SHADER_PIPELINE, decision.reason)
    assertTrue(decision.message.contains("mediacodec_embed"))
    assertTrue(decision.message.contains("direct output"))
    assertEquals(state, decision.nextState)
  }

  @Test
  fun `resolved direct-output flag rejects even before current vo is available`() {
    val decision = Anime4KRuntimePolicy.decide(
      mode = Anime4KManager.Mode.A,
      currentVideoOutput = null,
      gpuNextUsesVulkan = false,
      directOutput = true,
    )

    assertTrue(decision is Anime4KRuntimeDecision.Rejected)
  }

  @Test
  fun `decoder setting enables Anime4K without persistently disabling gpu next`() {
    val source = sequenceOf(
      File("src/main/java/app/marlboroadvance/mpvex/ui/preferences/DecoderPreferencesScreen.kt"),
      File("app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/DecoderPreferencesScreen.kt"),
    ).first { it.isFile }.readText()
    val anime4kSwitch = source.substringAfter("val enableAnime4K by").substringBefore("PreferenceDivider()")

    assertTrue(anime4kSwitch.contains("onValueChange = preferences.enableAnime4K::set"))
    assertFalse(anime4kSwitch.contains("preferences.gpuNext.set(false)"))
  }

  private fun assertChains(
    quality: Anime4KManager.Quality,
    expected: Map<Anime4KManager.Mode, List<String>>,
  ) {
    assertEquals(
      Anime4KManager.Mode.entries.filterNot { it == Anime4KManager.Mode.OFF }.toSet(),
      expected.keys,
    )
    expected.forEach { (mode, files) ->
      assertEquals(mode.name, files, Anime4KShaderCatalog.chain(mode, quality).fileNames)
    }
  }

  private fun assetsDirectory(): File = sequenceOf(
    File("src/main/assets/shaders"),
    File("app/src/main/assets/shaders"),
  ).first { it.isDirectory }

  private fun File.sha256(): String =
    MessageDigest.getInstance("SHA-256")
      .digest(readBytes())
      .joinToString("") { "%02x".format(it) }

  private companion object {
    const val CLAMP = "Anime4K_Clamp_Highlights.glsl"
    const val RESTORE_S = "Anime4K_Restore_CNN_S.glsl"
    const val RESTORE_M = "Anime4K_Restore_CNN_M.glsl"
    const val RESTORE_VL = "Anime4K_Restore_CNN_VL.glsl"
    const val RESTORE_SOFT_S = "Anime4K_Restore_CNN_Soft_S.glsl"
    const val RESTORE_SOFT_M = "Anime4K_Restore_CNN_Soft_M.glsl"
    const val RESTORE_SOFT_VL = "Anime4K_Restore_CNN_Soft_VL.glsl"
    const val UPSCALE_S = "Anime4K_Upscale_CNN_x2_S.glsl"
    const val UPSCALE_M = "Anime4K_Upscale_CNN_x2_M.glsl"
    const val UPSCALE_VL = "Anime4K_Upscale_CNN_x2_VL.glsl"
    const val DENOISE_M = "Anime4K_Upscale_Denoise_CNN_x2_M.glsl"
    const val DENOISE_VL = "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl"
    const val DOWN_X2 = "Anime4K_AutoDownscalePre_x2.glsl"
    const val DOWN_X4 = "Anime4K_AutoDownscalePre_x4.glsl"
  }
}
