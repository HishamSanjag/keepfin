# Third-party notices

## Origin

Keepfin (formerly Yoinks Studio) was originally inspired by [Yoinks](https://github.com/pablostanley/yoinks) by Pablo Stanley (MIT). Versions up to 0.3.12 vendored part of its engine; from 0.4.0 Keepfin uses its own independent engine and contains no Yoinks source. Earlier derived code remains in the repository history under its original MIT notice.

## Download engines and tools

- **yt-dlp** (https://github.com/yt-dlp/yt-dlp) — Unlicense (public domain). Desktop (shown in the app as the "Download engine") downloads, verifies (SHA-256 against the release `SHA2-256SUMS`) and updates the standalone executable; packaged builds ship a verified copy. The PyInstaller executable bundles Python and other components under their own licenses (see the yt-dlp release notes and `THIRD_PARTY_LICENSES` in its repository).
- **gallery-dl** (https://github.com/mikf/gallery-dl) — GNU GPL v2. Desktop invokes the unmodified standalone executable as a separate program for image galleries. Corresponding source is available at the upstream repository for the exact release tag that is downloaded or shipped. Android also ships gallery-dl 1.32.19 unmodified, as a Python package run by the interpreter bundled with the download engine, together with its pure-Python dependencies: requests 2.34.2 (Apache-2.0), urllib3 2.8.0 (MIT), idna 3.20 (BSD-3-Clause), certifi 2026.10.23 (MPL-2.0; source https://github.com/certifi/python-certifi) and charset-normalizer 3.5.2 (MIT). The pinned wheels are fetched and hash-checked by `scripts/build/fetch-gallery-python.mjs`; the full license texts ship in the APK as `assets/third-party/<name>-<version>-LICENSE.txt`.
- **ffmpeg** — via ffmpeg-static (desktop) and youtubedl-android's ffmpeg artifact (Android); GPL/LGPL depending on build configuration. See the respective upstream projects for source.
- **aria2** (https://github.com/aria2/aria2) — GNU GPL v2+, shipped on Android through youtubedl-android's aria2c artifact for multi-connection transfers.
- **Music matching** — Spotify, Apple Music and Deezer links are resolved from their public metadata pages/APIs only; no audio is retrieved from those services. Audio comes from the matched YouTube Music recording through yt-dlp.

## Application dependencies

React, Electron, ffmpeg-static, and other npm dependencies retain their own licenses in their packages. Desktop installs or uses the standalone yt-dlp executable; its distribution includes its own notices and dependencies. The ffmpeg binary has its own license and source requirements, distinct from the npm wrapper.

Android bundles youtubedl-android and its ffmpeg and aria2c artifacts at 0.18.1, plus their native runtime dependencies. See https://github.com/JunkFood02/youtubedl-android for upstream licensing and source. Preserve their GPL and other bundled component notices and applicable source distribution obligations when preparing public binary releases. This file is an attribution inventory, not a completed corresponding-source release bundle.

Android's interface uses Jetpack Compose and Material 3 (Apache 2.0), Coil (Apache 2.0), kotlinx.coroutines (Apache 2.0), AndroidX WorkManager (Apache 2.0) and Compose Reorderable 3.1.0 by Calvin Liang (Apache 2.0, https://github.com/Calvin-LL/Reorderable).

The Keepfin folder, devices and cloud accounts on Android use AndroidX SQLite bundled 2.5.2 (Apache 2.0; it bundles SQLite, public domain, https://sqlite.org/copyright.html), AndroidX Paging 3.3.6 (Apache 2.0), OkHttp 4.12.0 (Apache 2.0, https://github.com/square/okhttp), ZXing core 3.5.3 and ZXing Android Embedded 4.3.0 (Apache 2.0, https://github.com/zxing/zxing, https://github.com/journeyapps/zxing-android-embedded), AppAuth for Android 0.11.1 (Apache 2.0, https://github.com/openid/AppAuth-Android) and Google Play services Auth 21.3.0 (Android Software Development Kit License, https://developer.android.com/studio/terms).

The desktop devices feature uses bonjour-service (MIT), ws (MIT) and qrcode (MIT); their license files remain with the packaged npm dependencies.

Shared core (`core/`, Kotlin Multiplatform; decision logic used by both apps): the desktop app ships the JavaScript build of the Kotlin standard library (Kotlin 2.4.20, JetBrains, Apache 2.0, https://github.com/JetBrains/kotlin), bundled into the main process. The Windows package includes the Apache 2.0 text as `resources/licenses/Apache-2.0.txt`; on Android it ships as `markwon-4.6.2-LICENSE.txt` in `android/app/src/main/assets/third-party/`.

Fonts: the desktop interface bundles Manrope (headings) and Inter (text), both licensed under the SIL Open Font License 1.1 (Manrope: Copyright 2018 The Manrope Project Authors, https://github.com/googlefonts/manrope; Inter: Copyright 2020 The Inter Project Authors, https://github.com/rsms/inter). The fonts are shipped unmodified as variable TTF files inside the renderer assets, and the full licence texts ship as `resources/licenses/Manrope-OFL.txt` and `resources/licenses/Inter-OFL.txt` in the Windows package. Windows system fonts are the fallback when a bundled font cannot load.

### Release notes rendering

Android uses [Markwon core 4.6.2](https://github.com/noties/Markwon/tree/v4.6.2), licensed under Apache 2.0, with [commonmark-java 0.13.0](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.13.0), licensed under BSD 2-Clause. Their unchanged upstream license texts ship in `android/app/src/main/assets/third-party/` and therefore in the APK. The native UI uses only core Markdown spans, with HTTPS links and no HTML/image plugins.

Desktop uses react-markdown 10, rehype-raw 7 and rehype-sanitize 6, licensed under MIT; their license files remain with the packaged npm dependencies. Release HTML is sanitized before rendering; external images and scripts are excluded.

Before public binary releases, complete the notices/source bundle for the exact shipped dependency versions. This repository's upstream MIT notice does not relicense third-party binaries.

## In-app display

The apps present these components generically ("Download engine", "Gallery engine", "Media processor", "Compatibility runtime"). The apps and release pages never name these components. License texts ship only as plain files inside each package: `resources/licenses/` in the Windows install (LICENSE and this file) and `assets/third-party/` in the APK (`NOTICES.txt` plus bundled license texts). Keep those files complete for every shipped component.
