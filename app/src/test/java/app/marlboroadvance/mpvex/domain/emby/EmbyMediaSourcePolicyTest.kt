package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyMediaSourcePolicyTest {
  private val iso = EmbyMediaSource(Id = "disc", Container = "iso", Path = "/movies/disc.iso")
  private val video = EmbyMediaSource(Id = "video", Container = "mkv", Path = "/movies/video.mkv")

  private fun item(id: String = "item", vararg sources: EmbyMediaSource) =
    EmbyItem(Id = id, Type = "Movie", MediaSources = sources.toList())

  private fun failure(block: () -> Unit): Throwable =
    runCatching(block).exceptionOrNull() ?: throw AssertionError("Expected playback rejection")

  @Test fun `remote ISO uses HTTP schemes and decoded path case ignoring query and fragment`() {
    listOf(
      "https://cdn.test/movie.iso?sig=token#chapter",
      "HTTP://cdn.test/MOVIE.ISO",
      "https://cdn.test/movie%2E%49%53%4f?sig=token",
      "https://cdn.test/%E7%94%B5%E5%BD%B1%20name%2eiso",
      "https://cdn.test/a+name.ISO",
      "https://cdn.test/a name.iso",
    ).forEach { assertTrue(it, EmbyMediaSourcePolicy.isRemoteIsoUrl(it)) }
  }

  @Test fun `query and fragment ISO mentions are not ISO paths`() {
    listOf(
      "https://cdn.test/movie.mkv?filename=movie.iso",
      "https://cdn.test/stream#movie.iso",
      "https://cdn.test/movie.iso.mkv",
      "https://cdn.test/movie.iso/stream",
      "https://cdn.test/movie%252Eiso",
    ).forEach { assertFalse(it, EmbyMediaSourcePolicy.isRemoteIsoUrl(it)) }
  }

  @Test fun `non HTTP schemes missing URLs and malformed URLs are not remote ISO`() {
    listOf(null, "", "  ", "file:///movie.iso", "content://provider/movie.iso",
      "ftp://cdn.test/movie.iso", "/movie.iso", "http:/movie.iso", "https://cdn.test/%GG.iso")
      .forEach { assertFalse(it ?: "null", EmbyMediaSourcePolicy.isRemoteIsoUrl(it)) }
  }

  @Test fun `old ISO proxy matches only its exact host and route`() {
    listOf("http://127.0.0.1:12345/iso1", "http://127.0.0.1:23456/iso987?x=1#part",
      "http://127.0.0.1:23456/%69so42")
      .forEach { assertTrue(it, EmbyMediaSourcePolicy.isRemoteIsoUrl(it)) }
    listOf("http://127.0.0.1:12345/video", "http://127.0.0.1:12345/isomorphic",
      "http://127.0.0.1:12345/iso1/file", "http://127.0.0.1:12345/iso",
      "http://localhost:12345/iso1", "http://127.0.0.2:12345/iso1",
      "https://cdn.test/iso1", "http://127.0.0.1.example.test/iso1")
      .forEach { assertFalse(it, EmbyMediaSourcePolicy.isRemoteIsoUrl(it)) }
  }

  @Test fun `ISO container tokens accept case dot prefix and comma list`() {
    listOf("iso", " ISO ", ".ISO", "mkv, ISO", " .iso , mp4")
      .forEach { assertTrue(it, EmbyMediaSourcePolicy.isIsoSource(EmbyMediaSource(Container = it))) }
    listOf("strm", "", "isomorphic", "mkv,mp4", "application/iso")
      .forEach { assertFalse(it, EmbyMediaSourcePolicy.isIsoSource(EmbyMediaSource(Container = it))) }
  }

  @Test fun `source path detects encoded extension and does not need ISO container`() {
    listOf("/movies/Movie.ISO", "C:\\Movies\\Movie.iso", "/movies/Movie%2EISO",
      "https://cdn.test/Movie%2eiso?sig=1#chapter")
      .forEach { assertTrue(it, EmbyMediaSourcePolicy.isIsoSource(EmbyMediaSource(Container = "strm", Path = it))) }
    assertFalse(EmbyMediaSourcePolicy.isIsoSource(null))
    assertFalse(EmbyMediaSourcePolicy.isIsoSource(EmbyMediaSource(Path = "/movies/movie.iso.mkv")))
    assertFalse(EmbyMediaSourcePolicy.isIsoSource(EmbyMediaSource(Path = "https://cdn.test/movie.mkv?q=movie.iso")))
  }

  @Test fun `explicit selection uses exact ID and never falls back`() {
    val mixed = item("mixed", iso, video)
    assertSame(video, EmbyMediaSourcePolicy.sourceForPlayback(mixed, "video"))
    assertNull(EmbyMediaSourcePolicy.sourceForPlayback(mixed, "VIDEO"))
    assertNull(EmbyMediaSourcePolicy.sourceForPlayback(mixed, "missing"))
    assertTrue(failure { EmbyMediaSourcePolicy.requirePlayableSource(mixed, "missing") }
      .message.orEmpty().contains("版本不存在"))
  }

  @Test fun `mixed versions allow selected normal source even when default is ISO`() {
    val mixed = item("mixed", iso, video)
    EmbyMediaSourcePolicy.requirePlayableSource(mixed, "video", "https://cdn.test/video.mkv")
    assertEquals(EmbyMediaSourcePolicy.ISO_PLAYBACK_ERROR,
      failure { EmbyMediaSourcePolicy.requirePlayableSource(mixed, "disc") }.message)
  }

  @Test fun `default uses first source not any ISO version`() {
    val normalDefault = item("mixed", video, iso)
    assertSame(video, EmbyMediaSourcePolicy.sourceForPlayback(normalDefault))
    EmbyMediaSourcePolicy.requirePlayableSource(normalDefault)
    assertSame(iso, EmbyMediaSourcePolicy.sourceForPlayback(item("mixed", iso, video)))
    failure { EmbyMediaSourcePolicy.requirePlayableSource(item("mixed", iso, video)) }
  }

  @Test fun `single source uses that source with or without an ID`() {
    val single = item("single", video)
    assertSame(video, EmbyMediaSourcePolicy.sourceForPlayback(single))
    EmbyMediaSourcePolicy.requirePlayableSource(single)
    EmbyMediaSourcePolicy.requirePlayableSource(single, "video")
    failure { EmbyMediaSourcePolicy.requirePlayableSource(item("single", iso)) }
  }

  @Test fun `missing metadata and unknown container STRM stay allowed`() {
    EmbyMediaSourcePolicy.requirePlayableSource(EmbyItem())
    EmbyMediaSourcePolicy.requirePlayableSource(EmbyItem(), "known-by-caller")
    listOf(null, "", "strm", "unknown").forEach {
      EmbyMediaSourcePolicy.requirePlayableSource(item("unknown", EmbyMediaSource(Container = it)))
    }
  }

  @Test fun `item path fallback applies only without a source selection`() {
    failure { EmbyMediaSourcePolicy.requirePlayableSource(EmbyItem(Path = "/movie.iso")) }
    EmbyMediaSourcePolicy.requirePlayableSource(item("mixed", video, iso).copy(Path = "/movie.iso"))
    EmbyMediaSourcePolicy.requirePlayableSource(EmbyItem(Path = "/movie.iso"), "selected-without-metadata")
  }

  @Test fun `ISO URL blocks unknown STRM and normal metadata alike`() {
    listOf(EmbyItem(), item("video", video), item("strm", EmbyMediaSource(Container = "strm")))
      .forEach { failure { EmbyMediaSourcePolicy.requirePlayableSource(it, url = "https://cdn.test/movie%2Eiso") } }
  }

  @Test fun `ordinary stream URL never bypasses ISO source guard`() {
    val error = failure { EmbyMediaSourcePolicy.requirePlayableSource(item("iso", iso), "disc",
      "https://emby.test/videos/iso/master.m3u8") }
    assertEquals(EmbyMediaSourcePolicy.ISO_PLAYBACK_ERROR, error.message)
    failure { EmbyMediaSourcePolicy.requirePlayableSource(item("iso", iso), "disc",
      "https://cdn.test/disc.iso") }
    failure { EmbyMediaSourcePolicy.requirePlayableSource(item("iso", iso), "missing",
      "https://emby.test/master.m3u8") }
  }

  @Test fun `ISO first queue item rejects instead of silently playing second`() {
    val queue = listOf(item("first", iso), item("second", video))
    val error = failure { EmbyMediaSourcePolicy.filterPlayableQueue(queue) }
    assertEquals(EmbyMediaSourcePolicy.ISO_PLAYBACK_ERROR, error.message)
    assertEquals(listOf("first", "second"), queue.map { it.Id })
  }

  @Test fun `ISO resolved first URL rejects queue even with normal metadata`() {
    failure { EmbyMediaSourcePolicy.filterPlayableQueue(listOf(item("first", video), item("second", video)),
      directUrls = mapOf("first" to "http://127.0.0.1:23456/iso1")) }
  }

  @Test fun `queue later ISO filtering preserves source titles and item order`() {
    val first = item("first", iso, video).copy(Name = "selected normal")
    val unknown = EmbyItem(Id = "unknown", Name = "unknown", Type = "Movie")
    val last = item("last", video).copy(Name = "last")
    val result = EmbyMediaSourcePolicy.filterPlayableQueue(
      listOf(first, item("disc", iso), unknown, item("encoded-url", video), last),
      firstMediaSourceId = "video",
      directUrls = mapOf("encoded-url" to "https://cdn.test/movie%2eiso"),
    )
    assertEquals(listOf("first", "unknown", "last"), result.map { it.Id })
    assertEquals(listOf("selected normal", "unknown", "last"), result.map { it.Name })
    assertSame(first, result.first())
    assertSame(last, result.last())
  }

  @Test fun `empty queue stays empty and normal default mixed later item is retained`() {
    assertTrue(EmbyMediaSourcePolicy.filterPlayableQueue(emptyList()).isEmpty())
    val queue = listOf(item("first", video), item("mixed", video, iso))
    assertEquals(queue, EmbyMediaSourcePolicy.filterPlayableQueue(queue))
  }
}
