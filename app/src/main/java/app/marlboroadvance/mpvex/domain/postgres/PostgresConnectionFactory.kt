package app.marlboroadvance.mpvex.domain.postgres

import java.net.URLEncoder
import java.sql.Connection
import java.util.Properties
import org.postgresql.Driver

/** Fixed allowlisted pgjdbc properties shared by startup checking and business SQL. */
class PostgresConnectionFactory internal constructor(private val connect: (String, Properties) -> Connection?) {
  constructor() : this({ url, properties -> Driver().connect(url, properties) })

  fun open(config: PostgresConfig): Connection = connect(jdbcUrl(config), connectionProperties(config))
    ?: throw PostgresException(PostgresFailure.CONNECTION)

  /** Only the typed history cycle writer uses this path; all ordinary opens remain always read-only. */
  internal fun openHistoryMark(config: PostgresConfig): Connection = connect(jdbcUrl(config), connectionProperties(config).apply {
    setProperty("readOnly", "false")
  }) ?: throw PostgresException(PostgresFailure.CONNECTION)

  /** Only the typed automatic playback writer uses this additional narrowly scoped opening. */
  internal fun openAutomaticPlayback(config: PostgresConfig): Connection = connect(jdbcUrl(config), connectionProperties(config).apply {
    setProperty("readOnly", "false")
  }) ?: throw PostgresException(PostgresFailure.CONNECTION)

  companion object {
    fun jdbcUrl(config: PostgresConfig): String {
      val validated = PostgresConfigParser.validate(config)
      val host = if (':' in validated.host) "[${validated.host}]" else validated.host
      val database = URLEncoder.encode(validated.database, "UTF-8").replace("+", "%20")
      return "jdbc:postgresql://$host:${validated.port}/$database"
    }

    fun connectionProperties(config: PostgresConfig): Properties = Properties().apply {
      setProperty("user", config.username)
      setProperty("password", config.password)
      setProperty("sslmode", config.sslMode.jdbcValue)
      setProperty("sslNegotiation", "postgres")
      if (config.sslMode != PostgresSslMode.DISABLE) {
        setProperty("sslfactory", if (config.sslMode == PostgresSslMode.VERIFY_FULL) "org.postgresql.ssl.DefaultJavaSSLFactory" else "org.postgresql.ssl.NonValidatingFactory")
        setProperty("sslhostnameverifier", AndroidPostgresHostnameVerifier::class.java.name)
      }
      setProperty("gssEncMode", "disable")
      setProperty("requireAuth", "none,password,md5,scram-sha-256")
      setProperty("channelBinding", "prefer")
      setProperty("scramMaxIterations", "100000")
      setProperty("connectTimeout", "10")
      setProperty("loginTimeout", "10")
      setProperty("socketTimeout", "10")
      setProperty("sslResponseTimeout", "5000")
      setProperty("cancelSignalTimeout", "2")
      setProperty("ApplicationName", "mpvEx")
      setProperty("readOnly", "true")
      setProperty("readOnlyMode", "always")
    }
  }
}
