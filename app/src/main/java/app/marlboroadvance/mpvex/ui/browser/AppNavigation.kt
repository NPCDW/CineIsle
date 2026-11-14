package app.marlboroadvance.mpvex.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerSelectionScreen
import app.marlboroadvance.mpvex.ui.browser.history.PlaybackHistoryScreen
import app.marlboroadvance.mpvex.ui.preferences.PreferencesContent

/** Both UI styles share the same destinations, but each layer owns its tab state. */
enum class AppTab(val labelRes: Int, val icon: ImageVector) {
  Servers(R.string.app_nav_servers, Icons.Rounded.Dns),
  AggregateSearch(R.string.app_nav_aggregate_search, Icons.Rounded.Search),
  History(R.string.app_nav_playback_history, Icons.Rounded.History),
  Settings(R.string.app_nav_settings, Icons.Rounded.Settings),
  Home(R.string.app_nav_home, Icons.Rounded.Home),
  Favorites(R.string.app_nav_favorites, Icons.Rounded.Bookmark),
  Search(R.string.app_nav_search, Icons.Rounded.Search),
}

enum class AppLayer(val tabs: List<AppTab>) {
  Outer(listOf(AppTab.Servers, AppTab.AggregateSearch, AppTab.History, AppTab.Settings)),
  Server(listOf(AppTab.Home, AppTab.Favorites, AppTab.Search));

  val defaultTab: AppTab get() = tabs.first()
  fun tabAt(index: Int): AppTab = tabs.getOrElse(index) { defaultTab }
}

@Composable
internal fun OuterTabContent(tab: AppTab, onOpenSettings: () -> Unit) {
  when (tab) {
    AppTab.Servers -> EmbyServerSelectionScreen(showBack = false, onOpenSettings = onOpenSettings)
    AppTab.AggregateSearch -> EmbySearchScreen(aggregate = true)
    AppTab.History -> PlaybackHistoryScreen()
    AppTab.Settings -> PreferencesContent(showBack = false)
    else -> error("Not an outer destination: $tab")
  }
}
