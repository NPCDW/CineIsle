package app.marlboroadvance.mpvex.domain.postgres

/** Device-local identity only. Implementations must never delete or reuse allocated identities. */
interface EmbyServerIdentityMap {
  suspend fun localId(sourceKey: String, remoteId: String): Long
}

const val EMBY_IDENTITY_MIN: Long = 1L shl 48
const val EMBY_IDENTITY_LIMIT: Long = 1L shl 49
