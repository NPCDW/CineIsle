## Context
统一媒体卡片入口，去掉电视剧的旧季列表中间页，并按最新要求精简单集页。Classic/Cine 都生效；不改变显式播放操作及播放器上下集。

## 修改方案
1. **电视剧进入新详情页**
   - 修改 `EmbyLibraryScreen.kt` 的 Series 点击分支，直接打开 `EmbyDetailScreen`，不再创建仅含 Season 的媒体库页。
2. **所有单集卡片点击先看详情**
   - 统一季页、Cine 首页续播及“全部”网格、旧历史续播行、发现页、合集内容和相关卡片的 Episode 点击行为。
   - 复用已有详情路由和服务器绑定；新增小型卡片分流辅助函数，Episode 打开详情，其余媒体保留原行为。
   - 不修改 `EmbyViewModel.play()` 或通用播放控制器，避免误伤详情播放按钮、菜单播放和播放器切集。
3. **单集页精简**
   - 删除可见直链开关及其保存状态；正常播放不启用该开关，仅三点菜单的“直链播放”传入直链参数。
   - 删除章节区、页面章节状态及详情专用章节请求；播放器既有章节功能保留。
4. **选择器同行及禁用**
   - 版本/视频/音频/字幕均改为“左侧标签 + 右侧选择项”的同一行。
   - 仅一个真实选项时禁用，无轨道的自动占位也禁用；不因附加“关闭”项误判为有多个可选轨道。
   - 四选择的自动/手动快照和实际播放传递保持不变。
5. **版本信息全部横滑**
   - 每个版本分组外层只显示一次版本名称。
   - 基本信息移入横滑列表第一卡，显示“版本 0”；随后是视频、音频、字幕详情卡。没有轨道时也保留版本基本信息卡。

## 关键文件
- `ui/browser/emby/EmbyLibraryScreen.kt`：Series路由。
- `ui/browser/emby/components/EmbySeasonDetailContent.kt`：单集卡片导航。
- `ui/browser/emby/EmbyServerNavigation.kt` 或相邻共用导航文件：Episode卡片分流。
- `ui/cine/CineHomeScreen.kt`、`CineOverlays.kt`、`detail/CineDetailScreen.kt`，以及 `EmbyHistoryScreen.kt`、`discover/EmbyDiscoverScreen.kt`：沿用共用分流规则。
- `components/EmbyEpisodeDetailContent.kt`、`EmbyEpisodeDetailPresentation.kt`：删除直链开关/章节、同行选择器、横滑基本信息。
- `EmbyViewModel.kt`、`EmbyRepository.kt`：只清理不再使用的详情专用章节方法，不删除播放器章节接口。

## 验证
- 补充卡片分流测试：Episode只进入详情、其他媒体保持原行为、显式播放不受影响。
- 检查单轨/多轨/无轨的选择器禁用规则，基本信息卡索引0，普通播放与菜单直链参数。
- 运行全量 JVM 单元测试并构建 arm64 Debug APK；检查签名、架构及格式，更新压缩包。
- 沿用此前跳过实机测试的选择，未做的手机视觉/交互验收会明确说明。
- 不提交、不推送。