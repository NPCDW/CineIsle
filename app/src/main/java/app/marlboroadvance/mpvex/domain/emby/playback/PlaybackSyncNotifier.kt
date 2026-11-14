package app.marlboroadvance.mpvex.domain.emby.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Process-level delivery keeps stop failures visible after PlayerActivity has finished. */
internal class PlaybackSyncNotifier(context: Context) {
  private val appContext = context.applicationContext
  private val main = Handler(Looper.getMainLooper())

  fun show(message: String) {
    main.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
  }
}
