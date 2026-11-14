package app.marlboroadvance.mpvex.domain.emby

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// 数据模型（EmbyItem / EmbyUserDto / …）已搬到 EmbyModels.kt（清单 1.5 拆分第 1 刀），
// 尾部与错误处理相关的 DTO 仍留在本文件末尾。

// ════════════════════════════════════════════════════════════════════════
// EmbyClient —— 单例 HTTP 客户端，所有 Emby REST API 调用集中在此
// ════════════════════════════════════════════════════════════════════════

/**
 * Emby REST API 客户端。基于 OkHttp 5 + kotlinx.serialization。
 *
 * 用法：
 * ```
 * val client = EmbyClient
 * val auth = client.authenticateByName(server, "user", "pass")
 * // Repository keeps new credentials only in its process session overlay.
 * val libs = client.getVirtualFolders(server)  // 媒体库列表
 * val items = client.getItems(server, parentId = libs[0].Id, startIndex = 0, limit = 50)
 * ```
 *
 * 所有方法都会在 IO 线程阻塞（OkHttp 同步），调用方需自行 withContext(Dispatchers.IO)。
 */
object EmbyClient {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /**
   * 走 [EmbyHttp.shared]（共享连接池 + 统一 UA + 指数退避重试 + 401 自动重登）。
   *
   * 原来是这里自己 `OkHttpClient.Builder()` 造一个，与另外 5 个客户端各建各的连接池，
   * 详见 [EmbyHttp] 的说明。
   *
   * `internal` 而非 `private`：拆出去的 [EmbySubtitles] 需要它 —— 字幕下载要拿
   * **原始字节**（走完服务端格式转换的响应体），不能经过 [execString] 那条
   * 「按文本解码 + 抛异常」的路径。同理它必须显式写 `EmbyClient.httpClient`，
   * `internal` 不会把成员导入到别的 object 作用域里。
   */
  internal val httpClient: OkHttpClient = EmbyHttp.shared

  private val jsonMedia = "application/json; charset=UTF-8".toMediaType()

  /** These two Episode-only writes never use the shared 401/transport replay policy. */
  private val episodeWriteClient by lazy {
    httpClient.newBuilder().apply { interceptors().clear() }
      .retryOnConnectionFailure(false)
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor { chain ->
        val requested = requireNotNull(chain.request().tag(EmbyServer::class.java))
        val fresh = EmbyHttp.sessionServer(requested)
        if (!fresh.isLoggedIn || fresh.userId != requested.userId) {
          throw EmbyEpisodeOperationException("登录身份已变化，请刷新后重试")
        }
        chain.proceed(EmbyRequestHeaders.route(chain.request(), fresh))
      }.build()
  }

  internal fun requireUserStateMedia(item: EmbyItem): String {
    val id = item.Id
    val supported = item.Type.equals("Episode", ignoreCase = true) || item.Type.equals("Movie", ignoreCase = true)
    if (!supported || id.isNullOrBlank() || id.any { it.isWhitespace() || it in "/?#\\" || it.code < 32 }) {
      throw EmbyEpisodeOperationException("仅支持有效电影或单集的收藏和已看状态操作")
    }
    return id
  }

  fun setMediaFavorite(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    setMediaUserData(server, item, enabled, "FavoriteItems", "IsFavorite")

  fun setMediaPlayed(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    setMediaUserData(server, item, enabled, "PlayedItems", "Played")

  private fun setMediaUserData(
    server: EmbyServer, item: EmbyItem, enabled: Boolean, endpoint: String, field: String,
  ): EmbyUserData {
    val itemId = requireUserStateMedia(item)
    val itemType = item.Type.orEmpty()
    val target = EmbyHttp.sessionServer(server)
    if (!target.isLoggedIn || target.userId.any { it.isWhitespace() || it in "/?#\\" || it.code < 32 }) {
      throw EmbyEpisodeOperationException("请先登录后再操作")
    }
    val request = authedRequest(target, "/Users/${target.userId}/$endpoint/$itemId").apply {
      if (enabled) post(jsonBody("{}")) else delete()
    }.build()
    var uncertain = false
    val responseData = try {
      episodeWriteClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          if (response.code in 400..499 && response.code !in setOf(408, 429)) {
            throw EmbyEpisodeOperationException(when (response.code) {
              401 -> "登录已失效，请刷新后重试"
              403 -> "没有修改媒体状态的权限"
              404 -> "媒体不存在，请刷新后重试"
              else -> "媒体状态修改失败（HTTP ${response.code}）"
            })
          }
          uncertain = true
          null
        } else {
          val body = response.body.string()
          // Only an explicit boolean is authoritative; DTO defaults cannot prove a write.
          runCatching {
            val raw = json.parseToJsonElement(body) as? JsonObject
            val value = raw?.get(field) as? kotlinx.serialization.json.JsonPrimitive
            if (value != null && !value.isString && value.content in setOf("true", "false")) {
              json.decodeFromString<EmbyUserData>(body)
            } else null
          }.getOrNull()
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: EmbyEpisodeOperationException) {
      throw failure
    } catch (_: Exception) {
      uncertain = true
      null
    }
    fun checkedIdentity() {
      val fresh = EmbyHttp.sessionServer(target)
      if (!fresh.isLoggedIn || fresh.userId != target.userId) {
        throw EmbyEpisodeOperationException("登录身份已变化，请刷新后核对状态")
      }
    }
    checkedIdentity()
    if (responseData != null) return responseData
    // Empty success or an unknown outcome gets one readback, never another POST/DELETE.
    val data = try {
      val detail = getItem(target, itemId)
      if (detail.Id != itemId || !detail.Type.equals(itemType, ignoreCase = true)) null else detail.UserData
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) { null }
    checkedIdentity()
    if (data == null || (uncertain && (if (field == "IsFavorite") data.IsFavorite else data.Played) != enabled)) {
      throw EmbyEpisodeOperationException("操作结果暂未确认，请刷新核对状态；未自动重试写入")
    }
    return data
  }

  /** ServiceStack expects a JSON object even when a POST has no payload. */
  internal fun jsonBody(payload: String?) = payload?.takeUnless { it.isBlank() }
    .orEmpty().ifEmpty { "{}" }.toRequestBody(jsonMedia)


  private fun headerValue(value: String): String {
    if (value.any { it.code < 32 || it.code > 126 }) throw EmbyApiException(0, "客户端身份配置无效")
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  internal fun authorizationHeader(identity: EmbyAuthIdentity): String =
    "MediaBrowser Client=\"${headerValue(identity.client)}\", Device=\"${headerValue(identity.device)}\", " +
      "DeviceId=\"${headerValue(identity.deviceId)}\", Version=\"${headerValue(identity.clientVersion)}\""

  internal fun identityRequest(server: EmbyServer, url: String): Request.Builder =
    EmbyRequestHeaders.request(server, url)

  /**
   * 拉流时统一使用的 User-Agent。
   *
   * 「预解析直链（302）」探测与播放器真正拉流**必须用同一个 UA**：
   * 网盘直链的签名常把 UA 算进校验，两边不一致时探测能过、播放被 403。
   * 播放器侧通过 intent extra `headers` 收到同一个值（见 EmbyViewModel 的播放入口）。
   */
  const val STREAM_USER_AGENT = "mpvex/1.0"

  /** 视频类媒体（用于把音乐、图片从影视列表中过滤掉） */
  private const val VIDEO_MEDIA_TYPE = "Video"

  /**
   * 超过这个耗时的请求，在 [execString] 的计时日志里按「慢请求」标出（毫秒）。
   *
   * 定 1500 是因为正常局域网内一次 `/Items` 查询在几十到几百毫秒量级；
   * 持续超过 1.5 秒基本就是服务端那边在扫大表（十万级条目库里
   * `SortBy=Random` 这类无法走索引的排序尤其明显），值得单独看一眼。
   */
  private const val SLOW_REQUEST_MS = 1_500L

  /**
   * [probeStreamUrl] 的单次探测超时（毫秒）。
   *
   * 探测是为了「别把死链交给 mpv」，属于顺手确认，不能拖慢起播 ——
   * 2.5 秒还没拿到响应头，说明链路本身就不确定，按 [StreamProbe.Unknown] 放行。
   */
  private const val PROBE_TIMEOUT_MS = 2_500L

  /** 请求 Emby 时统一附加的扩展字段 */
  internal const val ITEM_FIELDS =
    "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources," +
    // Size 是「按文件大小排序」的数据来源，Emby 默认不返回，必须显式索取
    "Tags,SortName,ProductionLocations,Path,Size"

  /**
   * [ITEM_FIELDS] + **本地排序要用的那几个键**：年份 / 评分 / 添加时间 / 首播日期。
   *
   * ⚠️ 这几个字段 Emby **默认不回传**（不带 Fields 的 `/Items` 只回
   * Name / Id / Type / UserData / ImageTags，其余全是 null），而 [ITEM_FIELDS] 里也没有它们。
   * 于是「按年份 / 评分 / 最新添加排序」时每条的键都是 null，稳定排序退化为**原顺序** ——
   * 表现就是「排序只有名称（SortName 在 ITEM_FIELDS 里）和随机有效，其余点了没反应」。
   *
   * 凡是**要在客户端本地排序**的列表（收藏页、演员 / 导演作品页）都必须用这一份。
   */
  internal const val ITEM_FIELDS_WITH_SORT_KEYS =
    "$ITEM_FIELDS,ProductionYear,CommunityRating,DateCreated,PremiereDate"

  /**
   * 拉照片（Photo）列表时索要的扩展字段。
   *
   * 只用 OpenAPI 文档列出的合法 Fields 名（Overview/Genres/Taglines/SortName/
   * ProductionLocations/Path）—— 服务端对不认识的字段名有可能直接 400，
   * 不放没把握的值进去。
   *
   * ⚠️ **宽高 / MIME / EXIF（CameraMake 等）不在这个串里，也不该加**：
   * OpenAPI 的 Fields 可选值清单里根本没有它们 —— 这些字段由服务端在库扫描时
   * 从图片 EXIF 写进条目实体，对 Photo 条目**自动返回**，与 Fields 无关。
   * 见 [app.marlboroadvance.mpvex.domain.emby.EmbyModels] 里 EmbyItem 的照片字段段。
   */
  internal const val PHOTO_FIELDS =
    "Overview,Genres,Taglines,SortName,ProductionLocations,Path"

  /**
   * 取「整份条目原文」时索要的字段（演职员合并回写用）。
   *
   * 要点是**必须包含 `People`**：不带的话返回体里没有 People，
   * [app.marlboroadvance.mpvex.domain.emby.rewriteItemPeople] 会认为这条不用改从而静默跳过。
   * 其余是编辑元数据时用户可能已经改过的字段，一并要回来，
   * 免得整包回写时把它们从「有值」变成「缺字段」。
   */
  private const val RAW_ITEM_FIELDS =
    "Overview,OriginalTitle,SortName,Genres,Tags,Studios,ProductionLocations,People," +
      "OfficialRating,CommunityRating,PremiereDate,ProductionYear,Path,ProviderIds,Taglines"

  /** 统计演员作品数时每页拉多少条。开大一点，几千条的库几次就能拉完 */
  private const val COUNT_PAGE_SIZE = 500

  /**
   * 统计能接受的库规模上限（条数）。超过就整体放弃统计（角标留空），
   * 免得为了几十个角标去拉几十 MB 的响应。它同时也是翻页的硬上限 ——
   * 服务端万一忽略 StartIndex，配合按 Id 去重不会死循环。
   */
  private const val COUNT_MAX_ITEMS = 10_000

  /**
   * 小库阈值（条数）：不超过这个规模就整库扫一遍算齐所有演员角标。
   *
   * 定 3000 是因为 3000 / [COUNT_PAGE_SIZE] 500 = 6 个请求就能扫完，而单个响应
   * 只带 `People` 一个字段（见 [getItems] 的 fields 参数），体量很小 ——
   * 这个开销换「所有角标一次到位」很值。超过就转逐个问
   * （见 [getPersonWorkCountsAdaptive]），避免为几十个角标把大库拉穿。
   */
  private const val COUNT_FULL_SCAN_MAX = 3_000

  // ─── 内部工具 ───

  internal fun authedRequest(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    val server = EmbyHttp.sessionServer(server)
    val sb = StringBuilder("${EmbyEndpoints.apiBaseUrl(server)}$path")
    if (extraQuery.isNotEmpty()) {
      sb.append('?')
      extraQuery.entries.filter { it.value != null }.joinTo(sb, "&") { (k, v) ->
        "$k=${java.net.URLEncoder.encode(v!!, "UTF-8")}"
      }
    }
    return EmbyRequestHeaders.request(server, sb.toString())
  }

  internal fun authedRequestWithApiKey(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    // 带 api_key query 参数（用于 stream/images，便于直接拼 URL 给 PlayerActivity）
    val server = EmbyHttp.sessionServer(server)
    val withKey = extraQuery.toMutableMap()
    if (server.apiToken.isNotEmpty()) withKey["api_key"] = server.apiToken
    return authedRequest(server, path, withKey)
  }

  internal fun execString(request: Request): String {
    // 计时日志：所有 Emby API 请求（GET/POST/DELETE）都走这一个收口点，所以在这里
    // 打一行就够覆盖全部。用途是回答「到底哪个端点在慢」—— 大媒体库（十万级条目）下
    // `/Items` 系列查询的耗时会随库规模上涨，而外观上只表现为「首页转圈很久」，
    // 没有这行日志根本分不清是哪一个请求、等了多久。
    // ⚠️ 只打 path 不打 query：query 里可能有用户输入的搜索词，不进日志。
    val startedAt = System.nanoTime()
    try {
      httpClient.newCall(request).execute().use { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) {
          throw EmbyApiException(resp.code, body.ifEmpty { resp.message })
        }
        return body
      }
    } finally {
      val ms = (System.nanoTime() - startedAt) / 1_000_000
      val line = "${ms}ms ${request.method}"
      // 慢的单独按 warn 标出：这标签本身是诊断用的（`adb logcat -s EmbyDiag`），
      // 一屏能刷很多行，慢请求要能一眼挑出来。
      if (ms >= SLOW_REQUEST_MS) EmbyLog.warn("慢请求 $line") else EmbyLog.info(line)
    }
  }

  internal inline fun <reified T> decodeResponseJson(body: String): T = json.decodeFromString(body)

  internal inline fun <reified T> execJson(request: Request): T {
    val body = execString(request)
    if (body.isEmpty()) {
      // 返回空 body 时，尝试构造默认实例（kotlinx 不支持反射 default ctor，所以特殊处理）
      @Suppress("UNCHECKED_CAST")
      return when (T::class) {
        Unit::class -> Unit as T
        else -> decodeResponseJson(body.ifBlank { "null" })
      }
    }
    return decodeResponseJson(body)
  }

  internal inline fun <reified T> getJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).get().build()
    val result: T = execJson(req)
    // Apply policy only to item DTOs; authentication, images and other JSON stay untouched.
    @Suppress("UNCHECKED_CAST")
    return when (result) {
      is EmbyItemsResult -> EmbyItemPolicy.filterSupported(result) as T
      is EmbyItem -> EmbyItemPolicy.requireSupported(result) as T
      else -> result
    }
  }

