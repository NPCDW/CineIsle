package app.marlboroadvance.mpvex.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds identity mapping only. Keeps the historical emby_servers and every other table untouched. */
val MIGRATION_20_21: Migration = object : Migration(20, 21) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `emby_server_identities` (`localId` INTEGER NOT NULL, `sourceKey` TEXT NOT NULL, `remoteId` TEXT NOT NULL, PRIMARY KEY(`localId`))")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_emby_server_identities_sourceKey_remoteId` ON `emby_server_identities` (`sourceKey`, `remoteId`)")
  }
}
