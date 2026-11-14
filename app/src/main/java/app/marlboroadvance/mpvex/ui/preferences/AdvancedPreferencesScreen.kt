package app.marlboroadvance.mpvex.ui.preferences

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastJoinToString
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TwoTargetIconButtonPreference
import org.koin.compose.koinInject
import java.io.File
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.io.path.readLines

@Serializable
object AdvancedPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val scope = rememberCoroutineScope()

    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(R.string.pref_advanced),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backStack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Default.ArrowBack, 
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      val fontsClearedText = stringResource(R.string.pref_advanced_cleared_fonts_cache)
      ProvidePreferenceLocals {
        val locationPicker =
          rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
          ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            preferences.mpvConfStorageUri.set(uri.toString())

            // Auto-create standard MPV folder structure
            scope.launch(Dispatchers.IO) {
              runCatching {
                val tree = DocumentFile.fromTreeUri(context, uri)
                if (tree != null && tree.exists() && tree.canWrite()) {
                  val subdirs = listOf("fonts", "script-opts", "scripts", "shaders")
                  for (name in subdirs) {
                    val existing = tree.listFiles().firstOrNull {
                      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
                    }
                    if (existing == null) {
                      tree.createDirectory(name)
                    }
                  }
                  // Create default mpv.conf if missing
                  val hasConf = tree.listFiles().any {
                    it.isFile && it.name?.equals("mpv.conf", ignoreCase = true) == true
                  }
                  if (!hasConf) {
                    tree.createFile("application/octet-stream", "mpv.conf")
                  }
                  withContext(Dispatchers.Main) {
                    Toast.makeText(context, "MPV 目录已就绪 ✓", Toast.LENGTH_SHORT).show()
                  }
                }
              }.onFailure { e ->
                android.util.Log.e("AdvancedPrefs", "Error creating MPV directory structure", e)
              }
            }
          }
        val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
        val listState = rememberLazyListState()
        SettingsSpotlightReveal(listState, AdvancedPreferencesScreen)
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
          // MPV Configuration Section
          item {
            PreferenceSectionHeader(title = "MPV 配置")
          }
          
          item {
            PreferenceCard {
              var mpvConf by remember { mutableStateOf(preferences.mpvConf.get()) }
              var inputConf by remember { mutableStateOf(preferences.inputConf.get()) }
              
              // Load config files when storage location changes
              LaunchedEffect(mpvConfStorageLocation) {
                if (mpvConfStorageLocation.isBlank()) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                  val tempFile = kotlin.io.path.createTempFile()
                  runCatching {
                    val tree =
                      DocumentFile.fromTreeUri(
                        context,
                        mpvConfStorageLocation.toUri(),
                      )
                    val mpvConfFile = tree?.findFile("mpv.conf")
                    if (mpvConfFile != null && mpvConfFile.exists()) {
                      context.contentResolver
                        .openInputStream(
                          mpvConfFile.uri,
                        )?.copyTo(tempFile.outputStream())
                      val content = tempFile.readLines().fastJoinToString("\n")
                      preferences.mpvConf.set(content)
                      File(context.filesDir, "mpv.conf").writeText(content)
                      withContext(Dispatchers.Main) {
                        mpvConf = content
                      }
                    }
                  }
                  tempFile.deleteIfExists()
                }
              }
              
              // Load input.conf when storage location changes
              LaunchedEffect(mpvConfStorageLocation) {
                if (mpvConfStorageLocation.isBlank()) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                  val tempFile = kotlin.io.path.createTempFile()
                  runCatching {
                    val tree =
                      DocumentFile.fromTreeUri(
                        context,
                        mpvConfStorageLocation.toUri(),
                      )
                    val inputConfFile = tree?.findFile("input.conf")
                    if (inputConfFile != null && inputConfFile.exists()) {
                      context.contentResolver
                        .openInputStream(
                          inputConfFile.uri,
                        )?.copyTo(tempFile.outputStream())
                      val content = tempFile.readLines().fastJoinToString("\n")
                      preferences.inputConf.set(content)
                      File(context.filesDir, "input.conf").writeText(content)
                      withContext(Dispatchers.Main) {
                        inputConf = content
                      }
                    }
                  }
                  tempFile.deleteIfExists()
                }
              }
              
              TwoTargetIconButtonPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_mpv_conf_storage_location),
                title = { Text(stringResource(R.string.pref_advanced_mpv_conf_storage_location), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  if (mpvConfStorageLocation.isNotBlank()) {
                    Text(
                      getSimplifiedPathFromUri(mpvConfStorageLocation),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                onClick = { locationPicker.launch(null) },
                iconButtonIcon = { 
                  Icon(
                    Icons.Default.Clear, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                  ) 
                },
                onIconButtonClick = { preferences.mpvConfStorageUri.delete() },
                iconButtonEnabled = mpvConfStorageLocation.isNotBlank(),
              )
              
              PreferenceDivider()
              
              Preference(
                icon = {
                  Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_mpv_conf),
                title = { Text(stringResource(R.string.pref_advanced_mpv_conf), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  val firstLine = mpvConf.lines().firstOrNull()
                  if (firstLine != null && firstLine.isNotBlank()) {
                    Text(
                      firstLine,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(stringResource(R.string.i18n_tap_to_edit_config),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                onClick = {
                  backStack.add(ConfigEditorScreen(ConfigEditorScreen.ConfigType.MPV_CONF))
                },
              )
              
              PreferenceDivider()
              
              Preference(
                icon = {
                  Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_input_conf),
                title = { Text(stringResource(R.string.pref_advanced_input_conf), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  val firstLine = inputConf.lines().firstOrNull()
                  if (firstLine != null && firstLine.isNotBlank()) {
                    Text(
                      firstLine,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(stringResource(R.string.i18n_tap_to_edit_config),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                onClick = {
                  backStack.add(ConfigEditorScreen(ConfigEditorScreen.ConfigType.INPUT_CONF))
                },
              )
            }
          }
          
          // Cache Section
          item {
            PreferenceSectionHeader(title = "缓存")
          }
          
          item {
            PreferenceCard {
              var mpvConf by remember { mutableStateOf(preferences.mpvConf.get()) }
              
              Preference(
                icon = {
                  Icon(
                    Icons.Outlined.Cached,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.i18n_clear_config_cache),
                title = { Text(text = stringResource(R.string.i18n_clear_config_cache), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = { 
                  Text(
                    text = stringResource(R.string.i18n_clear_config_cache_hint),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    val mpvConfFile = File(context.filesDir, "mpv.conf")
                    mpvConfFile.delete()
                    // Clear preferences too
                    preferences.mpvConf.delete()
                    withContext(Dispatchers.Main) {
                      mpvConf = ""
                      Toast
                        .makeText(
                          context,
                          "Config cache cleared",
                          Toast.LENGTH_SHORT,
                        ).show()
                    }
                  }
                },
              )
              PreferenceDivider()
              
              Preference(
                icon = {
                  Icon(
                    Icons.Outlined.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_clear_fonts_cache),
                title = { Text(text = stringResource(id = R.string.pref_advanced_clear_fonts_cache), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = { 
                  Text(
                    text = stringResource(R.string.i18n_clear_font_cache_hint),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    val fontsDir = File(context.filesDir.path + "/fonts")
                    if (fontsDir.exists()) {
                      fontsDir.listFiles()?.forEach { file ->
                        // Delete all font files
                        if (file.isFile &&
                          file.name
                            .lowercase()
                            .matches(".*\\.[ot]tf$".toRegex())
                        ) {
                          file.delete()
                        }
                      }
                    }
                    withContext(Dispatchers.Main) {
                      Toast
                        .makeText(
                          context,
                          fontsClearedText,
                          Toast.LENGTH_SHORT,
                        ).show()
                    }
                  }
                },
              )
            }
          }
          
          // Logging Section
          item {
            PreferenceSectionHeader(title = "日志")
          }
          
          item {
            PreferenceCard {
              val activity = LocalActivity.current!!
              @Suppress("DEPRECATION")
              val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
              val verboseLogging by preferences.verboseLogging.collectAsState()
              
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = verboseLogging,
                onValueChange = preferences.verboseLogging::set,
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_verbose_logging_title),
                title = { Text(stringResource(R.string.pref_advanced_verbose_logging_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = { 
                  Text(
                    stringResource(R.string.pref_advanced_verbose_logging_summary),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                },
              )
              
              PreferenceDivider()
              
              Preference(
                icon = {
                  Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                modifier = Modifier.settingsAnchor(R.string.pref_advanced_dump_logs_title),
                title = { Text(stringResource(R.string.pref_advanced_dump_logs_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = { 
                  Text(
                    stringResource(R.string.pref_advanced_dump_logs_summary),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    val deviceInfo = CrashActivity.collectDeviceInfo()
                    val logcat = CrashActivity.collectLogcat()
    
                    clipboard.setText(AnnotatedString(CrashActivity.concatLogs(deviceInfo, null, logcat)))
                    CrashActivity.shareLogs(deviceInfo, null, logcat, activity)
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

fun getSimplifiedPathFromUri(uri: String): String =
  Environment.getExternalStorageDirectory().canonicalPath + "/" + Uri.decode(uri).substringAfterLast(":")
