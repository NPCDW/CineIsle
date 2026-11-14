package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.emby.EmbyEndpoint
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyServerLine
import org.junit.Assert.*
import org.junit.Test

/** Read-only projections used by both server lists. No CRUD gate or credentials are written. */
class EmbyServerManagementTest {
  private fun server() = EmbyServer(
    id = 1L shl 48, name = "Test", host = "example.test", username = "user", password = "",
    configuredBaseUrl = "https://example.test/emby", lineId = "line",
    lines = listOf(EmbyServerLine("line", "Desktop line", "https://example.test/emby")),
  )

  @Test fun selectedLineNameIsReadFromSharedSelection() {
    assertEquals("Desktop line", server().currentLineName)
    assertNull(server().connectionError)
  }

  @Test fun selectedLineDoesNotOverrideAppliedServerRoute() {
    val row = server()
    assertEquals("https://example.test/emby", row.effectiveBaseUrl)
    assertNull(row.connectionError)
  }

  @Test fun reverseProxyNameAndEffectiveUrlAreReadOnlyProjections() {
    val row = server().copy(reverseProxyId = "reverse", reverseProxyName = "Reverse line", reverseProxyUrl = "https://reverse.test/relay")
    assertEquals("Reverse line", row.reverseProxyName)
    assertEquals("https://reverse.test/relay/https://example.test/emby", row.effectiveBaseUrl)
    assertEquals("https://example.test/emby", row.configuredBaseUrl)
  }

  @Test fun missingLineAndMissingReverseAreExplicitErrors() {
    assertNotNull(server().copy(lineId = "missing").connectionError)
    assertNotNull(server().copy(reverseProxyId = "missing").connectionError)
  }

  @Test fun invalidBottomRowsStayVisibleWithoutEvaluatingThrowingEffectiveUrl() {
    val valid = server().copy(remoteId = "valid", sourceKey = "source")
    val bottomRows = listOf(
      valid.copy(remoteId = "disabled", disabled = true, configuredBaseUrl = "not a URI"),
      valid.copy(remoteId = "missing-line", lineId = "missing"),
      valid.copy(remoteId = "missing-reverse", reverseProxyId = "missing"),
      valid.copy(remoteId = "bad-url", configuredBaseUrl = "https://user:secret@host.test?token=secret"),
      valid.copy(remoteId = "bad-reverse", reverseProxyId = "reverse", reverseProxyUrl = "not a URL"),
    )
    bottomRows.forEach { assertThrows(Exception::class.java) { it.effectiveBaseUrl } }
    val rows = projectEmbyServerRows(EmbyServerLoadState.Ready(listOf(valid) + bottomRows))
    assertEquals(6, rows.size)
    assertTrue(rows.first().canSelect)
    rows.drop(1).forEach {
      assertFalse(it.canSelect)
      assertNotNull(it.connectionError)
      assertEquals("", it.effectiveBaseUrl)
    }
    assertEquals(listOf("valid", "disabled", "missing-line", "missing-reverse", "bad-url", "bad-reverse"), rows.map { it.remoteId })
  }

  @Test fun failedRefreshKeepsRowsVisibleButUnselectable() {
    val original = server().copy(sourceKey = "source", remoteId = "remote", iconUrl = "data:image/png;base64,abc")
    val rows = projectEmbyServerRows(EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(original)))
    assertFalse(rows.single().canSelect)
    assertEquals(original.iconUrl, rows.single().iconUrl)
    assertEquals("source/remote", rows.single().key)
    assertFalse(rows.single().toString().contains(original.username))
    assertFalse(rows.single().toString().contains("password"))
  }

  @Test fun usableRowsHaveNoCurrentSessionOrCredentialProjection() {
    val original = server().copy(sourceKey = "source", remoteId = "remote")
    val row = projectEmbyServerRows(EmbyServerLoadState.Ready(listOf(original))).single()
    val authenticated = original.copy(userId = "session-user", apiToken = "private-token", lastConnected = 42)
    assertEquals(row, projectEmbyServerRows(EmbyServerLoadState.Ready(listOf(authenticated))).single())
    assertTrue(row.canSelect)
    assertNull(row.connectionError)
    assertEquals(original.name, row.name)
    assertEquals(original.currentLineName, row.currentLineName)
    assertEquals(original.effectiveBaseUrl, row.effectiveBaseUrl)
    assertFalse(EmbyServerRow::class.java.declaredFields.any { it.name in setOf("selected", "currentServer") })
    assertFalse(row.toString().contains("selected"))
    assertFalse(row.toString().contains(authenticated.apiToken))
  }

  @Test fun loadingHasNoRowsAndReadyRefreshRestoresUsability() {
    val original = server()
    assertTrue(projectEmbyServerRows(EmbyServerLoadState.Loading).isEmpty())
    val retained = projectEmbyServerRows(EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(original))).single()
    assertFalse(retained.disabled)
    assertNull(retained.connectionError)
    assertFalse(retained.canSelect)
    val ready = projectEmbyServerRows(EmbyServerLoadState.Ready(listOf(original))).single()
    assertEquals(retained.copy(canSelect = true), ready)
  }

  @Test fun displayUrlStripsCredentialsQueryAndFragmentEvenOnInvalidReverseRow() {
    assertEquals("https://host.test/relay", safeEmbyDisplayUrl("https://user:secret@host.test/relay?token=private#private"))
    assertEquals("", safeEmbyDisplayUrl("not a URI containing private"))
  }

  @Test fun selectionRequiresNoConnectionError() {
    assertTrue(canSelectEmbyServer(server()))
    listOf(server().copy(disabled = true), server().copy(lineId = "missing"),
      server().copy(reverseProxyId = "missing"), server().copy(configuredBaseUrl = "invalid"))
      .forEach { assertFalse(canSelectEmbyServer(it)) }
  }

  @Test fun fullUrlPreservesPrefixAndIpv6() {
    val endpoint = EmbyEndpoint.fromUrl("http://[2001:db8::1]:8096/prefix/emby")
    assertEquals("http://[2001:db8::1]:8096/prefix/emby", endpoint.baseUrl)
    assertEquals(8096, endpoint.port)
  }
}
