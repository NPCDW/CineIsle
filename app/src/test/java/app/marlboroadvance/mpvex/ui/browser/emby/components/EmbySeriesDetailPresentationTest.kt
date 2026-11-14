package app.marlboroadvance.mpvex.ui.browser.emby.components

import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyExternalUrl
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyNameIdPair
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import org.junit.Assert.*
import org.junit.Test

class EmbySeriesDetailPresentationTest {
  private val server = EmbyServer(
    id = 1, name = "fixture", host = "emby.fixture.test", username = "user", password = "secret",
    sourceKey = "source", sourceRevision = 1, remoteId = "row", apiToken = "token",
  )

  @Test fun bannerPrefersBannerThenBackdropThenPrimary() {
    val item = EmbyItem(
      ImageTags = mapOf("Banner" to "banner", "Primary" to "poster", "Logo" to "logo"),
      BackdropImageTags = listOf("backdrop", "second"),
    )
    assertEquals(
      listOf(SeriesImageRef("Banner", "banner"), SeriesImageRef("Backdrop", "backdrop", 0), SeriesImageRef("Primary", "poster")),
      seriesBannerImages(item),
    )
  }

  @Test fun missingBannerFallsBackToBackdrop() {
    assertEquals(listOf(SeriesImageRef("Backdrop", "backdrop", 0)),
      seriesBannerImages(EmbyItem(BackdropImageTags = listOf("backdrop"))))
  }

  @Test fun missingBackdropFallsBackToPrimary() {
    assertEquals(listOf(SeriesImageRef("Primary", "poster")),
      seriesBannerImages(EmbyItem(ImageTags = mapOf("Banner" to " ", "Primary" to "poster"))))
  }

  @Test fun emptyArtworkDoesNotInventImages() {
    assertTrue(seriesBannerImages(EmbyItem()).isEmpty())
    assertTrue(seriesGalleryImages(EmbyItem()).isEmpty())
  }

  @Test fun galleryPreservesOriginalIndicesWhenSkippingBlankTags() {
    assertEquals(listOf(SeriesImageRef("Backdrop", "first", 0), SeriesImageRef("Backdrop", "third", 2)),
      seriesGalleryImages(EmbyItem(BackdropImageTags = listOf("first", " ", "third"))))
  }

  @Test fun galleryIndexChangesTheImagePathNotOnlyCacheTag() {
    val first = EmbyClient.imageUrl(server, "series", "Backdrop", "tag", imageIndex = 0)
    val third = EmbyClient.imageUrl(server, "series", "Backdrop", "tag", imageIndex = 2)
    assertTrue(first.contains("/Images/Backdrop/0?"))
    assertTrue(third.contains("/Images/Backdrop/2?"))
    assertNotEquals(first, third)
  }

  @Test fun ordinaryImageUrlsKeepTheirUnindexedPath() {
    val url = EmbyClient.imageUrl(server, "series", "Primary", "poster", 480)
    assertTrue(url.contains("/Images/Primary?tag=poster&maxWidth=480&quality=90"))
    assertFalse(url.contains("/Primary/0"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun negativeImageIndicesAreRejected() {
    EmbyClient.imageUrl(server, "series", "Backdrop", imageIndex = -1)
  }

  @Test fun externalTagsUseProviderKeyValuesNotGenresOrOrdinaryTags() {
    val item = EmbyItem(
      Genres = listOf("Drama"), Tags = listOf("ordinary"), TagItems = listOf(EmbyNameIdPair(Name = "other")),
      ProviderIds = linkedMapOf("Tmdb" to "123", "Imdb" to "tt456", "" to "invalid", "empty" to " "),
    )
    assertEquals(listOf("Tmdb" to "123", "Imdb" to "tt456"), seriesExternalTags(item))
  }

  @Test fun serverExternalUrlsTakePriorityAndDeduplicateByName() {
    val item = EmbyItem(
      ProviderIds = mapOf("Tmdb" to "123"),
      ExternalUrls = listOf(
        EmbyExternalUrl(Name = "Official", Url = "https://fixture.test/series"),
        EmbyExternalUrl(Name = "Official", Url = "https://fixture.test/duplicate"),
        EmbyExternalUrl(Name = "broken", Url = " "),
      ),
    )
    assertEquals(listOf("Official" to "https://fixture.test/series"), seriesExternalLinks(item, server.baseUrl))
  }

  @Test fun providerLinkFallbackPreservesQualifiedIds() {
    val item = EmbyItem(ProviderIds = linkedMapOf("Tmdb" to "123", "Plugin" to "Other:456"))
    assertEquals(listOf("Tmdb" to "https://fixture.test/?redirect=Tmdb:123", "Plugin" to "https://fixture.test/?redirect=Other:456"),
      seriesExternalLinks(item, "https://fixture.test/"))
  }

  @Test fun emptyMetadataOrMissingOriginDoesNotInventLinks() {
    assertTrue(seriesExternalTags(EmbyItem()).isEmpty())
    assertTrue(seriesExternalLinks(EmbyItem(), server.baseUrl).isEmpty())
    assertTrue(seriesExternalLinks(EmbyItem(ProviderIds = mapOf("Tmdb" to "123")), null).isEmpty())
  }
}
