package app.marlboroadvance.mpvex.ui.player.controls
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `is`.xyz.mpv.MPVLib
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.LeftSideOvalShape
import app.marlboroadvance.mpvex.presentation.components.RightSideOvalShape
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlayerDragDirection
import app.marlboroadvance.mpvex.ui.player.PlayerUpdates
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.VerticalGestureRegion
import app.marlboroadvance.mpvex.ui.player.classifyPlayerDrag
import app.marlboroadvance.mpvex.ui.player.isHorizontalSeekEligible
import app.marlboroadvance.mpvex.ui.player.mapVolumeGesture
import app.marlboroadvance.mpvex.ui.player.verticalGestureRegion
import app.marlboroadvance.mpvex.ui.player.volumeGestureScalar
import app.marlboroadvance.mpvex.ui.player.SingleActionGesture
import app.marlboroadvance.mpvex.ui.theme.playerRippleConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 横滑快进的震动间隔（秒，相对位移）。
 *
 * **必须是相对位移，不能是片子的绝对时间轴** —— 后者在长片上会一路震个不停：
 * 拖到 3 小时位置的电影里，「每 2 秒震一次」意味着每 2 秒震一次，永远停不下来。
 *
 * 触发逻辑在 `horizontal_seek` 分支里，用「已震档数」单向推进，
 * 所以手指滑回原处不会重复震动（那一段跳了多久，用户已经知道了）。
 */
private const val SEEK_HAPTIC_INTERVAL_SEC = 2

