package app.marlboroadvance.mpvex.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.marlboroadvance.mpvex.database.dao.EmbyServerIdentityDao
import app.marlboroadvance.mpvex.database.dao.PlaybackStateDao
import app.marlboroadvance.mpvex.database.dao.SearchHistoryDao
import app.marlboroadvance.mpvex.database.entities.EmbyServerIdentityEntity
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity

@Database(
  entities = [
    PlaybackStateEntity::class,
    EmbyServerIdentityEntity::class,
    SearchHistoryEntity::class,
  ],
  version = 22,
  exportSchema = true,
)
abstract class MpvExDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun embyServerIdentityDao(): EmbyServerIdentityDao

  abstract fun searchHistoryDao(): SearchHistoryDao
}
