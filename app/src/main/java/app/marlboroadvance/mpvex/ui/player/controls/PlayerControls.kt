package app.marlboroadvance.mpvex.ui.player.controls

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.preferences.preference.minusAssign
import app.marlboroadvance.mpvex.preferences.preference.plusAssign
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.player.CacheTimeline
import app.marlboroadvance.mpvex.ui.player.Decoder.Companion.getDecoderFromValue
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlaybackMemory
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.redactUrlSecrets
import app.marlboroadvance.mpvex.ui.player.controls.components.CachedTimelineCanvas
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerStatusIndicators
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekbarWithTimers
import app.marlboroadvance.mpvex.ui.player.controls.components.SlideToUnlock
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.playerRippleConfiguration
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import org.koin.compose.koinInject
import kotlin.math.abs

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> =
  tween(durationMillis = 300, easing = FastOutSlowInEasing)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> =
  tween(durationMillis = 100, easing = LinearOutSlowInEasing)

@Composable
@Suppress("CyclomaticComplexMethod", "ViewModelForwarding")
fun PlayerControls(
  viewModel: PlayerViewModel,
  onBackPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val activity = LocalActivity.current as PlayerActivity
  val playerPreferences = koinInject<PlayerPreferences>()
  val controlsShown by viewModel.controlsShown.collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val paused by PlayerLib.propBoolean["pause"].collectAsState()
  val duration by PlayerLib.propInt["duration"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val cacheTimelineNode by viewModel.cacheTimelineNode.collectAsState()
  val playbackSpeed by PlayerLib.propFloat["speed"].collectAsState()
  val mpvDecoder by PlayerLib.propString["hwdec-current"].collectAsState()
  val playbackState by activity.loembyPlaybackState.collectAsState()
  // Reading this Compose state keeps the header and queue-bound controls current on item changes.
  val playlistIndex = activity.playlistIndex
  val currentPlaybackState = playbackState?.takeIf { it.spec.playlistIndex - 1 == playlistIndex }
  val mediaTitle = redactUrlSecrets(
    currentPlaybackState?.spec?.title?.takeIf(String::isNotBlank)
      ?: currentPlaybackState?.item?.Name?.takeIf(String::isNotBlank)
      ?: currentPlaybackState?.spec?.itemName?.takeIf(String::isNotBlank)
      ?: activity.playlistTitleAt(playlistIndex)?.takeIf(String::isNotBlank)
      ?: activity.getTitleForControls(),
  ).orEmpty()
  val queueNavigation = queueNavigationVisibility(activity.queueSizeForControls(), playlistIndex)
  val playerUpdate by viewModel.playerUpdate.collectAsState()
  val horizontalSeekPreview by viewModel.horizontalSeekPreview.collectAsState()
  val netSpeed by viewModel.netSpeedBytesPerSec.collectAsState()
  val brightness by viewModel.currentBrightness.collectAsState()
  val volumeFraction by viewModel.volumeGestureFraction.collectAsState()
  val isBrightnessFeedbackShown by viewModel.isBrightnessSliderShown.collectAsState()
  val isVolumeFeedbackShown by viewModel.isVolumeSliderShown.collectAsState()
  val brightnessTimestamp by viewModel.brightnessSliderTimestamp.collectAsState()
  val volumeTimestamp by viewModel.volumeSliderTimestamp.collectAsState()
  val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
  val showSystemStatusBar by playerPreferences.showSystemStatusBar.collectAsState()
  val showSystemNavigationBar by playerPreferences.showSystemNavigationBar.collectAsState()
  val autoHideMillis by playerPreferences.playerTimeToDisappear.collectAsState()
  val interaction = remember { MutableInteractionSource() }
  var resetControlsTimestamp by remember { mutableLongStateOf(0L) }
  var isSeeking by remember { mutableStateOf(false) }
  var isUnlockDragging by remember { mutableStateOf(false) }
  val invertDuration by playerPreferences.invertDuration.collectAsState()
  val effectiveDuration = safeDuration(precisePosition, preciseDuration, duration)
  val cacheTimeline = remember(cacheTimelineNode, effectiveDuration) {
    CacheTimeline.fromMpv(cacheTimelineNode, effectiveDuration.takeIf { it > 0f }?.toDouble())
  }
  val speed = playbackSpeed ?: 1f
  val decoder = getDecoderFromValue(mpvDecoder ?: "auto")

  val onOpenSheet: (Sheets) -> Unit = { sheet ->
    viewModel.sheetShown.value = sheet
    if (sheet == Sheets.None) {
      viewModel.showControls()
    } else {
      viewModel.hideControls()
      viewModel.panelShown.value = Panels.None
    }
  }
  val onOpenPanel: (Panels) -> Unit = { panel ->
    viewModel.panelShown.value = panel
    if (panel == Panels.None) {
      viewModel.showControls()
    } else {
      viewModel.hideControls()
      viewModel.sheetShown.value = Sheets.None
    }
  }

  LaunchedEffect(controlsShown, paused, isSeeking, isUnlockDragging, resetControlsTimestamp) {
    if (controlsShown && paused == false && !isSeeking && !isUnlockDragging) {
      delay(autoHideMillis.toLong())
      viewModel.hideControls()
    }
  }
  LaunchedEffect(brightnessTimestamp) {
    if (isBrightnessFeedbackShown && brightnessTimestamp > 0L) {
      delay(1_300L)
      viewModel.isBrightnessSliderShown.value = false
    }
  }
  LaunchedEffect(volumeTimestamp) {
    if (isVolumeFeedbackShown && volumeTimestamp > 0L) {
      delay(1_300L)
      viewModel.isVolumeSliderShown.value = false
    }
  }
  LaunchedEffect(playerUpdate) {
    if (playerUpdate != app.marlboroadvance.mpvex.ui.player.PlayerUpdates.None &&
      playerUpdate !is app.marlboroadvance.mpvex.ui.player.PlayerUpdates.DynamicSpeedControl &&
      playerUpdate !is app.marlboroadvance.mpvex.ui.player.PlayerUpdates.HorizontalSeek
    ) {
      delay(2_000L)
      viewModel.playerUpdate.value = app.marlboroadvance.mpvex.ui.player.PlayerUpdates.None
    }
  }

  GestureHandler(viewModel = viewModel, interactionSource = interaction)

  CompositionLocalProvider(
    LocalPlayerButtonsClickEvent provides { resetControlsTimestamp = System.currentTimeMillis() },
    LocalContentColor provides Color.White,
    LocalLayoutDirection provides LayoutDirection.Ltr,
    androidx.compose.material3.LocalRippleConfiguration provides playerRippleConfiguration,
  ) {
    Box(
      modifier = modifier.fillMaxSize(),
    ) {
      PlayerUpdateFeedback(
        update = playerUpdate,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 94.dp),
      )

      if (isBrightnessFeedbackShown || isVolumeFeedbackShown) {
        HorizontalGestureFeedback(
          icon = if (isBrightnessFeedbackShown) Icons.Default.BrightnessMedium else Icons.AutoMirrored.Default.VolumeUp,
          fraction = if (isBrightnessFeedbackShown) {
            brightness.coerceIn(0f, 1f)
          } else {
            volumeFraction.coerceIn(0f, 1f)
          },
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp),
        )
      }

      AnimatedVisibility(
        visible = controlsShown && !areControlsLocked,
        enter = fadeIn(playerControlsEnterAnimationSpec()),
        exit = fadeOut(playerControlsExitAnimationSpec()),
      ) {
        Box(Modifier.fillMaxSize()) {
          FixedControlsScrim()
          FixedStatusRow(
            netSpeed = netSpeed,
            modifier = Modifier
              .align(Alignment.TopCenter)
              .then(if (showSystemStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
          )
          FixedHeader(
            decoderLabel = decoder.title,
            mediaTitle = mediaTitle,
            logoItem = currentPlaybackState?.let { it.series ?: it.item },
            logoServer = activity.currentPlaybackServerForUi(),
            onBack = onBackPress,
            onMediaDetailsClick = { onOpenSheet(Sheets.MediaDetails) },
            onDecoderClick = { onOpenSheet(Sheets.Decoders) },
            onPipClick = activity::enterPipModeHidingOverlay,
            onMoreClick = { onOpenSheet(Sheets.More) },
            modifier = Modifier
              .align(Alignment.TopCenter)
              .padding(top = if (isPortrait) 42.dp else 36.dp)
              .then(if (showSystemStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
          )
        ControlsButton(
          icon = Icons.Default.LockOpen,
          title = stringResource(R.string.player_control_lock_controls),
          onClick = viewModel::lockControls,
          showBackground = false,
          color = controlColor,
          modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp).size(48.dp),
        )
        VerticalSpeedControl(
          speed = speed,
          onDecrease = { updateSpeed(speed - 0.05f, playerPreferences, viewModel) },
          onIncrease = { updateSpeed(speed + 0.05f, playerPreferences, viewModel) },
          onOpen = { onOpenSheet(Sheets.PlaybackSpeed) },
          modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
        )
        FixedBottomControls(
          position = precisePosition,
          duration = effectiveDuration,
          cacheTimeline = cacheTimeline,
          paused = paused == true,
          isPortrait = isPortrait,
          canPrevious = queueNavigation.previous,
          canNext = queueNavigation.next,
          onPrevious = activity::playPrevious,
          onPause = viewModel::pauseUnpause,
          onNext = activity::playNext,
          onAudio = { onOpenSheet(Sheets.AudioTracks) },
          onSubtitles = { onOpenSheet(Sheets.SubtitleTracks) },
          onPlaylist = { onOpenSheet(Sheets.Playlist) },
          onSeek = { value ->
            viewModel.beginSeekPause()
            isSeeking = true
            viewModel.scrubTo(value)
          },
          onSeekFinished = {
            isSeeking = false
            viewModel.commitScrubSeek()
            viewModel.endSeekPause()
            viewModel.showControls()
          },
          invertDuration = invertDuration,
          onToggleDuration = { playerPreferences.invertDuration.set(!invertDuration) },
          showSeekbar = horizontalSeekPreview == null,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .then(
              if (showSystemNavigationBar) {
                val insets = WindowInsets.navigationBars.asPaddingValues()
                Modifier.padding(
                  start = insets.calculateLeftPadding(LayoutDirection.Ltr),
                  end = insets.calculateRightPadding(LayoutDirection.Ltr),
                  bottom = insets.calculateBottomPadding(),
                )
              } else {
                Modifier
              },
            ),
        )
        }
      }

      AnimatedVisibility(
        visible = controlsShown && areControlsLocked,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
      ) {
        SlideToUnlock(
          onUnlock = viewModel::unlockControls,
          onDraggingChanged = { isUnlockDragging = it },
        )
      }

      if (horizontalSeekPreview != null) {
        MiniSeekFeedback(
          preview = requireNotNull(horizontalSeekPreview),
          cacheTimeline = cacheTimeline,
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp),
        )
      }
    }

    val subtitles by viewModel.subtitleTracks.collectAsState(persistentListOf())
    val audioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
    val sheetShown by viewModel.sheetShown.collectAsState()
    val speedPresets by playerPreferences.speedPresets.collectAsState()
    PlayerSheets(
      sheetShown = sheetShown,
      subtitles = subtitles.toImmutableList(),
      onAddSubtitle = viewModel::addSubtitle,
      onToggleSubtitle = { id ->
        viewModel.toggleSubtitle(id)
        if (viewModel.isSubtitleSelected(id)) {
          subtitles.find { it.id == id }?.let {
            PlaybackMemory.saveSubtitleTrack(playerPreferences, viewModel.seriesKey, it)
          }
        }
      },
      isSubtitleSelected = viewModel::isSubtitleSelected,
      onDisableSubtitles = {
        PlayerLib.setPropertyString("secondary-sid", "no")
        PlayerLib.setPropertyString("sid", "no")
      },
      onRemoveSubtitle = viewModel::removeSubtitle,
      audioTracks = audioTracks.toImmutableList(),
      onDisableAudio = { PlayerLib.setPropertyString("aid", "no") },
      onSelectAudio = { track ->
        if (PlayerLib.getPropertyInt("aid") == track.id) {
          PlayerLib.setPropertyBoolean("aid", false)
        } else {
          PlayerLib.setPropertyInt("aid", track.id)
          PlaybackMemory.saveAudioTrack(playerPreferences, viewModel.seriesKey, track)
        }
      },
      decoder = decoder,
      onUpdateDecoder = { PlayerLib.setPropertyString("hwdec", it.value) },
      speed = speed,
      speedPresets = speedPresets.mapNotNull(String::toFloatOrNull).sorted(),
      onSpeedChange = { updateSpeed(it, playerPreferences, viewModel) },
      onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
      onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
      onResetSpeedPresets = playerPreferences.speedPresets::delete,
      onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
      onResetDefaultSpeed = {
        PlayerLib.setPropertyFloat("speed", playerPreferences.defaultSpeed.deleteAndGet().toFixed(2))
      },
      onOpenPanel = onOpenPanel,
      onDismissRequest = { onOpenSheet(Sheets.None) },
    )

    val panel by viewModel.panelShown.collectAsState()
    PlayerPanels(panelShown = panel, onDismissRequest = { onOpenPanel(Panels.None) })
  }
}

@Composable
private fun FixedControlsScrim() {
  Box(
    Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          0f to Color.Black.copy(alpha = 0.7f),
          0.28f to Color.Transparent,
          0.58f to Color.Transparent,
          1f to Color.Black.copy(alpha = 0.78f),
        ),
      ),
  )
}

