package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/** No authentication, media requests, credentials in saved state, or shared configuration writes. */
internal class EmbyServerSelectionViewModel(
  val loadState: StateFlow<EmbyServerLoadState>,
  private val refreshSnapshot: suspend () -> Unit,
  private val snapshotById: (Long) -> EmbyServer?,
  private val selectLatest: (EmbyServer) -> Unit,
) : ViewModel() {
  // UI rows reflect shared configuration only, never the repository's active media session.
  val rows = loadState.map(::projectEmbyServerRows)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), projectEmbyServerRows(loadState.value))
  private val _errorResource = MutableStateFlow<Int?>(null)
  val errorResource = _errorResource.asStateFlow()
  private val _refreshing = MutableStateFlow(false)
  val refreshing = _refreshing.asStateFlow()
  private var refreshJob: Job? = null

  fun refreshServers() {
    if (refreshJob?.isActive == true) return
    _errorResource.value = null
    refreshJob = viewModelScope.launch {
      _refreshing.value = true
      try {
        refreshSnapshot()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // The database load state contains its sanitized failure category; never expose exception text.
        _errorResource.value = R.string.emby_servers_refresh_failed
      } finally {
        _refreshing.value = false
      }
    }
  }

  /** Synchronous memory-only selection; caller navigates if and only if this returns true. */
  fun selectServer(row: EmbyServerRow): Boolean = selectServerSnapshot(row) != null

  /** Return the rechecked row to a bound home opener, never re-read ambient selection. */
  fun selectServerSnapshot(row: EmbyServerRow): EmbyServer? {
    _errorResource.value = null
    return try {
      val latest = snapshotById(row.id)
      if (loadState.value !is EmbyServerLoadState.Ready || latest == null ||
        latest.sourceKey != row.sourceKey || latest.remoteId != row.remoteId ||
        latest.sourceRevision != row.sourceRevision || !canSelectEmbyServer(latest)) {
        _errorResource.value = R.string.emby_servers_selection_failed
        null
      } else {
        // The repository rechecks source, credentials, and connection fingerprint atomically.
        selectLatest(latest)
        latest
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      _errorResource.value = R.string.emby_servers_selection_failed
      null
    }
  }

  companion object {
    fun factory(): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val servers = get<EmbyServerRepository>(EmbyServerRepository::class.java)
        EmbyServerSelectionViewModel(repository.loadState,
          repository::refreshServers, servers::snapshotById, repository::selectServerAsUser)
      }
    }
  }
}

/** Small non-credential projection. Disabled/invalid rows must not evaluate effectiveBaseUrl. */
internal data class EmbyServerRow(
  val id: Long,
  val sourceKey: String,
  val sourceRevision: Long,
  val remoteId: String,
  val name: String,
  val iconUrl: String,
  val disabled: Boolean,
  val canSelect: Boolean,
  val connectionError: String?,
  val currentLineName: String,
  val reverseProxyName: String,
  val reverseProxyUrl: String,
  val effectiveBaseUrl: String,
) {
  val key: String get() = "$sourceKey/$remoteId"
  override fun toString(): String = "EmbyServerRow(id=$id, canSelect=$canSelect, configuration=[redacted])"
}

internal fun canSelectEmbyServer(server: EmbyServer): Boolean = server.connectionError == null

internal fun projectEmbyServerRows(state: EmbyServerLoadState): List<EmbyServerRow> {
  val servers = when (state) {
    is EmbyServerLoadState.Ready -> state.servers
    is EmbyServerLoadState.Failed -> state.servers
    EmbyServerLoadState.Loading -> emptyList()
  }
  return servers.map { server ->
    val error = server.connectionError
    val effective = if (error == null) runCatching { server.effectiveBaseUrl }.getOrDefault("") else ""
    EmbyServerRow(server.id, server.sourceKey, server.sourceRevision, server.remoteId,
      server.name, server.iconUrl, server.disabled,
      state is EmbyServerLoadState.Ready && error == null && effective.isNotEmpty(), error,
      server.currentLineName, server.reverseProxyName, safeEmbyDisplayUrl(server.reverseProxyUrl), effective)
  }
}

/** Strip userinfo/query/fragment even on a malformed desktop reverse-proxy row. */
internal fun safeEmbyDisplayUrl(value: String): String = runCatching {
  val uri = URI(value.trim())
  require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
  URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
}.getOrDefault("")
