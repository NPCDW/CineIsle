package app.marlboroadvance.mpvex.domain.emby

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmbySearchServiceTest {
  private fun row(id: Int, disabled: Boolean = false) = EmbyServer(
    id = id.toLong(), name = "server-$id", host = "fixture.test", username = "", password = "",
    sourceKey = "source", sourceRevision = 1, remoteId = "row-$id", disabled = disabled,
  )
  private fun item(id: String, type: String = "Movie") = EmbyItem(Id = id, Name = id, Type = type)
  private fun backend(
    media: suspend (EmbyServer, String) -> EmbyItemsResult = { _, _ -> EmbyItemsResult() },
    persons: suspend (EmbyServer, String) -> EmbyItemsResult = { _, _ -> EmbyItemsResult() },
  ) = object : EmbySearchBackend {
    override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int) = media(server, query)
    override suspend fun persons(server: EmbyServer, query: String, limit: Int) = persons(server, query)
  }

  @Test fun asyncCompletionsKeepSnapshotOrderAndOnlyFourServersRun() = runTest {
    val gates = (1..9).associateWith { CompletableDeferred<Unit>() }
    var active = 0
    var maximum = 0
    val started = mutableListOf<Long>()
    val service = EmbySearchService(backend(media = { server, _ ->
      started += server.id
      active++
      maximum = maxOf(maximum, active)
      gates.getValue(server.id.toInt()).await()
      active--
      EmbyItemsResult(listOf(item("${server.id}")), 1)
    }))
    // Deliberate database ordering, distinct from names/local ids/orderBy.
    val rows = listOf(4, 2, 9, 1, 8, 7, 6, 5, 3).map(::row) + row(10, disabled = true)
    val snapshots = mutableListOf<List<EmbySearchGroup>>()
    val job = launch { service.search(rows, "query", listOf("Movie")).toList(snapshots) }
    runCurrent()
    assertEquals(listOf(4L, 2L, 9L, 1L), started)
    assertEquals(4, maximum)
    gates.getValue(1).complete(Unit)
    runCurrent()
    assertTrue(snapshots.last().single { it.server.id == 1L }.items.isNotEmpty())
    assertEquals(4L, snapshots.last().first().server.id)
    gates.values.forEach { it.complete(Unit) }
    advanceUntilIdle()
    job.join()
    snapshots.forEach { assertEquals(rows.filterNot { it.disabled }.map { it.id }, it.map { it.server.id }) }
    assertEquals(4, maximum)
    assertTrue(snapshots.last().all { it.status == EmbySearchStatus.SUCCESS })
  }

  @Test fun failuresStayFailuresAndSuccessfulBranchSurvives() = runTest {
    val service = EmbySearchService(backend(
      media = { server, _ -> if (server.id == 2L) throw IOException("secret address")
        else EmbyItemsResult(listOf(item("media")), 1) },
      persons = { _, _ -> throw EmbyApiException(401, "secret token") },
    ))
    val final = service.search(listOf(row(1), row(2)), "actor").toList().last()
    assertEquals(EmbySearchStatus.PARTIAL, final[0].status)
    assertEquals("media", final[0].items.single().Id)
    assertEquals(listOf(EmbySearchFailure.AUTH), final[0].failures)
    assertEquals(EmbySearchStatus.FAILED, final[1].status)
    assertTrue(final[1].items.isEmpty())
    assertEquals(setOf(EmbySearchFailure.AUTH, EmbySearchFailure.NETWORK), final[1].failures.toSet())
    assertFalse(final.toString().contains("secret"))
  }

  @Test fun emptyIsOnlySuccessfulEmptyAndInvalidConnectionsAreNeverRequested() = runTest {
    var requests = 0
    val service = EmbySearchService(backend(media = { _, _ -> requests++; EmbyItemsResult() }))
    val invalid = row(2).copy(lineId = "missing")
    val final = service.search(listOf(row(1), invalid), "query", listOf("Movie")).toList().last()
    assertEquals(1, requests)
    assertEquals(EmbySearchStatus.EMPTY, final[0].status)
    assertEquals(EmbySearchStatus.FAILED, final[1].status)
    assertEquals(listOf(EmbySearchFailure.CONFIGURATION), final[1].failures)
  }

  @Test fun cancellationStopsWorkersAndNeverStartsFallbackOrRetry() = runTest {
    var starts = 0
    var cancelled = 0
    var persons = 0
    val service = EmbySearchService(backend(media = { _, _ ->
      starts++
      try { awaitCancellation() } finally { cancelled++ }
    }, persons = { _, _ -> persons++; EmbyItemsResult() }))
    val snapshots = mutableListOf<List<EmbySearchGroup>>()
    val job = launch { service.search((1..12).map(::row), "query").toList(snapshots) }
    runCurrent()
    assertEquals(4, starts)
    job.cancel()
    job.join()
    advanceUntilIdle()
    assertEquals(4, cancelled)
    assertEquals(4, starts)
    assertEquals(0, persons)
    assertFalse(snapshots.last().any { it.status == EmbySearchStatus.EMPTY || it.status == EmbySearchStatus.FAILED })
  }

  @Test fun endpointTimeoutPreservesEarlierResultsAndBoundsBothPages() = runTest {
    val limits = mutableListOf<Int>()
    val backend = object : EmbySearchBackend {
      override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int): EmbyItemsResult {
        limits += limit
        return EmbyItemsResult((1..300).map { item("media-$it") }, 300)
      }
      override suspend fun persons(server: EmbyServer, query: String, limit: Int): EmbyItemsResult {
        limits += limit
        awaitCancellation()
      }
    }
    val snapshots = mutableListOf<List<EmbySearchGroup>>()
    launch { EmbySearchService(backend).search(listOf(row(1)), "query").toList(snapshots) }
    runCurrent()
    advanceTimeBy(EmbySearchService.REQUEST_TIMEOUT_MS + 1)
    runCurrent()
    val group = snapshots.last().single()
    assertEquals(80, group.items.size)
    assertTrue(group.truncated)
    assertEquals(EmbySearchStatus.PARTIAL, group.status)
    assertEquals(listOf(EmbySearchFailure.TIMEOUT), group.failures)
    assertEquals(listOf(80, 40), limits)
  }

  @Test fun personFilterUsesOneBoundedPersonsCallAndNoMediaCall() = runTest {
    var media = 0
    var people = 0
    val service = EmbySearchService(backend(media = { _, _ -> media++; EmbyItemsResult() }, persons = { _, _ ->
      people++; EmbyItemsResult(listOf(item("person", "Person")), 1)
    }))
    val group = service.search(listOf(row(1)), "actor", listOf("Person")).toList().last().single()
    assertEquals(0, media)
    assertEquals(1, people)
    assertEquals("Person", group.items.single().Type)
  }
}
