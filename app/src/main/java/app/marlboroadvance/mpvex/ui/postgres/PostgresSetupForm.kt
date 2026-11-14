package app.marlboroadvance.mpvex.ui.postgres

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSslMode

/**
 * Full-screen, theme-inheriting form. Null onBack means startup gate (no skip/back affordance).
 * The host must not compose its protected navigator until uiState.passed is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostgresSetupForm(
  viewModel: PostgresSetupViewModel,
  onSuccess: () -> Unit,
  onBack: (() -> Unit)? = null,
) {
  val state by viewModel.uiState.collectAsState()
  val currentOnSuccess by rememberUpdatedState(onSuccess)
  val keyboard = LocalSoftwareKeyboardController.current
  @Suppress("DEPRECATION")
  val clipboard = LocalClipboardManager.current
  var passwordVisible by remember { mutableStateOf(false) }
  var pasteVisible by remember { mutableStateOf(false) }
  val back: () -> Unit = {
    // Do not pretend to abort an already-started storage commit.
    if (!state.saving) {
      viewModel.cancel()
      onBack?.invoke()
    }
  }
  // Without a back callback, let the Activity finish normally; never pop into the protected UI.
  BackHandler(enabled = onBack != null, onBack = back)
  LaunchedEffect(viewModel, state.passed) {
    if (state.passed && viewModel.consumeSuccess()) currentOnSuccess()
  }
  LaunchedEffect(state.connecting) {
    if (state.connecting) {
      keyboard?.hide()
      passwordVisible = false
      pasteVisible = false
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(if (onBack == null) R.string.postgres_setup_title else R.string.postgres_settings_title)) },
        navigationIcon = {
          if (onBack != null) {
            IconButton(onClick = back, enabled = !state.saving) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.postgres_back))
            }
          }
        },
      )
    },
  ) { padding ->
    if (state.loading) {
      Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.postgres_loading), modifier = Modifier.padding(16.dp))
      }
    } else {
      Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
          modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            stringResource(if (onBack == null) R.string.postgres_setup_description else R.string.postgres_edit_description),
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(stringResource(R.string.postgres_import_title), style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
            value = state.importText,
            onValueChange = viewModel::updateImportText,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.editable,
            label = { Text(stringResource(R.string.postgres_import_label)) },
            supportingText = { Text(stringResource(R.string.postgres_import_hint)) },
            isError = state.importError != null,
            minLines = 2,
            maxLines = 4,
            visualTransformation = if (pasteVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
              VisibilityToggle(pasteVisible, state.editable) { pasteVisible = !pasteVisible }
            },
          )
          TextButton(
            onClick = { clipboard.getText()?.text?.let(viewModel::updateImportText) },
            enabled = state.editable,
          ) { Text(stringResource(R.string.postgres_paste)) }
          state.importError?.let { ErrorText(it) }

          ConnectionField(state.host, viewModel::updateHost, R.string.postgres_host, state.editable)
          ConnectionField(state.port, viewModel::updatePort, R.string.postgres_port, state.editable, KeyboardType.Number)
          ConnectionField(state.database, viewModel::updateDatabase, R.string.postgres_database, state.editable)
          ConnectionField(state.username, viewModel::updateUsername, R.string.postgres_username, state.editable)
          OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.editable,
            label = { Text(stringResource(R.string.postgres_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
              VisibilityToggle(passwordVisible, state.editable) { passwordVisible = !passwordVisible }
            },
          )
          Text(stringResource(R.string.postgres_tls_title), style = MaterialTheme.typography.titleMedium)
          Column(Modifier.fillMaxWidth().selectableGroup()) {
            PostgresSslMode.entries.forEach { mode ->
              Row(
                Modifier.fillMaxWidth().selectable(
                  selected = state.sslMode == mode,
                  enabled = state.editable,
                  role = Role.RadioButton,
                  onClick = { viewModel.updateSslMode(mode) },
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                RadioButton(selected = state.sslMode == mode, onClick = null, enabled = state.editable)
                Text(stringResource(mode.labelResource()), style = MaterialTheme.typography.bodyLarge)
              }
            }
          }
          Text(
            stringResource(state.sslMode.descriptionResource()),
            color = if (state.sslMode == PostgresSslMode.VERIFY_FULL) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
          state.error?.let { ErrorText(it) }
          if (state.error == PostgresFailure.LOAD) {
            TextButton(onClick = viewModel::retryLoad, enabled = state.editable) {
              Text(stringResource(R.string.postgres_reload))
            }
          }
          if (state.cancelled) {
            Text(stringResource(R.string.postgres_cancelled), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
          }
          when {
            state.connecting -> {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Text(stringResource(if (state.saving) R.string.postgres_saving else R.string.postgres_connecting))
              TextButton(onClick = viewModel::cancel, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.postgres_cancel))
              }
            }
            state.passed -> Text(stringResource(R.string.postgres_success))
            else -> Button(onClick = viewModel::connect, modifier = Modifier.fillMaxWidth(), enabled = state.editable) {
              Text(stringResource(
                if (state.error != null || state.cancelled) R.string.postgres_retry
                else if (onBack == null) R.string.postgres_connect else R.string.postgres_connect_save,
              ))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ConnectionField(
  value: String,
  onChange: (String) -> Unit,
  @StringRes label: Int,
  enabled: Boolean,
  keyboardType: KeyboardType = KeyboardType.Ascii,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    label = { Text(stringResource(label)) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
  )
}

@Composable
private fun VisibilityToggle(visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick, enabled = enabled) {
    Icon(
      if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
      stringResource(if (visible) R.string.postgres_hide_secret else R.string.postgres_show_secret),
      modifier = Modifier.size(24.dp),
    )
  }
}

@Composable
private fun ErrorText(failure: PostgresFailure) {
  Text(
    stringResource(failure.messageResource()),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    color = MaterialTheme.colorScheme.error,
    style = MaterialTheme.typography.bodyMedium,
  )
}

@StringRes
private fun PostgresFailure.messageResource(): Int = when (this) {
  PostgresFailure.INVALID_CONFIG -> R.string.postgres_error_config
  PostgresFailure.CONNECTION -> R.string.postgres_error_connection
  PostgresFailure.TIMEOUT -> R.string.postgres_error_timeout
  PostgresFailure.AUTHENTICATION -> R.string.postgres_error_authentication
  PostgresFailure.DATABASE -> R.string.postgres_error_database
  PostgresFailure.TLS -> R.string.postgres_error_tls
  PostgresFailure.STORAGE -> R.string.postgres_error_storage
  PostgresFailure.LOAD -> R.string.postgres_error_load
  PostgresFailure.SCHEMA_INCOMPATIBLE -> R.string.postgres_error_schema
  PostgresFailure.SOURCE_CHANGED -> R.string.postgres_error_source_changed
  PostgresFailure.NOT_FOUND -> R.string.postgres_error_not_found
  PostgresFailure.COMMIT_UNKNOWN -> R.string.postgres_error_commit_unknown
  PostgresFailure.UNKNOWN -> R.string.postgres_error_unknown
}

@StringRes
private fun PostgresSslMode.labelResource(): Int = when (this) {
  PostgresSslMode.VERIFY_FULL -> R.string.postgres_tls_verify_full
  PostgresSslMode.REQUIRE -> R.string.postgres_tls_require
  PostgresSslMode.DISABLE -> R.string.postgres_tls_disable
}

@StringRes
private fun PostgresSslMode.descriptionResource(): Int = when (this) {
  PostgresSslMode.VERIFY_FULL -> R.string.postgres_tls_verify_full_hint
  PostgresSslMode.REQUIRE -> R.string.postgres_tls_require_warning
  PostgresSslMode.DISABLE -> R.string.postgres_tls_disable_warning
}
