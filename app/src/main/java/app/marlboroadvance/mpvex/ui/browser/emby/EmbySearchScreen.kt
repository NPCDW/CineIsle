package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbySearchFailure
import app.marlboroadvance.mpvex.domain.emby.EmbySearchGroup
import app.marlboroadvance.mpvex.domain.emby.EmbySearchService
import app.marlboroadvance.mpvex.domain.emby.EmbySearchStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilterRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyServerIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.SearchHistoryPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Shared Classic/Cine screen. The route's ViewModelStoreOwner binds inner searches to its row. */
@Composable
fun EmbySearchScreen(aggregate: Boolean = false, onBack: (() -> Unit)? = null) {
  val openServerItem = rememberServerItemOpener()
  val repository: EmbyRepository = koinInject()
  val serverRepo: EmbyServerRepository = koinInject()
  val historyRepo: SearchHistoryRepository = koinInject()
  val application = LocalContext.current.applicationContext as Application
  val currentServer: EmbyServer? = if (aggregate) null else {
    val embyVM: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val bound by embyVM.currentServer.collectAsState()
    bound
  }
  val service = remember(repository) { EmbySearchService(repository) }
  val searchVM: EmbySearchViewModel = viewModel(
    key = if (aggregate) "emby-aggregate-search" else "emby-inner-search",
    factory = remember(service) { viewModelFactory { initializer { EmbySearchViewModel(service) } } },
  )
  val state by searchVM.state.collectAsState()
  val loadState by serverRepo.loadState.collectAsState()
  val source by serverRepo.sourceChanged.collectAsState()
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val bottomInset = LocalNavigationBarHeight.current + 24.dp
  var recordedQuery by remember(currentServer?.id, source) { mutableStateOf<String?>(null) }
  val historyFlow = remember(historyRepo, currentServer?.id, source) { historyRepo.observe(currentServer?.id) }
  val history by historyFlow.collectAsState(initial = emptyList())

  LaunchedEffect(loadState, currentServer, source, aggregate) {
    val rows = when (val loaded = loadState) {
      is EmbyServerLoadState.Ready -> loaded.servers
      is EmbyServerLoadState.Failed -> loaded.servers
      EmbyServerLoadState.Loading -> emptyList()
    }
    val targets = if (aggregate) rows.filterNot { it.disabled } else listOfNotNull(currentServer)
    val issue = when {
      loadState is EmbyServerLoadState.Loading -> EmbySearchSourceIssue.LOADING
      loadState is EmbyServerLoadState.Failed -> EmbySearchSourceIssue.FAILED
      aggregate && targets.isEmpty() -> EmbySearchSourceIssue.NO_ENABLED_SERVERS
      !aggregate && (currentServer == null || currentServer.disabled) -> EmbySearchSourceIssue.NO_CURRENT_SERVER
      else -> null
    }
    searchVM.bindSources(targets, issue, "${source.sourceKey}:${source.revision}")
  }
  DisposableEffect(searchVM) { onDispose { searchVM.cancelSearch() } }
  LaunchedEffect(state.query, state.pending, currentServer?.id) {
    val server = currentServer
    if (!aggregate && server != null && !state.pending && state.query.isNotBlank() &&
      state.groups.any { it.items.isNotEmpty() || it.status == EmbySearchStatus.EMPTY } &&
      recordedQuery != state.query) {
      recordedQuery = state.query
      historyRepo.record(server.id, state.query)
    }
  }
  BackHandler(enabled = onBack != null) { onBack?.invoke() }

  val submit = { searchVM.submit(); keyboard?.hide(); Unit }
  val queryField: @Composable () -> Unit = {
    OutlinedTextField(
      value = state.query,
      onValueChange = searchVM::setQuery,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      singleLine = true,
      placeholder = { Text(stringResource(R.string.emby_search_query_hint)) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      trailingIcon = {
        if (state.query.isNotEmpty()) IconButton(onClick = { searchVM.setQuery("") }) {
          Icon(Icons.Default.Close, stringResource(R.string.emby_search_clear_query))
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { submit() }),
    )
  }
  val filters: @Composable () -> Unit = {
    EmbySearchFilterRow(selected = state.filters, onSelectedChange = searchVM::setFilters)
  }
  val notice: @Composable () -> Unit = {
    SearchSourceNotice(state.sourceIssue) {
      scope.launch {
        try { serverRepo.refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* loadState retains the safe reason and cached snapshot. */ }
      }
    }
  }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().imePadding()) {
      Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.emby_search_back))
        }
        Text(stringResource(if (aggregate) R.string.emby_search_aggregate_title else R.string.emby_search_screen_title),
          style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(8.dp))
        if (state.pending) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
      }
      if (aggregate) {
        // Keep controls and searched groups in one vertical list.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomInset)) {
          item(key = "query") { queryField() }
          item(key = "filters") { filters() }
          item(key = "source") { notice() }
          if (state.query.isNotBlank()) items(state.groups, key = { it.key }) { group ->
            SearchServerGroup(group, openServerItem) { searchVM.retryGroup(group.key) }
          }
        }
      } else {
        queryField()
        filters()
        val group = state.groups.firstOrNull()
        val visible = group?.items.orEmpty()
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset), horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "source") { notice() }
          if (state.query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "history") {
              SearchHistoryPanel(history = history, onPick = { searchVM.setQuery(it); submit() },
                onRemove = { term -> currentServer?.let { server -> scope.launch { historyRepo.remove(server.id, term) } } },
                onClearAll = { currentServer?.let { server -> scope.launch { historyRepo.clear(server.id) } } },
                emptyHint = stringResource(R.string.emby_search_history_hint))
            }
          } else if (group != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
              SearchGroupReason(group, visible.isEmpty()) { searchVM.retryGroup(group.key) }
            }
            items(visible, key = { "${it.Type}:${it.Id}" }) { item ->
              SearchResultCard(group.server, item, true, openServerItem)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchServerGroup(group: EmbySearchGroup,
  open: (EmbyServer, EmbyItem) -> Unit, retry: () -> Unit) {
  val visible = group.items
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      EmbyServerIcon(group.server.sourceKey, group.server.iconUrl, Modifier.size(32.dp), server = group.server)
      Text(group.server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text(searchStatusText(group), style = MaterialTheme.typography.labelMedium,
        color = if (group.failures.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
      SearchStatusIcon(group)
    }
    SearchGroupReason(group, visible.isEmpty(), retry)
    if (visible.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(visible, key = { "${it.Type}:${it.Id}" }) { item -> SearchResultCard(group.server, item, false, open) }
    }
  }
}

@Composable
private fun SearchResultCard(server: EmbyServer, item: EmbyItem, fillWidth: Boolean,
  open: (EmbyServer, EmbyItem) -> Unit) {
  val imageUrl = remember(server, item) {
    try { item.primaryImageRef()?.let { EmbyClient.imageUrl(server, it.itemId, "Primary", it.tag, 320) } }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
  }
  EmbyMediaCard(title = item.displayTitle(), subtitle = item.ProductionYear?.toString(), imageUrl = imageUrl,
    progress = null, isFavorite = item.UserData?.IsFavorite == true, onClick = { open(server, item) },
    contentDescription = item.Name, fillWidth = fillWidth, titleMaxLines = 2, server = server)
}

@Composable
private fun SearchGroupReason(group: EmbySearchGroup, noVisible: Boolean, retry: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    group.failures.forEach { failure ->
      Text(stringResource(when (failure) {
        EmbySearchFailure.AUTH -> R.string.emby_search_reason_auth
        EmbySearchFailure.TIMEOUT -> R.string.emby_search_reason_timeout
        EmbySearchFailure.NETWORK -> R.string.emby_search_reason_network
        EmbySearchFailure.SERVER -> R.string.emby_search_reason_server
        EmbySearchFailure.CONFIGURATION -> R.string.emby_search_reason_configuration
        EmbySearchFailure.SOURCE_CHANGED -> R.string.emby_search_reason_source
        EmbySearchFailure.UNKNOWN -> R.string.emby_search_reason_unknown
      }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (group.failures.isNotEmpty()) TextButton(onClick = retry) { Text(stringResource(R.string.emby_search_retry)) }
    if (group.truncated) Text(stringResource(R.string.emby_search_bounded),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (noVisible && group.failures.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
      SearchStatusIcon(group)
      Text(stringResource(when (group.status) {
        EmbySearchStatus.IDLE -> R.string.emby_search_type_query
        EmbySearchStatus.QUEUED -> R.string.emby_search_queued
        EmbySearchStatus.LOADING -> R.string.emby_search_loading
        EmbySearchStatus.EMPTY -> R.string.emby_search_empty
        else -> R.string.emby_search_category_empty
      }), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SearchStatusIcon(group: EmbySearchGroup) {
  val description = searchStatusText(group)
  if (group.status == EmbySearchStatus.LOADING || group.status == EmbySearchStatus.QUEUED) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
  } else Icon(imageVector = when (group.status) {
    EmbySearchStatus.SUCCESS -> Icons.Outlined.CheckCircle
    EmbySearchStatus.EMPTY -> Icons.Outlined.SearchOff
    EmbySearchStatus.PARTIAL, EmbySearchStatus.FAILED -> Icons.Outlined.ErrorOutline
    else -> Icons.Default.Search
  }, contentDescription = description, modifier = Modifier.size(20.dp),
    tint = if (group.failures.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun searchStatusText(group: EmbySearchGroup): String = when (group.status) {
  EmbySearchStatus.IDLE -> stringResource(R.string.emby_search_idle)
  EmbySearchStatus.QUEUED -> stringResource(R.string.emby_search_queued)
  EmbySearchStatus.LOADING -> stringResource(R.string.emby_search_loading)
  EmbySearchStatus.SUCCESS -> stringResource(R.string.emby_search_success, group.items.size)
  EmbySearchStatus.EMPTY -> stringResource(R.string.emby_search_empty)
  EmbySearchStatus.PARTIAL -> stringResource(R.string.emby_search_partial)
  EmbySearchStatus.FAILED -> stringResource(R.string.emby_search_failed)
}

@Composable
private fun SearchSourceNotice(issue: EmbySearchSourceIssue?, refresh: () -> Unit) {
  if (issue == null) return
  Column(Modifier.fillMaxWidth().padding(16.dp)) {
    Text(stringResource(when (issue) {
      EmbySearchSourceIssue.LOADING -> R.string.emby_search_source_loading
      EmbySearchSourceIssue.FAILED -> R.string.emby_search_source_failed
      EmbySearchSourceIssue.NO_ENABLED_SERVERS -> R.string.emby_search_no_enabled
      EmbySearchSourceIssue.NO_CURRENT_SERVER -> R.string.emby_search_no_current
    }), color = if (issue == EmbySearchSourceIssue.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (issue != EmbySearchSourceIssue.LOADING) TextButton(onClick = refresh) {
      Text(stringResource(R.string.emby_search_refresh_sources))
    }
  }
}