@Composable
private fun FixedStatusRow(
  netSpeed: Long?,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
    PlayerStatusIndicators(
      showClock = true,
      showBattery = false,
      showNetSpeed = false,
      netSpeedBytesPerSec = null,
      modifier = Modifier.align(Alignment.Center),
    )
    PlayerStatusIndicators(
      showClock = false,
      showBattery = true,
      showNetSpeed = netSpeed != null,
      netSpeedBytesPerSec = netSpeed,
      modifier = Modifier.align(Alignment.CenterEnd),
    )
  }
}

@Composable
private fun FixedHeader(
  decoderLabel: String,
  mediaTitle: String,
  logoItem: app.marlboroadvance.mpvex.domain.emby.EmbyItem?,
  logoServer: app.marlboroadvance.mpvex.domain.emby.EmbyServer?,
  onBack: () -> Unit,
  onMediaDetailsClick: () -> Unit,
  onDecoderClick: () -> Unit,
  onPipClick: () -> Unit,
  onMoreClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ControlsButton(
      icon = Icons.AutoMirrored.Default.ArrowBack,
      title = stringResource(R.string.player_control_back),
      onClick = onBack,
      showBackground = false,
      modifier = Modifier.size(48.dp),
    )
    val mediaDetailsLabel = stringResource(R.string.player_control_open_media_details)
    val logoTag = logoItem?.ImageTags?.get("Logo")?.takeIf(String::isNotBlank)
    Surface(
      modifier = Modifier
        .height(46.dp)
        .widthIn(min = 96.dp, max = 220.dp)
        .clip(RoundedCornerShape(8.dp))
        .semantics(mergeDescendants = true) {
          contentDescription = mediaDetailsLabel
        }
        .clickable(role = Role.Button, onClick = onMediaDetailsClick),
      color = Color.Transparent,
    ) {
      if (logoTag != null && logoItem.Id != null && logoServer != null) {
        EmbyImage(
          url = app.marlboroadvance.mpvex.domain.emby.EmbyClient.imageUrl(
            logoServer,
            logoItem.Id,
            "Logo",
            logoTag,
            720,
          ),
          server = logoServer,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
          maxWidth = 720,
          placeholderContent = { PlayerHeaderTitle(mediaTitle) },
        )
      } else {
        PlayerHeaderTitle(mediaTitle)
      }
    }
    Spacer(Modifier.weight(1f))
    Surface(
      modifier = Modifier
        .height(44.dp)
        .clip(CircleShape)
        .clickable(onClick = onDecoderClick),
      shape = CircleShape,
      color = Color.Transparent,
    ) {
      Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(decoderLabel, color = Color.White, fontWeight = FontWeight.Medium)
      }
    }
    ControlsButton(
      icon = Icons.Default.PictureInPictureAlt,
      title = stringResource(R.string.player_control_pip),
      onClick = onPipClick,
      showBackground = false,
      modifier = Modifier.size(48.dp),
    )
    ControlsButton(
      icon = Icons.Default.MoreVert,
      title = stringResource(R.string.player_control_more),
      onClick = onMoreClick,
      showBackground = false,
      modifier = Modifier.size(48.dp),
    )
  }
}

