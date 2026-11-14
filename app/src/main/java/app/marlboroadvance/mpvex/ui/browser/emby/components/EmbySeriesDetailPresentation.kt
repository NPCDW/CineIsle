package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.externalLinkUrl

internal data class SeriesImageRef(val type: String, val tag: String, val index: Int? = null)

internal fun seriesBannerImages(item: EmbyItem): List<SeriesImageRef> = buildList {
  item.ImageTags["Banner"]?.takeIf { it.isNotBlank() }?.let { add(SeriesImageRef("Banner", it)) }
  item.BackdropImageTags.firstOrNull()?.takeIf { it.isNotBlank() }
    ?.let { add(SeriesImageRef("Backdrop", it, 0)) }
  item.ImageTags["Primary"]?.takeIf { it.isNotBlank() }?.let { add(SeriesImageRef("Primary", it)) }
}

internal fun seriesGalleryImages(item: EmbyItem): List<SeriesImageRef> =
  item.BackdropImageTags.mapIndexedNotNull { index, tag ->
    tag.takeIf { it.isNotBlank() }?.let { SeriesImageRef("Backdrop", it, index) }
  }

internal fun seriesExternalTags(item: EmbyItem): List<Pair<String, String>> =
  item.ProviderIds.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    .map { it.key to it.value }

internal fun seriesExternalLinks(item: EmbyItem, baseUrl: String?): List<Pair<String, String>> =
  item.ExternalUrls.mapNotNull { link ->
    val name = link.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val url = link.Url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    name to url
  }.ifEmpty {
    seriesExternalTags(item).mapNotNull { (name, value) -> externalLinkUrl(baseUrl, name, value) }
  }.distinctBy { it.first }
