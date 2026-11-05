# Kotlin runner spec

For the worker who writes the Android side of the shared fixtures. Read `README.md` (format, comparison rules) and each category's `SCHEMA.md` first. The desktop runner, `gui/shared/fixtures.test.ts`, is the reference implementation: when this spec is unclear, copy what it does.

## Where and how

- File: `android/app/src/test/java/app/keepfin/FixturesTest.kt` (plain JVM unit test, runs under `gradlew.bat :app:testDebugUnitTest`). `org.json` is already a test dependency (`org.json:json:20250517`).
- Fixture folder: `File("../../fixtures/engine")` (Gradle runs unit tests with `android/app` as the working directory). Fail with a clear message when it does not exist. Do not copy fixtures into resources or assets.
- Load every `*.json` under it recursively, skipping directories named `samples`. Each file is `{description, cases: [...]}`. `input.infoFile` names a file in `formats/samples/`.
- One `@Test` per category (`formats`, `args`, `progress`, `errors`, `providers`, `music`, `dedupe`, `versions`, `spaces`). Run all cases of the category, collect the failures, and fail once at the end with a list of `category/file: case name` plus the diff, so one run shows everything.
- Skip a case when `platforms` exists and does not contain `"android"`.
- A case with `suspectedMismatch` is a known mismatch: if it fails, print `KNOWN MISMATCH <id>: <case>` and do not fail; if it passes, fail the test with `<case> now passes: remove its suspectedMismatch`. Print a one-line count of known mismatches per category.
- Compare as the README says: `expected` is a subset of the result (objects by expected keys, arrays by length then element, `null` matches `null` or absent); any key named `size` within 1; progress `percent`, `speed`, `eta` within 1; music `minConfidence` as a floor. Write one `assertSubset(actual: Any?, expected: Any?, where: String)` over `org.json` values and use it everywhere. Numbers compare as doubles.
- Never edit an `expected` value to make Android pass.

All mappings below use the functions as they exist today. If a function has to become `internal` or move to make it callable, tell the lead; do not change behaviour.

## formats (`Formats`)

Since phase 3 of the shared core, every formats op runs through `app.keepfin.core.formats` (`Formats`, `Title` in the `core/` module), the code the app runs. The runner is `FormatsFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test (`SharedFixturesTest.formats`) and by the on-device `CoreFormatsFixturesDeviceTest`. It runs the cases that apply to `android` (desktop-only ones are skipped) through the app's `Formats` object, which hands the info to the core as text with `FormatOptions(codecPreference = "vp9", skipDrm = true)`. `TitleRules.kt` is gone: `title` calls `app.keepfin.core.formats.Title.clean`. The mappings below describe the ops; the app's `Formats.size`, `videoCodec`, `audioCodec`, `subtitles` and `mediaInfo` keep their signatures.

- `media`: `val info = JSONObject(...)`; `val media = Formats.mediaInfo(url, info)`. Project to: `provider`, `title`, `uploader`, `duration`, `isLive`, `chapters`, `playlistUrl`, `thumbnail`, `music` (`MusicMetadata` -> `artist`, `album`, `track`, `releaseYear`, or null), `videoIds` (`media.video.map { it.id }`), `video` (each `VideoOption`: `id`, `height`, `fps`, `hdr`, `codec`, `formatId`, `hasAudio`, `size`, `sizeApprox`), `audio` (each `AudioOption`: `formatId`, `codec`, `language`, `size`, `sizeApprox`), `hasVideoOptions = media.video.isNotEmpty()`, `hasAudioOptions = media.audio.isNotEmpty()`. `pickVideo` (option ids) and `pickAudio` (format ids) replace `video` / `audio` by the listed entries in the listed order (`null` where missing); when the key is absent return the full list.
- `size`: `Formats.size(JSONObject(format), duration)` -> `{size: bytes, approx}`.
- `codec`: `Formats.videoCodec(value)` or `Formats.audioCodec(value)` by `kind`.
- `subtitles`: `Formats.subtitles(JSONObject(info))` -> list of `{lang, auto}`.

## args (`ArgsPolicy`, shared core)

Since phase 4 of the shared core, every args op runs through `app.keepfin.core.args` (`DownloadPlan`, `Labels` in the `core/` module), the code the app runs. The runner is `ArgsFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test (`SharedFixturesTest.args`) and by the on-device `CoreArgsFixturesDeviceTest`. It runs the cases that apply to `android` (desktop-only ones are skipped) through the app's `ArgsPolicy`, which maps Android's `FormatRequest` and `DownloadOptions` to the core. `ArgsPolicy.format` and `ArgsPolicy.options` are one function now: `ArgsPolicy.download(request, options, squareCover, mutagen)`.