@Composable
private fun PlayerHeaderTitle(mediaTitle: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
    Text(
      text = mediaTitle,
      color = Color.White,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun VerticalSpeedControl(
  speed: Float,
  onDecrease: () -> Unit,
  onIncrease: () -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.width(66.dp),
    shape = RoundedCornerShape(22.dp),
    color = Color.Black.copy(alpha = 0.46f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      SpeedAction(Icons.Default.Add, onIncrease)
      Text(
        text = "${String.format(java.util.Locale.US, "%.2f", speed)}x",
        modifier = Modifier
          .clickable(onClick = onOpen)
          .padding(vertical = 14.dp),
        color = Color.White,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
      )
      SpeedAction(Icons.Default.Remove, onDecrease)
    }
  }
}

@Composable
private fun SpeedAction(icon: ImageVector, onClick: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(48.dp)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = Color.White)
  }
}

@Composable
private fun FixedBottomControls(
  position: Float,
  duration: Float,
  cacheTimeline: CacheTimeline,
  paused: Boolean,
  isPortrait: Boolean,
  canPrevious: Boolean,
  canNext: Boolean,
  onPrevious: () -> Unit,
  onPause: () -> Unit,
  onNext: () -> Unit,
  onAudio: () -> Unit,
  onSubtitles: () -> Unit,
  onPlaylist: () -> Unit,
  onSeek: (Float) -> Unit,
  onSeekFinished: () -> Unit,
  invertDuration: Boolean,
  onToggleDuration: () -> Unit,
  showSeekbar: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (showSeekbar) {
      SeekbarWithTimers(
        position = position,
        duration = duration,
        onValueChange = onSeek,
        onValueChangeFinished = onSeekFinished,
        timersInverted = false to invertDuration,
        positionTimerOnClick = {},
        durationTimerOnCLick = onToggleDuration,
        cacheTimeline = cacheTimeline,
      )
    }
    if (isPortrait) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        TransportButtons(canPrevious, canNext, paused, onPrevious, onPause, onNext)
        TrackButtons(onAudio, onSubtitles, onPlaylist)
      }
    } else {
      Box(Modifier.fillMaxWidth()) {
        TransportButtons(
          canPrevious,
          canNext,
          paused,
          onPrevious,
          onPause,
          onNext,
          modifier = Modifier.align(Alignment.CenterStart),
        )
        TrackButtons(
          onAudio,
          onSubtitles,
          onPlaylist,
          modifier = Modifier.align(Alignment.CenterEnd),
        )
      }
    }
  }
}

