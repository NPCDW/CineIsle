package app.marlboroadvance.mpvex.domain.emby.playback

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.postgres.PlaybackConfiguration
import app.marlboroadvance.mpvex.domain.postgres.playbackConfigurationForTest
import com.github.luben.zstd.Zstd
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** Real JVM CIO socket and real HTTP upstream, not a mocked rewriteHls result. */
class LoopbackPlaybackServerTest {
  private val browser = OkHttpClient.Builder().followRedirects(false).build()
  private fun exchange(code: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): (HttpExchange) -> Unit = { call ->
    headers.forEach { (name, value) -> call.responseHeaders.add(name, value) }
    call.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
    call.responseBody.use { it.write(body) }
  }

  private class Fixture : AutoCloseable {
    val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    val directory = Files.createTempDirectory("loemby-loopback-test").toFile()
    val calls = AtomicInteger()
    var playbackBody = ""; var payload = ""
    val server: EmbyServer
    var config: PlaybackConfiguration
    val local: LoopbackPlaybackServer
    val origin: String get() = "http://127.0.0.1:${upstream.address.port}"
    init {
      upstream.executor = executor
      server = EmbyServer(name = "test", host = "127.0.0.1", port = upstream.address.port,
        username = "account", password = "password", apiToken = "secret", userId = "user",
        remoteId = "uuid", sourceKey = "source", sourceRevision = 1,
        client = "client", device = "device", deviceId = "deviceid", clientVersion = "version", userAgent = "agent")
      config = playbackConfigurationForTest(
        source = app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken("source", 1),
        serverUuid = "uuid",
      )
      local = LoopbackPlaybackServer(directory, { it }, { it }, { config })
      payload = """{"PlaySessionId":"session","MediaSources":[{"Id":"media","Name":"1080p","Container":"mkv","DirectStreamUrl":"/video","MediaStreams":[]}]}"""
      upstream.createContext("/emby/Items/item/PlaybackInfo") {
        calls.incrementAndGet(); playbackBody = it.requestBody.bufferedReader().readText()
        Thread.sleep(25)
        val bytes = payload.toByteArray(); it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.use { out -> out.write(bytes) }
      }
      upstream.start()
    }
    override fun close() {
      runBlocking { local.stop() }; upstream.stop(0); executor.shutdownNow(); directory.deleteRecursively()
    }
  }

  private fun get(url: String, range: String? = null) = browser.newCall(Request.Builder().url(url)
    .apply { range?.let { header("Range", it) } }.build()).execute()

  @Test fun singleFlightPostRedirectResumeOwnershipAndRelease() = runBlocking {
    Fixture().use { f ->
      val play = f.local.register(PlaybackSpec(f.server, "item", positionTicks = 123))
      val results = List(12) { async { f.local.resolvedForUrl(play) } }.awaitAll()
      assertEquals(1, f.calls.get()); assertEquals(1, results.map { it.videoUrl }.distinct().size)
      assertTrue(f.playbackBody.contains("\"MaxStreamingBitrate\":1400000000"))
      assertTrue(f.playbackBody.contains("\"EnableTranscoding\":false"))
      get(play).use { assertEquals(308, it.code); assertEquals(results.first().videoUrl, it.header("Location")) }
      assertTrue(f.local.ownsMediaUrl(play, results.first().videoUrl))
      assertFalse(f.local.ownsMediaUrl(play, results.first().videoUrl + "?wrong"))
      assertFalse(f.local.isLocalPlayUrl(play.replace("127.0.0.1", "localhost")))
      assertFalse(f.local.isLocalPlayUrl(play.substringBeforeLast('/') + "/-1"))
      f.local.setResume(play, 456); assertEquals(456L, f.local.resolvedForUrl(play).spec.positionTicks)
      f.local.clearResume(play); assertEquals(0L, f.local.resolvedForUrl(play).spec.positionTicks)
      f.local.release(play); assertTrue(f.local.isLocalPlayUrl(play))
      f.local.resolvedForUrl(play); assertEquals(2, f.calls.get())
    }
  }

