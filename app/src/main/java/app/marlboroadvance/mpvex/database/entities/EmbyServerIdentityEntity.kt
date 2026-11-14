package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "emby_server_identities", indices = [Index(value = ["sourceKey", "remoteId"], unique = true)])
data class EmbyServerIdentityEntity(
  @PrimaryKey val localId: Long,
  val sourceKey: String,
  val remoteId: String,
)
