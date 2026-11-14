package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun SeasonEpisodesSkeleton(compact: Boolean) {
  val shimmer = rememberShimmer()
  val count = if (compact) 6 else 3
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    repeat(count) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
          Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.height(96.dp) else Modifier.height(184.dp))
            .clip(RoundedCornerShape(12.dp))
            .shimmer(shimmer),
        )
        Box(Modifier.fillMaxWidth(0.58f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmer(shimmer))
        if (!compact) {
          Box(Modifier.fillMaxWidth(0.9f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer(shimmer))
        }
      }
    }
  }
}

/** 固定版式占位不改变加载完成后的区块宽度；刷新旧内容时不显示。 */
@Composable
internal fun DetailRowSkeleton(cardWidth: Int = 112, cardHeight: Int = 168, count: Int = 3) {
  val shimmer = rememberShimmer()
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    repeat(count) {
      Column(Modifier.width(cardWidth.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(cardHeight.dp).clip(RoundedCornerShape(12.dp))
          .shimmer(shimmer))
        Box(Modifier.fillMaxWidth(0.75f).height(14.dp).clip(RoundedCornerShape(4.dp))
          .shimmer(shimmer))
      }
    }
  }
}

@Composable
internal fun DetailSelectorsSkeleton() {
  val shimmer = rememberShimmer()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(4) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(48.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).shimmer(shimmer))
        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).shimmer(shimmer))
      }
    }
  }
}
