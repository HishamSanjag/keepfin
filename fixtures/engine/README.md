# Shared engine fixtures

Language-neutral JSON cases (input, expected) for the logic that exists twice: TypeScript on desktop (`gui/shared/`, `gui/desktop/`) and Kotlin on Android (`android/app/src/main/java/app/keepfin/`). Both test suites run the same files, so any drift fails a test. The plan and the reasons are in `dev-docs/architecture/SHARED-FIXTURES.md`.

- Desktop runner: `gui/shared/fixtures.test.ts`, part of `npm run check` (`gui:test` globs `gui/shared/*.test.ts`).
- Android runner: `android/app/src/test/java/app/keepfin/SharedFixturesTest.kt`, under `:app:testDebugUnitTest`. Its spec is `KOTLIN-RUNNER.md` in this folder.
- Fixtures stay at the repo root. TypeScript resolves them from the test file; Kotlin JVM tests resolve `../../fixtures/engine` from `android/app`. There is no copy in the app assets.

## Layout

```
fixtures/engine/
  formats/    info JSON -> video/audio options, size estimates, codec names, subtitle tracks
              samples/    real public info JSON (never read as fixtures; referenced by "infoFile")
  args/       FormatRequest + options -> a SEMANTIC download plan (never a command line); validation; presets
  progress/   engine progress steps and log lines -> aggregated progress and phase keys
  errors/     failure text -> error class + retry kind, and the "no tool names" check
  providers/  URL -> provider, folder, music service, collection, playlist context, audio default; safe names; link validation
  music/      recording + search candidates -> best match, restricted-song alternates, search text
  dedupe/     link -> source id; two recordings -> same?; library items + probe -> skip-existing decision
  versions/   release ordering and update channel selection
  spaces/     Spaces v2: smart rules, tags, new-item detection, confirmAbove gate, ordering, migration, hub, routes
  playback/   player decisions: skips that add up, resume, chapters, what plays next, title order, the time label
  lyrics/     LRCLIB lyrics: the two requests, choosing a record, when to ask again after a busy answer
```

Each category folder has a `SCHEMA.md` with the operations (`input.op`) and the shape of `input` and `expected`.

## File and case shape

```json
{"description": "...", "cases": [
  {"name": "...", "platforms": ["desktop"], "suspectedMismatch": "M-XXX-1 ...", "input": {"op": "..."}, "expected": {}}
]}
```

- One case per line, stable key order, valid JSON. Case names are unique within a file.
- No absolute paths, no real timestamps. Time is an input (`now`), paths are not used.
- `platforms` defaults to both. Set it (`["desktop"]` or `["android"]`) only for a documented, intentional difference. A runner skips a case whose `platforms` does not list it.
- `reason` explains a per-platform case: when two platforms intentionally differ there is one case per platform (`platforms`), each with its own `expected` and a `reason`. Decisions are recorded in `DECISIONS.md`.
- `suspectedMismatch` marks a case where the other platform is known (from reading its code and tests) to behave differently. The `expected` value is always the DESKTOP behaviour. The lead decides every mismatch: fix the wrong side (then delete the field), or declare it intentional (then add `platforms`). The Android runner treats a failing `suspectedMismatch` case as a known mismatch and reports it; if such a case passes it fails the test ("fixed, remove the field").
- Do not change an `expected` value to make a test pass without deciding which platform is right.

## Comparison rules

`expected` is matched as a subset of the normalised result:

- objects: every key in `expected` must match; extra keys in the result are ignored.
- arrays: same length, element by element, each as a subset.
- `null`: the result is `null` or absent.
- numbers and strings: equal. Exceptions: any key named `size` (estimated bytes) may differ by 1; progress checkpoints compare `percent`, `speed` and `eta` within 1; a music `minConfidence` is a floor on the winner's confidence.
- Errors compare the error CLASS and retry kind, never the wording. A case also fails if a user-facing message names an external tool (yt-dlp, ffmpeg, gallery-dl, aria2c).
- Args compare a normalised plan (format selector, sort, merge/remux, conversion, subtitles, embed flags, section), never the full command line: desktop uses Electron as the JS runtime and Windows paths, Android uses multi-connection transfers and its own processor paths.
- Legacy v1 queue migration is excluded (the old stores had different formats). Playlist -> collection migration is covered in `spaces/`.

## Adding a case

1. Pick the category and operation (see its `SCHEMA.md`) and append a case to the right file (or a new `*.json` file in the folder).
2. Run `FIXTURES_DUMP=1 npx tsx --test gui/shared/fixtures.test.ts` and read the `DUMP` lines: they show what the desktop logic returns for every case. Copy the part that matters into `expected`; read it first, since the point is to say what is right, not to record what is.
3. Run `npm run gui:test` (or `npm run check`). When the Android runner exists, run `gradlew.bat :app:testDebugUnitTest` from `android/` too.
4. If the platforms disagree, keep the desktop value in `expected`, add `suspectedMismatch` with a short reason, and tell the lead.

## Real info JSON samples

`formats/samples/*.json` are trimmed yt-dlp info dicts of public videos and tracks (no cookies, accounts or personal data). `youtube-video-android.json` is the sample the Android tests already use. A case refers to one with `"infoFile": "<name>.json"`.
