## Context

将安卓 mpv 播放接入 loemby 的本地 Web 播放流程，而不是仅把远程 URL 换成代理地址。应用启动即监听三个路径；mpv 首先请求 `/play_media/`，该方法取得 PlaybackInfo、选择版本、建立播放会话并跳转本地 `/stream/`；文件加载后完整执行轨道挂载、初始化、事件处理和播放结算。

参考固定为 `/root/loemby` 的 main：
- `src-tauri/src/service/axum_svc.rs:11-59,168-448`：启动、三个路由及 HTTP 行为。
- `src-tauri/src/service/player_svc.rs:75-184,198-1169`：队列、版本选择、字幕缓存、命令、初始化、事件循环及结算。
- `src-tauri/src/config/http_pool.rs`、`mapper/proxy_server_mapper.rs`、`util/media_source_util.rs`：连接池、代理继承、版本与地址规则。

**不修改 `/root/loemby`，不把桌面多 mpv IPC 搬到安卓。安卓继续使用现有单 libmpv、JNI 回调、Surface 和内核初始化/销毁时序。** 基于当前未提交修改实施，不恢复已删除的通用主媒体输入、ISO、下载或手动 CRUD。

### 已确认的权限与差异
- 允许播放自动触发的共享 `play_history` 新增/更新、累计时长、系列置顶承接，以及 `emby_server.last_playback_time` 更新。
- 恢复只读获取播放/浏览/第三方同步所需代理、follow 和相关 global_config；已启用且已配置时执行 Trakt/Simkl/YamTrack 同步。
- **音量只保存安卓本机；Trakt 刷新结果只保留内存，不回写共享 global_config。** 不增加第三方授权配置页面，不回写 Emby 或 PostgreSQL 凭据。
- GSY/ExoPlayer 不接收 mpv 初始化命令；仅调整必要的播放器交接，确保切回 mpv 时经过新流程。

## 1. 应用级本地 Web 服务

新增 `domain/emby/playback/` 下的服务、注册表和播放参数模型，通过 Koin 单例注册，在 `App.kt:onCreate()` 完成 DI 后异步启动。

- 使用 Ktor CIO 承载 HTTP 服务，播放上游使用独立、按 loemby 契约配置的 HTTP 池；版本集中加入 `gradle/libs.versions.toml` 和 `app/build.gradle.kts`。
- 与 loemby 一样仅绑定 `127.0.0.1:0`，保存实际端口；不监听 LAN，不启用 Android 导出 Service，不额外创建 libmpv。
- 暴露启动就绪/失败状态；生成播放 URL 前等待服务 ready，失败明确显示错误，禁止回退成远程 URL 绕过新链路。
- 实现三张注册表：播放参数 UUID、上游请求 UUID、`播放UUID+版本序号` 跳转缓存。UUID 不能当作 Emby ItemId 或 MediaSourceId。
- 服务跟随应用进程，不能随 Activity 旋转或退出停止；播放协程与端口监听分别管理。
- 现有 Manifest 已有 INTERNET 和明文 HTTP 配置，沿用，不扩大网络暴露面。

## 2. `/stream/` 与 `/subtitle/`：按参考逐分支等价移植

这两条路由不使用安卓现有 `EmbyHttp` 重定向修正、通用字幕临时文件策略来代替，不重新设计 URL/HLS 算法。语言及服务框架不同，但保留 loemby 的处理规则、状态码、响应头和正文语义。

### `/stream/{types}/{id}`
- 按 UUID 查绑定服务器和地址；保留 404/503 分支、非绝对地址拼接规则。
- 只转发播放器的 Range，并加入参考的固定头和绑定服务器六个身份头；不得混入当前选中另一服务器的身份。
- 独立流池：30 秒连接超时、无读取超时、复用连接、参考重试规则；实现所需代理和 gzip/deflate/br/zstd 解码，不能只宣告支持而不解码。
- 禁止自动跟跳，照搬手动 3xx/Location/重定向次数处理；跨域继续携带同一身份头。**不擅自补全相对 Location 或套用现有 Android 的修正规则。**
- 保留成功/206 状态及非逐跳响应头，流式传输；照搬参考对非成功正文的处理。
- 照搬四种 HLS MIME 识别、逐行绝对 URL 匹配、反代前缀拼接、新 UUID 注册和 `/stream/m3u8/` 替换；改写响应仅保留 Content-Type。不得改成另一套 HLS parser，或擅自扩大相对链接改写范围。

