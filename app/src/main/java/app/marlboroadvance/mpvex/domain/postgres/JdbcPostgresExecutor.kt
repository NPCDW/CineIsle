package app.marlboroadvance.mpvex.domain.postgres

import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Captures the save generation as well as the password-free source fingerprint. */
data class PostgresSourceToken(val sourceKey: String, val revision: Long)

class PostgresSession internal constructor(
  val connection: Connection,
  val source: PostgresSourceToken,
  private val control: JdbcOperationControl,
) {
  fun track(statement: Statement) { control.statement.set(statement) }
}

interface PostgresExecutor {
  val sourceChanges: StateFlow<PostgresSourceState>
  suspend fun currentSource(): PostgresSourceToken
  fun isCurrent(source: PostgresSourceToken): Boolean
  suspend fun <T> read(expectedSourceKey: String? = null, block: (PostgresSession) -> T): T
}

/** Lazy: construction and configuration events never connect. Every operation reloads secure storage. */
class JdbcPostgresExecutor(
  private val storage: PostgresConfigStorage,
  private val factory: PostgresConnectionFactory = PostgresConnectionFactory(),
) : PostgresExecutor {
  override val sourceChanges: StateFlow<PostgresSourceState> get() = storage.sourceChanges

  private suspend fun configuration(): Pair<PostgresConfig, PostgresSourceToken> {
    val before = sourceChanges.value.revision
    val config = storage.load() ?: throw PostgresException(PostgresFailure.LOAD)
    val token = PostgresSourceToken(config.sourceKey(), before)
    if (!isCurrent(token)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    return config to token
  }

  override suspend fun currentSource(): PostgresSourceToken = configuration().second
  override fun isCurrent(source: PostgresSourceToken): Boolean {
    val current = sourceChanges.value
    return current.revision == source.revision && (current.sourceKey.isEmpty() || current.sourceKey == source.sourceKey)
  }

  override suspend fun <T> read(expectedSourceKey: String?, block: (PostgresSession) -> T): T = execute(expectedSourceKey, block)

  private suspend fun <T> execute(expected: String?, block: (PostgresSession) -> T): T {
    val (config, source) = configuration()
    if (expected != null && expected != source.sourceKey) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    return JdbcPostgresIo.run { control ->
      if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      factory.open(config).use { connection ->
        control.connection.set(connection)
        control.checkCancelled()
        connection.autoCommit = false
        connection.isReadOnly = true
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
          val value = block(PostgresSession(connection, source, control))
          control.checkCancelled()
          if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
          connection.rollback()
          if (!isCurrent(source)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
          value
        } catch (error: Throwable) {
          runCatching { connection.rollback() }
          throw error
        }
      }
    }
  }
}

internal class JdbcOperationControl {
  val connection = AtomicReference<Connection?>()
  val statement = AtomicReference<Statement?>()
  val cancelled = AtomicBoolean(false)
  fun checkCancelled() {
    if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
  }
}

/** Shared bounded blocking worker and cleanup pools. Cancellation never waits for socket close. */
internal object JdbcPostgresIo {
  const val TOTAL_TIMEOUT_MS = 20_000L
  const val QUERY_TIMEOUT_SECONDS = 5
  private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
    { runnable -> Thread(runnable, "postgres-jdbc").apply { isDaemon = true } })
  private val cleanup = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
    { runnable -> Thread(runnable, "postgres-cleanup").apply { isDaemon = true } })

  suspend fun <T> run(block: (JdbcOperationControl) -> T): T {
    val control = JdbcOperationControl()
    val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
      // Box allows SQL operations which legitimately return null.
      Box(await(control, block))
    } ?: throw PostgresException(PostgresFailure.TIMEOUT)
    return result.value
  }

  private data class Box<T>(val value: T)

  private suspend fun <T> await(control: JdbcOperationControl, block: (JdbcOperationControl) -> T): T = suspendCancellableCoroutine { continuation ->
    val future = AtomicReference<Future<*>?>()
    continuation.invokeOnCancellation {
      control.cancelled.set(true)
      future.get()?.cancel(true)
      // Keep cancel and close independent: pgjdbc cancel uses a second bounded socket.
      control.statement.get()?.let { statement -> cleanup { runCatching { statement.cancel() } } }
      control.connection.get()?.let { connection -> cleanup { runCatching { connection.close() } } }
    }
    try {
      val task = workers.submit {
        try {
          control.checkCancelled()
          val value = block(control)
          if (!control.cancelled.get()) continuation.resume(value)
        } catch (error: CancellationException) {
          continuation.cancel(error)
        } catch (error: Exception) {
          if (!control.cancelled.get()) continuation.resumeWithException(
            if (error is PostgresException) error else PostgresException(JdbcPostgresConnectionChecker.classifyFailure(error)))
        } catch (_: LinkageError) {
          if (!control.cancelled.get()) continuation.resumeWithException(PostgresException(PostgresFailure.UNKNOWN))
        } finally {
          control.connection.set(null)
          control.statement.set(null)
        }
      }
      future.set(task)
      if (control.cancelled.get()) task.cancel(true)
    } catch (_: RejectedExecutionException) {
      continuation.resumeWithException(PostgresException(PostgresFailure.TIMEOUT))
    }
  }

  private fun cleanup(block: () -> Unit) {
    try { cleanup.execute(block) } catch (_: RejectedExecutionException) {
      // Running worker still owns and closes its connection; do not create unbounded fallback threads.
    }
  }
}