   // ════════════════════════════════════════════════════════════════════════
  // 1. 认证 / 服务器信息
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 用用户名密码登录。返回的 AccessToken 写回 [EmbyServer.apiToken]。
   *
   * Emby 要求 X-Emby-Authorization header，格式：
   *   MediaBrowser Client="mpvEx", Device="Android", DeviceId="<uuid>", Version="1.0"
   */
  @Serializable
  internal data class LoginPayload(val Username: String, val Pw: String)

  internal fun loginJson(username: String, password: String): String =
    json.encodeToString(LoginPayload(username, password))

  fun authenticateByName(server: EmbyServer, username: String, password: String): EmbyAuthResult {
    val body = loginJson(username, password)
    val req = identityRequest(server, "${EmbyEndpoints.apiBaseUrl(server)}/Users/AuthenticateByName")
      .post(jsonBody(body))
      .build()
    return execJson(req)
  }

  /** 拉取服务器公开信息（无需登录）。 */
  fun getPublicSystemInfo(server: EmbyServer): EmbySystemInfo = getJson(server, "/System/Info/Public")

   // ════════════════════════════════════════════════════════════════════════
  // 2. 媒体库（VirtualFolders） / 媒体浏览
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 获取用户可见的媒体库（媒体库视图 / CollectionFolders）。
   *
   * 对应 SDK：`UserViewsServiceApi.getUsersByUseridViews` → `GET /Users/{UserId}/Views`
   * 返回电影、剧集、音乐等顶层库，而不是把所有根级 Item 平铺。
   */
  fun getVirtualFolders(server: EmbyServer): EmbyItemsResult =
    getJson(server, "/Users/${server.userId}/Views")

