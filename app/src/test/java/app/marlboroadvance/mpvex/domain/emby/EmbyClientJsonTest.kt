package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyClientJsonTest {
  @Test fun responseJsonCoercesExplicitNullCollectionsToDefaults() {
    val item = EmbyClient.decodeResponseJson<EmbyItem>(
      """
        {
          "Id": "item",
          "ImageTags": null,
          "BackdropImageTags": null,
          "Genres": null,
          "Tags": null,
          "ProductionLocations": null,
          "ProviderIds": null,
          "TagItems": null,
          "ExternalUrls": null,
          "SiblingIds": null,
          "UnknownField": true
        }
      """.trimIndent(),
    )

    assertEquals("item", item.Id)
    assertTrue(item.ImageTags.isEmpty())
    assertTrue(item.BackdropImageTags.isEmpty())
    assertTrue(item.Genres.isEmpty())
    assertTrue(item.Tags.isEmpty())
    assertTrue(item.ProductionLocations.isEmpty())
    assertTrue(item.ProviderIds.isEmpty())
    assertTrue(item.TagItems.isEmpty())
    assertTrue(item.ExternalUrls.isEmpty())
    assertTrue(item.SiblingIds.isEmpty())
  }
}
