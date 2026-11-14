# loembyA repository instructions

## Scope and source of truth

- These instructions apply to the entire repository.
- loembyA is a forked Android companion to the loemby desktop Emby player, not a greenfield app. Preserve compatibility with the relevant loemby behavior while adapting it to Android.
- Current source and build configuration are authoritative. Do not revive behavior from old comments, schemas, reference code, or repository history without confirming it still exists.
- Keep this file limited to durable project rules. `AGENTS.md` must not become a session journal: never append delivery notes, dates, APK hashes or sizes, device IDs, test counts, transient failures, or task-by-task memory.

## Project shape and identifiers

- This is one Gradle application module, `:app`, written primarily in Kotlin with Jetpack Compose. Classic and Cine are two presentation styles in the same app; they share domain, data, navigation contracts, and player behavior. Do not fork business logic by UI style.
- The Kotlin/Android namespace is `app.marlboroadvance.mpvex`. The base application ID is `com.github.npcdw.loembya`; debug is `com.github.npcdw.loembya.debug`, preview is `com.github.npcdw.loembya.preview`, and release uses the base ID.
- Namespace, source package, application ID, Room schema identity, storage keys, and signing identity are different concerns. Never infer an install package from the namespace or migrate internal identities merely because an application ID changes.

## Startup contract

- Preserve `App.onCreate()` ordering: boot markers and approved legacy preference cleanup precede Koin construction; the loopback playback server starts only after DI is ready; crash handling, caches/native thumbnail initialization, and decoder probing follow their existing guarded paths.
- Preserve the MainActivity composition gate: `MpvexTheme`/surface -> `AppLockGate` -> activity-scoped PostgreSQL setup and validation -> `Navigator`. Do not compose media navigation or start shared-data foreground refresh before the PostgreSQL gate passes.
- The branded splash is an overlay on the current gate, not a bypass. `App.markBootOk()` marks the first rendered composition, not successful entry into the media home.

## Data ownership and mutation boundaries

- Room is local and currently version 22. Its active role is limited to local playback state, `(sourceKey, remoteId) -> localId` Emby identity mapping, and search history. It is not the source of truth for shared Emby server configuration or PostgreSQL playback history.
- Ordinary PostgreSQL access is read-only, source-generation checked, and rolled back. Reads may consume the existing typed shared configuration needed at runtime, but must not expose a generic shared-data mutation path.
- Shared PostgreSQL writes are allowlisted to exactly these typed capabilities:
  1. automatic playback/history synchronization through `PostgresAutomaticPlaybackWriter`, including its existing guarded `play_history` insert/update, pinned-only series pin handoff, accumulated played duration, and `emby_server.last_playback_time` behavior; and
  2. the compare-and-set mark cycle through `JdbcPostgresPlaybackHistoryMarkWriter`, which may atomically change only `play_history.pinned` and `play_history.update_time` for the matched history row and return that database timestamp.
- Do not add arbitrary SQL callbacks or shared CRUD. In particular, Android must not create, edit, or delete shared servers, lines, proxies, `global_config`, credentials/tokens, or other shared configuration, and must not broaden the allowlist under the label of synchronization. Unknown commit outcomes are reconciled by reads, never blind write replay.
- Emby HTTP mutations are a separate backend and are not PostgreSQL writes. Keep existing automatic Emby playback/session reporting and guarded Movie/Episode favorite and played-state operations behind the typed handwritten Emby client, with its identity checks, no write retry/redirect, and readback rules. Do not use the PostgreSQL policy to silently remove them or use Emby mutations to bypass the PostgreSQL allowlist.

## Emby and player boundaries

