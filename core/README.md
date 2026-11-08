# Keepfin core

The decision logic that the desktop and Android apps share, written once in Kotlin Multiplatform. It makes decisions and performs no requests: each app still does its own HTTP and runs its own engine process. The plan is `dev-docs/plans/SHARED-CORE-PLAN.md`.

Today it holds these areas.

- Music matching (`app.keepfin.core.music`): the searches to run for a recording, reading the search results, scoring, ranking, alternates and the stop rule. The behaviour is the desktop app's (`gui/desktop/engine/music.ts`). `AlbumMatching` adds matching through the album: reading the YouTube Music albums search and album page, choosing the same edition of the album (title, artist, year, track count; a reissue, deluxe or anniversary edition is another album), and the track at its place; it is a step function (`step` says "fetch this page", "matched" or "none") so each platform only performs the two requests and keeps its own cache. `MusicVideos` finds a song's official music video: the YouTube Music videos search, reading its rows (type, artists with their channel, exact views), and a strict match (an official video by the song's artist with the song's title and qualifiers; none rather than a guess). `fixtures/engine/music/videos.json`, dev-docs/plans/MUSIC-VIDEOS.md.
- Download progress (`app.keepfin.core.progress`): the engine's progress lines and aria2c lines, byte-weighted aggregation of the streams, phase keys, and "percent never goes backwards". The two apps' progress-line formats are data (`ProgressWire`).
- Errors and retry (`app.keepfin.core.errors`): failure text to class, retry kind and a message that never names a tool, tool-name scrubbing, error details, retry pacing; the desktop's rules from `gui/shared/errors.ts`, with the Android differences as a `Platform` parameter.
- Dedupe (`app.keepfin.core.dedupe`, `Recordings`): which stable id a link names (through the providers parser), title and artist normalisation, when two recordings are the same (source id, ISRC, same bytes, metadata) and which known recording a new one repeats. The behaviour is the desktop app's (`gui/shared/library.ts`, formerly).
- Versions and update channels (`app.keepfin.core.versions`, `Releases`): which text is a version (`X.Y.Z` or `X.Y.Z-beta.N`), release order, which release a channel offers, and the release-notes highlights. The behaviour is the desktop app's (`gui/shared/updates.ts`).
- Formats and clean titles (`app.keepfin.core.formats`): yt-dlp info JSON (passed as text, parsed once in common code) to video and audio options with size estimates, codec names, subtitle tracks, music metadata, the thumbnail and the clean title (`Title`). The behaviour is the desktop app's (`gui/shared/formats.ts`, `title.ts`); the platform differences are `FormatOptions` (`codecPreference`, `skipDrm`). 
- The download plan (`app.keepfin.core.args`): `DownloadPlan` validates structured intent (`DownloadFormat`, `PlanOptions`; a closed set of values, format ids that can never look like an option), resolves presets, and builds the semantic `Plan` (format selector and sort, merge/remux, conversion, subtitles, cover art, chapters, SponsorBlock, sections) and its arguments (`args`, `argPairs`). `ArgsJson` reads untyped input (the desktop renderer's IPC payload) with the desktop's rules. `Labels` gives the label and expected size of a queued download (`quote`) and `formatBytes`. Embedded-tag arguments are `metadataArgs` and `tagArgs`. The behaviour is the desktop app's (`gui/shared/policy.ts`); the platform is `PlanPlatform(coverArtForOpusFlac)`. Network, cookies, tool paths, multi-connection transfers and the progress/output flags stay in each app.
- Providers, routing and naming (`app.keepfin.core.providers`): `Url` (a WHATWG-style link parser, so both apps read a link the same way), `Providers` (provider names and folders, music-service links, collections, playlist context, the audio default, video ids, `safeName`, `validateUrl`) and `Naming` (sub-folders and file name stems for the desktop and the Android library, the date is a parameter). The behaviour is the desktop app's; where Android differed, `fixtures/engine/DECISIONS.md` (PRV-7 to PRV-10, NAMING-1) says what was decided.
- Spaces v2 (`app.keepfin.core.spaces`): `SmartRules` (what a smart space gathers), `Tags` (normalisation and the tags worth a chip in a scope), `Collections` (canonical source link and id, kind, radio mixes, new-item detection, the confirm gate, follow defaults, the due check, source order, playlist to collection migration) and `Spaces` (home, pins, reorder, default space). The model is `LibraryItem`, `LibraryCollection`, `Follow` and `Space` with wire-string kinds; time is always a parameter. Storage stays per platform. The behaviour is the desktop app's (`gui/shared/library.ts`); `DECISIONS.md` SP-12 to SP-16 lists the few places Android or the desktop changed.

- Lyrics (`app.keepfin.core.lyrics`, `Lyrics`): the two LRCLIB requests (exact and search), reading their answers, choosing the record that fits the song (length, title, artist, synced first) and `retryAfterMs`, the one decision about an answer that did not work: a real "not found" (404, other 4xx) stops, a busy service (429, 5xx) or no answer is asked again, three tries in all after 2 s then 6 s. Titles and artists in any script. Desktop through `gui/shared/core/lyrics.ts`, Android through `LyricsLookup`; the waiting and the requests stay in each app. `fixtures/engine/lyrics`.

- Playback (`app.keepfin.core.playback`, `Playback`): what every Keepfin player decides the same way: skips that add up (`accumulateSkip`, the label and the spoken text), where playback resumes (`resumeFrom`), chapters (`chapterAt`, `chapterTarget` for previous/next chapter, `crossedChapter` for the phone's scrub tick), what plays next (`playbackList`: the list the item was opened from, or its folder; `neighbour`; `folderOrder` and `compareTitles`, the one natural title order), the speeds offered and the time label (`clock`). Named for any player, not only video. Desktop through `gui/shared/core/playback.ts`, Android through `ui/player/VideoLogic.kt` and `LibraryViews`. Drawing, gestures, keys, preview frames and timers stay in each app. `fixtures/engine/DECISIONS.md` PLAY-1 and PLAY-2.
- Photo posts (`app.keepfin.core.gallery`, `Gallery`): the gallery engine's `-j` output (passed as text) to a photo post (title, uploader, category, provider, previews, items, count, truncated; and `Gallery.range`, the engine range for the photos chosen), a reported failure, or nothing usable. The behaviour is the desktop app's (`gui/desktop/engine/gallery.ts`), through `gui/shared/core/gallery.ts`. `fixtures/engine/gallery`.
- Segment compaction (`app.keepfin.core.sync`, `SegmentCompaction`): which of a device's own small segment files to merge into fewer larger ones (tiers of 2,000 then 5,000 ops, contiguous, signed by the current key, never across a gap, a named file or an actor with problems) and which files a larger file of the same actor covers. Pure: the stores (`gui/desktop/store/store.ts`, `KeepfinStore.kt`) write and verify the merged file and only then remove the inputs. Desktop through `gui/shared/core/sync.ts`. `fixtures/engine/compaction`, dev-docs/plans/KEEPFIN-DATA-COMPACTION.md.

## Targets

- `jvm`: a plain JVM library (Java 17 bytecode). Android will use it through `includeBuild`; the core has no Android plugin.
- `js`: an ES module with TypeScript declarations, used by the desktop app. `npm run core:build` builds it and copies it to `build/core-js/`; only `gui/shared/core/*.ts` imports it.

## Commands

Run from `core/`. Needs a JDK 17 or newer and Node.js on `PATH` (the build uses the installed Node and npm, nothing is downloaded for them).

```
./gradlew jvmTest jsNodeTest                  # fixtures + portability test on both targets
./gradlew jsNodeProductionLibraryDistribution # the JavaScript library (npm run core:build does this and copies it)
```

On Windows use `gradlew.bat`. From the repository root, `npm run core:build` builds the JavaScript library and `npm run check` includes it.

## Tests

`src/commonTest` runs every case in `fixtures/engine/music`, `fixtures/engine/progress`, `fixtures/engine/errors`, `fixtures/engine/versions`, `fixtures/engine/providers`, `fixtures/engine/dedupe`, `fixtures/engine/formats`, `fixtures/engine/spaces`, `fixtures/engine/args`, `fixtures/engine/playback`, `fixtures/engine/gallery`, `fixtures/engine/signin`, `fixtures/engine/compaction` and `fixtures/engine/lyrics` (progress, errors, formats and args once per platform) (the files the desktop and Android runners use too) on both targets, and a portability test. The build passes the fixture folder to the tests (`keepfin.fixtures` on the JVM, `KEEPFIN_FIXTURES` on Node). An `expected` value in `fixtures/engine` is never changed to make the core pass. A target that disagrees with the other is a finding to report, not something to hide.

To add an area, add a handler for its category in `src/commonTest/kotlin/app/keepfin/core/` and register it in `FixturesTest`.

## Portability rules

The same Kotlin code runs on Java's, JavaScript's and (on Android) ICU's regex engines and string functions, so common code follows these rules:

- No regex shorthands that differ between engines (`\b`, `\s`, `\w`, `\d`), no lookbehind or named groups, and no end-of-input anchor (`$`) where the text can contain line terminators. A test scans `src/commonMain` and fails on them. Use explicit character classes (`JS_WS` in `JsText.kt` is JavaScript's whitespace set), and `matchStartAtEnd` for "at the end".
- Case-insensitive matching is spelled out with letter classes (`ci("feat")`) or `asciiLower`, never the engine's ignore-case flag.
- `String.trim()` differs between platforms: use `jsTrim`.
- Unicode normalisation is `expect fun nfkd` (music) and `expect fun nfkc` (the host-name mapping in `Url`).
- No `String.format`, `Locale`, `java.net.URI`, `java.net.URL`, `org.json` or clock calls. Links are parsed by `providers.Url`, never by the platform: `java.net.URI` and JavaScript's `new URL` disagree on host case, ports, user info, escapes and IDN hosts. `Url` was checked against `new URL` on about 700,000 random http(s) links. JSON is kotlinx-serialization's `JsonElement` only (no compiler plugin); time is a parameter.
- Round with `round2` (`floor(x * 100 + 0.5) / 100`, like JavaScript's `Math.round`), never `round()`, which rounds half to even.
- `@JsExport` only works in `jsMain`: the JavaScript facade (`Facade.kt`) lives there and converts to arrays, nullable numbers and wire strings. Its names are the TypeScript API.
