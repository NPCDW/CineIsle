package app.marlboroadvance.mpvex.domain.postgres

import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.postgresql.Driver

class JdbcPostgresConnectionCheckerTest {
  private val config = PostgresConfig("db.example", 5432, "db/+?&=", "u", "+p?&=secret")

  @Test fun `URL cannot inject properties and actual driver parser roundtrips database`() {
    val url = JdbcPostgresConnectionChecker.jdbcUrl(config)
    assertFalse(url.contains('?'))
    val parsed = Driver.parseURL(url, JdbcPostgresConnectionChecker.connectionProperties(config))!!
    assertEquals(config.database, parsed.getProperty("PGDBNAME"))
    assertEquals(config.host, parsed.getProperty("PGHOST"))
    assertEquals(config.password, parsed.getProperty("password"))
    assertEquals("jdbc:postgresql://[::1]:5432/db", JdbcPostgresConnectionChecker.jdbcUrl(config.copy(host = "::1", database = "db")))
    assertThrows(PostgresException::class.java) { JdbcPostgresConnectionChecker.jdbcUrl(config.copy(host = "h?sslfactory=evil")) }
  }

  @Test fun `TLS trust verifier and finite timeouts are fixed with no downgrade`() {
    val full = JdbcPostgresConnectionChecker.connectionProperties(config)
    assertEquals("verify-full", full.getProperty("sslmode"))
    assertEquals("org.postgresql.ssl.DefaultJavaSSLFactory", full.getProperty("sslfactory"))
    assertEquals(AndroidPostgresHostnameVerifier::class.java.name, full.getProperty("sslhostnameverifier"))
    assertEquals("disable", full.getProperty("gssEncMode"))
    assertFalse(full.getProperty("requireAuth").contains("gss"))
    listOf("connectTimeout", "loginTimeout", "socketTimeout", "sslResponseTimeout", "cancelSignalTimeout").forEach {
      assertTrue(full.getProperty(it).toInt() > 0)
    }
    val require = JdbcPostgresConnectionChecker.connectionProperties(config.copy(sslMode = PostgresSslMode.REQUIRE))
    assertEquals("require", require.getProperty("sslmode"))
    assertEquals("org.postgresql.ssl.NonValidatingFactory", require.getProperty("sslfactory"))
    val disable = JdbcPostgresConnectionChecker.connectionProperties(config.copy(sslMode = PostgresSslMode.DISABLE))
    assertEquals("disable", disable.getProperty("sslmode"))
    assertNull(disable.getProperty("sslfactory"))
  }

  @Test fun `SELECT 1 actually runs and every JDBC resource closes`() = runBlocking {
    val fixture = JdbcFixture()
    JdbcPostgresConnectionChecker { _, _ -> fixture.connection }.check(config)
    assertEquals("SELECT 1", fixture.sql)
    assertEquals(5, fixture.queryTimeout)
    assertTrue(fixture.connectionClosed.get())
    assertTrue(fixture.statementClosed)
    assertTrue(fixture.resultClosed)
  }

  @Test fun `failed query closes resources and does not retain unsafe driver error`() = runBlocking {
    val fixture = JdbcFixture(failQuery = true)
    try {
      JdbcPostgresConnectionChecker { _, _ -> fixture.connection }.check(config)
      fail("Expected failure")
    } catch (e: PostgresException) {
      assertEquals(PostgresFailure.SCHEMA_INCOMPATIBLE, e.reason)
      assertNull(e.cause)
      assertFalse(e.message.orEmpty().contains("secret"))
    }
    assertTrue(fixture.connectionClosed.get())
    assertTrue(fixture.statementClosed)
  }

  @Test fun `cancellation returns promptly and closes late connections`() = runBlocking {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val fixture = JdbcFixture()
    val checker = JdbcPostgresConnectionChecker { _, _ ->
      started.countDown()
      while (true) {
        try { release.await(); break } catch (_: InterruptedException) { /* simulate noninterruptible connect */ }
      }
      fixture.connection
    }
    val job = launch(Dispatchers.Default) { checker.check(config) }
    assertTrue(started.await(2, TimeUnit.SECONDS))
    val start = System.nanoTime()
    job.cancelAndJoin()
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    release.countDown()
    assertTrue(fixture.closed.await(2, TimeUnit.SECONDS))
  }

