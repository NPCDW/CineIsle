package app.marlboroadvance.mpvex.domain.emby

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** Cancelling the coroutine closes the real socket/read, including response-body reads. */
internal object EmbySearchHttp {
  private const val MAX_BODY_BYTES = 2L * 1024 * 1024

  suspend fun body(request: Request, factory: Call.Factory = EmbyHttp.search): String =
    suspendCancellableCoroutine { continuation ->
      val call = factory.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
          try {
            val text = response.use {
              // Do not retain or expose response bodies for HTTP errors (they may contain secrets).
              if (!it.isSuccessful) throw EmbyApiException(it.code, "")
              val source = it.body.source()
              val buffer = Buffer()
              while (continuation.isActive) {
                val count = source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1 - buffer.size))
                if (buffer.size > MAX_BODY_BYTES) throw IOException("Search response exceeds limit")
                if (count == -1L) break
              }
              buffer.readUtf8()
            }
            if (continuation.isActive) continuation.resume(text)
          } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }
        }
      })
    }
}
