package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceState
import app.marlboroadvance.mpvex.ui.browser.MainScreen
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Pure memory-only navigation gates; no database, authentication, or ambient selection writes. */
class EmbyServerBindingTest {
  private val server = EmbyServer(id = 1, name = "A", host = "example.test", username = "", password = "",
    sourceKey = "source-A", remoteId = "same-remote-uuid", sourceRevision = 3)
  private val source = PostgresSourceState(3, "source-A")
  private val ref = EmbyServerRef.from(server)
  private fun resolve(state: EmbyServerLoadState, current: Boolean = true) =
    resolveEmbyServerBinding(ref, state) { current }

  @Test fun navigationReferenceSerializesOnlyLightweightSourceIdentity() {
    val sensitive = server.copy(apiToken = "DO-NOT-SAVE-TOKEN", password = "DO-NOT-SAVE-PASSWORD",
      configuredBaseUrl = "https://do-not-save.example/emby")
    val json = Json.encodeToString(EmbyServerRef.serializer(), EmbyServerRef.from(sensitive))
    assertEquals(ref, Json.decodeFromString(EmbyServerRef.serializer(), json))
    assertFalse(json.contains("DO-NOT-SAVE"))
    assertFalse(json.contains("example"))
    assertFalse(json.contains("\"id\""))
    assertEquals(setOf("sourceKey", "sourceRevision", "remoteId"), Json.parseToJsonElement(json)
      .let { it as kotlinx.serialization.json.JsonObject }.keys)
  }

  @Test fun defaultDetailConstructionCreatesStableSerializableEntryIdentity() {
    val detail = EmbyDetailScreen("episode-1", "Episode 1")
    assertTrue(detail.detailEntryId.isNotBlank())
    val json = Json.encodeToString(EmbyDetailScreen.serializer(), detail)
    val restored = Json.decodeFromString(EmbyDetailScreen.serializer(), json)
    assertEquals(detail, restored)
    assertEquals(detail.detailEntryId, restored.detailEntryId)
    assertFalse(json.contains("password"))
    assertFalse(json.contains("apiToken"))
    assertEquals(setOf("itemId", "title", "detailEntryId"), Json.parseToJsonElement(json)
      .let { it as kotlinx.serialization.json.JsonObject }.keys)
  }

  @Test fun legacyDetailPayloadWithoutEntryIdentityUsesCompatibleDefault() {
    val restored = Json.decodeFromString(EmbyDetailScreen.serializer(), """{"itemId":"episode-1","title":"Episode 1"}""")
    assertEquals("episode-1", restored.itemId)
    assertTrue(restored.detailEntryId.isNotBlank())
    val destination = Json.decodeFromString(
      EmbyServerDestination.Detail.serializer(),
      """{"itemId":"episode-1","title":"Episode 1"}""",
    )
    assertTrue(destination.detailEntryId.isNotBlank())
  }

  @Test fun gatewayPayloadRoundTripsWithoutPolymorphicScreenRegistration() {
    listOf(EmbyServerHomeScreen, EmbyDetailScreen("same-id", "Detail"),
      EmbyPersonScreen("person-id", "Person", "image-tag")).forEach { destination ->
      val gateway = EmbyServerGateway(ref, "visit", destination)
      val json = Json.encodeToString(EmbyServerGateway.serializer(), gateway)
      val restored = Json.decodeFromString(EmbyServerGateway.serializer(), json)
      assertEquals(gateway, restored)
      assertEquals(destination, restored.destination)
      assertFalse(json.contains("password"))
      assertFalse(json.contains("apiToken"))
      assertFalse(json.contains("baseUrl"))
    }
  }

  @Test fun ordinaryDetailReplaceIsAtomicStableAndDoesNotGrowStack() {
    val entry = EmbyDetailScreen("episode-1", "Episode 1", "detail-entry")
    val stack = mutableListOf<app.marlboroadvance.mpvex.presentation.Screen>(MainScreen, entry)
    val originalSize = stack.size
    val originalKey = embyNavigationContentKey(entry)

    assertTrue(replaceTopEmbyDetail(stack, "detail-entry", "episode-2", "Episode 2"))
    assertEquals(originalSize, stack.size)
    val replaced = stack.last() as EmbyDetailScreen
    assertEquals("episode-2", replaced.itemId)
    assertEquals("detail-entry", replaced.detailEntryId)
    assertEquals(originalKey, embyNavigationContentKey(replaced))
    stack.removeAt(stack.lastIndex)
    assertEquals(listOf(MainScreen), stack)
  }

