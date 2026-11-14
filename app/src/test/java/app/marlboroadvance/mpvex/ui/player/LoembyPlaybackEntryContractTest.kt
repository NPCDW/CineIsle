package app.marlboroadvance.mpvex.ui.player

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LoembyPlaybackEntryContractTest {
  private fun source(path: String): String = sequenceOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
    .first { it.isFile }.readText()

  @Test fun playerLoadsOnlyRegisteredLocalMediaAndUsesCoordinatorEvents() {
    val activity = source("app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt")
    val nativeQueue = source("app/marlboroadvance/mpvex/ui/player/NativeMpvPlaylistController.kt")
    assertFalse(activity.contains("PlayerLib.command(\"loadfile\""))
    assertTrue(activity.contains("nativePlaylist.replace(localUrls, startIndex, prefetch)"))
    assertTrue(nativeQueue.contains("transport.command(\"loadfile\", url, \"insert-at\", index.toString())"))
    assertTrue(nativeQueue.contains("transport.command(\"playlist-play-index\""))
    assertTrue(activity.contains("loemby.prepare(local, loembyTransport, loembyCallbacks, this@PlayerActivity)"))
    assertFalse(activity.contains("startEmbyReporting("))
    assertFalse(activity.contains("trackSelector.onFileLoaded("))
    assertFalse(activity.contains("parseItemIdFromPath(PlayerLib.getPropertyString(\"path\"))"))
    assertTrue(activity.contains("loopback.ownsMediaUrl(playbackUrl.orEmpty(), PlayerLib.getPropertyString(\"path\"))"))
    assertTrue(activity.contains("loemby.eofReached(this)"))
    assertTrue(activity.contains("loemby.finish(\"quit\", this@PlayerActivity)"))
    val observer = source("app/marlboroadvance/mpvex/ui/player/PlayerObserver.kt")
    assertTrue(observer.contains("generation == activity.playbackEventGeneration"))
  }

  @Test fun playbackQueueKeepsCurrentEpisodeIndexAndRegistersEveryMpvItem() {
    val vm = source("app/marlboroadvance/mpvex/ui/browser/emby/EmbyViewModel.kt")
    assertTrue(vm.contains("buildEpisodePlaylist(server, playItem, completeSeason = true)"))
    val queue = vm.substringAfter("private suspend fun launchPlaylistAwait").substringBefore("fun isContainerOnly")
    assertTrue(queue.contains("loopback.register(PlaybackSpec("))
    assertTrue(queue.contains("Intent(Intent.ACTION_VIEW, uris[startIndex])"))
    assertTrue(queue.contains("putExtra(\"playlist_index\", startIndex)"))
    assertTrue(queue.contains("putStreamHeaders(server, uris[startIndex].toString())"))
  }
}
