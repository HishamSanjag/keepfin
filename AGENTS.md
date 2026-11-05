# Keepfin — AI contributor guide

## Product and ownership

Keepfin is a local media downloader for Windows (Electron) and Android (native Kotlin). Repository: https://github.com/HishamSanjag/keepfin (public: the source, the releases and the update feed). Downloads execute locally; there is no hosted backend. Android is an installable APK, not a public SDK. Goal: the most capable local downloader — backend capability first, every UI claim backed by real engine behaviour. `dev-docs/product/SPEC.md` is the product/engineering spec.

Prefer codebase-memory-mcp for code discovery: `search_graph`, `trace_path`, `get_code_snippet`, `query_graph`, `search_code`, then `get_architecture`. Index the repository first if needed. Fall back to rg/file reads for configs, literal searches, and insufficient graph results.

## Architecture and source owners

Desktop (`dev-docs/architecture/DESKTOP-ENGINE.md` has the engine design and measurements):

- `gui/shared/contracts.ts`: the v2 contract between main and renderer (MediaInfo with sized video/audio options, FormatRequest, DownloadOptions, PlaylistDiscovery, Job/JobProgress, Settings, EngineStatus, Operation, PushEvent). The renderer sends structured intent only, never raw command-line arguments.
- `gui/shared/formats.ts`, `policy.ts`, `errors.ts`, `providers.ts`, `output.ts`: pure, unit-tested logic — yt-dlp info → options and size estimates; FormatRequest/options/settings → validated yt-dlp args; friendly error mapping; provider names, platform folders and routing (collections, music links, galleries).
- `gui/desktop/engine/`: the Keepfin engine. `tools.ts` manages yt-dlp and gallery-dl in `<userData>/tools` (seeded from packaged `resources/tools`, SHA-256 verified downloads, daily auto-update, stable/nightly) plus ffmpeg-static and Electron-as-Node (`ELECTRON_RUN_AS_NODE` + `--js-runtimes node:<execPath>`) for YouTube's JS challenges. `ytdlp.ts` inspects, discovers collections in pages and runs downloads with progress/phase parsing and process-tree cancellation. `turbo.ts` is the optional parallel range downloader (off by default; measured slower on fast links). `gallery.ts` handles image posts via gallery-dl. `music.ts` resolves Spotify/Apple Music/Deezer metadata and matches recordings on YouTube Music (no audio is taken from those services). `index.ts` is the `StudioEngine` facade.
- `gui/desktop/manager.ts`, `settings.ts`, `migrate.ts`: persistent queue with parallel workers, pause/resume/cancel/retry, discovery sessions, cookie epoch fencing, settings validation and lossless migration of v1 `queue.json`. Pushes debounced snapshots and ≤4 Hz progress batches; never writes `queue.json` on progress ticks.
- `gui/desktop/main.ts`, `preload.cts`: Electron lifecycle, IPC (`keepfin:request`) with sender/frame checks, push channel `keepfin:push`, native dialogs and file opening. The preload exposes only `window.keepfin = {request, onPush, pathForFile}` (pathForFile = webUtils.getPathForFile for dropped files; main re-validates every path). Maintain context isolation and sandbox.
- `gui/shared/library.ts` + `gui/desktop/library/`: the library (dev-docs/architecture/LIBRARY.md): spaces, the curated index (`<userData>/library.json`), imports/moves/renames/tag edits, loudness, lyrics, playlists, the play cache (lossless remux) and the `keepfin-media://` protocol (opaque ids → files, real Range/206 responses). The renderer never sends paths except drops via `pathForFile`, which main re-validates.
- `gui/web/`: React renderer. `store.ts` is an external store with per-job subscriptions so progress re-renders only the affected row; surfaces live in `shell/`, `capture/`, `collection/`, `queue/`, `library/`, `settings/`, primitives in `ui/`, tokens in `styles/`. The browser preview has no download API; never fabricate successful inspection or transfers.

