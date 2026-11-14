## Context
按你的要求，删除持续失败的 4 项媒体源偏好测试，以及依赖 Robolectric、会在初始化时下载 Android 运行时的测试。这里不是修复失败，而是明确减少测试覆盖；生产行为保持不变。

## 修改
1. 在 `app/src/test/java/app/marlboroadvance/mpvex/domain/emby/MediaSourcePreferenceTest.kt` 中，只删除这 4 个方法，保留其余 8 项：
   - 最高分辨率按像素总数而不是高度
   - 最高码率在缺失值存在时仍然选最高
   - 码率缺失时回退用视频流自身的码率
   - 全部字段缺失时不瞎选
2. 删除 8 个 Robolectric 测试类，共 **48 个测试方法**：
   - `EmbySessionRestoreTest`、`PostgresConfigStoreTest`、`LegacyCastControlsTest`
   - `ServerHomeEntryStoresTest`、`ServerNavigationEntryStoresTest`
   - `EmbyPlaybackInputTest`、`GsyPlaybackAdmissionTest`、`PlayerHandoffHeadersTest`
   不使用 `@Ignore` 或运行过滤来隐藏它们，也不修改被测代码。
3. 清理 `app/build.gradle.kts` 的 Robolectric 依赖和 `isIncludeAndroidResources`，保留 `isReturnDefaultValues`、其他 JVM 测试依赖、桌面 zstd JNI 和设备测试配置。
4. 移除 `gradle/libs.versions.toml` 中的 Robolectric 版本与依赖别名，修正相关说明；README 的测试技术栈同步去除 Robolectric。

## 验证
- 搜索确认测试源码和构建配置不再引用 Robolectric，检查改动只删除指定覆盖而未修改生产代码。
- 重新运行完整 `:app:testStandardDebugUnitTest`，以及单 ABI `:app:assembleStandardDebug`，按实际结果报告，不能预先保证全绿。
- 按你此前选择，不连接设备、不做实机测试；不提交、不推送。