## Context
这次调整让服务器列表更紧凑，简化搜索筛选，并让播放历史标记操作不弹窗、不触发整页刷新。Classic/Cine 已共用相关页面，因此一次修改覆盖两种风格。工作区已有大量未提交改动，本次只在现有实现上做局部修改，不回退其他内容、不提交或推送。

## 修改方案

### 1. 缩小服务器列表选项
修改 `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/emby/EmbyServerManageScreen.kt` 的 `ServerSelectionCard`：
- 聚合搜索目前图标为 32dp、服务器名为 titleMedium；列表图标由 48dp 改为 36dp，服务器名约 18sp，略大于聚合搜索。
- 缩小卡片上下内边距、图文间距及卡片间距；服务器名默认单行省略。
- 保留禁用/无效诊断和连接信息入口，按钮仍保留至少 48dp 点击区域。

### 2. 历史标记直接操作、原地更新
关键文件：
- `ui/browser/history/PlaybackHistoryScreen.kt`
- `ui/browser/history/PlaybackHistoryViewModel.kt`
- `database/repository/PostgresPlaybackHistoryRepository.kt`

具体处理：
- 移除标记确认弹窗，按钮直接调用已有 `cycleMark(row)`。
- 保留普通 → 置顶 → 隐藏 → 普通的状态循环；收到明确提交成功后，复用按记录 key 的 `map + copy(pinned)`，仅更新目标记录标记。
- 删除成功后的整页核对：不重新 COUNT/分页查询，不重排、不跳页、不移除隐藏记录。用户主动刷新、翻页或重新进入时再采用数据库排序。
- 冲突或提交结果未知时，复用 `PostgresPlaybackHistoryStore.byId()` 仅核对目标记录；不自动重放 UPDATE，不重新查询整页。只合并目标标记，若目标确已被外部删除，只移除该记录，不拉取其他记录补位。
- 保留非模态失败/核对提示、重复点击保护和来源校验；不扩大数据库写权限，仍只允许现有条件 UPDATE 修改 pinned，不改时间、播放时长或其他字段。

### 3. 搜索默认检索电影与剧集，移除结果分类
关键文件：
- `ui/browser/emby/EmbySearchViewModel.kt`
- `ui/browser/emby/EmbySearchScreen.kt`

具体处理：
- `EmbySearchScreenState.filters` 默认设为 MOVIE + SERIES，确保实际请求的类型是 Movie、Series，而不只是视觉选中。
- 保留已有 `EmbySearchFilterRow` 检索类型控件及其多选语义，“全部”仍可手动选择；不因默认值变化而修改其他媒体库页面的默认行为。
- 移除结果分类用的 `EmbySearchTabRow`、页面内 selectedTab 和本地结果分类过滤；展示服务器按所选检索类型返回的完整结果。
- 聚合页面关键词为空或全为空格时不渲染服务器分组；清空关键词仍沿用已有取消请求逻辑。
- 删除服务器分组底部分隔线，用留白区分，保留原服务器顺序、加载/失败/空结果状态和单组重试。

### 4. Emby 反代前缀审查与补漏
已确认主要登录、列表、详情、搜索、图片、PlaybackInfo、媒体流、字幕及进度上报均使用 `EmbyEndpoints.apiBaseUrl/resolve`，会保留原站路径及配置反代前缀。

针对应用层明确缺口处理：
- `ui/browser/emby/components/EmbyServerIcon.kt`：同服务器图标在发请求前经过统一 URL 路由，避免直连原站；公共外部图标与 data 图片维持原行为，不额外注入身份。
- `domain/emby/EmbyUrls.kt`、`domain/emby/EmbyHttp.kt`：为绑定 Emby 的重定向补 URL 处理。对指回该服务器 API 的 Location 保留路径与反代前缀，并避免重复套前缀；在响应 Location 层处理，沿用 OkHttp 的跟跳规则，不在 network interceptor 中跨主机改写正在发送的请求。
- 外部 CDN 地址、签名查询参数、普通目录相对重定向及现有身份头策略不变；不把所有第三方地址强行套反代。
- 详情页外部 Web 链接及 native/HLS 派生请求单独列入检查结果，不盲目用 API 前缀重写第三方网页或播放器分片，也不宣称底层全链路已验证。

## 验证
- 扩充 `EmbySearchViewModelTest`：默认 Movie+Series 请求、默认不查询人物、手动全部/其他类型仍有效、清空输入取消请求。
- 调整 `PlaybackHistoryViewModelTest` 及 JDBC fixture：多记录三态循环只改目标 pinned；成功不增加 SELECT；列表顺序、页码、总数和其他字段不变；异常只查单记录且不重复写入。
- 扩充 `EmbySharedConnectionTest`、`EmbyHttpNetworkTest`、`EmbyServerIconTest`：完整原站路径+反代前缀、根相对/同源 API 重定向、307/308 POST、已反代地址不重复、CDN签名不改写、图标正确路由。
- 使用 fake HTTP/JDBC 运行相关单测，再运行全量 JVM 回归并构建单 ABI arm64 Debug APK；报告实际失败，区分已有失败与本次新增问题。
- 不连接或修改真实 PostgreSQL。若进入实机验收，按项目要求检测 ADB；无设备时先询问，不擅自跳过。实机重点检查两种风格的列表尺寸、搜索空态/筛选、历史点击无弹窗及列表位置稳定；安装卸载前说明数据影响。
- 在 `.zcode/AGENTS.md` 简短记录新行为和最终实际验证结果，覆盖此前“默认全部”“标记后整页核对”的旧记忆。