# Tech stack

What Keepfin is built with, what each piece does here, and where it lives. Versions are the ones in `package.json`, `android/app/build.gradle.kts` and `core/build.gradle.kts`; those files are the source of truth when this page falls behind.

Keepfin is two native apps that share one decision core:

```
                 ┌──────────────────────────── core/ (Kotlin Multiplatform) ────────────────────────────┐
                 │ formats, download choice, music matching, lyrics, spaces, after-download, sync rules │
                 └───────────────┬───────────────────────────────────────────────────┬──────────────────┘
                     compiled to JavaScript                                    compiled for the JVM
                                 │                                                   │
   ┌──────────── Windows app (gui/) ─────────────┐        ┌──────────── Android app (android/) ────────────┐
   │ React window  ⇄  typed IPC  ⇄  Electron main │        │ Jetpack Compose UI  ⇄  ViewModel  ⇄  engine    │
   │                    download engine (Node)    │        │            youtubedl-android, Media3, WorkManager │
   └──────────────────────────────────────────────┘        └────────────────────────────────────────────────┘
```

## Windows app (`gui/`)

| Technology | Version | What it does in Keepfin | Docs |
|---|---|---|---|
| [Electron](https://www.electronjs.org/docs/latest) | 44 | The desktop shell. The main process (`gui/desktop/`) owns downloads, files, the library and updates; the window is sandboxed with context isolation and talks to it only through one typed IPC channel. | [Process model](https://www.electronjs.org/docs/latest/tutorial/process-model) |
| [React](https://react.dev/) | 19 | The window (`gui/web/`). An external store (`gui/web/store.ts`) with per-job subscriptions keeps progress updates from re-rendering the whole list. | [react.dev/learn](https://react.dev/learn) |
| [TypeScript](https://www.typescriptlang.org/docs/) | 7 | Everything on Windows, including the contract between window and main process (`gui/shared/contracts.ts`). | |
| [Vite](https://vite.dev/guide/) | 8 | Builds the window and serves the browser preview (`npm run dev`). Config: `gui/vite.config.ts`. | |
| [tsup](https://tsup.egoist.dev/) | 8 | Bundles the Electron main process and preload. Config: `gui/tsup.config.ts`. | |
| [electron-builder](https://www.electron.build/) | 26 | Packages the NSIS installer, the portable EXE and the MSI. Config: `electron-builder.yml`. | [Windows targets](https://www.electron.build/win) |
| [electron-updater](https://www.electron.build/auto-update) | 6 | Silent in-place updates from the public releases feed. | |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | updated daily | The download engine. Kept in `<userData>/tools`, SHA-256 verified, run with Electron-as-Node for YouTube's JavaScript challenges. | |
| [gallery-dl](https://github.com/mikf/gallery-dl) | updated daily | The gallery engine for image posts. | |
| [ffmpeg](https://ffmpeg.org/documentation.html) (via [ffmpeg-static](https://github.com/eugeneware/ffmpeg-static)) | 5.x package | Merging, converting, cutting, cover art and video thumbnails. | |
| [media-chrome](https://www.media-chrome.org/) | | The built-in video player controls. | |
| [music-metadata](https://github.com/Borewit/music-metadata) | | Reads tags and cover art when files are imported into the library. | |
| [TanStack Virtual](https://tanstack.com/virtual/latest) | | Virtualised long lists (library, collections). | |
| [bonjour-service](https://github.com/onlxltd/bonjour-service) and [ws](https://github.com/websockets/ws) | | Finding paired devices on the network and talking to them. | |
| [react-markdown](https://github.com/remarkjs/react-markdown) | | Release notes and the guide inside the app. | |

## Android app (`android/`)

| Technology | Version | What it does in Keepfin | Docs |
|---|---|---|---|
| [Kotlin](https://kotlinlang.org/docs/home.html) | 2.4 | The whole app. Coroutines and `StateFlow` carry engine state to the screens. | [Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) |
| [Jetpack Compose](https://developer.android.com/develop/ui/compose/documentation) + [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3) | BOM 2026.03.00 | Every screen (`android/.../ui/`). The BOM is pinned for AGP 8.13 / compileSdk 36. | [Lists](https://developer.android.com/develop/ui/compose/lists) |
| [Android Gradle Plugin](https://developer.android.com/build) | 8.13 | Builds the APK; compileSdk and targetSdk 36, minSdk 26 (Android 8). | |
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) | 0.18.1 | The download engine on the phone, with its ffmpeg and aria2c packages (multi-connection transfers are opt-in). | |
| [Media3 / ExoPlayer](https://developer.android.com/media/media3) | 1.11 | Playback, the background player service, the notification and Android Auto (`PlaybackService.kt`). | [MediaLibraryService](https://developer.android.com/media/media3/session/background-playback) |
| [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent) | 2.12 | Scheduled work such as following channels and copies. | |
| [Coil 3](https://coil-kt.github.io/coil/) | 3.3 | Thumbnails and video frames in lists (`ui/components/`). | |
| [OkHttp](https://square.github.io/okhttp/) | 4.12 | Network calls (music matching, lyrics, cloud accounts). | |
| [AndroidX SQLite (bundled)](https://developer.android.com/jetpack/androidx/releases/sqlite) | 2.5 | The library's own database inside the Keepfin folder. | |
| [Paging 3](https://developer.android.com/topic/libraries/architecture/paging/v3-overview) | 3.3 | Large library lists. | |
| [ZXing](https://github.com/journeyapps/zxing-android-embedded) | 4.3 | QR codes for pairing devices. | |
| [Markwon](https://noties.io/Markwon/) | 4.6 | Release notes on the phone. | |

## Shared core (`core/`)

| Technology | What it does in Keepfin | Docs |
|---|---|---|
| [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) | Decision logic written once and compiled to two targets: `jvm` (Android uses it through `includeBuild("../core")`) and `js` (an ES module in `build/core-js/`, imported only by the adapters in `gui/shared/core/`). It does no I/O. | [Portability rules](../core/README.md) |
| Shared fixtures (`fixtures/`) | JSON test cases that run on the JVM, as JavaScript, in the Android test runner and in the Windows tests, so both apps prove the same behaviour. | [fixtures/engine](../fixtures/engine/README.md) |

## Testing and tooling

| Tool | Used for |
|---|---|
| Node's built-in test runner, run through [tsx](https://tsx.is/) by `scripts/test/run-tests.mjs` | Unit tests for `gui/shared` and `gui/desktop` (`npm run gui:test`) |
| [Playwright](https://playwright.dev/docs/intro) | Windows UI regression suites with a fake engine (`npm run gui:ui-test`) and the real-workflow test |
| JUnit + [Compose UI tests](https://developer.android.com/develop/ui/compose/testing) + UI Automator | Android unit tests (`:app:testDebugUnitTest`) and on-device UI tests (`:app:connectedDebugAndroidTest`) |
| `scripts/check/` | Repository guards in `npm run check`: layer boundaries, banned APIs, links in docs, file sizes |
| [resvg](https://github.com/RazrFalcon/resvg) (`@resvg/resvg-js`) | Renders every app icon and brand picture from `assets/` (`npm run icons:build`) |
| [GitHub Actions](https://docs.github.com/en/actions) | Checks on every push, and the combined Windows + Android release on `v*` tags |