Android (`android/app/src/main/java/app/keepfin/`) is native Kotlin; it does not run the Node engine or load the React page.

- `EngineApi.kt` + `EngineModels.kt`: typed engine surface (StateFlows of immutable data classes) mirroring the desktop contract. `MediaEngine.kt` implements it with `Inspector`, `Formats`, `ArgsPolicy`, `DownloadRunner`, `ProgressParser`, `JobStore` (v1 migration), `ToolsUpdater` (youtubedl-android `updateYoutubeDL`, at most daily), `MusicResolver`, `CookieExport` (in-app sign-in → Netscape cookies), `ErrorMessages` and `Providers`. youtubedl-android 0.18.1 with ffmpeg and aria2c (aria2c "Turbo" is opt-in).
- Library (dev-docs/architecture/LIBRARY.md): `LibraryApi`/`LibraryModels` contract, `LibraryStore` (MediaStore-backed curated index, spaces, imports via the document picker, consent flows for files from before a reinstall), `LibraryLogic` (pure layout/naming/dedupe), `PlaybackService` (Media3 MediaLibraryService: background playback, resumption, Auto tree) with `PlaybackControls` (sleep timer, equalizer). UI in `ui/library/` and `ui/player/`.
- `DownloadService` (foreground progress), `DownloadNotifications`, `PublicDownloads`, `ThumbnailStore`, `LocalMediaPreview`, `AppUpdates`.
- `ui/`: Jetpack Compose + Material 3 (`StudioApp`, `StudioViewModel`, downloads/, jobs/, library/, settings/, updates/, theme/). Queue rows are keyed by job id and recompose individually. The Compose BOM is pinned to 2026.03.00 for AGP 8.13 / compileSdk 36; newer BOMs need AGP 9 and compileSdk 37.

Shared core (`core/`, Kotlin Multiplatform, `dev-docs/plans/SHARED-CORE-PLAN.md`): decision logic written once for both apps. Targets `jvm` (Android consumes it through `includeBuild("../core")`) and `js` (an ES module built into `build/core-js/` by `scripts/build/build-core.mjs`; `gui/shared/core/*.ts` adapters are the only importers). It performs no I/O. Moved so far: music matching (`app.keepfin.core.music`: search plan, songs/flat result reading, scoring, best match, alternates). Follow the portability rules in `core/README.md` (no regex shorthands, JS whitespace/trim, JS rounding); `core/` tests run every fixture on JVM and JS.

Shared: `assets/icons/icon.svg` and `scripts/build/build-icons.mjs` are the reproducible icon inputs.

Preserve `app.keepfin.desktop`, Android `app.keepfin`, `%APPDATA%/Keepfin`, IPC identifiers, persisted settings and the signing key once released. Maintain portrait phone layouts, dp/sp sizing, 48dp touch targets and font scaling. Android share intents automatically inspect the captured link; downloading always requires an explicit action. `PublicDownloads` publishes videos to `Movies/Keepfin` (MediaStore.Video), audio to `Music/Keepfin` (MediaStore.Audio) and sidecars to `Download/Keepfin`. Only owned earlier public downloads migrate; user-moved files stay put. Public copies survive uninstalling. Handle save failures explicitly and preserve the original; never imply an unsuccessful save reached public storage.

## Behavioural rules

The app must not show external tool names (yt-dlp, ffmpeg, gallery-dl, aria2c) anywhere in its UI, notifications, errors, release notes or release pages; use "download engine", "gallery engine" and "multi-connection transfers". Developer docs and code may name them. There is no in-app licenses screen: license texts (and the credit "Originally inspired by Yoinks by Pablo Stanley.") ship only as plain files inside the packages — Windows `resources/licenses/`, Android `assets/third-party/NOTICES.txt` — and in `THIRD-PARTY-NOTICES.md`. Keep them complete for GPL/LGPL components.

