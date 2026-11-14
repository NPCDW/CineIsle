package app.marlboroadvance.mpvex.domain.emby

import android.os.Looper
import android.util.Log
import app.marlboroadvance.mpvex.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP pools, safe read retries and row-scoped memory-session 401 recovery. No failover. */
object EmbyHttp {
  private const val TAG = "EmbyHttp"
  private val connectionPool = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)

  @Volatile var onUnauthorized: ((EmbyServer) -> String?)? = null
  @Volatile private var sessionSource: ((EmbyServer) -> EmbyServer)? = null

  /** Resolve only this row's current process session; never use the currently-selected other row. */
  fun installSessionSource(source: (EmbyServer) -> EmbyServer) { sessionSource = source }
  internal fun sessionServer(server: EmbyServer): EmbyServer = sessionSource?.invoke(server) ?: server
  internal fun resetSessionSourceForTests() { sessionSource = null; onUnauthorized = null }

  val shared: OkHttpClient by lazy { baseBuilder().build() }
  val streaming: OkHttpClient by lazy {
    baseBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
  }
  val images: OkHttpClient by lazy {
    baseBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }

  /** Search owns cancellation and explicit suspendable 401 recovery, never the blocking retry loop. */
  val search: OkHttpClient by lazy {
    baseBuilder(retries = false)
      .retryOnConnectionFailure(false)
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .callTimeout(12, TimeUnit.SECONDS)
      .build()
  }

  private fun baseBuilder(retries: Boolean = true): OkHttpClient.Builder = OkHttpClient.Builder()
    .connectionPool(connectionPool)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .addInterceptor { chain ->
      val req = chain.request()
      val server = req.tag(EmbyServer::class.java)
      val updated = if (server == null) req else EmbyRequestHeaders.route(req, sessionServer(server))
      chain.proceed(if (server != null || updated.header("User-Agent") != null) updated else {
        updated.newBuilder().header("User-Agent", EmbyClient.STREAM_USER_AGENT).build()
      })
    }
    .apply { if (retries) addInterceptor(retryInterceptor) }
    .addNetworkInterceptor { chain ->
      val req = chain.request()
      val row = req.tag(EmbyServer::class.java)
      // Refresh this row's identity at every hop, including cross-origin CDN redirects.
      val routed = if (row == null) req else {
        val fresh = sessionServer(row)
        val replayToken = req.tag(ReplayToken::class.java)
        val bound = if (replayToken != null && fresh.apiToken == replayToken.previous) {
          fresh.copy(apiToken = replayToken.value)
        } else fresh
        EmbyRequestHeaders.route(req, bound)
      }
      val response = chain.proceed(routed)
      val location = response.header("Location")
      if (row == null || location == null || response.code !in setOf(300, 301, 302, 303, 307, 308)) response else {
        // Let OkHttp follow the corrected Location with its existing method/body and hop limits.
        val target = EmbyEndpoints.resolveRedirect(row, response.request.url.toString(), location)
        if (target == location) response else response.newBuilder().header("Location", target).build()
      }
    }

  private val retryInterceptor = Interceptor { chain ->
    val req = chain.request()
    val safeRead = req.method == "GET" || req.method == "HEAD"
    var lastError: IOException? = null
    for (attempt in 0 until if (safeRead) MAX_ATTEMPTS else 1) {
      try {
        val response = chain.proceed(req)
        val authentication = req.url.encodedPath.endsWith("/Users/AuthenticateByName")
        val row = req.tag(EmbyServer::class.java)
        val ownResponse = row != null && EmbyEndpoints.belongsToServer(row, response.request.url.toString())
        if (response.code == 401 && ownResponse && attempt == 0 && !authentication && req.body?.isOneShot() != true) {
          val recovery = try {
            val previousToken = sessionServer(row).apiToken
            onUnauthorized?.invoke(row)?.takeIf { it.isNotEmpty() }?.let { ReplayToken(it, previousToken) }
          } catch (error: Exception) { response.close(); throw error }
          if (recovery != null) {
            response.close()
            val replay = req.newBuilder().tag(ReplayToken::class.java, recovery)
            if (req.url.queryParameter("api_key") != null && EmbyEndpoints.belongsToServer(row, req.url.toString())) {
              replay.url(req.url.newBuilder().setQueryParameter("api_key", recovery.value).build())
            }
            return@Interceptor chain.proceed(replay.build())
          }
        }
        if (safeRead && (response.code in 500..599 || response.code == 429) && attempt < MAX_ATTEMPTS - 1) {
          response.close()
          sleepBackoff(attempt)
        } else return@Interceptor response
      } catch (error: IOException) {
        lastError = error
        if (!safeRead || attempt == MAX_ATTEMPTS - 1) throw error
        sleepBackoff(attempt)
      }
    }
    throw lastError ?: IOException("请求失败")
  }

  /** A recovery callback can return a token without installing a session source (e.g. an auxiliary client). */
  private class ReplayToken(val value: String, val previous: String)

  private const val MAX_ATTEMPTS = 3
  private fun sleepBackoff(attempt: Int) {
    if (BuildConfig.DEBUG && Looper.myLooper() === Looper.getMainLooper()) {
      Log.e(TAG, "HTTP retry must run on an IO thread")
    }
    try { Thread.sleep(500L shl attempt) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("请求已取消") }
  }
}