### `/subtitle/{id}`
- 完整 URL 的 SHA-256 对应 `cache/subtitle/{digest}.ass`，`.ass` 仅为缓存后缀，不转换字幕格式。
- hit 返回缓存字节；保留打开失败分支、不处理 Range、不自行指定字幕 MIME。
- miss 使用参考 image 池及身份头回源，保留上游状态/非逐跳头/正文；**路由 miss 不写缓存**。
- 字幕预缓存由播放流程执行：对选中版本的所有外置字幕提前异步下载，正式文件命中跳过，只接受 200，临时文件写入、flush、rename；不阻塞起播。
- 保留字幕缓存维护与保留期；Android 文件时间 API 的差异在测试和实现说明中明确，不把原有随机临时字幕策略冒充本流程。

## 3. `/play_media/{id}/{media_source_select}` 与播放参数

新增播放参数模型，承接 server、item/series、续播 ticks、direct-link 开关、manual/auto、版本及 video/audio/subtitle 选择、队列位置/总数和显示信息。

- `/play_media/` 返回与参考一致的 **308 跳转**，目标是本机 `/stream/video/{requestUuid}`；失败走 503。
- 缓存未命中时获取完整 PlaybackInfo；补齐现有模型缺少的 `PlaySessionId/ErrorCode/DirectStreamUrl/IsRemote/ItemId/DisplayLanguage` 等字段。
- 版本参数保持 0 表示自动/参数策略、正数为 1-based 序号，URL 正数越界回退首项；自动选择保持 loemby 的分辨率档位再 Size、或 Size 降序规则，不能替换为当前安卓的像素/Bitrate 排序。
- direct Path、DirectStreamUrl、默认视频流 URL 的优先级按参考实现，最终全部注册为本地流，即使直链也不直接交给 mpv。
- 音轨/字幕选择保持参考的按类型序号、默认轨规则、外置/中文/简体字幕评分，以及 `-1=no/0=auto`。
- 保留当前 Emby-only、来源会话和已知 ISO 禁止策略；在选定源后验证，不能因为本地 URL 掩盖原始 ISO。显式转码与已有受支持入口仍需经过本地流程，保留其绑定的源/会话，不能丢弃转码参数。
- 注册完整同季队列及起始 index，保留上一集/下一集和从头/续播意图；当前旧实现只取当前及后续集，需在 mpv 分支对齐参考的完整队列。

## 4. 全部 mpv 装载入口与单 libmpv 会话适配

复用 `PlayerLib.kt:19-209` 的命令、属性和 observer 门面，以及 `PlayerObserver.kt:23-76` 的 JNI 分发。新增串行播放协调器和 libmpv transport adapter，不新增 IPC、不更换 AAR 或内核。

必须覆盖：
- `PlayerActivity.kt:683` 首次 `playFile`。
- `PlayerActivity.kt:3867-3971` 新 Intent。
- `PlayerActivity.kt:4541-4600` 切集、重试等装载。
- `feed/FeedMpvEngine.kt:149-198` 的 mpv 兜底装载，以及对应 FeedItem/调用方的 server/item/续播参数传递。
- 播放版本选择、预加载、Surface 重挂、播放器 handoff 回到 mpv 的路径。

所有 mpv 主视频装载参数都变为 `http://127.0.0.1:{port}/play_media/{uuid}/{select}`；标题、条目、源和身份通过注册表与平行元数据映射读取，不再从 mpv 的最终 path 反推。