  /**
   * 继续观看。
   *
   * ## ⚠️ 不要再按类型过滤（v229）
   *
   * 原来默认传 `IncludeItemTypes=Movie,Series`，理由写着「单集属于剧集内部层级，放在
   * 继续观看里会显得零散」。后果是从**详情页点某一集**播放之后，那一集的类型是
   * `Episode` —— 服务端直接把它从 Resume 结果里滤掉：详情页明明有进度、首页那一行却
   * 始终没有它，用户侧就是「有些上报了但继续观看里看不到」。
   *
   * 现在按 [Yamby v2.1.0.11] 的做法：**不传 `IncludeItemTypes`**，只用
   * `MediaTypes=Video` + `Recursive=true` 圈定范围，让服务端按自己的 Resume 语义决定
   * 返回哪一条（电影返回电影、剧集返回那一集）。Emby 官方客户端也是这个口径。
   *
   * 需要收窄时再显式传 [includeItemTypes]（目前没有调用方这么做）。
   */
  fun getResumeItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 20,
    includeItemTypes: List<String>? = null,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    parentId: String? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Users/${server.userId}/Items/Resume",
    mapOf(
      "startIndex" to startIndex.toString(),
      "limit" to limit.toString(),
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      // ⚠️ `Recursive` 必须显式给：Resume 的默认递归行为在个别兼容服务端上不一致，
      //    显式 `true` 与官方客户端一致（Yamby 也是写死 true）。
      "Recursive" to "true",
      "ParentId" to parentId,
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
    ),
  )

  /**
   * 按 Id 批量取条目：`GET /Users/{uid}/Items?Ids=a,b,c`。
   *
   * 用途只有一个 —— 首页「继续观看」把**本地最近播放、但不在服务端那批结果里**的条目
   * 补回来。为什么需要补：服务端确实按 `LastPlayedDate` 降序排，但**只有该次播放真的
   * 刷新了时间戳，条目才会被顶到前面**；我们的上报过去只写了位置没写时间，条目就卡在
   * 旧位次（实测有一条卡在第 21 位，首页只要 20 条）。详见 [EmbyRecentPlays] 的 KDoc。
   *
   * ⚠️ 必须**一次请求取全部**，绝不逐条 `GET /Items/{id}`：账本最多补十几条，
   * 逐条就是十几个串行往返（`Limit` 显式给 `ids.size`，避免服务端按默认页大小截断）。
   */
  fun getItemsByIds(server: EmbyServer, ids: List<String>): EmbyItemsResult {
    if (ids.isEmpty()) return EmbyItemsResult()
    return getJson(
      server,
      "/Users/${server.userId}/Items",
      mapOf(
        "Ids" to ids.joinToString(","),
        "Recursive" to "true",
        "Limit" to ids.size.toString(),
        "Fields" to ITEM_FIELDS,
        "EnableImages" to "true",
        "EnableUserData" to "true",
        "ImageTypeLimit" to "1",
      ),
    )
  }

  /**
   * 最新加入。
   *
   * 默认只取电影与剧集（不含单集），避免单集把整页刷满。
   */
  fun getLatestItems(
    server: EmbyServer,
    parentId: String? = null,
    limit: Int = 16,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
  ): List<EmbyItem> = EmbyItemPolicy.filterSupported(getJson<List<EmbyItem>>(
    server,
    "/Users/${server.userId}/Items/Latest",
    mapOf(
      "limit" to limit.toString(),
      "ParentId" to parentId,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
    ),
  ))

  /**
   * 通用分页查询。可用于：库内媒体、收藏夹、随机播放、按类型筛选等。
   *
   * @param parentId 父级 ID（库 ID 或剧集 ID 等）。null 表示查用户根。
   * @param sortBy 排序字段：DateCreated/DatePlayed/Name/ProductionYear/Random/...
   * @param sortOrder Ascending / Descending
   * @param filters 筛选：IsFavorite/IsPlayed/IsUnplayed/...
   * @param includeItemTypes 类型过滤：Movie/Series/Episode/MusicAlbum/...
   * @param startIndex 分页起始
   * @param limit 每页大小
   */
  fun getItems(
    server: EmbyServer,
    parentId: String? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    filters: List<String>? = null,
    includeItemTypes: List<String>? = null,
    genres: List<String>? = null,
    searchTerm: String? = null,
    mediaTypes: List<String>? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    /** true / false 分别限定只要收藏 / 只要未收藏；null 表示不限 */
    isFavorite: Boolean? = null,
    /**
     * 排除的条目类型。Emby 的 Recursive 查询默认会把 Folder 一并返回，
     * 「全部」这类视图要传入 Folder/CollectionFolder/UserView 把它们挡掉。
     */
    excludeItemTypes: List<String>? = null,
    /** 按人员筛选：传演员/导演的 PersonId，查 TA 参与过的条目 */
    personIds: List<String>? = null,
    /** 按发行年份筛选；多选时 Emby 取并集 */
    years: List<Int>? = null,
    /** 最低社区评分（0~10），例如传 7.0 表示只要 7 分以上的 */
    minCommunityRating: Float? = null,
    /** 按标签筛选；多选时 Emby 取并集，查询串用 | 分隔 */
    tags: List<String>? = null,
    /**
     * 按「类型 Id / 标签 Id」筛选（逗号分隔）。
     *
     * 优先用这两个，而不是按名字的 [genres] / [tags]：
     * Emby 官方自 2019 年起就推荐用 Id（[官方说明](https://emby.media/community/topic/74602-tagitems-filtering-by-tags/)：
     * 「All list api's now have a TagIds filter… You can still filter using the tag name
     * with the Tags param, but this should only be used in situations where you do not have the id」）。
     *
     * 按名字那条路径会去查 `MediaItems.Genres` 列，某些服务端的 `library.db` 缺这一列
     * （跨版本升级/降级留下的坏 schema），一查就抛 SQLiteException 返回 500；
     * 按 Id 走的是关联表，与网页端筛选同路，不会踩到这个坑。
     */
    genreIds: List<String>? = null,
    tagIds: List<String>? = null,
    /** 按官方分级筛选；参数名是复数 OfficialRatings，查询串用 | 分隔 */
    officialRatings: List<String>? = null,
    /**
     * 已看 / 未看：true 只要看过的，false 只要没看过的，null 不限。
     *
     * Emby 4.x 起这是独立的 IsPlayed 布尔参数，不是 Filters 里的枚举值，
     * 所以单独拼，不要塞进 Filters（塞进去服务端会忽略）。
     */
    isPlayed: Boolean? = null,
    /**
     * 只要媒体本体 / 只要容器：false 会把 Folder、Series、Season、BoxSet 这类
     * 「本身不能播放」的容器一并挡掉（Emby 的 IsFolder）。null 表示不限。
     *
     * 「只要视频」的视图必须传 false —— 光靠 ExcludeItemTypes 只能挡住写在名单里的
     * 那几个容器类型，挡不住 Season、MusicAlbum 这些漏网的。
     */
    isFolder: Boolean? = null,
    /** true 只要高清 / false 只要标清（Emby 的 IsHD） */
    isHD: Boolean? = null,
    /** true 只要 3D / false 排除 3D（Emby 的 Is3D） */
    is3D: Boolean? = null,
    /** true 只要有字幕 / false 只要没字幕（Emby 的 HasSubtitles） */
    hasSubtitles: Boolean? = null,
    /** 工作室（出品方）Id 列表，多选取并集 */
    studioIds: List<String>? = null,
    /**
     * 工作室**名字**列表（`|` 分隔），多选取并集。
     *
     * 只在拿不到工作室 Id 时用 —— Id 那条（[studioIds]）更准。Emby 的 REST 文档里
     * `Studios` 与 `StudioIds` 都是官方过滤参数，所以这是个正经的兜底，不是硬凑。
     */
    studios: List<String>? = null,
    /**
     * 覆盖默认的 [ITEM_FIELDS]。
     *
     * 统计类请求（如数每个演员有多少部作品）只需要 `People`，用这个参数把
     * `MediaSources`、`Overview` 这些大字段去掉 —— 一页 500 条时响应体能小掉一大半。
     */
    fields: String = ITEM_FIELDS,
    /**
     * 按 Id 精确取条目（`Ids` 参数，逗号分隔）。
     *
     * 与 `Recursive` / `ParentId` 互斥的用法：`Ids` 命中即可，递归没有意义。
     * 用途见详情页的「同季其它集」（`BaseItemDto.SiblingIds` 只有 Id、没有内容）。
     */
    ids: List<String>? = null,
  ): EmbyItemsResult {
    val q = LinkedHashMap<String, String?>()
    // ⚠️ Ids 必须放在最前面并**关掉 Recursive**：Emby 在 `Ids + Recursive=true` 时
    // 会先展开整个库再过滤，几千条库能拖到超时（第三方服务端更甚）。
    ids?.takeIf { it.isNotEmpty() }?.let { q["Ids"] = it.joinToString(",") }
    parentId?.let { q["ParentId"] = it }
    sortBy?.let { q["SortBy"] = it }
    sortOrder?.let { q["SortOrder"] = it }
    filters?.takeIf { it.isNotEmpty() }?.let { q["Filters"] = it.joinToString("|") }
    isFavorite?.let { q["IsFavorite"] = it.toString() }
    // IsFolder 与 ExcludeItemTypes 是两个维度：前者按「是不是容器」筛，后者按类型名筛。
    // 只传其中一个都会漏 —— 第三方服务端对 ExcludeItemTypes 的支持尤其参差。
    isFolder?.let { q["IsFolder"] = it.toString() }
    includeItemTypes?.takeIf { it.isNotEmpty() }?.let { q["IncludeItemTypes"] = it.joinToString(",") }
    q["ExcludeItemTypes"] = EmbyItemPolicy.queryExclusions(excludeItemTypes).joinToString(",")
    genres?.takeIf { it.isNotEmpty() }?.let { q["Genres"] = it.joinToString("|") }
    tags?.takeIf { it.isNotEmpty() }?.let { q["Tags"] = it.joinToString("|") }
    // 按 Id 筛（优先）：官方推荐、且不碰会 500 的 Genres 列。逗号分隔。
    genreIds?.takeIf { it.isNotEmpty() }?.let { q["GenreIds"] = it.joinToString(",") }
    tagIds?.takeIf { it.isNotEmpty() }?.let { q["TagIds"] = it.joinToString(",") }
    officialRatings?.takeIf { it.isNotEmpty() }?.let { q["OfficialRatings"] = it.joinToString("|") }
    // 已看状态与视频规格都是独立布尔参数，服务端只认 true / false，不传才是「不限」
    isPlayed?.let { q["IsPlayed"] = it.toString() }
    isHD?.let { q["IsHD"] = it.toString() }
    is3D?.let { q["Is3D"] = it.toString() }
    hasSubtitles?.let { q["HasSubtitles"] = it.toString() }
    studioIds?.takeIf { it.isNotEmpty() }?.let { q["StudioIds"] = it.joinToString(",") }
    studios?.takeIf { it.isNotEmpty() }?.let { q["Studios"] = it.joinToString("|") }
    personIds?.takeIf { it.isNotEmpty() }?.let { q["PersonIds"] = it.joinToString(",") }
    years?.takeIf { it.isNotEmpty() }?.let { q["Years"] = it.joinToString(",") }
    // Genres / Tags / OfficialRatings 都是「|」分隔，只有 Years 是「,」分隔
    minCommunityRating?.let { q["MinCommunityRating"] = it.toString() }
    searchTerm?.takeIf { it.isNotBlank() }?.let { q["SearchTerm"] = it }
    mediaTypes?.takeIf { it.isNotEmpty() }?.let { q["MediaTypes"] = it.joinToString(",") }
    q["StartIndex"] = startIndex.toString()
    q["Limit"] = limit.toString()
    // Ids 查询一律非递归（见上方 ids 参数的说明）
    val byIds = ids?.takeIf { it.isNotEmpty() }
    q["Recursive"] = if (byIds != null) "false" else recursive.toString()
    q["Fields"] = fields
    q["EnableImages"] = "true"
    q["EnableUserData"] = "true"
    q["ImageTypeLimit"] = "1"
    return getJson(server, "/Users/${server.userId}/Items", q)
  }

  /**
   * 照片库：相册（PhotoAlbum）列表。
   *
   * ⚠️ **recursive=false**：相册是照片库下的一级容器，递归会把库/专辑里的照片
   * 一股脑倒出来，相册层级就没了。默认按名字升序（与 Emby 网页端一致）。
   *
   * 失败行为与 [getItems] 一致（抛给调用方），由 UI 决定怎么提示。
   */
  fun getPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("PhotoAlbum"),
    startIndex = startIndex,
    limit = limit,
    recursive = false,
    fields = PHOTO_FIELDS,
  )

  /**
   * 照片库：照片（Photo）列表。
   *
   * - [parentId] 传相册 Id 时取该相册内的照片；传库 Id 时递归拉平整个库
   *   （混合库「仅照片」态走这条：不管服务端把照片放在哪个专辑里，全部倒出来）。
   * - 排序传 `SortBy=Random` 即「随机一批照片」，供随机播放入口使用。
   * - 不传 `MediaTypes=Photo`：照片条目靠 `IncludeItemTypes=Photo` 已经圈死，
   *   再加 MediaTypes 属于重复过滤，且个别服务端对该参数的值域校验更严，
   *   多传一个反而可能整页拉空。
   */
  fun getPhotos(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    recursive: Boolean = true,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("Photo"),
    isFolder = false,
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    fields = PHOTO_FIELDS,
  )

  /**
   * 取「按名字聚合」端点 /Genres、/Tags、/OfficialRatings、/Years 返回的名称列表。
   * 这四个端点返回体结构一致（Items[].Name），可以共用一个取法。
   *
   * ⚠️ 失败仍返回空列表（**故意不抛**）：这四组数据是「筛选面板的选项」，
   * 缺了只是面板空着，不该因此让整个媒体库页面报错。**但必须留下日志** ——
   * 以前是纯 `getOrDefault(emptyList())`，失败后UI 表现为「筛选器里没有类型」，
   * 用户会以为库里真没有，而开发者根本查不到（这种静默失败最难查）。
   */
  private fun nameList(server: EmbyServer, path: String, parentId: String?): List<String> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { it.Name }
        .filter { it.isNotBlank() }
    }.onFailure {
      EmbyLog.warn("nameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
    }.getOrDefault(emptyList())

  /** 某媒体库下出现过的全部类型名 */
  fun getGenres(server: EmbyServer, parentId: String? = null): List<String> =
    nameList(server, "/Genres", parentId)

  /**
   * 按名字查「类型」（Genre）条目的 Id。
   *
   * 用途：类型结果页（`EmbyTagItemsScreen`）顶栏的收藏红心要拿这个 Id 再去
   * [getItem] 取一条带 `UserData` 的完整条目，才知道「这个类型当前收没收藏」。
   * 详情页的类型 chip 只给了**名字**（`EmbyItem.Genres` 是纯字符串列表），
   * 所以必须有「名字 → Id」这条反查通道。
   *
   * 主路走官方 `/Genres` 聚合端点 —— 与筛选面板取「类型名 + Id」是同一个端点
   * （见 [getFilterOptions]）。该端点的 `parentId` 是**可选**参数，不传即
   * 「该用户可见的全部类型」（官方文档：Gets all genres from a given item,
   * folder, **or the entire library**）。拿到全量后本地匹配：先精确（忽略大小写），
   * 再退一步「包含」，兜住服务端类型名的全半角 / 空格差异。
   *
   * ⚠️ 不要退回 `/Items?IncludeItemTypes=Genre&SearchTerm=...` 且不带 `Recursive`：
   * 没有 `ParentId` 时非递归查询只扫用户根（那里只有媒体库视图、没有类型条目），
   * **必然返回空** —— 这正是「类型页右上角不显示收藏红心」的根因。
   *
   * 兜底：个别服务端的 `/Genres` 在缺 `ParentId` 时仍返回空，这时改走一次
   * 递归的 `/Items`（`Recursive=true` 才会跨库扫到 `Genre` 条目，
   * 与 [getFavoriteGenres] 同一口径）。
   */
  fun getGenreIdByName(server: EmbyServer, name: String): String? {
    if (name.isBlank()) return null
    // 主路：官方聚合端点
    idNameList(server, "/Genres", parentId = null)
      .let { candidates ->
        candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
          ?: candidates.firstOrNull { it.name.contains(name, ignoreCase = true) }
      }
      ?.let { return it.id }
    // 兜底：递归 /Items 按名字搜一次类型条目
    return runCatching {
      getItems(
        server = server,
        includeItemTypes = listOf("Genre"),
        searchTerm = name,
        recursive = true,
        limit = 64,
      ).Items.firstOrNull { it.Name.equals(name, ignoreCase = true) }?.Id
    }.onFailure {
      EmbyLog.warn("getGenreIdByName(\"$name\") 兜底查询失败：${it.message}", it)
    }.getOrNull()
  }

  /**
   * 取「Id + 名字」的聚合端点（/Persons、/Studios）。
   *
   * 和 [nameList] 的区别是这里要留下 Id —— 演员同名很常见，只有 Id 能唯一确定一个人。
   */
  private fun idNameList(
    server: EmbyServer,
    path: String,
    parentId: String?,
    extra: Map<String, String> = emptyMap(),
  ): List<EmbyIdName> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      extra.forEach { (k, v) -> q[k] = v }
      getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          EmbyIdName(id, name)
        }
    }.onFailure {
      EmbyLog.warn("idNameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
    }.getOrDefault(emptyList())

  /**
   * 某媒体库里出现过的演员 / 导演 / 编剧。
   *
   * PersonTypes 用 | 分隔，和 Genres 一样；不传会把幕后工种全拉回来，列表会很长。
   */
  fun getPersons(
    server: EmbyServer,
    parentId: String? = null,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
  ): List<EmbyIdName> = idNameList(
    server,
    "/Persons",
    parentId,
    mapOf("PersonTypes" to personTypes.joinToString("|")),
  )

  /**
   * 某媒体库里出现过的演员（PersonTypes=Actor）。
   *
   * 用于媒体库「演员」分类：返回**完整 EmbyItem**（带 [EmbyItem.ImageTags] 头像），
   * 复用现有媒体卡片渲染；强制 [EmbyItem.Type]="Person"，点击时走「该演员作品」路由。
   */
  fun getActors(
    server: EmbyServer,
    parentId: String? = null,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = "Actor"
      // 这里本来想直接要 ChildCount —— Person 在该字段里确实是「参与的作品数」，
      // 但 /Persons 端点的 Fields 白名单里没有它（官方文档的选项是 Budget / Chapters /
      // Genres / MediaStreams / People … ），传了会被服务端忽略，Person 的 ChildCount
      // 于是恒为 null，演员卡片左下角的角标永远画不出来。
      // 真正的数据来源是 [getPersonWorkCounts]（客户端自己数）。这行留着，万一某个
      // 服务端认它就能省掉那次统计。
      q["Fields"] = "ChildCount"
      // EnableUserData：让服务端回传 UserData.IsFavorite。演员长按收藏、收藏页「演员」tab
      // 都靠这个字段判断收藏状态，没有它 Person 的 IsFavorite 恒为空。
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("getPersons 失败：${it.message} —— 演员/导演筛选会是空的", it)
    }.getOrDefault(emptyList())

  /** 「演职员合并」扫描时一页拉多少人 */
  private const val PERSON_PAGE_SIZE = 500

  /** 「演职员合并」扫描的人员上限。超过就截断并在 UI 里说明，不会无限翻页。 */
  private const val PERSON_SCAN_MAX = 8_000

  /**
   * 拉**全服务器**的人员清单（演员 / 导演 / 编剧），给「演职员合并」扫重复用。
   *
   * 与 [getActors] 的三点差别：
   *  1. **不限媒体库**（`ParentId` 不传）：一个人在不同库里各有一份，合并必须跨库看；
   *  2. **工种可配**：重复的不只有演员，导演 / 编剧一样会裂，默认三种都要；
   *  3. **自己翻页**：`/Persons` 一次能返回多少条由服务端决定（部分版本会截断 `Limit`），
   *     所以用 `StartIndex` 逐页拉，并以**服务端给的 `TotalRecordCount`** 作为终止条件，
   *     而不是「本页返回数 < 请求数」—— 后者在服务端截断 Limit 时会在第一页就退出、漏掉大半。
   *
   * `Fields=ProviderIds` 是**尝试性**的：`/Persons` 的 Fields 白名单很窄
   * （[getActors] 已证实它会静默忽略 `ChildCount`）。能拿到就是「同名但其实是两个人」的
   * 关键反证；拿不到也不影响流程（退化为按名字判断）。
   */
  fun getPersonsDetailed(
    server: EmbyServer,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
    maxItems: Int = PERSON_SCAN_MAX,
  ): List<EmbyItem> = runCatching {
    val out = ArrayList<EmbyItem>()
    // 按 Id 去重：服务端若忽略 StartIndex 反复给同一批，没有这层会死循环
    val seen = HashSet<String>()
    var start = 0
    while (out.size < maxItems && start < maxItems) {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = personTypes.joinToString("|")
      q["Fields"] = "ProviderIds"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      q["StartIndex"] = start.toString()
      q["Limit"] = PERSON_PAGE_SIZE.toString()
      val page = getJson<EmbyItemsResult>(server, "/Persons", q)
      if (page.consumedCount == 0) break
      var fresh = 0
      page.Items.forEach { item ->
        val id = item.Id ?: return@forEach
        if (!seen.add(id)) return@forEach
        val name = item.Name?.takeIf { it.isNotBlank() } ?: return@forEach
        fresh++
        out.add(item.copy(Type = "Person", Name = name))
      }
      // 一条新的都没有 = 服务端在重复给同一批，收手（这也是防死循环的硬闸门）
      if (fresh == 0 && page.Items.isNotEmpty()) break
      start += page.consumedCount
      if (page.TotalRecordCount > 0 && start >= page.TotalRecordCount) break
    }
    out.take(maxItems)
  }.onFailure {
    EmbyLog.warn("scanAllPersons 失败：${it.message} —— 演职员合并的候选名单会是空的", it)
  }.getOrDefault(emptyList())

  /**
   * 一个人**参与了多少部作品** —— 用 `PersonIds` 查询的 `TotalRecordCount` 直接拿。
   *
   * 为什么不复用 [getPersonWorkCounts]：那个方法是把**整个库逐页拉下来自己数**，
   * 几千条的库要几十个请求，而且超过 1 万条时会整体放弃返回空表。
   * 这里只要一个数字，问服务端一次就够了（`Limit=1` 只取一条，响应很小）。
   *
   * `IsFolder=false` 与「作品数」的口径保持一致：只数**本身能播**的条目，
   * 免得一季、一个合集被算成一部作品。
   */
  fun getPersonWorkCount(server: EmbyServer, personId: String): Int =
    runCatching {
      getItems(
        server = server,
        personIds = listOf(personId),
        recursive = true,
        startIndex = 0,
        limit = 1,
        isFolder = false,
        fields = "People",
      ).TotalRecordCount
    }.onFailure {
      EmbyLog.warn("getPersonWorkCount($personId) 失败：${it.message} —— 该演员角标按0 处理", it)
    }.getOrDefault(0)

  /**
   * 收藏的演员列表（收藏页「演员」tab 用）。
   *
   * **为什么不用 `Filters=IsFavorite` 直接让服务端筛**：`/Persons` 这条端点对 Filters
   * 的支持参差不齐（官方 Emby 认，部分第三方兼容服务端会静默忽略，忽略后返回的是
   * 全量演员的第一页 —— 收藏的人可能根本不在里面）。而 `/Persons` 全量拉一遍是
   * 媒体库「演员」分类已经在用的成熟路径，这里复用它再在客户端按
   * `UserData.IsFavorite` 过滤，各服务端表现一致。
   *
   * 调用方（EmbyViewModel）按服务器缓存结果，切换 影片/演员 tab 不重复发请求。
   */
  fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    getActors(server, null).filter { it.UserData?.IsFavorite == true }

  /**
   * 按 Id 取单个人员（演员 / 导演），带 UserData.IsFavorite。演员作品页的收藏红心用。
   *
   * ⚠️ 参数形状按 Emby Web 实际请求校正：
   *  · `/Persons` 的 `Ids` 与 `Recursive` **不能同时传** —— `Ids+Recursive` 会让服务端
   *    先展开整个库再过滤（本项目在 `getItems` 上已经踩过并规避，见那里的注释），
   *    第三方服务端上几千条的库能直接拖到超时；
   *  · `Ids` 本身在部分服务端的 `/Persons` 上**不被支持**（Emby Web 自己也不用它，
   *    人员列表走 `PersonTypes` / `IsFavorite` 过滤），被忽略时这条查询会退化成
   *    「未过滤的人员第一页」—— 演员大多不在第一页，于是红心永远读不到状态；
   *  · 补 `PersonTypes=Actor,Director,Writer`：部分服务端不传它就返回空；
   *  · `/Users/{uid}/Items/{id}` 是 Emby Web 人员详情页的同款端点，显式带
   *    `EnableUserData`，防服务端在该路径上默认不回传 UserData。
   *
   * 三条路径的结果**合并**而非早退：谁带 UserData 用谁（IsFavorite 是红心的唯一数据源），
   * 都不带就返回任意一个非空结果（至少保证条目可点、状态按「未收藏」兜底）。
   */
  fun getPersonById(server: EmbyServer, personId: String): EmbyItem? {
    val candidates = ArrayList<EmbyItem>()

    // 路径 1：/Persons + Ids（关掉 Recursive，见上方说明）
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Ids"] = personId
      q["Recursive"] = "false"
      q["PersonTypes"] = "Actor,Director,Writer"
      q["SortBy"] = "SortName"
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .firstOrNull { it.Id == personId }
        ?.copy(Type = "Person")
    }.onFailure {
      EmbyLog.warn("getPersonById 路径1(/Persons?Ids) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 2：/Users/{uid}/Items/{id}（Emby Web 人员详情页同款端点；轻量 Fields + EnableUserData）
    runCatching {
      getJson<EmbyItem>(
        server,
        "/Users/${server.userId}/Items/$personId",
        mapOf(
          "EnableUserData" to "true",
          "Fields" to "BasicSyncInfo,CanDelete,CanDownload,PrimaryImageAspectRatio",
        ),
      )
        .takeIf { it.Id == personId }
        ?.let { item -> item.copy(Type = item.Type ?: "Person") }
    }.onFailure {
      EmbyLog.warn("getPersonById 路径2(/Users/{uid}/Items/{id}) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 3：原有兜底（getItem 的重 fields 组合）
    runCatching { getItem(server, personId) }
      .getOrNull()
      ?.takeIf { it.Id == personId }
      ?.let { person -> person.copy(Type = person.Type ?: "Person") }
      ?.let { candidates += it }

    if (candidates.isEmpty()) {
      EmbyLog.warn("getPersonById($personId) 三条路径全部失败 —— 红心按「未收藏」兜底，仍可点击")
      return null
    }
    return candidates.firstOrNull { it.UserData != null } ?: candidates.first()
  }

  /**
   * 读单个人员的**收藏真值**（true=已收藏 / false=未收藏 / null=读不到，无法确认）。
   *
   * ⚠️ **两条路径的先后顺序在 2026-10-21 被对调过**，这是个踩出来的坑，别再调回去：
   * 旧顺序是「单条轻量读 → 收藏列表扫描」，而单条读走的 `/Users/{uid}/Items/{id}`
   * 在用户的这台服务端上**对相当一部分人员会卡满 30 秒然后回 HTTP 500**
   * （如 1114461 / 1083309 / 1333172 / 1132405 全是 500，而 1031021、1081006 又 40ms 正常）。
   * 后果有两个：① 进演员页要先干等 30s+ 才轮到下一步；② 单条读抛异常后 `runCatching`
   * 返回 null，才轮到列表法 —— 而列表法其实 **54ms** 就能给出真值。
   * 更致命的是：演员作品页把「读收藏状态」挂在 [getPersonById] 的非空返回值后面，
   * 而 [getPersonById] 的三条路径（`/Persons?Ids` 不支持 → 空；`/Users/{uid}/Items/{id}` → 500）
   * 在这台机上对演员**全部失败**返回 null，于是列表法**连执行的机会都没有** ——
   * 这就是「导演作品页红心正常、演员作品页红心永远置灰」的根因（导演刚好走通了单条读）。
   *
   * 为什么敢把列表法放在第一步：
   *  · `/Persons?UserId=<guid>&IsFavorite=true&EnableUserData=true` 54ms 返回，带 UserData；
   *  · `POST .../FavoriteItems/<id>` 收藏一个人之后**立刻**再查，列表马上多出这个人
   *    （不存在「按 URL 缓存、回读拿到旧值」的问题 —— 旧代码担心的缓存坑在这条 URL 上不成立）；
   *  · `DELETE` 取消收藏后同样立刻消失。
   * 所以它可以同时承担「进页面取初值」和「收藏后回读确认」两个职责。
   *
   * 为什么不能只靠 [getPersonById]：演员红心历史上最顽固的表现是「收藏明明成功、
   * 回读却说没收藏，红心被回滚成灰色，怎么点都点不亮」。成因对上过两条：
   *  1. 服务端（或中间层）按 URL 缓存响应 —— 加载时读一次 `/Persons?Ids=X`，
   *     收藏成功后**同一个 URL** 再读还是缓存里的旧 UserData，于是把成功误判成失败；
   *  2. `/Persons` 的 `Ids` 参数在部分服务端上不被支持（见 [getPersonById] 注释）。
   */
  fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? {
    // 1) 收藏列表法（Emby Web 收藏页同款查询；最快、真值新鲜）—— 见上方注释，别调换顺序
    readFavoriteFromPersonList(server, personId)?.let { return it }
    // 2) 单条轻量读兜底：列表法拿不到结论时才走。部分人员在这台服务端上会 30s 后 500，
    //    失败就放弃（返回 null = 无法确认），不再让红心为它干等。
    return runCatching {
      getJson<EmbyItem>(
        server,
        "/Users/${server.userId}/Items/$personId",
        mapOf("EnableUserData" to "true"),
      )
        .takeIf { it.Id == personId }
        ?.UserData?.IsFavorite
    }.onFailure {
      EmbyLog.warn("getPersonFavoriteState 单条读 $personId 失败：${it.message}", it)
    }.getOrNull()
  }

  /**
   * 翻 `/Persons?IsFavorite=true` 找这个人：true=已收藏 / false=不在收藏列表里 / null=下不了结论。
   *
   * [isFavoriteFilterHonored] 只在「人找到了但没带 UserData」这一个歧义分支上被调用 ——
   * 那种情况下「出现在列表里」到底是"它被过滤出来的"还是"服务端忽略过滤返回了全量人员"，
   * 必须靠总数对比才能定，否则会把所有人的红心都点亮成已收藏。
   */
  private fun readFavoriteFromPersonList(server: EmbyServer, personId: String): Boolean? =
    runCatching {
      var start = 0
      val limit = 100
      var favTotal = 0
      var found: EmbyItem? = null
      while (found == null) {
        val q = LinkedHashMap<String, String?>()
        q["UserId"] = server.userId
        q["IsFavorite"] = "true"
        q["Recursive"] = "true"
        q["SortBy"] = "SortName"
        q["SortOrder"] = "Ascending"
        q["EnableUserData"] = "true"
        q["EnableTotalRecordCount"] = "true"
        q["StartIndex"] = start.toString()
        q["Limit"] = limit.toString()
        val page = getJson<EmbyItemsResult>(server, "/Persons", q)
        favTotal = page.TotalRecordCount
        found = page.Items.firstOrNull { it.Id == personId }
        if (found == null) {
          if (page.consumedCount == 0) break
          // 终止条件两条腿走路：TotalRecordCount 说了算（服务端若忽略 Limit、按自己的
          // 默认上限分页时，「页不满」不代表拉完，会漏掉后面页里的人）；拿不到总数
          // （服务端不回传）才退化看页是否不满
          val total = page.TotalRecordCount.takeIf { it > 0 }
          if (total != null) {
            if (start + page.consumedCount >= total) break
          } else if (page.consumedCount < limit) {
            break
          }
          start += page.consumedCount
          // 兜底防无限翻页：收藏的人员不可能有这个量级
          if (start >= 10_000) break
        }
      }
      val hit = found
      val hitFavorite = hit?.UserData?.IsFavorite
      when {
        // 翻完没找到 → 未收藏（能确认）
        hit == null -> false
        // 找到了且自带 UserData.IsFavorite → 直接信它（EnableUserData 生效时最准）
        hitFavorite != null -> hitFavorite
        // 找到了但拿不到 UserData.IsFavorite → 只有确认过滤真生效了，才敢把「在列表里」当成已收藏
        isFavoriteFilterHonored(server, favTotal) -> true
        else -> null
      }
    }.onFailure {
      EmbyLog.warn("readFavoriteFromPersonList($personId) 失败：${it.message}", it)
    }.getOrNull()

  /**
   * 判断这台服务端的 `/Persons` 是否真的认 `IsFavorite=true` 过滤：
   * 「收藏人员总数」明显少于「全部人员总数」就是生效了。
   *
   * 代价只是一次 `Limit=1` 的轻量请求，而且只在 [readFavoriteFromPersonList]
   * 撞上「找得到人、却没有 UserData」这个罕见分支时才发。
   */
  private fun isFavoriteFilterHonored(server: EmbyServer, favTotal: Int): Boolean {
    if (favTotal <= 0) return false
    val q = LinkedHashMap<String, String?>()
    q["UserId"] = server.userId
    q["Recursive"] = "true"
    q["EnableTotalRecordCount"] = "true"
    q["Limit"] = "1"
    val allTotal = runCatching {
      getJson<EmbyItemsResult>(server, "/Persons", q).TotalRecordCount
    }.getOrNull() ?: return false
    return allTotal > favTotal
  }

  /**
   * 数每个演员在该媒体库里参与了多少部作品：PersonId → 条目数。
   *
   * **为什么不直接读 `ChildCount`**：`/Persons` 端点的 `Fields` 参数并不支持
   * `ChildCount`（官方文档列出的可选项是 Budget / Chapters / Genres / MediaStreams /
   * People … ，没有它），传了也会被服务端忽略，Person 的 `ChildCount` 于是恒为
   * null —— 演员卡片左下角那个作品数角标就永远画不出来。所以自己数一遍。
   *
   * 口径与「点进这位演员」那一页保持一致（同一个库 + 递归 + 只要非容器条目），
   * 角标上的数字因此和点进去看到的条数对得上。
   *
   * 代价是把整个库逐页拉一遍，所以只索取 `People`、把 MediaSources 这类大字段去掉；
   * 几千条的库通常几次请求就够。调用方应放在后台，不要挡首屏。
   */
  fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    runCatching {
      val counts = HashMap<String, Int>()
      // 按 Id 去重：服务端若忽略 StartIndex 反复返回同一批，没有这层就会把同一部数好几次
      val seen = HashSet<String>()
      var start = 0
      while (true) {
        val page = getItems(
          server = server,
          parentId = parentId,
          startIndex = start,
          limit = COUNT_PAGE_SIZE,
          recursive = true,
          // 只要「本身能播」的条目：把 Folder / Season / BoxSet 这些容器挡掉，
          // 否则一季、一个合集都会被算成一部作品，数字虚高
          isFolder = false,
          fields = "People",
        )
        var fresh = 0
        page.Items.forEach itemLoop@{ item ->
          val id = item.Id
          if (id != null && !seen.add(id)) return@itemLoop
          fresh++
          item.People?.forEach { person ->
            person.Id?.let { pid -> counts[pid] = (counts[pid] ?: 0) + 1 }
          }
        }
        // 库太大就整体放弃：与其拉几十 MB 响应，不如让这几个角标先空着。
        // 只看第一页拿到的总数就够判断，不必真拉完
        if (start == 0 && page.TotalRecordCount > COUNT_MAX_ITEMS) {
          android.util.Log.d(
            "EmbyClient",
            "库规模 ${page.TotalRecordCount} 超过统计上限 $COUNT_MAX_ITEMS，跳过演员作品数统计",
          )
          return@runCatching emptyMap()
        }
        // 本页一条新的都没有 = 服务端在重复给同一批，收手
        if (page.consumedCount == 0 || (fresh == 0 && page.Items.isNotEmpty())) break
        start += page.consumedCount
        if (page.TotalRecordCount > 0 && start >= page.TotalRecordCount) break
        if (start >= COUNT_MAX_ITEMS) break
      }
      counts
    }.onFailure {
      EmbyLog.warn("getPersonWorkCounts 失败：${it.message} —— 演员角标会留空", it)
    }.getOrDefault(emptyMap())

  /**
   * 演员作品数角标 —— 按库规模自动挑一条路（[getPersonWorkCounts] 全库扫描太贵，
   * 逐个问又不敢全信，两头都要的话这里统一收口）。
   *
   * 策略分两档，用**第一条请求的 TotalRecordCount** 判断：
   *
   * - **小库**（≤ [COUNT_FULL_SCAN_MAX]）：走 [getPersonWorkCounts] 全库扫描。
   *   几千条 = 2~6 个请求就把**所有**演员的角标一次算齐，最划算。
   * - **大库**：逐个 PersonId 问服务端要 `TotalRecordCount`（[getPersonWorkCount]，
   *   `Limit=1` 响应极小），但**只查前 [ADAPTIVE_PER_PERSON_LIMIT] 个**。
   *   角标是次要信息，为它把大库拉穿不划算 —— 查不到的留空即可。
   *
   * @param personIds 这一轮要画角标的演员（通常是当前列表那一批）
   */
  fun getPersonWorkCountsAdaptive(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> {
    val targets = personIds.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) return emptyMap()

    val total = runCatching {
      getItems(
        server = server,
        parentId = parentId,
        startIndex = 0,
        limit = 1,
        recursive = true,
        isFolder = false,
        fields = "",
      ).TotalRecordCount
    }.getOrDefault(0)

    if (total in 1..COUNT_FULL_SCAN_MAX) {
      val all = getPersonWorkCounts(server, parentId)
      if (all.isNotEmpty()) return all
    }

    val out = HashMap<String, Int>(targets.size)
    for (pid in targets.take(ADAPTIVE_PER_PERSON_LIMIT)) {
      val n = getPersonWorkCount(server, pid)
      if (n > 0) out[pid] = n
    }
    return out
  }

  /** 大库兜底时逐个问演员的个数上限：够铺满好几屏，又不至于把请求打爆 */
  private const val ADAPTIVE_PER_PERSON_LIMIT = 60

  /**
   * 按名字搜演员。
   *
   * 必须走 `/Persons`：`/Users/{id}/Items` 这条拿媒体的端点**不返回 Person**，
   * 所以即便传了 `IncludeItemTypes=Person`，[getItems] 也永远只能拿到空列表 ——
   * 表现就是「勾选『演员』筛选后什么都搜不到」。
   *
   * @param parentId 限定某个媒体库；null 表示跨库（全库搜索用）
   */
  fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["SearchTerm"] = term
      q["Limit"] = limit.toString()
      // 与 [getActors] 同样的情况：Fields 里的 ChildCount 不被 /Persons 支持，
      // 搜索结果那张卡片的作品数同样得靠调用方另行统计（见 [getPersonWorkCounts]）
      q["Fields"] = "ChildCount"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("searchPersons(\"$term\") 失败：${it.message} —— 搜演员会搜不到", it)
    }.getOrDefault(emptyList())

  /** 某媒体库里出现过的工作室（出品方） */
  fun getStudios(server: EmbyServer, parentId: String? = null): List<EmbyIdName> =
    idNameList(server, "/Studios", parentId)

  /**
   * 一次拿齐筛选面板需要的全部可选项。
   *
   * 走按名字聚合的端点而不是从条目列表里现去重：后者要翻完整个库才准，
   * 而且分页时漏掉的项根本看不见。任一端点失败就退化成空列表，
   * 面板里对应的分组会自动隐藏（年份另有兜底，见 [fallbackYears]）。
   */
  fun getFilterOptions(server: EmbyServer, parentId: String? = null): EmbyFilterOptions {
    // /Genres、/Tags 的返回体里 Items[] 同时带 Id 和 Name，一次请求拿两样
    // （名字给 UI，Id 给「按 Id 筛」的 GenreIds / TagIds 用）。
    val genreItems = idNameList(server, "/Genres", parentId)
    val tagItems = idNameList(server, "/Tags", parentId)
    return EmbyFilterOptions(
      genres = genreItems.map { it.name },
      tags = tagItems.map { it.name },
      genreItems = genreItems,
      tagItems = tagItems,
      years = nameList(server, "/Years", parentId)
        .mapNotNull { it.toIntOrNull() }
        .sortedDescending()
        .takeIf { it.isNotEmpty() } ?: fallbackYears(),
      officialRatings = nameList(server, "/OfficialRatings", parentId),
      persons = getPersons(server, parentId),
      studios = getStudios(server, parentId),
    )
  }

  /** /Years 端点不可用时兜底：给一份从今年往前推的年份表，保证年份筛选不会空着 */
  private fun fallbackYears(): List<Int> {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return (now downTo 1950).toList()
  }

  /**
   * 媒体详情。
   *
   * 显式带 Fields：不带的话 Emby 默认不返回 People / Studios / Tags /
   * ProductionLocations 等字段，「编辑元数据」弹窗里就是一片空白。
   *
   * ⚠️ **不要把 `TagItems` 加进这个清单**：它不在官方 OpenAPI 的 `ItemFields` 可选值里，
   * 而服务端对不认识的字段名有可能直接 400（见 [PHOTO_FIELDS] 的同一条告诫）。
   * 好消息是它**不需要索取**：单条目查询默认就会带 `TagItems`（响应里有
   * `TagItems` 三条、却连 `Tags` 字段都没有）⇒ 详情页读标签用
   * 「优先 TagItems、为空再退回 Tags」的写法，两边都索取就是白搭风险。
   */
  fun getItem(server: EmbyServer, itemId: String): EmbyItem {
    val path = "/Users/${server.userId}/Items/$itemId"
    // 先按「带 ExternalUrls」的清单要一次，失败再退回不带它的（原因见 [ITEM_FIELDS_WITH_EXTERNAL_URLS]）。
    // ⚠️ 必须显式写 `<EmbyItem>`：runCatching 里推断不出 reified 类型参数。
    val withExternalUrls = runCatching {
      getJson<EmbyItem>(server, path, mapOf("fields" to ITEM_FIELDS_WITH_EXTERNAL_URLS))
    }
    // 第二次再失败就让它抛出去 —— 真正的错误（网络 / 鉴权 / 404）不能被吞掉。
    return withExternalUrls.getOrElse {
      getJson(server, path, mapOf("fields" to ITEM_FIELDS_CORE))
    }
  }

  /**
   * 详情页要的字段清单（基础版）。
   *
   * 抽成常量而不是写在 [getItem] 里：它有两个使用者 —— 正常路径和
   * 「ExternalUrls 不被接受时的降级重试」，两处必须逐字一致。
   */
  private val ITEM_FIELDS_CORE: String = listOf(
    "Overview",
    "OriginalTitle",
    "SortName",
    "Genres",
    "Tags",
    "Studios",
    "ProductionLocations",
    "People",
    "OfficialRating",
    "CommunityRating",
    "PremiereDate",
    "ProductionYear",
    "MediaSources",
    "MediaStreams",
    // 详情页「宣传语」一行 + 「外部链接」一行的数据来源，官方 ItemFields 里确实有这两个值
    "Taglines",
    "ProviderIds",
  ).joinToString(",")

  /**
   * [ITEM_FIELDS_CORE] + `ExternalUrls`（详情页「外部链接」一行的**首选**数据源）。
   *
   * 为什么要单列一个、还要在失败时重试：`ExternalUrls` 在 Emby SDK 的 `ItemFields`
   * 枚举里是有的（`BaseItemDto.ExternalUrls` 是标准字段），但它**没被写进官方 OpenAPI
   * 那段 `Fields` 可选值描述**（那段描述本身也不全，例如 `Size` / `Chapters` 都不在里面）。
   * 服务端对不认识的字段名**可能直接 400**，而详情页一旦 400 就整页打不开 ——
   * 这个代价（整页挂掉）远大于收益（少一个降级路径）。
   * 所以：带上它、失败就退回 [ITEM_FIELDS_CORE] 重来一次。
   *
   * 拿到 `ExternalUrls` 的价值：它的 `Url` 是**服务端/元数据插件拼好的完整地址**
   * （如 `http://<host>:<port>/?redirect=JavBus:ACZD-218`），与 Emby Web / Yamby
   * 打开的是同一条链接；拿 `ProviderIds` 自己拼容易拼错（见 `EmbyDetailScreen`）。
   */
  private val ITEM_FIELDS_WITH_EXTERNAL_URLS: String =
    // SiblingIds：单集详情页要拿「同季其它集」。它是标准 BaseItemDto 字段，
    // 但同样不一定被老服务端认 —— 所以放在这个「失败就降级」的清单里，
    // 而不是塞进 [ITEM_FIELDS_CORE]（那会把降级路径一起弄坏）。
    "$ITEM_FIELDS_CORE,ExternalUrls,SiblingIds"

  /**
   * 拉取条目的片头 / 片尾分段（Emby 4.7+ 原生 Intro Skip）。
   *
   * 对应端点：`GET /Users/{UserId}/Items/{Id}/Intros`。
   *
   * ⚠️ 两个「拿不到」要分清：
   * - 服务端没扫描过片头标记 ⇒ 返回**空 Segments**（正常，不是错误）；
   * - 服务端版本 < 4.7 / 端点不存在 ⇒ 抛异常，由仓库层兜成空结果。
   *
   * 调用方（播放页）拿空结果时退到「固定时长兜底」，见 `PlayerPreferences.autoSkipIntro`。
   */
  fun getItemIntros(server: EmbyServer, itemId: String): EmbyIntrosResult =
    getJson(server, "/Users/${server.userId}/Items/$itemId/Intros")

  /**
   * 关键词搜索。
   *
   * @param includeItemTypes 限定类型，传 null 表示不限（Emby 默认只搜部分类型，
   *                         传入 Movie/Series/Episode 等可精确控制）
   */
  /** Failure-preserving, cancellable search API. Never routes through legacy actor fallbacks. */
  suspend fun searchMediaPage(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>,
    limit: Int = 80,
  ): EmbyItemsResult {
    require(term.isNotBlank())
    val request = authedRequest(server, "/Users/${server.userId}/Items", mapOf(
      "SearchTerm" to term.trim().take(200),
      "Recursive" to "true",
      "IncludeItemTypes" to includeItemTypes.joinToString(","),
      "StartIndex" to "0",
      "Limit" to limit.coerceIn(1, 120).toString(),
      "SortBy" to "SortName",
      "Fields" to "BasicSyncInfo,ProductionYear,CommunityRating",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
    )).get().build()
    return EmbyItemPolicy.filterSupported(json.decodeFromString<EmbyItemsResult>(EmbySearchHttp.body(request)))
  }

  /** A single bounded /Persons request: no full-library fetch or per-actor work-count requests. */
  suspend fun searchPersonPage(server: EmbyServer, term: String, limit: Int = 40): EmbyItemsResult {
    require(term.isNotBlank())
    val request = authedRequest(server, "/Persons", mapOf(
      "UserId" to server.userId,
      "SearchTerm" to term.trim().take(200),
      "Recursive" to "true",
      "StartIndex" to "0",
      "Limit" to limit.coerceIn(1, 40).toString(),
      "SortBy" to "SortName",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
    )).get().build()
    val page = json.decodeFromString<EmbyItemsResult>(EmbySearchHttp.body(request))
    return page.copy(Items = page.Items.filter { !it.Id.isNullOrBlank() && !it.Name.isNullOrBlank() }
      .map { it.copy(Type = "Person") })
  }

  internal suspend fun authenticateForSearch(server: EmbyServer): EmbyAuthResult {
    val request = identityRequest(server, "${EmbyEndpoints.apiBaseUrl(server)}/Users/AuthenticateByName")
      .post(jsonBody(loginJson(server.username, server.password))).build()
    return json.decodeFromString(EmbySearchHttp.body(request))
  }

  fun searchItems(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>? = null,
    limit: Int = 60,
    /**
     * 排序方式。默认 SortName（按名称），与改造前行为一致。
     *
     * Emby 的 /Items 接受 SearchTerm 与 SortBy 组合，所以搜索结果同样能排序 ——
     * 媒体库里搜索后再切排序要真的生效，就必须把用户选的排序传进来。
     */
    sortBy: String? = "SortName",
    sortOrder: String? = null,
    /**
     * 搜索范围限定在某个目录下。
     *
     * null = 全服搜索（老口径，默认值，所有既有调用一行不改）。
     * 非 null + [recursive] = false 就是界面上的「仅本目录」：
     * 只在这一层的直接子项里按名字找。
     *
     * 为什么必须同时给 recursive：Emby 的 `ParentId` 默认是**带递归**的，
     * 单给 ParentId 等于「在这个子树里搜」，与「仅本目录」不是一回事。
     */
    parentId: String? = null,
    recursive: Boolean = true,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = includeItemTypes,
    searchTerm = term,
    startIndex = 0,
    limit = limit,
    recursive = recursive,
  )

  /**
   * 某剧集下的季列表。
   *
   * ⚠️ [startIndex] / [limit] 是后加的分页参数（对应清单 1.1）——
   * 原先这里一次性拿回全部季，长剧（几十季）会白拉一堆详情。
   * 传 [limit] = 0 / 不传时行为与从前一致（服务端按默认返回）。
   */
  fun getSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Seasons",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to (limit ?: 0).toString(),
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 某季（或整部剧集）下的剧集列表。
   *
   * ⚠️ 分页参数是后加的（对应清单 1.1）：原先一次拿回全部集，
   * 长剧（几百集）响应体很大且列表全进内存。现在由详情页按季分页拉。
   */
  fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Episodes",
    mapOf(
      "UserId" to server.userId,
      "SeasonId" to seasonId,
      "StartIndex" to startIndex.toString(),
      "Limit" to (limit ?: 0).toString(),
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 收藏夹列表（用户标过 IsFavorite 的项）。
   *
   * 默认只返回视频类媒体，音乐与图片不在播放器收藏页展示。
   *
   * ## 为什么排序要交给服务端（而不是拉回来本地排）
   *
   * 这个接口是**分页**的（收藏页一页 100 条，滚到底再续拉）。本地排序只作用于
   * 「已经拉回来的那几页」，而**分页切分本身是按 SortBy 走的**：
   * 排序口径写死 `SortName Ascending` 时，服务端返回的第一页是「名字最靠前的 100 条」，
   * 客户端再按入库时间排一遍 —— 得到的其实是「名字前 100 条里最晚入库的」，
   * 真正最晚入库的那部片子如果名字靠后，压根不在这一页里，永远进不了第一屏。
   * 收藏越多越离谱，且只在特定排序下出现，看起来就像「排序偶尔不生效」。
   *
   * 所以凡是服务端有对应排序的（名称 / 年份 / 评分 / 入库时间），都必须下推，
   * 让**分页与排序同口径**；只有 `收藏时间` 这种服务端没有的，才退回本地排
   * （见 EmbyFavoritesScreen 的 FavoriteSort.FAVORITED）。
   *
   * @param sortBy Emby `SortBy` 取值（`SortName` / `ProductionYear` / `CommunityRating` /
   *   `DateCreated` …）。传 null 保持原来的 `SortName`，老调用方行为不变。
   * @param sortOrder `Ascending` / `Descending`
   */
  fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    includeItemTypes: List<String>? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = sortBy ?: "SortName",
    sortOrder = sortOrder ?: "Ascending",
    filters = listOf("IsFavorite"),
    mediaTypes = mediaTypes,
    includeItemTypes = includeItemTypes,
    startIndex = startIndex,
    limit = limit,
    // 收藏页要按年份 / 评分 / 添加时间做**本地排序**，这几个字段必须显式索取
    // （见 [ITEM_FIELDS_WITH_SORT_KEYS] 的说明：不索取就全是 null，排序等于没排）
    fields = ITEM_FIELDS_WITH_SORT_KEYS,
  )

  /**
   * 收藏的**类型**（Genre）列表（收藏页「类型」tab 用）。
   *
   * Genre 是带 UserData 的正式条目，可以被收藏 —— 所以与 [getFavoriteItems] 同一套口径，
   * 让服务端直接按 `Filters=IsFavorite` 筛。
   *
   * ⚠️ **不传 MediaTypes**：类型没有媒体形态（不像影片是 Video），
   * 硬塞一个 MediaTypes 会让部分服务端整页拉空。
   *
   * @param limit 类型数量通常几十个，默认给 200 一次拉完，不分页。
   */
  fun getFavoriteGenres(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
    sortOrder = "Ascending",
    filters = listOf("IsFavorite"),
    includeItemTypes = listOf("Genre"),
    startIndex = startIndex,
    limit = limit,
  )

  /**
   * 收藏的**工作室**（Studio）列表（收藏页「工作室」tab 用）。
   *
   * 口径与 [getFavoriteGenres] 完全一致，只把条目类型换成 Studio。
   */
  fun getFavoriteStudios(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
    sortOrder = "Ascending",
    filters = listOf("IsFavorite"),
    includeItemTypes = listOf("Studio"),
    startIndex = startIndex,
    limit = limit,
  )

  /**
   * 随机播放：拿 [limit] 个随机项（按 Random 排序）。
   * 用户可在播放页点"随机播放"调用，把返回的 IDs 依次送给 PlayerActivity。
   */
  fun getRandomItems(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = "Random",
    sortOrder = "Ascending",
    includeItemTypes = includeItemTypes,
    startIndex = 0,
    limit = limit,
    isFavorite = isFavorite,
  )

  // ════════════════════════════════════════════════════════════════════════
  // 2.5 相似推荐 / 即混（Instant Mix）/ 续播（NextUp）
  // ════════════════════════════════════════════════════════════════════════
  // 这三个端点是**服务端算法**。客户端还另有一套本地打分算法（见
  // `ui/browser/emby/discover/LocalSimilarity.kt`），两者由
  // `SimilarAlgorithm` 偏好切换 —— 服务端没刮削或算法不产结果时，
  // 本地那套能兜住，不至于整个区块空着。

  /**
   * 相似推荐：`GET /Items/{Id}/Similar`。
   *
   * ⚠️ **没有** `MediaSourceId` / `IsMusic` / `IsLiveTvPrograms` / `IsNewsPrograms`
   * 这几个参数（它们只存在于 Jellyfin，Emby 的参数集比 Jellyfin 少），
   * 写上去会被服务端直接忽略，调试时极难发现「为什么筛选没生效」。
   *
   * @param itemId 种子条目（拿它的类型 / 人员 / 年代去做相似匹配）
   * @param limit 返回条数。Emby 服务端对 `Similar` 的实现是「查库再算相似度」，
   *   limit 给太大反而拖慢响应，20~50 比较合适。
   * @param includeItemTypes 只要这些类型；不传表示不限。
   *   默认 `Movie,Series` —— 单集（Episode）混进来会把区块塞满同一部剧的几十集。
   * @param excludeIdList 要排除的条目 Id（例如种子自己）。
   *   服务端通常自己会排除种子，但**第三方兼容实现不总是排除**，
   *   所以客户端还会再滤一次（见 `SimilarRecommendationViewModel`）。
   */
  fun getSimilarItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 24,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Items/$itemId/Similar",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      "ExcludeIdList" to excludeIdList?.joinToString(","),
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 即混：`GET /Items/{Id}/InstantMix`（服务端基于种子自动续播一批）。
   *
   * 参数集与 [getSimilarItems] **完全一致**（同一个服务端实现里出来的），
   * 所以复用一套参数拼装，不要以为它有什么特殊参数。
   *
   * ⚠️ **不存在 `/Shows/{Id}/InstantMix`** —— 那是幻觉端点。真要「按某部剧接着播」，
   * 只能用 `/Items/{Id}/InstantMix` 再配 `Filters=IsResumable`。
   */
  fun getInstantMixItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    filters: List<String>? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Items/$itemId/InstantMix",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      "Filters" to filters?.takeIf { it.isNotEmpty() }?.joinToString("|"),
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 续播（NextUp）：`GET /Shows/NextUp`。
   *
   * ⚠️ **两个极易写错的点**：
   * 1. `UserId` 是**必填 query 参数**，不是 path 段 —— 写成
   *    `/Shows/{UserId}/NextUp` 会 404，且报错信息毫无指向性。
   * 2. **不存在 `/Shows/{Id}/NextUp`**（按剧集取续播）这个端点。
   *    要限定某部剧，用 `SeriesId` query 参数。
   *
   * 服务端没启用「剧集自动续播」时这个列表会是空的，UI 要能接受空态。
   */
  fun getNextUpItems(
    server: EmbyServer,
    limit: Int = 20,
    seriesId: String? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/NextUp",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "SeriesId" to seriesId,
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )

  // ════════════════════════════════════════════════════════════════════════
  // 3. 收藏 / 删除
  // ════════════════════════════════════════════════════════════════════════

  // 3.4 Read-only stream URL resolution
  // 3.4 直链（302）预解析
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 预解析「302 直链」：把 `/Videos/{id}/stream` 背后真正的直链取出来。
   *
   * ## 为什么需要
   *
   * 网盘 / STRM 这类源，Emby 的 `/Videos/{id}/stream` 会回一个 **302 跳转**到
   * 网盘给的临时直链。正常情况播放器自己会跟着跳转，不用管；但两类场景会翻车：
   *
   *  · **播放器不跟 302**（部分内核把 302 当错误 / 需要额外的 Referer 头）→ 直接播不动；
   *  · **直链有有效期**，播到一半过期 → 中途卡死，重开一次又能放。这正是
   *    「预解析失效重试」要解决的：把最终直链提前取出来交给播放器，出错时再重取一次。
   *
   * ## 怎么取
   *
   * 用 `Range: bytes=0-0` 发一个最小 GET，让 OkHttp 自动跟完跳转链，
   * 从最终请求的 URL 拿到直链，随即关闭响应 —— 只读 1 个字节，几乎不产生流量。
   * 只有**确实发生了跳转**才返回直链；没跳转（说明服务端是直出、或本身就是直链）
   * 返回 null，调用方沿用原地址即可，不改变既有行为。
   *
   * 失败时按 [attempts] 重试，全部失败返回 null（调用方回退到原始 URL，绝不因为
   * 解析失败而挡住播放）。
   *
   * @return 最终直链；未发生跳转 / 解析失败均为 null
   */
  /**
   * 预解析「302 直链」：拿 `/Videos/{id}/stream` 背后跳转到的真实地址，
   * **同时**把这次的 HTTP 状态码翻译成「能不能播」的结论。
   *
   * ## 为什么要带出状态码（v128）
   *
   * 这个方法本来就会发一次 `Range: bytes=0-0`。以前它只把结果当「直链」用，
   * 被拒（403 / 404）时静默返回 null、让调用方回退原始地址 —— 于是死链照样被交给
   * mpv，而 mpv 遇到死链会在 native 层崩掉（整个 App 消失，Java 层拦不到）。
   *
   * 现在把**本来就已经拿到手**的 `resp.code` 一并翻译出来：调用方据此在起播前拦下死链。
   * **不额外发任何请求** —— 防崩能力是白捡的，这是刻意的设计约束。
   *
   * ## 判定口径
   *
   * · 2xx → [StreamProbe.Ok]，`finalUrl` 是跟完跳转后的地址（没跳转就是原地址）；
   * · 401 / 403 / 404 / 410 → [StreamProbe.Denied]，**立即返回不再重试**
   *   （这些码重试没有意义，每多试一次就多打一次 CDN —— 网盘/CDN 会记风控）；
   * · 5xx / 416 / 400 / 405 之类 → [StreamProbe.Unknown]，不下死结论
   *   （服务端临时抽风或对 Range 处理特殊，交给调用方回退原始地址，别误拦用户）；
   * · 网络异常 / 超时 → [StreamProbe.Unknown]，按可播处理。
   *
   * @return [StreamProbe.Ok] 可播（含最终直链）；[StreamProbe.Denied] 明确不可播；
   *         [StreamProbe.Unknown] 探不出来，调用方回退原始地址
   */
  fun resolveStreamUrl(
    server: EmbyServer,
    itemId: String,
    attempts: Int = 2,
    /**
     * 多版本媒体要指定的版本（`MediaSources[i].Id`）。null = 不指定，由服务端挑默认版本。
     * 探测直链与真正拉流必须是同一个版本，否则「解析出来的是 4K、播的却是 1080P」。
     */
    mediaSourceId: String? = null,
    /**
     * **单次调用的硬上限**（毫秒）。
     *
     * ⚠️ 这条请求挂在**起播关键路径**上：`EmbyViewModel.play()` 拿到结果之前不会拉起
     * 播放器。共享 client 的 `readTimeout` 是 60 秒，而 strm 条目在服务端还没 probe
     * 过源文件时（详情页「没有媒体信息」正是这个状态），`/Videos/{id}/stream` 会先
     * 让服务端去读远端文件、把大小/时长写库，这一次能挂到分钟级（× [attempts] 次重试）。
     * 现象就是「第一次点播放一直卡在加载中，返回再进详情页点播放立马就能播」——
     * 第二次服务端已经 probe 完、有缓存了。所以这里必须自己带上限，超时按
     * [StreamProbe.Unknown] 处理、把地址原样交给播放器，绝不久等。
     */
    callTimeoutMs: Long = STREAM_PROBE_CALL_TIMEOUT_MS,
  ): StreamProbe {
    val original = videoStreamUrl(server, itemId, mediaSourceId = mediaSourceId)
    repeat(attempts.coerceAtLeast(1)) {
      val outcome = runCatching {
        val req = identityRequest(server, original)
          // 只取 1 个字节：既让服务端走完整跳转链，又不真的下载内容
          .header("Range", "bytes=0-0")
          // ⚠️ 必须显式带上与播放器一致的 UA。网盘直链（115 这类）的签名常把 UA
          // 算进去：探测用 OkHttp 默认 UA 拿到直链、再交给 mpv 用另一套 UA 去请求，
          // 服务端就会以 403 拒绝 —— 现象是「解析成功、播放必失败」。
          .header("User-Agent", server.userAgent)
          .get()
          .build()
        val call = httpClient.newCall(req)
        // per-call 超时：不动共享 client 的全局口径（下载 / 图片那些档位不受影响）
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
          when {
            resp.isSuccessful -> {
              // response.request 是「跟完跳转后的最后一个请求」，它的 URL 就是直链
              val finalUrl = resp.request.url.toString()
              StreamProbe.Ok(
                finalUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                  ?: original
              )
            }
            resp.code in DENIED_STREAM_CODES -> {
              android.util.Log.w(
                "EmbyClient",
                "直链被拒（HTTP ${resp.code} @ ${resp.request.url.host}）—— 判定不可播",
              )
              StreamProbe.Denied(streamDeniedMessage(resp.code))
            }
            else -> {
              // 416 / 400 / 405…：不下死结论，交给调用方回退原始地址
              android.util.Log.i(
                "EmbyClient",
                "直链探测返回 HTTP ${resp.code} @ ${resp.request.url.host}，按未知处理",
              )
              null
            }
          }
        }
      }.getOrElse { error -> if (error is CancellationException) throw error else null }

      // Ok / Denied 都是明确结论，立即返回。
      // Denied 不重试是关键：4xx 重试注定还是 4xx，只会多打一次 CDN。
      if (outcome != null) return outcome
    }
    return StreamProbe.Unknown
  }

  /** 能把一条流地址判死的 HTTP 状态码（见 [resolveStreamUrl] 的判定口径）。 */
  private val DENIED_STREAM_CODES = setOf(401, 403, 404, 410)

  /**
   * 302 预解析的**单次调用上限**（毫秒）。
   *
   * 2 秒是刻意选的：4xx 死链基本都在百毫秒内回来（判死链的能力不受影响），
   * 而「服务端要先 probe 远端文件」这种慢路径一秒都不该让用户等 ——
   * 超时按 [StreamProbe.Unknown] 放行，地址照样交给播放器，它自己会跟 302。
   */
  private const val STREAM_PROBE_CALL_TIMEOUT_MS = 2_000L

  /** 把「判死」的状态码翻成可直接给用户看的中文原因。 */
  private fun streamDeniedMessage(code: Int): String = when {
    code == 401 -> "登录状态已失效（HTTP 401），请重新登录这台服务器"
    code == 403 -> "服务器拒绝访问（HTTP 403）—— 直链可能已过期，或这个文件没有访问权限"
    code == 404 -> "服务器找不到这个文件（HTTP 404）—— 它可能已被移动、重命名或删除"
    code == 410 -> "这个文件已从服务器移除（HTTP 410）"
    code in 500..599 -> "服务器出错了（HTTP $code），请稍后重试"
    else -> "服务器返回 HTTP $code，无法播放"
  }

  /**
   * 播放前探测一条**已经拼好的**流地址能不能真的取到数据。
   *
   * ## 为什么必须探在播放之前
   *
   * 403 / 404 的死链（网盘直链过期、文件被移走、STRM 指向失效）一旦交给 mpv，
   * libmpv 会在 **native 层**崩掉 —— 现象是「点播放 → 整个 App 直接消失回桌面」，
   * 而且拿不到任何 Java 堆栈（`GlobalExceptionHandler` 收不到，崩溃页也不会出现）。
   * 这类崩溃发生在 native 栈里，`try/catch` / `runCatching` 一律无效，
   * **唯一可靠的防线就是在把地址交出去之前确认它能取到数据**。
   *
   * ## 判定口径
   *
   * · 与 [resolveStreamUrl] 一致：`Range: bytes=0-0` 只取一个字节，
   *   并带 `STREAM_USER_AGENT`（网盘签名常把 UA 算进去，换 UA 就 403）；
   * · 单次 2.5 秒超时，用 per-call timeout，不动共享 client 的全局口径；
   * · **只有服务端明确拒绝（4xx / 5xx）才算不可播**。超时、DNS 失败、连接被重置
   *   一律返回 [StreamProbe.Unknown] 并按可播处理 —— 本地网络抖一下不该被拦在门外。
   *
   * @return [StreamProbe.Ok] 可播（含跟完跳转后的最终地址，可直接交给播放器）；
   *         [StreamProbe.Denied] 明确不可播，`message` 是可直接展示给用户的中文原因；
   *         [StreamProbe.Unknown] 探不出来，调用方照旧播放
   */
  fun probeStreamUrl(
    url: String,
    userAgent: String = STREAM_USER_AGENT,
    server: EmbyServer? = null,
  ): StreamProbe {
    // 本地文件 / content:// / 本地 ISO 代理不需要探测，直接放行
    if (!url.startsWith("http://") && !url.startsWith("https://")) return StreamProbe.Ok(url)
    return runCatching {
      val builder = if (server == null) Request.Builder().url(url).header("User-Agent", userAgent)
        else EmbyRequestHeaders.request(server, url)
      val req = builder.header("Range", "bytes=0-0").get().build()
      val call = httpClient.newCall(req)
      call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      call.execute().use { resp ->
        val code = resp.code
        when {
          resp.isSuccessful -> StreamProbe.Ok(resp.request.url.toString())
          code == 401 -> StreamProbe.Denied("登录状态已失效（HTTP 401），请重新登录这台服务器")
          code == 403 ->
            StreamProbe.Denied("服务器拒绝访问（HTTP 403）—— 网盘直链可能已过期，或这个文件没有访问权限")
          code == 404 ->
            StreamProbe.Denied("服务器找不到这个文件（HTTP 404）—— 它可能已被移动、重命名或删除")
          code in 500..599 -> StreamProbe.Denied("服务器出错了（HTTP $code），请稍后重试")
          else -> StreamProbe.Denied("服务器返回 HTTP $code，无法播放")
        }
      }
    }.getOrElse { error -> if (error is CancellationException) throw error else StreamProbe.Unknown }
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.5 元数据操作（编辑 / 刮削 / 刷新）
  // ════════════════════════════════════════════════════════════════════════

  /** Read existing item images. */
  fun getItemImages(server: EmbyServer, itemId: String): List<EmbyImageInfo> =
    execJson(authedRequest(server, "/Items/$itemId/Images").build())

  /** 可用的远程图源（GET /Items/{Id}/RemoteImages/Providers） */
  fun getRemoteImageProviders(server: EmbyServer, itemId: String): List<EmbyImageProviderInfo> =
    execJson(authedRequest(server, "/Items/$itemId/RemoteImages/Providers").build())

  /** 从远程图源搜某类图片（GET /Items/{Id}/RemoteImages） */
  fun searchRemoteImages(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    providerName: String? = null,
    includeAllLanguages: Boolean = true,
  ): List<EmbyRemoteImageInfo> =
    execJson<EmbyRemoteImageResult>(
      authedRequest(
        server,
        "/Items/$itemId/RemoteImages",
        mapOf(
          "Type" to imageType,
          "ProviderName" to providerName,
          "IncludeAllLanguages" to includeAllLanguages.toString(),
          // 跟 Emby Web 端一致：一次取 50 张足够挑，不设的话有的图源会全量返回几百张
          "Limit" to "50",
        ),
      ).build(),
    ).Images

   // ════════════════════════════════════════════════════════════════════════
  // 3.7 识别 / 刮削（Identify）
  // ════════════════════════════════════════════════════════════════════════

  /** 条目已绑定的外部 ID（GET /Items/{Id}/ExternalIdInfos），用于预填识别对话框 */
  fun getExternalIdInfos(server: EmbyServer, itemId: String): List<EmbyExternalIdInfo> =
    execJson(authedRequest(server, "/Items/$itemId/ExternalIdInfos").build())

  /**
   * 按条件远程检索元数据（POST /Items/RemoteSearch/{Type}）。
   *
   * [searchType] 是 **检索类型**（Movie / Series / BoxSet / Person …），
   * 跟条目的 Type 大多数时候同名，但剧集（Episode）要按 Series 去搜，不能直接拿 Type 填。
   *
   * 请求体必须是 `{"SearchInfo":{...},"ItemId":n,...}` 这层壳 ——
   * 官方 schema 里 body 类型是 `RemoteSearchQuery<MovieInfo>`，
   * 直接把 lookup 对象发过去的话服务器读到的 SearchInfo 是 null，返回永远是空数组。
   */
  fun remoteSearch(
    server: EmbyServer,
    searchType: String,
    lookup: EmbyItemLookupInfo,
    itemId: String? = null,
  ): List<EmbyRemoteSearchResult> {
    val path = "/Items/RemoteSearch/$searchType"
    // 主请求：ItemId 按字符串发
    val body = json.encodeToString(EmbyRemoteSearchQuery(SearchInfo = lookup, ItemId = itemId))
    val first = runCatching { execJson<List<EmbyRemoteSearchResult>>(authedRequest(server, path).post(jsonBody(body)).build()) }
    if (first.isSuccess) return first.getOrThrow()

    // 兜底：少数版本只认数字 ItemId（严格按 OpenAPI 生成的那批），换形式再试一次。
    // 两次都失败时抛第一次的错误，它更贴近「实际用的那种形式」的问题。
    val numeric = itemId?.toLongOrNull() ?: throw first.exceptionOrNull()!!
    runCatching {
      execJson<List<EmbyRemoteSearchResult>>(authedRequest(server, path).post(jsonBody(json.encodeToString(EmbyRemoteSearchQueryNumeric(SearchInfo = lookup, ItemId = numeric)))).build())
    }.getOrElse { throw first.exceptionOrNull()!! }
    // 上一行要么返回、要么抛，这里到不了；写出来是为了让编译器确认返回类型非空
    @Suppress("UNREACHABLE_CODE")
    return emptyList()
  }

  // Read-only raw metadata and server information
  // 3.8 演职员合并（Person 去重）：整包读写条目 / 管理员探测 / 服务端任务
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 取条目的**原始 JSON 文本**（不解析成 [EmbyItem]）。
   *
   * ⚠️ 演职员合并改的是条目里的 `People` 引用，而 `POST /Items/{id}` **必须回传完整 payload**
   * （Emby 开发者 Luke 官方确认："you have to include the complete payload"，
   * 缺字段会被**清空** —— 社区里有人被清掉 plot / year / rating）。
   * 我们的 [EmbyItem] 只声明了四十来个字段，序列化回去恰好就是「不完整的 payload」，
   * 所以这里必须原文进、原文出，中途只在 `JsonObject` 上做定点修改。
   *
   * 走 `Users/{uid}/Items/{id}` 这条（Luke 明确推荐）而不是 `/Items/{id}`：
   * 用户维度的那条会带上 UserData，字段更全。
   *
   * `Fields` 里**必须包含 `People`** —— 不带的话返回体里没有 People，
   * 改写函数会认为「这条没事要改」从而静默跳过，表现就是「合并点了但作品一部都没动」。
   */
  fun getItemRawJson(server: EmbyServer, itemId: String): String =
    execString(
      authedRequest(
        server,
        "/Users/${server.userId}/Items/$itemId",
        mapOf("Fields" to RAW_ITEM_FIELDS),
      ).get().build(),
    )

  /**
   * 当前登录账号是不是管理员。
   *
   * 为什么必须先问：改条目元数据（`POST /Items/{id}`）是**管理员操作**，
   * 非管理员会被拒。与其让用户点了半天在每个条目上吃 403，
   * 不如进页面就把整块写作能力禁掉并说明原因。
   *
   * 探测失败（网络 / 端点不可用）返回 false —— **保守当作没有权限**，
   * 不能让一次失败的网络请求把「不能写」误判成「能写」。
   */
  fun fetchIsAdmin(server: EmbyServer): Boolean =
    runCatching {
      getJson<EmbyUserDetailDto>(server, "/Users/${server.userId}").Policy?.IsAdministrator == true
    }.onFailure {
      EmbyLog.warn("fetchIsAdmin 失败：${it.message} —— 保守当作无权限（写作能力会被禁掉）", it)
    }.getOrElse { error -> if (error is CancellationException) throw error else false }

  /** 服务端定时任务清单（找「刷新人员」用） */
  fun getScheduledTasks(server: EmbyServer): List<EmbyScheduledTask> =
    runCatching { getJson<List<EmbyScheduledTask>>(server, "/ScheduledTasks") }
      .onFailure { EmbyLog.warn("getScheduledTasks 失败：${it.message}", it) }
      .getOrDefault(emptyList())

   // ════════════════════════════════════════════════════════════════════════
  // 4. 播放进度上报（Playstate）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 上报播放开始。调用时机：播放页准备开始播放时。
   *
   * @param playSessionId **客户端自己生成的**会话 id（见下方说明）。传了就会写进 body，
   *   返回值也会在服务端不回显时退回它。
   * @return 本次播放的会话 id —— 服务端回显的就用它的，否则就是传进来的那个。
   */
  /**
   * 向服务器要一个**它签发的**播放会话（`POST /Items/{id}/PlaybackInfo`，`IsPlayback=true`）。
   *
   * @return `PlaySessionId to MediaSources[0].Id`；拿不到（老版本 / 该条目没有可用源 / 网络失败）
   *   返回 null，调用方退回自生成的 UUID。
   *
   * ## 为什么要这一步（v231）
   *
   * v226 起我们是自己造 UUID 当 `PlaySessionId` 的 —— Start / Progress / Stopped 三处一致，
   * 进度确实能记上（位置、百分比都对）。但「**条目什么时候被顶到继续观看最前面**」取决于
   * 服务端有没有把 `LastPlayedDate` 刷新，而那是挂在**服务端会话**上的：自己造的 id 服务端
   * 根本没有这条会话，收尾时就跳过了这一步。
   *
   * 现象因此非常割裂：详情页有进度（位置写了）、首页却不往前提（时间没写）。
   * 实测同一条服务器上，别的播放器（Yamby 先 PlaybackInfo 再上报）播过的片会立刻排到第一。
   *
   * 代价：每次起播多一次请求。它和「预解析直链」无关 —— PlaybackInfo 只打到自己的 Emby，
   * 不碰网盘 CDN，也不会跟 302。
   */
  suspend fun requestPlaybackSession(
    server: EmbyServer,
    itemId: String,
  ): Pair<String, String?>? {
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          // IsPlayback=true 才是「我要播了」（false 是探测），会话的语义在这里
          "IsPlayback" to true,
          "AutoOpenLiveStream" to false,
          "EnableDirectPlay" to true,
          "EnableDirectStream" to true,
          "EnableTranscoding" to false,
        ),
      ).toString()
    val info = runCatching {
      execJson<PlaybackInfoResult>(
        authedRequest(server, "/Items/$itemId/PlaybackInfo", mapOf("DeviceId" to server.deviceId))
          .post(jsonBody(payload))
          .build(),
      )
    }.getOrElse { error -> if (error is CancellationException) throw error else null } ?: return null
    val sessionId = info.PlaySessionId?.takeIf { it.isNotBlank() } ?: return null
    return sessionId to info.MediaSources?.firstOrNull()?.Id
  }

  fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    playMethod: String = "DirectStream",
    /** 多版本媒体：本次播放的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
    /**
     * 会话 id（客户端生成，官方客户端也是自己生成 GUID 带上来的）。
     *
     * ⚠️ 为什么必须由客户端给：Emby 的 `POST /Sessions/Playing` 正常返回 **204**（空 body）
     * 或 `{}`，**里面没有 `PlaySessionId` 字段**。只从响应里读的话永远是空串，而
     * Progress / Stopped 拿着空 id 去对账，服务端既匹配不上会话、又不会按 ItemId 兜底，
     * 整场播放一条进度都记不下来 —— 现象就是「退出后已观看 0%，剩余=总时长」。
     */
    playSessionId: String? = null,
  ): String {
    val obj = buildMap {
      put("ItemId", itemId)
      if (!playSessionId.isNullOrBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      put("PlayMethod", playMethod)
      mediaSourceId?.let { put("MediaSourceId", it) }
      audioStreamIndex?.let { put("AudioStreamIndex", it) }
      subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
      put("CanSeek", true)
      put("IsMuted", false)
      put("IsPaused", false)
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing")
      .post(jsonBody(body))
      .build()
    val resp = execString(req)
    // 服务端回显了就用它的（个别版本会）；没回显（204 / 空 JSON，最常见）就用我们自己的。
    return try {
      json.parseToJsonElement(resp).jsonObject["PlaySessionId"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: playSessionId.orEmpty()
    } catch (_: Exception) {
      playSessionId.orEmpty()
    }
  }

  /**
   * 上报播放进度（建议每 10 秒一次 + 暂停/恢复时）。
   *
   * 两个字段**不能省**，理由都是「服务端要靠它们把这条进度归到哪一集、哪个版本」：
   *
   * · `MediaSourceId`：多版本条目（4K / 1080P 各一条）上，Start 带了它、Progress 不带，
   *   服务端会把这次进度记到**默认版本**上，于是别的版本续播位置一直是旧的。
   * · `PlaySessionId`：只在**非空**时发送。送一个空串过去，服务端既匹配不上既有会话，
   *   又不会走「按 ItemId 找当前会话」的兜底，这条 Progress 就被丢掉了；
   *   整个字段缺席反而能命中兜底。
   */
  fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
    isMuted: Boolean = false,
    /** 多版本媒体：正在播的那个版本（`MediaSources[i].Id`）；null = 未知 / 单版本 */
    mediaSourceId: String? = null,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      if (playSessionId.isNotBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      put("IsPaused", isPaused)
      put("IsMuted", isMuted)
      put("PlayMethod", "DirectStream")
      mediaSourceId?.let { put("MediaSourceId", it) }
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing/Progress")
      .post(jsonBody(body))
      .build()
    execString(req)
  }

  /** 上报播放停止（退出播放页时调用）。[playSessionId] / [mediaSourceId] 的取舍同上。 */
  fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    mediaSourceId: String? = null,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      if (playSessionId.isNotBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      mediaSourceId?.let { put("MediaSourceId", it) }
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing/Stopped")
      .post(jsonBody(body))
      .build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 5. 图片 / 视频流 URL 构造（不调用，只生成 URL 给 Coil/PlayerActivity）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 构造图片 URL。Emby 图片 API：
   *   GET /Items/{itemId}/Images/{imageType}?tag={tag}&maxWidth={w}&maxHeight={h}&quality=90
   *
   * imageType: Primary / Backdrop / Thumb / Banner / Logo / Art / Disc
   */
  fun imageUrl(
    server: EmbyServer,
    itemId: String,
    imageType: String = "Primary",
    tag: String? = null,
    maxWidth: Int = 480,
    maxHeight: Int? = null,
    imageIndex: Int? = null,
  ): String {
    require(imageIndex == null || imageIndex >= 0)
    val server = EmbyHttp.sessionServer(server)
    val q = LinkedHashMap<String, String>()
    if (tag != null) q["tag"] = tag
    q["maxWidth"] = maxWidth.toString()
    maxHeight?.let { q["maxHeight"] = it.toString() }
    q["quality"] = "90"
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    val indexPath = imageIndex?.let { "/$it" }.orEmpty()
    return "${EmbyEndpoints.apiBaseUrl(server)}/Items/$itemId/Images/$imageType$indexPath?$qs"
  }

  /**
   * 构造**照片**图片 URL（照片浏览器 / 照片卡片用）。
   *
   * 与 [imageUrl] 同一端点（`GET /Items/{itemId}/Images/Primary`），差别只有一个
   * 可选的 [format] 参数：传 `"jpg"` 时服务端把原图**转码成 JPEG** 再返回
   * （OpenAPI 原文：`Determines the output foramt of the image - original,gif,jpg,png`）。
   * 这是 HEIC 的兜底通道 —— 设备解不了 HEIC 时，让服务端转码而不是本地解。
   *
   * 为什么不直接改 [imageUrl] 加参数：照片链路整体是新增功能（相册/浏览器/混合库
   * 照片分支），单独一个函数能让视频侧调用点**一行都不用动**，回归风险为零。
   *
   * @param format 输出格式；null = 服务端按原图格式返回（默认）。HEIC 兜底传 `"jpg"`。
   */
  fun photoImageUrl(
    server: EmbyServer,
    itemId: String,
    tag: String? = null,
    maxWidth: Int = 1280,
    maxHeight: Int? = null,
    format: String? = null,
  ): String {
    val server = EmbyHttp.sessionServer(server)
    val q = LinkedHashMap<String, String>()
    if (tag != null) q["tag"] = tag
    q["maxWidth"] = maxWidth.toString()
    maxHeight?.let { q["maxHeight"] = it.toString() }
    format?.let { q["Format"] = it }
    q["quality"] = "90"
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "${EmbyEndpoints.apiBaseUrl(server)}/Items/$itemId/Images/Primary?$qs"
  }

  /**
   * 构造静态视频流 URL，直接传输原文件，不请求服务器转码。
   *
   * 调用方把这个 URL 传给 PlayerActivity，作为 mpv 的播放源。
   */
  fun videoStreamUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
  ): String {
    val server = EmbyHttp.sessionServer(server)
    val q = LinkedHashMap<String, String>()
    q["static"] = "true"
    q["DeviceId"] = server.deviceId
    mediaSourceId?.let { q["MediaSourceId"] = it }
    audioStreamIndex?.let { q["AudioStreamIndex"] = it.toString() }
    subtitleStreamIndex?.let { q["SubtitleStreamIndex"] = it.toString() }
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "${EmbyEndpoints.apiBaseUrl(server)}/Videos/$itemId/stream?$qs"
  }

  /**
   * 多版本媒体：向服务器要**指定版本**的直连播放地址。
   *
   * ## 为什么不能只自己拼 `/Videos/{id}/stream?MediaSourceId=…`
   *
   * Emby 官方文档把 `MediaSourceId` 与 **`PlaySessionId`** 一起列为 `/Videos/{Id}/stream`
   * 的**必需参数**（`PlaySessionId` from the PlaybackInfo response）。我们以前只带
   * `MediaSourceId` 就去请求 —— 服务器会无视它、直接吐默认版本，现象正是
   * 「下拉里选了不带字幕的 B，播出来的还是带字幕的 A」，而且关掉直链预解析也一样
   * （说明不是 302 那一步的问题，是最初这条流地址就没带对身份）。
   *
   * 所以这里先 `POST /Items/{id}/PlaybackInfo`（声明只要直连 / 直串流），让服务器把
   * 该版本的地址拼好给我们（`DirectStreamUrl`）；服务器没给就自己拼，
   * 但这一次**一定带上 `PlaySessionId`**。
   *
   * @return 该版本的播放地址；任何一步拿不到都返回 null —— 调用方回退到原有拼法，
   *   解析失败绝不挡住播放。
   */
  suspend fun directStreamUrlForSource(
    server: EmbyServer,
    itemId: String,
    /** 要播放的版本（`MediaSources[i].Id`） */
    mediaSourceId: String,
  ): String? {
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          // 只要直连 / 直串流：不让服务器转码
          "EnableDirectPlay" to true,
          "EnableDirectStream" to true,
          "EnableTranscoding" to false,
          "AutoOpenLiveStream" to false,
          "IsPlayback" to false,
          // 指定版本：服务器据此返回对应 MediaSource（含它自己的 DirectStreamUrl）
          "MediaSourceId" to mediaSourceId,
        ),
      ).toString()

    val info = runCatching {
      execJson<PlaybackInfoResult>(
        authedRequest(server, "/Items/$itemId/PlaybackInfo", mapOf("DeviceId" to server.deviceId))
          .post(jsonBody(payload))
          .build(),
      )
    }.getOrElse { error -> if (error is CancellationException) throw error else null } ?: return null

    // ⚠️ 服务端没按 MediaSourceId 给回我们要的那个版本 ⇒ 宁可返回 null 让调用方走原路径。
    // 退回首条会把「默认版本」当成「用户选的版本」播出去 —— 那正是要修的这个 bug，
    // 只是换个地方再犯一次。
    val source = info.MediaSources?.firstOrNull { it.Id == mediaSourceId } ?: return null

    source.DirectStreamUrl?.takeIf { it.isNotBlank() }?.let {
      return ensureApiKey(absolutize(server, it), server)
    }

    // 服务器没给直连地址：自己拼，这次带上 PlaySessionId（官方文档的必需参数）
    val q = LinkedHashMap<String, String?>()
    q["Static"] = "true"
    q["MediaSourceId"] = source.Id ?: mediaSourceId
    info.PlaySessionId?.let { q["PlaySessionId"] = it }
    q["DeviceId"] = server.deviceId
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v ?: "", "UTF-8")}"
    }
    return "${EmbyEndpoints.apiBaseUrl(server)}/Videos/$itemId/stream?$qs"
  }

  /** 服务器给的 `DirectStreamUrl` 未必带 `api_key`（取决于 `AddApiKeyToDirectStreamUrl`），补上更稳。 */
  private fun ensureApiKey(url: String, server: EmbyServer): String {
    if (server.apiToken.isEmpty() || !EmbyEndpoints.belongsToServer(server, url)) return url
    if (url.contains("api_key=")) return url
    return url + (if (url.contains("?")) "&" else "?") + "api_key=${server.apiToken}"
  }

  /** 把服务器返回的相对播放地址补成完整 URL，已经是绝对地址的原样返回。 */
  internal fun absolutize(server: EmbyServer, url: String): String = EmbyEndpoints.resolve(server, url)

  /**
   * 字幕下载 URL（用于把外挂字幕交给 mpv 用 sub-file 加载）。
   * Emby 字幕 API：GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.srt
   */
  fun subtitleUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): String {
    val server = EmbyHttp.sessionServer(server)
    val q = if (server.apiToken.isNotEmpty()) "?api_key=${server.apiToken}" else ""
    return "${EmbyEndpoints.apiBaseUrl(server)}/Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format$q"
  }

   // ════════════════════════════════════════════════════════════════════════
  // 5.6 字幕在线搜索（Emby 服务端）—— 实现在 EmbySubtitles.kt，以下为转发
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 与 [subtitleUrl]（拼 URL 把**服务端已有**的字幕轨交给 mpv）是两回事：
  // 这里是「搜字幕提供方 → 下载到服务器 → 再取字节」的三段式流程。
  // 默认参数值必须与 EmbySubtitles 里的完全一致。

  fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
    isForced: Boolean? = null,
    isHearingImpaired: Boolean? = null,
  ) = EmbySubtitles.searchRemoteSubtitles(
    server, itemId, language, mediaSourceId, isPerfectMatch, isForced, isHearingImpaired,
  )

  fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ) = EmbySubtitles.downloadSubtitleStream(server, itemId, mediaSourceId, subtitleIndex, format)
  // ════════════════════════════════════════════════════════════════════════
  // 5.7 合集（Collection / 官方客户端叫 BoxSet）—— 媒体库结构管理
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ **本节最容易踩的坑：REST 路径是 `/Collections`，不是 `/BoxSets`**
  // `/BoxSets` 是 **Jellyfin** 的命名。Emby 官方 Java 客户端里 BoxSet 走的是
  // `CollectionServiceApi` → `/Collections`。照着 Jellyfin 的文档 / 网上教程写必然 404。
  // 另一个要记的：**参数名是 `Ids` 不是 `EntryIds`**。
  // BoxSet(Collection) 查询使用 `Ids`。
  // 写错参数名服务端不报错，只是**静默不生效**（合集内容不变），极难排查。

  /**
   * 列出全部合集。
   *
   * **没有** `GET /Collections` 这种「按类型拿」的专用端点 ——
   * 合集本身就是一种 Item，只能走 `/Users/{id}/Items` + `IncludeItemTypes=BoxSet`。
   *
   * ⚠️ 返回里的 `ChildCount` 是服务端算的合集条目数，可能与实际差一
   * （有条目被删但合集没刷新）。UI 显示它没问题，**拿它做判断条件不行**。
   */
  fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = getJson(
    server,
    "/Users/${server.userId}/Items",
    mapOf(
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "IncludeItemTypes" to "BoxSet",
      "Fields" to "Overview,DateCreated,ChildCount,Genres,ProductionYear",
      "SortBy" to "SortName",
      "SortOrder" to "Ascending",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 取合集里的条目（分页）。
   *
   * 对应 `GET /Collections/{Id}/Items`。
   *
   * 不用通用 `getItems(parentId = ...)`：
   * `ParentId` 传播合集 Id 时部分服务端会算成空，老实用专用端点。
   */
  fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = getJson(
    server,
    "/Collections/$collectionId/Items",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
      "EnableTotalRecordCount" to "true",
    ),
  )

   // ════════════════════════════════════════════════════════════════════════
  // 5.8 显示偏好（DisplayPreferences）
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 三条容易踩空的真相，实现前务必先读完：
  // 1. **粒度是「每个目标」而不是「每个用户」**。落点的Id 叫
  //    `DisplayPreferencesId`，来自 `BaseItemDto.DisplayPreferencesId`，
  //    媒体库和单条目各有一份。所以不存在「一个用户一份显示偏好」这种写法 ——
  //    想让整个 App 的偏好跟着走，就得遍历所有目标库逐个写（见 EmbyDisplayPrefsSync）。
  // 2. **`Client` 是必填的分区键**，不是可选的 UA。`GET` 时它是 query 参数
  //    （`POST` 时它只在 body 里，见下），取不同值就读到不同的偏好副本。
  //    本 App 用 [DISPLAY_PREFS_CLIENT] 作为一个**独立分区**，
  //    好处是绝不会把 Emby Web 的排版偏好冲掉；代价是**不与网页端互通**，
  //    这是有意取舍，不是没做完。
  // 3. **不要写到 `/Users/{Id}/Configuration`**。`UserConfiguration` 的 17 个字段
  //    （音频语言 / 字幕语言 / ProfilePin / 跳过片头 / 重播剩余秒数…）**全是播放类**，
  //    一个显示字段都没有，网格密度写不进去。
  // 参数位置逐条照抄 SDK（`DisplayPreferencesServiceApi`）：
  //   GET  /DisplayPreferences/{Id}?UserId&Client← 只有 {Id} 在路径里
  //   POST /DisplayPreferences/{DisplayPreferencesId}?UserId
  //        body = 整个 DisplayPreferences（含 Client），**返回 Void（空 body）**

  /**
   * 本 App 在服务端显示偏好里的分区标识。
   *
   * 取值本身随意，只要**稳定** —— 换了它，之前写的偏好就读不到了（等于被清空）。
   * 所以定下来就别改，也别跟别的客户端重名。
   */
  const val DISPLAY_PREFS_CLIENT = "mpvex-android"

  /**
   * 读某个目标（媒体库 / 条目）的显示偏好。
   *
   * 目标不存在时部分部署返回 **404**，也有返回空对象的 —— 两种都当「还没有偏好」处理，
   * 由调用方决定是回落默认值还是提示用户。
   */
  fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String = DISPLAY_PREFS_CLIENT,
  ): EmbyDisplayPreferences? = getJson(
    server,
    "/DisplayPreferences/$displayPreferencesId",
    mapOf(
      "UserId" to server.userId,
      "Client" to client,
    ),
  )


}