@Composable
private fun TransportButtons(
  canPrevious: Boolean,
  canNext: Boolean,
  paused: Boolean,
  onPrevious: () -> Unit,
  onPause: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (canPrevious) {
      BottomIcon(Icons.Default.SkipPrevious, stringResource(R.string.player_control_previous), onPrevious)
    } else {
      Spacer(Modifier.size(46.dp))
    }
    BottomIcon(
      if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
      stringResource(if (paused) R.string.player_control_play else R.string.player_control_pause),
      onPause,
      54.dp,
    )
    if (canNext) {
      BottomIcon(Icons.Default.SkipNext, stringResource(R.string.player_control_next), onNext)
    } else {
      Spacer(Modifier.size(46.dp))
    }
  }
}

@Composable
private fun TrackButtons(
  onAudio: () -> Unit,
  onSubtitles: () -> Unit,
  onPlaylist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    BottomIcon(Icons.Default.Audiotrack, stringResource(R.string.player_control_audio), onAudio)
    BottomIcon(Icons.Default.Subtitles, stringResource(R.string.player_control_subtitles), onSubtitles)
    BottomIcon(Icons.Default.PlaylistPlay, stringResource(R.string.player_control_playlist), onPlaylist)
  }
}

@Composable
private fun BottomIcon(
  icon: ImageVector,
  title: String,
  onClick: () -> Unit,
  size: androidx.compose.ui.unit.Dp = 46.dp,
) {
  ControlsButton(
    icon = icon,
    title = title,
    onClick = onClick,
    showBackground = false,
    modifier = Modifier.size(size),
  )
}

