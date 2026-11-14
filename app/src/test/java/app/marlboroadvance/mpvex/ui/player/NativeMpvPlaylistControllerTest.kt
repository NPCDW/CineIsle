package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVNode
import app.marlboroadvance.mpvex.domain.postgres.playbackConfigurationForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMpvPlaylistControllerTest {
  @Test
  fun `replace builds the complete queue before starting requested entry`() {
    val transport = RecordingTransport()
    val controller = NativeMpvPlaylistController(transport)
    val urls = listOf(localUrl("a"), localUrl("b"), localUrl("c"))

    controller.replace(urls, startIndex = 1, prefetch = true)

    assertEquals(urls, controller.entries)
    assertEquals(urls, transport.playlist)
    assertEquals(1, transport.position)
    assertEquals(
      listOf(
        "set:prefetch-playlist=no",
        "command:stop",
        "command:loadfile|${urls[0]}|insert-at|0",
        "command:loadfile|${urls[1]}|insert-at|1",
        "command:loadfile|${urls[2]}|insert-at|2",
        "set:prefetch-playlist=yes",
        "command:playlist-play-index|1",
      ),
      transport.events,
    )
  }

  @Test
  fun `replace current keeps the surrounding native playlist`() {
    val transport = RecordingTransport()
    val controller = NativeMpvPlaylistController(transport)
    val urls = listOf(localUrl("a"), localUrl("b"), localUrl("c"))
    controller.replace(urls, startIndex = 0, prefetch = false)
    transport.events.clear()

    val replacement = localUrl("b-version-2")
    controller.replaceCurrent(1, replacement)

    assertEquals(listOf(urls[0], replacement, urls[2]), controller.entries)
    assertEquals(listOf(urls[0], replacement, urls[2]), transport.playlist)
    assertEquals(1, transport.position)
    assertEquals(
      listOf(
        "command:loadfile|$replacement|insert-at|1",
        "command:playlist-remove|2",
        "command:playlist-play-index|1",
      ),
      transport.events,
    )
  }

  @Test
  fun `replace removes an active duplicate before rebuilding the queue`() {
    val old = localUrl("old")
    val transport = RecordingTransport(mutableListOf(old), 0)
    val controller = NativeMpvPlaylistController(transport)
    val urls = listOf(localUrl("a"), old, localUrl("c"))

    controller.replace(urls, startIndex = 2, prefetch = false)

    assertEquals(urls, controller.entries)
    assertEquals(urls, transport.playlist)
    assertEquals(2, transport.position)
  }

  @Test
  fun `replace current works for a non-current index`() {
    val transport = RecordingTransport()
    val controller = NativeMpvPlaylistController(transport)
    val urls = listOf(localUrl("a"), localUrl("b"), localUrl("c"))
    controller.replace(urls, startIndex = 0, prefetch = false)
    transport.events.clear()

    val replacement = localUrl("c-version-2")
    controller.replaceCurrent(2, replacement)

    assertEquals(listOf(urls[0], urls[1], replacement), controller.entries)
    assertEquals(controller.entries, transport.playlist)
    assertEquals(2, transport.position)
  }

  @Test
  fun `shared prefetch accepts only loemby yes value`() {
    assertTrue(sharedPrefetchPlaylistEnabled(playbackConfigurationForTest(mapOf("prefetch_playlist" to "yes"))))
    assertFalse(sharedPrefetchPlaylistEnabled(playbackConfigurationForTest(mapOf("prefetch_playlist" to "no"))))
    assertFalse(sharedPrefetchPlaylistEnabled(playbackConfigurationForTest(mapOf("prefetch_playlist" to "true"))))
    assertFalse(sharedPrefetchPlaylistEnabled(playbackConfigurationForTest()))
  }

  private fun localUrl(id: String) = "http://127.0.0.1:34567/play_media/$id/0"

  private class RecordingTransport(
    val playlist: MutableList<String> = mutableListOf(),
    var position: Int = if (playlist.isEmpty()) -1 else 0,
  ) : LoembyMpvTransport {
    val events = mutableListOf<String>()

    override fun command(vararg args: String) {
      events += "command:${args.joinToString("|")}"
      when (args.firstOrNull()) {
        "stop" -> {
          playlist.clear()
          position = -1
        }
        "loadfile" -> {
          require(args.getOrNull(2) == "insert-at")
          val index = args.getOrNull(3)?.toIntOrNull() ?: playlist.size
          playlist.add(index.coerceIn(0, playlist.size), args[1])
          if (position >= index) position++
        }
        "playlist-remove" -> {
          val index = args[1].toInt()
          playlist.removeAt(index)
          if (playlist.isEmpty()) {
            position = -1
          } else if (position > index) {
            position--
          } else if (position == index) {
            position = position.coerceAtMost(playlist.lastIndex)
          }
        }
        "playlist-play-index" -> position = args[1].toInt()
      }
    }

    override fun getDouble(name: String): Double? = null
    override fun propertyString(name: String): String? = null
    override fun setDouble(name: String, value: Double) {
      events += "set:$name=$value"
    }

    override fun setString(name: String, value: String) {
      events += "set:$name=$value"
    }

    override fun setBoolean(name: String, value: Boolean) {
      events += "set:$name=$value"
    }

    override fun getBoolean(name: String): Boolean? = null
    override fun getNode(name: String): MPVNode? = null
  }
}
