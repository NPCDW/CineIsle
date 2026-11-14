## Context
当前剧／季／集（以及共用组件中的电影）已经共用 `SeriesHero`，顶部图实际固定为 **336dp**，缺图时也会保留这块区域；但 Logo 仍完全位于图片内并带局部 Haze 磨砂，Hero 底部也没有渐变到正文背景。与此同时，AndroidX Palette 会把图片鲜艳色直接铺成整页背景，实际效果不理想。单集／电影标题下仍显示评分，版本详情卡片使用上下键值且高度不一，页面右上还保留刷新按钮。

目标是保留当前顶部图高度不变，在剧／季／集／电影统一调整 Logo 与渐变关系；彻底移除鲜艳色整页背景；并把 Movie/Episode 共用详情页的版本摘要与详情卡片改为更紧凑、随版本切换且等高的布局。实施时保留当前工作区已有改动，不整文件回退，不处理无关的脏文件，也不提交或推送。

## 实施方案

### 1. 固定 Hero 高度并重排 Logo／标题
- 在 `EmbySeriesDetailContent.kt` 保留当前 **336dp** 顶部图片高度，图片存在与否均固定占位；剧、季、集、电影继续复用同一 Hero。
- 将 96dp Logo 区改为以图片底边为中心：上半 48dp 覆盖图片、下半 48dp 落在正文背景；标题随之下移，确保不与 Logo 重叠。
- 去掉 Logo 区的 `hazeSource` / `hazeEffect`、模糊、噪点和 fallback tint；Logo 容器不再绘制磨砂底，保持透明。Haze 依赖仍保留，因为 Cine 其它玻璃组件仍在使用。
- 在图片底部叠加垂直线性渐变：从透明过渡到 `MaterialTheme.colorScheme.surface`，终点与正文区背景完全一致；顶部状态栏现有黑色渐变保留。缺图时仍渲染同样高度和结构。
- 同步修订 `DetailRefinementTest.kt`，固定检查：336dp 图片区、Logo 半跨边界、透明 Logo 容器、底部渐变及无 Haze。

### 2. 移除 AndroidX Palette 鲜艳色整页背景
- 在剧、季、Movie/Episode 共用页面去掉 `EmbyDetailTheme` 包装，直接继承当前 Classic 或 Cine 的 Material 主题；页面背景与 Hero 底部渐变统一使用继承的 `surface`。
- 在 `EmbyPalette.kt` 只删除 AndroidX Palette 的鲜艳色数据结构、缓存、并发提取和 `rememberVibrantColor`；保留旧 fallback 页面仍使用的自研 `rememberDominantColor` 与量化逻辑。
- 在 `EmbyDetailTheme.kt` 保留实际 Hero 仍需要的 Movie/Episode 图片候选 `mediaArtworkUrls`，删除仅服务鲜艳背景的主题派生、剧集颜色候选和无生产调用的辅助函数。
- 从 `CineDetailScreen.kt` 删除 Series 的二次鲜艳色提取和整页配色分支；非共享 fallback 页继续使用现有海报强调色。删除 `CineColors.kt` 中不再有调用的 `cineColorsForDetailBackground`，保留 Cine home／fallback detail 的既有渐变和配色逻辑。
- 从 `app/build.gradle.kts` 与 `gradle/libs.versions.toml` 移除 `androidx.palette:palette`；删除只验证该功能的 `EmbyVibrantColorCacheTest.kt`、`CineDetailBackgroundTest.kt`，并将仍有价值的图片候选顺序测试迁移到保留的 helper 测试中。

### 3. Movie/Episode 标题下改为所选版本参数
- 在 `EmbyEpisodeDetailPresentation.kt` 增加纯 Kotlin 的“当前版本摘要”构造，复用 `EmbyMediaSource.Size`、`Bitrate` 以及首个 Video stream 的 `Width×Height`；格式与 `EmbyMediaInfo.kt` 的现有文件大小／码率规则保持一致，避免两处显示不一致。
- `EmbyEpisodeDetailContent.kt` 的标题下一行移除社区评分；保留日期和所选版本时长，并加入所选版本的 **大小／总码率／分辨率**，只显示服务端实际提供的非空字段。
- 摘要直接依赖当前 `selection` 对应的 `source`，版本切换后即时重组；Movie 与 Episode 按用户确认同步生效。
- 为缺字段、单版本、多版本切换和具体格式增加 JVM 单测。

### 4. 去掉刷新按钮并接入紧凑等高版本卡片
- 从 `EmbyEpisodeDetailContent.kt` 删除 Movie/Episode 顶部刷新按钮及 `refreshBusy` 状态、刷新协程和相关图标／Toast 分支；返回、收藏、已看与播放忙碌状态保持正常。
- 用已有 `rememberDetailMediaInfoHeight` + `DetailMediaInfoGroup` 替换页面内重复的卡片实现：每行统一为 `Key: Value`，保留长按复制；同一页面所有横向卡片按内容测量后使用共同高度。
- 保留目前“每个版本一个分组、基本信息卡 + 视频／音频／字幕卡”的信息结构和完整值，不截断路径等长文本。
- 校正等高组件的边界：空分组、字体／密度变化、长文本换行和 key 稳定性，并更新源码结构测试及媒体信息分组测试。

## 关键文件
- Hero 与共用页面：`app/src/main/java/app/marlboroadvance/mpvex/ui/browser/emby/components/EmbySeriesDetailContent.kt`、`EmbySeasonDetailContent.kt`、`EmbyEpisodeDetailContent.kt`。
- 版本摘要与卡片：`EmbyEpisodeDetailPresentation.kt`、`EmbyDetailMediaInfo.kt`、`domain/emby/EmbyMediaInfo.kt`。
- Palette 清理：`EmbyPalette.kt`、`EmbyDetailTheme.kt`、`ui/cine/detail/CineDetailScreen.kt`、`ui/cine/tokens/CineColors.kt`、`app/build.gradle.kts`、`gradle/libs.versions.toml`。
- 测试：`DetailRefinementTest.kt`、`EmbyEpisodeDetailPresentationTest.kt`、图片候选测试，以及删除的 Palette 专属测试。

## 验证
1. 静态检查零残留：`androidx.palette`、`rememberVibrantColor`、`EmbyVibrant*`、`EmbyDetailTheme`、`cineColorsForDetailBackground`、共用详情刷新状态／图标均无引用；确认 Haze 仍被 Cine 其它组件正常使用。
2. 运行详情页、媒体信息、Series/Season/Episode presentation、Classic/Cine 路由等定向 JVM 测试，再运行 `:app:testStandardDebugUnitTest`。
3. 运行 `:app:compileStandardDebugKotlin`、`git diff --check`、Palette 依赖核验、`:app:lintStandardDebug`，并构建单 ABI `:app:assembleStandardDebug -Pabi=arm64-v8a`。
4. 若环境具备可启动的应用／设备，人工检查 Classic 与 Cine、深浅主题，以及剧／季／集／电影的有图、无图、无 Logo、长 Logo、多版本切换场景：顶图均为 336dp，Logo 48dp/48dp 跨边界，渐变无接缝，标题不重叠，页面不再随图片变色，版本摘要随选择变化，所有横向卡片等高。若无设备则如实说明视觉验收未执行。