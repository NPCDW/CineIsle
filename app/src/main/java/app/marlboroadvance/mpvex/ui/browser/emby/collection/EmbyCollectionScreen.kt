package app.marlboroadvance.mpvex.ui.browser.emby.collection

import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonList
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable

@Serializable
object EmbyCollectionScreen : Screen {
  @Composable override fun Content() { CollectionBrowser() }
}

@Serializable
data class EmbyCollectionItemsScreen(val collectionId: String, val title: String) : Screen {
  @Composable override fun Content() { CollectionBrowser(collectionId, title) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionBrowser(collectionId: String? = null, title: String = "合集") {
  val app = LocalContext.current.applicationContext as Application
  val vm: EmbyCollectionViewModel = viewModel(factory = EmbyCollectionViewModel.factory(app))
  val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by embyVm.currentServer.collectAsState()
  CompositionLocalProvider(LocalEmbyImageServer provides server) {
  val collections by vm.collections.collectAsState()
  val contents by vm.items.collectAsState()
  val listLoading by vm.listLoading.collectAsState()
  val itemsLoading by vm.itemsLoading.collectAsState()
  val error by vm.error.collectAsState()
  val backStack = LocalBackStack.current
  val rows = if (collectionId == null) collections else contents
  val loading = if (collectionId == null) listLoading else itemsLoading
  fun refresh() {
    server?.let { if (collectionId == null) vm.loadCollections(it, force = true) else vm.loadItems(it, collectionId) }
  }
  LaunchedEffect(server, collectionId) { refresh() }
  Scaffold(topBar = {
    TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      navigationIcon = { IconButton(onClick = { backStack.removeLastOrNull() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
      actions = { IconButton(onClick = ::refresh, enabled = server != null && !loading) { Icon(Icons.Default.Refresh, "刷新") } })
  }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        TextButton(onClick = ::refresh) { Text("重试") }
      }
      when {
        server == null -> Text("请先选择服务器。", modifier = Modifier.padding(16.dp))
        loading && rows.isEmpty() -> EmbySkeletonList(itemCount = 6)
        rows.isEmpty() && error == null -> Text("暂无内容，请在桌面端维护后刷新。", modifier = Modifier.padding(16.dp))
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
          items(rows, key = { it.Id ?: it.hashCode().toString() }) { item ->
            ListItem(
              modifier = Modifier.clickable {
                item.Id?.let { id ->
                  val destination = if (collectionId == null) {
                    EmbyCollectionItemsScreen(id, item.Name.orEmpty())
                  } else {
                    EmbyDetailScreen(id, item.displayTitle())
                  }
                  backStack.add(destination)
                }
              },
              headlineContent = { Text(item.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
              supportingContent = { Text(if (collectionId == null) "${item.ChildCount ?: 0} 项" else listOfNotNull(item.ProductionYear?.toString(), item.Type).joinToString(" · ")) },
              leadingContent = { EmbyImage(url = server?.let { embyVm.imageUrl(it, item, "Primary", 180) }, contentDescription = item.Name, modifier = Modifier.size(48.dp, 64.dp)) },
            )
          }
        }
      }
    }
  }
  }
}