单 libmpv 适配要求：
- 一个 observer/事件消费入口；以 owner、generation、加载阶段及可用的 playlist entry 信息关联会话。
- 注册并准备当前会话后再下发 loadfile；HTTP Range 重访、同 URL 重试、预取请求不能产生多个初始化/结算，也不能抢占当前播放。
- 旧初始化网络结果、file-loaded、end-file 和延迟命令不能落到新条目；切集/切版本先完成旧会话的逻辑收尾，再让新会话生效。
- 所有 native 调用维持 ready 门禁与安全线程/Surface 时序；事件回调不阻塞 HTTP/JDBC，不在 libmpv 读取流时同步等待它。
- mpv 新链路停用旧 `EmbyPlaybackReporter` 的重复起报/轮询，但保留 GSY 原路径；视界流原有上报也按实际引擎去重。

## 5. 完整 file-loaded、命令与事件生命周期

逐项移植 `playback_process()`、`play_info_init()`、事件循环与结束分类，不用空实现或 TODO 代替：

1. 加载前启动全部外置字幕预缓存，设置 demuxer 前后缓存参数、观测音量。
2. 首次有效 file-loaded 重新取条目信息，创建/更新共享历史、承接系列置顶，发布开始播放状态。
3. 生成本地 `/play_media/` 多版本菜单，设置媒体标题、按 ticks 续播。
4. Emby **外置 Audio** 经 `/stream/audio/` 下发 `audio-add`；全部外置 Subtitle 经 `/subtitle/` 下发 `sub-add`。这不恢复手动导入本地音轨功能。
5. 保留版本与轨道标题命令的语义；桌面 uosc 脚本消息通过安卓现有 Compose 选择器/状态接收呈现，不能发送无人处理的消息就声称功能存在。
6. 应用 vid/aid/sid，读 duration，发送 Emby Playing，完成初始化通知。
7. 每 2 秒读取 playback-time，按参考整秒舍入；每 30 秒异步发送 Progress。一次性续播与返回同集的行为显式管理，避免旧安卓恢复逻辑二次 seek 或覆写轨道选择。
8. 处理 end-file：eof 区分 Next/EndOfPlaylist，stop、redirect、quit、error 分别对应参考语义；终止轮询、清跳转缓存并执行对应结算。Android keep-open/eof-reached 也需接入同一终态，防止只等 END_FILE 导致不结算。
9. EOF 防护参照 `resources/mpv/portable_config/scripts/eof-guard/main.lua`，保留缓存提前 EOF 检测、重载和恢复位置能力；不能漏掉参考依赖的文件结束防护。
10. 退出、加载失败、切核及内核销毁均有明确终态；结算恰好一次。退出/报错使用安卓已有安全退出和错误展示，不将桌面窗口唤醒行为或无条件 quit 错用于正在播放的新会话。

## 6. 自动结算、配置和第三方同步

### PostgreSQL
- 新增只读播放配置 store/repository，读取所需 global_config 和 proxy_server，并按参考解释 no/follow/followBrowse/followPlay；不恢复编辑入口。
- 新增专用、类型化的自动播放 history writer。复用 `JdbcPostgresIo` 的有界工作线程、固定连接/TLS、来源令牌，以及 `PostgresPlaybackHistoryMarkWriter.kt` 的提交不确定处理模式。
- 默认 `PostgresExecutor.read()` 和普通连接仍 SELECT-only；自动播放使用独立连接入口，允许字段限定于已授权的历史及最近播放时间，不暴露任意 SQL/通用写事务。
- file-loaded 更新 metadata/create_time/update_time 与置顶承接；结束按参考 wall-clock 累计 played_duration（含暂停），超过 300 秒更新服务器时间；周期 Playing 只发 Emby Progress，不重复累加历史时长。
- Emby Playing/Progress/Stopped 保留参考的 item/source/session/ticks 和 DirectStream 请求契约。
- 结算异步但按会话排序，防止旧 Progress 晚于 Stopped；COMMIT_UNKNOWN 不自动重放累计写入，不将不确定提交报告为成功。

