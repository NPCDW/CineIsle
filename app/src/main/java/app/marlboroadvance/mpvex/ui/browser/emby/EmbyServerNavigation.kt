package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemPolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyImageServer
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.util.UUID

/** Restricted saved payload: no arbitrary routes, URLs, credentials, or large item models. */
@Serializable
sealed interface EmbyServerDestination {
  @Serializable data object Home : EmbyServerDestination
  @Serializable data class Detail(
    val itemId: String,
    val title: String,
    val detailEntryId: String = UUID.randomUUID().toString(),
  ) : EmbyServerDestination
  @Serializable data class Person(val personId: String, val name: String, val imageTag: String?) : EmbyServerDestination
}

/** One independent visit; descendants inherit its identity through back-stack ancestry. */
@Serializable
data class EmbyServerGateway(
  val serverRef: EmbyServerRef,
  val visitId: String,
  val target: EmbyServerDestination,
) : Screen {
  constructor(serverRef: EmbyServerRef, visitId: String, destination: Screen) : this(serverRef, visitId,
    when (destination) {
      EmbyServerHomeScreen -> EmbyServerDestination.Home
      is EmbyDetailScreen -> EmbyServerDestination.Detail(
        destination.itemId,
        destination.title,
        destination.detailEntryId,
      )
      is EmbyPersonScreen -> EmbyServerDestination.Person(destination.personId, destination.personName, destination.personImageTag)
      else -> error("Unsupported server entry destination")
    })

  val destination: Screen get() = when (val payload = target) {
    EmbyServerDestination.Home -> EmbyServerHomeScreen
    is EmbyServerDestination.Detail -> EmbyDetailScreen(
      payload.itemId,
      payload.title,
      payload.detailEntryId,
    )
    is EmbyServerDestination.Person -> EmbyPersonScreen(payload.personId, payload.name, payload.imageTag)
  }

  @Composable
  override fun Content() { destination.Content() }
}

/** Uses position, not current selection or item id: identical ids on different servers are valid. */
internal fun nearestEmbyServerGateway(stack: List<Screen>, entryIndex: Int): EmbyServerGateway? {
  if (entryIndex !in stack.indices) return null
  for (index in entryIndex downTo 0) {
    val entry = stack[index]
    if (entry is EmbyServerGateway) return entry
    // Never lend an earlier media visit to a new unbound layer root.
    if (entry == EmbyServerHomeScreen || entry == app.marlboroadvance.mpvex.ui.browser.MainScreen) return null
  }
  return null
}

/** Stable NavDisplay identity: replacing an episode must not create a second visual page. */
internal fun embyNavigationContentKey(
  route: Screen,
  gateway: EmbyServerGateway? = null,
  entryIndex: Int = -1,
): String = when {
  route is EmbyServerGateway && route.target is EmbyServerDestination.Detail ->
    "emby-detail/${route.visitId}/${route.target.detailEntryId}"
  route is EmbyDetailScreen && gateway != null ->
    "emby-detail/${gateway.visitId}/${route.detailEntryId}"
  route is EmbyDetailScreen -> "emby-detail/${route.detailEntryId}"
  gateway != null -> "emby-entry/${gateway.visitId}/$entryIndex/${route}"
  else -> route.toString()
}

/**
 * Atomically swaps only the current ordinary detail. A stale callback is rejected and never pushes.
 */
internal fun replaceTopEmbyDetail(
  stack: MutableList<Screen>,
  expectedDetailEntryId: String,
  itemId: String,
  title: String,
): Boolean {
  if (itemId.isBlank() || expectedDetailEntryId.isBlank()) return false
  val current = stack.lastOrNull() as? EmbyDetailScreen ?: return false
  if (current.detailEntryId != expectedDetailEntryId) return false
  stack[stack.lastIndex] = current.copy(itemId = itemId, title = title)
  return true
}

/**
 * Atomically swaps only the current gateway detail while retaining serverRef, visitId and entry ID.
 */
internal fun replaceTopEmbyGatewayDetail(
  stack: MutableList<Screen>,
  expectedDetailEntryId: String,
  itemId: String,
  title: String,
): Boolean {
  if (itemId.isBlank() || expectedDetailEntryId.isBlank()) return false
  val current = stack.lastOrNull() as? EmbyServerGateway ?: return false
  val detail = current.target as? EmbyServerDestination.Detail ?: return false
  if (detail.detailEntryId != expectedDetailEntryId) return false
  stack[stack.lastIndex] = current.copy(target = detail.copy(itemId = itemId, title = title))
  return true
}

/** One callback handles both stack shapes and deliberately has no push fallback. */
internal fun replaceTopEmbyDetailEntry(
  stack: MutableList<Screen>,
  expectedDetailEntryId: String,
  itemId: String,
  title: String,
): Boolean = when (stack.lastOrNull()) {
  is EmbyDetailScreen -> replaceTopEmbyDetail(stack, expectedDetailEntryId, itemId, title)
  is EmbyServerGateway -> replaceTopEmbyGatewayDetail(stack, expectedDetailEntryId, itemId, title)
  else -> false
}

@Composable
fun rememberServerDetailOpener(): (EmbyServer, String, String) -> Unit {
  val open = rememberServerDestinationOpener()
  return remember(open) { { server, id, title ->
    if (id.isNotBlank()) open(server, EmbyDetailScreen(id, title))
  } }
}

