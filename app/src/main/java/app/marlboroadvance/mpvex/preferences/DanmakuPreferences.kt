package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

/** Preferences that remain user-configurable after the in-player danmaku sheet was removed. */
class DanmakuPreferences(preferenceStore: PreferenceStore) {
  /** Master switch. Disabled by default because matching requires network access and an extra render layer. */
  val enabled = preferenceStore.getBoolean("danmaku_enabled", false)

  /** Empty values are normalized to [DanmakuClient.DEFAULT_HOST] by the playback state. */
  val host = preferenceStore.getString("danmaku_host", DanmakuClient.DEFAULT_HOST)

  val appId = preferenceStore.getString(
    "danmaku_app_id",
    app.marlboroadvance.mpvex.BuildConfig.DANDAN_APP_ID,
  )

  val appSecret = preferenceStore.getString(
    "danmaku_app_secret",
    app.marlboroadvance.mpvex.BuildConfig.DANDAN_APP_SECRET,
  )

  /** When disabled, playback keeps the renderer idle instead of exposing a hidden manual picker. */
  val autoMatch = preferenceStore.getBoolean("danmaku_auto_match", true)
}
