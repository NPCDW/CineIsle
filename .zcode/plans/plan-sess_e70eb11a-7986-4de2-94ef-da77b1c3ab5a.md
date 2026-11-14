## Context
已确认 `/workspace/.zcode/plans/plan-sess_5a1165d8-a88c-4605-9a41-ff9beea76928.md` 的实施曾发生补丁拒绝：分页快照、Classic/Cine 收藏筛选和收藏查询链只合入了部分调用端，因而产生当前的 `PagedListSnapshot`、`mediaFilter`、`queryFilter` 等编译错误；Logo 磨砂目标也只接了 Haze source，未接 effect。残留 `.rej` 仅作为意图参考，不能直接套用过时补丁。

电影复用单集详情也尚未真正实现：Movie 目前仍进入 Classic/Cine 旧 fallback。目标是补齐上述计划、恢复编译，并将 Movie 与 Episode 收口到同一“单媒体详情页”；仅 Movie 迁移，Video/MusicVideo/BoxSet 等继续使用现有兼容 fallback。用户已明确授权 Movie 与 Episode 两类执行收藏／已看两项状态操作，授权不扩展到 Series、Season、BoxSet 或其他 CRUD。

## 实施方案

### 1. 补齐中断计划并解除现有编译错误
- 在 `ui/browser/emby/PagedList.kt` 增加 `PagedListSnapshot<T>`、显式 `loaded`、`snapshot()` 和 `restore()`：恢复可见条目、服务端原始游标 `consumed`、总数和 `hasMore`，但不恢复 loading/appending/error；成功空页也标记 loaded，首次失败不标记。
- 保留并完成 `EmbyBrowsePage.kt` 的分页快照缓存语义，使筛选切换拥有独立 page，旧请求只能更新旧 page。
- 完成收藏查询全链路：
  - `EmbyClient.getFavoriteItems`、`EmbyRepository.getFavoriteItems`、`EmbyViewModel.loadFavorites` 增加 `includeItemTypes` 并正确处理 `MediaTypes`：Movie 可保留 Video，Series/All 不用 `MediaTypes=Video` 排除容器。
  - Classic `EmbyFavoritesScreen.kt` 和 Cine `CineCollectionScreens.kt` 增加“全部／电影／电视剧”状态与筛选控件，查询键升级为 v2 并包含筛选/排序快照。
  - 服务端传 `IncludeItemTypes`，客户端再次用 `FavoriteMediaFilter.accepts` 严格校验；分页始终按服务端原始 `consumedCount` 前进。原始总数只用于判断 exhausted，本地过滤后不显示为精确匹配总数。
- 在 `EmbySeriesDetailContent.kt` 完成 Logo 固定区域的局部 Haze effect：背景是唯一 source，Logo 保持清晰，加入低强度 tint/noise 和旧 Android fallback，不重复加载图片，也不干扰 Cine 的全页 Haze。

### 2. 将 Episode 页面重构为 Movie/Episode 共用单媒体详情
- 从 `EmbyEpisodeDetailContent.kt` 抽取语义中性的 `EmbyMediaDetailContent` 与公共选择模型；用严格的 `Movie` / `Episode` 模式分派，而不是用 fallback 推断类型。
- 公共层复用现有单集能力：
  - 版本／视频／音频／字幕选择和 auto/manual 快照；
  - 所选源时长、续播、从头播放、直链播放；
  - 简介、演职员、外链、ProviderIds、等高分版本媒体信息；
  - 当前 Palette 深／浅鲜艳色背景。
- 复用已存在但未接线的 `EmbyDetailMovieMetadata.kt`、`EmbyDetailMediaInfo.kt` 和 `EmbyDetailUserActions.kt`，避免再实现一套电影详情。
- Episode 专属增强单独封装：所属 Series/Season 解析、集标题、当前季真分页和当前集定位。Movie 从结构上不创建这些状态，不调用 `resolveEpisodeParents` 或 `loadEpisodes`，Hero 使用 Movie 自身，且不显示“当前季所有集”。
- Movie 保留旧页的重要能力：按选中 `MediaSourceId` 读取 `EmbyVersionProgress`，避免多版本电影退回条目级共享进度；播放继续调用现有 `EmbyViewModel.play`，自然生成单元素队列。
- 图片候选与实际 Hero 一致：Movie 用自身 Backdrop→Primary，Episode 保持当前 Primary→Backdrop；主图加载成功但没有相应 Dark/Light Vibrant 时回退应用主题，不借用未显示图片的颜色。