@Suppress("CyclomaticComplexMethod", "MultipleEmitters")
@Composable
fun GestureHandler(
  viewModel: PlayerViewModel,
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val audioPreferences = koinInject<AudioPreferences>()
  val gesturePreferences = koinInject<GesturePreferences>()
  val panelShown by viewModel.panelShown.collectAsState()
  val allowGesturesInPanels by playerPreferences.allowGesturesInPanels.collectAsState()
  val paused by PlayerLib.propBoolean["pause"].collectAsState()
  val duration by PlayerLib.propInt["duration"].collectAsState()
  val playbackSpeed by PlayerLib.propFloat["speed"].collectAsState()
  val controlsShown by viewModel.controlsShown.collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val seekAmount by viewModel.doubleTapSeekAmount.collectAsState()
  val isSeekingForwards by viewModel.isSeekingForwards.collectAsState()
  val useSingleTapForCenter by gesturePreferences.useSingleTapForCenter.collectAsState()
  val doubleTapSeekAreaWidth by gesturePreferences.doubleTapSeekAreaWidth.collectAsState()
  var isDoubleTapSeeking by remember { mutableStateOf(false) }
  LaunchedEffect(seekAmount) {
    delay(800)
    isDoubleTapSeeking = false
    viewModel.updateSeekAmount(0)
    viewModel.updateSeekText(null)
    delay(100)
    viewModel.hideSeekBar()
  }
  val multipleSpeedGesture by playerPreferences.holdForMultipleSpeed.collectAsState()
  val showDynamicSpeedOverlay by playerPreferences.showDynamicSpeedOverlay.collectAsState()
  val brightnessGesture by playerPreferences.brightnessGesture.collectAsState()
  val volumeGesture by playerPreferences.volumeGesture.collectAsState()
  val pinchToZoomGesture by playerPreferences.pinchToZoomGesture.collectAsState()
  val horizontalSwipeToSeek by playerPreferences.horizontalSwipeToSeek.collectAsState()
  val horizontalSwipeSensitivity by playerPreferences.horizontalSwipeSensitivity.collectAsState()
  // 震动反馈总开关（issue 反馈新增）。下面三处震动都受它控制：
  // 横滑快进每跨一档、长按开始、倍速跨档位。
  val hapticFeedbackEnabled by playerPreferences.hapticFeedbackEnabled.collectAsState()
  var isLongPressing by remember { mutableStateOf(false) }
  var isDynamicSpeedControlActive by remember { mutableStateOf(false) }
  var isHorizontalSeekActive by remember { mutableStateOf(false) }
  var dynamicSpeedStartX by remember { mutableStateOf(0f) }
  var dynamicSpeedStartValue by remember { mutableStateOf(2f) }
  var lastAppliedSpeed by remember { mutableStateOf(2f) }
  var hasSwipedEnough by remember { mutableStateOf(false) }
  var longPressTriggeredDuringTouch by remember { mutableStateOf(false) }
  var isVerticalGestureActive by remember { mutableStateOf(false) }
  val currentVolumeFraction by viewModel.volumeGestureFraction.collectAsState()
  val currentMPVVolume by PlayerLib.propInt["volume"].collectAsState()
  val currentBrightness by viewModel.currentBrightness.collectAsState()
  val volumeBoostingCap = audioPreferences.volumeBoostCap.get()
  val haptics = LocalHapticFeedback.current
  val touchSlop = LocalViewConfiguration.current.touchSlop
  val currentDuration by rememberUpdatedState(duration)
  val coroutineScope = rememberCoroutineScope()

  // Isolated double-tap state tracking
  var tapCount by remember { mutableStateOf(0) }
  var lastTapTime by remember { mutableStateOf(0L) }
  var lastTapPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
  var lastTapRegion by remember { mutableStateOf<String?>(null) }
  var pendingSingleTapRegion by remember { mutableStateOf<String?>(null) }
  var pendingSingleTapPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
  val doubleTapTimeout = 250L
  val multiTapContinueWindow = 650L

  // Multi-tap seeking state
  var lastSeekRegion by remember { mutableStateOf<String?>(null) }
  var lastSeekTime by remember { mutableStateOf<Long?>(null) }

  // Auto-reset tap count on timeout and execute single tap if no double tap detected
  LaunchedEffect(tapCount, longPressTriggeredDuringTouch) {
    if (tapCount == 1 && pendingSingleTapRegion != null) {
      delay(doubleTapTimeout)
      // Timeout occurred, execute single tap action only if not double-tap seeking and not triggered by long press
      if (tapCount == 1 && pendingSingleTapRegion != null && !isDoubleTapSeeking && !longPressTriggeredDuringTouch) {
        val region = pendingSingleTapRegion!!
        val isCenterTap = region == "center"
        if (useSingleTapForCenter && isCenterTap) {
          viewModel.handleCenterSingleTap()
        } else {
          if (panelShown != Panels.None && !allowGesturesInPanels) {
            viewModel.panelShown.update { Panels.None }
          }
          if (controlsShown) {
            viewModel.hideControls()
          } else {
            viewModel.showControls()
          }
        }
        pendingSingleTapRegion = null
        pendingSingleTapPosition = null
      }
      tapCount = 0
      lastTapRegion = null
      if (!isDoubleTapSeeking) {
        isDoubleTapSeeking = false
        viewModel.updateSeekAmount(0)
      }
    }
  }

  // Reset double-tap seek state when seeking stops
  LaunchedEffect(seekAmount) {
    if (seekAmount == 0) {
      delay(100)
      isDoubleTapSeeking = false
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(
        horizontalSwipeToSeek,
        areControlsLocked,
        horizontalSwipeSensitivity,
        hapticFeedbackEnabled,
      ) {
        if (!horizontalSwipeToSeek || areControlsLocked) return@pointerInput

        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
          val startPosition = down.position
          val startTime = android.os.SystemClock.elapsedRealtime()

          var hasStartedSeeking = false
          var lastSeekDeltaX = 0f
          var initialVideoPosition = 0f
          // Count haptic buckets monotonically so reversing does not buzz the same interval twice.
          var seekHapticBuckets = 0
          val seekSensitivity = horizontalSwipeSensitivity

          fun updateSeek(deltaX: Float, change: androidx.compose.ui.input.pointer.PointerInputChange) {
            val targetPosition = (initialVideoPosition + deltaX * seekSensitivity).coerceAtLeast(0f)
            val maxDuration = viewModel.effectiveDurationSeconds().takeIf { it > 0f }
              ?: currentDuration?.toFloat() ?: 0f
            val clampedPosition = targetPosition.coerceAtMost(maxDuration)

            // Retain throttled live scrub, preview, haptics, and an exact final commit.
            viewModel.scrubTo(clampedPosition)

            if (hapticFeedbackEnabled) {
              val movedSecs = abs(clampedPosition - initialVideoPosition).toInt()
              val bucket = movedSecs / SEEK_HAPTIC_INTERVAL_SEC
              if (bucket > seekHapticBuckets) {
                seekHapticBuckets = bucket
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              }
            }

            viewModel.updateHorizontalSeekPreview(
              startSeconds = initialVideoPosition,
              targetSeconds = clampedPosition,
              durationSeconds = maxDuration,
            )
            change.consume()
          }

          var clearSeekPreview = false
          try {
            do {
              val event = awaitPointerEvent(PointerEventPass.Final)
              val pointerCount = event.changes.count { it.pressed }

              if (pointerCount == 1) {
                event.changes.forEach { change ->
                  if (change.pressed && !hasStartedSeeking) {
                    val deltaX = change.position.x - startPosition.x
                    val deltaY = change.position.y - startPosition.y
                    val timeSinceStart = android.os.SystemClock.elapsedRealtime() - startTime
                    lastSeekDeltaX = deltaX

                    if (classifyPlayerDrag(deltaX, deltaY, touchSlop) == PlayerDragDirection.VERTICAL) {
                      return@awaitEachGesture
                    }
                    if (isHorizontalSeekEligible(
                        deltaX = deltaX,
                        deltaY = deltaY,
                        touchSlop = touchSlop,
                        elapsedMillis = timeSinceStart,
                        panelOpen = panelShown != Panels.None,
                        longPressActive = isLongPressing,
                        speedControlActive = isDynamicSpeedControlActive,
                        claimedByAnotherGesture = change.isConsumed,
                      )) {
                      hasStartedSeeking = true
                      isHorizontalSeekActive = true
                      initialVideoPosition = viewModel.precisePosition.value

                      // Keep pause ownership in the ViewModel; StateFlow pause snapshots can lag.
                      viewModel.beginSeekPause()
                      viewModel.showSeekBar()
                      change.consume()
                    }
                  } else if (change.pressed) {
                    lastSeekDeltaX = change.position.x - startPosition.x
                  }

                  if (change.pressed && hasStartedSeeking) {
                    updateSeek(lastSeekDeltaX, change)
                  }
                }
              } else if (pointerCount > 1) {
                if (hasStartedSeeking) {
                  clearSeekPreview = true
                  viewModel.playerUpdate.update { PlayerUpdates.None }
                }
                break
              }
            } while (event.changes.any { it.pressed })
          } finally {
            if (hasStartedSeeking) {
              viewModel.commitScrubSeek()
              viewModel.endSeekPause()
              if (clearSeekPreview) {
                viewModel.clearHorizontalSeekPreview()
              } else {
                viewModel.finishHorizontalSeekPreview()
              }
            }
            isHorizontalSeekActive = false
          }
        }
      },
    ) {
      Box(
        // Keep every pre-existing gesture on the original inset surface; only seek lives outside.
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 48.dp, vertical = 48.dp)
          .pointerInput(areControlsLocked, doubleTapSeekAreaWidth, gesturePreferences, isVerticalGestureActive) {

        // Isolated double-tap detection that doesn't interfere with other gestures
        if (isVerticalGestureActive) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val downPosition = down.position
          val downTime = System.currentTimeMillis()

          // Calculate regions
          val seekAreaFraction = doubleTapSeekAreaWidth / 100f
          val leftBoundary = size.width * seekAreaFraction
          val rightBoundary = size.width * (1f - seekAreaFraction)
          val region = when {
            downPosition.x > rightBoundary -> "right"
            downPosition.x < leftBoundary -> "left"
            else -> "center"
          }

          // Track for potential drag
          var isDrag = false
          var wasConsumedByTapGesture = false

          do {
            val event = awaitPointerEvent()
            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break

            // Check if this is a drag (not a tap)
            val distance = sqrt(
              (pointer.position.x - downPosition.x) * (pointer.position.x - downPosition.x) +
              (pointer.position.y - downPosition.y) * (pointer.position.y - downPosition.y)
            )

            if (distance > 10f) {
              isDrag = true
              // Don't consume - let other pointer inputs handle drag gestures
            }

            if (!pointer.pressed) {
              // Pointer lifted - this is a tap if it wasn't a drag
              if (!isDrag && !wasConsumedByTapGesture) {
                val timeSinceLastTap = downTime - lastTapTime
                val positionChange = sqrt(
                  (downPosition.x - lastTapPosition.x) * (downPosition.x - lastTapPosition.x) +
                  (downPosition.y - lastTapPosition.y) * (downPosition.y - lastTapPosition.y)
                )

                // Check if this is a continuation of multi-tap sequence
                val isMultiTapContinuation =
                  lastTapRegion == region &&
                  timeSinceLastTap < multiTapContinueWindow &&
                  positionChange < 100f &&
                  tapCount >= 2 &&
                  isDoubleTapSeeking

                // Check if this is a valid double-tap
                val isDoubleTap =
                  timeSinceLastTap < doubleTapTimeout &&
                  lastTapRegion == region &&
                  positionChange < 100f &&
                  tapCount == 1

                if (isDoubleTap && !areControlsLocked) {
                  // Valid double-tap detected
                  tapCount = 2
                  lastTapTime = downTime
                  lastTapPosition = downPosition
                  pendingSingleTapRegion = null // Cancel pending single tap
                  pendingSingleTapPosition = null
                  wasConsumedByTapGesture = true
                  pointer.consume()

                  when (region) {
                    "right" -> {
                      val rightGesture = gesturePreferences.rightSingleActionGesture.get()
                      if (rightGesture == SingleActionGesture.Seek) {
                        isDoubleTapSeeking = true
                        lastSeekRegion = "right"
                        lastSeekTime = System.currentTimeMillis()
                        if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                      }
                      viewModel.handleRightDoubleTap()
                    }
                    "left" -> {
                      val leftGesture = gesturePreferences.leftSingleActionGesture.get()
                      if (leftGesture == SingleActionGesture.Seek) {
                        isDoubleTapSeeking = true
                        lastSeekRegion = "left"
                        lastSeekTime = System.currentTimeMillis()
                        if (isSeekingForwards) viewModel.updateSeekAmount(0)
                      }
                      viewModel.handleLeftDoubleTap()
                    }
                    "center" -> {
                      viewModel.handleCenterDoubleTap()
                    }
                  }
                } else if (isMultiTapContinuation && isDoubleTapSeeking) {
                  // Continue multi-tap seeking
                  tapCount++
                  wasConsumedByTapGesture = true
                  pointer.consume()
                  lastSeekTime = System.currentTimeMillis()
                  lastTapTime = downTime
                  lastTapPosition = downPosition

                  when (region) {
                    "right" -> {
                      val rightGesture = gesturePreferences.rightSingleActionGesture.get()
                      if (rightGesture == SingleActionGesture.Seek) {
                        if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                      }
                      viewModel.handleRightDoubleTap()
                    }
                    "left" -> {
                      val leftGesture = gesturePreferences.leftSingleActionGesture.get()
                      if (leftGesture == SingleActionGesture.Seek) {
                        if (isSeekingForwards) viewModel.updateSeekAmount(0)
                      }
                      viewModel.handleLeftDoubleTap()
                    }
                    "center" -> {
                      viewModel.handleCenterDoubleTap()
                    }
                  }
                } else if (tapCount == 0 || timeSinceLastTap >= doubleTapTimeout) {
                  // Single tap or timed out - start new tap sequence
                  tapCount = 1
                  lastTapTime = downTime
                  lastTapPosition = downPosition
                  lastTapRegion = region
                  pendingSingleTapRegion = region
                  pendingSingleTapPosition = downPosition
                  wasConsumedByTapGesture = true
                  pointer.consume()
                  // Don't execute single tap action yet - wait to see if second tap comes
                }
              }
              break
            }
          } while (event.changes.any { it.pressed })
        }
      }
      .pointerInput(areControlsLocked, multipleSpeedGesture, brightnessGesture, volumeGesture) {
        if ((!brightnessGesture && !volumeGesture && multipleSpeedGesture <= 0f) || areControlsLocked) return@pointerInput

        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startPosition = down.position

          // Reset long press tracking at the start of each gesture
          longPressTriggeredDuringTouch = false

          // State for vertical gestures (volume/brightness)
          var originalVolumeScalar = volumeGestureScalar(
            currentVolumeFraction,
            currentMPVVolume ?: 100,
            volumeBoostingCap,
          )
          var originalBrightness = currentBrightness
          var lastMPVVolumeValue = currentMPVVolume ?: 100
          var lastBrightnessValue = currentBrightness

          // Original speed for long press
          var originalSpeed = playbackSpeed ?: 1f

          // Track long press separately
          var longPressTriggered = false
          val longPressDelay = 500L
          var longPressJob = coroutineScope.launch {
            delay(longPressDelay)
            if (!longPressTriggered && paused == false) {
              val distance = sqrt(
                (down.position.x - startPosition.x) * (down.position.x - startPosition.x) +
                (down.position.y - startPosition.y) * (down.position.y - startPosition.y)
              )
              // Only trigger if still within tap threshold
              if (distance < 10f && multipleSpeedGesture > 0f) {
                longPressTriggered = true
                isLongPressing = true
                longPressTriggeredDuringTouch = true
                if (hapticFeedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                originalSpeed = playbackSpeed ?: 1f
                // Ramp speed up incrementally to avoid audio filter stutter
                val startSpeed = originalSpeed
                val targetSpeed = multipleSpeedGesture
                val steps = 5
                val stepDelay = 16L // ~one frame per step
                for (i in 1..steps) {
                  val t = i.toFloat() / steps
                  val intermediateSpeed = startSpeed + (targetSpeed - startSpeed) * t
                  PlayerLib.setPropertyFloat("speed", intermediateSpeed)
                  if (i < steps) delay(stepDelay)
                }

                if (showDynamicSpeedOverlay) {
                  isDynamicSpeedControlActive = true
                  hasSwipedEnough = false
                  dynamicSpeedStartX = startPosition.x
                  dynamicSpeedStartValue = multipleSpeedGesture
                  lastAppliedSpeed = multipleSpeedGesture
                  viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(multipleSpeedGesture, false) }
                } else {
                  viewModel.playerUpdate.update { PlayerUpdates.MultipleSpeed }
                }
              }
            }
          }

          var gestureType: String? = null

          do {
            val event = awaitPointerEvent()
            val pointerCount = event.changes.count { it.pressed }

            if (pointerCount == 1) {
              event.changes.forEach { change ->
                if (change.pressed) {
                  val currentPosition = change.position
                  val deltaX = currentPosition.x - startPosition.x
                  val deltaY = currentPosition.y - startPosition.y

                  // Determine gesture type based on initial drag direction
                  if (gestureType == null && (abs(deltaX) > 20f || abs(deltaY) > 20f)) {
                    // Cancel long press if drag started
                    longPressJob.cancel()

                    // Check if we're in long press mode with dynamic speed control
                    if (isLongPressing && isDynamicSpeedControlActive && showDynamicSpeedOverlay && abs(deltaX) > 10f) {
                      gestureType = "speed_control"
                    } else {
                      gestureType = if (abs(deltaX) > abs(deltaY) * 1.5f) {
                        "horizontal"
                      } else if (abs(deltaY) > abs(deltaX) * 1.5f) {
                        "vertical"
                      } else {
                        null
                      }
                    }

                    // Initialize gesture-specific state
                    when (gestureType) {
                      "speed_control" -> {
                        dynamicSpeedStartX = currentPosition.x
                        dynamicSpeedStartValue = PlayerLib.getPropertyFloat("speed") ?: multipleSpeedGesture
                      }
                      "vertical" -> {
                        if ((brightnessGesture || volumeGesture) && !isLongPressing) {
                          isVerticalGestureActive = true
                          originalVolumeScalar = volumeGestureScalar(
                            currentVolumeFraction,
                            currentMPVVolume ?: 100,
                            volumeBoostingCap,
                          )
                          originalBrightness = currentBrightness
                          lastMPVVolumeValue = currentMPVVolume ?: 100
                          lastBrightnessValue = currentBrightness
                        }
                      }
                    }
                  }

                  // Handle the appropriate gesture
                  when (gestureType) {
                    "speed_control" -> {
                      if (!showDynamicSpeedOverlay) return@forEach
                      if (isLongPressing && isDynamicSpeedControlActive && paused == false) {
                        change.consume()

                        val speedPresets = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)
                        val screenWidth = size.width.toFloat()

                        val deltaX = currentPosition.x - dynamicSpeedStartX
                        val swipeDetectionThreshold = 10.dp.toPx()

                        if (!hasSwipedEnough && abs(deltaX) >= swipeDetectionThreshold) {
                          hasSwipedEnough = true
                          viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(lastAppliedSpeed, true) }
                        }

                        if (hasSwipedEnough) {
                          val presetsRange = speedPresets.size - 1
                          val indexDelta = (deltaX / screenWidth) * presetsRange * 3.5f

                          val startIndex = speedPresets.indexOfFirst {
                            abs(it - dynamicSpeedStartValue) < 0.01f
                          }.takeIf { it >= 0 } ?: 4

                          val newIndex = (startIndex + indexDelta.toInt()).coerceIn(0, speedPresets.size - 1)
                          val newSpeed = speedPresets[newIndex]

                          if (abs(lastAppliedSpeed - newSpeed) > 0.01f) {
                            if (hapticFeedbackEnabled) {
                              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            lastAppliedSpeed = newSpeed
                            PlayerLib.setPropertyFloat("speed", newSpeed)
                            viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(newSpeed, true) }
                          }
                        }
                      }
                    }
                    "vertical" -> {
                      if ((brightnessGesture || volumeGesture) && !isLongPressing) {
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val fractionDelta = -(currentPosition.y - startPosition.y) / height * 1.5f

                        val changeVolume: () -> Unit = {
                          val target = mapVolumeGesture(originalVolumeScalar + fractionDelta, volumeBoostingCap)
                          viewModel.updateVolumeGesture(target.systemFraction)
                          if (target.mpvVolume != lastMPVVolumeValue) {
                            viewModel.changeMPVVolumeTo(target.mpvVolume)
                            lastMPVVolumeValue = target.mpvVolume
                          }
                          viewModel.displayVolumeSlider()
                        }
                        val changeBrightness: () -> Unit = {
                          val newBrightness = (originalBrightness + fractionDelta).coerceIn(0f, 1f)
                          if (abs(newBrightness - lastBrightnessValue) > 0.001f) {
                            viewModel.changeBrightnessTo(newBrightness, persist = false)
                            lastBrightnessValue = newBrightness
                          }
                          viewModel.displayBrightnessSlider()
                        }

                        when (verticalGestureRegion(
                          startX = startPosition.x,
                          width = size.width.toFloat(),
                          brightnessEnabled = brightnessGesture,
                          volumeEnabled = volumeGesture,
                        )) {
                          VerticalGestureRegion.BRIGHTNESS -> changeBrightness()
                          VerticalGestureRegion.VOLUME -> changeVolume()
                          VerticalGestureRegion.NONE -> Unit
                        }

                        change.consume()
                      }
                    }
                  }
                }
              }
            } else if (pointerCount > 1) {
              // Multi-finger gesture detected
              longPressJob.cancel()
              if (gestureType != null) {
                when (gestureType) {
                  "vertical" -> {
                    if (brightnessGesture || volumeGesture) {
                      isVerticalGestureActive = false
                      viewModel.persistBrightness(lastBrightnessValue)
                      lastMPVVolumeValue = currentMPVVolume ?: 100
                      lastBrightnessValue = currentBrightness
                    }
                  }
                }
                gestureType = null
              }
              break
            }
          } while (event.changes.any { it.pressed })

          // Handle gesture end
          longPressJob.cancel()

          if (isLongPressing) {
            isLongPressing = false
            isDynamicSpeedControlActive = false
            hasSwipedEnough = false
            // Ramp speed back down incrementally to avoid audio filter stutter
            val currentSpeed = PlayerLib.getPropertyFloat("speed") ?: multipleSpeedGesture
            val targetSpeed = originalSpeed
            val steps = 5
            val stepDelay = 16L
            coroutineScope.launch {
              for (i in 1..steps) {
                val t = i.toFloat() / steps
                val intermediateSpeed = currentSpeed + (targetSpeed - currentSpeed) * t
                PlayerLib.setPropertyFloat("speed", intermediateSpeed)
                if (i < steps) delay(stepDelay)
              }
            }
            viewModel.playerUpdate.update { PlayerUpdates.None }
          }

          when (gestureType) {
            "vertical" -> {
              if (brightnessGesture || volumeGesture) {
                isVerticalGestureActive = false
                viewModel.persistBrightness(lastBrightnessValue)
                lastMPVVolumeValue = currentMPVVolume ?: 100
                lastBrightnessValue = currentBrightness
              }
            }
          }
        }
      }
      .pointerInput(pinchToZoomGesture, areControlsLocked, isVerticalGestureActive) {
        if (!pinchToZoomGesture || areControlsLocked || isVerticalGestureActive) return@pointerInput
        awaitEachGesture {
          var zoom = 0f
          var gestureStarted = false
          var previousDistance = 0f
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size == 2) {
              val first = pressed[0].position
              val second = pressed[1].position
              val dx = second.x - first.x
              val dy = second.y - first.y
              val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
              if (previousDistance == 0f) {
                previousDistance = distance
                zoom = PlayerLib.getPropertyDouble("video-zoom")?.toFloat() ?: 0f
              } else {
                if (!gestureStarted && abs(distance - previousDistance) > 5f) {
                  gestureStarted = true
                  viewModel.playerUpdate.update { PlayerUpdates.PinchZoom }
                }
                if (gestureStarted) {
                  val delta = ln((distance / previousDistance).toDouble()).toFloat() * 1.2f
                  zoom = (zoom + delta).coerceIn(-1f, 3f)
                  viewModel.applyPinchZoom(zoom)
                }
                previousDistance = distance
              }
              pressed.forEach { it.consume() }
            } else if (pressed.size < 2 && previousDistance != 0f) {
              break
            }
          } while (event.changes.any { it.pressed })
        }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubleTapToSeekOvals(
  amount: Int,
  text: String?,
  showOvals: Boolean,
  showSeekIcon: Boolean,
  showSeekTime: Boolean,
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
) {
  val gesturePreferences = koinInject<GesturePreferences>()
  val doubleTapSeekAreaWidth by gesturePreferences.doubleTapSeekAreaWidth.collectAsState()
  val seekAreaFraction = doubleTapSeekAreaWidth / 100f
  
  val alpha by animateFloatAsState(if (amount == 0) 0f else 0.2f, label = "double_tap_animation_alpha")

  // Scale animation for text
  var scaleTarget by remember { mutableStateOf(1f) }
  val scale by animateFloatAsState(
      targetValue = scaleTarget,
      animationSpec = tween(durationMillis = 150),
      label = "text_scale"
  )
  
  LaunchedEffect(amount) {
      if (amount != 0) {
          scaleTarget = 1.2f
          delay(100)
          scaleTarget = 1f
      } else {
        scaleTarget = 1f
      }
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = if (amount > 0) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    CompositionLocalProvider(
      LocalRippleConfiguration provides playerRippleConfiguration,
    ) {
      if (amount != 0) {
        Box(
          modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(seekAreaFraction),
          contentAlignment = Alignment.Center,
        ) {
          if (showOvals) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .clip(if (amount > 0) RightSideOvalShape else LeftSideOvalShape)
                .background(Color.White.copy(alpha))
                .indication(interactionSource, ripple()),
            )
          }
          if (showSeekIcon || showSeekTime) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (amount < 0) {
                    CombiningChevronsAnimation(isRight = false, trigger = amount)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "- ${abs(amount)}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.scale(scale)
                    )
                } else {
                    Text(
                        text = "+ ${abs(amount)}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.scale(scale)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CombiningChevronsAnimation(isRight = true, trigger = amount)
                }
            }
          }
        }
      }
    }
  }
}

@Composable
fun CombiningChevronsAnimation(
    isRight: Boolean,
    trigger: Int,
    modifier: Modifier = Modifier
) {
    // List of active animations (unique IDs)
    val animations = remember { mutableStateListOf<Long>() }

    // Fire a new animation whenever trigger changes
    LaunchedEffect(trigger) {
        animations.add(System.nanoTime())
    }

    Row(modifier = modifier) {
        Box {
             // Static Chevron
              Icon(
                imageVector = if (isRight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
            
            // Render active moving chevrons
            animations.forEach { animId ->
                key(animId) {
                    MovingChevron(
                        isRight = isRight,
                        onFinished = { animations.remove(animId) }
                    )
                }
            }
        }
    }
}

@Composable
fun MovingChevron(
    isRight: Boolean,
    onFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(250, easing = LinearEasing)
        )
        onFinished()
    }
    
    val startOffset = if (isRight) -15f else 15f
    val currentOffset = startOffset * (1f - progress.value)
    val alpha = 1f - progress.value
    
    Icon(
        imageVector = if (isRight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(48.dp)
            .alpha(alpha)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(x = currentOffset.dp.roundToPx(), y = 0)
                }
            } 
    )
}
