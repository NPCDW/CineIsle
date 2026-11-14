package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EmbyItemPolicyTest {
  private fun item(id: String, type: String?) = EmbyItem(Id = id, Type = type)

  @Test fun `all removed types and compatibility aliases are unsupported`() {
    listOf("Playlist", "TvChannel", "LiveTvChannel", "Program", "LiveTvProgram").forEach { type ->
      assertFalse(type, EmbyItemPolicy.isSupported(type))
      assertFalse(type, EmbyItemPolicy.isSupported(item(type, type)))
      assertFalse(type, EmbyItemPolicy.isSupported("  ${type.lowercase()}  "))
    }
  }

  @Test fun `ordinary media containers recordings photos and unknown types are preserved`() {
    listOf("Movie", "Series", "Season", "Episode", "BoxSet", "Folder", "CollectionFolder",
      "UserView", "Person", "Genre", "Studio", "Audio", "MusicAlbum", "VideoChannel",
      "Recording", "Photo", "PhotoAlbum", "FutureMediaType", "", null).forEach { type ->
      assertTrue(type, EmbyItemPolicy.isSupported(type))
      assertTrue(type, EmbyItemPolicy.isSupported(item("ordinary", type)))
    }
  }

  @Test fun `live television and playlist virtual views cannot bypass item type policy`() {
    listOf("livetv", "playlists", " LiveTV ", " PlayLists ").forEach { collection ->
      assertFalse(EmbyItemPolicy.isSupported(EmbyItem(Type = "UserView", CollectionType = collection)))
    }
    listOf("movies", "tvshows", "music", "photos", "mixed", "boxsets", null).forEach { collection ->
      assertTrue(EmbyItemPolicy.isSupported(EmbyItem(Type = "CollectionFolder", CollectionType = collection)))
    }
  }

  @Test fun `filter preserves supported order and duplicate raw positions`() {
    val movie = item("movie", "Movie")
    val raw = EmbyItemsResult(listOf(item("pl", "Playlist"), movie, movie,
      item("channel", "TvChannel"), item("episode", "Episode")), TotalRecordCount = 25)
    val filtered = EmbyItemPolicy.filterSupported(raw)
    assertEquals(listOf("movie", "movie", "episode"), filtered.Items.map { it.Id })
    assertEquals(5, filtered.consumedCount)
    assertEquals(25, filtered.TotalRecordCount)
    assertEquals(5, raw.Items.size)
  }

  @Test fun `fully hidden page still advances across server offset space`() {
    val pages = listOf(
      EmbyItemsResult(listOf(item("pl", "Playlist"), item("channel", "TvChannel")), 4),
      EmbyItemsResult(listOf(item("program", "LiveTvProgram"), item("movie", "Movie")), 4),
    )
    var consumed = 0
    val visible = mutableListOf<EmbyItem>()
    pages.map(EmbyItemPolicy::filterSupported).forEach { page ->
      assertTrue(page.consumedCount > 0)
      consumed += page.consumedCount
      visible += page.Items
    }
    assertEquals(4, consumed)
    assertEquals(listOf("movie"), visible.map { it.Id })
  }

  @Test fun `filtering repeatedly keeps original count and server total`() {
    val raw = EmbyItemsResult(listOf(item("pl", "Playlist"), item("movie", "Movie")), 100)
    val once = EmbyItemPolicy.filterSupported(raw)
    assertEquals(once, EmbyItemPolicy.filterSupported(once))
    assertEquals(2, once.consumedCount)
    assertEquals(100, once.TotalRecordCount)
  }

  @Test fun `empty raw result consumes zero positions`() {
    val result = EmbyItemPolicy.filterSupported(EmbyItemsResult())
    assertTrue(result.Items.isEmpty())
    assertEquals(0, result.consumedCount)
  }

  @Test fun `consumed count comes from decoded Items not an untrusted JSON field`() {
    val json = Json { ignoreUnknownKeys = true }
    val raw = json.decodeFromString<EmbyItemsResult>("""{"Items":[{"Id":"pl","Type":"Playlist"},{"Id":"movie","Type":"Movie"}],"TotalRecordCount":10,"consumedCount":999}""")
    assertEquals(2, raw.consumedCount)
    assertEquals(2, EmbyItemPolicy.filterSupported(raw).consumedCount)
    assertFalse(json.encodeToString(raw).contains("consumedCount"))
  }

  @Test fun `query exclusions retain caller folder filters and add every removed type`() {
    val exclusions = EmbyItemPolicy.queryExclusions(listOf("Folder", "CollectionFolder", "playlist"))
    assertTrue(exclusions.contains("Folder"))
    assertTrue(exclusions.contains("CollectionFolder"))
    EmbyItemPolicy.unsupportedTypes.forEach { type ->
      assertEquals(1, exclusions.count { it.equals(type, ignoreCase = true) })
    }
    assertFalse(exclusions.contains("Recording"))
    assertFalse(exclusions.contains("VideoChannel"))
  }

  @Test fun `single item guard rejects all unsupported types but preserves ordinary identity`() {
    EmbyItemPolicy.unsupportedTypes.forEach { type ->
      val failure = runCatching { EmbyItemPolicy.requireSupported(item("forbidden", type)) }.exceptionOrNull()
      assertTrue(type, failure is IllegalArgumentException)
    }
    assertTrue(runCatching {
      EmbyItemPolicy.requireSupported(EmbyItem(Type = "UserView", CollectionType = "livetv"))
    }.isFailure)
    val movie = item("movie", "Movie")
    assertSame(movie, EmbyItemPolicy.requireSupported(movie))
  }
}
