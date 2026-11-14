package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Safe UI reasons: never include an HTTP body, address, account or exception message. */
enum class EmbySearchFailure {
  AUTH, TIMEOUT, NETWORK, SERVER, CONFIGURATION, SOURCE_CHANGED, UNKNOWN;

  companion object {
    fun from(error: Exception): EmbySearchFailure = when (error) {
      is TimeoutCancellationException -> TIMEOUT
      is CancellationException -> throw error
      is PostgresException -> if (error.reason == PostgresFailure.SOURCE_CHANGED) SOURCE_CHANGED else CONFIGURATION
      is EmbyApiException -> when (error.errorKind) {
        EmbyErrorKind.AUTH -> AUTH
        EmbyErrorKind.NETWORK -> NETWORK
        EmbyErrorKind.SERVER -> SERVER
        else -> UNKNOWN
      }
      is InterruptedIOException -> TIMEOUT
      is IOException -> NETWORK
      is IllegalArgumentException -> CONFIGURATION
      else -> UNKNOWN
    }
  }
}

enum class EmbySearchStatus { IDLE, QUEUED, LOADING, SUCCESS, EMPTY, PARTIAL, FAILED }

data class EmbySearchGroup(
  val server: EmbyServer,
  val status: EmbySearchStatus = EmbySearchStatus.IDLE,
  val items: List<EmbyItem> = emptyList(),
  val failures: List<EmbySearchFailure> = emptyList(),
  val truncated: Boolean = false,
) {
  val key: String get() = "${server.sourceKey}:${server.sourceRevision}:${server.remoteId}"
}

/** Explicit row transport, also the JVM fake seam for search scheduling and failure tests. */
interface EmbySearchBackend {
  suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int): EmbyItemsResult
  suspend fun persons(server: EmbyServer, query: String, limit: Int): EmbyItemsResult
}
