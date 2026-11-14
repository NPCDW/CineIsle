package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyVersionMediaInfo
import app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun detailInfoText(row: MediaInfoEntry.Row): String = "${row.label}: ${row.value}"

/** 留在页面层测量并缓存，不随版本分组滚出视口重复测量。 */
@Composable
internal fun rememberDetailMediaInfoHeight(groups: List<EmbyVersionMediaInfo>): Dp {
  val density = LocalDensity.current
  val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
  val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
  val titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
  val rowStyle = MaterialTheme.typography.bodySmall
  val height by produceState(240.dp, groups, density, fontResolver, layoutDirection, titleStyle, rowStyle) {
    value = withContext(Dispatchers.Default) {
      // 独立测量器只由此后台协程使用，避免与实际文字布局争用缓存。
      val measurer = androidx.compose.ui.text.TextMeasurer(fontResolver, density, layoutDirection)
      val constraints = Constraints(maxWidth = with(density) { 248.dp.roundToPx() })
      fun textHeight(text: String, style: androidx.compose.ui.text.TextStyle): Int =
        measurer.measure(text, style, constraints = constraints).size.height
      val cards = groups.flatMap { group -> listOf("版本 0" to group.basic) + group.tracks.map { it.title to it.rows } }
      val maximum = cards.maxOfOrNull { (title, rows) ->
        textHeight(title, titleStyle) + rows.sumOf { textHeight(detailInfoText(it), rowStyle) } +
          with(density) { (32.dp + 12.dp + 6.dp * (rows.size - 1).coerceAtLeast(0)).roundToPx() }
      } ?: 0
      with(density) { maximum.toDp() + 2.dp }
    }
  }
  return height
}

/** 每个版本作为外层 LazyColumn 的独立条目，横向卡片仍使用共同高度。 */
@Composable
internal fun DetailMediaInfoGroup(group: EmbyVersionMediaInfo, height: Dp) {
  DetailSection(group.title) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      item(key = "version-0") { DetailInfoCard("版本 0", group.basic, Modifier.width(280.dp).height(height)) }
      itemsIndexed(group.tracks, key = { index, _ -> "track-$index" }) { _, track ->
        DetailInfoCard(track.title, track.rows, Modifier.width(280.dp).height(height))
      }
    }
  }
}

@Composable
private fun DetailInfoCard(title: String, rows: List<MediaInfoEntry.Row>, modifier: Modifier) {
  Card(modifier) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
          Text(detailInfoText(row), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.longPressToCopy(row.value, row.label))
        }
      }
    }
  }
}
