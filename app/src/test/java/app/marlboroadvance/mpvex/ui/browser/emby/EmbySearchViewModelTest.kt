package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemsResult
import app.marlboroadvance.mpvex.domain.emby.EmbySearchBackend
import app.marlboroadvance.mpvex.domain.emby.EmbySearchService
import app.marlboroadvance.mpvex.domain.emby.EmbySearchStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmbySearchViewModelTest {
  private val server = EmbyServer(id = 1, name = "fixture", host = "fixture.test", username = "", password = "",
    sourceKey = "source", sourceRevision = 1, remoteId = "row")
  private fun backend(request: suspend (String) -> EmbyItemsResult) = object : EmbySearchBackend {
    override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int) = request(query)
    override suspend fun persons(server: EmbyServer, query: String, limit: Int) = EmbyItemsResult()
  }
  private fun page(name: String) = EmbyItemsResult(listOf(EmbyItem(Id = name, Name = name, Type = "Movie")), 1)

  @Test fun defaultSearchRequestsOnlyMoviesAndSeriesAndAllRemainsExplicitlySelectable() = runTest {
    val requestedTypes = mutableListOf<List<String>>()
    var personRequests = 0
    val vm = EmbySearchViewModel(EmbySearchService(object : EmbySearchBackend {
      override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int): EmbyItemsResult {
        requestedTypes += types
        return page(query)
      }
      override suspend fun persons(server: EmbyServer, query: String, limit: Int): EmbyItemsResult {
        personRequests++
        return EmbyItemsResult()
      }
    }), this)
    assertEquals(setOf(EmbySearchFilter.MOVIE, EmbySearchFilter.SERIES), vm.state.value.filters)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("movie")
    vm.submit()
    advanceUntilIdle()
    assertEquals(listOf(listOf("Movie", "Series")), requestedTypes)
    assertEquals(0, personRequests)
    vm.setFilters(emptySet())
    advanceUntilIdle()
    assertTrue(requestedTypes.last().containsAll(listOf("Movie", "Series", "Episode", "BoxSet")))
    assertEquals(1, personRequests)
    vm.setFilters(setOf(EmbySearchFilter.PERSON))
    advanceUntilIdle()
    assertEquals(2, requestedTypes.size)
    assertEquals(2, personRequests)
  }

  @Test fun debounceCoalescesInputAndKeyboardSubmitsImmediately() = runTest {
    val requests = mutableListOf<String>()
    val vm = EmbySearchViewModel(EmbySearchService(backend { requests += it; page(it) }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("f")
    advanceTimeBy(200)
    vm.setQuery("film")
    advanceTimeBy(399)
    runCurrent()
    assertTrue(requests.isEmpty())
    vm.submit()
    runCurrent()
    assertEquals(listOf("film"), requests)
    advanceUntilIdle()
    assertEquals(listOf("film"), requests)
    assertEquals("film", vm.state.value.groups.single().items.single().Id)
  }

  @Test fun changedQueryAndSourceRejectNonCooperativeLateResults() = runTest {
    val gate = CompletableDeferred<Unit>()
    val vm = EmbySearchViewModel(EmbySearchService(backend { query ->
      if (query == "old") withContext(NonCancellable) { gate.await() }
      page(query)
    }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("old")
    vm.submit()
    runCurrent()
    vm.setQuery("new")
    vm.bindSources(listOf(server.copy(sourceKey = "other", sourceRevision = 2)), null, "other:2")
    vm.submit()
    runCurrent()
    assertEquals("new", vm.state.value.groups.single().items.single().Id)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals("other", vm.state.value.groups.single().server.sourceKey)
    assertEquals("new", vm.state.value.groups.single().items.single().Id)
  }

  @Test fun sourceFailurePreservesResultsAndDoesNotSearchCachedServers() = runTest {
    var requests = 0
    val vm = EmbySearchViewModel(EmbySearchService(backend { requests++; page(it) }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("movie")
    vm.submit()
    advanceUntilIdle()
    vm.bindSources(listOf(server), EmbySearchSourceIssue.FAILED, "source:1")
    vm.submit()
    runCurrent()
    assertEquals(1, requests)
    assertEquals("movie", vm.state.value.groups.single().items.single().Id)
    assertEquals(EmbySearchSourceIssue.FAILED, vm.state.value.sourceIssue)
    vm.bindSources(emptyList(), EmbySearchSourceIssue.LOADING, "other:2")
    assertTrue(vm.state.value.groups.isEmpty())
  }

  @Test fun failedRetryKeepsSuccessfulResultsAndDoesNotClaimEmpty() = runTest {
    var fail = false
    val vm = EmbySearchViewModel(EmbySearchService(backend { if (fail) throw IOException("private") else page(it) }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("movie")
    vm.submit()
    advanceUntilIdle()
    fail = true
    vm.submit()
    advanceUntilIdle()
    val group = vm.state.value.groups.single()
    assertEquals("movie", group.items.single().Id)
    assertEquals(EmbySearchStatus.PARTIAL, group.status)
    assertTrue(group.failures.isNotEmpty())
  }

  @Test fun retryGroupDoesNotRequestOrReplaceOtherServers() = runTest {
    val requests = mutableListOf<Long>()
    val service = EmbySearchService(object : EmbySearchBackend {
      override suspend fun media(server: EmbyServer, query: String, types: List<String>, limit: Int): EmbyItemsResult {
        requests += server.id
        return page("${server.id}-${requests.count { it == server.id }}")
      }
      override suspend fun persons(server: EmbyServer, query: String, limit: Int) = EmbyItemsResult()
    })
    val vm = EmbySearchViewModel(service, this)
    vm.bindSources(listOf(server, server.copy(id = 2, remoteId = "row-2")), null, "source:1")
    vm.setQuery("movie")
    vm.submit()
    advanceUntilIdle()
    val before = vm.state.value.groups[1]
    vm.retryGroup(vm.state.value.groups[0].key)
    advanceUntilIdle()
    assertEquals(listOf(1L, 2L, 1L), requests)
    assertEquals(before, vm.state.value.groups[1])
    assertEquals("1-2", vm.state.value.groups[0].items.single().Id)
  }

  @Test fun reenterSameSourceResumesCancelledBusySearchButDoesNotReloadCompletedSearch() = runTest {
    val gate = CompletableDeferred<Unit>()
    var requests = 0
    val vm = EmbySearchViewModel(EmbySearchService(backend { query ->
      requests++
      gate.await()
      page(query)
    }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("movie")
    vm.submit()
    runCurrent()
    assertTrue(vm.state.value.pending)
    vm.cancelSearch()
    runCurrent()
    assertFalse(vm.state.value.pending)
    vm.bindSources(listOf(server), null, "source:1")
    runCurrent()
    assertEquals(2, requests)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals("movie", vm.state.value.groups.single().items.single().Id)
    vm.cancelSearch()
    vm.bindSources(listOf(server), null, "source:1")
    runCurrent()
    assertEquals(2, requests)
  }

  @Test fun clearingInputCancelsPendingDebounce() = runTest {
    var requests = 0
    val vm = EmbySearchViewModel(EmbySearchService(backend { requests++; page(it) }), this)
    vm.bindSources(listOf(server), null, "source:1")
    vm.setQuery("movie")
    vm.setQuery("")
    advanceUntilIdle()
    assertEquals(0, requests)
    assertFalse(vm.state.value.pending)
    assertTrue(vm.state.value.groups.single().items.isEmpty())
  }
}