  @Test fun `active connection closes in background without delaying cancellation`() = runBlocking {
    val queryStarted = CountDownLatch(1)
    val releaseQuery = CountDownLatch(1)
    val closeStarted = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val fixture = JdbcFixture(
      onQuery = {
        queryStarted.countDown()
        while (true) {
          try { releaseQuery.await(); break } catch (_: InterruptedException) { /* noninterruptible IO */ }
        }
      },
      onClose = {
        assertEquals("postgres-cleanup", Thread.currentThread().name)
        closeStarted.countDown()
        releaseClose.await()
      },
    )
    val job = launch(Dispatchers.Default) {
      JdbcPostgresConnectionChecker { _, _ -> fixture.connection }.check(config)
    }
    try {
      assertTrue(queryStarted.await(2, TimeUnit.SECONDS))
      val start = System.nanoTime()
      job.cancelAndJoin()
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
      assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
      assertFalse(fixture.connectionClosed.get()) // close is blocked, cancellation already completed.
    } finally {
      releaseClose.countDown()
      // Wait for background close before letting the query worker perform its normal use{} close.
      fixture.closed.await(2, TimeUnit.SECONDS)
      releaseQuery.countDown()
      job.cancelAndJoin()
    }
  }

  @Test fun `connection cancellation is not reported as connection failure`() = runBlocking {
    try {
      JdbcPostgresConnectionChecker { _, _ -> throw CancellationException("cancelled") }.check(config)
      fail("Expected cancellation")
    } catch (_: CancellationException) { }
  }

  @Test fun `failure categories do not expose server text`() {
    assertEquals(PostgresFailure.TIMEOUT, JdbcPostgresConnectionChecker.classifyFailure(SQLException("secret", "08006", SocketTimeoutException())))
    assertEquals(PostgresFailure.TLS, JdbcPostgresConnectionChecker.classifyFailure(SQLException("secret", "08006", SSLHandshakeException("secret"))))
    assertEquals(PostgresFailure.TLS, JdbcPostgresConnectionChecker.classifyFailure(SQLException("hostnameverifier secret", "08006")))
    assertEquals(PostgresFailure.AUTHENTICATION, JdbcPostgresConnectionChecker.classifyFailure(SQLException("secret", "28P01")))
    assertEquals(PostgresFailure.DATABASE, JdbcPostgresConnectionChecker.classifyFailure(SQLException("secret", "3D000")))
    assertEquals(PostgresFailure.CONNECTION, JdbcPostgresConnectionChecker.classifyFailure(SQLException("secret", "08001")))
    assertEquals(PostgresFailure.UNKNOWN, JdbcPostgresConnectionChecker.classifyFailure(IllegalStateException("secret")))
  }

  private class JdbcFixture(
    private val failQuery: Boolean = false,
    private val onQuery: () -> Unit = {},
    private val onClose: () -> Unit = {},
  ) {
    val closed = CountDownLatch(1)
    private val closeInvoked = AtomicBoolean(false)
    val connectionClosed = AtomicBoolean(false)
    var statementClosed = false
    var resultClosed = false
    var sql: String? = null
    var queryTimeout = 0
    private val result = proxy(ResultSet::class.java) { name, _ -> when (name) {
      "next" -> true
      "getInt" -> 1
      "close" -> { resultClosed = true; null }
      else -> null
    } }
    private val statement = proxy(Statement::class.java) { name, args -> when (name) {
      "setQueryTimeout" -> { queryTimeout = args!![0] as Int; null }
      "executeQuery" -> {
        sql = args!![0] as String
        onQuery()
        if (failQuery) throw SQLException("private-password secret", "42P01")
        result
      }
      "close" -> { statementClosed = true; null }
      else -> null
    } }
    val connection = proxy(Connection::class.java) { name, _ -> when (name) {
      "createStatement" -> statement
      "close" -> {
        if (closeInvoked.compareAndSet(false, true)) {
          onClose()
          connectionClosed.set(true)
          closed.countDown()
        }
        null
      }
      "abort" -> throw AssertionError("Android Connection must be closed without abort")
      else -> null
    } }
  }

  companion object {
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
      Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) },
    )
  }
}
