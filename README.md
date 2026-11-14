<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="loembyA" />
</p>

<h1 align="center">loembyA</h1>

<p align="center">
  <strong>读取 loemby 共享配置、使用内置 mpv 播放 Emby 媒体的 Android 客户端</strong>
</p>

## 项目定位

loembyA 是 [loemby](https://cnb.cool/my-codespace/loemby) 的 Android 伴侣客户端。它不是一个独立管理 Emby 服务器的应用：服务器、线路、认证信息和部分播放设置来自 loemby 使用的共享 PostgreSQL 数据库，Android 端只提供受约束的读取与播放能力。

项目继承关系为：

```text
loembyA → CineIsle → mpvEx → mpv-android / libmpv
```

当前工程是单一 Gradle 应用模块 `:app`，主要使用 Kotlin、Jetpack Compose 和内部 libmpv 播放器。Classic 与 Cine 是同一应用中的两套呈现风格，共用领域逻辑、数据层、导航契约和播放器行为。

### 应用标识

| 构建类型 | Application ID |
| --- | --- |
| Release | `com.github.npcdw.loembya` |
| Debug | `com.github.npcdw.loembya.debug` |
| Preview | `com.github.npcdw.loembya.preview` |

源码 namespace 为 `app.marlboroadvance.mpvex`，它不是安装包名。不同 Application ID 是彼此独立的应用，不共享本机加密的 PostgreSQL 连接配置。

## 工作方式

### 启动与配置门禁

首次启动需要填写可访问的 PostgreSQL 连接。应用在进入媒体界面前会完成配置读取和连接校验；校验未通过时不会加载 Emby 导航，也不会启动共享数据刷新。

本机 PostgreSQL 连接配置保存在 `noBackupFilesDir/postgres-config.json`，整体使用 Android Keystore AES-GCM 加密。共享数据库中的 Emby 密码、Token 等字段仍遵循 loemby 的数据库设计；不要把“本机连接文件已加密”理解为共享库里的全部凭据都经过 Android 加密。

主导航分为两层：

- 外层：服务器、跨服务器搜索、共享播放历史、设置；
- 选中服务器后：主页、收藏、搜索。

### 播放链路

```text
共享 PostgreSQL 配置
        ↓
Emby PlaybackInfo / 元数据
        ↓
127.0.0.1 进程内能力地址
        ↓
内置 libmpv 播放器
        ↓
Emby 会话上报 + 共享播放历史 + 可选第三方同步
```

播放器只接受应用内部创建、绑定到有效 Emby 会话的 HTTP(S) 媒体任务。`127.0.0.1` 服务用于隐藏上游凭据、处理媒体流和字幕，并不是可从外部调用的通用播放代理。

共享配置中的普通 HTTP/HTTPS/SOCKS 正向代理会被 Android 忽略；`reverse_proxy_server` 仍作为 loemby 兼容的 Emby URL 反向路由使用。

## 当前功能

- 从共享 PostgreSQL 读取并选择多个 Emby 服务器；共享 Token 失效时可在当前进程内重新登录，不回写共享凭据；
- Emby 主页、媒体库、文件夹下钻、继续观看、最新内容、推荐与随机浏览；
- 服务器内搜索和跨服务器聚合搜索，本机保存搜索历史；
- 排序、筛选、分页、中文字幕路径筛选和多种卡片呈现；
- Movie / Episode 收藏与已看状态操作；
- 只读合集、相似推荐和 Instant Mix；
- 照片相册、分页网格、缩放、旋转、幻灯片、胶片条和 EXIF 信息；
- 内置 mpv 播放、剧集队列、断点续播、版本选择、音轨和字幕选择、手势、画中画、后台媒体会话、Anime4K 与弹幕；
- Emby 媒体竖向连播视界流；
- Emby `/Sessions/Playing`、`/Progress`、`/Stopped` 播放会话上报；
- 共享 PostgreSQL 播放历史、累计播放时长以及 pin / hide / ordinary 标记循环；
- 可选 Trakt、Simkl、YamTrack 播放同步；
- 桌面“继续观看”小组件；
- PIN / 图案应用锁，以及本机崩溃记录页面；
- 独立 MediaInfo 分析器。

## 不包含的功能

以下边界是有意保留的，不应从旧代码、旧 Room 表或参考 SDK 恢复：

- 本地媒体库或任意文件、`content://`、分享链接的通用播放入口；
- SMB、FTP、WebDAV、AList 等独立网络文件客户端；
- 媒体下载和离线存储；
- Live TV、独立 Emby 播放列表；
- DLNA、投屏、外部播放器跳转；
- 远程 ISO 播放或 ISO 代理；
- 在 Android 上新增、编辑或删除共享服务器、线路、代理、全局配置或凭据；
- 元数据刮削、编辑、删除、人物合并和“不喜欢”列表；
- 把多个媒体库合并成一个虚拟媒体库。

导出的 MediaInfo Activity 可以分析用户交给它的文件、内容 URI 或 HTTP(S) 地址，但它不是播放器入口。

## 数据所有权与写入边界

| 后端 | Android 读取 | Android 写入 |
| --- | --- | --- |
| 共享 PostgreSQL | Emby 服务器与线路、反向路由、允许的运行时设置、播放历史 | 仅自动播放/历史同步，以及带 compare-and-set 保护的历史标记循环；标记与服务器生成的 `update_time` 原子更新 |
| Emby HTTP API | 媒体库、条目、播放信息和会话数据 | 自动播放会话上报；受身份校验保护的 Movie / Episode 收藏与已看状态操作 |
| 本机 Room v22 | 本机播放状态、Emby `(sourceKey, remoteId) → localId` 映射、搜索历史 | 仅本机数据 |
| 本机文件 | 加密的 PostgreSQL 连接配置、缓存、崩溃记录 | 仅应用私有目录或用户明确选择的 SAF 路径 |

普通 PostgreSQL 查询使用只读事务并回滚。Android 不运行共享 loemby PostgreSQL schema 迁移，也不提供任意 SQL 或共享配置 CRUD。提交结果不确定时通过读取核对，绝不盲目重放写入。

## 环境要求

### 使用要求

- Android 8.0（API 26）或更高版本；
- 一个结构兼容的 loemby PostgreSQL 数据库；
- Android 设备能访问该 PostgreSQL 数据库及其中引用的 Emby 地址；
- 如启用第三方同步，设备还需能访问相应服务。

PostgreSQL 默认 TLS 模式为 `verify-full`。只有在明确理解风险时才应选择较弱的 `require` 或 `disable`。连接参数使用项目的显式允许列表，未知参数会被拒绝。

### 构建要求

维护用开发镜像安装 JDK 21；工程编译出的 Java/Kotlin 字节码目标为 17。当前构建环境使用：

- Android SDK Platform 37；
- Android Build Tools 36.0.0；
- Gradle Wrapper 9.6；
- Android Gradle Plugin 9.4；
- Kotlin 2.3.20。

仓库中的 `dev-android-Dockerfile` 是维护环境的来源。容器内 Gradle Wrapper 没有可执行位，请通过 `sh` 调用。

## 构建与测试

以下命令是本仓库的标准验证方式：

```sh
# JVM 单元测试
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh /workspace/gradlew -p /workspace :app:testStandardDebugUnitTest

# arm64 debug APK
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh /workspace/gradlew -p /workspace :app:assembleStandardDebug -Pabi=arm64-v8a

# arm64 release APK
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh /workspace/gradlew -p /workspace :app:assembleStandardRelease -Pabi=arm64-v8a

# Android Lint
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh /workspace/gradlew -p /workspace :app:lintStandardDebug

# 需要已授权设备或模拟器的 instrumentation / Room 迁移测试
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh /workspace/gradlew -p /workspace :app:connectedStandardDebugAndroidTest
```

APK 位于 `app/build/outputs/apk/<flavor>/<buildType>/`。物理 arm64 设备优先使用 `-Pabi=arm64-v8a`，避免构建和传输不需要的 ABI。未指定 ABI 时会生成多 ABI 拆分包和 universal 包。

### Flavor

| Flavor | 自更新 |
| --- | --- |
| `standard` | 启用 |
| `playstore` | 禁用 |
| `fdroid` | 禁用 |

### Release 签名

Release 签名从仓库根目录、已被 Git 忽略的 `keystore.properties` 读取：

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

配置缺失时构建仍可完成，但产物未签名，不能直接安装。不要把签名材料或口令提交到仓库。

弹幕开放平台参数可从已忽略的 `local.properties` 注入 `BuildConfig`。这只能避免凭据进入 Git，不能让凭据在 APK 中保密；公开构建不应放入不适合分发到客户端的秘密。

## 安装

安装前必须确认实际设备、APK 变体和精确包名：

```sh
adb devices -l
adb -s <serial> shell pm path <application-id>
adb -s <serial> install -r <apk-path>
```

- 不要按应用显示名称、Kotlin namespace 或猜测的变体卸载应用；
- debug、preview、release 的包名不同；
- 覆盖安装要求包名和签名兼容；
- 卸载会删除该包名下的本机数据，包括加密的 PostgreSQL 连接配置。

## 安全与隐私说明

- PostgreSQL 连接文件由 Android Keystore AES-GCM 加密，且位于 `noBackupFilesDir`；
- 共享数据库字段可能包含 loemby 保存的明文 Emby 密码、Token 等敏感数据，Android 只在运行期读取所需字段；
- 只读共享配置页面只执行查询，不保存、删除或回写 PostgreSQL；
- 应用允许访问用户配置的 HTTP Emby / 反向路由地址，并信任系统 CA 和用户安装的 CA；
- `danger_accept_invalid_certs` 会进一步关闭 TLS 证书和主机名校验，仅应在明确知情时启用；
- 启用后，Trakt、Simkl、YamTrack、弹幕和 standard flavor 更新器会访问各自服务；
- 正向代理服务器设置及其凭据不会被 Android 读取或使用，反向路由仍会影响 Emby URL；
- 应用锁主要用于防止随手打开或儿童误操作，不是高强度加密边界；
- 崩溃报告保存在本机，不会自动上传；
- 播放器和视界流 Activity 不导出；MediaInfo 分析器会响应系统 SEND / VIEW 请求，但不会进入播放流程。

## 目录结构

```text
app/src/main/java/app/marlboroadvance/mpvex/
├── database/          # Room、DAO、迁移与数据仓库
├── di/                # Koin 依赖注入
├── domain/
│   ├── emby/          # 手写 OkHttp/kotlinx.serialization Emby 客户端
│   ├── postgres/      # 共享配置、只读查询和受限写入能力
│   ├── playbackstate/ # 本机播放状态
│   ├── danmaku/       # 弹幕请求与匹配
│   └── anime4k/       # Anime4K 配置
├── ui/
│   ├── browser/emby/  # Emby 浏览与 Classic 页面
│   ├── cine/          # Cine 呈现风格
│   ├── player/        # 内部播放器、视界流和本地 loopback 集成
│   ├── postgres/      # PostgreSQL 首次配置门
│   ├── preferences/   # 设置与只读共享配置
│   ├── lock/          # 应用锁
│   └── mediainfo/     # 独立 MediaInfo 分析器
└── widget/            # 继续观看小组件
```

`app/embyclient-reference/` 是未参与编译的 Emby Java SDK 参考源码。生产网络实现只使用 `domain/emby/` 下的手写客户端。

## 许可证与致谢

本项目使用 [Apache License 2.0](LICENSE)。感谢以下项目及其贡献者：

- loemby：共享配置、行为和桌面端兼容参考；
- CineIsle：Android Emby 客户端与界面基础；
- mpvEx、mpv-android、libmpv：播放器基础；
- Jetpack Compose、OkHttp、Kotlin Coroutines、kotlinx.serialization、Room、Koin、Coil、Telephoto、DanmakuFlameMaster、AboutLibraries 等依赖项目；
- Emby Java SDK：仅作为端点与数据模型参考，不参与生产编译。

Emby 是其权利人的商标；loembyA 不是 Emby 官方客户端。