  @Test fun rangeResponseStreamsLengthAndSixHeadersWithoutHopHeaders() = runBlocking {
    Fixture().use { f ->
      f.upstream.createContext("/binary") { call ->
        assertEquals("bytes=2-4", call.requestHeaders.getFirst("Range"))
        assertEquals("1", call.requestHeaders.getFirst("icy-metadata"))
        assertEquals("*/*", call.requestHeaders.getFirst("Accept"))
        f.local.http.identityHeaders(f.server).forEach { (name, value) -> assertEquals(name, value, call.requestHeaders.getFirst(name)) }
        assertNull(call.requestHeaders.getFirst("X-Emby-Authorization"))
        exchange(206, "cde".toByteArray(), mapOf("Content-Range" to "bytes 2-4/8", "Keep-Alive" to "timeout=30", "Connection" to "close"))(call)
      }
      val stream = f.local.registerStream(f.server, f.origin + "/binary", "video")
      get(stream, "bytes=2-4").use { response ->
        assertEquals(206, response.code); assertEquals("3", response.header("Content-Length"))
        assertEquals("bytes 2-4/8", response.header("Content-Range"))
        assertNull(response.header("Keep-Alive")); assertEquals("cde", response.body.string())
      }
      get(stream.substringBeforeLast('/') + "/missing").use { assertEquals(404, it.code); assertEquals("没有找到对应的流媒体", it.body.string()) }
    }
  }

  @Test fun streamRedirectKeepsIdentityResolvesRelativeLocationAndTenAllowed() = runBlocking {
    Fixture().use { f ->
      f.upstream.createContext("/absolute", exchange(302, headers = mapOf("Location" to f.origin.replace("127.0.0.1", "localhost") + "/final")))
      f.upstream.createContext("/relative", exchange(302, headers = mapOf("Location" to "/final")))
      f.upstream.createContext("/missing", exchange(302))
      f.upstream.createContext("/final") {
        assertEquals("secret", it.requestHeaders.getFirst("X-Emby-Token")); exchange(200, "done".toByteArray())(it)
      }
      for (n in 0..11) f.upstream.createContext("/r$n", if (n == 0) exchange(200, "end".toByteArray())
        else exchange(302, headers = mapOf("Location" to "${f.origin}/r${n - 1}")))
      suspend fun stream(path: String) = f.local.registerStream(f.server, f.origin + path, "video")
      get(stream("/absolute")).use { assertEquals("done", it.body.string()) }
      get(stream("/relative")).use { assertEquals(200, it.code); assertEquals("done", it.body.string()) }
      get(stream("/missing")).use { assertEquals("手动重定向失败，媒体流响应没有 Location 头", it.body.string()) }
      get(stream("/r10")).use { assertEquals("end", it.body.string()) }
      get(stream("/r11")).use { assertEquals(503, it.code); assertEquals("重定向次数过多", it.body.string()) }
    }
  }

