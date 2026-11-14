package app.marlboroadvance.mpvex.ui.browser.history

import app.marlboroadvance.mpvex.database.repository.PostgresPlaybackHistoryRepository
import app.marlboroadvance.mpvex.domain.postgres.HistoryJdbcFixture
import app.marlboroadvance.mpvex.domain.postgres.PostgresExecutor
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryFilters
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkResult
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkWriter
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryRow
import app.marlboroadvance.mpvex.domain.postgres.PostgresSession
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceState
import app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken
import app.marlboroadvance.mpvex.domain.postgres.JdbcOperationControl
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
class PlaybackHistoryViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun before() { Dispatchers.setMain(dispatcher) }
  @After fun after() { Dispatchers.resetMain() }

  private val jdbc = HistoryJdbcFixture()
  private val executor = Executor(jdbc)
  private val writer = Writer()
  private fun vm() = PlaybackHistoryViewModel(PostgresPlaybackHistoryRepository(executor, writer))

  @Test fun `construction is lazy refresh failure retains same source rows and count`() = runTest(dispatcher) {
    val vm = vm()
    runCurrent()
    assertEquals(0, executor.reads)
    vm.refresh(); runCurrent()
    val original = vm.state.value.rows
    assertEquals(1, original.size)
    executor.failure = PostgresFailure.CONNECTION
    vm.refresh(); runCurrent()
    assertEquals(original, vm.state.value.rows)
    assertEquals(1L, vm.state.value.total)
    assertFalse(vm.state.value.loading)
    assertEquals(PostgresFailure.CONNECTION, vm.state.value.error)
  }

  @Test fun `filter and page changes clear differently labelled old rows and reset page`() = runTest(dispatcher) {
    jdbc.total = 61L
    val vm = vm()
    vm.refresh(); runCurrent()
    vm.goToPage(3)
    assertTrue(vm.state.value.rows.isEmpty())
    runCurrent()
    assertEquals(3, vm.state.value.pageNumber)
    executor.failure = PostgresFailure.CONNECTION
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "different"))
    assertTrue(vm.state.value.rows.isEmpty())
    assertEquals(1, vm.state.value.pageNumber)
    runCurrent()
    assertTrue(vm.state.value.rows.isEmpty())
    assertEquals(PostgresFailure.CONNECTION, vm.state.value.error)
  }

  @Test fun `page clamps within transaction after desktop removals including empty result`() = runTest(dispatcher) {
    jdbc.total = 61L
    val vm = vm()
    vm.refresh(); runCurrent()
    vm.goToPage(3); runCurrent()
    jdbc.total = 1L
    vm.refresh(); runCurrent()
    assertEquals(1, vm.state.value.pageNumber)
    jdbc.total = 0L; jdbc.missingRow = true
    vm.refresh(); runCurrent()
    assertEquals(1, vm.state.value.pageNumber)
    assertEquals(0L, vm.state.value.total)
    assertTrue(vm.state.value.rows.isEmpty())
  }

  @Test fun `source revision change clears rows filters and rejects stale mark`() = runTest(dispatcher) {
    val vm = vm()
    vm.refresh(); runCurrent()
    val row = vm.state.value.rows.single()
    executor.switchRevision(); runCurrent()
    assertTrue(vm.state.value.rows.isEmpty())
    assertEquals(PostgresPlaybackHistoryFilters(), vm.state.value.filters)
    assertEquals(PostgresFailure.SOURCE_CHANGED, vm.state.value.error)
    vm.cycleMark(row); runCurrent()
    assertEquals(0, writer.calls)
  }

  @Test fun `late read cannot publish after source changes even a broken executor returns it`() = runTest(dispatcher) {
    val vm = vm()
    val gate = CompletableDeferred<Unit>()
    executor.gate = gate
    vm.refresh(); runCurrent()
    executor.switchRevision()
    gate.complete(Unit)
    runCurrent()
    assertTrue(vm.state.value.rows.isEmpty())
    assertEquals(PostgresFailure.SOURCE_CHANGED, vm.state.value.error)
  }

  @Test fun `unknown write is never retried successful recovery exposes observed value truthfully`() = runTest(dispatcher) {
    val vm = vm()
    vm.refresh(); runCurrent()
    val row = vm.state.value.rows.single()
    writer.result = PostgresPlaybackHistoryMarkResult.CommitUnknown
    writer.after = {
      jdbc.values["pinned"] = -1
      jdbc.values["update_time"] = java.sql.Timestamp(4_321L)
    }
    vm.cycleMark(row); runCurrent()
    val mark = vm.state.value.markState as PlaybackHistoryMarkState.Failed
    assertEquals(PlaybackHistoryMarkFailure.COMMIT_UNKNOWN, mark.failure)
    assertTrue(mark.reconciled)
    assertFalse(mark.requiresReconcile)
    assertEquals(-1, mark.observedPinned)
    assertEquals(true, mark.observedRowExists)
    assertEquals(-1, vm.state.value.rows.single().pinned)
    assertEquals(4_321L, vm.state.value.rows.single().updateTimeMs)
    assertEquals(1, writer.calls)
    vm.cycleMark(row); runCurrent()
    assertEquals(1, writer.calls)
    vm.dismissMarkError()
    assertEquals(PlaybackHistoryMarkState.Idle, vm.state.value.markState)
  }

  @Test fun `failed unknown reconciliation blocks dismissal and refresh retries only target SELECT`() = runTest(dispatcher) {
    populateMultipleRows()
    val vm = vm()
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "Series")); runCurrent()
    vm.goToPage(3); runCurrent()
    val before = vm.state.value
    val row = before.rows[1]
    val selects = jdbc.calls.size
    writer.result = PostgresPlaybackHistoryMarkResult.CommitUnknown
    writer.after = { jdbc.queryError = true; jdbc.total = 1L }
    vm.cycleMark(row); runCurrent()
    assertTrue((vm.state.value.markState as PlaybackHistoryMarkState.Failed).requiresReconcile)
    assertEquals(before.rows, vm.state.value.rows)
    assertEquals(before.total, vm.state.value.total)
    assertEquals(before.pageNumber, vm.state.value.pageNumber)
    assertTargetSelects(row, selects, 1)
    vm.dismissMarkError()
    vm.cycleMark(row)
    assertEquals(1, writer.calls)
    jdbc.queryError = false
    vm.refresh(); runCurrent()
    assertEquals(1, writer.calls)
    val failed = vm.state.value.markState as PlaybackHistoryMarkState.Failed
    assertTrue(failed.reconciled)
    assertFalse(failed.requiresReconcile)
    assertEquals(before.copy(markState = failed), vm.state.value)
    assertTargetSelects(row, selects, 2)
  }

  @Test fun `normal three state cycle changes only target pin without any additional SELECT`() = runTest(dispatcher) {
    populateMultipleRows()
    val vm = vm()
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "Series")); runCurrent()
    vm.goToPage(3); runCurrent()
    val before = vm.state.value
    val target = before.rows[1]
    assertEquals(3, before.rows.size)
    val reads = executor.reads
    val selects = jdbc.calls.toList()
    writer.after = {
      // A desktop update/reorder would be visible if normal commits accidentally read the page again.
      jdbc.values["item_name"] = "Desktop renamed target"
      jdbc.values["update_time"] = java.sql.Timestamp(9_999L)
      jdbc.values["played_duration"] = 42L
      jdbc.rows.first()["item_name"] = "Desktop renamed peer"
      jdbc.rows.reverse()
      jdbc.total = 1L
    }
    for ((index, next) in listOf(1, -1, 0).withIndex()) {
      val returnedUpdateTimeMs = 7_000L + index
      writer.result = PostgresPlaybackHistoryMarkResult.Committed(next, returnedUpdateTimeMs)
      val row = vm.state.value.rows.first { it.key == target.key }
      vm.cycleMark(row)
      assertEquals(setOf(target.key), vm.state.value.markingIds)
      runCurrent()
      val expected = before.copy(rows = before.rows.map {
        if (it.key == target.key) it.copy(pinned = next, updateTimeMs = returnedUpdateTimeMs) else it
      })
      assertEquals(expected, vm.state.value)
      assertEquals(PlaybackHistoryMarkState.Idle, vm.state.value.markState)
      assertEquals(reads, executor.reads)
      assertEquals(selects, jdbc.calls)
    }
    assertEquals(listOf(0, 1, -1), writer.inputRows.map { it.pinned })
    assertEquals(listOf(target.key, target.key, target.key), writer.inputRows.map { it.key })
    assertEquals(3, writer.calls)
  }

  @Test fun `committed mark needs no recovery even if subsequent database reads would fail`() = runTest(dispatcher) {
    val vm = vm()
    vm.refresh(); runCurrent()
    val before = vm.state.value
    val row = before.rows.single()
    val selects = jdbc.calls.toList()
    writer.result = PostgresPlaybackHistoryMarkResult.Committed(1, 8_765L)
    writer.after = { executor.failure = PostgresFailure.CONNECTION }
    vm.cycleMark(row); runCurrent()
    assertEquals(before.copy(rows = listOf(row.copy(pinned = 1, updateTimeMs = 8_765L))), vm.state.value)
    assertEquals(PlaybackHistoryMarkState.Idle, vm.state.value.markState)
    assertEquals(1, executor.reads)
    assertEquals(selects, jdbc.calls)
    vm.reconcileMark(); runCurrent()
    assertEquals(1, executor.reads)
    assertEquals(1, writer.calls)
  }

  @Test fun `stale row cannot overwrite a committed pin or start additional SELECT`() = runTest(dispatcher) {
    val vm = vm()
    vm.refresh(); runCurrent()
    val stale = vm.state.value.rows.single()
    vm.cycleMark(stale); runCurrent()
    val selects = jdbc.calls.toList()
    vm.cycleMark(stale); runCurrent()
    assertEquals(1, writer.calls)
    assertEquals(1, vm.state.value.rows.single().pinned)
    assertEquals(PlaybackHistoryMarkFailure.CONFLICT, (vm.state.value.markState as PlaybackHistoryMarkState.Failed).failure)
    assertEquals(selects, jdbc.calls)
  }

  @Test fun `source change during target reconciliation rejects observation and clears old page`() = runTest(dispatcher) {
    populateMultipleRows()
    val vm = vm()
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "Series")); runCurrent()
    vm.goToPage(3); runCurrent()
    val row = vm.state.value.rows[1]
    val selects = jdbc.calls.size
    writer.result = PostgresPlaybackHistoryMarkResult.CommitUnknown
    writer.after = { jdbc.onQuery = { executor.switchRevision() } }
    vm.cycleMark(row); runCurrent()
    assertTrue(vm.state.value.rows.isEmpty())
    assertEquals(0L, vm.state.value.total)
    assertEquals(1, vm.state.value.pageNumber)
    assertEquals(PostgresPlaybackHistoryFilters(), vm.state.value.filters)
    assertEquals(PostgresFailure.SOURCE_CHANGED, vm.state.value.error)
    assertEquals(PlaybackHistoryMarkState.Idle, vm.state.value.markState)
    assertEquals(1, writer.calls)
    assertTargetSelects(row, selects, 1)
  }

  @Test fun `conflict observes only target pin and retains page metadata and peer order`() =
    assertTargetReconciliation(PostgresPlaybackHistoryMarkResult.Conflict, PlaybackHistoryMarkFailure.CONFLICT)

  @Test fun `unknown commit observes only target pin and retains page metadata and peer order`() =
    assertTargetReconciliation(PostgresPlaybackHistoryMarkResult.CommitUnknown, PlaybackHistoryMarkFailure.COMMIT_UNKNOWN)

  private fun assertTargetReconciliation(result: PostgresPlaybackHistoryMarkResult, failure: PlaybackHistoryMarkFailure) = runTest(dispatcher) {
    populateMultipleRows()
    val vm = vm()
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "Series")); runCurrent()
    vm.goToPage(3); runCurrent()
    val before = vm.state.value
    val row = before.rows[1]
    val selects = jdbc.calls.size
    val reads = executor.reads
    writer.result = result
    writer.after = {
      jdbc.values["pinned"] = -1
      jdbc.values["emby_server_name"] = "Desktop renamed server"
      jdbc.values["item_name"] = "Desktop renamed target"
      jdbc.values["series_id"] = "desktop-series"
      jdbc.values["series_name"] = "Desktop series"
      jdbc.values["create_time"] = java.sql.Timestamp(8_888L)
      jdbc.values["update_time"] = java.sql.Timestamp(9_999L)
      jdbc.values["played_duration"] = 42L
      jdbc.rows.first()["item_name"] = "Desktop renamed peer"
      jdbc.rows.reverse()
      jdbc.total = 1L
    }
    vm.cycleMark(row); runCurrent()
    val failed = vm.state.value.markState as PlaybackHistoryMarkState.Failed
    assertEquals(failure, failed.failure)
    assertTrue(failed.reconciled)
    assertFalse(failed.requiresReconcile)
    assertEquals(-1, failed.observedPinned)
    assertEquals(true, failed.observedRowExists)
    assertEquals(
      before.copy(
        rows = before.rows.map {
          if (it.key == row.key) it.copy(pinned = -1, updateTimeMs = 9_999L) else it
        },
        markState = failed,
      ),
      vm.state.value,
    )
    assertEquals(reads + 1, executor.reads)
    assertTargetSelects(row, selects, 1)
    vm.cycleMark(row); runCurrent()
    assertEquals(1, writer.calls)
    assertTargetSelects(row, selects, 1)
  }

  @Test fun `conflict missing row removes only target without refill count or page change and bounds duplicate taps`() = runTest(dispatcher) {
    populateMultipleRows()
    val vm = vm()
    vm.setFilters(PostgresPlaybackHistoryFilters(seriesName = "Series")); runCurrent()
    vm.goToPage(3); runCurrent()
    val before = vm.state.value
    val row = before.rows[1]
    val selects = jdbc.calls.size
    writer.gate = CompletableDeferred()
    writer.result = PostgresPlaybackHistoryMarkResult.Conflict
    writer.after = {
      jdbc.rows.remove(jdbc.values)
      jdbc.rows.reverse()
      jdbc.rows += jdbc.values.toMutableMap().apply { this["id"] = "desktop-replacement" }
      jdbc.total = 1L
    }
    vm.cycleMark(row); vm.cycleMark(row); runCurrent()
    assertEquals(setOf(row.key), vm.state.value.markingIds)
    assertEquals(1, writer.calls)
    writer.gate!!.complete(Unit); runCurrent()
    val failed = vm.state.value.markState as PlaybackHistoryMarkState.Failed
    assertEquals(PlaybackHistoryMarkFailure.CONFLICT, failed.failure)
    assertEquals(false, failed.observedRowExists)
    assertEquals(before.copy(rows = before.rows.filterNot { it.key == row.key }, markState = failed), vm.state.value)
    assertEquals(2, vm.state.value.rows.size)
    assertTargetSelects(row, selects, 1)
    assertEquals(1, writer.calls)
  }

  private fun populateMultipleRows() {
    jdbc.rows.add(0, jdbc.values.toMutableMap().apply {
      this["id"] = "first-history"
      this["item_id"] = "first-item"
      this["item_name"] = "First episode"
      this["pinned"] = 1
      this["update_time"] = java.sql.Timestamp(5_555L)
      this["played_duration"] = 12L
    })
    // Same id in another server verifies that merging and reconciliation use the full row key.
    jdbc.rows += jdbc.values.toMutableMap().apply {
      this["emby_server_id"] = "other-server"
      this["item_name"] = "Other server episode"
      this["pinned"] = -1
      this["played_duration"] = 34L
    }
    jdbc.total = 63L
  }

  private fun assertTargetSelects(row: PostgresPlaybackHistoryRow, from: Int, expectedCount: Int) {
    val queries = jdbc.calls.drop(from)
    assertEquals(expectedCount, queries.size)
    queries.forEach { (sql, parameters) ->
      assertTrue(sql.startsWith("SELECT "))
      assertTrue(sql.endsWith("WHERE id = ? AND emby_server_id = ?"))
      assertFalse(sql.contains("count(*)"))
      assertFalse(sql.contains("ORDER BY"))
      assertFalse(sql.contains("LIMIT"))
      assertEquals(listOf(row.id, row.serverUuid), parameters)
    }
  }

  private class Writer : PostgresPlaybackHistoryMarkWriter {
    var result: PostgresPlaybackHistoryMarkResult = PostgresPlaybackHistoryMarkResult.Committed(1, 2_000L)
    var calls = 0
    val inputRows = mutableListOf<PostgresPlaybackHistoryRow>()
    var after: () -> Unit = {}
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun cycleMark(row: PostgresPlaybackHistoryRow): PostgresPlaybackHistoryMarkResult {
      calls++; inputRows += row; gate?.await(); after(); return result
    }
  }

  private class Executor(private val jdbc: HistoryJdbcFixture) : PostgresExecutor {
    override val sourceChanges = MutableStateFlow(PostgresSourceState(0L, "source-fingerprint"))
    var reads = 0
    var failure: PostgresFailure? = null
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun currentSource() = PostgresSourceToken(sourceChanges.value.sourceKey, sourceChanges.value.revision)
    override fun isCurrent(source: PostgresSourceToken) = source == PostgresSourceToken(sourceChanges.value.sourceKey, sourceChanges.value.revision)
    fun switchRevision() { sourceChanges.value = sourceChanges.value.copy(revision = sourceChanges.value.revision + 1L) }
    override suspend fun <T> read(expectedSourceKey: String?, block: (PostgresSession) -> T): T {
      reads++
      val captured = currentSource()
      gate?.await()
      failure?.let { throw PostgresException(it) }
      return block(PostgresSession(jdbc.connection, captured, JdbcOperationControl()))
    }
  }
}
