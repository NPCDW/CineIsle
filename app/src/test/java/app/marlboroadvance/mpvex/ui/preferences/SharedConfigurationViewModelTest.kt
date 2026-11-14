package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceState
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationDataSource
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationSection
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedConfigurationViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun before() { Dispatchers.setMain(dispatcher) }
  @After fun after() { Dispatchers.resetMain() }

  @Test fun `construction is lazy initial load publishes sanitized snapshot`() = runTest(dispatcher) {
    val source = FakeSource()
    val vm = SharedConfigurationViewModel(source)
    runCurrent()
    assertEquals(0, source.loads)
    vm.loadIfNeeded(); runCurrent()
    assertEquals(1, source.loads)
    assertSame(source.snapshot, vm.state.value.snapshot)
    assertFalse(vm.state.value.loading)
    assertFalse(vm.state.value.stale)
  }

  @Test fun `manual refresh bypasses cache request and same-source failure retains stale snapshot`() = runTest(dispatcher) {
    val source = FakeSource()
    val vm = SharedConfigurationViewModel(source)
    vm.loadIfNeeded(); runCurrent()
    source.failure = PostgresFailure.CONNECTION
    vm.refresh(); runCurrent()
    assertEquals(listOf(false, true), source.forceRequests)
    assertSame(source.snapshot, vm.state.value.snapshot)
    assertTrue(vm.state.value.stale)
    assertEquals(PostgresFailure.CONNECTION, vm.state.value.error)
  }

  @Test fun `source revision including password-only change immediately clears snapshot`() = runTest(dispatcher) {
    val source = FakeSource()
    val vm = SharedConfigurationViewModel(source)
    vm.loadIfNeeded(); runCurrent()
    source.switchRevision()
    runCurrent()
    assertNull(vm.state.value.snapshot)
    assertFalse(vm.state.value.stale)
    assertEquals(PostgresFailure.SOURCE_CHANGED, vm.state.value.error)
  }

  @Test fun `late previous-source load cannot publish after revision change`() = runTest(dispatcher) {
    val source = FakeSource()
    val gate = CompletableDeferred<Unit>()
    source.gate = gate
    val vm = SharedConfigurationViewModel(source)
    vm.loadIfNeeded(); runCurrent()
    source.switchRevision(); runCurrent()
    gate.complete(Unit); runCurrent()
    assertNull(vm.state.value.snapshot)
    assertEquals(PostgresFailure.SOURCE_CHANGED, vm.state.value.error)
  }

  @Test fun `load failure without snapshot is not marked stale`() = runTest(dispatcher) {
    val source = FakeSource().apply { failure = PostgresFailure.TIMEOUT }
    val vm = SharedConfigurationViewModel(source)
    vm.loadIfNeeded(); runCurrent()
    assertNull(vm.state.value.snapshot)
    assertFalse(vm.state.value.stale)
    assertEquals(PostgresFailure.TIMEOUT, vm.state.value.error)
  }

  private class FakeSource : SharedConfigurationDataSource {
    override val sourceChanges = MutableStateFlow(PostgresSourceState(1, "source"))
    val snapshot = SharedConfigurationSnapshot(
      PostgresSourceToken("source", 1),
      listOf(SharedConfigurationSection("Section", items = emptyList())),
      emptyList(),
    )
    var loads = 0
    val forceRequests = mutableListOf<Boolean>()
    var failure: PostgresFailure? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun currentSource() = PostgresSourceToken(sourceChanges.value.sourceKey, sourceChanges.value.revision)

    override suspend fun snapshot(expected: PostgresSourceToken, forceRefresh: Boolean): SharedConfigurationSnapshot {
      loads++
      forceRequests += forceRefresh
      gate?.await()
      failure?.let { throw PostgresException(it) }
      return snapshot
    }

    fun switchRevision() {
      sourceChanges.value = sourceChanges.value.copy(revision = sourceChanges.value.revision + 1)
    }
  }
}
