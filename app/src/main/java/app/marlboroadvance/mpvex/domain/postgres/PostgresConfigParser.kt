package app.marlboroadvance.mpvex.domain.postgres

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Strict, offline parser. Unsupported options are errors, not hidden driver configuration. */
object PostgresConfigParser {
  private const val MAX_TEXT_LENGTH = 64 * 1024

  fun parse(text: String): PostgresConfig = safely {
    require(text.length <= MAX_TEXT_LENGTH)
    val source = text.trim().removePrefix("\uFEFF").trim()
    require(source.isNotEmpty())
    when {
      source.startsWith("postgres://", true) || source.startsWith("postgresql://", true) -> parseUri(source)
      source.startsWith("jdbc:postgresql://", true) -> parseUri(source.substring(5))
      source.startsWith("jdbc:", true) -> invalid()
      source.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
        ?.trimStart()?.let { it.startsWith("PG", true) || it.startsWith("export ") } == true -> parseEnv(source)
      else -> parseKeyValues(source)
    }.let(::validate)
  }

  fun validate(config: PostgresConfig): PostgresConfig = safely {
    require(config.port in 1..65535)
    require(config.database.isNotBlank() && config.username.isNotBlank())
    require(config.database.length <= MAX_TEXT_LENGTH && config.username.length <= MAX_TEXT_LENGTH)
    require(config.password.length <= MAX_TEXT_LENGTH)
    require(listOf(config.database, config.username, config.password).none { '\u0000' in it })
    config.copy(host = normalizeHost(config.host))
  }

  private fun parseUri(source: String): PostgresConfig {
    val uri = URI(source)
    require(uri.scheme.equals("postgres", true) || uri.scheme.equals("postgresql", true))
    require(uri.rawFragment == null)
    val authority = requireNotNull(uri.rawAuthority)
    require(authority.isNotEmpty())
    val values = linkedMapOf<String, String>()
    val at = authority.lastIndexOf('@')
    val address = if (at >= 0) {
      require(authority.indexOf('@') == at)
      val credentials = authority.substring(0, at)
      val colon = credentials.indexOf(':')
      put(values, "username", decode(if (colon < 0) credentials else credentials.substring(0, colon)))
      if (colon >= 0) put(values, "password", decode(credentials.substring(colon + 1)))
      authority.substring(at + 1)
    } else authority
    val host: String
    val port: String?
    if (address.startsWith('[')) {
      val end = address.indexOf(']')
      require(end > 1)
      host = decode(address.substring(1, end))
      val rest = address.substring(end + 1)
      require(rest.isEmpty() || rest.startsWith(':'))
      port = if (rest.isEmpty()) null else rest.substring(1)
    } else {
      require(address.count { it == ':' } <= 1)
      val colon = address.indexOf(':')
      host = decode(if (colon < 0) address else address.substring(0, colon))
      port = if (colon < 0) null else address.substring(colon + 1)
    }
    put(values, "host", host)
    if (port != null) put(values, "port", port)
    val path = uri.rawPath.orEmpty()
    require(path.startsWith('/') && path.length > 1 && '/' !in path.substring(1))
    put(values, "database", decode(path.substring(1)))
    uri.rawQuery?.let { query ->
      require(query.isNotEmpty())
      query.split('&').forEach { item ->
        val equals = item.indexOf('=')
        require(equals > 0)
        val key = decode(item.substring(0, equals)).lowercase(Locale.ROOT)
        val canonical = when (key) {
          "user", "username" -> "username"
          "password" -> "password"
          "sslmode" -> "sslmode"
          else -> invalid()
        }
        put(values, canonical, decode(item.substring(equals + 1)))
      }
    }
    return fromValues(values)
  }

  private fun parseEnv(source: String): PostgresConfig {
    val values = linkedMapOf<String, String>()
    source.lineSequence().forEach { rawLine ->
      val line = rawLine.trim().removePrefix("export ").trimStart()
      if (line.isEmpty() || line.startsWith('#')) return@forEach
      val equals = line.indexOf('=')
      require(equals > 0)
      val key = when (line.substring(0, equals).trim().uppercase(Locale.ROOT)) {
        "PGHOST" -> "host"
        "PGPORT" -> "port"
        "PGDATABASE" -> "database"
        "PGUSER" -> "username"
        "PGPASSWORD" -> "password"
        "PGSSLMODE" -> "sslmode"
        else -> invalid()
      }
      put(values, key, readValue(line.substring(equals + 1), env = true))
    }
    return fromValues(values)
  }

