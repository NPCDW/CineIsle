package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Memory-only seams: no Koin, Emby API, credentials, or real shared database is accessed. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbyServerSelectionViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun before() { Dispatchers.setMain(dispatcher) }
  @After fun after() { Dispatchers.resetMain() }
  private val server = EmbyServer(id = 1, name = "test", host = "example.test", username = "", password = "",
    sourceKey = "source", remoteId = "remote", sourceRevision = 0)
  private val state = MutableStateFlow<EmbyServerLoadState>(EmbyServerLoadState.Ready(listOf(server)))
  private val current = MutableStateFlow<EmbyServer?>(null)
  private var latest: EmbyServer? = server
  private var selections = 0
  private var refreshes = 0
  private var failDuringSelect = false
  private fun vm() = EmbyServerSelectionViewModel(state,
    refreshSnapshot = { refreshes++ }, snapshotById = { latest }, selectLatest = {
      if (failDuringSelect) throw IllegalStateException("unsafe URL/token must not escape")
      selections++
      current.value = it
    })
  private fun displayedRow() = projectEmbyServerRows(EmbyServerLoadState.Ready(listOf(server))).single()

  @Test fun constructionDoesNotRefreshAuthenticateOrLoadMedia() = runTest(dispatcher) {
    val vm = vm()
    runCurrent()
    assertEquals(0, refreshes)
    assertEquals(0, selections)
    assertEquals(1, vm.rows.value.size)
  }

  @Test fun successfulSelectionAloneAllowsCallerNavigation() = runTest(dispatcher) {
    val vm = vm()
    var navigationCalls = 0
    if (vm.selectServer(displayedRow())) navigationCalls++
    assertEquals(1, navigationCalls)
    assertEquals(1, selections)
    assertNull(vm.errorResource.value)
    assertEquals(server, current.value)
    assertEquals(0, refreshes)
  }

  @Test fun activeSessionChangesAndSelectionDoNotChangeListProjection() = runTest(dispatcher) {
    val vm = vm()
    val emissions = mutableListOf<List<EmbyServerRow>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect { emissions.add(it) } }
    runCurrent()
    val originalRows = vm.rows.value

    current.value = server.copy(remoteId = "other", apiToken = "other-session")
    runCurrent()
    assertEquals(originalRows, vm.rows.value)
    assertEquals(1, emissions.size)
    assertTrue(vm.selectServer(originalRows.single()))
    runCurrent()
    assertEquals(server, current.value)
    assertEquals(originalRows, vm.rows.value)
    assertEquals(1, emissions.size)

    val renamed = server.copy(name = "desktop renamed")
    latest = renamed
    state.value = EmbyServerLoadState.Ready(listOf(renamed))
    runCurrent()
    assertEquals("desktop renamed", vm.rows.value.single().name)
    assertEquals(2, emissions.size)
  }

  @Test fun alreadyActiveServerCanBeExplicitlyReenteredWithoutRefresh() = runTest(dispatcher) {
    current.value = server
    val vm = vm()
    val row = displayedRow()
    var navigationCalls = 0
    repeat(2) {
      if (vm.selectServer(row)) navigationCalls++
      assertNull(vm.errorResource.value)
    }
    assertEquals(2, navigationCalls)
    assertEquals(2, selections)
    assertEquals(server, current.value)
    assertEquals(0, refreshes)
  }

  @Test fun reentryStillChecksLatestSnapshotAndCanRecoverAfterReadyRefresh() = runTest(dispatcher) {
    current.value = server
    val vm = vm()
    val row = displayedRow()
    var navigationCalls = 0
    if (vm.selectServer(row)) navigationCalls++
    latest = server.copy(disabled = true)
    assertFalse(vm.selectServer(row))
    assertEquals(R.string.emby_servers_selection_failed, vm.errorResource.value)
    latest = server
    state.value = EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(server))
    assertFalse(vm.selectServer(row))
    state.value = EmbyServerLoadState.Ready(listOf(server))
    if (vm.selectServer(row)) navigationCalls++
    assertEquals(2, navigationCalls)
    assertEquals(2, selections)
    assertNull(vm.errorResource.value)
    assertEquals(0, refreshes)
  }

  @Test fun selectionUsesLatestSnapshotRatherThanStaleRowConfiguration() = runTest(dispatcher) {
    val vm = vm()
    latest = server.copy(name = "desktop changed", configuredBaseUrl = "https://new.example.test/emby")
    assertTrue(vm.selectServer(displayedRow()))
    assertEquals(latest, current.value)
  }

  @Test fun deletedDisabledOrInvalidatedRowNeverSelectsOrNavigates() = runTest(dispatcher) {
    val vm = vm()
    var navigationCalls = 0
    listOf(null, server.copy(disabled = true), server.copy(lineId = "deleted-line"),
      server.copy(reverseProxyId = "missing-reverse"), server.copy(configuredBaseUrl = "invalid"))
      .forEach { snapshot ->
        latest = snapshot
        if (vm.selectServer(displayedRow())) navigationCalls++
        assertEquals(R.string.emby_servers_selection_failed, vm.errorResource.value)
      }
    assertEquals(0, selections)
    assertEquals(0, navigationCalls)
  }

  @Test fun sourceRemoteIdentityOrRevisionChangeRejectsStaleTap() = runTest(dispatcher) {
    val vm = vm()
    listOf(server.copy(sourceKey = "different"), server.copy(remoteId = "other"), server.copy(sourceRevision = 1))
      .forEach { latest = it; assertFalse(vm.selectServer(displayedRow())) }
    assertEquals(0, selections)
  }

  @Test fun repositoryRaceFailureIsSanitizedAndDoesNotNavigate() = runTest(dispatcher) {
    val vm = vm()
    failDuringSelect = true
    var navigationCalls = 0
    if (vm.selectServer(displayedRow())) navigationCalls++
    assertEquals(0, navigationCalls)
    assertEquals(0, selections)
    assertEquals(R.string.emby_servers_selection_failed, vm.errorResource.value)
  }

  @Test fun failedDatabaseRefreshPreventsSelectionOfRetainedSnapshot() = runTest(dispatcher) {
    val vm = vm()
    state.value = EmbyServerLoadState.Failed(PostgresFailure.CONNECTION, listOf(server))
    assertFalse(vm.selectServer(displayedRow()))
    assertEquals(0, selections)
  }

  @Test fun explicitRefreshCoalescesAndDoesNotSelect() = runTest(dispatcher) {
    val vm = vm()
    vm.refreshServers()
    vm.refreshServers()
    runCurrent()
    assertEquals(1, refreshes)
    assertEquals(0, selections)
    assertFalse(vm.refreshing.value)
  }
}
