package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceState
import kotlinx.serialization.Serializable

/** The only server identity allowed in navigation/saved state. Never carries a session or URL. */
@Serializable
data class EmbyServerRef(val sourceKey: String, val sourceRevision: Long, val remoteId: String) {
  internal fun matches(server: EmbyServer): Boolean = sourceKey == server.sourceKey &&
    sourceRevision == server.sourceRevision && remoteId == server.remoteId

  companion object {
    fun from(server: EmbyServer) = EmbyServerRef(server.sourceKey, server.sourceRevision, server.remoteId)
  }
}

/** Fail closed on loading/failure, ambiguous identity, source changes, deleted/disabled/invalid rows. */
internal fun resolveEmbyServerBinding(
  ref: EmbyServerRef,
  state: EmbyServerLoadState,
  isCurrent: (EmbyServer) -> Boolean,
): EmbyServer? {
  if (ref.sourceKey.isBlank() || ref.remoteId.isBlank() || ref.sourceRevision < 0) return null
  val ready = state as? EmbyServerLoadState.Ready ?: return null
  return ready.servers.singleOrNull(ref::matches)?.takeIf { server ->
    isCurrent(server) && canSelectEmbyServer(server)
  }
}

/** Transient read failure blocks interaction but is not a different source or a popped visit. */
internal fun shouldRetainEmbyServerVisit(ref: EmbyServerRef, source: PostgresSourceState): Boolean =
  ref.sourceKey.isNotBlank() && ref.remoteId.isNotBlank() && ref.sourceRevision >= 0 &&
    ref.sourceKey == source.sourceKey && ref.sourceRevision == source.revision

/** Once truly invalidated, this visit cannot revive against changed credentials or a new source. */
internal fun nextBoundEmbyServer(
  original: EmbyServer,
  previous: EmbyServer?,
  state: EmbyServerLoadState,
  source: PostgresSourceState,
  isCurrent: (EmbyServer) -> Boolean,
  sessionServer: (EmbyServer) -> EmbyServer?,
): EmbyServer? {
  val ref = EmbyServerRef.from(original)
  if (previous == null || !shouldRetainEmbyServerVisit(ref, source)) return null
  if (state !is EmbyServerLoadState.Ready) return previous
  if (resolveEmbyServerBinding(ref, state, isCurrent) == null) return null
  // Resolve the ORIGINAL row, not the new row: repository enforces credential/connection identity.
  return sessionServer(original)
}

/** Activity-retained visit owners; hiding an entry is not popping it. */
internal class ServerNavigationEntryStores : ViewModel() {
  private val entries = mutableMapOf<String, ViewModelStoreOwner>()
  fun owner(visitId: String): ViewModelStoreOwner = entries.getOrPut(visitId) {
    object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
  }

  fun retain(visitIds: Set<String>) {
    (entries.keys - visitIds).forEach(::release)
  }

  fun release(visitId: String) { entries.remove(visitId)?.viewModelStore?.clear() }

  override fun onCleared() {
    entries.values.forEach { it.viewModelStore.clear() }
    entries.clear()
  }
}