/**
 * Emby API 调用失败时抛出，包含 HTTP 状态码与原始 body。
 *
 * ## 为什么要给消息加前缀（对应清单 1.3「错误分类」）
 *
 * 以前 UI 层只能拿到 `it.message`，而那个message 就是裸的
 * 「Emby API 500: <html>...</html>」—— 用户看到的是一坨 HTML，
 * 开发者也无法据此区分「网络断了」「密码错了」「服务器炸了」。
 *
 * 现在 [errorKind] 把失败归成少数几类，UI 可以据此给**人话**提示
 * （「网络不可用，检查连接」「账号或密码失效，请重新登录」）。
 * 原始 body 仍然保留在 [body] 里，排障时照发不误。
 */
class EmbyApiException(val code: Int, val body: String) :
  Exception(buildMessage(code, body)) {

  /** 归类后的错误种类，供 UI 决定提示文案 */
  val errorKind: EmbyErrorKind
    get() = EmbyErrorKind.of(code)
}

/**
 * [EmbyClient.probeStreamUrl] 的探测结果。
 *
 * 三态而不是布尔，是因为「探不出来」和「明确不可播」必须分开：
 * 前者（超时、DNS 失败）要照旧放行播放，后者（403 / 404）要在进播放器之前就拦住 ——
 * 把死链交给 mpv 会 native 崩溃（整个 App 消失，Java 层拦不住）。
 */
