package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry
import app.marlboroadvance.mpvex.ui.browser.emby.components.detailInfoText
import org.junit.Assert.*
import org.junit.Test

class DetailRefinementTest {
  private fun source(path: String): String = sequenceOf(java.io.File("src/main/java/$path"), java.io.File("app/src/main/java/$path"))
    .first { it.isFile }.readText()

  @Test fun logoCrossesFixedHeroBoundaryWithoutGlassAndUsesSurfaceGradient() {
    val hero = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbySeriesDetailContent.kt")
    assertTrue(hero.contains("SERIES_HERO_IMAGE_HEIGHT = 336.dp"))
    assertTrue(hero.contains("SERIES_LOGO_OVERLAP = SERIES_LOGO_HEIGHT / 2"))
    assertTrue(hero.contains("Spacer(Modifier.height(SERIES_HERO_IMAGE_HEIGHT - SERIES_LOGO_OVERLAP))"))
    assertTrue(hero.contains("Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))"))
    assertTrue(hero.contains("Modifier.fillMaxWidth().height(SERIES_LOGO_HEIGHT)"))
    assertFalse(hero.contains("hazeEffect("))
    assertFalse(hero.contains("hazeSource("))
    assertFalse(hero.contains("HazeStyle"))
  }

  @Test fun noCustomSplashAndStartupSecurityGatesRemain() {
    val activity = source("app/marlboroadvance/mpvex/MainActivity.kt")
    assertTrue(activity.contains("MediaNavigator"))
    assertTrue(activity.contains("if (postgresState.passed)"))
    assertTrue(activity.contains("PostgresSetupForm"))
  }

  @Test fun ordinaryAndPredictiveBackShareSlideFadeWithoutScale() {
    val activity = source("app/marlboroadvance/mpvex/MainActivity.kt")
    assertTrue(activity.contains("internal fun navigationSlideFadeTransform"))
    assertTrue(activity.contains("popTransitionSpec = { navigationSlideFadeTransform(forward = false) }"))
    assertTrue(activity.contains("predictivePopTransitionSpec = { navigationSlideFadeTransform(forward = false) }"))
    assertTrue(activity.contains("transitionSpec = { navigationSlideFadeTransform(forward = true) }"))
    assertFalse(activity.contains("scaleIn("))
    assertFalse(activity.contains("scaleOut("))
    assertFalse(activity.contains("TransformOrigin"))
  }

  @Test fun episodeSwitchContractsUseOneReplacerAndRebuildOnlyDetailContent() {
    val activity = source("app/marlboroadvance/mpvex/MainActivity.kt")
    val navigation = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyServerNavigation.kt")
    val classic = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyDetailScreen.kt")
    val cine = source("app/marlboroadvance/mpvex/ui/cine/detail/CineDetailScreen.kt")
    val routes = source("app/marlboroadvance/mpvex/ui/cine/CineRoutes.kt")
    val episode = source("app/marlboroadvance/mpvex/ui/browser/emby/components/EmbyEpisodeDetailContent.kt")
    val longPress = source("app/marlboroadvance/mpvex/ui/utils/LongPressCopy.kt")

    assertTrue(activity.contains("embyNavigationContentKey(route, gateway, index)"))
    assertTrue(activity.contains("ServerBoundEntry(gateway, destination, serverStores)"))
    assertTrue(navigation.contains("key(destination.itemId) { content() }"))
    assertTrue(classic.contains("replaceTopEmbyDetailEntry(backStack, detailEntryId, id, episode.displayTitle())"))
    assertTrue(cine.contains("replaceTopEmbyDetailEntry(backStack, detailEntryId, id, episode.displayTitle())"))
    assertTrue(routes.contains("detailEntryId = route.detailEntryId"))
    assertTrue(episode.contains("if (actionsBusy || launching"))
    assertTrue(episode.contains("focusManager.clearFocus(force = true)"))
    assertTrue(episode.contains("listState.scrollToTop()"))
    assertTrue(episode.contains("onTitleClick = confirmedSeries"))
    assertTrue(episode.contains("seasonEpisodeTitle(item)"))
    assertTrue(longPress.contains("this.combinedClickable("))
    assertTrue(longPress.contains("onClick = onClick ?: {}"))
  }

  @Test fun compactInformationUsesColonAndPreservesFullValues() {
    assertEquals("文件: /media/a b/movie.mkv", detailInfoText(MediaInfoEntry.Row("文件", "/media/a b/movie.mkv")))
    assertEquals(listOf("Movie", "Series", "Season", "Episode", "Video"), FavoriteMediaFilter.ALL.includeItemTypes)
    assertEquals(listOf("Movie"), FavoriteMediaFilter.MOVIE.includeItemTypes)
    assertEquals(listOf("Series"), FavoriteMediaFilter.SERIES.includeItemTypes)
  }
}