### 3. 严格扩展 Movie 用户状态写链
- 将 `EmbyClient`、Repository、ViewModel 中的 Episode-only 命名和校验重构为 Movie/Episode 白名单的 typed media API，接通当前编译失败的 `setMediaFavorite` / `setMediaPlayed`。
- 保留现有安全约束：合法 ItemId、POST/DELETE、禁自动重试、禁重定向、空响应或不确定结果只读回查一次、来源/登录身份变化失败关闭；回查必须匹配原 ID 与原 Type。
- `DetailUserActions` 成为共用详情唯一状态按钮实现；响应省略字段时只合并目标字段，不以 DTO 默认值清空现有进度。
- 明确拒绝 Series、Season、BoxSet、Video 等类型，且不恢复任何共享配置或其它媒体 CRUD。

### 4. 切换路由并移除 Movie 的旧页面路径
- Classic `EmbyDetailScreen.kt` 与 Cine `CineDetailScreen.kt` 显式分派：
  - Series → 现有剧页
  - Season → 现有季页
  - Episode / Movie → 新共用单媒体页
  - BoxSet、Video、MusicVideo 和未知兼容类型 → 原 fallback
- 移除 Movie 对旧 `DetailBody` / `CineDetailHero`、旧推荐请求、旧折叠顶栏和旧 action sheet 的消费，确保不会同时组合两套电影 UI。
- 旧 fallback 仍服务非 Movie 类型，因此不整块误删；仅删除确认无生产调用的 Movie/Episode 残留状态、旧分集 rail、重复用户状态实现和 imports。BoxSet 子项加载与容器行为保持不变。
- 保留当前未提交的 Palette/详情主题改动，不覆盖 `todo.md`，不提交或推送。

## 关键文件
- 中断计划：`PagedList.kt`、`EmbyBrowsePage.kt`、`FavoriteMediaFilter.kt`、`EmbyFavoritesScreen.kt`、`CineCollectionScreens.kt`、`EmbyClient.kt`、`EmbyRepository.kt`、`EmbyViewModel.kt`、`EmbySeriesDetailContent.kt`。
- 单媒体详情：`EmbyEpisodeDetailContent.kt`／新 `EmbyMediaDetailContent.kt`、`EmbyEpisodeDetailPresentation.kt`、`EmbyDetailUserActions.kt`、`EmbyDetailMovieMetadata.kt`、`EmbyDetailMediaInfo.kt`、`EmbyDetailTheme.kt`。
- 路由与旧页裁剪：`EmbyDetailScreen.kt`、`CineDetailScreen.kt`，以及确认无调用后删除的旧 rail/辅助代码。

## 验证
1. 增补单测：
   - `PagedListSnapshot` 空成功、round-trip、原始游标恢复、恢复后续页；
   - 三种收藏筛选的 HTTP 参数、本地严格过滤、原始游标、旧请求/缓存隔离；
   - Movie/Episode 路由矩阵及 BoxSet/Video 不误入新页；
   - Movie 不请求父级和季列表、Movie 多版本续播、公共选择模型；
   - Movie/Episode 状态写成功、空响应/不确定回查、不重放，以及其它类型在网络前拒绝；
   - Logo Haze source/effect/noise/fallback 与固定高度；
   - 当前 Palette 缓存、深浅 swatch 和 Cine 顶栏配色测试。
2. 先运行 `:app:compileStandardDebugKotlin`，再跑详情、分页、收藏、HTTP、导航定向测试及全量 `:app:testStandardDebugUnitTest`。
3. 执行 `git diff --check`、依赖核验和单 ABI `:app:assembleStandardDebug -Pabi=arm64-v8a`；构建成功后检查 APK 的 ABI、签名和体积。
4. 运行 Lint 并如实区分本次问题与既有基线。若需要真机验收而 ADB 无设备，按项目要求询问“重新检测设备”或“跳过实机测试”；不直接跳过。