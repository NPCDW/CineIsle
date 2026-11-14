package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import org.junit.Assert.*
import org.junit.Test

class EmbyMediaCardNavigationTest {
  @Test fun episodeCardOnlyOpensDetail() {
    listOf("Episode", "episode").forEach { type ->
      val item = EmbyItem(Id = "episode", Type = type)
      var detail: EmbyItem? = null
      var play = false
      openEmbyMediaCard(item, onOpenDetail = { detail = it }, onPlay = { play = true })
      assertEquals(item, detail)
      assertFalse(play)
    }
  }

  private fun source(path: String): String = sequenceOf(
    java.io.File("src/main/java/$path"), java.io.File("app/src/main/java/$path"),
  ).first { it.isFile }.readText()

  @Test fun episodeLayoutKeepsInlineSelectorsAndUsesSharedEqualHeightInformation() {
    val content = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbyEpisodeDetailContent.kt")
    assertFalse(content.contains("Switch("))
    assertFalse(content.contains("episode-chapters"))
    assertFalse(content.contains("loadEpisodeChapters"))
    assertTrue(content.contains("useDirectLink = forceDirect && supportsDirect"))
    val selector = content.substringAfter("private fun EpisodeSelector").substringBefore("private fun DetailRetry")
    assertTrue(selector.contains("Row(modifier = Modifier.fillMaxWidth()"))
    assertTrue(selector.contains("episodeSelectorCanChoose(options)"))
    assertTrue(selector.contains("shape = MaterialTheme.shapes.small"))
    assertTrue(content.contains("val mediaInfoHeight = rememberDetailMediaInfoHeight(groups)"))
    assertTrue(content.contains("DetailMediaInfoGroup(group, mediaInfoHeight)"))
    assertTrue(content.contains("val facts = listOfNotNull("))
    val facts = content.substringAfter("val facts = listOfNotNull(").substringBefore("if (facts.isNotEmpty())")
    assertTrue(facts.contains("item.PremiereDate"))
    assertTrue(facts.contains("episodeVersionDuration(source)"))
    assertFalse(facts.contains("Size"))
    assertFalse(facts.contains("Bitrate"))
    assertFalse(content.contains("episodeVersionFacts"))
    assertTrue(content.contains("episodeVersionOption(source, selected.version - 1).label"))
    assertTrue(content.contains("EpisodeOptionText(options.firstOrNull"))
    assertTrue(content.contains("DropdownMenuItem(text = { EpisodeOptionText(option) }"))
    assertFalse(content.contains("DetailMovieMetadata"))
    assertFalse(content.contains("onOpenGenre"))
    assertFalse(content.contains("onOpenTag"))
    assertFalse(content.contains("onOpenStudio"))
    assertFalse(content.contains("item.CommunityRating"))
    assertFalse(content.contains("refreshBusy"))
    assertFalse(content.contains("Icons.Default.Refresh"))

    val info = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbyDetailMediaInfo.kt")
    assertTrue(info.indexOf("item(key = \"version-0\")") < info.indexOf("itemsIndexed(group.tracks"))
    assertTrue(info.contains("detailInfoText(row)"))
    assertTrue(info.contains(".height(height)"))
  }

  @Test fun seriesAndSeasonCardsOpenDedicatedDetailsAndExplicitPlaybackRemainsAvailable() {
    val library = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyLibraryScreen.kt")
    val open = library.substringAfter("private fun openItem(")
    assertTrue(open.contains("\"Series\" -> backStack.add(EmbyDetailScreen"))
    assertFalse(open.substringBefore("\"PhotoAlbum\"").contains("includeItemTypes = listOf(\"Season\")"))
    val season = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbySeasonDetailContent.kt")
    assertTrue(season.contains("onClick = onOpenDetail"))
    assertFalse(season.contains("viewModel.play("))
    val episode = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbyEpisodeDetailContent.kt")
    assertTrue(episode.contains("Button(onClick = { startPlayback() }"))
    assertTrue(episode.contains("startPlayback(forceDirect = true)"))
  }

  @Test fun movieAndEpisodeUseSharedMediaDetailWhileBoxSetKeepsFallback() {
    val classic = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyDetailScreen.kt")
    val cine = source("app/marlboroadvance/mpvex/ui/cine/detail/CineDetailScreen.kt")
    listOf(classic, cine).forEach { detail ->
      assertTrue(detail.contains("Type.equals(\"Episode\", ignoreCase = true) ||"))
      assertTrue(detail.contains("Type.equals(\"Movie\", ignoreCase = true)"))
      assertTrue(detail.contains("EmbyMediaDetailContent("))
      assertFalse(detail.contains("Type.equals(\"BoxSet\", ignoreCase = true) || loaded.Type.equals(\"Movie\""))
      val sharedCall = detail.substringAfter("EmbyMediaDetailContent(").substringBefore("return@Box")
      assertFalse(sharedCall.contains("onOpenGenre"))
      assertFalse(sharedCall.contains("onOpenTag"))
      assertFalse(sharedCall.contains("onOpenStudio"))
    }
    val media = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbyEpisodeDetailContent.kt")
    assertTrue(media.contains("if (!isEpisode)"))
    assertTrue(media.contains("if (isEpisode && parents != null)"))
    assertTrue(media.contains("if (isEpisode) {\n      item(key = \"episode-season\")"))
  }

  @Test fun sharedMovieDetailDoesNotStartUnusedRecommendationWork() {
    val classic = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyDetailScreen.kt")
    val cine = source("app/marlboroadvance/mpvex/ui/cine/detail/CineDetailScreen.kt")
    assertTrue(classic.contains("listOf(\"Series\", \"Season\", \"Episode\", \"Movie\")"))
    assertTrue(cine.contains("!current.Type.equals(\"Movie\", ignoreCase = true)"))
  }

  @Test fun otherMediaRetainsOriginalClickBehavior() {
    listOf("Movie", "Series", "Season", "Video", null).forEach { type ->
      val item = EmbyItem(Id = "item", Type = type)
      var detail = false
      var play: EmbyItem? = null
      openEmbyMediaCard(item, onOpenDetail = { detail = true }, onPlay = { play = it })
      assertFalse(detail)
      assertEquals(item, play)
    }
  }
}