@Composable
private fun PlayerUpdateFeedback(
  update: app.marlboroadvance.mpvex.ui.player.PlayerUpdates,
  modifier: Modifier = Modifier,
) {
  val text = when (update) {
    app.marlboroadvance.mpvex.ui.player.PlayerUpdates.None -> null
    app.marlboroadvance.mpvex.ui.player.PlayerUpdates.MultipleSpeed -> null
    is app.marlboroadvance.mpvex.ui.player.PlayerUpdates.DynamicSpeedControl ->
      "${String.format(java.util.Locale.US, "%.2f", update.speed)}x"
    app.marlboroadvance.mpvex.ui.player.PlayerUpdates.PinchZoom -> stringResource(R.string.player_update_pinch_zoom)
    is app.marlboroadvance.mpvex.ui.player.PlayerUpdates.HorizontalSeek -> null
    is app.marlboroadvance.mpvex.ui.player.PlayerUpdates.ShowText -> update.value
  }
  AnimatedVisibility(visible = text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = Color.Black.copy(alpha = 0.58f),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
      Text(
        text.orEmpty(),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        color = Color.White,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

@Composable
private fun HorizontalGestureFeedback(
  icon: ImageVector,
  fraction: Float,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.width(270.dp),
    shape = RoundedCornerShape(22.dp),
    color = Color.Black.copy(alpha = 0.58f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
  ) {
    Row(
      Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(icon, contentDescription = null, tint = Color.White)
      Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
        Box(
          Modifier
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.84f)),
        )
      }
      Text("${(fraction * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
private fun MiniSeekFeedback(
  preview: app.marlboroadvance.mpvex.ui.player.PlayerUpdates.HorizontalSeek,
  cacheTimeline: CacheTimeline,
  modifier: Modifier = Modifier,
) {
  val delta = preview.seekDeltaSeconds
  val deltaText = buildString {
    append(if (delta >= 0f) "+" else "-")
    append(Utils.prettyTime(abs(delta).toInt(), false))
  }
  Surface(
    modifier = modifier.widthIn(min = 260.dp, max = 440.dp),
    shape = RoundedCornerShape(18.dp),
    color = Color.Black.copy(alpha = 0.58f),
  ) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        buildString {
          append(Utils.prettyTime(preview.targetSeconds.toInt(), false))
          append(" / ")
          append(Utils.prettyTime(preview.durationSeconds.toInt(), false))
          append("  ")
          append(deltaText)
        },
        color = Color.White,
      )
      CachedTimelineCanvas(
        position = preview.targetSeconds,
        duration = preview.durationSeconds,
        timeline = cacheTimeline,
        modifier = Modifier.fillMaxWidth().height(12.dp),
        lineWidth = 4.dp,
        thumbRadius = 5.dp,
      )
    }
  }
}

private fun safeDuration(position: Float, precise: Float, integer: Int?): Float {
  val raw = if (precise > 0f) precise else integer?.toFloat() ?: 0f
  return if (raw > 0f && position > raw + 1f) position + 1f else raw.coerceAtLeast(0f)
}

private fun updateSpeed(
  requested: Float,
  preferences: PlayerPreferences,
  viewModel: PlayerViewModel,
) {
  val value = (Math.round(requested.coerceIn(0.1f, 4f) * 20f) / 20f).toFixed(2)
  PlayerLib.setPropertyFloat("speed", value)
  PlaybackMemory.saveSpeed(preferences, viewModel.seriesKey, value)
  if (preferences.rememberGlobalSpeed.get()) preferences.lastPlaybackSpeed.set(value)
}