  @Test fun allFourHlsMimeRewriteRelativeLinesAndUriAttributesWithOwnership() = runBlocking {
    Fixture().use { f ->
      val text = """#EXTM3U\r
#EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"\r
#EXT-X-MAP:URI="/init.mp4"\r
child/index.m3u8\r
segments/one.ts\r
/root.ts\r
${f.origin}/absolute.ts\r
"""
      val types = listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl")
      types.forEachIndexed { i, mime -> f.upstream.createContext("/manifests/hls$i", exchange(200, text.toByteArray(), mapOf("Content-Type" to "$mime; charset=utf-8"))) }
      val payloads = linkedMapOf(
        "/manifests/keys/key.bin" to "key",
        "/init.mp4" to "init",
        "/manifests/child/index.m3u8" to "child",
        "/manifests/segments/one.ts" to "one",
        "/root.ts" to "root",
        "/absolute.ts" to "absolute",
      )
      payloads.forEach { (path, payload) -> f.upstream.createContext(path, exchange(200, payload.toByteArray())) }
      for (i in types.indices) {
        val play = f.local.register(PlaybackSpec(f.server, "item", overrideUrl = "${f.origin}/manifests/hls$i"))
        val result = f.local.resolvedForUrl(play)
        get(result.videoUrl).use {
          assertEquals(200, it.code)
          assertEquals("${types[i]}; charset=utf-8", it.header("Content-Type"))
          val body = it.body.string()
          assertFalse(body.contains("keys/key.bin")); assertFalse(body.contains("/init.mp4\""))
          assertFalse(body.contains("child/index.m3u8")); assertFalse(body.contains("segments/one.ts"))
          assertFalse(body.contains(f.origin + "/absolute.ts"))
          val urls = Regex("http://127\\.0\\.0\\.1:[0-9]+/(?:stream/[^/]+/[^\\s\"]+)")
            .findAll(body).map { match -> match.value }.toList()
          assertEquals(payloads.size, urls.size)
          urls.forEach { assertTrue(f.local.ownsMediaUrl(play, it)) }
          assertEquals(payloads.values.toSet(), urls.map { local -> get(local).use { child -> child.body.string() } }.toSet())
        }
      }
    }
  }

  @Test fun subtitleHitMissAndPrecacheOnlyComplete200Files() = runBlocking {
    Fixture().use { f ->
      val subtitles = AtomicInteger()
      f.upstream.createContext("/subtitle") { subtitles.incrementAndGet(); exchange(200, "subtitle".toByteArray())(it) }
      val upstream = f.origin + "/subtitle"
      val local = f.local.registerSubtitle(f.server, upstream)
      get(local).use { assertEquals("subtitle", it.body.string()) }
      assertFalse(f.local.subtitleCache.fileForUrl(upstream).exists())
      f.local.subtitleCache.precache(f.local.scope, f.server, upstream)!!.join()
      assertTrue(f.local.subtitleCache.fileForUrl(upstream).isFile)
      get(local).use { assertEquals(200, it.code); assertEquals("subtitle", it.body.string()); assertNull(it.header("Content-Type")) }
      assertEquals(2, subtitles.get())
      f.upstream.createContext("/failed", exchange(404, "missing".toByteArray()))
      f.local.subtitleCache.precache(f.local.scope, f.server, f.origin + "/failed")!!.join()
      assertFalse(f.local.subtitleCache.fileForUrl(f.origin + "/failed").exists())
      assertFalse(f.directory.walk().any { it.name.endsWith(".tmp") })
      f.local.subtitleCache.clear(); assertFalse(f.local.subtitleCache.fileForUrl(upstream).exists())
    }
  }

