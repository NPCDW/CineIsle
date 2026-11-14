package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationItem
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationRepository
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationSection
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationServerSnapshot
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** No-field route. No snapshot, source token, credential, or endpoint is part of navigation state. */
@Serializable
object SharedConfigurationPreferencesScreen : Screen {
  @Composable
  override fun Content() {
    val repository = koinInject<SharedConfigurationRepository>()
    val model: SharedConfigurationViewModel = viewModel(
      key = "shared-configuration-read-only",
      factory = remember(repository) {
        viewModelFactory { initializer { SharedConfigurationViewModel(repository) } }
      },
    )
    SharedConfigurationPreferencesContent(model)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedConfigurationPreferencesContent(viewModel: SharedConfigurationViewModel) {
  val backStack = LocalBackStack.current
  val state by viewModel.state.collectAsState()
  val expanded = remember { mutableStateMapOf<String, Boolean>() }
  LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "共享配置（只读）",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
          )
        },
        navigationIcon = {
          IconButton(onClick = backStack::removeLastOrNull) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
      item(key = "readonly-notice") {
        ReadOnlyNotice(stale = state.stale)
      }
      state.error?.let { failure ->
        item(key = "error") {
          ErrorCard(
            failure = failure,
            hasSnapshot = state.snapshot != null,
          )
        }
      }
      val snapshot = state.snapshot
      if (snapshot != null) {
        snapshot.sections.forEachIndexed { index, section ->
          item(key = "global-header-$index") { PreferenceSectionHeader(section.title) }
          item(key = "global-section-$index") { ReadOnlySection(section) }
        }
        item(key = "servers-header") { PreferenceSectionHeader("Emby 服务器") }
        if (snapshot.servers.isEmpty()) {
          item(key = "servers-empty") {
            PreferenceCard { ReadOnlyInformationRow("服务器", "没有共享服务器记录") }
          }
        } else {
          items(snapshot.servers, key = { it.summary }) { server ->
            val open = expanded[server.summary] == true
            ServerCard(server, open, onToggle = { expanded[server.summary] = !open })
          }
        }
      } else if (state.loading) {
        item(key = "loading") {
          Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
      } else if (state.error == null) {
        item(key = "empty") {
          PreferenceCard { ReadOnlyInformationRow("共享配置", "尚未读取，点右上角刷新") }
        }
      }
    }
  }
}

@Composable
private fun ReadOnlyNotice(stale: Boolean) {
  PreferenceCard {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        InfoTag("只读")
        Spacer(Modifier.width(8.dp))
        if (stale) InfoTag("旧快照")
      }
      Text("仅查看 Android 当前会生效的共享配置。请在 loemby 桌面端修改。", style = MaterialTheme.typography.bodyMedium)
      Text(
        "刷新只执行只读查询；本页不会保存、删除或回写 PostgreSQL。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ReadOnlySection(section: SharedConfigurationSection) {
  PreferenceCard {
    section.summary?.let {
      Text(
        text = it,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    section.items.forEachIndexed { index, item ->
      if (index > 0) PreferenceDivider()
      ReadOnlyInformationRow(item)
    }
  }
}

@Composable
private fun ServerCard(
  server: SharedConfigurationServerSnapshot,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  PreferenceCard {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(server.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(server.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Icon(
        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
        contentDescription = if (expanded) "收起" else "展开",
      )
    }
    if (expanded) {
      server.sections.forEach { section ->
        PreferenceDivider()
        Text(
          text = section.title,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        section.items.forEach { item -> ReadOnlyInformationRow(item) }
      }
    }
  }
}

@Composable
private fun ReadOnlyInformationRow(item: SharedConfigurationItem) =
  ReadOnlyInformationRow(item.label, item.value, item.info)

@Composable
private fun ReadOnlyInformationRow(label: String, value: String, info: String? = null) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(label, modifier = Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium)
    Column(modifier = Modifier.weight(0.58f), horizontalAlignment = Alignment.End) {
      Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      info?.let { InfoTag(it, Modifier.padding(top = 4.dp)) }
    }
  }
}

@Composable
private fun InfoTag(text: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = RoundedCornerShape(50),
  ) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun ErrorCard(failure: PostgresFailure, hasSnapshot: Boolean) {
  PreferenceCard {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(
        if (hasSnapshot) "刷新失败，正在显示同一来源的旧快照" else failureMessage(failure),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

private fun failureMessage(failure: PostgresFailure): String = when (failure) {
  PostgresFailure.SOURCE_CHANGED -> "PostgreSQL 来源已变化，旧快照已清空，请刷新。"
  PostgresFailure.SCHEMA_INCOMPATIBLE -> "共享数据库表结构或引用不兼容。"
  PostgresFailure.TIMEOUT -> "读取共享配置超时。"
  PostgresFailure.AUTHENTICATION -> "PostgreSQL 认证失败。"
  PostgresFailure.DATABASE -> "PostgreSQL 数据库不可用。"
  PostgresFailure.TLS -> "PostgreSQL TLS 校验失败。"
  PostgresFailure.LOAD, PostgresFailure.STORAGE -> "无法读取本机 PostgreSQL 连接配置。"
  else -> "无法读取共享配置。"
}
