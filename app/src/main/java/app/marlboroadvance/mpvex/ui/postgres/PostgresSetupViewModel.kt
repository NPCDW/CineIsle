package app.marlboroadvance.mpvex.ui.postgres

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfig
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigParser
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigStorage
import app.marlboroadvance.mpvex.domain.postgres.PostgresConnectionChecker
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSslMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** In-memory only: neither credentials nor pasted connection strings enter saved instance state. */
data class PostgresSetupUiState(
  val host: String = "",
  val port: String = "5432",
  val database: String = "",
  val username: String = "",
  val password: String = "",
  val sslMode: PostgresSslMode = PostgresSslMode.VERIFY_FULL,
  val importText: String = "",
  val importError: PostgresFailure? = null,
  val error: PostgresFailure? = null,
  val loading: Boolean = true,
  val connecting: Boolean = false,
  val saving: Boolean = false,
  val cancelled: Boolean = false,
  val passed: Boolean = false,
) {
  val editable: Boolean get() = !loading && !connecting && !passed

  // Prevent accidental credential disclosure through diagnostics of the UI state.
  override fun toString(): String =
    "PostgresSetupUiState(loading=$loading, connecting=$connecting, saving=$saving, passed=$passed, error=$error)"
}

/** Own with the Activity for startup, and with a separate navigation entry for settings. */
class PostgresSetupViewModel(
  private val storage: PostgresConfigStorage,
  private val checker: PostgresConnectionChecker,
  private val autoConnect: Boolean = true,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(PostgresSetupUiState())
  val uiState: StateFlow<PostgresSetupUiState> = mutableUiState.asStateFlow()
  private var operation: Job? = null
  private var generation = 0L
  private var successConsumed = false

  init {
    loadConfiguration()
  }

  private fun loadConfiguration() {
    val token = ++generation
    mutableUiState.value = mutableUiState.value.copy(loading = true, error = null, cancelled = false)
    operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
      val config = try {
        storage.load()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == token) {
          mutableUiState.value = mutableUiState.value.copy(loading = false, error = PostgresFailure.LOAD)
        }
        return@launch
      }
      coroutineContext.ensureActive()
      if (generation != token) return@launch
      mutableUiState.value = (config?.let { mutableUiState.value.withConfig(it) } ?: mutableUiState.value)
        .copy(loading = false)
      if (config != null && autoConnect) connect()
    }
    operation?.start()
  }

  /** Explicit retry only; a load error never silently replaces stored credentials. */
  fun retryLoad() {
    if (uiState.value.editable) loadConfiguration()
  }

  fun updateHost(value: String) = edit { copy(host = value) }
  fun updatePort(value: String) = edit { copy(port = value) }
  fun updateDatabase(value: String) = edit { copy(database = value) }
  fun updateUsername(value: String) = edit { copy(username = value) }
  fun updatePassword(value: String) = edit { copy(password = value) }
  fun updateSslMode(value: PostgresSslMode) = edit { copy(sslMode = value) }

  private fun edit(update: PostgresSetupUiState.() -> PostgresSetupUiState) {
    if (!uiState.value.editable) return
    mutableUiState.value = uiState.value.update().copy(error = null, cancelled = false)
  }

  /** Called only by manual text/clipboard changes, never by an effect observing restored state. */
  fun updateImportText(text: String) {
    if (!uiState.value.editable) return
    val current = uiState.value.copy(importText = text, importError = null, error = null, cancelled = false)
    mutableUiState.value = if (text.isBlank()) {
      current
    } else {
      try {
        // Clear a successfully parsed paste to minimize the lifetime of duplicate secrets.
        current.withConfig(PostgresConfigParser.parse(text)).copy(importText = "")
      } catch (_: Exception) {
        // Keep the invalid paste and all previous fields so either can be corrected manually.
        current.copy(importError = PostgresFailure.INVALID_CONFIG)
      }
    }
  }

  /** Validate a snapshot, check it, then persist it. Repeated taps and edits while busy are ignored. */
  fun connect() {
    val current = uiState.value
    if (!current.editable) return
    val config = try {
      val port = current.port.toIntOrNull() ?: throw PostgresException(PostgresFailure.INVALID_CONFIG)
      PostgresConfigParser.validate(
        PostgresConfig(current.host, port, current.database, current.username, current.password, current.sslMode),
      )
    } catch (_: Exception) {
      mutableUiState.value = current.copy(error = PostgresFailure.INVALID_CONFIG, cancelled = false)
      return
    }
    val token = ++generation
    mutableUiState.value = current.withConfig(config).copy(
      connecting = true, saving = false, error = null, cancelled = false,
      importText = "", importError = null,
    )
    operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
      try {
        withTimeout(CONNECTION_TIMEOUT_MILLIS) { checker.check(config) }
        // A checker may take time to stop native/blocking IO, or swallow cancellation.
        // Never let that stale result authorize startup or reach the storage layer.
        coroutineContext.ensureActive()
        if (generation != token) return@launch
        mutableUiState.value = uiState.value.copy(saving = true)
        try {
          storage.save(config)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (generation == token) {
            mutableUiState.value = uiState.value.copy(connecting = false, saving = false, error = PostgresFailure.STORAGE)
          }
          return@launch
        }
        coroutineContext.ensureActive()
        if (generation != token) return@launch
        mutableUiState.value = uiState.value.copy(connecting = false, saving = false, passed = true)
      } catch (_: TimeoutCancellationException) {
        finishFailure(token, PostgresFailure.TIMEOUT)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        finishFailure(token, (error as? PostgresException)?.reason ?: PostgresFailure.UNKNOWN)
      }
    }
    operation?.start()
  }

  private fun finishFailure(token: Long, failure: PostgresFailure) {
    if (generation == token) {
      mutableUiState.value = uiState.value.copy(connecting = false, saving = false, error = failure)
    }
  }

  /** Cancellation is immediate during the check; saving is a short, non-interruptible UI commit. */
  fun cancel() {
    val current = uiState.value
    if (!current.connecting || current.saving) return
    ++generation
    operation?.cancel()
    mutableUiState.value = current.copy(connecting = false, saving = false, cancelled = true, passed = false)
  }

  /** One navigation callback per ViewModel, including across Activity recreation. Does not reset passed. */
  fun consumeSuccess(): Boolean {
    if (!uiState.value.passed || successConsumed) return false
    successConsumed = true
    return true
  }

  companion object {
    const val CONNECTION_TIMEOUT_MILLIS = 20_000L

    fun factory(
      storage: PostgresConfigStorage,
      checker: PostgresConnectionChecker,
      autoConnect: Boolean = true,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer { PostgresSetupViewModel(storage, checker, autoConnect) }
    }
  }
}

private fun PostgresSetupUiState.withConfig(config: PostgresConfig): PostgresSetupUiState = copy(
  host = config.host,
  port = config.port.toString(),
  database = config.database,
  username = config.username,
  password = config.password,
  sslMode = config.sslMode,
)
