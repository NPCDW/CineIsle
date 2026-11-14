package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPlaylistProjectionTest {
  @Test
  fun `partial hydration never drops or reorders queue positions`() {
    val seeds = listOf(
      seed("Queue one", metadata("one", "Spec one")),
      seed("Queue two", metadata("two", "Spec two")),
      seed("Queue three", metadata("three", "Spec three")),
    )
    val hydration = listOf(
      EmbyItem(Id = "three", Name = "Remote three"),
      EmbyItem(Id = "one", Name = "Remote one"),
    )

    val result = projectPlaylistDisplayEntries(seeds, hydration)

    assertEquals(listOf("one", "two", "three"), result.map { it.itemId })
    assertEquals(listOf("Queue one", "Queue two", "Queue three"), result.map { it.title })
  }

  @Test
  fun `backdrop is primary image with primaryImageRef as fallback`() {
    val item = EmbyItem(
      Id = "episode",
      BackdropImageTags = listOf("backdrop-tag"),
      PrimaryImageItemId = "series",
      PrimaryImageTag = "primary-tag",
    )

    val entry = projectPlaylistDisplayEntries(
      listOf(seed("Episode", metadata("episode", "Episode"))),
      listOf(item),
    ).single()

    assertEquals(
      PlaylistImageRef("episode", PlaylistImageType.Backdrop, "backdrop-tag"),
      entry.image,
    )
    assertEquals(
      PlaylistImageRef("series", PlaylistImageType.Primary, "primary-tag"),
      entry.fallbackImage,
    )
  }

  @Test
  fun `playback image metadata survives hydration that omits image fields`() {
    val playback = metadata("episode", "Episode").copy(
      primaryImageItemId = "series",
      primaryImageTag = "registered-primary",
      backdropImageTag = "registered-backdrop",
    )

    val entry = projectPlaylistDisplayEntries(
      listOf(seed("Episode", playback)),
      listOf(EmbyItem(Id = "episode")),
    ).single()

    assertEquals(
      PlaylistImageRef("episode", PlaylistImageType.Backdrop, "registered-backdrop"),
      entry.image,
    )
    assertEquals(
      PlaylistImageRef("series", PlaylistImageType.Primary, "registered-primary"),
      entry.fallbackImage,
    )
  }

  @Test
  fun `runtime falls back from selected remote source to item then playback spec`() {
    val seeds = listOf(
      seed("Selected source", metadata("source", "Source", ticks = 10).copy(explicitMediaSourceId = "chosen")),
      seed("Remote item", metadata("item", "Item", ticks = 11)),
      seed("Playback spec", metadata("spec", "Spec", ticks = 12)),
    )
    val hydration = listOf(
      EmbyItem(
        Id = "source",
        RunTimeTicks = 20,
        MediaSources = listOf(
          EmbyMediaSource(Id = "other", RunTimeTicks = 21),
          EmbyMediaSource(Id = "chosen", RunTimeTicks = 30),
        ),
      ),
      EmbyItem(
        Id = "item",
        RunTimeTicks = 22,
        MediaSources = listOf(EmbyMediaSource(Id = "default", RunTimeTicks = null)),
      ),
    )

    assertEquals(
      listOf(30L, 22L, 12L),
      projectPlaylistDisplayEntries(seeds, hydration).map { it.runTimeTicks },
    )
  }

  private fun seed(title: String, playback: PlaylistPlaybackMetadata) = PlaylistDisplaySeed(
    queueTitle = title,
    queueItemId = playback.itemId,
    playback = playback,
  )

  private fun metadata(
    id: String,
    title: String,
    ticks: Long? = null,
  ) = PlaylistPlaybackMetadata(
    itemId = id,
    title = title,
    itemName = title,
    runTimeTicks = ticks,
    primaryImageItemId = null,
    primaryImageTag = null,
    backdropImageTag = null,
    explicitMediaSourceId = null,
    selectPolicy = "auto",
    versionSelect = 1,
  )
}
