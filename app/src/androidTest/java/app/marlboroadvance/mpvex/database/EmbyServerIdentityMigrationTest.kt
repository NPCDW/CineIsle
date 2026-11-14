package app.marlboroadvance.mpvex.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marlboroadvance.mpvex.database.migrations.MIGRATION_20_21
import app.marlboroadvance.mpvex.database.migrations.MIGRATION_21_22
import app.marlboroadvance.mpvex.database.migrations.ROOM_V22_DROPPED_TABLES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbyServerIdentityMigrationTest {
  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MpvExDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
  )

  @Test
  fun migrates20To22DropsLegacyRowsAndPreservesIdentityAndSearchData() {
    val name = "emby-identity-migration-test.db"
    helper.createDatabase(name, 20).use { db ->
      db.execSQL("INSERT INTO emby_servers (id, name, host, port, useHttps, username, password, userId, apiToken, serverName, version, createdAt, lastConnected) VALUES (1, 'legacy', 'legacy.invalid', 8096, 0, 'old-user', 'old-password', '', '', '', '', 1, 0)")
      db.execSQL("INSERT INTO search_history (serverId, keyword, lastUsedAt, useCount) VALUES (1, 'preserve-local-data', 1, 1)")
    }

    helper.runMigrationsAndValidate(
      name,
      22,
      true,
      MIGRATION_20_21,
      MIGRATION_21_22,
    ).use { db ->
      db.query("SELECT keyword FROM search_history WHERE serverId = 1").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("preserve-local-data", cursor.getString(0))
      }
      db.query("SELECT * FROM emby_server_identities").use { cursor -> assertFalse(cursor.moveToFirst()) }

      val firstId = 1L shl 48
      db.execSQL(
        "INSERT INTO emby_server_identities(localId, sourceKey, remoteId) VALUES (?, ?, ?)",
        arrayOf<Any>(firstId, "source-a", "remote-a"),
      )
      db.execSQL(
        "INSERT INTO emby_server_identities(localId, sourceKey, remoteId) VALUES (?, ?, ?)",
        arrayOf<Any>(firstId + 1, "source-b", "remote-a"),
      )
      db.query("SELECT COUNT(*) FROM emby_server_identities").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(2, cursor.getInt(0))
      }

      val tables = buildSet {
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
          val index = cursor.getColumnIndexOrThrow("name")
          while (cursor.moveToNext()) add(cursor.getString(index))
        }
      }
      ROOM_V22_DROPPED_TABLES.forEach { table ->
        assertFalse("v22 must drop $table", table in tables)
      }
      assertTrue("emby_server_identities" in tables)
      assertTrue("search_history" in tables)
      assertTrue("PlaybackStateEntity" in tables)
    }
  }
}
