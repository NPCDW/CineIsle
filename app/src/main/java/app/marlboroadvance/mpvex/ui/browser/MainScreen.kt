package app.marlboroadvance.mpvex.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyFavoritesScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyHomeScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.collection.EmbyCollectionScreen
import app.marlboroadvance.mpvex.ui.browser.emby.discover.EmbyDiscoverScreen
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoGridScreen
import app.marlboroadvance.mpvex.ui.cine.CineRoot
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.LocalUiStyle
import app.marlboroadvance.mpvex.ui.cine.UiStyle
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object MainScreen : Screen {
  @Composable
  override fun Content() {
    AppLayerRoot(AppLayer.Outer)
  }
}

@Composable
internal fun AppLayerRoot(layer: AppLayer) {
  // Save above the style branch so returning from Appearance keeps the same destination.
  var tabIndex by rememberSaveable(layer) { mutableIntStateOf(0) }
  if (LocalUiStyle.current == UiStyle.Cine) {
    CineTheme {
      CineRoot(tabIndex = tabIndex, onTabSelected = { tabIndex = it }, serverHome = layer == AppLayer.Server)
    }
  } else {
    ClassicNavigationShell(layer, tabIndex, onTabSelected = { tabIndex = it })
  }
}

@Composable
private fun ClassicNavigationShell(layer: AppLayer, tabIndex: Int, onTabSelected: (Int) -> Unit) {
  val selectedTab = layer.tabAt(tabIndex)
  val backStack = LocalBackStack.current
  val appearancePreferences = koinInject<AppearancePreferences>()
  val hideNavLabels by appearancePreferences.hideNavigationBarLabels.collectAsState()
  val onBackToServers = { backStack.removeLastOrNull(); Unit }
  // Keep only the selected tab composed, but retain its saveable Lazy/List state after disposal.
  val tabStateHolder = rememberSaveableStateHolder()

  // Inner tabs never consume Back as a Home selection: leaving the server pops its route.
  BackHandler(enabled = layer == AppLayer.Outer && selectedTab != layer.defaultTab) {
    onTabSelected(0)
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      NavigationBar(
        modifier = Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
      ) {
        layer.tabs.forEachIndexed { index, tab ->
          val label = stringResource(tab.labelRes)
          NavigationBarItem(
            icon = { Icon(tab.icon, contentDescription = label) },
            label = if (hideNavLabels) null else ({ Text(label) }),
            selected = selectedTab == tab,
            onClick = { onTabSelected(index) },
          )
        }
      }
    },
  ) { padding ->
    // The Scaffold reserves the real bar + system inset; child lists need no second bar inset.
    CompositionLocalProvider(LocalNavigationBarHeight provides 0.dp) {
      val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
      Box(Modifier.fillMaxSize().padding(bottomPadding).consumeWindowInsets(bottomPadding)) {
        tabStateHolder.SaveableStateProvider(selectedTab) {
          if (layer == AppLayer.Outer) {
            OuterTabContent(selectedTab, onOpenSettings = { onTabSelected(layer.tabs.indexOf(AppTab.Settings)) })
          } else {
            when (selectedTab) {
              AppTab.Home -> ClassicServerHome(onBackToServers)
              AppTab.Favorites -> EmbyFavoritesScreen(onBackToServers = onBackToServers)
              AppTab.Search -> EmbySearchScreen(onBack = onBackToServers)
              else -> error("Not a server destination: $selectedTab")
            }
          }
        }
      }
    }
  }
}

/** Existing Classic media-home navigation, hosted by the selected-server route. */
@Composable
internal fun ClassicServerHome(onBackToServers: () -> Unit) {
  val backStack = LocalBackStack.current
  EmbyHomeScreen(
    onOpenLibrary = { library ->
      if (!EmbyItemPolicy.isSupported(library)) return@EmbyHomeScreen
      val libraryId = library.Id ?: return@EmbyHomeScreen
      // 纯照片库直接进入照片网格；混合库仍使用媒体库页。
      if (library.CollectionType?.equals("photos", ignoreCase = true) == true) {
        backStack.add(EmbyPhotoGridScreen(libraryId = libraryId, title = library.Name ?: ""))
      } else {
        backStack.add(EmbyLibraryScreen(libraryId = libraryId, title = library.Name ?: "", collectionType = library.CollectionType))
      }
    },
    onOpenDetail = { item ->
      if (!EmbyItemPolicy.isSupported(item)) return@EmbyHomeScreen
      val id = item.Id ?: return@EmbyHomeScreen
      if (item.Type == "Person") {
        backStack.add(EmbyPersonScreen(personId = id, personName = item.Name ?: "", personImageTag = item.ImageTags["Primary"]))
      } else {
        backStack.add(EmbyDetailScreen(itemId = id, title = item.displayTitle()))
      }
    },
    onManageServers = onBackToServers,
    onBackToServers = onBackToServers,
    onOpenSettings = { backStack.add(PreferencesScreen) },
    onOpenDiscover = { backStack.add(EmbyDiscoverScreen) },
    onOpenCollections = { backStack.add(EmbyCollectionScreen) },
  )
}

val LocalNavigationBarHeight = compositionLocalOf { 0.dp }