Keep saved artwork durable and separate from temporary inspection caches. Removing history preserves media and rejects active jobs; deleting a file is a separate, confirmed action. Late image callbacks must not recreate removed artwork. Notifications are owned by the Android service/application and the Electron main process, not renderer polling. Both apps show an app-update indicator in the header and a one-time "What's new" after an upgrade.

Collections (playlists, channels, albums, mixes, Spotify playlists) use progressive flat discovery in pages of 200 with a 60-second deadline per page and explicit selection; formats resolve only when selected jobs start. `PlaylistDiscovery.error` is for failures; `notice` is for non-fatal notes on usable results. Radio mixes are infinite and must stay bounded by paging. Single-item inspection keeps two metadata workers and a ten-minute cache; cookie changes fence in-flight results. Preserve successful partial results and retry only unfinished links. Never promise provider-independent timing or speed-ups that were not measured.

## Local builds and checks

Requires Git, Node.js 24+ (`npm ci`) and a JDK 17+ (`npm run core:build` builds the shared core; `check`, `gui:build`, `gui:test`, `gui:typecheck` and `engine:test` run it first). Android uses JDK 17+, SDK 36 and build-tools 36.0.0. If a shell exports `ELECTRON_RUN_AS_NODE=1`, unset it before launching Electron.

```powershell
npm run check             # TypeScript, all unit tests (shared, desktop, engine), renderer + main builds
npm start                 # Real Electron app
npm run dev               # Browser preview (labelled, no downloads)
npm run gui:ui-test       # Renderer suites with a fake bridge: flows, progress isolation, layout/a11y
npm run gui:real-test     # Real renderer/IPC/yt-dlp video + MP3 workflow (after a vite build)
npm run engine:test       # Real engine smoke on a generated local video; add -- --live for YouTube/YouTube Music/Spotify
npm run desktop:test      # Electron smoke; pass a packaged exe path to check the installed build and bundled yt-dlp
npm run tools:fetch       # Download + verify yt-dlp/gallery-dl into build/tools (run by desktop:build/pack)
npm run desktop:build -- --win --publish never
npm run android:build     # Local optimized APK signed with the debug key, sorted into releases/<version>/ (add -- --debug for the debuggable build)
npm run releases:organize # After a desktop build: current builds to releases/<version>/, earlier ones to releases/older/
```

Android, from `android/`: `gradlew.bat :app:testDebugUnitTest` (JVM tests for formats, args, progress, errors, music parsers and migration) and `gradlew.bat :app:connectedDebugAndroidTest` with a device or emulator (engine tests plus Compose UI tests in `app.keepfin.ui`). On this Windows machine, strip stray double quotes from PATH before unit tests. Report what actually ran; a build alone does not prove device UI or provider behaviour. macOS/Linux packaging is configured locally but not automated.

## Deployment and GitHub Actions

Deployment means distributing desktop executables and APKs through GitHub Releases.

