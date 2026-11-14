package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import com.github.luben.zstd.ZstdInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream

/** Independent loemby pool. No EmbyHttp interceptor, URL rewrite, proxy selection or implicit global session. */
class LoopbackHttpPool {
  enum class Kind { STREAM, IMAGE, API }
  private data class Key(val kind: Kind, val invalidCerts: Boolean)
  private val clients = ConcurrentHashMap<Key, OkHttpClient>()

  fun client(kind: Kind = Kind.API, dangerAcceptInvalidCerts: Boolean = false): OkHttpClient =
    clients.computeIfAbsent(Key(kind, dangerAcceptInvalidCerts)) {
      val builder = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(6, 90, TimeUnit.SECONDS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(if (kind == Kind.STREAM) 0 else 30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor(DecodeContent())
        // This pool must not inherit a process or platform proxy.
        .proxy(Proxy.NO_PROXY)
      if (dangerAcceptInvalidCerts) {
        val trust = object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
          override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
          override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
          init(null, arrayOf<TrustManager>(trust), SecureRandom())
        }
        builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }
      }
      builder.build()
    }

  /** Six loemby headers, including across hosts; deliberately no combined Authorization/Referer. */
  fun identityHeaders(server: EmbyServer): Map<String, String> = linkedMapOf(
    "User-Agent" to server.userAgent,
    "X-Emby-Token" to server.apiToken,
    "X-Emby-Client" to server.client,
    "X-Emby-Device-Name" to server.device,
    "X-Emby-Device-Id" to server.deviceId,
    "X-Emby-Client-Version" to server.clientVersion,
  )

  suspend fun execute(
    server: EmbyServer? = null,
    url: String,
    method: String = "GET",
    body: String? = null,
    kind: Kind = Kind.API,
    headers: Map<String, String> = emptyMap(),
    dangerAcceptInvalidCerts: Boolean = false,
    retry: Boolean = true,
  ): Response {
    val builder = Request.Builder().url(url)
    builder.header("Accept-Encoding", "deflate, gzip, br, zstd")
    headers.filterKeys { it.lowercase() !in HOP_BY_HOP }.forEach { (name, value) -> builder.header(name, value) }
    server?.let { identityHeaders(it).forEach { (name, value) -> builder.header(name, value) } }
    val verb = method.uppercase()
    val requestBody = body?.toRequestBody(JSON_TYPE)
      ?: if (verb in setOf("POST", "PUT", "PATCH")) "{}".toRequestBody(JSON_TYPE) else null
    var request = builder.method(verb, requestBody).build()
    val client = client(kind, dangerAcceptInvalidCerts)
    var attempt = 0
    var redirects = 0
    while (true) {
      try {
        val response = client.newCall(request).await()
        if (retry && response.code in TRANSIENT_STATUS && attempt < 3) {
          response.close()
        } else {
          if (kind == Kind.STREAM || response.code !in setOf(301, 302, 303, 307, 308)) return response
          val location = response.header("Location") ?: return response
          val next = response.request.url.resolve(location) ?: return response
          if (redirects >= 10) {
            response.close()
            throw java.net.ProtocolException("Too many redirects")
          }
          val status = response.code
          response.close()
          val nextRequest = request.newBuilder().url(next)
          if (status == 303 || ((status == 301 || status == 302) && request.method == "POST")) {
            nextRequest.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
          }
          // Unlike OkHttp defaults, retain the bound row's six headers on every host.
          request = nextRequest.build()
          redirects++
          attempt = 0
          continue
        }
      } catch (failure: IOException) {
        // TLS validation and invalid protocol are not transient.
        if (!retry || attempt >= 3 || failure is javax.net.ssl.SSLException ||
          failure is java.net.ProtocolException) {
          throw failure
        }
      }
      delay(1000L shl attempt)
      attempt++
    }
  }

  suspend fun text(
    server: EmbyServer? = null,
    url: String,
    method: String = "GET",
    body: String? = null,
    kind: Kind = Kind.API,
    headers: Map<String, String> = emptyMap(),
    dangerAcceptInvalidCerts: Boolean = false,
  ): String = execute(server, url, method, body, kind, headers, dangerAcceptInvalidCerts).use {
    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
    it.body.string()
  }

  fun close() {
    clients.values.forEach {
      it.dispatcher.cancelAll()
      it.connectionPool.evictAll()
      it.dispatcher.executorService.shutdown()
    }
    clients.clear()
  }

  private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (!continuation.isCancelled) continuation.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        continuation.resume(response) { _, value, _ -> value.close() }
      }
    })
  }

  private class DecodeContent : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val response = chain.proceed(chain.request())
      val encoding = response.header("Content-Encoding")?.lowercase()?.trim() ?: return response
      if (encoding !in setOf("gzip", "deflate", "br", "zstd") || response.request.method == "HEAD" ||
        response.code in setOf(204, 304)) {
        return response
      }
      val original = response.body
      val source = object : ResponseBody() {
        private val decoded: BufferedSource by lazy {
          val input = original.byteStream()
          val stream: InputStream = when (encoding) {
            "gzip" -> GZIPInputStream(input)
            "br" -> BrotliInputStream(input)
            "zstd" -> ZstdInputStream(input)
            else -> {
              val peek = java.io.PushbackInputStream(input, 2)
              val a = peek.read()
              val b = peek.read()
              if (b >= 0) peek.unread(b)
              if (a >= 0) peek.unread(a)
              val zlib = a >= 0 && b >= 0 && (a and 15) == 8 && ((a shl 8) + b) % 31 == 0
              InflaterInputStream(peek, Inflater(!zlib))
            }
          }
          stream.source().buffer()
        }
        override fun contentType() = original.contentType()
        override fun contentLength() = -1L
        override fun source() = decoded
      }
      return response.newBuilder()
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .body(source)
        .build()
    }
  }

  companion object {
    val HOP_BY_HOP = setOf(
      "connection",
      "keep-alive",
      "proxy-connection",
      "transfer-encoding",
      "upgrade",
      "te",
      "trailer",
      "proxy-authenticate",
      "proxy-authorization",
    )
    private val TRANSIENT_STATUS = setOf(408, 429, 500, 502, 503, 504)
    private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
  }
}
