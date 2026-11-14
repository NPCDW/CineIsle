package app.marlboroadvance.mpvex.domain.postgres

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

@Serializable
data class PostgresConfig(
  val host: String = "",
  val port: Int = 5432,
  val database: String = "",
  val username: String = "",
  val password: String = "",
  val sslMode: PostgresSslMode = PostgresSslMode.VERIFY_FULL,
) {
  override fun toString(): String = "PostgresConfig(port=$port, sslMode=$sslMode, credentials=[redacted])"
}

@Serializable
enum class PostgresSslMode(val jdbcValue: String) {
  VERIFY_FULL("verify-full"),
  REQUIRE("require"),
  DISABLE("disable"),
}

enum class PostgresFailure {
  INVALID_CONFIG, CONNECTION, TIMEOUT, AUTHENTICATION, DATABASE, TLS, STORAGE, LOAD, UNKNOWN,
  SCHEMA_INCOMPATIBLE, SOURCE_CHANGED, NOT_FOUND, COMMIT_UNKNOWN,
}

/** Only fixed messages: never include a pasted URL, hostname, credentials or driver message. */
class PostgresException(val reason: PostgresFailure, cause: Throwable? = null) :
  Exception(
    when (reason) {
      PostgresFailure.INVALID_CONFIG -> "Invalid PostgreSQL configuration"
      PostgresFailure.CONNECTION -> "PostgreSQL connection failed"
      PostgresFailure.TIMEOUT -> "PostgreSQL operation timed out"
      PostgresFailure.AUTHENTICATION -> "PostgreSQL authentication failed"
      PostgresFailure.DATABASE -> "PostgreSQL database is unavailable"
      PostgresFailure.TLS -> "PostgreSQL TLS verification failed"
      PostgresFailure.STORAGE -> "PostgreSQL configuration could not be saved"
      PostgresFailure.LOAD -> "PostgreSQL configuration could not be loaded"
      PostgresFailure.UNKNOWN -> "PostgreSQL operation failed"
      PostgresFailure.SCHEMA_INCOMPATIBLE -> "PostgreSQL schema is incompatible"
      PostgresFailure.SOURCE_CHANGED -> "PostgreSQL configuration source changed"
      PostgresFailure.NOT_FOUND -> "Shared Emby configuration no longer exists"
      PostgresFailure.COMMIT_UNKNOWN -> "PostgreSQL write outcome is unknown; refresh before retrying"
    },
    cause,
  )

data class PostgresSourceState(val revision: Long = 0, val sourceKey: String = "")

private val unchangingSource = MutableStateFlow(PostgresSourceState())

/** Stable source fingerprint: deliberately excludes the password. No plaintext endpoint in local identities. */
fun PostgresConfig.sourceKey(): String {
  val validated = PostgresConfigParser.validate(this)
  val fields = listOf(validated.host.lowercase(), validated.port.toString(), validated.database, validated.username, validated.sslMode.jdbcValue)
  val input = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
  return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

interface PostgresConfigStorage {
  /** Save revision changes even for password-only edits, invalidating in-flight operations. */
  val sourceChanges: StateFlow<PostgresSourceState> get() = unchangingSource
  suspend fun load(): PostgresConfig?
  suspend fun save(config: PostgresConfig)
}

fun interface PostgresConnectionChecker {
  suspend fun check(config: PostgresConfig)
}