- `.github/workflows/build-windows.yml`: reusable Windows build; Node 24, npm ci, checks, UI regression suites, engine smoke, verified tool bundle, NSIS/portable/MSI packages, Actions artifact. It does not publish releases.
- `.github/workflows/build-android.yml`: reusable Ubuntu build; Java 17, Android SDK tools, npm icon generation, Gradle assembleRelease, zipalign, apksigner sign/verify, APK artifact. It does not publish releases.
- `.github/workflows/checks.yml`: runs on every branch push (not tags): Windows `npm run check`, Ubuntu Android `:app:testDebugUnitTest`; read-only, no secrets, newer pushes cancel older runs. `gui:real-test` is not in it: it needs the downloaded engine tools (network) and a built renderer. A pre-commit hook (`scripts/dev/hooks/pre-commit`, installed by `npm ci` through `prepare`) runs the fast checks; `git commit --no-verify` skips it once.
- Both support manual builds and workflow_call. Branch pushes do not start separate platform builds. Only the combined release workflow runs automatically on v* tags; manual platform builds remain available. Old desktop-v/android-v tags no longer trigger them.
- `.github/workflows/release.yml`: tag `v<VERSION>` validates matching desktop/Android versions and combined notes, invokes both reusable builds, then publishes one Release on this repository after both succeed. A manual run validates and builds both but does not publish. Secrets are inherited only by the Android call. Every job has contents: read except the publishing job, which has contents: write and uses the workflow's own token.
- Release title: `Keepfin v<VERSION>`. Notes: `docs/release-notes/<version>.md` with Windows and Android bullets. Add notes to the tagged commit; missing/blank notes or mismatched versions fail validation. Notes under windows/ and android/ are historical and are no longer used for publishing.
- Keep package.json, package-lock.json, and Android versionName on the same version for a release. Increment Android versionCode for each new release, even if its UI is unchanged; build both from the same tagged source rather than relabeling old binaries.
- Windows outputs: `releases/desktop`. CI Android output: `releases/android/Keepfin-Android-<version>.apk`. The local debug helper names its file using the npm package version.
- Android signing secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Refer to secret names only; never read, print, commit, or embed their values or key files. Reuse the signing identity and increment Android versionCode for upgrades.
- Public downloads, and the feed both apps update from, are this repository's Releases.

The user is learning Actions, signing, releases, and updates step by step. Explain workflow changes and provide commands for the user to practice. Do not push release tags or publish on their behalf unless they explicitly ask. Read dev-docs/guides/IN-APP-UPDATES.md for owners, public-feed requirements and publishing steps. Preserve app IDs/profile paths and signing keys. Include latest.yml and the Setup blockmap in every release. Portable copies use manual EXE replacement. Local updater tests do not prove a two-version published upgrade; report that limit until it is tested.

## Design ownership

`PRODUCT.md` records product constraints and the confirmed everyday video/audio workflow. `DESIGN.md` owns the Capture workbench language: ink graphite, cobalt, compact system typography and open media rows. Android has a platform-specific `android/DESIGN.md`. Preserve meaningful light/dark contrast, 80–140% desktop sizing, phone touch targets, reduced motion, and truthful engine states. Keep active downloads separate from Library; settings groups include Updates. Icon geometry is reproducible from `assets/icons/icon.svg`; run `npm run icons:build` instead of hand-editing generated launcher assets. Use real engine tests as well as fixture UI regressions; fixtures never certify provider compatibility.

## Docs

`docs/` is public and written for people using Keepfin: plain language, no plans or decisions, every Settings path checked against the app. `dev-docs/` (plans, decisions, architecture notes, guides, archives) is gitignored and exists only on the owner's machine; new plans go there, and public pages never link to it (`npm run check` fails if they do). The in-app guide is `content/guide.json`: keep it and `docs/` in step with the Settings pages.

## Licensing and references

`LICENSE` is MIT, Copyright (c) 2026 Hisham Sanjag. Preserve all bundled component notices. The MIT source notice does not cover every third-party binary; Android and ffmpeg have additional license/source obligations. See THIRD-PARTY-NOTICES.md before preparing public distribution bundles.

- `dev-docs/product/SPEC.md`: Keepfin capabilities; `dev-docs/architecture/DESKTOP-ENGINE.md`: desktop engine design and benchmarks; `docs/screenshots/`: current UI.
- `dev-docs/architecture/FRONTEND-AND-PLATFORMS.md`: architecture details.
- `dev-docs/guides/REPOSITORY.md`: repository history and source ownership.
- `dev-docs/guides/BUILD-COMMANDS.md`: local commands.
- `dev-docs/guides/RELEASE-PROCESS.md`: shared versions, change bullets, combined tags and release editing.
- `dev-docs/archive/STUDIO-VALIDATION.md`: historical migration validation, not proof that later changes passed.

Several older guides and validation records describe the earlier architecture. Prefer the current guides and current workflow/source configuration when they differ.
