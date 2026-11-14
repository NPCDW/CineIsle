package app.marlboroadvance.mpvex.domain.emby

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Real Call contract, but no sockets, external servers, credentials or Android runtime required. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbySearchHttpTest {
  private class FakeCall(private val req: Request) : Call {
    var callback: Callback? = null
    var cancelled = false
    var enqueued = 0
    override fun request() = req
    override fun execute(): Response = error("Search must enqueue, not block execute")
    override fun enqueue(responseCallback: Callback) { enqueued++; callback = responseCallback }
    override fun cancel() { cancelled = true }
    override fun isExecuted() = enqueued > 0
    override fun isCanceled() = cancelled
    override fun timeout() = Timeout()
    override fun clone(): Call = FakeCall(req)
    private val tags = mutableMapOf<Class<*>, Any>()
    override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = tag(type.java)
    @Suppress("UNCHECKED_CAST")
    override fun <T> tag(type: Class<out T>): T? = tags[type] as T?
    override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
      tag(type.java, computeIfAbsent)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
      tags.getOrPut(type, computeIfAbsent) as T
    fun response(code: Int, body: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
      .code(code).message("fixture").body(body.toResponseBody()).build()
  }
  private val request = Request.Builder().url("https://fixture.test/Items").build()

  @Test fun coroutineCancellationCancelsCallAndLateFailureCannotRetry() = runTest {
    val call = FakeCall(request)
    var calls = 0
    val result = async { EmbySearchHttp.body(request, Call.Factory { calls++; call }) }
    runCurrent()
    assertEquals(1, call.enqueued)
    result.cancel()
    result.join()
    assertTrue(call.cancelled)
    call.callback!!.onFailure(call, IOException("cancelled"))
    runCurrent()
    assertEquals(1, calls)
    assertTrue(result.isCancelled)
  }

  @Test fun timeoutCancelsActualCallRatherThanAbandoningBlockingWork() = runTest {
    val call = FakeCall(request)
    val result = async { withTimeoutOrNull(100) { EmbySearchHttp.body(request, Call.Factory { call }) } }
    runCurrent()
    advanceTimeBy(101)
    runCurrent()
    assertNull(result.await())
    assertTrue(call.cancelled)
    assertEquals(1, call.enqueued)
  }

  @Test fun httpFailurePreservesCodeButNeverRetainsResponseSecret() = runTest {
    val call = FakeCall(request)
    val result = async {
      try { EmbySearchHttp.body(request, Call.Factory { call }); null }
      catch (failure: EmbyApiException) { failure }
    }
    runCurrent()
    call.callback!!.onResponse(call, call.response(503, "private token, account and url"))
    val error = result.await()!!
    assertEquals(503, error.code)
    assertEquals("", error.body)
    assertFalse(error.message.orEmpty().contains("private"))
    assertEquals(1, call.enqueued)
  }

  @Test fun successfulResponseIsReturnedAndOversizedBodyFailsWithoutRetry() = runTest {
    val success = FakeCall(request)
    val result = async { EmbySearchHttp.body(request, Call.Factory { success }) }
    runCurrent()
    success.callback!!.onResponse(success, success.response(200, "{\"Items\":[]}"))
    assertEquals("{\"Items\":[]}", result.await())

    val tooLarge = FakeCall(request)
    val failure = async {
      try { EmbySearchHttp.body(request, Call.Factory { tooLarge }); false }
      catch (_: IOException) { true }
    }
    runCurrent()
    tooLarge.callback!!.onResponse(tooLarge, tooLarge.response(200, "x".repeat(2 * 1024 * 1024 + 1)))
    assertTrue(failure.await())
    assertEquals(1, tooLarge.enqueued)
  }
}
