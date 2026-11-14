package app.marlboroadvance.mpvex.ui.cine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.AppLayer
import app.marlboroadvance.mpvex.ui.browser.AppTab
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchScreen
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.OuterTabContent
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.collection.EmbyCollectionScreen
import app.marlboroadvance.mpvex.ui.browser.emby.discover.EmbyDiscoverScreen
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoGridScreen
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHazeEnabled
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.components.CineDock
import app.marlboroadvance.mpvex.ui.cine.components.CineDrawerEntry
import app.marlboroadvance.mpvex.ui.cine.components.CineDrawerOverlay
import app.marlboroadvance.mpvex.ui.cine.components.CineRail
import app.marlboroadvance.mpvex.ui.cine.list.CineFavoritesScreen
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import org.koin.compose.koinInject

/** Outer and server shells share the Dock/Rail presentation, not their destinations or state. */
@Composable
fun CineRoot(
  tabIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  serverHome: Boolean = false,
) {
  val layer = if (serverHome) AppLayer.Server else AppLayer.Outer
  val space = CineTheme.space
  val wide = rememberCineWide()
  val backStack = LocalBackStack.current
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showNavLabels = !appearancePreferences.hideNavigationBarLabels.collectAsState().value
  var dockHidden by remember { mutableStateOf(false) }
  // Home reports only scroll start/stop. Providing the gate here also covers the sibling Dock/Rail,
  // while the Lazy content keeps its Haze source and layout unchanged.
  var pageScrolling by remember { mutableStateOf(false) }
  var drawerOpen by remember { mutableStateOf(false) }
  var grid by remember { mutableStateOf<CineGridPayload?>(null) }
  val selectedTab = layer.tabAt(tabIndex)
  // Only the selected destination is composed. The holder retains each tab's saveable Lazy state
  // after disposal, so switching tabs does not trade scroll restoration for background composition.
  val tabStateHolder = rememberSaveableStateHolder()

  fun selectTab(tab: AppTab) {
    val index = layer.tabs.indexOf(tab)
    if (index < 0) return
    onTabSelected(index)
    dockHidden = false
    pageScrolling = false
  }

  fun backToServers() {
    drawerOpen = false
    backStack.removeLastOrNull()
  }

  fun openDetail(item: EmbyItem) {
    if (!EmbyItemPolicy.isSupported(item)) return
    val id = item.Id ?: return
    if (item.Type == "Person") {
      backStack.add(EmbyPersonScreen(personId = id, personName = item.Name ?: "", personImageTag = item.ImageTags["Primary"]))
    } else {
      backStack.add(EmbyDetailScreen(itemId = id, title = item.displayTitle()))
    }
  }

  fun openLibrary(library: EmbyItem) {
    if (!EmbyItemPolicy.isSupported(library)) return
    val id = library.Id ?: return
    if (library.CollectionType?.equals("photos", ignoreCase = true) == true) {
      backStack.add(EmbyPhotoGridScreen(libraryId = id, title = library.Name ?: ""))
    } else {
      backStack.add(EmbyLibraryScreen(libraryId = id, title = library.Name ?: "", collectionType = library.CollectionType))
    }
  }

  fun openDrawerEntry(entry: CineDrawerEntry) {
    drawerOpen = false
    when (entry) {
      CineDrawerEntry.Discover -> backStack.add(EmbyDiscoverScreen)
      CineDrawerEntry.Collections -> backStack.add(EmbyCollectionScreen)
      CineDrawerEntry.ServerManage -> backToServers()
      CineDrawerEntry.Settings -> backStack.add(PreferencesScreen)
    }
  }

  // Only the outer shell treats Back as returning to its default tab.
  BackHandler(enabled = !serverHome && tabIndex != 0 && !drawerOpen && grid == null) {
    selectTab(layer.defaultTab)
  }

  @Composable
  fun TabContent() {
    tabStateHolder.SaveableStateProvider(selectedTab) {
      if (!serverHome) {
        Box(Modifier.fillMaxSize().cineHazeSource(LocalCineHaze.current)) {
          OuterTabContent(selectedTab, onOpenSettings = { selectTab(AppTab.Settings) })
        }
      } else {
        when (selectedTab) {
          AppTab.Home -> CineHomeScreen(
            onOpenDetail = ::openDetail,
            onOpenLibrary = ::openLibrary,
            onOpenDrawer = { drawerOpen = true },
            onOpenServerManage = ::backToServers,
            onBackToServers = ::backToServers,
            onOpenAll = { title, items -> grid = CineGridPayload(title, items) },
            onDockHiddenChange = { dockHidden = it },
            onPageScrollInProgressChange = { pageScrolling = it },
          )
          AppTab.Favorites -> CineFavoritesScreen(onBackToServers = ::backToServers)
          AppTab.Search -> Box(Modifier.fillMaxSize().cineHazeSource(LocalCineHaze.current)) {
            EmbySearchScreen(onBack = ::backToServers)
          }
          else -> error("Not a server destination: $selectedTab")
        }
      }
    }
  }

  CompositionLocalProvider(
    LocalNavigationBarHeight provides if (wide) 0.dp else space.contentBottomInset,
    LocalCineHazeEnabled provides !pageScrolling,
  ) {
    Box(modifier = modifier.fillMaxSize()) {
      if (wide) {
        Row(Modifier.fillMaxSize()) {
          CineRail(
            tabs = layer.tabs,
            selected = selectedTab,
            onSelect = ::selectTab,
            showLabels = showNavLabels,
            modifier = Modifier.fillMaxHeight(),
          )
          Box(Modifier.weight(1f).fillMaxHeight()) { TabContent() }
        }
      } else {
        TabContent()
        if (grid == null) {
          CineDock(
            tabs = layer.tabs,
            selected = selectedTab,
            onSelect = ::selectTab,
            hidden = dockHidden,
            showLabels = showNavLabels,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
      grid?.let { payload ->
        CineGridOverlay(
          title = payload.title,
          items = payload.items,
          onClose = { grid = null; dockHidden = false },
          onOpenDetail = ::openDetail,
        )
      }
      // Keep the drawer composed for its exit animation; it consumes Back before the route.
      if (serverHome) {
        CineDrawerOverlay(
          visible = drawerOpen,
          onDismiss = { drawerOpen = false },
          onNavigate = ::openDrawerEntry,
        )
      }
    }
  }
}

private data class CineGridPayload(val title: String, val items: List<CineMediaUi>)
