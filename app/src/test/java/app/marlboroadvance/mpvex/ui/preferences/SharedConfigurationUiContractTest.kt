package app.marlboroadvance.mpvex.ui.preferences

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SharedConfigurationUiContractTest {
  private fun source(name: String): String {
    val candidates = listOf(
      File("src/main/java/app/marlboroadvance/mpvex/ui/preferences/$name"),
      File("app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/$name"),
    )
    return candidates.first { it.isFile }.readText()
  }

  @Test fun `route is no-field serializable object and screen never saveables sensitive data`() {
    val screen = source("SharedConfigurationPreferencesScreen.kt")
    val viewModel = source("SharedConfigurationViewModel.kt")
    assertTrue(screen.contains("object SharedConfigurationPreferencesScreen : Screen"))
    assertFalse(screen.contains("data class SharedConfigurationPreferencesScreen"))
    assertFalse(screen.contains("rememberSaveable"))
    assertFalse(Regex("\\bSavedState(Handle)?\\s*[<(]").containsMatchIn(screen))
    assertFalse(Regex("\\bSavedStateHandle\\s*[<(]").containsMatchIn(viewModel))
    assertTrue(viewModel.contains("No SavedStateHandle"))
  }

  @Test fun `screen is structurally read-only with only back refresh and expand actions`() {
    val screen = source("SharedConfigurationPreferencesScreen.kt")
    listOf("OutlinedTextField", "TextField(", "Switch(", "SwitchPreference", "Slider", "DropdownMenu", "onValueChange").forEach {
      assertFalse("editable control $it", screen.contains(it))
    }
    assertTrue(screen.contains("contentDescription = \"返回\""))
    assertTrue(screen.contains("contentDescription = \"刷新\""))
    assertTrue(screen.contains("if (expanded) \"收起\" else \"展开\""))
    assertTrue(screen.contains("请在 loemby 桌面端修改"))
    assertTrue(screen.contains("只读"))
    assertTrue(screen.contains("旧快照"))
  }

  @Test fun `preferences entry follows PostgreSQL and search index routes directly`() {
    val preferences = source("PreferencesScreen.kt")
    val postgres = preferences.indexOf("backstack.add(PostgresPreferencesScreen)")
    val shared = preferences.indexOf("backstack.add(SharedConfigurationPreferencesScreen)")
    assertTrue(postgres >= 0)
    assertTrue(shared > postgres)
    val index = source("SearchablePreferenceIndex.kt")
    assertTrue(index.contains("title = \"共享配置（只读）\""))
    assertTrue(index.contains("screen = SharedConfigurationPreferencesScreen"))
  }

  @Test fun `UI snapshot has no serialization or parcelable contracts and toString is redacted`() {
    val model = File(
      listOf(
        File("src/main/java/app/marlboroadvance/mpvex/domain/postgres/SharedConfiguration.kt"),
        File("app/src/main/java/app/marlboroadvance/mpvex/domain/postgres/SharedConfiguration.kt"),
      ).first { it.isFile }.path,
    ).readText()
    assertFalse(model.contains("@Serializable\nclass SharedConfigurationSnapshot"))
    assertFalse(model.contains("SharedConfigurationSnapshot : Parcelable"))
    assertFalse(model.contains("SharedConfigurationSnapshot : Serializable"))
    assertTrue(model.contains("values=[redacted]"))
  }
}
