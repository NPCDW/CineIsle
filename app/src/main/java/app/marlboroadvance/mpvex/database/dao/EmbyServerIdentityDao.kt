package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.marlboroadvance.mpvex.database.entities.EmbyServerIdentityEntity
import app.marlboroadvance.mpvex.domain.postgres.EMBY_IDENTITY_LIMIT
import app.marlboroadvance.mpvex.domain.postgres.EMBY_IDENTITY_MIN
import app.marlboroadvance.mpvex.domain.postgres.EmbyServerIdentityMap

@Dao
abstract class EmbyServerIdentityDao : EmbyServerIdentityMap {
  @Query("SELECT localId FROM emby_server_identities WHERE sourceKey = :sourceKey AND remoteId = :remoteId")
  abstract suspend fun find(sourceKey: String, remoteId: String): Long?
  @Query("SELECT MAX(localId) FROM emby_server_identities")
  abstract suspend fun maximum(): Long?
  @Insert abstract suspend fun insertIdentity(identity: EmbyServerIdentityEntity)

  @Transaction
  override suspend fun localId(sourceKey: String, remoteId: String): Long {
    require(sourceKey.isNotEmpty() && remoteId.isNotEmpty())
    find(sourceKey, remoteId)?.let { return it }
    val next = (maximum()?.plus(1) ?: EMBY_IDENTITY_MIN).coerceAtLeast(EMBY_IDENTITY_MIN)
    check(next < EMBY_IDENTITY_LIMIT) { "Emby identity range exhausted" }
    insertIdentity(EmbyServerIdentityEntity(next, sourceKey, remoteId))
    return next
  }
}