@Composable
fun rememberServerItemOpener(): (EmbyServer, EmbyItem) -> Unit {
  val open = rememberServerDestinationOpener()
  return remember(open) { { server, item ->
    val id = item.Id
    if (!id.isNullOrBlank() && EmbyItemPolicy.isSupported(item)) {
      val destination = if (item.Type == "Person") {
        EmbyPersonScreen(id, item.Name.orEmpty(), item.ImageTags["Primary"])
      } else EmbyDetailScreen(id, item.displayTitle())
      open(server, destination)
    }
  } }
}

@Composable
fun rememberServerHomeOpener(): (EmbyServer) -> Unit {
  val open = rememberServerDestinationOpener()
  return remember(open) { { server -> open(server, EmbyServerHomeScreen) } }
}

@Composable
private fun rememberServerDestinationOpener(): (EmbyServer, Screen) -> Unit {
  val stack = LocalBackStack.current
  val context = LocalContext.current
  val rows: EmbyServerRepository = koinInject()
  val sessions: EmbyRepository = koinInject()
  return remember(stack, context, rows, sessions) { { displayed, destination ->
    // A delayed tap is rechecked against live memory, without JDBC or global select+push.
    val fresh = resolveEmbyServerBinding(EmbyServerRef.from(displayed), rows.loadState.value, rows::isCurrent)
    val checked = fresh?.let { runCatching { sessions.sessionServer(it) }.getOrNull() }
    if (checked == null) {
      Toast.makeText(context, R.string.emby_servers_selection_failed, Toast.LENGTH_SHORT).show()
    } else {
      stack.add(EmbyServerGateway(EmbyServerRef.from(checked), UUID.randomUUID().toString(), destination))
    }
  } }
}

/** Legacy singleton callers bind once; they cannot migrate to a later global selection. */
@Composable
internal fun rememberCompatibilityHomeGateway(stack: List<Screen>): EmbyServerGateway? {
  if (EmbyServerHomeScreen !in stack) return null
  val repository: EmbyRepository = koinInject()
  val current by repository.currentServer.collectAsState()
  val initial = remember { current }
  val sourceKey = rememberSaveable { initial?.sourceKey.orEmpty() }
  val revision = rememberSaveable { initial?.sourceRevision ?: -1L }
  val remoteId = rememberSaveable { initial?.remoteId.orEmpty() }
  val visitId = rememberSaveable { UUID.randomUUID().toString() }
  return EmbyServerGateway(EmbyServerRef(sourceKey, revision, remoteId), visitId, EmbyServerHomeScreen)
}

/** Called above NavDisplay so popped visits (not merely hidden entries) release their entire store. */
@Composable
internal fun rememberServerNavigationStores(
  stack: List<Screen>,
  compatibilityHome: EmbyServerGateway? = null,
): ServerNavigationEntryStores {
  val activity = LocalContext.current.requireServerActivity()
  val stores: ServerNavigationEntryStores = viewModel(
    viewModelStoreOwner = activity,
    key = "emby-server-navigation-stores",
    factory = remember { viewModelFactory { initializer { ServerNavigationEntryStores() } } },
  )
  val rows: EmbyServerRepository = koinInject()
  val source by rows.sourceChanged.collectAsState()
  val active = (stack.filterIsInstance<EmbyServerGateway>() + listOfNotNull(compatibilityHome)).filter {
    shouldRetainEmbyServerVisit(it.serverRef, source)
  }.mapTo(mutableSetOf()) { it.visitId }
  SideEffect { stores.retain(active) }
  DisposableEffect(activity, stores) {
    onDispose { if (!activity.isChangingConfigurations) stores.retain(emptySet()) }
  }
  return stores
}

/** Scope existing Classic/Cine detail/person/descendant factories before invoking the destination. */
@Composable
internal fun ServerBoundEntry(
  gateway: EmbyServerGateway,
  destination: Screen = gateway.destination,
  stores: ServerNavigationEntryStores,
  content: @Composable () -> Unit,
) {
  val rows: EmbyServerRepository = koinInject()
  val state by rows.loadState.collectAsState()
  val source by rows.sourceChanged.collectAsState()
  val fresh = if (shouldRetainEmbyServerVisit(gateway.serverRef, source)) {
    resolveEmbyServerBinding(gateway.serverRef, state, rows::isCurrent)
  } else null
  if (fresh == null) {
    ServerBindingUnavailable()
    return
  }
  val application = LocalContext.current.applicationContext as Application
  val owner = remember(stores, gateway.visitId) { stores.owner(gateway.visitId) }
  // Same default ViewModelProvider key as every existing child viewModel(factory=...) call.
  val vm = remember(owner, application) {
    ViewModelProvider(owner, EmbyViewModel.factory(application, fresh))[EmbyViewModel::class.java]
  }
  val server by vm.currentServer.collectAsState()
  if (server == null) {
    ServerBindingUnavailable()
    return
  }
  CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalEmbyImageServer provides server) {
    if (destination is EmbyDetailScreen) {
      key(destination.itemId) { content() }
    } else {
      content()
    }
  }
}

@Composable
internal fun ServerBindingUnavailable() {
  val stack = LocalBackStack.current
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(stringResource(R.string.emby_servers_selection_failed), color = MaterialTheme.colorScheme.onSurface)
    TextButton(onClick = { stack.removeLastOrNull() }) {
      Text(stringResource(R.string.emby_servers_back))
    }
  }
}

internal tailrec fun Context.requireServerActivity(): ComponentActivity = when (this) {
  is ComponentActivity -> this
  is ContextWrapper -> baseContext.requireServerActivity()
  else -> error("Server navigation requires a ComponentActivity")
}
