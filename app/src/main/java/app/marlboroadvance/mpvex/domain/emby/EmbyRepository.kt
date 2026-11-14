package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import app.marlboroadvance.mpvex.database.repository.EmbyServerLoadState
import app.marlboroadvance.mpvex.domain.postgres.PostgresException
import app.marlboroadvance.mpvex.domain.postgres.PostgresFailure
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Read-only shared configuration and browse/play APIs. New auth tokens live only in this process. */
class EmbyRepository(
  private val serverRepo: EmbyServerRepository,
  private val context: android.content.Context,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val authenticate: (EmbyServer, String, String) -> EmbyAuthResult = EmbyClient::authenticateByName,
) {
  private val prefs by lazy {
    context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
  }
  private val _currentServer = MutableStateFlow<EmbyServer?>(null)
  val currentServer: StateFlow<EmbyServer?> = _currentServer.asStateFlow()
  val loadState: StateFlow<EmbyServerLoadState> = serverRepo.loadState
  private val sessionLock = Any()
  private val loginLock = Mutex()
  private var sourceRevision = serverRepo.sourceChanged.value.revision
  private var restoredSource: String? = null
  private data class Session(val userId: String, val token: String, val lastConnected: Long)
  private val sessions = mutableMapOf<String, Session>()

  /** Includes source generation, UUID, credentials and the full shared HTTP/device identity. */
  private fun sessionKey(server: EmbyServer): String {
    val fields = listOf(server.sourceKey, server.sourceRevision.toString(), server.remoteId,
      server.username, server.password, server.client, server.device, server.deviceId,
      server.clientVersion, server.userAgent, server.baseUrl, server.reverseProxyId, server.reverseProxyUrl)
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { value ->
      val bytes = value.toByteArray(Charsets.UTF_8)
      digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
      digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun sourceMatches(server: EmbyServer): Boolean = serverRepo.isCurrent(server)

  private fun overlay(server: EmbyServer): EmbyServer = sessions[sessionKey(server)]?.let {
    server.copy(userId = it.userId, apiToken = it.token, lastConnected = it.lastConnected)
  } ?: server

  private fun readyServers(): List<EmbyServer> = when (val state = loadState.value) {
    is EmbyServerLoadState.Ready -> state.servers
    is EmbyServerLoadState.Failed -> throw PostgresException(state.reason)
    EmbyServerLoadState.Loading -> throw PostgresException(PostgresFailure.LOAD)
  }

  private fun clearCurrent() { _currentServer.value = null }

  /** Resolve an existing row against current source/credentials, applying its memory-only token. */
  fun sessionServer(server: EmbyServer): EmbyServer = synchronized(sessionLock) {
    if (!sourceMatches(server)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    val fresh = serverRepo.snapshotById(server.id)?.takeIf {
      it.remoteId == server.remoteId && it.sourceKey == server.sourceKey
    } ?: throw PostgresException(PostgresFailure.NOT_FOUND)
    if (sessionKey(fresh) != sessionKey(server)) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
    overlay(fresh).also { it.requireSupportedConnection() }
  }

  /** Non-blocking image/player lookup; does not trigger config loading or JDBC. */
  fun sessionSnapshotById(id: Long): EmbyServer? = synchronized(sessionLock) {
    serverRepo.snapshotById(id)?.let { overlay(it) }
  }

  /** UI-only source-bound lookup by shared UUID; does not query or authenticate. */
  fun sessionSnapshotByRemoteId(remoteId: String, source: app.marlboroadvance.mpvex.domain.postgres.PostgresSourceToken): EmbyServer? =
    synchronized(sessionLock) {
      serverRepo.snapshotServers().firstOrNull {
        it.remoteId == remoteId && it.sourceKey == source.sourceKey && it.sourceRevision == source.revision
      }?.let { overlay(it) }
    }

  private fun syncReady(servers: List<EmbyServer>) = synchronized(sessionLock) {
    val state = serverRepo.sourceChanged.value
    if (sourceRevision != state.revision) {
      sourceRevision = state.revision
      restoredSource = null
      sessions.clear()
      clearCurrent()
    }
    val source = state.sourceKey.ifBlank { servers.firstOrNull()?.sourceKey.orEmpty() }
    if (servers.any { it.sourceKey != source || it.sourceRevision != state.revision }) return@synchronized
    val keys = servers.mapTo(mutableSetOf(), ::sessionKey)
    sessions.keys.retainAll(keys)
    val current = _currentServer.value
    if (current != null) {
      val fresh = servers.firstOrNull { it.sourceKey == current.sourceKey && it.remoteId == current.remoteId }
      // Keep the selected row visible even if its line/reverse reference becomes invalid: no fallback.
      if (fresh == null) clearCurrent() else _currentServer.value = overlay(fresh)
    } else if (restoredSource != source) {
      val savedSource = prefs.getString(KEY_CURRENT_SOURCE, null)
      val savedRemote = prefs.getString(KEY_CURRENT_REMOTE, null)
      val preferred = servers.firstOrNull { savedSource == source && it.remoteId == savedRemote }
        ?: servers.firstOrNull { !it.disabled && it.isLoggedIn }
        ?: servers.firstOrNull { !it.disabled }
      if (preferred != null) {
        _currentServer.value = overlay(preferred)
        prefs.edit().putString(KEY_CURRENT_SOURCE, preferred.sourceKey).putString(KEY_CURRENT_REMOTE, preferred.remoteId).apply()
      }
    }
    restoredSource = source
    if (servers.isEmpty()) clearCurrent()
  }

  init {
    EmbyHttp.installSessionSource { requested -> sessionServer(requested) }
    EmbyHttp.onUnauthorized = unauthorized@{ requested ->
      try {
        val target = sessionServer(requested)
        if (target.apiToken != requested.apiToken && target.isLoggedIn) return@unauthorized target.apiToken
        if (target.username.isBlank() || target.password.isBlank()) return@unauthorized null
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { relogin(target).apiToken }
      } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    }
    // Observe only: constructing App/Koin does not load secure config or connect to PostgreSQL.
    scope.launch {
      serverRepo.sourceChanged.collect { source ->
        synchronized(sessionLock) {
          if (sourceRevision != source.revision) {
            sourceRevision = source.revision
            restoredSource = null
            sessions.clear()
            clearCurrent()
          }
        }
      }
    }
    scope.launch {
      loadState.collect { state -> if (state is EmbyServerLoadState.Ready) syncReady(state.servers) }
    }
  }

  suspend fun awaitCurrentServer(): EmbyServer? {
    if (loadState.value is EmbyServerLoadState.Loading) serverRepo.getAll()
    val state = loadState.first { it !is EmbyServerLoadState.Loading }
    if (state is EmbyServerLoadState.Failed) throw PostgresException(state.reason)
    syncReady((state as EmbyServerLoadState.Ready).servers)
    return _currentServer.value
  }

  /** Only source+remote UUID selection is persisted locally. No configuration or token is written. */
  fun setCurrentServer(server: EmbyServer) = synchronized(sessionLock) {
    val fresh = sessionServer(server)
    _currentServer.value = fresh
    prefs.edit().putString(KEY_CURRENT_SOURCE, fresh.sourceKey).putString(KEY_CURRENT_REMOTE, fresh.remoteId).apply()
    Unit
  }

  suspend fun selectServer(id: Long): EmbyServer? {
    val server = getServer(id) ?: return null
    setCurrentServer(server)
    return currentServer.value
  }

  fun selectServerAsUser(server: EmbyServer) { setCurrentServer(server) }

  suspend fun refreshCurrentServer(): EmbyServer? {
    refreshServers()
    return currentServer.value
  }

  suspend fun refreshServers() {
    serverRepo.refresh()
    syncReady(readyServers())
  }

  suspend fun ensureLoggedIn(server: EmbyServer? = _currentServer.value): EmbyServer? {
    val target = server?.let(::sessionServer) ?: return null
    if (target.isLoggedIn) return target
    if (target.username.isBlank() || target.password.isBlank()) return null
    return try { relogin(target) }
      catch (error: CancellationException) { throw error }
      catch (error: PostgresException) { throw error }
      catch (_: Exception) { null }
  }

  val servers = serverRepo.allServers.map { rows -> synchronized(sessionLock) { rows.map(::overlay) } }
  suspend fun getServers(): List<EmbyServer> {
    val rows = serverRepo.getAll()
    syncReady(rows)
    return synchronized(sessionLock) { rows.map(::overlay) }
  }
  suspend fun getServer(id: Long): EmbyServer? = serverRepo.getById(id)?.let { row ->
    synchronized(sessionLock) { overlay(row) }
  }

  /** Authenticate the existing shared row, but keep all returned credentials in this process only. */
  suspend fun relogin(server: EmbyServer): EmbyServer = withContext(Dispatchers.IO) {
    loginLock.withLock {
      val target = sessionServer(server)
      // Concurrent 401s reuse the already-refreshed session rather than login repeatedly.
      if (target.isLoggedIn && target.apiToken != server.apiToken) return@withLock target
      val expectedKey = sessionKey(target)
      val auth = authenticate(target, target.username, target.password)
      val token = auth.AccessToken?.takeIf { it.isNotEmpty() } ?: throw EmbyApiException(401, "重登录失败")
      val userId = auth.User?.Id?.takeIf { it.isNotEmpty() } ?: throw EmbyApiException(401, "重登录失败：无 User.Id")
      synchronized(sessionLock) {
        val fresh = sessionServer(target)
        if (sessionKey(fresh) != expectedKey) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
        sessions[expectedKey] = Session(userId, token, System.currentTimeMillis())
        val loggedIn = overlay(fresh)
        if (_currentServer.value?.let(::sessionKey) == expectedKey) _currentServer.value = loggedIn
        loggedIn
      }
    }
  }

  /** Search-only auth: suspendable Call, memory overlay only, no selection or home refresh. */
  private suspend fun searchLogin(server: EmbyServer): EmbyServer = loginLock.withLock {
    val target = sessionServer(server)
    if (target.isLoggedIn && target.apiToken != server.apiToken) return@withLock target
    if (target.username.isBlank() || target.password.isBlank()) throw EmbyApiException(401, "")
    val expectedKey = sessionKey(target)
    val auth = EmbyClient.authenticateForSearch(target)
    currentCoroutineContext().ensureActive()
    val token = auth.AccessToken?.takeIf { it.isNotEmpty() } ?: throw EmbyApiException(401, "")
    val userId = auth.User?.Id?.takeIf { it.isNotEmpty() } ?: throw EmbyApiException(401, "")
    synchronized(sessionLock) {
      val fresh = sessionServer(target)
      if (sessionKey(fresh) != expectedKey) throw PostgresException(PostgresFailure.SOURCE_CHANGED)
      sessions[expectedKey] = Session(userId, token, System.currentTimeMillis())
      // Deliberately do not assign _currentServer, even if this row happens to be selected.
      overlay(fresh)
    }
  }

  private suspend fun <T> searchRequest(server: EmbyServer, request: suspend (EmbyServer) -> T): T {
    val fresh = sessionServer(server)
    val target = if (fresh.isLoggedIn) fresh else searchLogin(fresh)
    val result = try {
      request(target)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: EmbyApiException) {
      if (error.code != 401) throw error
      currentCoroutineContext().ensureActive()
      // Only one explicit 401 session recovery, never IO/timeout/cancellation retries.
      request(searchLogin(target))
    }
    currentCoroutineContext().ensureActive()
    sessionServer(server) // Source/row/credentials must still match before publishing a late result.
    return result
  }

  suspend fun searchMediaPage(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>,
    limit: Int = 80,
  ): EmbyItemsResult = searchRequest(server) { EmbyClient.searchMediaPage(it, term, includeItemTypes, limit) }

  suspend fun searchPersonPage(server: EmbyServer, term: String, limit: Int = 40): EmbyItemsResult =
    searchRequest(server) { EmbyClient.searchPersonPage(it, term, limit) }

  // ─── 媒体库 / 媒体 ───

  /** 媒体库列表（根级 VirtualFolders） */
  suspend fun getVirtualFolders(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      EmbyClient.getVirtualFolders(sessionServer(server)).Items
    }

  /** 库内媒体分页 */
  suspend fun getItems(
    server: EmbyServer,
    parentId: String? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    filters: List<String>? = null,
    includeItemTypes: List<String>? = null,
    genres: List<String>? = null,
    searchTerm: String? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    excludeItemTypes: List<String>? = null,
    /** 按发行年份筛选，多选取并集 */
    years: List<Int>? = null,
    /** 最低社区评分（0~10） */
    minCommunityRating: Float? = null,
    /** 标签筛选，多选取并集 */
    tags: List<String>? = null,
    /** 类型 Id 筛选（逗号分隔），优先于按名字的 [genres] */
    genreIds: List<String>? = null,
    /** 标签 Id 筛选（逗号分隔），优先于按名字的 [tags] */
    tagIds: List<String>? = null,
    /** 官方分级筛选，多选取并集 */
    officialRatings: List<String>? = null,
    /** true / false 分别限定只要收藏 / 只要未收藏；null 表示不限 */
    isFavorite: Boolean? = null,
    /**
     * 只要某种 MediaType：Video / Audio / Photo / Book。
     *
     * 这是 Emby 官方「只要视频」的正解 —— 和 IncludeItemTypes 是两套口径：
     * 后者按条目类型（Movie / Episode …）筛，前者按**媒体形态**筛。
     * 库里混进的音频、图片、电子书，MediaType 都不是 Video，单靠类型白名单挡不住。
     */
    mediaTypes: List<String>? = null,
    /** false = 只要能直接播放的媒体本体，容器（Folder / Series / Season / BoxSet）全挡掉 */
    isFolder: Boolean? = null,
    /** 按人员（演员 / 导演）筛选，传 PersonId */
    personIds: List<String>? = null,
    /** true 只要已看 / false 只要未看；null 不限 */
    isPlayed: Boolean? = null,
    /** true 只要高清 / false 只要标清 */
    isHD: Boolean? = null,
    /** true 只要 3D / false 排除 3D */
    is3D: Boolean? = null,
    /** true 只要有字幕 / false 只要没字幕 */
    hasSubtitles: Boolean? = null,
    /** 工作室 Id 列表，多选取并集 */
    studioIds: List<String>? = null,
    /**
     * 覆盖默认的 [EmbyClient.ITEM_FIELDS]。
     *
     * 只在「大页数 + 只要几个字段」的场景用得上，典型是拼文件夹宫格封面：
     * 一页几百条，却只要 `ParentId` 与 `ImageTags`，带上默认的
     * `MediaSources` / `Overview` / `People` 会让响应体大出一个数量级。
     * 其余调用点保持默认值，行为不变。
     */
    fields: String = EmbyClient.ITEM_FIELDS,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getItems(
      sessionServer(server),
      parentId = parentId,
      sortBy = sortBy,
      sortOrder = sortOrder,
      filters = filters,
      includeItemTypes = includeItemTypes,
      genres = genres,
      searchTerm = searchTerm,
      startIndex = startIndex,
      limit = limit,
      recursive = recursive,
      excludeItemTypes = excludeItemTypes,
      years = years,
      minCommunityRating = minCommunityRating,
      tags = tags,
      genreIds = genreIds,
      tagIds = tagIds,
      officialRatings = officialRatings,
      isFavorite = isFavorite,
      mediaTypes = mediaTypes,
      isFolder = isFolder,
      personIds = personIds,
      isPlayed = isPlayed,
      isHD = isHD,
      is3D = is3D,
      hasSubtitles = hasSubtitles,
      studioIds = studioIds,
      fields = fields,
    )
  }

  // ─── 照片（图集） ───

  /**
   * 相册列表：照片库根级（或任意文件夹下）的 PhotoAlbum。
   *
   * Emby 的照片库结构是「库 → 相册（文件夹）→ 照片」两层，所以进照片库先拉相册。
   * `Recursive=false`：相册就一层，不需要往下递归（再往下是照片，由 [getPhotos] 拉）。
   */
  suspend fun getPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server = sessionServer(server),
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("PhotoAlbum"),
    startIndex = startIndex,
    limit = limit,
    recursive = false,
    isFolder = false,
    fields = EmbyClient.PHOTO_FIELDS,
  )

  /**
   * 照片列表：相册内的 Photo（[parentId] 传相册 Id），或整个照片库递归平铺的
   * 全部照片（[parentId] 传库 Id + [recursive] = true，跳过相册层级）。
   *
   * `MediaTypes=Photo` 与 `IncludeItemTypes=Photo` 双写：前者按媒体形态、
   * 后者按条目类型，是 Emby 官方「只要照片」的口径，混进来的视频/音频
   * 两个关口都过不掉。
   *
   * ⚠️ 宽高 / EXIF **不靠 fields 索取**（见 [EmbyClient.PHOTO_FIELDS] 的说明），
   * 服务端对 Photo 条目自动返回，这里传默认的相册 fields 串即可。
   */
  suspend fun getPhotos(
    server: EmbyServer,
    parentId: String,
    recursive: Boolean = true,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server = sessionServer(server),
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("Photo"),
    mediaTypes = listOf("Photo"),
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    isFolder = false,
    fields = EmbyClient.PHOTO_FIELDS,
  )

  /**
   * 全量扫描：把 [query] 命中的**整个**结果集逐页拉完。
   *
   * 用在「客户端筛选」场景（如按路径判断的中文字幕）：Emby 服务端没有这类筛选参数，
   * 只筛第一页必然漏，必须一个 StartIndex 一个 StartIndex 地翻到底。
   * 每页拉完调一次 [onChunk]，UI 能边扫边出结果，不必等整库拉完。
   *
   * 结束条件只认「空页」和「已翻到 TotalRecordCount」：
   * 不能拿「返回条数 < 请求条数」当结束条件 —— 有些 Emby 版本会截断 Limit，
   * 那样会在第一页就退出，退化成「只筛出一部分」。
   *
   * @param pageSize 每页条数
   * @param maxItems 本次扫描的条数上限（兜底，防止异常数据把设备拖死）
   * @return 实际扫描到的条目数
   */
  suspend fun scanItems(
    server: EmbyServer,
    query: EmbyScanQuery,
    pageSize: Int = SCAN_PAGE_SIZE,
    maxItems: Int = SCAN_MAX_ITEMS,
    onChunk: suspend (EmbyScanChunk) -> Unit,
  ): Int = withContext(Dispatchers.IO) {
    var startIndex = 0
    var scanned = 0
    var total = 0
    while (scanned < maxItems) {
      // 每页之间检查一次取消：整个扫描可能上百个请求，用户退出页面时要能立刻停下
      currentCoroutineContext().ensureActive()
      val limit = (maxItems - scanned).coerceAtMost(pageSize)
      val page = EmbyClient.getItems(
        server = sessionServer(server),
        parentId = query.parentId,
        sortBy = query.sortBy,
        sortOrder = query.sortOrder,
        // 「搜索 + 客户端筛选」时把搜索词交给服务端，扫的就是命中那批，但照样翻到底
        searchTerm = query.searchTerm,
        filters = query.filters,
        includeItemTypes = query.includeItemTypes,
        genres = query.genres,
        genreIds = query.genreIds,
        tagIds = query.tagIds,
        startIndex = startIndex,
        limit = limit,
        recursive = query.recursive,
        excludeItemTypes = query.excludeItemTypes,
        personIds = query.personIds,
        years = query.years,
        minCommunityRating = query.minCommunityRating,
        tags = query.tags,
        officialRatings = query.officialRatings,
        isPlayed = query.isPlayed,
        isHD = query.isHD,
        is3D = query.is3D,
        hasSubtitles = query.hasSubtitles,
        studioIds = query.studioIds,
        isFavorite = query.isFavorite,
        mediaTypes = query.mediaTypes,
        isFolder = query.isFolder,
      )
      val items = page.Items
      if (page.TotalRecordCount > 0) total = page.TotalRecordCount
      if (page.consumedCount == 0) break
      scanned += page.consumedCount
      startIndex += page.consumedCount
      onChunk(EmbyScanChunk(scanned = scanned, total = total, items = items))
      if (total > 0 && startIndex >= total) break
    }
    scanned
  }

  /** 某媒体库的筛选可选项（类型 / 标签 / 年份 / 分级） */
  suspend fun getFilterOptions(    server: EmbyServer,
    parentId: String? = null,
  ): EmbyFilterOptions = withContext(Dispatchers.IO) { EmbyClient.getFilterOptions(sessionServer(server), parentId) }

  /** 继续观看 */
  suspend fun getResumeItems(server: EmbyServer, limit: Int = 20): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getResumeItems(sessionServer(server), limit = limit).Items }

  /** 最新加入 */
  suspend fun getLatestItems(server: EmbyServer, limit: Int = 16): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getLatestItems(sessionServer(server), limit = limit) }

  /** Explicitly authorized Episode-only user-state writes; no shared configuration writes. */
  suspend fun setMediaFavorite(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    mediaOperation(server, item) { EmbyClient.setMediaFavorite(it, item, enabled) }

  suspend fun setMediaPlayed(server: EmbyServer, item: EmbyItem, enabled: Boolean): EmbyUserData =
    mediaOperation(server, item) { EmbyClient.setMediaPlayed(it, item, enabled) }

  private suspend fun mediaOperation(
    server: EmbyServer, item: EmbyItem, operation: (EmbyServer) -> EmbyUserData,
  ): EmbyUserData = withContext(Dispatchers.IO) {
    EmbyClient.requireUserStateMedia(item)
    val target = ensureLoggedIn(sessionServer(server))
      ?: throw EmbyEpisodeOperationException("请先登录后再操作")
    currentCoroutineContext().ensureActive()
    val result = operation(target)
    currentCoroutineContext().ensureActive()
    val fresh = sessionServer(server)
    if (!fresh.isLoggedIn || fresh.userId != target.userId) {
      throw EmbyEpisodeOperationException("登录身份已变化，请刷新后核对状态")
    }
    result
  }

  /** 媒体详情 */
  suspend fun getItem(server: EmbyServer, itemId: String): EmbyItem =
    withContext(Dispatchers.IO) { EmbyClient.getItem(sessionServer(server), itemId) }

  /**
   * 收藏夹列表。
   *
   * [sortBy] / [sortOrder] 会**下推到服务端**：收藏页是分页的，分页切分跟着 SortBy 走，
   * 只在本地排序会得到「第一页里再排一遍」这种半截结果（见 EmbyClient.getFavoriteItems 的 KDoc）。
   */
  suspend fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
    includeItemTypes: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    val mediaTypes = if (includeItemTypes == listOf("Movie")) listOf("Video") else null
    EmbyClient.getFavoriteItems(
      server = sessionServer(server),
      startIndex = startIndex,
      limit = limit,
      mediaTypes = mediaTypes,
      includeItemTypes = includeItemTypes,
      sortBy = sortBy,
      sortOrder = sortOrder,
    )
  }

  /**
   * 收藏的演员列表（收藏页「演员」tab）。
   *
   * Person 只能从 `/Persons` 端点拿（`/Users/{id}/Items` 不返回 Person），
   * 见 [EmbyClient.getFavoritePersons] 的说明。
   */
  suspend fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getFavoritePersons(sessionServer(server)) }

  /**
   * 收藏的类型 / 工作室列表（收藏页「类型 / 工作室」tab）。
   *
   * 与 [getFavoritePersons] 不同，Genre / Studio 是 `/Users/{id}/Items` 能直接返回的
   * 正式条目，所以走服务端 `Filters=IsFavorite` 筛选，不需要客户端再过滤。
   */
  suspend fun getFavoriteGenres(server: EmbyServer): EmbyItemsResult =
    withContext(Dispatchers.IO) { EmbyClient.getFavoriteGenres(sessionServer(server)) }

  suspend fun getFavoriteStudios(server: EmbyServer): EmbyItemsResult =
    withContext(Dispatchers.IO) { EmbyClient.getFavoriteStudios(sessionServer(server)) }

  /** 按 Id 取单个人员（演员作品页收藏红心查当前状态用） */
  suspend fun getPersonById(server: EmbyServer, personId: String): EmbyItem? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonById(sessionServer(server), personId) }

  /**
   * 读单个人员的收藏真值（true=已收藏 / false=未收藏 / null=读不到）。
   *
   * 与 [getPersonById] 的分工：那个返回**条目**（给 UI 拼可点状态用），这个只关心
   * **IsFavorite 布尔值**，且走与加载时不同的 URL 形态（单条轻量读 + Web 收藏页同款的
   * `IsFavorite=true` 列表查询），专门绕开「按 URL 缓存导致回读拿到旧值」的坑。
   */
  suspend fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonFavoriteState(sessionServer(server), personId) }

  /**
   * 随机播放：返回 [limit] 个随机项。可指定 [includeItemTypes]
   * 例如 listOf("Movie") / listOf("Episode")。
   */
  suspend fun getRandomItems(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getRandomItems(sessionServer(server), parentId, includeItemTypes, limit, isFavorite)
  }

  // ─── 相似推荐 / 即混 / 续播 ───
  // 服务端算法那三套（`/Items/{Id}/Similar` / `/Items/{Id}/InstantMix` / `/Shows/NextUp`）
  // 与本地算法（`LocalSimilarity`）由 `SimilarAlgorithm` 偏好二选一，
  // 详见 `EmbyClient` 2.5 节的说明。

  /** 相似推荐（服务端算法）。返回的是分页壳，用 [EmbyItemsResult.Items] 取。 */
  suspend fun getSimilarItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 24,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf("Video"),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getSimilarItems(sessionServer(server), itemId, limit, includeItemTypes, mediaTypes, excludeIdList)
  }

  /** 即混（服务端基于种子自动续播一批）。 */
  suspend fun getInstantMixItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf("Video"),
    filters: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getInstantMixItems(sessionServer(server), itemId, limit, includeItemTypes, mediaTypes, filters)
  }

  /** 续播列表。服务端没开「剧集自动续播」时会是空列表。 */
  suspend fun getNextUpItems(
    server: EmbyServer,
    limit: Int = 20,
    seriesId: String? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getNextUpItems(sessionServer(server), limit, seriesId)
  }

  // ─── 合集（BoxSet）───
  // ⚠️ REST 路径是 `/Collections` 不是 `/BoxSets`（那是 Jellyfin 的命名），
  // 且参数名一律 `Ids`。详见 [EmbyClient] 5.7 节。

  suspend fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getCollections(sessionServer(server), startIndex, limit)
  }

  suspend fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getCollectionItems(sessionServer(server), collectionId, startIndex, limit)
  }

  /** Read existing display preferences; Android never persists them remotely. */
  suspend fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String = EmbyClient.DISPLAY_PREFS_CLIENT,
  ): EmbyDisplayPreferences? = withContext(Dispatchers.IO) {
    EmbyClient.getDisplayPreferences(sessionServer(server), displayPreferencesId, client)
  }

  /**
   * 拉条目的片头 / 片尾分段（a1 精准跳过片头片尾）。
   *
   * 拿不到就返回 null **而不是抛异常**：服务端没扫描片头标记（空 Segments）、
   * 端点不存在（旧版服务端）、网络断，都是正常情况，播放页按「没有精准数据、
   * 退固定时长兜底」处理即可。
   */
  suspend fun getItemIntros(
    server: EmbyServer,
    itemId: String,
  ): EmbyIntrosResult? =
    withContext(Dispatchers.IO) {
      try { EmbyClient.getItemIntros(sessionServer(server), itemId) }
      catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

   // ─── 字幕在线搜索 ───

  /**
   * 搜在线字幕候选（详见 [EmbyClient.searchRemoteSubtitles]）。
   *
   * ⚠️ 单语言单次请求 —— 搜双语就是两次调用，由调用方合并。
   */
  suspend fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
  ): List<EmbyRemoteSubtitle> = withContext(Dispatchers.IO) {
    EmbyClient.searchRemoteSubtitles(
      server = sessionServer(server),
      itemId = itemId,
      language = language,
      mediaSourceId = mediaSourceId,
      isPerfectMatch = isPerfectMatch,
    )
  }

  /**
   * 取已存到服务器的字幕字节。
   *
   * ⚠️ 返回的是**内存里的字节**而不是落盘文件 —— 落盘与命名由 UI 层的
   * temporary playback consumer 负责；没有字幕导出或服务器持久化。
   */
  suspend fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): ByteArray = withContext(Dispatchers.IO) {
    EmbyClient.downloadSubtitleStream(sessionServer(server), itemId, mediaSourceId, subtitleIndex, format)
  }

   // ─── 播放进度上报 ───

  suspend fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    /** 多版本媒体：告诉服务器「正在播的是哪一个版本」，服务器控制台的「正在播放」才准 */
    mediaSourceId: String? = null,
    /**
     * 客户端自己生成的会话 id。
     *
     * ⚠️ 必须传：`/Sessions/Playing` 正常返回 204 / 空 JSON，里面**没有** `PlaySessionId`
     * 可读。不传的话 Progress / Stopped 只能带空串去对账，服务端记不住这次播放 ——
     * 用户侧表现为「退出后已观看 0%」。
     */
    playSessionId: String? = null,
  ): String = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackStart(
      sessionServer(server),
      itemId,
      positionTicks,
      mediaSourceId = mediaSourceId,
      playSessionId = playSessionId,
    )
  }

  suspend fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
    /** 多版本媒体：正在播的那个版本；漏传会让进度记到默认版本上（见 EmbyClient 注释） */
    mediaSourceId: String? = null,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackProgress(
      sessionServer(server),
      itemId,
      playSessionId,
      positionTicks,
      isPaused,
      mediaSourceId = mediaSourceId,
    )
  }

  /**
   * 要一个服务端签发的播放会话（`PlaybackInfo`）。拿不到返回 null，调用方退回自生成 UUID。
   * 为什么要这一步见 [EmbyClient.requestPlaybackSession] 的 KDoc。
   */
  suspend fun requestPlaybackSession(
    server: EmbyServer,
    itemId: String,
  ): Pair<String, String?>? = withContext(Dispatchers.IO) {
    EmbyClient.requestPlaybackSession(sessionServer(server), itemId)
  }

  /** 按 Id 批量取条目（首页「继续观看」补本地最近播放用，见 [EmbyRecentPlays]） */
  suspend fun getItemsByIds(server: EmbyServer, ids: List<String>): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getItemsByIds(sessionServer(server), ids).Items }

  suspend fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    mediaSourceId: String? = null,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackStopped(sessionServer(server), itemId, playSessionId, positionTicks, mediaSourceId)
  }

  /** 搜索媒体（服务端 SearchTerm 匹配），可按指定方式排序 */
  suspend fun searchItems(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>? = null,
    limit: Int = 60,
    sortBy: String? = "SortName",
    sortOrder: String? = null,
    /** 搜索范围：null = 全服；非 null + [recursive] = false 即「仅本目录」 */
    parentId: String? = null,
    recursive: Boolean = true,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.searchItems(
      server = sessionServer(server),
      term = term,
      includeItemTypes = includeItemTypes,
      limit = limit,
      sortBy = sortBy,
      sortOrder = sortOrder,
      parentId = parentId,
      recursive = recursive,
    )
  }

  /**
   * 搜演员（走 `/Persons`）。
   *
   * 与 [searchItems] 分开，因为 `/Users/{id}/Items` 那条端点不返回 Person。
   */
  suspend fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    EmbyClient.searchPersons(sessionServer(server), term, parentId, limit)
  }

  /**
   * 剧集的季列表。
   *
   * [startIndex] / [limit] 是清单 1.1 补的分页透传；不传时行为与从前一致（一次拿全）。
   */
  suspend fun getSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getSeasons(sessionServer(server), seriesId, startIndex, limit)
  }

  /** 某一季下的剧集列表（分页参数同上，见 [getSeasons]） */
  suspend fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getEpisodes(sessionServer(server), seriesId, seasonId, startIndex, limit)
  }

  companion object {
    private const val PREFS_NAME = "emby_session"
    private const val KEY_CURRENT_SOURCE = "current_source_key_v2"
    private const val KEY_CURRENT_REMOTE = "current_remote_uuid_v2"
  }

  // ─── URL 构造（图片 / 流 / 字幕）───

  fun imageUrl(
    server: EmbyServer,
    itemId: String,
    imageType: String = "Primary",
    tag: String? = null,
    maxWidth: Int = 480,
    maxHeight: Int? = null,
    imageIndex: Int? = null,
  ): String = EmbyClient.imageUrl(sessionServer(server), itemId, imageType, tag, maxWidth, maxHeight, imageIndex)

  fun videoStreamUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
  ): String = EmbyClient.videoStreamUrl(sessionServer(server), itemId, mediaSourceId)

  /**
   * 多版本媒体：**指定版本**的直连播放地址（先问一次 `PlaybackInfo`）。
   *
   * 为什么不能直接用 [videoStreamUrl]：官方文档把 `PlaySessionId` 与 `MediaSourceId`
   * 一起列为 `/Videos/{Id}/stream` 的必需参数，缺了前者服务器会无视我们选的版本。
   * 详见 [EmbyClient.directStreamUrlForSource]。
   *
   * @return 该版本的地址；拿不到返回 null，调用方回退到 [videoStreamUrl]。
   */
  suspend fun directStreamUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
  ): String? = EmbyClient.directStreamUrlForSource(sessionServer(server), itemId, mediaSourceId)

  fun subtitleUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): String = EmbyClient.subtitleUrl(sessionServer(server), itemId, mediaSourceId, subtitleIndex, format)
}

/**
 * Ticks (Emby 内部时间单位, 10000 ticks = 1ms = 0.001s) 与秒/毫秒转换。
 */
object EmbyTicks {
  const val TICKS_PER_MS = 10_000L
  const val TICKS_PER_SECOND = 10_000_000L

  fun ticksToSeconds(ticks: Long?): Long = (ticks ?: 0) / TICKS_PER_SECOND

  fun ticksToMs(ticks: Long?): Long = (ticks ?: 0) / TICKS_PER_MS

  fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND

  fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
}
