package app.marlboroadvance.mpvex.domain.postgres

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.Properties
import javax.net.ssl.SSLException

/** Startup checker shares fixed security properties and bounded IO with business SQL; only SELECT 1. */
class JdbcPostgresConnectionChecker internal constructor(
  private val factory: PostgresConnectionFactory,
) : PostgresConnectionChecker {
  constructor() : this(PostgresConnectionFactory())
  internal constructor(connect: (String, Properties) -> Connection?) : this(PostgresConnectionFactory(connect))

  override suspend fun check(config: PostgresConfig) {
    val validated = PostgresConfigParser.validate(config)
    JdbcPostgresIo.run { control ->
      factory.open(validated).use { active ->
        control.connection.set(active)
        control.checkCancelled()
        active.createStatement().use { statement ->
          control.statement.set(statement)
          statement.queryTimeout = QUERY_TIMEOUT_SECONDS
          statement.executeQuery("SELECT 1").use { result ->
            if (!result.next() || result.getInt(1) != 1) throw PostgresException(PostgresFailure.DATABASE)
          }
        }
      }
    }
  }

  internal companion object {
    const val TOTAL_TIMEOUT_MS = JdbcPostgresIo.TOTAL_TIMEOUT_MS
    const val QUERY_TIMEOUT_SECONDS = JdbcPostgresIo.QUERY_TIMEOUT_SECONDS
    fun jdbcUrl(config: PostgresConfig): String = PostgresConnectionFactory.jdbcUrl(config)
    fun connectionProperties(config: PostgresConfig): Properties = PostgresConnectionFactory.connectionProperties(config)

    fun classifyFailure(error: Throwable): PostgresFailure {
      val chain = generateSequence(error) { it.cause }.take(12).toList()
      if (chain.any { it is SocketTimeoutException || it is SQLTimeoutException }) return PostgresFailure.TIMEOUT
      if (chain.any { it is SSLException || it is CertificateException }) return PostgresFailure.TLS
      val sql = chain.filterIsInstance<SQLException>()
      if (sql.any { it.sqlState?.startsWith("28") == true }) return PostgresFailure.AUTHENTICATION
      if (sql.any { it.sqlState == "42P01" || it.sqlState == "42703" || it.sqlState == "42804" }) return PostgresFailure.SCHEMA_INCOMPATIBLE
      if (sql.any { it.sqlState == "3D000" || it.sqlState?.startsWith("42") == true }) return PostgresFailure.DATABASE
      if (sql.any { it.sqlState?.startsWith("08") == true &&
          (it.message?.contains("SSL", ignoreCase = true) == true || it.message?.contains("hostnameverifier", ignoreCase = true) == true) }) return PostgresFailure.TLS
      if (chain.any { it is UnknownHostException || it is ConnectException } || sql.any { it.sqlState?.startsWith("08") == true }) return PostgresFailure.CONNECTION
      return PostgresFailure.UNKNOWN
    }
  }
}