sealed interface StreamProbe {
  /**
   * 可以播。
   *
   * [finalUrl] 是**跟完 302 跳转之后**的最终地址；没发生跳转时就是传进来的原地址。
   * 播放器直接用它即可（也顺手解决了「内核不跟 302」那类问题）。
   */
  data class Ok(val finalUrl: String) : StreamProbe

  /** 明确不可播。[message] 是可直接展示给用户的中文原因。 */
  data class Denied(val message: String) : StreamProbe

  /** 探不出来（超时 / 网络异常 / 不支持探测）。调用方按「可播」处理，别挡着用户。 */
  object Unknown : StreamProbe

  /**
   * 可直接交给播放器的地址。
   *
   * [Denied] / [Unknown] 都是 null —— 注意 null 的语义是「不知道」，不是「不能播」，
   * 调用方应当回退到原始流地址，而不是当成失败。
   */
  val urlOrNull: String?
    get() = (this as? Ok)?.finalUrl
}

/** 错误归类（见 [EmbyApiException.errorKind]）。 */
enum class EmbyErrorKind {
  /** 断网 / 超时 / 连不上 */
  NETWORK,

  /** 401 / 403：token 过期或没权限 —— 该重登 / 重新登录 */
  AUTH,

  /** 404：资源没了（条目被删、Id 写错） */
  NOT_FOUND,