  @Test fun freshPlaybackInfoAutoSelectionValidatesSelectedVersionNotStaleFirstSource() = runBlocking {
    Fixture().use { f ->
      f.config = playbackConfigurationForTest(
        values = mapOf("play_version_auto_select_policy" to "high-resolution"),
        source = app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken("source", 1),
        serverUuid = "uuid",
      )
      f.payload = """{"PlaySessionId":"s","MediaSources":[
        {"Id":"iso","Name":"ISO","Container":"iso","MediaStreams":[{"Type":"Video","Width":640,"Height":360}]},
        {"Id":"video","Name":"4K","Container":"mkv","DirectStreamUrl":"/selected","MediaStreams":[{"Type":"Video","Width":3840,"Height":2160}]}
      ]}"""
      val play = f.local.register(PlaybackSpec(f.server, "item"))
      val resolved = f.local.resolvedForUrl(play)
      assertEquals("video", resolved.source.Id)
      assertEquals(1, resolved.sourceIndex)
      assertTrue(resolved.videoUrl.startsWith("http://127.0.0.1:"))

      val explicit = f.local.register(PlaybackSpec(f.server, "item", explicitMediaSourceId = "iso"))
      try { f.local.resolvedForUrl(explicit); fail() } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun preplayStartsExternalSubtitleCacheAndIsoSelectedDoesNotFallback() = runBlocking {
    Fixture().use { f ->
      f.payload = """{"PlaySessionId":"s","MediaSources":[{"Id":"media","Container":"mkv","MediaStreams":[{"Index":4,"Type":"Subtitle","IsExternal":true,"Codec":"ass"}]}]}"""
      f.upstream.createContext("/emby/Videos/item/media/Subtitles/4/Stream.ass", exchange(200, "cached".toByteArray()))
      val play = f.local.register(PlaybackSpec(f.server, "item"))
      f.local.resolvedForUrl(play)
      val target = f.local.subtitleCache.fileForUrl(f.origin + "/emby/Videos/item/media/Subtitles/4/Stream.ass")
      withTimeout(3000) { while (!target.exists()) delay(10) }
      assertEquals("cached", target.readText())
      f.payload = """{"MediaSources":[{"Id":"iso","Container":"iso"},{"Id":"ordinary","Container":"mkv"}]}"""
      val iso = f.local.register(PlaybackSpec(f.server, "item", explicitMediaSourceId = "iso"))
      try { f.local.resolvedForUrl(iso); fail() } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun directHttpApiImageFollowAndThirdPartyPostNoIdentityAndRetryFalse() = runBlocking {
    Fixture().use { f ->
      f.upstream.createContext("/redirect", exchange(302, headers = mapOf("Location" to "target")))
      f.upstream.createContext("/target") { assertEquals("secret", it.requestHeaders.getFirst("X-Emby-Token")); exchange(200, "ok".toByteArray())(it) }
      f.local.http.execute(f.server, f.origin + "/redirect", kind = LoopbackHttpPool.Kind.IMAGE).use { assertEquals("ok", it.body.string()) }
      val calls = AtomicInteger()
      f.upstream.createContext("/third") {
        calls.incrementAndGet(); assertEquals("POST", it.requestMethod)
        assertNull(it.requestHeaders.getFirst("X-Emby-Token"))
        assertEquals("{}", it.requestBody.bufferedReader().readText()); exchange(429)(it)
      }
      f.local.http.execute(url = f.origin + "/third", method = "POST", retry = false).use { assertEquals(429, it.code) }
      assertEquals(1, calls.get())
      assertEquals(0, f.local.http.client(kind = LoopbackHttpPool.Kind.STREAM).readTimeoutMillis)
      assertEquals(30000, f.local.http.client(kind = LoopbackHttpPool.Kind.IMAGE).readTimeoutMillis)
      assertNotSame(f.local.http.client(kind = LoopbackHttpPool.Kind.IMAGE), f.local.http.client(kind = LoopbackHttpPool.Kind.API))
      listOf(LoopbackHttpPool.Kind.STREAM, LoopbackHttpPool.Kind.IMAGE, LoopbackHttpPool.Kind.API).forEach {
        assertEquals(java.net.Proxy.NO_PROXY, f.local.http.client(kind = it).proxy)
      }
    }
  }

  @Test fun compressedBodiesDecodedAndUpstreamLengthNotForwarded() = runBlocking {
    Fixture().use { f ->
      val bytes = "decompressed".toByteArray()
      fun encoded(write: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(write).toByteArray()
      val variants = mapOf(
        "gzip" to encoded { GZIPOutputStream(it).use { out -> out.write(bytes) } },
        "deflate" to encoded { DeflaterOutputStream(it).use { out -> out.write(bytes) } },
        "zstd" to Zstd.compress(bytes),
        // Brotli uncompressed meta-block encoding of "hello".
        "br" to byteArrayOf(0x0b, 0x02, 0x80.toByte(), 0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x03),
      )
      for ((encoding, data) in variants) {
        f.upstream.createContext("/encoded/$encoding", exchange(200, data, mapOf("Content-Encoding" to encoding)))
        val stream = f.local.registerStream(f.server, "${f.origin}/encoded/$encoding", "video")
        get(stream).use {
          assertNull(it.header("Content-Encoding")); assertNull(it.header("Content-Length"))
          assertEquals(if (encoding == "br") "hello" else "decompressed", it.body.string())
        }
      }
    }
  }
}