- Build the request: `type video` -> `FormatRequest.Video(maxHeight, formatId, audioFormatId, fps, codec ?: "auto", container ?: "mp4")`; `type audio` -> `FormatRequest.Audio(target, quality ?: "best", formatId)`.
- Build the options: `DownloadOptions(subtitles = SubtitleOptions(enabled, languages ?: "en.*", auto, embed)` (when the fixture has no `subtitles`: `SubtitleOptions(false, "en.*", false, false)`), `embedThumbnail`, `embedMetadata`, `embedChapters`, `writeThumbnail` (each default **false**, not the Kotlin default), `sponsorBlock ?: "off"`, `sectionStart = section.start`, `sectionEnd = section.end)`.
- `args`: `ArgsPolicy.download(request, options, squareCover = input.squareCover == true, mutagen = false)` flattened to `[name, value?, ...]`.
- `plan`: the same tokens, read as `args/SCHEMA.md` describes. Valued flags: `-f -S --merge-output-format --remux-video --audio-format --audio-quality --sub-langs --convert-thumbnails --convert-subs --sponsorblock-mark --sponsorblock-remove --download-sections --ppa -o`; every other flag is a presence flag. Audio `format`: `--audio-format` absent or `best` -> `original`. Audio `quality`: only for mp3, m4a, opus, else null. `section`: parse `*S-E` (`inf` -> null) into numbers.
- `validateFormat` / `validateOptions`: `runCatching { ArgsPolicy.validate(...) }` -> `{valid = isSuccess}` (it throws `IllegalArgumentException`).
- `preset`: `ArgsPolicy.preset(DownloadPreset.of(wire), container ?: "mp4")` -> video `{type: "video", maxHeight, codec, container}` or audio `{type: "audio", target, quality}`. `presetValid`: the wire value is one of `DownloadPreset.entries`.
- `metadata`: `ArgsPolicy.metadata(title)` flattened. `tags`: `ArgsPolicy.tags(SourceTags(title, artist, album, track, year))` flattened.
- `quote`: `app.keepfin.core.args.Labels.quote(format, video, audio, duration, withSize)` (build `DownloadFormat`, `LabelVideo`, `LabelAudio` from the fixture) -> `{label, expectedSize, approx}`. `bytes`: `Labels.formatBytes(bytes)`. The app's own `Labels.label` / `Labels.expectedSize` (in `Formats.kt`) call the same function with `withSize = false`.

## progress (shared core)

