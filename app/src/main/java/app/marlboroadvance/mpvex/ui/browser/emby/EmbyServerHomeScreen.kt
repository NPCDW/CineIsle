package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.AppLayer
import app.marlboroadvance.mpvex.ui.browser.AppLayerRoot
import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.util.UUID

/** Entered only after an explicit server selection, never from startup restoration. */
@Serializable
object EmbyServerHomeScreen : Screen {
  @Composable
  override fun Content() {
    val activity = LocalContext.current.requireServerActivity()
    val inherited = LocalViewModelStoreOwner.current
    val entryId = rememberSaveable { UUID.randomUUID().toString() }
    val stores: ServerHomeEntryStores = viewModel(
      viewModelStoreOwner = activity,
      key = "emby-server-home-entry-stores",
      factory = remember { viewModelFactory { initializer { ServerHomeEntryStores() } } },
    )
    val owner = remember(stores, entryId, inherited, activity) {
      if (inherited != null && inherited !== activity) inherited else stores.owner(entryId)
    }
    DisposableEffect(activity, stores, entryId) {
      onDispose {
        if (!activity.isChangingConfigurations) stores.release(entryId)
      }
    }
    val repository: EmbyRepository = koinInject()
    // Compatibility direct callers also capture once, never keep following currentServer.
    val initial = remember { repository.currentServer.value }
    if ((inherited == null || inherited === activity) && initial == null) {
      ServerBindingUnavailable()
      return
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
      val embyViewModel: EmbyViewModel = viewModel(
        factory = EmbyViewModel.factory(activity.applicationContext as Application, initial),
      )
      val server by embyViewModel.currentServer.collectAsState()
      if (server == null) {
        ServerBindingUnavailable()
      } else CompositionLocalProvider(LocalEmbyImageServer provides server) {
        AppLayerRoot(AppLayer.Server)
      }
    }
  }
}

internal class ServerHomeEntryStores : ViewModel() {
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
