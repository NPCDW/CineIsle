package app.marlboroadvance.mpvex.database.migrations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomV22MigrationContractTest {
  private fun source(path: String): String =
    sequenceOf(
      File("src/main/java/$path"),
      File("app/src/main/java/$path"),
    ).first { it.isFile }.readText()

  private fun schema(version: Int): String =
    sequenceOf(
      File("schemas/app.marlboroadvance.mpvex.database.MpvExDatabase/$version.json"),
      File("app/schemas/app.marlboroadvance.mpvex.database.MpvExDatabase/$version.json"),
    ).first { it.isFile }.readText()

  @Test
  fun `drop table manifest is exact and foreign key safe`() {
    assertEquals(
      listOf(
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
      ),
      ROOM_V22_DROPPED_TABLES,
    )
    assertEquals(ROOM_V22_DROPPED_TABLES.size, ROOM_V22_DROPPED_TABLES.toSet().size)
    assertTrue(
      "PlaylistItemEntity must be dropped before its parent",
      ROOM_V22_DROPPED_TABLES.indexOf("PlaylistItemEntity") < ROOM_V22_DROPPED_TABLES.indexOf("PlaylistEntity"),
    )
  }

  @Test
  fun `migration rebuilds playback state before issuing exact drop-if-exists statements`() {
    val migration = source("app/marlboroadvance/mpvex/database/migrations/RoomV22Migration.kt")

    assertTrue(migration.contains("db.execSQL(PLAYBACK_STATE_V22_CREATE_SQL.trimIndent())"))
    assertTrue(migration.contains("db.execSQL(PLAYBACK_STATE_V22_COPY_SQL.trimIndent())"))
    assertTrue(migration.contains("db.execSQL(\"DROP TABLE `PlaybackStateEntity`\")"))
    assertTrue(migration.contains("db.execSQL(\"ALTER TABLE `PlaybackStateEntity_new` RENAME TO `PlaybackStateEntity`\")"))
    assertTrue(migration.contains("db.execSQL(\"DROP TABLE IF EXISTS `${'$'}table`\")"))
    assertTrue(migration.indexOf("ALTER TABLE `PlaybackStateEntity_new` RENAME") < migration.indexOf("ROOM_V22_DROPPED_TABLES.forEach"))
    assertFalse(PLAYBACK_STATE_V22_CREATE_SQL.contains("videoZoom"))
    assertFalse(PLAYBACK_STATE_V22_COPY_SQL.contains("videoZoom"))
  }

  @Test
  fun `migration SQL is executable on JVM sqlite and preserves approved data`() {
    Class.forName("org.sqlite.JDBC")

    val connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
    connection.use { db ->
      db.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute("CREATE TABLE PlaybackStateEntity (`mediaTitle` TEXT NOT NULL PRIMARY KEY, `lastPosition` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `videoZoom` REAL NOT NULL, `sid` INTEGER NOT NULL, `secondarySid` INTEGER NOT NULL, `subDelay` INTEGER NOT NULL, `subSpeed` REAL NOT NULL, `aid` INTEGER NOT NULL, `audioDelay` INTEGER NOT NULL, `timeRemaining` INTEGER NOT NULL, `externalSubtitles` TEXT NOT NULL, `hasBeenWatched` INTEGER NOT NULL)")
        statement.execute("INSERT INTO PlaybackStateEntity VALUES ('sentinel', 321, 1.25, 0.75, 4, 8, 125, 0.95, 6, -250, 679, 'content://subtitle', 1)")
        statement.execute("CREATE TABLE PlaylistEntity (`id` INTEGER PRIMARY KEY)")
        statement.execute("CREATE TABLE PlaylistItemEntity (`id` INTEGER PRIMARY KEY, `playlistId` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES PlaylistEntity(`id`) ON DELETE CASCADE)")
        ROOM_V22_DROPPED_TABLES.drop(2).forEach { statement.execute("CREATE TABLE `$it` (`id` INTEGER)") }
        statement.execute(PLAYBACK_STATE_V22_CREATE_SQL.trimIndent())
        statement.execute(PLAYBACK_STATE_V22_COPY_SQL.trimIndent())
        statement.execute("DROP TABLE PlaybackStateEntity")
        statement.execute("ALTER TABLE PlaybackStateEntity_new RENAME TO PlaybackStateEntity")
        ROOM_V22_DROPPED_TABLES.forEach { statement.execute("DROP TABLE IF EXISTS `$it`") }
      }

      db.createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = 'sentinel'").use { row ->
          assertTrue(row.next())
          assertEquals(321, row.getInt("lastPosition"))
          assertEquals(1.25, row.getDouble("playbackSpeed"), 0.0)
          assertEquals("content://subtitle", row.getString("externalSubtitles"))
        }
        statement.executeQuery("PRAGMA table_info(PlaybackStateEntity)").use { columns ->
          val names = buildSet {
            while (columns.next()) add(columns.getString("name"))
          }
          assertFalse("videoZoom" in names)
        }
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { tables ->
          val names = buildSet {
            while (tables.next()) add(tables.getString("name"))
          }
          ROOM_V22_DROPPED_TABLES.forEach { assertFalse(it in names) }
        }
      }
    }
  }

  @Test
  fun `database registers 21 to 22 without destructive fallback`() {
    val database = source("app/marlboroadvance/mpvex/database/MpvExDatabase.kt")
    val module = source("app/marlboroadvance/mpvex/di/DatabaseModule.kt")

    assertTrue(database.contains("version = 22"))
    assertTrue(module.contains("MIGRATION_21_22"))
    assertFalse(module.contains(".fallbackToDestructiveMigration("))
    assertFalse(module.contains(".fallbackToDestructiveMigrationFrom("))
  }

  @Test
  fun `canonical schema directory retains every available historical schema`() {
    val canonicalDirectory = sequenceOf(
      File("schemas/app.marlboroadvance.mpvex.database.MpvExDatabase"),
      File("app/schemas/app.marlboroadvance.mpvex.database.MpvExDatabase"),
    ).first { it.isDirectory }

    listOf(1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22).forEach { version ->
      assertTrue("missing canonical schema $version.json", File(canonicalDirectory, "$version.json").isFile)
    }
  }

  @Test
  fun `schema 22 contains only the three approved entities`() {
    val schema22 = schema(22)
    val tableNames = Regex("\\\"tableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
      .findAll(schema22)
      .map { it.groupValues[1] }
      .toList()

    assertEquals(
      listOf("PlaybackStateEntity", "emby_server_identities", "search_history"),
      tableNames,
    )
    assertFalse(schema22.contains("videoZoom"))
    assertTrue(schema22.contains("index_emby_server_identities_sourceKey_remoteId"))
    assertTrue(schema22.contains("\"unique\": true"))
  }
}
