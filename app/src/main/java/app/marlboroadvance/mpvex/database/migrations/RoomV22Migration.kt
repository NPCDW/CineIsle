package app.marlboroadvance.mpvex.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val PLAYBACK_STATE_V22_CREATE_SQL = """
CREATE TABLE IF NOT EXISTS `PlaybackStateEntity_new` (`mediaTitle` TEXT NOT NULL, `lastPosition` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `sid` INTEGER NOT NULL, `secondarySid` INTEGER NOT NULL, `subDelay` INTEGER NOT NULL, `subSpeed` REAL NOT NULL, `aid` INTEGER NOT NULL, `audioDelay` INTEGER NOT NULL, `timeRemaining` INTEGER NOT NULL, `externalSubtitles` TEXT NOT NULL, `hasBeenWatched` INTEGER NOT NULL, PRIMARY KEY(`mediaTitle`))
"""

const val PLAYBACK_STATE_V22_COPY_SQL = """
INSERT INTO `PlaybackStateEntity_new` (
  `mediaTitle`,
  `lastPosition`,
  `playbackSpeed`,
  `sid`,
  `secondarySid`,
  `subDelay`,
  `subSpeed`,
  `aid`,
  `audioDelay`,
  `timeRemaining`,
  `externalSubtitles`,
  `hasBeenWatched`
)
SELECT
  `mediaTitle`,
  `lastPosition`,
  `playbackSpeed`,
  `sid`,
  `secondarySid`,
  `subDelay`,
  `subSpeed`,
  `aid`,
  `audioDelay`,
  `timeRemaining`,
  `externalSubtitles`,
  `hasBeenWatched`
FROM `PlaybackStateEntity`
"""

/**
 * Legacy Room tables removed by v22.
 *
 * Referencing/child tables precede referenced/parent tables (notably playlist items before playlists),
 * so the sequence remains safe with foreign-key enforcement enabled.
 */
val ROOM_V22_DROPPED_TABLES: List<String> = listOf(
  "PlaylistItemEntity",
  "PlaylistEntity",
  "RecentlyPlayedEntity",
  "video_metadata_cache",
  "network_favorites",
  "folder_cover_cache",
  "network_connections",
  "emby_download_tasks",
  "emby_downloaded_media",
  "disliked_media",
  "aggregate_libraries",
  "favorite_marks",
  "emby_servers",
)

/** Removes videoZoom from playback state and deletes all legacy tables no longer owned by the app. */
val MIGRATION_21_22: Migration = object : Migration(21, 22) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(PLAYBACK_STATE_V22_CREATE_SQL.trimIndent())
    db.execSQL(PLAYBACK_STATE_V22_COPY_SQL.trimIndent())
    db.execSQL("DROP TABLE `PlaybackStateEntity`")
    db.execSQL("ALTER TABLE `PlaybackStateEntity_new` RENAME TO `PlaybackStateEntity`")

    ROOM_V22_DROPPED_TABLES.forEach { table ->
      db.execSQL("DROP TABLE IF EXISTS `$table`")
    }
  }
}