### 第三方与本机差异
- 移植 Trakt/Simkl/YamTrack 的媒体外部 ID/系列映射、启用条件、开始/结束请求、进度及完成阈值、参考限流重试和失败通知。
- 用只读共享配置与绑定的同步代理；禁用或缺配置时按参考不调用。
- 音量通过本机偏好保存，不写 global_config。
- Trakt 自动刷新只更新按配置来源隔离的内存令牌；不回写共享 token/username/switch，不增加授权路由。来源变化清除该内存会话。
- 日志和错误展示脱敏，不复制参考中打印 token、代理密码、完整签名 URL 的日志。

## 关键修改位置

- 启动/依赖：`App.kt`、`di/DomainModule.kt`、必要的 `di/DatabaseModule.kt` 注册、版本目录和 app 构建依赖。
- 新核心：`domain/emby/playback/` 下本地服务、路由、注册表、选择策略、字幕缓存、播放协调器、配置及同步模块。
- Emby 数据：`EmbyModels.kt`、`EmbyClient.kt`、`EmbyRepository.kt`；复用现有服务器来源/会话快照及身份字段，不破坏其他浏览请求的网络策略。
- 播放接入：`EmbyViewModel.kt`、`PlayerHandoff.kt`、`EmbyPlaybackInput.kt`、`PlayerActivity.kt`、`PlayerObserver.kt`、相关版本/音轨/字幕控件及 Feed mpv 调用链。
- 自动写入：`domain/postgres/` 新 playback store/writer、`PostgresConnectionFactory.kt` 的专用入口。
- `.zcode/AGENTS.md` 仅追加本次确认的权限边界与最终验证记录；不覆盖已有工作。

## Verification

1. **HTTP 契约测试**：实际本地服务配合假上游验证三个路由、308、Range/206、身份头、跨域跳转/次数限制、九种逐跳头、错误正文差异、四种 HLS MIME 与绝对链接改写、压缩正文、代理/follow、字幕 hit/miss 和缓存文件字节。
2. **选择及命令测试**：覆盖所有版本序号/排序、direct URL 优先级、video/audio/subtitle auto/manual/禁用、外置轨道与服务端 Index；用 fake libmpv 断言初始化完整命令序列及 URL 都在本机。
3. **生命周期测试**：模拟 file-loaded、redirect、EOF、stop、quit、error、失败初始化、快速切集/切版本、预取、重复 HTTP、新 Intent、Surface 重挂和核心销毁；断言不串会话、不重复起报/seek/结算，2 秒读取和30秒保存生效。
4. **数据库/同步测试**：默认 fake JDBC/HTTP，检查允许 SQL 字段、来源切换、原子置顶承接、累计时长、300 秒阈值、COMMIT_UNKNOWN、第三方启用/缺配置/限流/刷新内存隔离；断言没有 global_config 或凭据回写。
5. **构建回归**：运行 `:app:testStandardDebugUnitTest` 的相关测试及全量回归，单 ABI `:app:assembleStandardDebug`，必要的 R8 验证与 lint。已记录的 MediaSourcePreference 四项失败和既有 lint 问题以实际输出区分，不宣称全绿。
6. **设备端到端**：测试启动监听、真实 mpv 冷启动/续播/版本/内外音字幕/seek/上下集/退出及进度，包含 Feed mpv 与 GSY→mpv 交接。APK 使用设备 ABI 小包并压缩传输；安装/真实 Emby、PG 自动写入和第三方同步前确认测试目标及影响。ADB 无设备时按项目要求弹窗询问重新检测或跳过，不能默默略过。
7. **最终审查**：扫描全部 playFile/loadfile 等入口，不得残留远程主视频直送 mpv；逐项核对参考行为清单，明确仅有用户确认的本机音量/内存 Trakt 令牌，以及安卓单 libmpv/UI 平台适配差异。无提交、推送或桌面修改。