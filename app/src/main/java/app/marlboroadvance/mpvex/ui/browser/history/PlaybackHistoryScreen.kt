package app.marlboroadvance.mpvex.ui.browser.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.database.repository.PostgresPlaybackHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryFilters
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryRow
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyServerIcon
import app.marlboroadvance.mpvex.ui.browser.emby.embyPostgresErrorResource
import app.marlboroadvance.mpvex.ui.browser.emby.rememberServerDetailOpener
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackHistoryScreen() {
  val repository = koinInject<PostgresPlaybackHistoryRepository>()
  val viewModel: PlaybackHistoryViewModel = viewModel(factory = remember(repository) {
    viewModelFactory { initializer { PlaybackHistoryViewModel(repository) } }
  })
  val state by viewModel.state.collectAsState()
  val serverRepository = koinInject<EmbyServerRepository>()
  val serverState by serverRepository.loadState.collectAsState()
  val servers = when (val snapshot = serverState) {
    is EmbyServerLoadState.Ready -> snapshot.servers
    is EmbyServerLoadState.Failed -> snapshot.servers
    EmbyServerLoadState.Loading -> emptyList()
  }
  val openDetail = rememberServerDetailOpener()
  var filtersExpanded by rememberSaveable { mutableStateOf(false) }
  var serverMenu by remember { mutableStateOf(false) }
  var seriesName by rememberSaveable { mutableStateOf("") }
  var itemName by rememberSaveable { mutableStateOf("") }
  val canModify = state.markState == PlaybackHistoryMarkState.Idle

  LaunchedEffect(viewModel) { viewModel.refresh() }

  Scaffold(topBar = {
    TopAppBar(
      title = { Text(stringResource(R.string.app_nav_playback_history)) },
      actions = {
        IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
          Icon(Icons.Rounded.Refresh, stringResource(R.string.pg_history_refresh))
        }
      },
    )
  }) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalNavigationBarHeight.current + 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "filters") {
        Column {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pg_history_total, state.total), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { filtersExpanded = !filtersExpanded }) { Text(stringResource(R.string.pg_history_filters)) }
          }
          Box {
            val selectedName = servers.firstOrNull { it.remoteId == state.filters.serverUuid }?.name
            TextButton(onClick = { serverMenu = true }) {
              Text(selectedName ?: stringResource(if (state.filters.serverUuid == null) R.string.pg_history_all_servers else R.string.pg_history_missing_server))
            }
            DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
              DropdownMenuItem(text = { Text(stringResource(R.string.pg_history_all_servers)) }, onClick = {
                serverMenu = false
                viewModel.setFilters(state.filters.copy(serverUuid = null))
              })
              servers.forEach { server ->
                DropdownMenuItem(text = { Text(server.name) }, onClick = {
                  serverMenu = false
                  viewModel.setFilters(state.filters.copy(serverUuid = server.remoteId))
                })
              }
            }
          }
          if (filtersExpanded) {
            OutlinedTextField(seriesName, { seriesName = it }, label = { Text(stringResource(R.string.pg_history_series_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(itemName, { itemName = it }, label = { Text(stringResource(R.string.pg_history_item_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row {
              TextButton(onClick = { viewModel.setFilters(PostgresPlaybackHistoryFilters(state.filters.serverUuid, seriesName.trim(), itemName.trim())) }) { Text(stringResource(R.string.pg_history_query)) }
              TextButton(onClick = { seriesName = ""; itemName = ""; viewModel.setFilters(PostgresPlaybackHistoryFilters()) }) { Text(stringResource(R.string.pg_history_reset)) }
            }
          }
        }
      }
      if (state.loading) item(key = "loading") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
      }
      state.error?.let { failure -> item(key = "error") {
        Column {
          Text(stringResource(embyPostgresErrorResource(failure)), color = MaterialTheme.colorScheme.error)
          TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.pg_history_retry)) }
        }
      } }
      when (val mark = state.markState) {
        is PlaybackHistoryMarkState.Failed -> item(key = "mark-error") {
          Column {
            Text(stringResource(if (mark.reconciled) R.string.pg_history_mark_checked else when (mark.failure) {
              PlaybackHistoryMarkFailure.CONFLICT -> R.string.pg_history_mark_conflict
              PlaybackHistoryMarkFailure.COMMIT_UNKNOWN -> R.string.pg_history_mark_unknown
              PlaybackHistoryMarkFailure.SOURCE_CHANGED -> R.string.pg_history_mark_source_changed
              PlaybackHistoryMarkFailure.DATABASE -> R.string.pg_history_mark_failed
            }), color = MaterialTheme.colorScheme.error)
            if (mark.reconciled) {
              Text(
                if (mark.observedRowExists == false) stringResource(R.string.pg_history_reconciled_missing)
                else stringResource(R.string.pg_history_reconciled, stringResource(when (mark.observedPinned) {
                  1 -> R.string.pg_history_pinned
                  -1 -> R.string.pg_history_hidden
                  else -> R.string.pg_history_ordinary
                })),
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            TextButton(onClick = if (mark.requiresReconcile) viewModel::reconcileMark else viewModel::dismissMarkError) {
              Text(stringResource(if (mark.requiresReconcile) R.string.pg_history_verify else R.string.pg_history_close))
            }
          }
        }
        is PlaybackHistoryMarkState.Reconciling -> item(key = "mark-verify") { Text(stringResource(R.string.pg_history_verifying)) }
        else -> Unit
      }
      if (state.rows.isEmpty() && !state.loading && state.error == null) item(key = "empty") {
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Rounded.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(stringResource(R.string.pg_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      items(state.rows, key = { it.key }) { row ->
        val server = servers.firstOrNull { it.remoteId == row.serverUuid && it.sourceKey == row.source.sourceKey && it.sourceRevision == row.source.revision }
        val usable = serverState is EmbyServerLoadState.Ready && server != null && !server.disabled && server.connectionError == null
        HistoryCard(
          row = row,
          server = server,
          usable = usable,
          marking = row.key in state.markingIds,
          canModify = canModify && !state.loading,
          onOpenItem = { if (usable) openDetail(requireNotNull(server), row.itemId, row.itemName) },
          onOpenSeries = { if (usable && !row.seriesId.isNullOrBlank()) openDetail(requireNotNull(server), row.seriesId, row.seriesName.orEmpty()) },
          onMark = { viewModel.cycleMark(row) },
          unavailableRes = if (serverState !is EmbyServerLoadState.Ready) R.string.pg_history_server_unverified else R.string.pg_history_server_unavailable,
        )
      }
      if (state.total > state.pageSize || state.pageNumber > 1) item(key = "pagination") {
        val lastPage = ((state.total + state.pageSize - 1) / state.pageSize).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
          IconButton(onClick = { viewModel.goToPage(state.pageNumber - 1) }, enabled = !state.loading && state.pageNumber > 1) {
            Icon(Icons.AutoMirrored.Rounded.NavigateBefore, stringResource(R.string.pg_history_previous))
          }
          Text(stringResource(R.string.pg_history_page, state.pageNumber, lastPage))
          IconButton(onClick = { viewModel.goToPage(state.pageNumber + 1) }, enabled = !state.loading && state.pageNumber < lastPage) {
            Icon(Icons.AutoMirrored.Rounded.NavigateNext, stringResource(R.string.pg_history_next))
          }
        }
      }
    }
  }
}

@Composable
private fun HistoryCard(
  row: PostgresPlaybackHistoryRow,
  server: EmbyServer?,
  usable: Boolean,
  marking: Boolean,
  canModify: Boolean,
  onOpenItem: () -> Unit,
  onOpenSeries: () -> Unit,
  onMark: () -> Unit,
  unavailableRes: Int,
) {
  Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EmbyServerIcon(row.source.sourceKey, server?.iconUrl.orEmpty(), Modifier.size(32.dp), server = server)
        Text(row.serverName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (marking) CircularProgressIndicator(Modifier.size(24.dp)) else {
          val next = if (row.pinned == 0) 1 else if (row.pinned == 1) -1 else 0
          IconButton(onClick = onMark, enabled = canModify) {
            Icon(if (row.pinned == -1) Icons.Rounded.VisibilityOff else Icons.Rounded.PushPin,
              stringResource(markActionResource(next)),
              tint = if (row.pinned == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (!row.seriesName.isNullOrBlank()) Text(row.seriesName, modifier = Modifier.clickable(enabled = usable && !row.seriesId.isNullOrBlank(), onClick = onOpenSeries), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
      Text(row.itemName, modifier = Modifier.fillMaxWidth().clickable(enabled = usable, onClick = onOpenItem), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(stringResource(R.string.pg_history_duration, formatHistoryDuration(row.playedDurationMs)), style = MaterialTheme.typography.bodyMedium)
      val timestamp = row.updateTimeMs ?: row.createTimeMs
      Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      if (row.pinned != 0) Text(stringResource(if (row.pinned == 1) R.string.pg_history_pinned else R.string.pg_history_hidden), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
      if (!usable) Text(stringResource(unavailableRes), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}

private fun markActionResource(next: Int): Int = when (next) {
  1 -> R.string.pg_history_pin
  -1 -> R.string.pg_history_hide
  else -> R.string.pg_history_normal
}

internal fun formatHistoryDuration(milliseconds: Long): String {
  val seconds = milliseconds.coerceAtLeast(0) / 1000
  return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
