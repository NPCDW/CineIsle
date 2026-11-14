package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyDetailArtworkTest {
  private val server = EmbyServer(id = 1, name = "fixture", host = "fixture.test", username = "user", password = "",
    apiToken = "token", userId = "user")

  @Test fun episodeArtworkUsesPrimaryThenBackdropAndNeverLogo() {
    val item = EmbyItem(Id = "episode", ImageTags = mapOf("Primary" to "primary", "Logo" to "logo"),
      BackdropImageTags = listOf("backdrop"))
    val urls = mediaArtworkUrls(server, item, backdropFirst = false)
    assertEquals(2, urls.size)
    assertTrue(urls[0].contains("/Images/Primary"))
    assertTrue(urls[1].contains("/Images/Backdrop/0"))
    assertFalse(urls.any { it.contains("/Images/Logo") })
  }

  @Test fun movieArtworkUsesBackdropThenPrimaryAndMissingArtworkStaysEmpty() {
    val item = EmbyItem(Id = "movie", ImageTags = mapOf("Primary" to "primary", "Logo" to "logo"),
      BackdropImageTags = listOf("backdrop"))
    val urls = mediaArtworkUrls(server, item, backdropFirst = true)
    assertEquals(2, urls.size)
    assertTrue(urls[0].contains("/Images/Backdrop/0"))
    assertTrue(urls[1].contains("/Images/Primary"))
    assertFalse(urls.any { it.contains("/Images/Logo") })
    assertTrue(mediaArtworkUrls(server, EmbyItem(Id = "empty"), backdropFirst = true).isEmpty())
  }
}
