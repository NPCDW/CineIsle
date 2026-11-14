package app.marlboroadvance.mpvex.ui.player.feed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.domain.emby.EmbyApiException
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt

private const val LOAD_TIMEOUT_MS = 15_000L
private const val SEEK_PREVIEW_INTERVAL_MS = 120L

/**
 * 视界流播放器。整个 Activity 只持有一份 [FeedMpvEngine]，翻页时用同一 mpv 实例换源。
 * VerticalPager 只负责分页和手势，不再为每页创建播放内核。
 */
class VerticalFeedActivity : ComponentActivity(), FeedPlayerController, FeedGestureHost, KoinComponent {

  private val embyRepository: EmbyRepository by inject()
  private val playerPreferences: PlayerPreferences by inject()

  private lateinit var viewModel: FeedViewModel
  private lateinit var mpvEngine: FeedMpvEngine
  private lateinit var audioManager: AudioManager

  private val gestureUi = FeedGestureUi()
  private val resumeSec = HashMap<String, Double>()

  private var tickerJob: Job? = null
  private var isExiting = false
  private var endHandled = false
  private var playingIndex = -1
  private var loadStartedAt = 0L

  private var seekPreviewTargetMs = 0L
  private var lastPreviewCommitAt = 0L
  private var wasPlayingBeforeSeek = false

  private var _topInsetPx by mutableFloatStateOf(0f)
  override val topInsetPx: Float get() = _topInsetPx
  private var _bottomInsetPx by mutableFloatStateOf(0f)
  override val bottomInsetPx: Float get() = _bottomInsetPx

  private var brightnessValue by mutableFloatStateOf(0.6f)
  private var volumeValue by mutableFloatStateOf(0.5f)

  override val brightnessFraction: Float get() = brightnessValue
  override val volumeFraction: Float get() = volumeValue
  override val landscape: Boolean get() = viewModel.landscape

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val items = readItems(intent).filter(::isFeedItemPlayable)
    if (items.isEmpty()) {
      finish()
      return
    }

