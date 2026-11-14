package app.marlboroadvance.mpvex.utils.media

import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic responses only: no network, native MediaInfo, Android context or real DB. */
class AuxiliaryHttpHeadersTest {
  @get:Rule val temporaryFolder = TemporaryFolder()
  @Before fun before() { EmbyHttp.resetSessionSourceForTests() }
  @After fun after() { EmbyHttp.resetSessionSourceForTests() }

  private val server = EmbyServer(
    id = 8, name = "Auxiliary", host = "emby.test", username = "u", password = "p",
    configuredBaseUrl = "https://emby.test/custom/api", apiToken = "fixture-token", userAgent = "DB-UA/8",
  )

  private fun client(
    requests: MutableList<Request>,
    code: Int = 200,
    content: ByteArray = "1\n00:00:00,000 --> 00:00:01,000\nfixture".toByteArray(),
    declaredLength: Long? = null,
  ) = OkHttpClient.Builder().addInterceptor { chain ->
    val request = chain.request()
    requests += request
    val body = if (declaredLength == null) content.toResponseBody() else object : okhttp3.ResponseBody() {
      override fun contentType(): okhttp3.MediaType? = null
      override fun contentLength(): Long = declaredLength
      override fun source(): okio.BufferedSource = okio.Buffer().write(content)
    }
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
      .header("Content-Disposition", "attachment; filename=fixture.srt").body(body).build()
  }.build()

  @Test fun `filename HEAD uses bound identity and ignores stale caller identity`() {
    val requests = mutableListOf<Request>()
    assertEquals("fixture.srt", HttpUtils.getFilenameFromHttpHeaders(
      "${server.baseUrl}/stream", server,
      mapOf("X-Emby-Token" to "wrong", "User-Agent" to "wrong"), client(requests),
    ))
    val request = requests.single()
    assertEquals("HEAD", request.method)
    assertEquals(server.apiToken, request.header("X-Emby-Token"))
    assertEquals(server.userAgent, request.header("User-Agent"))
    assertEquals(server.id, request.tag(EmbyServer::class.java)?.id)
  }

  @Test fun `filename HEAD to a bound CDN uses complete identity and retains ordinary authorization`() {
    val requests = mutableListOf<Request>()
    HttpUtils.getFilenameFromHttpHeaders("https://cdn.test/stream?signature=a%2Fb", server,
      mapOf("X-Emby-Token" to "wrong", "Authorization" to "ordinary-auth", "Cookie" to "ordinary-cookie=1"),
      client(requests))
    val request = requests.single()
    assertEquals("HEAD", request.method)
    assertEquals(server.apiToken, request.header("X-Emby-Token"))
    assertEquals(server.client, request.header("X-Emby-Client"))
    assertEquals(server.device, request.header("X-Emby-Device-Name"))
    assertEquals(server.deviceId, request.header("X-Emby-Device-Id"))
    assertEquals(server.clientVersion, request.header("X-Emby-Client-Version"))
    assertEquals(server.userAgent, request.header("User-Agent"))
    assertEquals(EmbyClient.authorizationHeader(server.authIdentity), request.header("X-Emby-Authorization"))
    assertEquals(server.hostUrl, request.header("Referer"))
    assertEquals("ordinary-auth", request.header("Authorization"))
    assertEquals("ordinary-cookie=1", request.header("Cookie"))
    assertEquals(server.id, request.tag(EmbyServer::class.java)?.id)
    assertEquals("signature=a%2Fb", request.url.encodedQuery)
    assertNull(request.url.queryParameter("api_key"))
  }

  @Test fun `explicit Emby subtitle is local after controlled row-scoped download`() = runBlocking {
    val requests = mutableListOf<Request>()
    val directory = temporaryFolder.newFolder()
    val file = SubtitleOps.downloadToFile("${server.baseUrl}/subtitle.ass", server, directory, client(requests))
    assertNotNull(file)
    assertEquals("ass", file!!.extension)
    assertTrue(file.readText().contains("fixture"))
    assertEquals(server.apiToken, requests.single().header("X-Emby-Token"))
    assertEquals(server.id, requests.single().tag(EmbyServer::class.java)?.id)
  }

  @Test fun `different server subtitle is never fetched with the bound token`() = runBlocking {
    val requests = mutableListOf<Request>()
    assertNull(SubtitleOps.downloadToFile("https://other.test/sub.srt", server,
      temporaryFolder.newFolder(), client(requests)))
    assertTrue(requests.isEmpty())
  }

  @Test fun `subtitle HTTP failure removes partial cache file`() = runBlocking {
    val directory = temporaryFolder.newFolder()
    assertNull(SubtitleOps.downloadToFile("${server.baseUrl}/sub.srt", server, directory,
      client(mutableListOf(), code = 403)))
    assertTrue(directory.listFiles()!!.isEmpty())
  }

  @Test fun `subtitle unknown length is bounded while streaming and removes partial output`() = runBlocking {
    val directory = temporaryFolder.newFolder()
    assertNull(SubtitleOps.downloadToFile("${server.baseUrl}/sub.srt", server, directory,
      client(mutableListOf(), content = ByteArray(16 * 1024 * 1024 + 1), declaredLength = -1)))
    assertTrue(directory.listFiles()!!.isEmpty())
  }

  @Test fun `subtitle oversized declared body and empty body leave no cache files`() = runBlocking {
    val directory = temporaryFolder.newFolder()
    assertNull(SubtitleOps.downloadToFile("${server.baseUrl}/sub.srt", server, directory,
      client(mutableListOf(), declaredLength = 16L * 1024 * 1024 + 1)))
    assertNull(SubtitleOps.downloadToFile("${server.baseUrl}/sub.srt", server, directory,
      client(mutableListOf(), content = ByteArray(0))))
    assertTrue(directory.listFiles()!!.isEmpty())
  }
}
