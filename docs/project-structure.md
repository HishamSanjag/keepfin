# Project structure

Where everything lives, and the rules that keep the folders apart. For who owns which source in more detail, see [AGENTS.md](../AGENTS.md); for the technologies, see [tech stack](tech-stack.md).

## Top level

```
keepfin/
├── android/          Android app: native Kotlin + Jetpack Compose (one Gradle module, :app)
├── gui/              Windows app: Electron main process, React window, shared TypeScript
├── core/             Shared core: Kotlin Multiplatform, compiled for the JVM and to JavaScript
├── fixtures/         Shared test cases (JSON) that both apps and the core must pass
├── assets/           Icon and brand sources (SVG/PNG), installer art, support images
├── resources/        Files packaged with the Windows app (the app icons)
├── content/          In-app text both apps show: the guide and "how downloads work"
├── scripts/          Build, check, test, capture and release scripts (Node)
├── docs/             The user guide, tech stack, this page, release notes and screenshots
├── licenses/         Third-party license texts
├── .github/workflows/  CI checks, platform builds and the combined release
├── package.json      npm scripts for everything (build, check, test, release)
└── electron-builder.yml  Windows packaging (installer, portable, MSI)
```

## Windows app (`gui/`)

```
gui/
├── desktop/          Electron main process (Node): owns files, downloads, the library and updates
│   ├── main.ts       App lifecycle, the window, IPC (keepfin:request / keepfin:push)
│   ├── preload.cts   Exposes only window.keepfin = {request, onPush, pathForFile}
│   ├── engine/       The download engine: tools, inspection, downloads, galleries, music matching
│   ├── manager.ts    The persistent download queue (parallel workers, pause/resume/retry)
│   ├── library/      The library index, imports, playlists, play cache, keepfin-media:// protocol
│   ├── copies/, cloud/, net/, merge/   Copies to drives, cloud accounts, paired devices, library merging
│   └── *.test.ts     Unit tests next to the code they test
├── web/              The React window (no Node access; talks to main only through bridge.ts)
│   ├── shell/        App frame and navigation
│   ├── capture/, preview/, collection/, queue/   Paste a link, choose, download, the queue
│   ├── library/, player/   Library spaces and the players
│   ├── setup/, start/      First start, Keepfin folder setup, intro pages
│   ├── settings/     Settings pages, the guide, release notes
│   ├── ui/, styles/  Shared components and design tokens
│   └── store.ts      External store with per-job subscriptions
└── shared/           Pure TypeScript both sides import: the contract, formats, policy, errors, providers
    ├── contracts.ts  The typed contract between window and main process
    └── core/         Adapters, the only importers of the shared core's JavaScript build
```

## Android app (`android/app/src/`)

```
src/
├── main/java/app/keepfin/
│   ├── MainActivity.kt, KeepfinApplication.kt
│   ├── EngineApi.kt, MediaEngine.kt   The typed engine surface and its implementation
│   ├── Inspector.kt, Formats.kt, ArgsPolicy.kt, DownloadRunner.kt, ProgressParser.kt   The download engine
│   ├── DownloadService.kt, DownloadNotifications.kt, PublicDownloads.kt   Foreground downloads and saving
│   ├── PlaybackService.kt, PlaybackControls.kt   Media3 background playback
│   ├── folder/, library/   The Keepfin folder and the library (repository, SQL, imports)
│   ├── copies/, cloud/, net/, merge/, where/   Copies, cloud accounts, paired devices, merging, file locations
│   ├── setup/        First-start and folder setup logic
│   └── ui/           Jetpack Compose screens
│       ├── StudioApp.kt, StudioViewModel.kt   The app frame, navigation and screen state
│       ├── model/    Flows the screens call (jobs, collections, spaces, library actions, updates)
│       ├── downloads/, jobs/, home/, search/, library/, player/, settings/, setup/, devices/, copies/
│       └── theme/, components/   Design tokens and shared components
├── test/             JVM unit tests (formats, arguments, progress, errors, music parsers, flows)
├── sharedTest/       Runs the shared fixtures/ on Android
├── androidTest/      On-device engine and Compose UI tests
└── debug/            Debug-only resources
```

## Shared core (`core/`)

```
core/src/
├── commonMain/kotlin/app/keepfin/core/
│   ├── choice/       What a download offers and sends (Video, Audio, Both; quality; sizes)
│   ├── formats/, args/, progress/, errors/, providers/   Engine-facing decisions
│   ├── music/, lyrics/   Matching Spotify/Apple Music/Deezer links on YouTube Music; lyrics
│   ├── after/        What happens after a download (save, tags, cover art, lyrics, music video)
│   ├── library/, spaces/, dedupe/, playback/   Library rules, spaces, duplicates, playback
│   └── copies/, sync/, merge/, cloud/, net/, sqlstate/   The Keepfin folder, copies and devices
├── commonTest/       Tests that run on both targets
├── jvmMain/, jvmTest/   JVM-only pieces
└── jsMain/, jsTest/  JavaScript-only pieces
```

The core does no I/O, so both apps call it with plain data. Rules for writing portable code are in [core/README.md](../core/README.md).

## Boundaries

These are enforced by `npm run check` (`scripts/check/check-boundaries.mjs`) or by the platforms themselves:

- The React window never touches Node, files or paths. It sends structured requests over IPC; dropped files go through `pathForFile`, and the main process re-validates every path.
- `gui/shared/` is pure: no Electron, no Node I/O, no React.
- Only `gui/shared/core/*.ts` imports the core's JavaScript build.
- Logic both apps need goes in `core/` with a fixture in `fixtures/`, not into each app.
- Generated icon files are written by `npm run icons:build` from `assets/`; they are not edited by hand.

## Where the build output goes

| Output | Where |
|---|---|
| Windows window and main process | `build/web/`, `build/desktop/` |
| Shared core JavaScript | `build/core-js/` |
| Windows installer, portable, MSI | `releases/desktop/`, then sorted into `releases/<version>/` |
| Android APK | `releases/<version>/` (local), `releases/android/` (CI) |

None of these are committed.