  @Test fun gatewayDetailReplaceKeepsVisitServerEntryAndSerializedPayloadSafe() {
    val detail = EmbyDetailScreen("episode-1", "Episode 1", "detail-entry")
    val gateway = EmbyServerGateway(ref, "visit-A", detail)
    val stack = mutableListOf<app.marlboroadvance.mpvex.presentation.Screen>(MainScreen, gateway)
    val originalKey = embyNavigationContentKey(gateway, gateway)

    assertTrue(replaceTopEmbyGatewayDetail(stack, "detail-entry", "episode-2", "Episode 2"))
    assertEquals(2, stack.size)
    val replaced = stack.last() as EmbyServerGateway
    assertEquals(ref, replaced.serverRef)
    assertEquals("visit-A", replaced.visitId)
    val target = replaced.target as EmbyServerDestination.Detail
    assertEquals("episode-2", target.itemId)
    assertEquals("detail-entry", target.detailEntryId)
    assertEquals(originalKey, embyNavigationContentKey(replaced, replaced))
    val json = Json.encodeToString(EmbyServerGateway.serializer(), replaced)
    assertFalse(json.contains("password"))
    assertFalse(json.contains("apiToken"))
    assertFalse(json.contains("baseUrl"))
  }

  @Test fun staleOrWrongShapeReplaceNeverMutatesOrFallsBackToPush() {
    val entry = EmbyDetailScreen("episode-1", "Episode 1", "current-entry")
    val stack = mutableListOf<app.marlboroadvance.mpvex.presentation.Screen>(MainScreen, entry)
    val before = stack.toList()
    assertFalse(replaceTopEmbyDetail(stack, "stale-entry", "episode-2", "Episode 2"))
    assertEquals(before, stack)
    assertFalse(replaceTopEmbyGatewayDetail(stack, "stale-entry", "episode-2", "Episode 2"))
    assertEquals(before, stack)
    assertFalse(replaceTopEmbyDetailEntry(stack, "stale-entry", "episode-2", "Episode 2"))
    assertEquals(before, stack)
    stack += EmbyPersonScreen("person", "Person")
    val withPerson = stack.toList()
    assertFalse(replaceTopEmbyDetailEntry(stack, "current-entry", "episode-3", "Episode 3"))
    assertEquals(withPerson, stack)
    assertFalse(replaceTopEmbyDetailEntry(stack, "current-entry", "", "Blank"))
    assertEquals(withPerson, stack)
  }

  @Test fun gatewayReplacerRejectsStaleCallbackWithoutMutation() {
    val gateway = EmbyServerGateway(ref, "visit-A", EmbyDetailScreen("episode-1", "Episode 1", "current-entry"))
    val stack = mutableListOf<app.marlboroadvance.mpvex.presentation.Screen>(MainScreen, gateway)
    val before = stack.toList()
    assertFalse(replaceTopEmbyGatewayDetail(stack, "stale-entry", "episode-2", "Episode 2"))
    assertEquals(before, stack)
  }

  @Test fun contentKeysIgnoreMediaAndTitleButSeparateEntriesAndServers() {
    val first = EmbyDetailScreen("episode-1", "First", "detail-entry")
    val sibling = first.copy(itemId = "episode-2", title = "Second")
    val otherEntry = sibling.copy(detailEntryId = "other-entry")
    assertEquals(embyNavigationContentKey(first), embyNavigationContentKey(sibling))
    assertNotEquals(embyNavigationContentKey(first), embyNavigationContentKey(otherEntry))

    val gatewayA = EmbyServerGateway(ref, "visit-A", first)
    val sameVisitOtherServer = EmbyServerGateway(ref.copy(sourceKey = "source-B"), "visit-A", first)
    val gatewayB = EmbyServerGateway(ref.copy(sourceKey = "source-B"), "visit-B", first)
    assertEquals(embyNavigationContentKey(gatewayA, gatewayA), embyNavigationContentKey(sameVisitOtherServer, sameVisitOtherServer))
    assertNotEquals(embyNavigationContentKey(gatewayA, gatewayA), embyNavigationContentKey(gatewayB, gatewayB))
  }

  @Test fun gatewayDestinationDetailIdMatchesGatewayContentKey() {
    val gateway = EmbyServerGateway(ref, "visit-A", EmbyDetailScreen("episode", "Episode", "detail-entry"))
    val destination = gateway.destination as EmbyDetailScreen
    assertEquals(embyNavigationContentKey(gateway, gateway), embyNavigationContentKey(destination, gateway))
  }

  @Test fun readyTapUsesFreshRowRatherThanDisplayedConfiguration() {
    val fresh = server.copy(name = "desktop renamed", configuredBaseUrl = "https://latest.example/emby")
    assertEquals(fresh, resolve(EmbyServerLoadState.Ready(listOf(fresh))))
  }