    val server = runCatching {
      val id = intent.getLongExtra(EXTRA_SERVER_ID, 0L)
      val snapshot = embyRepository.sessionSnapshotById(id)
        ?: throw EmbyApiException(0, "播放服务器配置已失效")
      embyRepository.sessionServer(snapshot)
    }.getOrElse {
      android.widget.Toast.makeText(this, "播放服务器配置已失效，请重新打开", android.widget.Toast.LENGTH_LONG).show()
      finish()
      return
    }

    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    requestedOrientation = app.marlboroadvance.mpvex.ui.player.AppOrientationPolicy.VIDEO
    volumeControlStream = AudioManager.STREAM_MUSIC
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    viewModel = ViewModelProvider(
      this,
      FeedViewModel.factory(
        initialItems = items,
        embyRepository = embyRepository,
        libraryId = intent.getStringExtra(EXTRA_LIBRARY_ID),
        favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES_ONLY, false),
        server = server,
        initialConsumedCount = intent.getIntExtra(EXTRA_CONSUMED_COUNT, items.size),
      ),
    )[FeedViewModel::class.java]

    val rememberedBrightness = rememberedBrightness()
    brightnessValue = rememberedBrightness ?: readSystemBrightness()
    if (rememberedBrightness != null) applyWindowBrightness(rememberedBrightness)
    volumeValue = readSystemVolume()

    mpvEngine = FeedMpvEngine(this) { message ->
      if (!isExiting && playingIndex >= 0) viewModel.errors[playingIndex] = message
    }
    if (mpvEngine.ensureHost(layoutInflater) == null) {
      android.widget.Toast.makeText(this, "无法初始化 mpv 播放内核", android.widget.Toast.LENGTH_LONG).show()
      finish()
      return
    }

    setupSystemGestureInsets()

    setContent {
      val density = LocalDensity.current
      MpvexTheme {
        val pagerState = rememberPagerState(
          initialPage = viewModel.index.coerceAtLeast(0),
          pageCount = { viewModel.items.size },
        )
        var orientationLock by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState) {
          snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
              if (orientationLock || page !in viewModel.items.indices) return@collect
              if (page != viewModel.index) viewModel.syncFromPager(page)
              switchTo(page)
            }
        }

        LaunchedEffect(viewModel.index) {
          val target = viewModel.index
          if (target !in viewModel.items.indices) return@LaunchedEffect
          switchTo(target)
          if (pagerState.currentPage != target) {
            runCatching { pagerState.animateScrollToPage(target) }
          }
        }

        LaunchedEffect(viewModel.landscape) {
          orientationLock = true
          withFrameNanos { }
          withFrameNanos { }
          val target = viewModel.index
          if (target in viewModel.items.indices && pagerState.currentPage != target) {
            runCatching { pagerState.scrollToPage(target) }
          }
          withFrameNanos { }
          orientationLock = false
        }

        LaunchedEffect(viewModel.playToken) {
          if (viewModel.playToken == 0) return@LaunchedEffect
          reloadCurrent(viewModel.consumeReloadSeek())
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .feedGestures(host = this@VerticalFeedActivity, density = density.density),
        ) {
          VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
          ) { page ->
            FeedVideoPage(page)
          }

          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mpvEngine.hostView() ?: View(it) },
          )

          FeedScreen(vm = viewModel, controller = this@VerticalFeedActivity, gestureUi = gestureUi)
        }
      }
    }

    startTicker()
  }

  private fun setupSystemGestureInsets() {
    val fallback = 24f * resources.displayMetrics.density
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      _topInsetPx = bars.top.takeIf { it > 0 }?.toFloat() ?: fallback
      _bottomInsetPx = bars.bottom.takeIf { it > 0 }?.toFloat() ?: fallback
      insets
    }
    _topInsetPx = fallback
    _bottomInsetPx = fallback
  }

  @Composable
  private fun FeedVideoPage(index: Int) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(index) {
          detectTapGestures(
            onTap = {
              if (viewModel.landscape) {
                viewModel.controlsVisible = !viewModel.controlsVisible
              } else {
                togglePlayPause()
              }
            },
          )
        },
    )
  }

  private fun switchTo(index: Int) {
    if (index !in viewModel.items.indices || index == playingIndex || isExiting) return
    rememberCurrentPosition()
    playingIndex = index
    endHandled = false
    loadStartedAt = SystemClock.elapsedRealtime()
    viewModel.resetPlaybackState(index)
    play(index, resumeSec[viewModel.items[index].itemId])
  }

  private fun play(index: Int, resume: Double? = null) {
    val item = viewModel.items.getOrNull(index) ?: return
    runCatching {
      mpvEngine.play(
        item.mpvPlaybackSpec(
          server = viewModel.playbackServer(),
          playlistIndex = index + 1,
          playlistTotal = viewModel.items.size,
          resumeSec = resume,
        ),
      )
    }.onSuccess {
      viewModel.errors.remove(index)
    }.onFailure {
      mpvEngine.stop()
      viewModel.errors[index] = it.message ?: "播放服务器配置已失效，请重新打开"
    }
  }

  private fun rememberCurrentPosition() {
    val index = playingIndex
    val item = viewModel.items.getOrNull(index) ?: return
    val position = mpvEngine.positionSec()
    if (position.isFinite() && position >= 0.0) resumeSec[item.itemId] = position
    mpvEngine.stop()
  }

  private fun reloadCurrent(resume: Double) {
    val index = viewModel.index
    if (index !in viewModel.items.indices) return
    mpvEngine.stop()
    playingIndex = index
    endHandled = false
    loadStartedAt = SystemClock.elapsedRealtime()
    play(index, resume.takeIf { it > 0.0 })
  }

  private fun startTicker() {
    tickerJob?.cancel()
    tickerJob = lifecycleScope.launch {
      while (true) {
        runCatching { tick() }
        delay(250)
      }
    }
  }

  private fun tick() {
    if (isExiting || playingIndex != viewModel.index) return
    mpvEngine.pump()

    val position = mpvEngine.positionSec()
    val duration = mpvEngine.durationSec()
    viewModel.positionSec = position
    if (duration > 0.0) {
      viewModel.durationSec = duration
      loadStartedAt = 0L
    }
    viewModel.paused = mpvEngine.isPaused()
    viewModel.buffering = mpvEngine.isBuffering()
    if (mpvEngine.hasPicture()) viewModel.buffered = true

    if (mpvEngine.reachedEnd() && !endHandled) {
      endHandled = true
      viewModel.goNext()
    }

    if (loadStartedAt > 0L && duration <= 0.0 &&
      SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS
    ) {
      loadStartedAt = 0L
      viewModel.errors[playingIndex] = "加载失败：视频源不可用"
    }
  }

  override fun togglePlayPause() {
    val paused = !mpvEngine.isPaused()
    mpvEngine.setPaused(paused)
    viewModel.paused = paused
  }

  override fun seekTo(sec: Double) {
    mpvEngine.seekTo(sec)
  }

  override fun exit() {
    isExiting = true
    finish()
  }

  override val durationMs: Long
    get() = (mpvEngine.durationSec() * 1000.0).toLong().takeIf { it > 0 } ?: 0L

  override val positionMs: Long
    get() = (mpvEngine.positionSec() * 1000.0).toLong().coerceAtLeast(0L)

  override fun setBrightness(fraction: Float) {
    brightnessValue = fraction.coerceIn(0.01f, 1f)
    applyWindowBrightness(brightnessValue)
  }

  override fun commitBrightness(fraction: Float) {
    if (!playerPreferences.rememberBrightness.get()) return
    playerPreferences.defaultBrightness.set(fraction.coerceIn(0.01f, 1f))
  }

  private fun applyWindowBrightness(fraction: Float) {
    val lp = window.attributes
    lp.screenBrightness = fraction
    window.attributes = lp
  }

  private fun rememberedBrightness(): Float? {
    if (!playerPreferences.rememberBrightness.get()) return null
    return playerPreferences.defaultBrightness.get().takeIf { it in 0.01f..1f }
  }

  private fun readSystemBrightness(): Float =
    window.attributes.screenBrightness.takeIf { it in 0f..1f }
      ?: runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .coerceIn(0, 255) / 255f
      }.getOrDefault(0.6f)

  private fun readSystemVolume(): Float {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0.5f
    return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
  }

  override fun setVolume(fraction: Float) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    volumeValue = fraction.coerceIn(0f, 1f)
    runCatching {
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeValue * max).roundToInt(), 0)
    }
  }

  override fun showVerticalIndicator(kind: VerticalZone?, fraction: Float) {
    gestureUi.showIndicator(kind, fraction)
  }

  override fun beginSeek(startMs: Long) {
    seekPreviewTargetMs = startMs
    lastPreviewCommitAt = 0L
    gestureUi.seekStartMs = startMs
    gestureUi.seekTargetMs = startMs
    wasPlayingBeforeSeek = !mpvEngine.isPaused()
    mpvEngine.setPaused(true)
  }

  override fun previewSeek(targetMs: Long) {
    seekPreviewTargetMs = targetMs
    gestureUi.seekTargetMs = targetMs
    val now = SystemClock.elapsedRealtime()
    if (now - lastPreviewCommitAt < SEEK_PREVIEW_INTERVAL_MS) return
    lastPreviewCommitAt = now
    mpvEngine.seekTo(targetMs / 1000.0)
  }

  override fun commitSeek(targetMs: Long) {
    mpvEngine.seekTo(targetMs / 1000.0)
    if (wasPlayingBeforeSeek) mpvEngine.setPaused(false)
    wasPlayingBeforeSeek = false
    gestureUi.endSeek()
  }

  override fun requestEdgeBack() {
    exit()
  }

  override fun setRequestedOrientation(requestedOrientation: Int) {
    super.setRequestedOrientation(
      app.marlboroadvance.mpvex.ui.player.AppOrientationPolicy.constrainRequest(true, requestedOrientation),
    )
  }

  override fun onResume() {
    super.onResume()
    requestedOrientation = app.marlboroadvance.mpvex.ui.player.AppOrientationPolicy.VIDEO
  }

  override fun onPause() {
    super.onPause()
    if (::mpvEngine.isInitialized) mpvEngine.setPaused(true)
    if (::viewModel.isInitialized) viewModel.paused = true
  }

  override fun onDestroy() {
    isExiting = true
    tickerJob?.cancel()
    tickerJob = null
    if (::mpvEngine.isInitialized) mpvEngine.release()
    super.onDestroy()
  }

  companion object {
    private const val EXTRA_ITEMS = "feed_items"
    private const val EXTRA_LIBRARY_ID = "feed_library_id"
    private const val EXTRA_FAVORITES_ONLY = "feed_favorites_only"
    private const val EXTRA_SERVER_ID = "feed_server_id"
    private const val EXTRA_CONSUMED_COUNT = "feed_consumed_count"

    fun launch(
      context: Context,
      items: List<FeedItem>,
      libraryId: String?,
      favoritesOnly: Boolean,
      serverId: Long,
      initialConsumedCount: Int = items.size,
    ) {
      val playable = items.filter(::isFeedItemPlayable)
      if (playable.isEmpty()) return
      val intent = Intent(context, VerticalFeedActivity::class.java).apply {
        putExtra(EXTRA_ITEMS, ArrayList(playable))
        putExtra(EXTRA_LIBRARY_ID, libraryId)
        putExtra(EXTRA_FAVORITES_ONLY, favoritesOnly)
        putExtra(EXTRA_SERVER_ID, serverId)
        putExtra(EXTRA_CONSUMED_COUNT, initialConsumedCount)
      }
      if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun readItems(intent: Intent): List<FeedItem> =
      (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<FeedItem>).orEmpty()
  }
}
