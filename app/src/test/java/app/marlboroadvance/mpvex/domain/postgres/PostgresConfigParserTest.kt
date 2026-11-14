package app.marlboroadvance.mpvex.domain.postgres

import org.junit.Assert.*
import org.junit.Test

class PostgresConfigParserTest {
  private val expected = PostgresConfig("db.example", 5432, "media", "alice", "p+a:ss/@?&=#")

  @Test fun `URI decoding preserves literal plus and escaped separators`() {
    assertEquals(expected, PostgresConfigParser.parse("postgres://alice:p+a%3Ass%2F%40%3F%26%3D%23@db.example/media"))
    assertEquals(expected, PostgresConfigParser.parse("postgresql://alice:p%2Ba%3Ass%2F%40%3F%26%3D%23@db.example/media"))
  }

  @Test fun `JDBC query credentials decode Unicode and plus without form semantics`() {
    assertEquals(PostgresConfig("db.example", 6543, "媒体 +", "alice", "a+b c"),
      PostgresConfigParser.parse("jdbc:postgresql://db.example:6543/%E5%AA%92%E4%BD%93%20%2B?user=alice&password=a+b%20c"))
  }

  @Test fun `IPv6 port and SSL mode`() {
    assertEquals(PostgresConfig("2001:db8::1", 65535, "db", "u", "+", PostgresSslMode.REQUIRE),
      PostgresConfigParser.parse("postgresql://u:+@[2001:db8::1]:65535/db?sslmode=require"))
    assertEquals("2001:db8::1", PostgresConfigParser.validate(PostgresConfig("[2001:db8::1]", database = "d", username = "u")).host)
  }

  @Test fun `Npgsql quoted separators equals and doubled quotes`() {
    assertEquals(PostgresConfig("db.example", 5432, "media", "alice", " x;'y\"=+ ", PostgresSslMode.VERIFY_FULL),
      PostgresConfigParser.parse("Host=db.example;Database='media';Username=\"alice\";Password=' x;''y\"=+ ';SSL Mode=VerifyFull;"))
    assertEquals(expected, PostgresConfigParser.parse("Server=db.example;Initial Catalog=media;User ID=alice;Pwd='p+a:ss/@?&=#'"))
  }

  @Test fun `dotenv supports export quotes comments and preserves password spaces`() {
    val env = """
      # pasted config
      export PGHOST=db.example
      PGPORT=5432 # default
      PGDATABASE='media'
      PGUSER="alice"
      PGPASSWORD='p+a:ss/@?&=#'
      PGSSLMODE=verify-full
    """.trimIndent()
    assertEquals(expected, PostgresConfigParser.parse(env))
    assertEquals(" +#= ", PostgresConfigParser.parse("PGHOST=db.example\nPGDATABASE=media\nPGUSER=alice\nPGPASSWORD=' +#= '").password)
  }

  @Test fun `IDN normalized with no DNS access and optional empty password`() {
    assertEquals("xn--bcher-kva.example", PostgresConfigParser.parse("Host=bücher.example;Database=db;Username=u").host)
    assertEquals("", PostgresConfigParser.parse("postgres://u@localhost/db").password)
  }

  @Test fun `reject unsupported dangerous unknown duplicate and multi-host settings`() {
    val inputs = listOf(
      "postgres://u:p@a,b/db", "jdbc:postgresql://a:5432,b:5432/db?user=u",
      "Host=a,b;Database=d;Username=u", "PGHOST=a,b\nPGDATABASE=d\nPGUSER=u",
      "postgres://u:p@h/db?sslfactory=evil", "postgres://u:p@h/db?socketFactory=evil",
      "postgres://u:p@h/db?options=x", "postgres://u:p@h/db?sslmode=prefer",
      "postgres://u:p@h/db?sslmode=verify-ca", "postgres://u:p@h/db?user=v",
      "Host=h;Database=d;Username=u;Trust Server Certificate=true",
      "Host=h;Database=d;Username=u;Timeout=1", "Host=h;Host=h;Database=d;Username=u",
      "PGHOST=h\nPGDATABASE=d\nPGUSER=u\nPGOPTIONS=evil",
      "Host=h;Database=d;Username=u;Password='unclosed", "Host=h;;Database=d;Username=u",
    )
    inputs.forEach(::invalid)
  }

  @Test fun `reject missing malformed and out of range data without including input in errors`() {
    listOf("", "secret", "postgres://u:secret@h/", "postgres://u:secret@h/db#secret",
      "postgres://u:%ZZ@h/db", "postgres://u:%FF@h/db", "postgres://u:p@h:0/db",
      "postgres://u:p@h:65536/db", "postgres://u:p@h:-1/db", "postgres://u:p@h:/db",
      "postgres://u:p@h:999999999999999/db", "postgres://u:p@[bad::ip]/db",
      "postgres://u:p@h%3Fsslmode=disable/db", "Host=h/path;Database=d;Username=u",
      "Host=h;Database=d", "Host=h;Database=d;Username=u;Port=+5432",
      "jdbc:postgresql:db?user=u").forEach(::invalid)
    val error = assertThrows(PostgresException::class.java) { PostgresConfigParser.validate(expected.copy(password = "secret\u0000")) }
    assertNull(error.cause)
    assertFalse(error.message.orEmpty().contains("secret"))
  }

  @Test fun `diagnostics do not expose credentials or private host`() {
    val diagnostics = expected.toString()
    listOf(expected.host, expected.database, expected.username, expected.password).forEach {
      assertFalse(diagnostics.contains(it))
    }
  }

  private fun invalid(input: String) {
    val error = assertThrows(PostgresException::class.java) { PostgresConfigParser.parse(input) }
    assertEquals(PostgresFailure.INVALID_CONFIG, error.reason)
    assertNull(error.cause)
  }
}