  /** 5xx：服务器自己出问题，可提示稍后重试 */
  SERVER,

  /** 其余（4xx 客户端错误等） */
  UNKNOWN,
  ;

  companion object {
    fun of(code: Int): EmbyErrorKind =
      when {
        code <= 0 -> NETWORK // 0 是本项目自定义的「没拿到响应码」⇒按网络问题处理
        code == 401 || code == 403 -> AUTH
        code == 404 -> NOT_FOUND
        code in 500..599 -> SERVER
        else -> UNKNOWN
      }
  }
}

/** Safe, user-facing text only: never retain HTTP bodies, URLs or credentials as a cause. */
class EmbyEpisodeOperationException(message: String) : IllegalStateException(message)

/** 把原始响应包成人话；body 只留一小段，避免塞满 Toast。 */
private fun buildMessage(code: Int, body: String): String {
  val kind = EmbyErrorKind.of(code)
  val head = when (kind) {
    EmbyErrorKind.NETWORK -> "网络不可用"
    EmbyErrorKind.AUTH -> "鉴权失败"
    EmbyErrorKind.NOT_FOUND -> "资源不存在"
    EmbyErrorKind.SERVER -> "服务器出错"
    EmbyErrorKind.UNKNOWN -> "请求失败"
  }
  val detail = body.trim().take(120)
  return if (detail.isEmpty()) "$head（$code）" else "$head（$code）：$detail"
}

