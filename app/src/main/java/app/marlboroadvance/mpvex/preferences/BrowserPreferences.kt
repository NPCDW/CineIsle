package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleMarks
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyGachaStyle
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarAlgorithm

/**
 * Local presentation preferences for the read-only Emby browser and player.
 */
class BrowserPreferences(
  private val preferenceStore: PreferenceStore,
  @Suppress("UNUSED_PARAMETER") context: android.content.Context,
) {
  /**
   * 随机播放取多少条。**所有随机入口统一读这里**：
   * 媒体库工具行上的「随机播放 / 随机播放收藏」、以及视界流（仿抖音竖屏连播）。
   */
  val randomPlayCount = preferenceStore.getInt("random_play_count", 100)

  /**
   * 随机播放 / 视界流的**手势分工**：true = 单击下钻、长按只播当前目录（默认）。
   *
   * 为什么做成可切：下钻是「按文件夹组织片子」的人想要的（大部分集合都是
   * `剧名/季/集` 这种结构），但按「一层装一批、各层是不同内容」组织的人
   * 更常用的是**只播这一层** —— 对他们来说每次都得长按，反而更别扭。
   * 两种习惯都合理，所以给一个总开关，而不是替用户决定。
   */
  val embyRandomDrillDownOnTap = preferenceStore.getBoolean("emby_random_drill_down_on_tap", true)

  /**
   * 随机播放入口的手势提示（Toast）是否显示，默认 true。
   *
   * 每次点随机都弹一句「含子文件夹 / 只播当前目录」对老用户是噪音 ——
   * 但对手势分工不熟的人，这是唯一能发现长按的入口。给开关，不替用户决定。
   * ⚠️ 只关「动作说明」，不关「取不到内容的提示」：后者是唯一的失败反馈，
   * 关掉就成了「点了没反应」。
   */
  val embyRandomGestureToast = preferenceStore.getBoolean("emby_random_gesture_toast", true)

  /**
   * 随机抽卡一次抽几张：**1（默认）/ 3 / 5 / 10（上限）**，单位「张」。
   *
   * 为什么给固定档位而不是让人随便填：抽卡这个动作本身就是**成批**语义（单抽 / 十连），
   * 填个 7 张既没有仪式感、卡面网格也排不出整齐的行；而且数量直接影响请求数，
   * 固定档位才好把代价写清楚。
   *
   * 为什么上限是 10：多抽的每张卡都要补一次详情（`/Items/{id}`），20 张在网盘库上
   * 就是 20 次请求；10 张在列表接口里只多几百字节，卡面（5 列 × 2 行）也还排得下。
   * 另外 10 是「再抽一次」的成本上限 —— 重抽按钮的文案直接写「再抽 10 张」，
   * 用户点之前就知道要花多少。
   *
   * ⚠️ 读取处一律 `coerceIn(1, 10)`：偏好是明文存的，手改过 / 旧版本残留都可能越界，
   *    越界的值会让抽卡界面按 0 张或 30 张去取数。
   */
  val embyGachaDrawCount = preferenceStore.getInt("emby_gacha_draw_count", 1)

  /**
   * 单抽揭晓的动画形态：经典翻牌 / 滚动揭晓。
   *
   * ⚠️ **只有单抽会用到它**：多抽永远走经典翻牌（见 [EmbyGachaStyle] 的说明），
   * 所以设置项上只写「仅单抽生效」，不按当前抽卡数量去禁用 —— 用户完全可能
   * 先挑好动画、再去把数量改成 1，禁用会让人以为这个选项坏了。
   *
   * ⚠️ 存的是枚举 `name`，发布后不可改名。
   */
  val embyGachaStyle = preferenceStore.getEnum("emby_gacha_style", EmbyGachaStyle.CLASSIC)

  /**
   * 侧边可拖动滚动条的总开关（Emby 媒体库等长列表右侧那根）。
   *
   * **全局唯一入口**：「设置 → 外观 → 侧边滚动条」。所有长列表要显示滚动条，
   * 统一套 [app.marlboroadvance.mpvex.ui.utils.LazyGridSideScrollbar] /
   * [app.marlboroadvance.mpvex.ui.utils.LazyListSideScrollbar] 这两个外壳，
   * 由它们读本偏好 —— 新增列表时套壳即自动遵循，不要绕过外壳直接调库。
   *
   * ⚠️ 关掉时外壳是**不套壳**（直接渲染内容），而不是把 thumb 透明度归零：
   * 透明 thumb 仍占触摸拖动区，会留下一道「摸得到却看不见」的死区。
   */
  val sideScrollbarEnabled = preferenceStore.getBoolean("side_scrollbar_enabled", true)

  // Watched threshold preference (percentage 1-100)
  val watchedThreshold = preferenceStore.getInt("watched_threshold", 95)

  // ── Emby 媒体库浏览偏好 ──

  /**
   * 媒体库内的排序方式。
   *
   * 取值与 Emby `/Items` 的 `SortBy` 参数一致（SortName / DateCreated / …），
   * 持久化后重新进入媒体库仍沿用上次选择。
   */
  val embyLibrarySortBy = preferenceStore.getString("emby_library_sort_by", "SortName")

  /** 媒体库内的卡片样式名（对应 [EmbyCardStyle] 的枚举名：POSTER / BACKDROP / BANNER） */
  val embyLibraryCardStyle = preferenceStore.getString("emby_library_card_style", "POSTER")

  /**
   * 媒体库排序方向。**空串 = 跟随该排序项的自然方向**（名称升序、加入时间降序…）；
   * 用户在库里点过升降序箭头后写死成 "Ascending" / "Descending"。
   *
   * 换排序项时会清空回空串重新跟随 —— 否则「名称」点成降序后切到「加入时间」，
   * 会变成最旧的排在最前面，几乎没人想要。
   */
  val embyLibrarySortOrder = preferenceStore.getString("emby_library_sort_order", "")

  /**
   * 每个媒体库各自记住的筛选条件（[EmbyLibraryFilterState] 的 JSON 串，空串 = 没筛过）。
   *
   * key 带上 libraryId：电影库和剧集库的筛选互不干扰。
   */
  fun embyLibraryFilter(libraryId: String): Preference<String> =
    preferenceStore.getString("emby_library_filter_$libraryId", "")

  /**
   * 这台服务器**支不支持**用 `GenreIds` / `TagIds` 按 Id 筛类型 / 标签。
   *
   * 三态（空串 = 还不知道）：
   *  - `""` 未知 → 先按支持试一次；
   *  - `"1"` 支持 → 之后一直走服务端筛（正常分页，不扫全库）；
   *  - `"0"` 不支持 → 之后直接走客户端本地筛，不再白试。
   *
   * 为什么要记：按名字的 `Genres` / `Tags` 参数在部分服务端会踩到坏列直接 500，
   * 但**按 Id 的 `GenreIds` / `TagIds` 一般没事**（网页端就是这么筛的）。
   * 万一某台服务器连 Id 也不认，试一次失败后记住，后续不再每次白跑一趟。
   */
  fun embyGenreIdFilterSupport(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_genreid_filter_support_$serverId", "")

  /**
   * 每个媒体库各自记住的**分类**（全部 / 继续播放 / 合集 / 收藏 / 文件夹，存枚举名）。
   *
   * 空串 = 没选过 → 用「全部」。key 带 libraryId，跨库互不干扰。
   *
   * **为什么必须落盘而不是只 `remember`**：从库里下钻进一个文件夹（或剧集 → 季）再返回时，
   * 这一页的组合会被重建，`remember` 里的分类直接丢、被打回「全部」。
   * 和排序 / 筛选一样存进偏好，返回与重启都能回到用户上次选的那一档。
   */
  fun embyLibraryCategory(libraryId: String): Preference<String> =
    preferenceStore.getString("emby_library_category_$libraryId", "")

  /**
   * 混合库的**内容三态**（仅媒体 / 全部 / 仅照片，存 [EmbyPhotoMode] 的 key）。
   *
   * key 带 libraryId，按库各自记住。默认空串 = 仅媒体（与加这个开关之前
   * 的行为完全一致，老用户升级后看不到任何变化）。
   *
   * ⚠️ 只有**混合库 / 家庭视频库 / 未知类型库**的「全部」分类才显示这个开关，
   * 读取方（媒体库页）负责判断可用性；这里只负责存取。
   */
  fun embyLibraryPhotoMode(libraryId: String): Preference<String> =
    preferenceStore.getString("emby_library_photo_mode_$libraryId", "")

  /**
   * 首页「媒体库」卡片的**置顶列表**（逗号分隔的库 Id）。
   *
   * 顺序即优先级：第 0 个排最前 —— 也就是「最后置顶的排第一」，
   * 所以置顶动作是把 Id 插到表头（规则见 [app.marlboroadvance.mpvex.domain.emby.LibraryOrdering]）。
   *
   * **按服务器分开存**：不同服务器的库 Id 完全不同，混在一起会互相挤名额。
   */
  fun embyLibraryPins(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_library_pins_$serverId", "")

  /**
   * 首页「媒体库」卡片的**顺序号**（`id:号` 逗号分隔）。
   *
   * 置顶的库固定排在最前，所以序号实际决定的是「非置顶那一段」的先后；
   * 有置顶库时，序号 1 的库也排在所有置顶库之后。
   */
  fun embyLibraryNumbers(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_library_numbers_$serverId", "")

  /**
   * 首页「媒体库」横滑里被**隐藏**的库 Id 列表（逗号分隔，空串 = 一个都不隐藏）。
   *
   * 语义是纯**显示层**过滤：进了这张表的库不再出现在首页那一眼的横滑里，
   * 但库本身还在服务器上、也仍然能在「调整顺序号」弹窗里看到与排序 ——
   * 只是首页少滑两下（库特别多的用户的需求）。
   *
   * 与 [embyLibraryPins] / [embyLibraryNumbers] 一样**按服务器分开存**：
   * 库 Id 是服务器级标识，混存会互相误伤。
   *
   * 序列化沿用「逗号分隔」的小串（库 Id 是十六进制、不含逗号），
   * 读写直接复用 [app.marlboroadvance.mpvex.domain.emby.LibraryOrdering]
   * 的 parsePins / serializePins。
   */
  fun embyHiddenLibraries(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_hidden_libraries_$serverId", "")

  /**
   * 「最近播放」账本（`id:时间戳` 逗号分隔，见 [app.marlboroadvance.mpvex.domain.emby.EmbyRecentPlays]）。
   *
   * 存在的理由：这个服务端的 `Items/Resume` **不按播放时间排序**，只按库里的固定顺序
   * 切前 N 条 ⇒ 刚播的条目能不能出现在首页全靠运气。服务端又不记 `LastPlayedDate`，
   * 所以只能本地记一份，首页据此把最近播过的排到最前。
   *
   * **按服务器分开存**：条目 Id 是服务器级标识，混在一起会互相挤名额。
   */
  fun embyRecentPlays(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_recent_plays_$serverId", "")

  /**
   * 「中文字幕」路径标记配置（[ChineseSubtitleMarks] 的 JSON 串，空串 = 用默认标记）。
   *
   * 全局一份、不按库分：标记是**资源命名惯例**，跟具体哪个库无关。
   * 放在偏好里而不是写死在代码里，是为了库里出现新的标记写法时能自己加，
   * 不必重新发版。
   */
  val embyChineseSubtitleMarks = preferenceStore.getString("emby_chinese_subtitle_marks", "")

  /**
   * 收藏页选中的内容类型（影片 / 演员，存枚举名，空串 = 影片）。
   *
   * **为什么落盘**：从收藏页点进某位演员的作品页再返回时，这一屏的
   * `remember` 状态会整个重建，只放在组合里的话就被打回「影片」。
   * 存进偏好后，返回、甚至重启 App 都还停在用户上次选的类型。
   */
  val embyFavoritesTab = preferenceStore.getString("emby_favorites_tab", "")

  /**
   * 收藏页的排序方式（存枚举名，空串 = 默认「名称」）。
   *
   * 收藏页是一次性拉全量（200 条）后本地排序，所以排序切换是即时的、不再发请求。
   */
  val embyFavoritesSort = preferenceStore.getString("emby_favorites_sort", "")

  /**
   * 收藏页排序的**方向**（true = 升序），与 [embyPersonSortAsc] 同一套做法。
   *
   * 只存排序项、方向留在组合里的话，从详情页返回后「年份 ↓」会变回「年份 ↑」。
   */
  val embyFavoritesSortAsc = preferenceStore.getBoolean("emby_favorites_sort_asc", true)

  /**
   * 演员 / 导演作品页的排序方式（存枚举名，空串 = 默认「名称」）。
   *
   * 与 [embyFavoritesSort] 同一套语义：作品是一次性拉全后本地排序，切换即时生效、不发请求。
   * 落盘的原因也一样 —— 从作品页点进详情页再返回，这一屏的组合状态会重建，
   * 只放在 `remember` 里会被打回默认排序。
   */
  val embyPersonSort = preferenceStore.getString("emby_person_sort", "")

  /**
   * 演员 / 导演作品页排序的**方向**（true = 升序）。
   *
   * 与 [embyPersonSort] 成对落盘：只存排序项、方向留在组合里的话，从详情页返回时
   * 排序项还在、方向却被打回默认 —— 同一屏上「名称 ↑」变「名称 ↓」比整体重置更让人困惑。
   */
  val embyPersonSortAsc = preferenceStore.getBoolean("emby_person_sort_asc", true)

  /**
   * 「预解析直链（302）」开关。
   *
   * 打开后，播放前先把 `/Videos/{id}/stream` 背后跳转到的真实直链取出来再交给播放器。
   * 针对网盘 / STRM 这类**有 302 跳转、且直链带有效期**的源 ——
   * 某些播放器内核不跟跳转、或播到一半直链过期会卡死，预解析能绕开这两个坑。
   *
   * 默认**关闭**：多一次网络往返，对直出型的服务器（绝大多数）没有收益；
   * 由用户按自己的源决定要不要开（解析失败会自动回退到原始地址，不会挡住播放）。
   */
  val embyResolveDirectLink = preferenceStore.getBoolean("emby_resolve_direct_link", false)

  /**
   * 多版本媒体的**优先选择规则**（存 [MediaSourcePreference.key]，不是枚举名）。
   *
   * 存 key 而不是枚举名：枚举重命名 / 增删会让 `valueOf` 抛异常或误匹配，
   * key 是稳定契约（见 `MediaSourcePreference` 的注释）。
   *
   * 默认 `DEFAULT` = 不自动选、跟随服务器 —— 与加这个功能之前行为完全一致，
   * 老用户升级后不会看到任何变化。
   */
  val embyMediaSourcePreference = preferenceStore.getString("emby_media_source_preference", "default")

  /**
   * 媒体库内的**视图模式**（[EmbyLibraryViewMode] 的枚举名，空串 = 网格）。
   *
   * 三种：网格（海报 / 背景图 / 横幅三选一）、紧凑列表（一行一条，信息密度最高）、
   * 年份时间轴（按播出年份分组，跨年找片快）。落盘的理由和分类一样 ——
   * 从库里钻进文件夹再返回时这一页会被重建，只放 `remember` 会掉回默认值。
   */
  val embyLibraryViewMode = preferenceStore.getString("emby_library_view_mode", "")

  /**
   * 海报网格**每行几个卡片**（2~6，默认 3）。
   *
   * 只对「海报」样式生效：背景图 / 横幅是宽图，行数由自身最小宽度自适应决定，
   * 硬塞进固定列数会被压得又窄又小。屏幕大的设备可以调到 5~6，
   * 手机上一行 2~3 个看得清封面上的字。
   */
  val embyLibraryGridColumns = preferenceStore.getInt("emby_library_grid_columns", 3)

  /**
   * 媒体库卡片**标题最多显示几行**（1 或 2，默认 1）。
   *
   * 有些片子名字很长（尤其带季集号、压制组后缀的），一行放不下就被省略号截掉，
   * 只认得前半截。调成 2 行能看全，代价是卡片整体变高、一屏能看到的条目变少 ——
   * 所以默认保持 1 行，谁需要谁自己开。
   *
   * 只对网格 / 年份时间轴里的卡片生效：紧凑列表本来就是「一屏扫尽量多条」，
   * 那里把标题撑成两行只会砸掉它的定位。
   */
  val embyLibraryTitleLines = preferenceStore.getInt("emby_library_title_lines", 1)

  /**
   * 文件夹 / 合集卡片的多宫格封面是否**随机合成**（默认开）。
   *
   * 容器条目（Folder / BoxSet / CollectionFolder）自身没有封面，客户端会拉内部若干个
   * 视频的缩略图拼成 1/2/3/4 宫格充当封面。开＝每次从文件夹内部**随机**取样若干视频合成，
   * 关＝按名称顺序固定取前几个。两种模式都会**跳过没封面的条目**、一直往后凑，直到凑满
   * 四张或扫完为止 —— 所以「前几个没封面就整张空白」不会发生。
   *
   * 默认**开**：随机取样能把文件夹里更多片子露出来，观感比固定前四张活泼。
   * （随机只影响取样，登录后的顺序、缓存键都按模式区分，来回切不会串图。）
   */
  val embyFolderRandomCover = preferenceStore.getBoolean("emby_folder_random_cover", true)

  /**
   * 宫格封面的「重新合成策略」——决定**多久**才允许把已经拼好的封面换一批。
   *
   * 随机取样每次都不一样，所以「什么时候该换」必须交给用户定（见 [FolderCoverRefreshPolicy]）：
   * 默认 [FolderCoverRefreshPolicy.Manual] —— **只有下拉刷新才换**，单纯进出
   * （含从子文件夹返回上一级再进来）一律复用已合成的封面。
   * 选 [FolderCoverRefreshPolicy.Always] 则每次进库都换；选小时 / 天则期间一律复用缓存、
   * 不产生任何请求。
   *
   * ⚠️ 只对「随机合成」有意义：关掉 [embyFolderRandomCover] 时取样结果是确定的，
   *    重取拿到的还是同一批图，策略整体跳过（见 `EmbyLibraryScreen` 的判定）。
   */
  val embyFolderCoverRefreshPolicy =
    preferenceStore.getEnum(
      "emby_folder_cover_refresh_policy",
      FolderCoverRefreshPolicy.Manual,
    )

  /** 「满 X 小时重新合成」的 X：1~168（一周），默认 6 */
  val embyFolderCoverRefreshHours = preferenceStore.getInt("emby_folder_cover_refresh_hours", 6)

  /** 「满 X 天重新合成」的 X：1~30，默认 1 */
  val embyFolderCoverRefreshDays = preferenceStore.getInt("emby_folder_cover_refresh_days", 1)

  /**
   * 上一次**整体重新合成**宫格封面的时间（墙钟毫秒，0 = 从未合成过）。
   *
   * ⚠️ 必须**落盘**：策略是「距上次满 X 小时 / X 天」，只放进程内的话每次冷启动都会
   *    被判成「从没合成过」⇒ 每开一次 App 就把所有封面重取一遍，策略等于没开。
   * ⚠️ 必须用**墙钟**（`System.currentTimeMillis`），不能用 `elapsedRealtime`：
   *    后者开机清零、跨重启归零，差值要么变成负数要么突然变得极大，两种都判错。
   */
  val embyFolderCoverLastSynthAt =
    preferenceStore.getLong("emby_folder_cover_last_synth_at", 0L)

  // ── Emby 卡片外观（统一项）──
  //
  // 这三项是「把散落的视觉常量收口成设置」：圆角原来写死在 EMBY_CARD_CORNER，
  // 角标（进度 / 作品数）也是硬开着。收口之后卡片的样子只有**一个来源**，
  // 用户在「设置 → Emby 卡片与封面」里改一次，全 App 的卡片一起变。

  /** 卡片圆角（dp），范围 0~24，默认 12 */
  val embyCardCorner = preferenceStore.getInt("emby_card_corner", 12)

  /** 卡片间距（dp），网格与紧凑列表共用，范围 2~24，默认 8 */

  /**
   * 是否显示封面角标（播放进度条 / 作品数）。
   *
   * 关掉后卡片更干净，代价是看不出「看到哪儿了」；默认**开**，
   * 因为进度条是大部分人判断「要不要接着看」的主要线索。
   */
  val embyShowCardBadges = preferenceStore.getBoolean("emby_show_card_badges", true)

  /**
   * 文件夹类型图标的颜色：true = 蓝色，false = 白色（默认）。
   *
   * 白色对比度更高、和「剩余时长」「作品数」等角标同一套语言；蓝色更醒目，但小尺寸
   * 在深色角标上会发灰、且偏「按钮感」。两种都有人要，就把选择权交给用户。
   *
   * ⚠️ 刻意**不**进 `EmbyDisplayPrefsSync`：这是纯本地观感项，不属于「界面布局」，
   * 不该跟着账号同步到别的设备上把别人的配色也改掉。
   */
  val embyFolderIconBlue = preferenceStore.getBoolean("emby_folder_icon_blue", false)

  // ── Emby 浏览交互开关 ──

  /**
   * **预测性懒加载**总开关（媒体库 / 收藏 / 历史 / 标签结果 /
   * 照片网格 / 照片浏览器等所有分页列表通用）。
   *
   * 开（默认）= 距列表末尾约一屏（`PagedList.PREFETCH_DISTANCE`，3 列网格约 4 行）
   * 就提前拉下一页，快速fling 也来得及；关 = 贴近末尾才拉（老行为，省请求）。
   *
   * 读取方统一走 `rememberPredictivePreloadDistance(browserPreferences)` 拿距离，
   * 不要在各自页面里硬编码 —— 否则设置里关了某页还在预取。
   */
  val embyPredictivePreload = preferenceStore.getBoolean("emby_predictive_preload", true)

  /**
   * 详情页是否显示推荐区（「推荐」+「同类型推荐」两个横向区块）。
   *
   * 关掉后媒体详情页只留影片信息与演职员，不再往下展示推荐内容 ——
   * 页面更短，也省掉两次推荐相关的网络请求。默认**开**。
   */
  val embyShowRecommendations = preferenceStore.getBoolean("emby_show_recommendations", true)

  /**
   * 详情页「同类型」推荐是否**随机选取类型**。
   *
   * 一部片子往往属于多个类型（剧情 / 惊悚 / 犯罪…）。固定取第一个类型的话，
   * 同一部片每次打开看到的推荐都一模一样。打开后每次进详情页从它的类型里随机挑一个，
   * 于是每次刷新都能看到不同题材的同类片。默认**开**。
   */
  val embyRandomGenreRecommend =
    preferenceStore.getBoolean("emby_random_genre_recommend", true)

  /**
   * 首页是否显示「随便看看」区块（即混的一批）。
   *
   * 这个区块是首页「最新加入」下面那一行横滑，作用是给首页一个「点开有惊喜」的入口。
   * 有人喜欢一进首页就看到推荐，也有人只想看新片 —— 所以做成可关。
   *
   * 关掉后**同时**不再发起那一次推荐请求（`EmbyHomeScreen` 里的
   * `LaunchedEffect(serverId)` 会一起被跳过），不是只把 UI 藏起来。
   * 默认**开**。
   */
  val embyHomeShowMix = preferenceStore.getBoolean("emby_home_show_mix", true)

  /**
   * 「随便看看」区块的条目数。
   *
   * 一行横滑放太多会让人滑不到头就腻，放太少又不像「一堆」。默认 12。
   * 范围 4~30，超出直接夹到边界（偏好值可能被手改成任意数）。
   */
  val embyHomeMixCount = preferenceStore.getInt("emby_home_mix_count", 12)

  /**
   * 「猜你喜欢」页里**「连播列表」区块**每行放几个（2~6，默认 3）。
   *
   * 刻意与 [embyLibraryGridColumns] 分开存：媒体库列数是跟着屏幕宽窄调的，
   * 连播列表的列数是「一批推荐想看几列」的偏好。共用一个键就会出现「改一边、两边一起变」。
   *
   * 读取处一律要 `coerceIn(2, 6)`：偏好文件可能被外部改坏，而 0 列会让网格直接崩。
   */
  val embyMixGridColumns = preferenceStore.getInt("emby_mix_grid_columns", 3)

  /**
   * 搜索联想词列表是否**收起**。
   *
   * 联想只是「顺手补全」的参考，不是主角 —— 下面才是真正的搜索结果。展开时最多占
   * 三行出头的高度（再多就在卡片内部滚动），收起后只留一行标题。
   * 记的是「收起」而不是「展开」：默认展开，用户点一次收起后就一直保持收起
   * （默认值 false = 展开，与改之前的表现一致）。
   */
  val embySearchSuggestCollapsed = preferenceStore.getBoolean("emby_search_suggest_collapsed", false)

  /**
   * 「相似推荐 / 即混」用哪套算法（[SimilarAlgorithm]），默认 [SimilarAlgorithm.EMBY]。
   *
   * 服务端算法更准但依赖服务端支持与元数据完整度；本地算法（`LocalSimilarity`）
   * 永远能出结果。选 LOCAL 的典型场景：自建服务没开推荐索引、元数据刮削不全、
   * 或者用的是第三方兼容实现（`/Items/{Id}/Similar` 直接 404）。
   *
   * ⚠️ 存的是枚举名，常量名发布后不能改。
   */
  val embySimilarAlgorithm =
    preferenceStore.getEnum("emby_similar_algorithm", SimilarAlgorithm.Default)

  /**
   * 本地算法是否**排除已看过的条目**。
   *
   * 默认**开**（「你刚看完的片子不用再推给你」）；关掉后可以把看过的也列出来，
   * 用于「重刷一遍老片」的场景。
   */
  val embySimilarExcludePlayed = preferenceStore.getBoolean("emby_similar_exclude_played", true)

  // ── 照片（图集）──

  /**
   * 照片浏览器「幻灯片播放」的翻页间隔，默认 [PhotoSlideshowInterval.FIVE_SECONDS]。
   *
   * **全局一份、不按库分**：这是「看照片的习惯」，不是某个媒体库的属性。
   * 设置入口在「设置 → Emby 照片浏览 → 幻灯片间隔」。
   *
   * 为什么是固定三档而不是滑块：幻灯片是「放手让照片自己走」的场景，
   * 常用就 3 秒（快览）/ 5 秒（默认）/ 10 秒（细看），滑块拖出来的 4.7 秒
   * 没有意义还容易误触。
   */
  val photoSlideshowInterval =
    preferenceStore.getEnum("photo_slideshow_interval", PhotoSlideshowInterval.Default)

  /**
   * 照片网格**每行几个**（2~4，默认 3）。读取处一律 `coerceIn(2, 4)` ——
   * 偏好文件可能被外部改坏，0 列会让瀑布流直接崩。
   *
   * 与 [embyLibraryGridColumns] 分开存：那是海报网格（视频），这是照片网格，
   * 两者的合适密度不一样（照片更小更密），共用一个键会互相干扰。
   */
  val photoGridColumns = preferenceStore.getInt("photo_grid_columns", 3)

  /**
   * 照片网格的排序方式，取值与 Emby `/Items` 的 `SortBy` 一致：
   * `SortName`（名称）/ `DateCreated`（拍摄时间）/ `Random`（随机）。
   */
  val photoSortBy = preferenceStore.getString("photo_sort_by", "SortName")

  /**
   * 照片网格排序方向。**空串 = 跟随该排序项的自然方向**（名称升序、时间降序），
   * 与 [embyLibrarySortOrder] 同一套语义；用户点过升降序箭头后写死。
   */
  val photoSortOrder = preferenceStore.getString("photo_sort_order", "")

  /**
   * 打开照片浏览器时**自动开始幻灯片播放**，默认**关**。
   *
   * 关 = 进浏览器停在第一张，用户自己滑 / 自己点播放键；
   * 开 = 进浏览器就按 [photoSlideshowInterval] 自动翻页，适合「当相框用」。
   * 全局一份、不按库分 —— 这是看照片的习惯，不是媒体库的属性。
   * 设置入口在「设置 → Emby 照片浏览 → 幻灯片间隔」下方。
   */
  val photoAutoSlideshow = preferenceStore.getBoolean("photo_auto_slideshow", false)

  /**
   * 照片浏览器**前后各预加载几张**（1~3，默认 1）。
   *
   * 读取处一律 `coerceIn(1, 3)` —— 偏好文件可能被外部改坏，0 会让预取直接不生效、
   * 999 会一次打出上百个请求。
   *
   * **为什么不设更大**：预取的是全屏图（maxWidth=2048），一张十几 MB。
   * Coil 的内存缓存按堆的 25% 封顶（见 EmbyPhotoImageLoader.MEMORY_PERCENT），
   * 超了自动丢最旧的，所以调大不会撑爆进程，只会让**当前这张之外的图更容易被顶掉**，
   * 等于「预取了个寂寞」还多花了流量。3 张已经是收益递减的拐点。
   */
  val photoPreloadCount = preferenceStore.getInt("photo_preload_count", 1)

  /**
   * 照片浏览器**底部缩略图胶片条**开关，默认**关**。
   *
   * 关 = 整屏只有这一张照片（沉浸）；开 = 底部多一条横向缩略图，点一下跳到那张。
   * 默认关的理由：胶片条要吃掉 ~64dp 的高度，而照片浏览器最值钱的就是画面面积；
   * 想要「快速跳图」的人自己打开即可。
   */
  val photoFilmstrip = preferenceStore.getBoolean("photo_filmstrip", false)

  /**
   * 「上次在这个库/相册里看到第几张」，按 parentId 各存一份，-1 = 没有记录。
   *
   * 用途：重新进入相册时网格自动滚动到上次看的位置，不用从头翻。
   * 两个来源写同一个键：
   *  · 照片浏览器退出时写「当前第几张」（看过哪张就回到哪张）；
   *  · 网格页自己退出时写「第一个可见项」（只是滚动浏览、没点开照片也记得住）。
   *
   * ⚠️ 做成**函数**而不是 `val`，因为 key 里带 parentId（每个相册一份），
   * 不可能为每个相册预先声明一个字段。调用方用
   * `remember(parentId) { prefs.photoLastIndex(parentId) }` 拿稳定对象再 collect。
   */
  fun photoLastIndex(parentId: String) =
    preferenceStore.getInt("photo_last_index_$parentId", -1)

  /**
   * 与 [photoLastIndex] 配套的**像素级偏移**（该可见项滚过了多少 px）。
   *
   * 只记 index 会丢掉「同一项内的滚动量」：一屏 3 列时差一格就是差半屏，
   * 恢复后看着像「差一点点」，用户还得自己微调。两个值一起存才能精确还原。
   */
  fun photoLastOffset(parentId: String) =
    preferenceStore.getInt("photo_last_offset_$parentId", 0)

}

/**
 * 照片浏览器幻灯片播放的翻页间隔。
 *
 * ⚠️ 存的是**枚举名**（`getEnum`），常量名发布后不能改 —— 改了老用户的偏好会失效。
 */
enum class PhotoSlideshowInterval(
  /** 间隔秒数，直接给幻灯片协程用 */
  val seconds: Int,
) {
  THREE_SECONDS(3),
  FIVE_SECONDS(5),
  TEN_SECONDS(10),
  ;

  companion object {
    /** 默认 5 秒：快览不赶、细看不等 */
    val Default = FIVE_SECONDS
  }
}

/**
 * 宫格封面的「重新合成策略」。
 *
 * 四档回答的是同一个问题 ——「多旧的合成结果算旧」：
 * [Manual] = 只有手动下拉刷新才算旧（默认）；[Always] = 任何旧结果都算旧。
 *
 * ⚠️ 存的是**枚举名**（`getEnum`），常量名发布后不能改 —— 改了老用户的偏好会失效。
 *
 * ⚠️ 四档只在**每次进库 / 下拉刷新**时判定一次，不是每次渲染都判 ——
 * 判定结果要么「整批换代重取」要么「什么都不做」，中间没有第三种状态
 * （`EmbyFolderCoverCache` 的换代本来就是全量的，所以时间戳也只能是全局一个）。
 */
enum class FolderCoverRefreshPolicy(
  /** 设置项下拉里的展示名 */
  val label: String,
) {
  /**
   * **只**在下拉刷新时重新取样；单纯进进出出（含从子文件夹返回）一律复用已合成的封面。
   *
   * 这是默认值：只有下拉刷新会换代，「进一个文件夹 → 滑一下 → 返回上一级 → 再进来」
   * 不会重新合成。默认写成 [Always] 的话每次进任何一层都会整屏重取一遍，
   * 四宫格当场洗牌换脸。
   * （看着像「只有一部分」，是因为子项不超过 4 个的文件夹怎么抽都是同一批图，
   *   抽不出新花样；子项多的那些每次都不一样，于是显得随机。）
   */
  Manual("仅下拉刷新时重新合成"),

  /** 每次进入媒体库都重新取样（有旧图也不复用） */
  Always("每次进入都重新合成"),

  /** 距上次合成满 [BrowserPreferences.embyFolderCoverRefreshHours] 小时才换 */
  Hours("满 X 小时重新合成"),

  /** 距上次合成满 [BrowserPreferences.embyFolderCoverRefreshDays] 天才换 */
  Days("满 X 天重新合成"),
}

/**
 * 这个策略对应的「多久算旧」（毫秒）。
 *
 * [FolderCoverRefreshPolicy.Always] 返回 0（任何旧结果都算旧）。
 * [FolderCoverRefreshPolicy.Manual] 返回 [Long.MAX_VALUE] —— 它压根不靠时间判定
 * （见 [shouldResynthesizeCover]），给个极大值是为了让「直接拿阈值比大小」的调用点
 * 也不会误判成「该换了」。
 *
 * 两个 X 都做了 `coerceIn` —— 用户被 UI 拦住填 0/负数，但偏好是持久化的、可以手改，
 * 这里兜底。
 */
fun FolderCoverRefreshPolicy.thresholdMillis(
  hours: Int,
  days: Int,
): Long =
  when (this) {
    FolderCoverRefreshPolicy.Always -> 0L
    FolderCoverRefreshPolicy.Manual -> Long.MAX_VALUE
    FolderCoverRefreshPolicy.Hours -> hours.coerceIn(1, 168) * 3_600_000L
    FolderCoverRefreshPolicy.Days -> days.coerceIn(1, 30) * 86_400_000L
  }

/**
 * 这次要不要把已经拼好的宫格封面换一批。
 *
 * 纯函数（`now` 由调用方传入，不在里面取时间）—— 只依赖入参，能直接单测，
 * 也不会因为「函数内部悄悄读了一次时钟」而出现两个调用点判得不一样。
 *
 * 四条「一定要换」的例外：
 * 1. [FolderCoverRefreshPolicy.Always]：本来就是「有旧图就重做」；
 * 2. `lastSynthAt <= 0`：从没合成过（首次安装 / 刚清过数据）；
 * 3. `now < lastSynthAt`：**时钟回拨**（用户改系统时间 / 换时区 / NTP 校正）。
 *    不挡这一条的话差值恒为负、阈值永远达不到 ⇒ 封面永久冻结在旧图上，
 *    用户只能清 App 数据才能恢复；
 * 4. 只有 [FolderCoverRefreshPolicy.Manual] **一定不换** —— 时间到没到都不换，
 *    换图交给下拉刷新那条路（那里无条件换代）。所以它排在 `lastSynthAt <= 0`
 *    这类例外的**后面**是刻意的：从没合成过的时候，进库还是得先合成出一批，
 *    否则第一屏永远空白。
 *
 * @param lastSynthAt 上次整体重新合成的墙钟毫秒，0 = 从未
 */
fun shouldResynthesizeCover(
  policy: FolderCoverRefreshPolicy,
  lastSynthAt: Long,
  now: Long,
  hours: Int,
  days: Int,
): Boolean =
  when {
    policy == FolderCoverRefreshPolicy.Always -> true
    lastSynthAt <= 0L -> true
    now < lastSynthAt -> true
    policy == FolderCoverRefreshPolicy.Manual -> false
    else -> now - lastSynthAt >= policy.thresholdMillis(hours, days)
  }