- Production Emby networking is the handwritten OkHttp/kotlinx.serialization implementation under `app/src/main/java/app/marlboroadvance/mpvex/domain/emby/`. `app/embyclient-reference/` is an uncompiled reference SDK only; do not import it into production, add it as a source set, or assume its generated API behavior is implemented.
- The video players are internal Emby-only consumers. Accept only the existing typed `ACTION_VIEW` handoff bound to a valid Emby session, item IDs, and HTTP(S) media URLs. Do not restore generic file/content/share/deep-link playback. The exported MediaInfo analyzer is not a playback entry point.
- Removed product boundaries stay removed: local/network file browsing and SMB/FTP/WebDAV/AList clients, media downloads/offline storage, live TV, standalone Emby playlists, DLNA/casting, and remote ISO playback/proxying. Do not reintroduce them from legacy Room migrations, stale comments, or the reference SDK. Internal Emby queues, ordinary HTTP/HLS playback, embedded/external subtitle handling through approved paths, and automatic reporting remain supported.

## Reference repositories and development image

- Use only the local reference repositories at `/root/loemby`, `/root/uosc`, and `/root/Yamtrack`. Never web-fetch their files or substitute hosted copies.
- Clone a reference only when its exact directory is missing, and only from the corresponding CNB URL:
  - `git clone https://cnb.cool/my-codespace/loemby /root/loemby`
  - `git clone https://cnb.cool/my-codespace/uosc /root/uosc`
  - `git clone https://cnb.cool/my-codespace/Yamtrack /root/Yamtrack`
- Treat reference repositories as read-only unless the user explicitly requests changes to them.
- The development container is defined by `/workspace/dev-android-Dockerfile`. Any required JDK, Android SDK/build-tools, system package, repository-mirror, or other environment change must be reflected there in the same change; do not leave a one-off container fix undocumented in the image definition.

## Code, migrations, and secrets

- Follow `.editorconfig`: Kotlin/KTS use UTF-8, LF, 2-space indentation, a 120-column limit, and trailing commas where supported. Match established Kotlin/Compose patterns and keep Classic/Cine behavior aligned through shared business code.
- Every Room schema change must bump the database version, add an explicit migration, register it in `DatabaseModule`, export the canonical schema under `app/schemas/app.marlboroadvance.mpvex.database.MpvExDatabase/`, and add/update migration tests. Never rely on destructive fallback to hide a missing migration. Android does not own or run migrations for the shared loemby PostgreSQL schema.
- Never put passwords, database URLs, Emby tokens, webhook URLs/tokens, API secrets, signing material, or other credentials in source, tests, fixtures, logs, reports, APK-visible constants, or this file. Redact them from diagnostics and command output.
- PostgreSQL connection configuration belongs only in the Android-Keystore AES-GCM encrypted `noBackupFilesDir/postgres-config.json`; do not move it to SharedPreferences, SavedState, settings export, or Room. Keep signing/API material in ignored local files or environment variables (`keystore.properties`, `local.properties`, or the established CI secrets), and never print their contents.

## Canonical verification commands

The wrapper is tracked without its executable bit in this container. Use these exact commands from any working directory; do not add `--max-workers` overrides:

```sh
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk sh /workspace/gradlew -p /workspace :app:testStandardDebugUnitTest
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk sh /workspace/gradlew -p /workspace :app:assembleStandardDebug -Pabi=arm64-v8a
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk sh /workspace/gradlew -p /workspace :app:assembleStandardRelease -Pabi=arm64-v8a
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk sh /workspace/gradlew -p /workspace :app:lintStandardDebug
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk sh /workspace/gradlew -p /workspace :app:connectedStandardDebugAndroidTest
```

- Prefer the arm64 single-ABI APK for a physical arm64 device because it is smaller to build and transfer. Use another single ABI only when the confirmed target requires it; do not build or transfer a universal/multi-ABI APK unless requested.
- Before any device install, run `adb devices -l`, select and confirm the target, determine the APK's actual application ID, and check that exact package on that exact device (for example, `adb -s <serial> shell pm path <application-id>`). Only after that check may an existing same-application-ID package be uninstalled/replaced and the new APK installed. Never uninstall by app label, namespace, or an assumed variant.
- If ADB reports no usable device when device validation is requested, do not silently skip it. Ask the user either to reconnect/authorize the device and retry detection, or to explicitly skip device testing. Report device validation as skipped only after that explicit choice.
