package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.CacheTimeline
import app.marlboroadvance.mpvex.ui.player.controls.LocalPlayerButtonsClickEvent
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.Utils

/** Cache classification is independent of the playback position; the thumb alone marks progress. */
internal enum class TimelineIntervalKind {
  Cached,
  Uncached,
}

internal data class TimelineClassifiedInterval(
  val startSeconds: Double,
  val endSeconds: Double,
  val kind: TimelineIntervalKind,
)

internal data class TimelineColorPalette<T>(
  val cached: T,
  val uncached: T,
)

internal fun <T> TimelineColorPalette<T>.colorFor(kind: TimelineIntervalKind): T = when (kind) {
  TimelineIntervalKind.Cached -> cached
  TimelineIntervalKind.Uncached -> uncached
}

internal fun classifyTimelineIntervals(
  timeline: CacheTimeline,
  durationSeconds: Double,
): List<TimelineClassifiedInterval> {
  if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return emptyList()

  return buildList {
    timeline.cachedRanges.forEach { range ->
      add(TimelineClassifiedInterval(range.startSeconds, range.endSeconds, TimelineIntervalKind.Cached))
    }
    timeline.uncachedRanges.forEach { range ->
      add(TimelineClassifiedInterval(range.startSeconds, range.endSeconds, TimelineIntervalKind.Uncached))
    }
  }.sortedWith(compareBy(TimelineClassifiedInterval::startSeconds, TimelineClassifiedInterval::endSeconds))
}

@Composable
internal fun SeekbarWithTimers(
  position: Float,
  duration: Float,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnCLick: () -> Unit,
  cacheTimeline: CacheTimeline,
  modifier: Modifier = Modifier,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val liveDuration by rememberUpdatedState(duration)
  var isInteracting by remember { mutableStateOf(false) }
  var userPosition by remember { mutableFloatStateOf(position) }
  val shownPosition = if (isInteracting) userPosition else position
  val progressDescription = stringResource(
    R.string.player_seek_progress_description,
    Utils.prettyTime(shownPosition.toInt(), false),
    Utils.prettyTime(duration.coerceAtLeast(0f).toInt(), false),
  )

  Row(
    modifier = modifier.height(48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    VideoTimer(
      value = shownPosition,
      isInverted = timersInverted.first,
      onClick = { clickEvent(); positionTimerOnClick() },
      modifier = Modifier.width(92.dp),
    )
    Box(
      Modifier
        .weight(1f)
        .fillMaxHeight()
        .semantics {
          val total = duration.coerceAtLeast(0f)
          progressBarRangeInfo = ProgressBarRangeInfo(shownPosition.coerceIn(0f, total), 0f..total.coerceAtLeast(0.1f))
          contentDescription = progressDescription
          setProgress { target ->
            if (total <= 0f) return@setProgress false
            val clamped = target.coerceIn(0f, total)
            userPosition = clamped
            isInteracting = false
            onValueChange(clamped)
            onValueChangeFinished()
            true
          }
        }
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            val total = liveDuration
            if (width <= 0f || total <= 0f) return@awaitEachGesture
            fun target(x: Float) = (x / width * total).coerceIn(0f, total)
            isInteracting = true
            userPosition = target(down.position.x)
            onValueChange(userPosition)
            down.consume()
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              userPosition = target(change.position.x)
              onValueChange(userPosition)
              change.consume()
            }
            isInteracting = false
            onValueChangeFinished()
          }
        },
      contentAlignment = Alignment.Center,
    ) {
      CachedTimelineCanvas(
        position = shownPosition,
        duration = duration,
        timeline = cacheTimeline,
        modifier = Modifier.fillMaxWidth().height(28.dp),
        lineWidth = 5.dp,
        thumbRadius = 7.dp,
      )
    }
    VideoTimer(
      value = if (timersInverted.second) shownPosition - duration else duration,
      isInverted = timersInverted.second,
      onClick = { clickEvent(); durationTimerOnCLick() },
      modifier = Modifier.width(92.dp),
    )
  }
}

@Composable
internal fun CachedTimelineCanvas(
  position: Float,
  duration: Float,
  timeline: CacheTimeline,
  modifier: Modifier = Modifier,
  lineWidth: androidx.compose.ui.unit.Dp = 5.dp,
  thumbRadius: androidx.compose.ui.unit.Dp = 7.dp,
) {
  Canvas(modifier) {
    val centerY = size.height / 2f
    val stroke = lineWidth.toPx()
    val total = duration.coerceAtLeast(0f)
    val pointFraction = if (total > 0f) (position / total).coerceIn(0f, 1f) else 0f

    val colors = TimelineColorPalette(
      cached = Color.White.copy(alpha = 0.58f),
      uncached = Color.White.copy(alpha = 0.24f),
    )

    if (total > 0f) {
      classifyTimelineIntervals(timeline, total.toDouble()).forEach { interval ->
        drawLine(
          color = colors.colorFor(interval.kind),
          start = Offset((interval.startSeconds / total * size.width).toFloat(), centerY),
          end = Offset((interval.endSeconds / total * size.width).toFloat(), centerY),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
        )
      }
    } else {
      drawLine(
        color = colors.uncached,
        start = Offset(0f, centerY),
        end = Offset(size.width, centerY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
      )
    }
    drawCircle(
      color = Color.White,
      radius = thumbRadius.toPx(),
      center = Offset(size.width * pointFraction, centerY),
    )
  }
}

@Composable
fun VideoTimer(
  value: Float,
  isInverted: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .fillMaxHeight()
      .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = Utils.prettyTime(value.toInt(), isInverted),
      color = Color.White,
      textAlign = TextAlign.Center,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewSeekBar() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
    onValueChange = {},
    onValueChangeFinished = {},
    timersInverted = false to true,
    positionTimerOnClick = {},
    durationTimerOnCLick = {},
    cacheTimeline = CacheTimeline.fromMpv(null, 180.0),
  )
}
