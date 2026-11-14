package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL policy only: no native player, network or disc reads. */
class PlayerIsoPolicyTest {
  @Test fun `HTTP ISO remains unsupported`() {
    for (url in listOf(
      "https://cdn.example/movie.iso",
      "HTTP://cdn.example/MOVIE.ISO?token=fixture#part",
      "https://cdn.example/Movie%2eISO?name=movie.mkv",
      "http://127.0.0.1:12345/iso17",
    )) {
      assertTrue(url, EmbyMediaSourcePolicy.isRemoteIsoUrl(url))
      assertFalse(url, EmbyPlaybackInput.isPlayableUrl(url))
    }
  }

  @Test fun `ordinary Emby CDN and loopback streams remain supported`() {
    for (url in listOf(
      "https://emby.example/Videos/id/stream?static=true",
      "https://cdn.example/movie.strm",
      "https://cdn.example/opaque?filename=movie.iso#movie.iso",
      "https://cdn.example/movie.iso.mkv",
      "http://127.0.0.1:8096/Videos/item/stream",
      "http://localhost:8096/Videos/item/stream",
      "http://[::1]:8096/Videos/item/stream",
    )) assertTrue(url, EmbyPlaybackInput.isPlayableUrl(url))
  }

  @Test fun `local files content descriptors and disc protocols are unsupported`() {
    for (url in listOf(
      "file:///storage/movie.iso", "file:///storage/movie.mkv",
      "content://documents/movie.iso", "content://media/video/1", "fd://12",
      "/storage/movie.iso", "bluray:///storage/movie.iso", "dvd:///storage/movie.iso",
      "data:video/mp4;base64,AAAA", "rtsp://server.example/video", "https:///movie.mkv",
    )) assertFalse(url, EmbyPlaybackInput.isPlayableUrl(url))
  }
}
