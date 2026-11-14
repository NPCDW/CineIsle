package app.marlboroadvance.mpvex.ui.postgres

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigStorage
import app.marlboroadvance.mpvex.domain.postgres.PostgresConnectionChecker
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.util.UUID

@Serializable
object PostgresPreferencesScreen : Screen {
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val storage = koinInject<PostgresConfigStorage>()
    val checker = koinInject<PostgresConnectionChecker>()
    val activity = LocalContext.current.requireActivity()
    // NavDisplay currently has no ViewModelStore entry decorator. Keep independent entry stores
    // in an Activity VM, retaining them across rotation but clearing them on actual entry disposal.
    // Only this opaque identifier is saveable, NEVER the configuration or clipboard content.
    val entryId = rememberSaveable { UUID.randomUUID().toString() }
    val stores: PostgresEntryStores = viewModel(
      viewModelStoreOwner = activity,
      key = "postgres-preferences-entry-stores",
      factory = remember { viewModelFactory { initializer { PostgresEntryStores() } } },
    )
    val entryOwner = remember(stores, entryId) { stores.owner(entryId) }
    val setupViewModel: PostgresSetupViewModel = viewModel(
      viewModelStoreOwner = entryOwner,
      key = "postgres-preferences-editor",
      factory = PostgresSetupViewModel.factory(storage, checker, autoConnect = false),
    )
    DisposableEffect(activity, stores, entryId) {
      onDispose {
        if (!activity.isChangingConfigurations) stores.release(entryId)
      }
    }
    var closing by remember { mutableStateOf(false) }
    val closeEntry: () -> Unit = {
      if (!closing) {
        closing = true
        // Invalidate the checker immediately, not only after the outgoing animation ends.
        setupViewModel.cancel()
        backStack.removeLastOrNull()
      }
    }
    PostgresSetupForm(
      viewModel = setupViewModel,
      onSuccess = closeEntry,
      onBack = closeEntry,
    )
  }
}

private class PostgresEntryStores : ViewModel() {
  private val entries = mutableMapOf<String, ViewModelStoreOwner>()

  fun owner(id: String): ViewModelStoreOwner = entries.getOrPut(id) {
    object : ViewModelStoreOwner {
      override val viewModelStore = ViewModelStore()
    }
  }

  fun release(id: String) {
    entries.remove(id)?.viewModelStore?.clear()
  }

  override fun onCleared() {
    entries.values.forEach { it.viewModelStore.clear() }
    entries.clear()
  }
}

private tailrec fun Context.requireActivity(): ComponentActivity = when (this) {
  is ComponentActivity -> this
  is ContextWrapper -> baseContext.requireActivity()
  else -> error("Postgres preferences require a ComponentActivity")
}
