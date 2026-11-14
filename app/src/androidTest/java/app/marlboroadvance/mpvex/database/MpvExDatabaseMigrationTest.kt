package app.marlboroadvance.mpvex.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marlboroadvance.mpvex.database.migrations.MIGRATION_20_21
import app.marlboroadvance.mpvex.database.migrations.MIGRATION_21_22
import app.marlboroadvance.mpvex.database.migrations.ROOM_V22_DROPPED_TABLES
import app.marlboroadvance.mpvex.di.MIGRATION_10_11
import app.marlboroadvance.mpvex.di.MIGRATION_11_12
import app.marlboroadvance.mpvex.di.MIGRATION_12_13
import app.marlboroadvance.mpvex.di.MIGRATION_13_14
import app.marlboroadvance.mpvex.di.MIGRATION_14_15
import app.marlboroadvance.mpvex.di.MIGRATION_15_16
import app.marlboroadvance.mpvex.di.MIGRATION_16_17
import app.marlboroadvance.mpvex.di.MIGRATION_17_18
import app.marlboroadvance.mpvex.di.MIGRATION_18_19
import app.marlboroadvance.mpvex.di.MIGRATION_19_20
import app.marlboroadvance.mpvex.di.MIGRATION_1_2 as M1_2
import app.marlboroadvance.mpvex.di.MIGRATION_2_3
import app.marlboroadvance.mpvex.di.MIGRATION_3_4
import app.marlboroadvance.mpvex.di.MIGRATION_4_5
import app.marlboroadvance.mpvex.di.MIGRATION_5_6
import app.marlboroadvance.mpvex.di.MIGRATION_6_7
import app.marlboroadvance.mpvex.di.MIGRATION_7_8
import app.marlboroadvance.mpvex.di.MIGRATION_8_9
import app.marlboroadvance.mpvex.di.MIGRATION_9_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvExDatabaseMigrationTest {
  private val databaseName = "migration-test.db"

  private fun helper(): MigrationTestHelper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      MpvExDatabase::class.java,
      emptyList(),
      FrameworkSQLiteOpenHelperFactory(),
    )

  private fun createIfSchemaExists(h: MigrationTestHelper, version: Int) {
    assumeTrue(
      "schemas/ 目录里缺 $version.json，该版本的迁移暂时无法验证（已跳过）",
      tryCreate(h, version),
    )
  }

  private fun createRequired(h: MigrationTestHelper, version: Int) {
    check(tryCreate(h, version)) {
      "必须提供 canonical Room schema $version.json；完整迁移链不得静默跳过"
    }
  }

  private fun tryCreate(h: MigrationTestHelper, version: Int): Boolean =
    runCatching { h.createDatabase(databaseName, version).close() }
      .onFailure { android.util.Log.w("MpvExMigrationTest", "缺 $version.json，跳过该版本", it) }
      .isSuccess

  private fun tableNames(db: SupportSQLiteDatabase): Set<String> = buildSet {
    db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
      val index = cursor.getColumnIndexOrThrow("name")
      while (cursor.moveToNext()) add(cursor.getString(index))
    }
  }

  private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
      val index = cursor.getColumnIndexOrThrow("name")
      while (cursor.moveToNext()) add(cursor.getString(index))
    }
  }

  @Test
  fun 迁移链从1到22完整() {
    val h = helper()
    createRequired(h, 1)

    h.runMigrationsAndValidate(
      databaseName,
      22,
      true,
      M1_2,
      MIGRATION_2_3,
      MIGRATION_3_4,
      MIGRATION_4_5,
      MIGRATION_5_6,
      MIGRATION_6_7,
      MIGRATION_7_8,
      MIGRATION_8_9,
      MIGRATION_9_10,
      MIGRATION_10_11,
      MIGRATION_11_12,
      MIGRATION_12_13,
      MIGRATION_13_14,
      MIGRATION_14_15,
      MIGRATION_15_16,
      MIGRATION_16_17,
      MIGRATION_17_18,
      MIGRATION_18_19,
      MIGRATION_19_20,
      MIGRATION_20_21,
      MIGRATION_21_22,
    ).close()
  }

  @Test
  fun 迁移链从13到22完整() {
    val h = helper()
    createRequired(h, 13)

    h.runMigrationsAndValidate(
      databaseName,
      22,
      true,
      MIGRATION_13_14,
      MIGRATION_14_15,
      MIGRATION_15_16,
      MIGRATION_16_17,
      MIGRATION_17_18,
      MIGRATION_18_19,
      MIGRATION_19_20,
      MIGRATION_20_21,
      MIGRATION_21_22,
    ).close()
  }

  @Test
  fun 迁移_21_22_保留哨兵数据并精简结构() {
    val h = helper()
    h.createDatabase(databaseName, 21).use { db ->
      db.execSQL(
        "INSERT INTO PlaybackStateEntity " +
          "(mediaTitle, lastPosition, playbackSpeed, videoZoom, sid, secondarySid, subDelay, subSpeed, aid, audioDelay, timeRemaining, externalSubtitles, hasBeenWatched) " +
          "VALUES ('playback-sentinel', 321, 1.25, 0.75, 4, 8, 125, 0.95, 6, -250, 679, 'content://subtitle', 1)",
      )
      db.execSQL(
        "INSERT INTO search_history(serverId, keyword, lastUsedAt, useCount) " +
          "VALUES (99, 'search-sentinel', 123456, 7)",
      )
      db.execSQL(
        "INSERT INTO emby_server_identities(localId, sourceKey, remoteId) " +
          "VALUES (${1L shl 48}, 'source-sentinel', 'remote-sentinel')",
      )
    }

    h.runMigrationsAndValidate(databaseName, 22, true, MIGRATION_21_22).use { db ->
      db.query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = 'playback-sentinel'").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(321, cursor.getInt(cursor.getColumnIndexOrThrow("lastPosition")))
        assertEquals(1.25, cursor.getDouble(cursor.getColumnIndexOrThrow("playbackSpeed")), 0.0)
        assertEquals(4, cursor.getInt(cursor.getColumnIndexOrThrow("sid")))
        assertEquals(8, cursor.getInt(cursor.getColumnIndexOrThrow("secondarySid")))
        assertEquals(125, cursor.getInt(cursor.getColumnIndexOrThrow("subDelay")))
        assertEquals(0.95, cursor.getDouble(cursor.getColumnIndexOrThrow("subSpeed")), 0.0)
        assertEquals(6, cursor.getInt(cursor.getColumnIndexOrThrow("aid")))
        assertEquals(-250, cursor.getInt(cursor.getColumnIndexOrThrow("audioDelay")))
        assertEquals(679, cursor.getInt(cursor.getColumnIndexOrThrow("timeRemaining")))
        assertEquals("content://subtitle", cursor.getString(cursor.getColumnIndexOrThrow("externalSubtitles")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasBeenWatched")))
      }
      assertFalse("videoZoom 必须从 PlaybackStateEntity 删除", "videoZoom" in columnNames(db, "PlaybackStateEntity"))

      db.query("SELECT lastUsedAt, useCount FROM search_history WHERE serverId = 99 AND keyword = 'search-sentinel'").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(123456L, cursor.getLong(0))
        assertEquals(7, cursor.getInt(1))
      }
      db.query("SELECT sourceKey, remoteId FROM emby_server_identities WHERE localId = ${1L shl 48}").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("source-sentinel", cursor.getString(0))
        assertEquals("remote-sentinel", cursor.getString(1))
      }

      val tables = tableNames(db)
      assertTrue("PlaybackStateEntity 必须保留", "PlaybackStateEntity" in tables)
      assertTrue("emby_server_identities 必须保留", "emby_server_identities" in tables)
      assertTrue("search_history 必须保留", "search_history" in tables)
      ROOM_V22_DROPPED_TABLES.forEach { table ->
        assertFalse("v22 必须删除 $table，实际表：$tables", table in tables)
      }
    }
  }

  @Test
  fun 迁移_21_22_保留身份映射唯一索引() {
    val h = helper()
    createRequired(h, 21)

    h.runMigrationsAndValidate(databaseName, 22, true, MIGRATION_21_22).use { db ->
      data class IndexRow(val name: String, val unique: Boolean)
      val indices = buildList {
        db.query("PRAGMA index_list(`emby_server_identities`)").use { cursor ->
          val nameIndex = cursor.getColumnIndexOrThrow("name")
          val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
          while (cursor.moveToNext()) {
            add(IndexRow(cursor.getString(nameIndex), cursor.getInt(uniqueIndex) == 1))
          }
        }
      }
      assertTrue(
        "identity sourceKey/remoteId 唯一索引必须保留，实际：$indices",
        indices.any { it.name == "index_emby_server_identities_sourceKey_remoteId" && it.unique },
      )
    }
  }

  @Test
  fun 历史相邻迁移仍可分别验证() {
    val h = helper()
    val cases = listOf(
      2 to arrayOf(M1_2),
      3 to arrayOf(MIGRATION_2_3),
      4 to arrayOf(MIGRATION_3_4),
      5 to arrayOf(MIGRATION_4_5),
      6 to arrayOf(MIGRATION_5_6),
      7 to arrayOf(MIGRATION_6_7),
      8 to arrayOf(MIGRATION_7_8),
      9 to arrayOf(MIGRATION_8_9),
      10 to arrayOf(MIGRATION_9_10),
      11 to arrayOf(MIGRATION_10_11),
      12 to arrayOf(MIGRATION_11_12),
      13 to arrayOf(MIGRATION_12_13),
      14 to arrayOf(MIGRATION_13_14),
      15 to arrayOf(MIGRATION_14_15),
      16 to arrayOf(MIGRATION_15_16),
      17 to arrayOf(MIGRATION_16_17),
      18 to arrayOf(MIGRATION_17_18),
      19 to arrayOf(MIGRATION_18_19),
      20 to arrayOf(MIGRATION_19_20),
      21 to arrayOf(MIGRATION_20_21),
      22 to arrayOf(MIGRATION_21_22),
    )
    var verified = 0
    var skipped = 0
    for ((target, migrations) in cases) {
      if (!tryCreate(h, target - 1)) {
        skipped++
        continue
      }
      h.runMigrationsAndValidate(databaseName, target, true, *migrations).close()
      verified++
    }
    assertTrue("没有任何一段迁移被验证到，测试配置有问题", verified > 0)
    android.util.Log.i("MpvExMigrationTest", "相邻验证 $verified 段，跳过 $skipped 段")
  }

  @Test
  fun 迁移_12_13_仍建出下载表及索引() {
    val h = helper()
    createIfSchemaExists(h, 12)

    h.runMigrationsAndValidate(databaseName, 13, true, MIGRATION_12_13).use { db ->
      val tables = tableNames(db)
      assertTrue("emby_download_tasks" in tables)
      assertTrue("emby_downloaded_media" in tables)
      val indices = buildSet {
        db.query("SELECT name FROM sqlite_master WHERE type='index'").use { cursor ->
          val index = cursor.getColumnIndexOrThrow("name")
          while (cursor.moveToNext()) add(cursor.getString(index))
        }
      }
      listOf(
        "index_emby_download_tasks_serverId",
        "index_emby_download_tasks_status",
        "index_emby_download_tasks_createdAt",
        "index_emby_downloaded_media_serverId",
        "index_emby_downloaded_media_seriesName",
        "index_emby_downloaded_media_completedAt",
      ).forEach { assertTrue("缺历史索引 $it，实际：$indices", it in indices) }
    }
  }

  @Test
  fun 迁移_12_13_仍不改历史既有表() {
    val h = helper()
    createIfSchemaExists(h, 12)

    h.runMigrationsAndValidate(databaseName, 13, true, MIGRATION_12_13).use { db ->
      val tables = tableNames(db)
      listOf(
        "emby_servers",
        "PlaylistEntity",
        "PlaybackStateEntity",
        "search_history",
      ).forEach { table ->
        assertTrue("12→13 不该删除历史既有表 $table，实际：$tables", table in tables)
      }
    }
  }

  @Test
  fun 迁移_11_12_仍创建搜索历史表() {
    val h = helper()
    createIfSchemaExists(h, 11)

    h.runMigrationsAndValidate(databaseName, 12, true, MIGRATION_11_12).use { db ->
      assertTrue("search_history" in tableNames(db))
    }
  }

  @Test
  fun 迁移_9_10_仍创建旧服务器表() {
    val h = helper()
    createIfSchemaExists(h, 9)

    h.runMigrationsAndValidate(databaseName, 10, true, MIGRATION_9_10).use { db ->
      assertTrue("emby_servers" in tableNames(db))
    }
  }

  @Test
  fun 迁移_10_11_12_仍保持历史语义() {
    val h = helper()
    createIfSchemaExists(h, 10)

    h.runMigrationsAndValidate(
      databaseName,
      12,
      true,
      MIGRATION_10_11,
      MIGRATION_11_12,
    ).use { db ->
      val columns = columnNames(db, "PlaybackStateEntity")
      listOf("mediaTitle", "secondarySid", "externalSubtitles", "hasBeenWatched").forEach {
        assertTrue("v10 的列 $it 不该在历史迁移后消失", it in columns)
      }
    }
  }
}