  private fun parseKeyValues(source: String): PostgresConfig {
    val values = linkedMapOf<String, String>()
    var start = 0
    var quote: Char? = null
    var i = 0
    while (i <= source.length) {
      if (i == source.length || (source[i] == ';' && quote == null)) {
        val part = source.substring(start, i).trim()
        if (part.isNotEmpty()) {
          val equals = part.indexOf('=')
          require(equals > 0)
          val key = when (part.substring(0, equals).trim().lowercase(Locale.ROOT).replace(" ", "")) {
            "host", "server" -> "host"
            "port" -> "port"
            "database", "initialcatalog" -> "database"
            "username", "userid", "user" -> "username"
            "password", "pwd" -> "password"
            "sslmode" -> "sslmode"
            else -> invalid()
          }
          put(values, key, readValue(part.substring(equals + 1), env = false))
        } else require(i == source.length) // Permit one trailing separator only.
        start = i + 1
      } else if (source[i] == '\'' || source[i] == '"') {
        val ch = source[i]
        when {
          quote == null -> quote = ch
          quote == ch && i + 1 < source.length && source[i + 1] == ch -> i++
          quote == ch -> quote = null
        }
      }
      i++
    }
    require(quote == null)
    return fromValues(values)
  }

  private fun readValue(raw: String, env: Boolean): String {
    val source = raw.trimStart()
    if (!source.startsWith('\'') && !source.startsWith('"')) {
      require('\'' !in source && '"' !in source)
      val comment = if (env) source.indices.firstOrNull { source[it] == '#' && (it == 0 || source[it - 1].isWhitespace()) } else null
      return (if (comment == null) source else source.substring(0, comment)).trimEnd()
    }
    val quote = source[0]
    val result = StringBuilder()
    var i = 1
    while (i < source.length) {
      val ch = source[i++]
      if (ch == quote) {
        if (!env && i < source.length && source[i] == quote) {
          result.append(quote)
          i++
          continue
        }
        val rest = source.substring(i).trim()
        require(rest.isEmpty() || (env && rest.startsWith('#')))
        return result.toString()
      }
      if (env && quote == '"' && ch == '\\') {
        require(i < source.length)
        result.append(when (val escaped = source[i++]) {
          'n' -> '\n'
          'r' -> '\r'
          't' -> '\t'
          '\\', '"', '$' -> escaped
          else -> invalid()
        })
      } else result.append(ch)
    }
    invalid()
  }

  private fun fromValues(values: Map<String, String>): PostgresConfig = PostgresConfig(
    host = values["host"].orEmpty(),
    port = values["port"]?.let {
      require(it.isNotEmpty() && it.all { ch -> ch in '0'..'9' })
      it.toInt()
    } ?: 5432,
    database = values["database"].orEmpty(),
    username = values["username"].orEmpty(),
    password = values["password"].orEmpty(),
    sslMode = values["sslmode"]?.let {
      when (it.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "")) {
        "verifyfull" -> PostgresSslMode.VERIFY_FULL
        "require" -> PostgresSslMode.REQUIRE
        "disable" -> PostgresSslMode.DISABLE
        else -> invalid() // No prefer/allow/verify-ca: never silently weaken TLS.
      }
    } ?: PostgresSslMode.VERIFY_FULL,
  )

  private fun normalizeHost(value: String): String {
    var host = value.trim()
    if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
    require(host.isNotEmpty() && host.length <= 253)
    if (':' in host) {
      // Numeric literals only: getByName cannot perform a DNS lookup here.
      require(host.all { it in "0123456789abcdefABCDEF:." })
      require(InetAddress.getByName(host) is Inet6Address)
      return host.lowercase(Locale.ROOT)
    }
    val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    require(ascii.length <= 253)
    val labels = ascii.removeSuffix(".").split('.')
    require(labels.all { it.isNotEmpty() && it.length <= 63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' } })
    return ascii
  }

  /** URI percent decoding, deliberately NOT form decoding: a literal '+' stays '+'. */
  private fun decode(value: String): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
      if (value[i] == '%') {
        require(i + 2 < value.length)
        val hi = value[i + 1].digitToIntOrNull(16) ?: invalid()
        val lo = value[i + 2].digitToIntOrNull(16) ?: invalid()
        bytes.write(hi * 16 + lo)
        i += 3
      } else {
        val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
        bytes.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
        i = end
      }
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
  }

  private fun put(values: MutableMap<String, String>, key: String, value: String) {
    require(!values.containsKey(key))
    values[key] = value
  }

  private inline fun <T> safely(block: () -> T): T = try {
    block()
  } catch (e: PostgresException) {
    throw e
  } catch (_: Exception) {
    // Parsing exceptions often embed the original input. Do not retain them as causes.
    invalid()
  }

  private fun invalid(): Nothing = throw PostgresException(PostgresFailure.INVALID_CONFIG)
}