  @Test fun loadingFailureAndInvalidSourceNeverAllowNavigation() {
    assertNull(resolve(EmbyServerLoadState.Loading))
    assertNull(resolve(EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(server))))
    assertNull(resolve(EmbyServerLoadState.Ready(listOf(server)), current = false))
    assertNull(resolveEmbyServerBinding(ref.copy(sourceKey = ""), EmbyServerLoadState.Ready(listOf(server))) { true })
  }

  @Test fun deletedDisabledAndInvalidConnectionFailClosed() {
    assertNull(resolve(EmbyServerLoadState.Ready(emptyList())))
    listOf(server.copy(disabled = true), server.copy(lineId = "missing-line"),
      server.copy(reverseProxyId = "missing-reverse"), server.copy(configuredBaseUrl = "invalid"))
      .forEach { assertNull(resolve(EmbyServerLoadState.Ready(listOf(it)))) }
  }

  @Test fun remoteUuidAndLocalIdCollisionsDoNotCrossSourcesOrRevisions() {
    val otherSource = server.copy(sourceKey = "source-B")
    assertNull(resolve(EmbyServerLoadState.Ready(listOf(otherSource))))
    assertNull(resolve(EmbyServerLoadState.Ready(listOf(server.copy(sourceRevision = 4)))))
    assertNull(resolve(EmbyServerLoadState.Ready(listOf(server.copy(remoteId = "different-uuid")))))
    assertEquals(server, resolve(EmbyServerLoadState.Ready(listOf(otherSource, server))))
  }

  @Test fun ambiguousExactIdentityFailsRatherThanPickingFirst() {
    assertNull(resolve(EmbyServerLoadState.Ready(listOf(server, server.copy(id = 2)))))
  }

  @Test fun nearestGatewayUsesEntryPositionEvenForEqualMediaIds() {
    val detail = EmbyDetailScreen("same-item-id", "Same title")
    val a = EmbyServerGateway(ref, "visit-A", detail)
    val b = EmbyServerGateway(ref.copy(sourceKey = "source-B"), "visit-B", detail)
    val stack = listOf(MainScreen, a, detail, b, detail, EmbyPersonScreen("same-person", "Person"))
    assertSame(a, nearestEmbyServerGateway(stack, 2))
    assertSame(b, nearestEmbyServerGateway(stack, 4))
    assertSame(b, nearestEmbyServerGateway(stack, 5))
    assertNull(nearestEmbyServerGateway(stack, 0))
    assertNull(nearestEmbyServerGateway(stack, -1))
    assertNull(nearestEmbyServerGateway(stack, stack.size))
  }

  @Test fun descendantsKeepIdentityAndPopReturnsToPriorVisit() {
    val a = EmbyServerGateway(ref, "A", EmbyDetailScreen("movie", "Movie"))
    val b = EmbyServerGateway(ref, "B", EmbyDetailScreen("movie", "Movie"))
    val stack = mutableListOf<app.marlboroadvance.mpvex.presentation.Screen>(MainScreen, a,
      EmbyPersonScreen("person", "Person"), b, EmbyDetailScreen("child", "Child"))
    assertSame(b, nearestEmbyServerGateway(stack, stack.lastIndex))
    stack.removeAt(stack.lastIndex)
    stack.removeAt(stack.lastIndex)
    assertSame(a, nearestEmbyServerGateway(stack, stack.lastIndex))
  }

  @Test fun layerRootNeverBorrowsPreviousGateway() {
    val gateway = EmbyServerGateway(ref, "A", EmbyDetailScreen("movie", "Movie"))
    assertNull(nearestEmbyServerGateway(listOf(MainScreen, gateway, EmbyServerHomeScreen,
      EmbyDetailScreen("item", "Title")), 3))
  }

  @Test fun failedRefreshBlocksInteractionButRetainsSessionAndVisitForRecovery() {
    val failed = EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(server))
    assertTrue(shouldRetainEmbyServerVisit(ref, source))
    assertNull(resolve(failed))
    var calls = 0
    val retained = nextBoundEmbyServer(server, server, failed, source, { true }) { calls++; it }
    assertSame(server, retained)
    assertEquals(0, calls)
    val refreshed = server.copy(name = "fresh name", apiToken = "memory session")
    assertSame(refreshed, nextBoundEmbyServer(server, retained, EmbyServerLoadState.Ready(listOf(server)),
      source, { true }) { original -> assertSame(server, original); refreshed })
  }

  @Test fun sourceChangeInvalidatesEvenBeforeLoadingStateUpdateAndNeverRevivesOldVisit() {
    val changed = PostgresSourceState(4, "source-B")
    assertFalse(shouldRetainEmbyServerVisit(ref, changed))
    val invalidated = nextBoundEmbyServer(server, server, EmbyServerLoadState.Ready(listOf(server)),
      changed, { false }) { fail("must not resolve session on stale source"); null }
    assertNull(invalidated)
    assertNull(nextBoundEmbyServer(server, invalidated, EmbyServerLoadState.Ready(listOf(server)),
      source, { true }) { server })
  }

  @Test fun credentialOrConnectionChangeCannotRebindVisitToFreshConfiguration() {
    val changed = server.copy(password = "new credential", configuredBaseUrl = "https://changed.example")
    val invalidated = nextBoundEmbyServer(server, server, EmbyServerLoadState.Ready(listOf(changed)),
      source, { true }) { original -> assertSame(server, original); null }
    assertNull(invalidated)
    assertNull(nextBoundEmbyServer(server, invalidated, EmbyServerLoadState.Ready(listOf(server)),
      source, { true }) { server })
  }

  @Test fun removedOrDisabledRowPermanentlyInvalidatesBoundFlow() {
    val disabled = nextBoundEmbyServer(server, server, EmbyServerLoadState.Ready(listOf(server.copy(disabled = true))),
      source, { true }) { fail("disabled row must not authenticate"); null }
    assertNull(disabled)
    assertNull(nextBoundEmbyServer(server, server, EmbyServerLoadState.Ready(emptyList()),
      source, { true }) { server })
  }
}
