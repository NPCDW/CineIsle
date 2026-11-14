# Privacy Policy for loembyA

**Effective Date:** September 27, 2026  
**Application Name:** loembyA<br>
**Package Name:** `app.cineisle.player`<br>
**Upstream Historical Privacy Policy (mpvEx):** [https://marlboro-advance.github.io/mpvEx/privacy-policy.html](https://marlboro-advance.github.io/mpvEx/privacy-policy.html) — This upstream historical policy does not represent the privacy policy of this loembyA fork.<br>
**License:** Apache License 2.0  

---

## 🔒 Summary (TL;DR)

- **Zero Data Collection:** We do not collect, transmit, store, sell, or share any personal information.
- **No Ads & No Analytics:** The application contains zero advertising SDKs, zero analytics tracking (no Google/Firebase Analytics), and zero telemetry.
- **Local Processing:** All media discovery, playback history, thumbnails, and settings remain 100% on your device.
- **100% Open Source:** loembyA is free and open-source software. Its direct upstream is [CineIsle](https://github.com/nu1lkali/CineIsle), built on the earlier [mpvEx](https://github.com/marlboro-advance/mpvEx) project.

---

## 1. Introduction

loembyA ("the Application", "we", "us", or "our") is an open-source media player for Android built upon the `libmpv` playback engine. We are committed to maintaining the highest privacy standards. This Privacy Policy describes how loembyA handles information on Android devices.

Because loembyA operates as a client-side Emby media player utility, we do not operate user accounts, remote user databases, or data tracking services.

---

## 2. Information We Do NOT Collect

We do not collect or monitor any personal or device data:

- **Personal Information:** No names, email addresses, phone numbers, or physical addresses.
- **Device Identifiers:** No IMEI, Android ID, MAC address, advertising ID, or hardware fingerprints.
- **Usage Telemetry:** No analytics libraries, event trackers, or usage heatmaps.
- **Media Content:** We never upload, inspect, or transfer your video files, audio files, photos, or documents to external servers.

---

## 3. Android Permissions & Usage

To function properly as a media player, loembyA requests the following Android permissions. Each permission is used strictly for on-device functionality:

| Permission | Category | Purpose |
| :--- | :--- | :--- |
| `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` | Storage | Legacy storage declarations do not enable local audio/video playback. Independent MediaInfo analysis remains available; subtitles, fonts and configuration use system document access. |
| `MANAGE_EXTERNAL_STORAGE` *(Standard flavor only)* | All Files Access | This legacy declaration does not enable file browsing or file management. Subtitle, font and configuration access uses the system document picker. *Not present in Google Play Scoped Storage builds.* |
| `INTERNET` | Network | Required to retrieve and play Emby media, including associated HTTP(S) streams, and check for GitHub release updates (standard build only). No arbitrary "Open URL" or independent SMB/WebDAV/FTP playback is provided. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background Playback | Allows uninterrupted audio and video playback when navigating outside the app, using Picture-in-Picture (PiP), or turning off the screen. |
| `POST_NOTIFICATIONS` | Notifications | Displays ongoing playback controls (play, pause, seek, track info) on Android 13+. |
| `REQUEST_INSTALL_PACKAGES` *(Standard flavor only)* | Updates | Allows self-updating the APK when a user initiates an update from GitHub releases. Not used for third-party APKs. |

You may grant or revoke permissions at any time via Android System Settings (**Settings > Apps > loembyA > Permissions**).

---

## 4. Local Data Storage & User Data Control

All app-related data is stored locally on your device:

- **Playback History & Positions:** Saved in a local SQLite/Room database on your device to let you resume playback where you left off.
- **App Preferences:** Playback speed, gestures, subtitle preferences, and mpv configuration scripts are saved in Android DataStore/SharedPreferences.
- **Data Deletion:** You can clear your playback history at any time in the app settings, or clear all application data via Android System Settings (**Settings > Apps > loembyA > Storage > Clear Data**). Uninstalling the app permanently deletes all local data.

---

## 5. Network Streaming & Third-Party Services

When you stream media from an Emby server or its associated HTTP(S) media source:
- loembyA establishes a direct network connection from your device to the specified server.
- No intermediary proxy or logging servers are operated by loembyA.
- The connection is governed by the privacy policy of the respective streaming host.
- Any network credentials entered are stored encrypted/locally on your device and sent only to the host you specified.

---

## 6. Crash Reporting

loembyA provides an on-device crash dialog (`CrashActivity`) for diagnosing unexpected crashes. Crash logs are **never** transmitted automatically. If you choose to submit a bug report to our GitHub Issues page, you do so manually and can review or sanitize the log contents before submitting.

---

## 7. Children's Privacy

loembyA does not collect personal information from any user, including children under the age of 13. It complies with COPPA and GDPR regulations.

---

## 8. Open Source & Transparency

loembyA is licensed under the **Apache License 2.0**. We gratefully acknowledge the projects it is derived from; these upstream repositories are not the loembyA repository:
- Direct upstream: [CineIsle](https://github.com/nu1lkali/CineIsle)
- Earlier player foundation: [mpvEx](https://github.com/marlboro-advance/mpvEx)

---

## 9. Contact Us

If you have any questions or feedback regarding this Privacy Policy, use the issue tracker of the loembyA repository from which you obtained this application. The upstream repositories linked above document the project's origins and are not contact channels for this fork.
