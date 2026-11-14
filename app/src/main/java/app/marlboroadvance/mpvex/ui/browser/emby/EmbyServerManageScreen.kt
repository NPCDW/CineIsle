package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyServerIcon
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbyServerSelectionScreen(
  modifier: Modifier = Modifier,
  showBack: Boolean = true,
  onServerSelected: (() -> Unit)? = null,
  onOpenSettings: (() -> Unit)? = null,
) {
  val viewModel: EmbyServerSelectionViewModel = viewModel(factory = EmbyServerSelectionViewModel.factory())
  val rows by viewModel.rows.collectAsState()
  val state by viewModel.loadState.collectAsState()
  val iconServers = when (val loadState = state) {
    is EmbyServerLoadState.Ready -> loadState.servers
    is EmbyServerLoadState.Failed -> loadState.servers
    EmbyServerLoadState.Loading -> emptyList()
  }.associateBy { Triple(it.sourceKey, it.remoteId, it.sourceRevision) }
  val errorResource by viewModel.errorResource.collectAsState()
  val refreshing by viewModel.refreshing.collectAsState()
  val backStack = LocalBackStack.current
  val openServerHome = rememberServerHomeOpener()
  var infoKey by remember { mutableStateOf<String?>(null) }
  val infoRow = rows.firstOrNull { it.key == infoKey }
  val bottomInset = LocalNavigationBarHeight.current
  LaunchedEffect(viewModel) { viewModel.refreshServers() }
  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.emby_servers_title),
          style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          scrolledContainerColor = MaterialTheme.colorScheme.background,
          navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
          titleContentColor = MaterialTheme.colorScheme.onBackground,
          actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        navigationIcon = {
          if (showBack) IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.emby_servers_back))
          }
        },
        actions = {
          IconButton(onClick = viewModel::refreshServers, enabled = !refreshing) {
            Icon(Icons.Default.Refresh, stringResource(R.string.emby_shared_refresh))
          }
          IconButton(onClick = { if (onOpenSettings != null) onOpenSettings() else backStack.add(PreferencesScreen) }) {
            Icon(Icons.Default.Settings, stringResource(R.string.emby_servers_settings))
          }
        },
      )
    },
  ) { padding ->
    if (state is EmbyServerLoadState.Loading || (state is EmbyServerLoadState.Failed && rows.isEmpty())) {
      SharedServerLoadStatus(state, viewModel::refreshServers, Modifier.fillMaxSize().padding(padding).padding(bottom = bottomInset))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state is EmbyServerLoadState.Failed) item(key = "load-status") {
          SharedServerLoadStatus(state, viewModel::refreshServers, Modifier.fillMaxWidth())
        }
        errorResource?.let { resource -> item(key = "selection-error") {
          Text(stringResource(resource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } }
        if (rows.isEmpty()) item(key = "empty") {
          Text(stringResource(R.string.emby_servers_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
        }
        items(rows, key = { it.key }) { row ->
          ServerSelectionCard(row, onSelect = {
            viewModel.selectServerSnapshot(row)?.let { selected ->
              if (onServerSelected != null) onServerSelected() else openServerHome(selected)
            }
          }, onShowInfo = { infoKey = row.key },
            server = iconServers[Triple(row.sourceKey, row.remoteId, row.sourceRevision)])
        }
      }
    }
  }
  if (infoRow != null) {
    ServerConnectionInfo(infoRow, onDismiss = { infoKey = null })
  }
}

@Composable
private fun ServerSelectionCard(
  row: EmbyServerRow,
  onSelect: () -> Unit,
  onShowInfo: () -> Unit,
  server: EmbyServer? = null,
) {
  Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(enabled = row.canSelect, onClick = onSelect)
        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      EmbyServerIcon(row.sourceKey, row.iconUrl, Modifier.size(36.dp), server = server)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.name.ifBlank { stringResource(R.string.emby_servers_unnamed) },
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.Bold,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        val diagnostic = when {
          row.disabled -> R.string.emby_servers_disabled
          row.connectionError != null -> R.string.emby_servers_unavailable
          !row.canSelect -> R.string.emby_servers_not_verified
          else -> null
        }
        if (diagnostic != null) {
          Text(stringResource(diagnostic),
            color = if (row.disabled || row.connectionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      IconButton(onClick = onShowInfo) {
        Icon(Icons.Default.MoreVert, stringResource(R.string.emby_servers_connection_info), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun ServerConnectionInfo(row: EmbyServerRow, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.emby_servers_connection_info)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        ConnectionInfoField(R.string.emby_servers_current_line, row.currentLineName)
        ConnectionInfoField(R.string.emby_servers_reverse_proxy, row.reverseProxyName)
        ConnectionInfoField(R.string.emby_servers_reverse_url, row.reverseProxyUrl)
        ConnectionInfoField(R.string.emby_servers_effective_url, row.effectiveBaseUrl)
        row.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.emby_servers_read_only), style = MaterialTheme.typography.bodySmall)
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.emby_servers_close)) } },
  )
}

@Composable
private fun ConnectionInfoField(label: Int, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    Text(value.ifBlank { stringResource(R.string.emby_servers_none) }, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
internal fun SharedServerLoadStatus(state: EmbyServerLoadState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    if (state is EmbyServerLoadState.Loading) {
      CircularProgressIndicator()
      Text(stringResource(R.string.emby_shared_loading), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
    } else if (state is EmbyServerLoadState.Failed) {
      Text(stringResource(R.string.emby_shared_load_failed), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      Text(stringResource(embyPostgresErrorResource(state.reason)), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
      Button(onClick = onRetry) { Text(stringResource(R.string.emby_shared_retry)) }
    }
  }
}
