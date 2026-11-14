package app.marlboroadvance.mpvex.ui.postgres

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfig
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigStorage
import app.marlboroadvance.mpvex.domain.postgres.PostgresConnectionChecker
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import app.marlboroadvance.mpvex.domain.postgres.PostgresSslMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostgresSetupViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val stores = mutableListOf<ViewModelStore>()
  private val validConfig = PostgresConfig(
    host = "database.example.com",
    port = 5432,
    database = "media",
    username = "viewer",
    password = "private-password",
  )

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    stores.forEach(ViewModelStore::clear)
    Dispatchers.resetMain()
  }

  private fun model(storage: FakeStorage, checker: FakeChecker, autoConnect: Boolean = true): PostgresSetupViewModel {
    val store = ViewModelStore().also(stores::add)
    return ViewModelProvider(store, PostgresSetupViewModel.factory(storage, checker, autoConnect))
      .get(PostgresSetupViewModel::class.java)
  }

  @Test
  fun `initial loading hides the form and ignores edits and connection requests`() = runTest(dispatcher) {
    val loaded = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig).apply { onLoad = { loaded.await() } }
    val checker = FakeChecker()
    val vm = model(storage, checker, autoConnect = false)
    assertTrue(vm.uiState.value.loading)
    assertFalse(vm.uiState.value.editable)
    vm.updateHost("must-not-replace")
    vm.connect()
    runCurrent()
    assertTrue(vm.uiState.value.loading)
    assertTrue(checker.calls.isEmpty())
    loaded.complete(Unit)
    advanceUntilIdle()
    assertEquals(validConfig.host, vm.uiState.value.host)
    assertFalse(vm.uiState.value.loading)
  }

  @Test
  fun `saved startup configuration automatically checks then saves before passing`() = runTest(dispatcher) {
    val checked = CompletableDeferred<Unit>()
    val saved = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig).apply { onSave = { saved.await() } }
    val checker = FakeChecker { checked.await() }
    val vm = model(storage, checker)
    runCurrent()
    assertEquals(validConfig.password, vm.uiState.value.password)
    assertEquals(listOf(validConfig), checker.calls)
    assertTrue(vm.uiState.value.connecting)
    assertFalse(vm.uiState.value.passed)
    assertTrue(storage.saved.isEmpty())
    checked.complete(Unit)
    runCurrent()
    assertTrue(vm.uiState.value.saving)
    assertFalse(vm.uiState.value.passed)
    saved.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(validConfig), storage.saved)
    assertTrue(vm.uiState.value.passed)
    assertFalse(vm.uiState.value.connecting)
  }

  @Test
  fun `settings load fills fields without automatic check or save`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker()
    val vm = model(storage, checker, autoConnect = false)
    advanceUntilIdle()
    assertEquals(validConfig.host, vm.uiState.value.host)
    assertTrue(vm.uiState.value.editable)
    assertTrue(checker.calls.isEmpty())
    assertTrue(storage.saved.isEmpty())
    assertFalse(vm.uiState.value.passed)
  }

  @Test
  fun `no saved configuration presents secure defaults without checking`() = runTest(dispatcher) {
    val checker = FakeChecker()
    val vm = model(FakeStorage(), checker)
    advanceUntilIdle()
    assertEquals("5432", vm.uiState.value.port)
    assertEquals(PostgresSslMode.VERIFY_FULL, vm.uiState.value.sslMode)
    assertTrue(vm.uiState.value.editable)
    assertFalse(vm.uiState.value.passed)
    assertTrue(checker.calls.isEmpty())
  }

  @Test
  fun `authentication failure keeps editable fields and corrected password can pass`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker { config ->
      if (config.password == validConfig.password) throw PostgresException(PostgresFailure.AUTHENTICATION)
    }
    val vm = model(storage, checker)
    advanceUntilIdle()
    assertEquals(PostgresFailure.AUTHENTICATION, vm.uiState.value.error)
    assertEquals(validConfig.password, vm.uiState.value.password)
    assertFalse(vm.uiState.value.passed)
    assertTrue(storage.saved.isEmpty())
    vm.updatePassword("corrected")
    assertNull(vm.uiState.value.error)
    vm.connect()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.passed)
    assertEquals("corrected", storage.saved.single().password)
  }

  @Test
  fun `cancelled noncooperative check cannot save or pass and fresh retry can succeed`() = runTest(dispatcher) {
    val stale = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker { config ->
      if (config.host == validConfig.host) withContext(NonCancellable) { stale.await() }
    }
    val vm = model(storage, checker)
    runCurrent()
    vm.cancel()
    assertTrue(vm.uiState.value.cancelled)
    assertFalse(vm.uiState.value.passed)
    assertTrue(vm.uiState.value.editable)
    vm.updateHost("new.example.com")
    vm.connect()
    runCurrent()
    assertTrue(vm.uiState.value.passed)
    assertEquals("new.example.com", storage.saved.single().host)
    stale.complete(Unit)
    advanceUntilIdle()
    assertEquals(1, storage.saved.size)
    assertEquals("new.example.com", vm.uiState.value.host)
    assertTrue(vm.uiState.value.passed)
  }

  @Test
  fun `cancel without retry never grants success when stale checker returns`() = runTest(dispatcher) {
    val stale = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig)
    val vm = model(storage, FakeChecker { withContext(NonCancellable) { stale.await() } })
    runCurrent()
    vm.cancel()
    stale.complete(Unit)
    advanceUntilIdle()
    assertFalse(vm.uiState.value.passed)
    assertTrue(storage.saved.isEmpty())
    assertTrue(vm.uiState.value.cancelled)
  }

  @Test
  fun `duplicate submissions and field import mutations while connecting are ignored`() = runTest(dispatcher) {
    val checked = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker { checked.await() }
    val vm = model(storage, checker, autoConnect = false)
    advanceUntilIdle()
    vm.connect()
    vm.connect()
    vm.updateHost("wrong")
    vm.updatePort("1")
    vm.updateDatabase("wrong")
    vm.updateUsername("wrong")
    vm.updatePassword("wrong")
    vm.updateSslMode(PostgresSslMode.DISABLE)
    vm.updateImportText("invalid-paste")
    runCurrent()
    assertEquals(listOf(validConfig), checker.calls)
    assertEquals(validConfig.host, vm.uiState.value.host)
    assertEquals(validConfig.password, vm.uiState.value.password)
    assertEquals(PostgresSslMode.VERIFY_FULL, vm.uiState.value.sslMode)
    assertEquals("", vm.uiState.value.importText)
    checked.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(validConfig), storage.saved)
    vm.connect()
    assertEquals(1, checker.calls.size)
  }

  @Test
  fun `save failure does not pass and retry rechecks before saving`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig).apply { onSave = { throw IllegalStateException("storage unavailable") } }
    val checker = FakeChecker()
    val vm = model(storage, checker)
    advanceUntilIdle()
    assertEquals(PostgresFailure.STORAGE, vm.uiState.value.error)
    assertFalse(vm.uiState.value.passed)
    assertTrue(vm.uiState.value.editable)
    assertTrue(storage.saved.isEmpty())
    storage.onSave = {}
    vm.connect()
    advanceUntilIdle()
    assertEquals(2, checker.calls.size)
    assertTrue(vm.uiState.value.passed)
  }

  @Test
  fun `load failure is visible never checks and can be retried`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig).apply { onLoad = { throw IllegalStateException("read failed") } }
    val checker = FakeChecker()
    val vm = model(storage, checker)
    advanceUntilIdle()
    assertFalse(vm.uiState.value.loading)
    assertEquals(PostgresFailure.LOAD, vm.uiState.value.error)
    assertFalse(vm.uiState.value.passed)
    assertTrue(checker.calls.isEmpty())
    assertTrue(storage.saved.isEmpty())
    storage.onLoad = {}
    vm.retryLoad()
    assertTrue(vm.uiState.value.loading)
    advanceUntilIdle()
    assertTrue(vm.uiState.value.passed)
    assertEquals(2, storage.loadCount)
  }

  @Test
  fun `invalid port never reaches checker or storage and can be corrected`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker()
    val vm = model(storage, checker, autoConnect = false)
    advanceUntilIdle()
    for (invalid in listOf("", "abc", "0", "65536", "999999999999999")) {
      vm.updatePort(invalid)
      vm.connect()
      assertEquals(PostgresFailure.INVALID_CONFIG, vm.uiState.value.error)
      assertTrue(checker.calls.isEmpty())
      assertTrue(storage.saved.isEmpty())
    }
    vm.updatePort("5432")
    vm.connect()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.passed)
  }

  @Test
  fun `invalid paste is retained without damaging fields and corrected paste fills form`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker()
    val vm = model(storage, checker, autoConnect = false)
    advanceUntilIdle()
    vm.updateImportText("postgresql://invalid:%")
    assertEquals(PostgresFailure.INVALID_CONFIG, vm.uiState.value.importError)
    assertEquals("postgresql://invalid:%", vm.uiState.value.importText)
    assertEquals(validConfig.host, vm.uiState.value.host)
    assertEquals(validConfig.password, vm.uiState.value.password)
    vm.updateImportText("postgresql://importer:secret@import.example.com:5444/imported?sslmode=verify-full")
    assertNull(vm.uiState.value.importError)
    assertEquals("", vm.uiState.value.importText)
    assertEquals("import.example.com", vm.uiState.value.host)
    assertEquals("5444", vm.uiState.value.port)
    assertEquals("imported", vm.uiState.value.database)
    assertEquals("importer", vm.uiState.value.username)
    assertEquals("secret", vm.uiState.value.password)
    assertTrue(checker.calls.isEmpty())
    assertTrue(storage.saved.isEmpty())
  }

  @Test
  fun `timeout fails closed without saving`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val vm = model(storage, FakeChecker { delay(Long.MAX_VALUE) })
    runCurrent()
    advanceTimeBy(PostgresSetupViewModel.CONNECTION_TIMEOUT_MILLIS)
    runCurrent()
    assertEquals(PostgresFailure.TIMEOUT, vm.uiState.value.error)
    assertFalse(vm.uiState.value.passed)
    assertTrue(storage.saved.isEmpty())
  }

  @Test
  fun `success callback is consumed once while passed remains true and cold vm rechecks`() = runTest(dispatcher) {
    val storage = FakeStorage(validConfig)
    val checker = FakeChecker()
    val vm = model(storage, checker)
    assertFalse(vm.consumeSuccess())
    advanceUntilIdle()
    assertTrue(vm.consumeSuccess())
    assertFalse(vm.consumeSuccess())
    assertTrue(vm.uiState.value.passed)
    val cold = model(storage, checker)
    assertTrue(cold.uiState.value.loading)
    assertFalse(cold.uiState.value.passed)
    advanceUntilIdle()
    assertEquals(2, checker.calls.size)
    assertTrue(cold.uiState.value.passed)
  }

  @Test
  fun `save commit ignores cancellation and mutations until it finishes`() = runTest(dispatcher) {
    val committed = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig).apply { onSave = { committed.await() } }
    val vm = model(storage, FakeChecker())
    runCurrent()
    assertTrue(vm.uiState.value.saving)
    vm.cancel()
    vm.updatePassword("different")
    vm.connect()
    assertTrue(vm.uiState.value.saving)
    assertFalse(vm.uiState.value.passed)
    assertEquals(validConfig.password, vm.uiState.value.password)
    committed.complete(Unit)
    advanceUntilIdle()
    assertTrue(vm.uiState.value.passed)
    assertEquals(listOf(validConfig), storage.saved)
  }

  @Test
  fun `entry disposal prevents a late checker from persisting or passing`() = runTest(dispatcher) {
    val stale = CompletableDeferred<Unit>()
    val storage = FakeStorage(validConfig)
    val vm = model(storage, FakeChecker { withContext(NonCancellable) { stale.await() } })
    runCurrent()
    stores.last().clear()
    stale.complete(Unit)
    advanceUntilIdle()
    assertTrue(storage.saved.isEmpty())
    assertFalse(vm.uiState.value.passed)
  }

  @Test
  fun `unknown failure never displays exception text containing credentials`() = runTest(dispatcher) {
    val vm = model(FakeStorage(validConfig), FakeChecker { throw IllegalStateException(validConfig.password) })
    advanceUntilIdle()
    assertEquals(PostgresFailure.UNKNOWN, vm.uiState.value.error)
    assertFalse(vm.uiState.value.toString().contains(validConfig.password))
    assertFalse(vm.uiState.value.passed)
  }

  private class FakeStorage(var config: PostgresConfig? = null) : PostgresConfigStorage {
    var loadCount = 0
    val saved = mutableListOf<PostgresConfig>()
    var onLoad: suspend () -> Unit = {}
    var onSave: suspend (PostgresConfig) -> Unit = {}

    override suspend fun load(): PostgresConfig? {
      loadCount++
      onLoad()
      return config
    }

    override suspend fun save(config: PostgresConfig) {
      onSave(config)
      saved += config
      this.config = config
    }
  }

  private class FakeChecker(
    private val behavior: suspend (PostgresConfig) -> Unit = {},
  ) : PostgresConnectionChecker {
    val calls = mutableListOf<PostgresConfig>()

    override suspend fun check(config: PostgresConfig) {
      calls += config
      behavior(config)
    }
  }
}
