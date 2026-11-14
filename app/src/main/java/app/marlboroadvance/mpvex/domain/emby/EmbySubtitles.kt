package app.marlboroadvance.mpvex.domain.emby

/** Read-only subtitle candidate search and existing subtitle bytes for temporary playback. */
object EmbySubtitles {

  /**
   * 搜索某个媒体的在线字幕候选。
   *
   * `GET /Items/{Id}/RemoteSearch/Subtitles/{Language}`
   *
   * @param language 三字母语言码（`chi` / `eng` / `jpn`…），**大小写敏感的 path 段**。
   *   ⚠️ **没有「一次搜多语言」的端点** —— 要搜中英双轨就得发两次请求，
   *   所以调用方（ViewModel）负责循环 + 合并。
   * @param mediaSourceId 媒体源 Id。**参考客户端把它标成 (required)，但实际是 query 参数**；
   *   不传时服务端会用默认媒体源。对多版本（4K/1080P 各自带不同字幕）必须传对。
   * @param isPerfectMatch 只看「与文件名哈希精确匹配」的（质量最高、通常只 0~3 条）。
   * @return 候选列表；服务端没配任何字幕提供方时返回**空列表而不是报错**，
   *   这是正常状态（用户没在 Emby 后台开字幕插件）。
   */
  fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
    isForced: Boolean? = null,
    isHearingImpaired: Boolean? = null,
  ): List<EmbyRemoteSubtitle> {
    // language 走 path 段，必须编码（虽然三字母码实际都是 ASCII，但别赌）
    val path = "/Items/$itemId/RemoteSearch/Subtitles/${java.net.URLEncoder.encode(language, "UTF-8")}"
    return EmbyClient.getJson(
      server = server,
      path = path,
      query = buildMap {
        put("MediaSourceId", mediaSourceId)
        put("IsPerfectMatch", isPerfectMatch?.toString())
        put("IsForced", isForced?.toString())
        put("IsHearingImpaired", isHearingImpaired?.toString())
      },
    )
  }

  /**
   * 取已存到服务器的字幕字节。
   *
   * 与 [subtitleUrl] 的差别只是这里真的**发请求拿字节**（而不是拼 URL 交给 mpv）。
   *
   * `GET /Items/{Id}/{MediaSourceId}/Subtitles/{Index}/Stream.{Format}`
   * （参考客户端还有个 `/Videos/...` 的等价形式，两者服务端都认，这里统一用 `Items`）
   *
   * @param format 服务端支持的格式：`srt` / `ass` / `ssa` / `vtt` / `srt` 等。
   *   注意 Emby 的转换能力因服务端而异，**拿不到时应该回退 srt**（最通用的目标格式）。
   */
  fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): ByteArray {
    val req = EmbyClient.authedRequest(
      server,
      "/Items/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format",
    ).get().build()
    EmbyClient.httpClient.newCall(req).execute().use { resp ->
      val bytes = resp.body.bytes()
      if (!resp.isSuccessful) {
        throw EmbyApiException(
          resp.code,
          "字幕下载失败（HTTP ${resp.code}）：" +
            String(bytes, Charsets.UTF_8).take(120).ifBlank { resp.message },
        )
      }
      if (bytes.isEmpty()) throw EmbyApiException(resp.code, "服务端返回了空字幕")
      return bytes
    }
  }
}
