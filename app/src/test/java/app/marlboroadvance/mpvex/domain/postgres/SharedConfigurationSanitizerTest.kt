package app.marlboroadvance.mpvex.domain.postgres

import android.os.Parcelable
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.io.Serializable
import org.junit.Assert.*
import org.junit.Test

class SharedConfigurationSanitizerTest {
  @Test fun `sanitizer strips all secret canaries controls bidi and desktop only values`() {
    val canaries = listOf(
      "PASSWORD-CANARY", "TOKEN-CANARY", "WEBHOOK-CANARY", "DEVICE-CANARY", "USER-ID-CANARY",
    )
    val raw = rawSnapshot(
      values = mapOf(
        "play_version_auto_select_policy" to "high-resolution\u0000\u202Ehidden",
        "trakt_username" to "account@example",
        "trakt_access_token" to canaries[1],
        "trakt_refresh_token" to "REFRESH-CANARY",
        "trakt_redirect_uri" to "https://private.redirect.example:9443/secret/path?code=${canaries[1]}",
        "simkl_access_token" to "SIMKL-CANARY",
        "yamtrack_sync_url" to "https://hook.private.example/webhooks/${canaries[2]}",
      ),
      server = serverRaw(
        password = canaries[0], authToken = canaries[1], userId = canaries[4],
        deviceId = canaries[3], userAgent = "agent/${canaries[3]}",
      ),
    )
    val snapshot = SharedConfigurationSanitizer.sanitize(raw)
    val rendered = render(snapshot)

    canaries.forEach { assertFalse(it, rendered.contains(it)) }
    listOf("REFRESH-CANARY", "SIMKL-CANARY", "private.redirect", "secret/path", "hook.private", "webhooks").forEach {
      assertFalse(it, rendered.contains(it))
    }
    assertFalse(rendered.contains('\u0000'))
    assertFalse(rendered.contains('\u202E'))
    assertFalse(rendered.contains("desktop_only"))
    assertTrue(rendered.contains("已配置"))
    assertTrue(rendered.contains("含路径前缀"))
    assertTrue(rendered.contains("https://p***e.redirect.example:9443"))
    assertFalse(Serializable::class.java.isAssignableFrom(snapshot.javaClass))
    assertFalse(Parcelable::class.java.isAssignableFrom(snapshot.javaClass))
    assertFalse(snapshot.toString().contains("account"))
  }

  @Test fun `endpoint exposes only sanitized scheme host port and path presence`() {
    assertEquals("https://m***a.example:8443 · 含路径前缀", SharedConfigurationSanitizer.sanitizeEndpoint("https://media.example:8443/emby?q=token#fragment"))
    assertEquals("http://l***t · 无路径前缀", SharedConfigurationSanitizer.sanitizeEndpoint("http://localhost"))
    assertEquals("https://[IPv6]:9443 · 含路径前缀", SharedConfigurationSanitizer.sanitizeEndpoint("https://[2001:db8::7]:9443/prefix"))
    assertEquals("地址无效", SharedConfigurationSanitizer.sanitizeEndpoint("https://user:password@example.com/path"))
    assertEquals("未配置", SharedConfigurationSanitizer.sanitizeEndpoint(""))
  }

  @Test fun `server projection shows approved session line reverse route and credential statuses only`() {
    val row = serverRaw()
    val raw = rawSnapshot(server = row)
    val shared = EmbyServer(
      name = "Media", host = "media.example", username = "u", password = "p", userId = row.userId,
      apiToken = row.authToken, remoteId = row.id, sourceKey = raw.source.sourceKey, sourceRevision = raw.source.revision,
    )
    val relogin = shared.copy(apiToken = "memory-token", userId = "memory-user")
    val sharedText = render(SharedConfigurationSanitizer.sanitize(raw, mapOf(row.id to shared)))
    val reloginText = render(SharedConfigurationSanitizer.sanitize(raw, mapOf(row.id to relogin)))
    val loggedOutText = render(SharedConfigurationSanitizer.sanitize(raw))

    assertTrue(sharedText.contains("共享 Token"))
    assertTrue(reloginText.contains("本进程重登录"))
    assertTrue(loggedOutText.contains("未登录"))
    assertTrue(sharedText.contains("引用有效"))
    assertTrue(sharedText.contains("反向代理"))
    assertFalse(sharedText.contains("浏览代理"))
    assertFalse(sharedText.contains("播放代理"))
    assertTrue(sharedText.contains("桌面端已应用到服务器行"))
    assertFalse(sharedText.contains("server-password"))
    assertFalse(sharedText.contains("shared-token"))
    assertFalse(sharedText.contains("shared-user-id"))
  }

  @Test fun `unknown global key is rejected instead of displayed`() {
    val raw = rawSnapshot().copy(values = mapOf("mpv_volume" to "87"))
    try {
      SharedConfigurationSanitizer.sanitize(raw)
      fail("Expected unknown-key rejection")
    } catch (_: IllegalArgumentException) {
      // exact allowlist boundary
    }
  }

  private fun render(snapshot: SharedConfigurationSnapshot): String = buildString {
    snapshot.sections.forEach { section ->
      append(section.title); append(section.summary)
      section.items.forEach { append(it.label); append(it.value); append(it.info) }
    }
    snapshot.servers.forEach { server ->
      append(server.title); append(server.summary)
      server.sections.forEach { section ->
        append(section.title)
        section.items.forEach { append(it.label); append(it.value); append(it.info) }
      }
    }
  }

  private fun rawSnapshot(
    values: Map<String, String> = emptyMap(),
    server: SharedServerRaw = serverRaw(),
  ) = SharedConfigurationRawSnapshot(
    values = values,
    servers = listOf(server),
    lines = listOf(SharedLineRaw("line-id", "Primary", server.id)),
    reverseProxies = mapOf("reverse-id" to SharedReverseProxyRaw("reverse-id", "Gateway", "https://gateway.example/reverse/path")),
    source = PostgresSourceToken("source", 3),
  )

  private fun serverRaw(
    password: String = "server-password",
    authToken: String = "shared-token",
    userId: String = "shared-user-id",
    deviceId: String = "device-id",
    userAgent: String = "user-agent",
  ) = SharedServerRaw(
    id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    createdAtMillis = 1_700_000_000_000L,
    baseUrl = "https://media.private.example:9443/emby",
    username = "viewer@example",
    password = password,
    serverName = "Media\u0007Server",
    authToken = authToken,
    userId = userId,
    client = "loemby",
    device = "Android",
    deviceId = deviceId,
    clientVersion = "1.0",
    userAgent = userAgent,
    orderBy = 1,
    disabled = false,
    iconUrl = "https://icons.private.example/art/server.png",
    lineId = "line-id",
    reverseProxyId = "reverse-id",
  )
}
