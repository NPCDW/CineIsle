package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

internal fun mediaArtworkUrls(server: EmbyServer, item: EmbyItem, backdropFirst: Boolean): List<String> {
  val primary = item.ImageTags["Primary"]?.takeIf(String::isNotBlank)?.let { tag ->
    EmbyClient.imageUrl(server, item.Id.orEmpty(), "Primary", tag, 1600)
  }
  val backdrop = item.BackdropImageTags.firstOrNull()?.takeIf(String::isNotBlank)?.let { tag ->
    EmbyClient.imageUrl(server, item.Id.orEmpty(), "Backdrop", tag, 1600, imageIndex = 0)
  }
  return (if (backdropFirst) listOfNotNull(backdrop, primary) else listOfNotNull(primary, backdrop)).distinct()
}