Since phase 5 of the shared core, progress runs through `app.keepfin.core.progress.ProgressParser` (the `core/` module), the code both apps run. The runner is `ProgressFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test and by the on-device `CoreProgressFixturesDeviceTest`. The old Android parser logic is gone; `ProgressParser.kt` only maps the core's snapshot to Android types and the on-screen wording of each phase.

- `ProgressParser(ProgressPlatform.ANDROID, expectedSize = sum of streamSizes values, or null)`.
- A `line` step: `parser.accept(text)`.
- A `progress` step: `parser.accept("[keepfin] $status|$downloaded|$total|$estimate|$speed|$eta|$formatId")`, with `NA` for a null/absent number and the number as an integer otherwise. `fragmentIndex` / `fragmentCount` are not part of the Android template: ignore them.
- After every step take `parser.snapshot` -> `{percent, downloadedBytes, totalBytes, speed, eta, phase (already a key), processing}`.
- Expected checkpoints are `{after: i, ...}`; compare against the snapshot taken after step `i`, with `percent`, `speed` and `eta` within 1.

## errors (`ErrorMessages`)

- `classify`: `message = ErrorMessages.friendly(text)`, `retryKind = ErrorMessages.classify(text)` (`RETRYABLE` -> `retryable`, `ENGINE` -> `engine`, `PERMANENT` -> `permanent`, `RATE_LIMITED` -> `rateLimited`, `BOT_CHECK` -> `botCheck`). Class from the message with this table (first hit wins): `ErrorMessages.BOT_MESSAGE` -> `botCheck`; `RATE_LIMIT_MESSAGE` or `RATE_LIMIT_FINAL_MESSAGE` -> `rateLimited`; starts `Age-restricted` -> `ageRestricted`; starts `Sign in required` -> `signInRequired`; contains `blocked in your region` -> `regionBlocked`; contains `removed or is unavailable` -> `removed`; contains `didn't return this video right now` -> `unavailable`; contains `isn't supported by the download engine` -> `unsupported`; contains `DRM-protected` -> `drm`; contains `hasn't started yet` -> `notStarted`; contains `That quality isn't available` -> `formatUnavailable`; contains `site rejected the download engine` -> `extractorBroken`; contains `refused this download` -> `forbidden`; contains `out of storage` -> `diskFull`; starts `Network problem` or contains `connection to the site was interrupted` -> `network`; contains `Processing the downloaded media failed` -> `processing`; anything else -> `unknown`. Result: `{class, retry, noToolNames}`.
- `noToolNames` (for `classify` and `scrub`): none of `message`, `ErrorMessages.scrub(text)` and `ErrorMessages.detail(text)` matches `(?i)yt-?dlp|yt_dlp|youtube-?dl|ffmpeg|ffprobe|gallery-dl|aria2`. Every `errors` case asserts `noToolNames == true` even when `expected` does not list it.
- `scrub`: result `{noToolNames}`.

## providers (shared core)

Since phase 2 of the shared core, every providers op runs through `app.keepfin.core.providers` (`Providers`, `Naming` and the `Url` parser in the `core/` module), the code the app runs. The runner is `ProvidersFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test (`SharedFixturesTest.providers`) and by the on-device `CoreProvidersFixturesDeviceTest`. It runs every case of the folder, including the ones limited to `desktop` (the core implements the desktop's library naming too); the ops and their shapes are in `providers/SCHEMA.md`. The old `Providers` bodies, `Restriction.videoId` and `SpaceLogic.subfolder`/`fileStem`/`isoDate` now delegate to the core.

## music (shared core)

Since phase 1 of the shared core, every music op runs through `app.keepfin.core.music.MusicMatching` (the `core/` module), the code the app runs. The runner is `MusicFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test and by the on-device `CoreMusicFixturesDeviceTest`. The old `MusicMatcher` and `Restriction.pickAlternates` were removed.

## dedupe (shared core)

Since phase 7 of the shared core, every dedupe op (`sourceId`, `normalizeTitle`, `normalizeArtists`, `same`, `compare`, `existing`) runs through `app.keepfin.core.dedupe.Recordings` (the `core/` module), the code the app runs. The runner is `DedupeFixtureRunner` in `android/app/src/sharedTest`, used by `SharedFixturesTest.dedupe` (JVM) and by the on-device `CoreDedupeFixturesDeviceTest`; the ops and their shapes are in `dedupe/SCHEMA.md`. `SourceIds.of` and `Recordings.compare`/`find` in `LibraryLogic.kt` delegate to the core; only `Recordings.normalizeArtist` (the album grouping key) stays in Android.

## versions (shared core)

Since phase 8 of the shared core, every versions op (`compare`, `pick`, `valid`, `highlights`) runs through `app.keepfin.core.versions.Releases` (the `core/` module), the code the app runs (`UpdateRelease.newer` and `updateHighlights` delegate to it). The runner is `VersionsFixtureRunner` in `android/app/src/sharedTest`, used by `SharedFixturesTest.versions` (JVM) and by the on-device `CoreVersionsFixturesDeviceTest`. The feed parsing that stays in Android (`UpdateRelease.parse` and `select`: draft and prerelease flags, APK asset, download address) is covered by `UpdateChannelTest`.

## spaces (shared core)

Since phase 9 of the shared core, every spaces op runs through `app.keepfin.core.spaces` (`SmartRules`, `Tags`, `Collections`, `Spaces` in the `core/` module), the code the app runs. The runner is `SpacesFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test (`SharedFixturesTest.spaces`) and by the on-device `CoreSpacesFixturesDeviceTest`. It runs every case of the folder, including the metered-network case (`platforms: ["android"]`); the ops and their shapes are in `spaces/SCHEMA.md`. The core model is `LibraryItem`, `LibraryCollection`, `Follow` and `Space` in `app.keepfin.core.spaces` (wire strings for kinds and modes); the app's own `TagLogic`, `SmartLogic`, `CollectionLogic` and `SpaceLogic` map their types onto it and are covered by `SpacesV2Test`, `OptInFollowTest` and the other Android unit tests.

## Known mismatches the runner must tolerate

The `suspectedMismatch` field on a case carries its id and a one-line reason (`M-FMT-1` ... `M-SP-10`, 75 cases at the time of writing). They are listed by the lead after the first run. A `suspectedMismatch` case that starts to pass is a failure of the runner until the field is removed.

## errors (shared core)

Since phase 6 of the shared core every errors op runs through `app.keepfin.core.errors` (`Errors`, `Retry`), the code the app runs. The runner is `ErrorsFixtureRunner` in `android/app/src/sharedTest`, used by the JVM unit test (`SharedFixturesTest.errors`) and by the on-device `CoreErrorsFixturesDeviceTest`. `classify` -> `Errors.analyze(text, Platform.ANDROID)` (`class` = `errorClass.wire`, `retry` = `retry.wire`); `scrub` checks `ErrorMessages.scrub` and `ErrorMessages.detail`; `retry` -> `RetryPolicy.next(retriesUsed, text, crashed)`. Cases limited to `desktop` are skipped. The old per-app class mapping table in the runner was removed.
