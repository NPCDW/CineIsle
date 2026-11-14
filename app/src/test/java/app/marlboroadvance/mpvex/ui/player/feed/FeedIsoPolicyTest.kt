package app.marlboroadvance.mpvex.ui.player.feed

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedIsoPolicyTest {
  private fun source(name: String): String = sequenceOf(
    File("src/main/java/app/marlboroadvance/mpvex/ui/player/feed/$name"),
    File("app/src/main/java/app/marlboroadvance/mpvex/ui/player/feed/$name"),
  ).first { it.isFile }.readText()

  @Test
  fun `feed uses one mpv engine with loopback and coordinator`() {
    val engine = source("FeedMpvEngine.kt").replace(Regex("(?s)/\\*.*?\\*/|//[^\\n]*"), "")
    assertTrue(engine.contains("fun play(spec: PlaybackSpec)"))
    assertTrue(engine.contains("local = loopback.register(spec)"))
    assertTrue(engine.contains("coordinator.prepare(local, transport"))
    assertTrue(engine.contains("owner = this@FeedMpvEngine"))
    assertTrue(engine.contains("transport.command(\"loadfile\", local, \"replace\")"))
    assertTrue(engine.contains("args.getOrNull(1)?.let(loopback::isLocalPlayUrl) == true"))
    assertEquals(1, Regex("MPVLib\\.command\\(").findAll(engine).count())
    assertFalse(engine.contains("MPVLib.command(\"loadfile\""))
    assertFalse(engine.contains("playFile("))
    assertTrue(engine.contains("coordinator.eofReached(this@FeedMpvEngine)"))

    val activity = source("VerticalFeedActivity.kt")
    assertEquals(1, Regex("FeedMpvEngine\\(").findAll(activity).count())
    assertTrue(activity.contains("private fun switchTo(index: Int)"))
    assertTrue(activity.contains("mpvEngine.play("))
    assertTrue(activity.contains("item.mpvPlaybackSpec("))
    assertFalse(activity.contains("ExoPlayer"))
    assertFalse(activity.contains("Media3"))
    assertFalse(activity.contains("playInMpv"))
  }

  @Test
  fun `feed spec binds source series title resume and queue without remote override`() {
    val server = EmbyServer(name = "fixture", host = "server.test", username = "user", password = "")
    val item = EmbyItem(
      Id = "episode", Name = "episode name", Type = "Episode", SeriesId = "series", SeriesName = "series name",
      ParentIndexNumber = 1, IndexNumber = 2,
      UserData = EmbyUserData(PlaybackPositionTicks = 123_000_000),
      MediaSources = listOf(EmbyMediaSource(Id = "source", Container = "mkv")),
    )
    var streamFactoryCalled = false
    val feed = feedPlaybackItemOf(item, "display title") {
      streamFactoryCalled = true
      "https://cdn.test/opaque"
    }!!
    assertFalse(streamFactoryCalled)
    val spec = feed.mpvPlaybackSpec(server, 2, 3)
    assertSame(server, spec.server)
    assertEquals("episode", spec.itemId)
    assertEquals("source", spec.explicitMediaSourceId)
    assertEquals("series", spec.seriesId)
    assertEquals("episode name", spec.itemName)
    assertEquals("series name", spec.seriesName)
    assertEquals("Episode", spec.itemType)
    assertEquals(1, spec.seasonNumber)
    assertEquals(2, spec.episodeNumber)
    assertEquals("S1E2. episode name", spec.title)
    assertEquals(123_000_000L, spec.positionTicks)
    assertEquals(2, spec.playlistIndex)
    assertEquals(3, spec.playlistTotal)
    assertNull(spec.overrideUrl)
    assertEquals(455_000_000L, feed.mpvPlaybackSpec(server, 2, 3, resumeSec = 45.5).positionTicks)
    assertEquals(0L, feed.mpvPlaybackSpec(server, 2, 3, resumeSec = 0.0).positionTicks)
  }

  @Test
  fun `feed spec refuses invalid server queue and missing item id`() {
    val server = EmbyServer(name = "fixture", host = "server.test", username = "user", password = "")
    val feed = FeedItem("film", "film")
    assertTrue(runCatching { feed.mpvPlaybackSpec(server.copy(disabled = true), 1, 1) }.isFailure)
    assertTrue(runCatching { feed.mpvPlaybackSpec(server, 0, 1) }.isFailure)
    assertTrue(runCatching { feed.copy(itemId = "").mpvPlaybackSpec(server, 1, 1) }.isFailure)
  }

  @Test
  fun `ISO only item is excluded without constructing a remote stream`() {
    val item = EmbyItem(Id = "film", MediaSources = listOf(EmbyMediaSource(Id = "disc", Container = "iso")))
    val feed = feedPlaybackItemOf(item, "film") { throw AssertionError("stream URL must not be generated") }
    assertNull(feed)
  }

  @Test
  fun `mixed versions bind ordinary source id without constructing a remote stream`() {
    val item = EmbyItem(Id = "film", MediaSources = listOf(
      EmbyMediaSource(Id = "disc", Container = "iso"),
      EmbyMediaSource(Id = "file", Container = "mkv"),
    ))
    var called = false
    val feed = feedPlaybackItemOf(item, "film") {
      called = true
      "https://server.test/Videos/film/stream"
    }
    assertFalse(called)
    assertEquals("file", feed!!.mediaSourceId)
  }

  @Test
  fun `unknown source remains playable without a URL`() {
    val feed = feedPlaybackItemOf(EmbyItem(Id = "film"), "film")
    assertNotNull(feed)
    assertTrue(isFeedItemPlayable(feed!!))
  }

  @Test
  fun `unknown-id mixed source does not guess a playback version`() {
    val item = EmbyItem(Id = "film", MediaSources = listOf(
      EmbyMediaSource(Container = "mkv"),
      EmbyMediaSource(Id = "disc", Container = "iso"),
    ))
    assertNull(feedPlaybackItemOf(item, "film"))
  }

  @Test
  fun `missing item id never enters feed`() {
    assertFalse(isFeedItemPlayable(FeedItem("", "film")))
    assertNull(feedPlaybackItemOf(EmbyItem(Id = null), "film"))
  }
}
