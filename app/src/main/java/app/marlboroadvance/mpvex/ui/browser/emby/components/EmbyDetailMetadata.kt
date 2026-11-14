package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy

@Composable
internal fun DetailSection(title: String, content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
    content()
  }
}

@Composable
internal fun DetailPeople(people: List<EmbyPerson>, server: EmbyServer, onOpenPerson: (EmbyPerson) -> Unit) {
  DetailSection("演职人员") {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      itemsIndexed(people) { _, person ->
        Column(
          modifier = Modifier.width(88.dp).clickable(enabled = person.Id != null) { onOpenPerson(person) },
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          EmbyImage(
            server = server,
            url = person.Id?.let { EmbyClient.imageUrl(server, it, "Primary", person.PrimaryImageTag, 240) },
            contentDescription = person.Name,
            placeholder = Icons.Default.Person,
            modifier = Modifier.size(72.dp).clip(CircleShape),
          )
          Spacer(Modifier.height(8.dp))
          Text(person.Name.orEmpty(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.longPressToCopy(person.Name, "姓名"))
          val role = person.Role ?: when (person.Type) {
            "Director" -> "导演"
            "Writer" -> "编剧"
            "Producer" -> "制片人"
            "Actor" -> "演员"
            else -> person.Type
          }
          if (!role.isNullOrBlank()) {
            Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

@Composable
internal fun DetailExternalTags(tags: List<Pair<String, String>>) {
  DetailSection("外部标签") {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      tags.forEach { (key, value) ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
          Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
          Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f).longPressToCopy(value, key))
        }
      }
    }
  }
}

@Composable
internal fun DetailExternalLinks(links: List<Pair<String, String>>) {
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current
  DetailSection("外部链接") {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      itemsIndexed(links) { _, link ->
        SuggestionChip(
          onClick = {
            runCatching { uriHandler.openUri(link.second) }
              .onFailure { Toast.makeText(context, "无法打开外部链接", Toast.LENGTH_SHORT).show() }
          },
          label = { Text(link.first) },
        )
      }
    }
  }
}
