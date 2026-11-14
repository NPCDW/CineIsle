package app.marlboroadvance.mpvex.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigationTest {
  @Test
  fun `outer tabs follow requested order`() {
    assertThat(AppLayer.Outer.tabs).containsExactly(
      AppTab.Servers, AppTab.AggregateSearch, AppTab.History, AppTab.Settings,
    ).inOrder()
    assertThat(AppLayer.Outer.defaultTab).isEqualTo(AppTab.Servers)
  }

  @Test
  fun `server tabs follow requested order`() {
    assertThat(AppLayer.Server.tabs).containsExactly(
      AppTab.Home, AppTab.Favorites, AppTab.Search,
    ).inOrder()
    assertThat(AppLayer.Server.defaultTab).isEqualTo(AppTab.Home)
  }

  @Test
  fun `each layer owns only its destinations`() {
    assertThat(AppLayer.Outer.tabs.intersect(AppLayer.Server.tabs.toSet())).isEmpty()
    assertThat(AppLayer.Server.tabs).doesNotContain(AppTab.History)
    assertThat(AppLayer.Outer.tabs).doesNotContain(AppTab.Favorites)
  }

  @Test
  fun `invalid restored indices fall back to the layer default`() {
    AppLayer.entries.forEach { layer ->
      assertThat(layer.tabAt(-1)).isEqualTo(layer.defaultTab)
      assertThat(layer.tabAt(layer.tabs.size)).isEqualTo(layer.defaultTab)
    }
  }

  @Test
  fun `home selection stays within the server layer`() {
    val layer = AppLayer.Server
    val index = layer.tabs.indexOf(AppTab.Home)
    assertThat(index).isEqualTo(0)
    assertThat(layer.tabAt(index)).isEqualTo(AppTab.Home)
    assertThat(layer.tabAt(layer.tabs.indexOf(AppTab.Search))).isEqualTo(AppTab.Search)
    assertThat(AppLayer.Outer.defaultTab).isEqualTo(AppTab.Servers)
  }
}
