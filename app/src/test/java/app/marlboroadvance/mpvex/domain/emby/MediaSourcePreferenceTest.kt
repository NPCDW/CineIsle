package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [selectPreferredMediaSource] 的部分行为约定。
 *
 * 覆盖默认规则、空列表、单版本、最低分辨率与码率、体积和规则键解析。
 */
class MediaSourcePreferenceTest {

  private fun source(
    id: String,
    bitrate: Long? = null,
    size: Long? = null,
    width: Int? = null,
    height: Int? = null,
  ) = EmbyMediaSource(
    Id = id,
    Bitrate = bitrate,
    Size = size,
    MediaStreams =
      if (width == null && height == null) {
        null
      } else {
        listOf(EmbyMediaStream(Type = "Video", Width = width, Height = height))
      },
  )

  @Test
  fun `默认规则不选版本`() {
    val list = listOf(source("a", bitrate = 1000), source("b", bitrate = 2000))
    assertNull(selectPreferredMediaSourceId(list, MediaSourcePreference.DEFAULT))
  }

  @Test
  fun `单版本时不选`() {
    assertNull(
      selectPreferredMediaSourceId(listOf(source("only")), MediaSourcePreference.HIGHEST_BITRATE)
    )
  }

  @Test
  fun `空列表不选`() {
    assertNull(selectPreferredMediaSourceId(emptyList(), MediaSourcePreference.HIGHEST_BITRATE))
  }

  @Test
  fun `最低分辨率把缺失值排到最后而不是排最前`() {
    val list =
      listOf(
        source("unknown"), // 服务端没给分辨率
        source("uhd", width = 3840, height = 2160),
        source("sd", width = 640, height = 480),
      )
    assertEquals("sd", selectPreferredMediaSourceId(list, MediaSourcePreference.LOWEST_RESOLUTION))
  }

  @Test
  fun `最低码率不会选中缺失值`() {
    val list = listOf(source("none"), source("mid", bitrate = 5_000_000))
    assertEquals("mid", selectPreferredMediaSourceId(list, MediaSourcePreference.LOWEST_BITRATE))
  }

  @Test
  fun `体积最小在体积缺失时挑有值里最小的`() {
    val list = listOf(source("none"), source("big", size = 900L), source("small", size = 120L))
    assertEquals("small", selectPreferredMediaSourceId(list, MediaSourcePreference.SMALLEST_SIZE))
  }

  @Test
  fun `同画质选更小的是按分辨率优先`() {
    // 1080P 的 5G 与 720P 的 200M：按"每像素成本"算 720P 更优，
    // 但用户说"同画质"就是**别降分辨率**，所以仍应选 1080P。
    val list =
      listOf(source("hd", size = 5_000L, width = 1920, height = 1080), source("sd", size = 200L, width = 1280, height = 720))
    assertEquals(
      "hd",
      selectPreferredMediaSourceId(list, MediaSourcePreference.BEST_QUALITY_PER_SIZE),
    )
  }

  @Test
  fun `规则键非法时回落到默认`() {
    assertEquals(MediaSourcePreference.DEFAULT, MediaSourcePreference.fromKey("不存在的规则"))
    assertEquals(MediaSourcePreference.DEFAULT, MediaSourcePreference.fromKey(null))
    assertEquals(
      MediaSourcePreference.HIGHEST_BITRATE,
      MediaSourcePreference.fromKey("highest_bitrate"),
    )
  }
}
