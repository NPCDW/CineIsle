package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import java.util.Locale

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/** Matches loemby's cache_speed.lua: one decimal and a fixed MB-per-second scale. */
fun formatNetworkSpeed(bytesPerSec: Long): String = String.format(
  Locale.US,
  "%.1f MB/s",
  bytesPerSec.coerceAtLeast(0L).toDouble() / BYTES_PER_MEGABYTE,
)

/** Direct snapshot of mpv's cache-speed property; the caller already polls once per second. */
object MpvNetSpeedSampler {
  @Suppress("UNUSED_PARAMETER")
  fun sample(isPaused: Boolean): Long =
    PlayerLib.getPropertyInt("cache-speed")?.toLong()?.coerceAtLeast(0L) ?: 0L

  /** Kept for the existing playback lifecycle call; direct sampling has no retained baseline. */
  fun reset() = Unit
}