/** kotlinx.serialization 的 JsonObject 构造辅助（避免每次写 JsonObject(mapOf(...)) 套娃） */
private fun jsonObjectOf(entries: Map<String, Any?>): JsonObject {
  val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
  entries.forEach { (k, v) ->
    val e = when (v) {
      null -> kotlinx.serialization.json.JsonNull
      is String -> kotlinx.serialization.json.JsonPrimitive(v)
      is Number -> kotlinx.serialization.json.JsonPrimitive(v)
      is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
      else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }
    map[k] = e
  }
  return JsonObject(map)
}

/**
 * `POST /Items/{id}/PlaybackInfo` 的响应。
 *
 * 只声明播放会话与直连地址需要的字段，其余靠 `ignoreUnknownKeys` 忽略。
 */
@Serializable
private data class PlaybackInfoResult(
  val MediaSources: List<PlaybackMediaSource>? = null,
  /** 本次播放会话 Id，指定版本的流地址需带上。 */
  val PlaySessionId: String? = null,
  /** 拿不到可用源时的错误码，例如 `NoCompatibleStream` */
  val ErrorCode: String? = null,
)

/** [PlaybackInfoResult] 里的一条媒体源 */
@Serializable
private data class PlaybackMediaSource(
  /** 即 MediaSourceId */
  val Id: String? = null,
  val DirectStreamUrl: String? = null,
)

/**
 * `GET /Users/{id}` 的响应，只为拿 [Policy]。
 *
 * 声明成 private：外面只关心「是不是管理员」这一个布尔值（见 [EmbyClient.fetchIsAdmin]），
 * 权限模型的其余十几个字段不该泄漏成公共 API。
 */
@Serializable
private data class EmbyUserDetailDto(
  val Id: String? = null,
  val Name: String? = null,
  val Policy: EmbyUserPolicyDto? = null,
)

/** [EmbyUserDetailDto] 里的权限块。只声明要用的那个字段，其余靠 ignoreUnknownKeys 丢掉。 */
@Serializable
private data class EmbyUserPolicyDto(
  val IsAdministrator: Boolean = false,
)
