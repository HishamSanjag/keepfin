# Mismatch decisions (lead, 2026-10-10)

Each `M-*` id in the fixtures carries one of three decisions:

- **D**: desktop behaviour is canonical, so fix Android.
- **A**: Android behaviour is canonical, so fix desktop.
- **P**: an intentional platform difference. Mark the case with `"platforms"` (keep one case per platform) and add a one-line reason.

Once fixed, the `suspectedMismatch` flag is removed and both suites assert the same `expected`.

Two principles:

1. Correctness and safety come first.
2. Where both sides are reasonable, the more robust behaviour wins. Ties go to desktop, the older and more widely tested code.

## Args

| id | Decision | Note |
|---|---|---|
| ARGS-1 hdr:sdr sort | D | Prefer SDR on both, for playback compatibility |
| ARGS-2 remux-video | D | |
| ARGS-3 height cap as sort | D | A sort degrades gracefully when no stream fits; a filter fails |
| ARGS-4 exact-id fallback chain | D | |
| ARGS-5 webm merge `webm/mkv` | D | |
| ARGS-6 `[acodec^=mp4a]` m4a fallback | A | Desktop adds it too |
| ARGS-7 subtitles for audio | A | No subtitle sidecars for audio-only downloads |
| ARGS-8 chapters in any non-wav | A | Long mixes keep chapters in audio files |
| ARGS-9 cover art for opus/flac needs mutagen | P | Android packaging constraint |
| ARGS-10 format id `--exec` | A | **Security hardening**: desktop adopts Android's strict format-id pattern |
| ARGS-11 min height | D | 100 on both |
| ARGS-12 MP3 preset quality | D | `best` (VBR V0) on both |
| ARGS-13 label and expected size of a queued download | lead | Found in phase 3, built in phase 4. The core uses Android's richer estimate: video is the size of the chosen quality; an audio conversion is estimated from its bitrate and the duration (mp3 at its quality or 245 kbit/s for "best", wav 1411 kbit/s), anything else is the size of the chosen or best audio stream. The estimate is always marked approximate (`approx`). The wording is the desktop's ("1080p60 · MP4 · H.264", "MP3 · 320 kbps", "Best quality · MP4"); no fixture shows Android's wording as the one users see on both. Details: a quality named by an exact id that was not inspected is not replaced by a search under the height cap (desktop); the label takes the codec from the request, not from the stream (desktop); a frame rate above 32 is shown (desktop; Android showed above 30). The desktop puts the video size in the label ("~143 MB"); the Android job row shows the size on its own, so it asks for the label without it (`withSize = false`, a parameter, not a second code path) |
| ARGS-14 one set of validation limits and messages | D | Found in phase 4 (ties go to the desktop). Frame rate 1 to 240 (Android allowed 480). An empty format id means none. A time range is at most one week, and an end without a start must be after 0 (Android had no cap; the desktop only found it when the download started). Subtitle language codes are checked whenever the options carry subtitles, 100 characters at most. The messages are the desktop's ("Choose a supported video quality.", "Choose a format from the inspected list.", and so on). Android keeps one exception at its own boundary: it passes subtitles to the core only while they are on, because its language field can hold half-typed text while the switch is off |
| ARGS-15 one spelling of the arguments | D | Found in phase 4. Both apps now build the same list in the desktop's order: short selector names (`ba/b`, not `bestaudio/best`; same meaning to the engine); no `--audio-quality` for flac, wav and original, no `--audio-format best` for original (both were no-ops); an exact audio stream keeps the preferred-codec fallback (`251/ba[acodec=opus]/ba/b`); subtitle sidecars are converted to srt (`--convert-subs srt`; Android left the site's format, often vtt) |
| ARGS-16 seconds in a section | D | Found in phase 4. The core writes at most three decimals without trailing zeros (`*0.5-10.25`, `*90-inf`). Android already wrote three decimals (`20.500`), the desktop wrote the full number (`12.3456`); the engine reads all of them |
| ARGS-17 one-word tag values | D | Found in phase 4. The engine's `--parse-metadata` reads a single word as a field name (it would read the artist `Adele` as the missing field `Adele`, giving `NA`). The desktop's clean title already wrote a one-word value as a template with a default (`%(keepfin_literal|Hello)s`); the resolver tags on Android (title, artist, album, album artist) did not. Now they all do. Track number and year stay plain digits. Text is flattened and cut to 300 characters before it is escaped (Android cut after escaping, which could split an escape) |

## Formats

| id | Decision | Note |
|---|---|---|
| FMT-1 portrait label | D | Label by the short side (1080p) |
| FMT-2 `is_upcoming` is live | A | An upcoming stream cannot be downloaded yet |
| FMT-3 "original" option for a bare direct file | A | Desktop offers it too |
| FMT-4 `file://` thumbnail | D | **Security**: only http(s) thumbnails, on both |
| FMT-5 vp9 above av1 | P | Phones decode vp9 in hardware far more often; desktop ranks them equal |
| FMT-6 audio dedupe per codec | D | |
| FMT-7 vbr + abr summed | D | Summing double-counts the size |
| FMT-8 alac | D | Lossless family, like desktop |
| FMT-9 manual subtitle order sorted | A | |
| FMT-10 DRM-protected formats | P | Android drops formats flagged `has_drm` (`FormatOptions.skipDrm`); the desktop does not read the flag and lists them (the download then fails with a clear message). Found in phase 3; both behaviours kept as a parameter, two fixture cases |
| FMT-11 one ranking and one set of limits | D | Found in phase 3. Android used its own video/audio selection code; it now runs the desktop's: audio options up to 40 (Android: 24), language codes `[A-Za-z0-9_-]` up to 30 characters (Android: also `.` up to 40), at most 24 automatic captions and 20 priority languages (Android: all, up to 250), bitrates and sizes rounded like `Math.round` (Android: truncated), uploader from `uploader`/`channel`/`creator` |

## Progress

| id | Decision | Note |
|---|---|---|
| PROG-1 weight two streams by bytes | D | |
| PROG-2 percent never goes back within a phase | A | |
| PROG-3 fragment counts | P | The Android engine output template differs |
| PROG-4 Remuxer / MoveFiles phase labels | D | |

## Errors

Compare classes, not wording.

| id | Decision | Note |
|---|---|---|
| ERR-1 logged-in wall is signIn | D | |
| ERR-2 "no video formats" retries once after an engine refresh | D | |
| ERR-3 HTTP 403 | P | Android refreshes its bundled engine (it ships stale); desktop already auto-updates daily and retries with backoff |
| ERR-4 missing media processor | D | Same class |
| ERR-5 permission denied | D | Android gets the class and message |
| ERR-6 unknown text | D | One engine-refresh retry, then fail. **5xx**: a plain retry on both (desktop changes for 5xx) |
| ERR-7 copyright grounds | D | Android pattern fixed |
| ERR-8 module name scrubbed | D | Rule: no tool names |

## Providers

| id | Decision | Note |
|---|---|---|
| PRV-1 extractor-key fallback | D | |
| PRV-2 YouTube Music platform folder | D | "YouTube" on both; changing desktop would split existing folders |
| PRV-3 music detection beyond the host | D | Covers artist pages; `itunes.apple.com` is not a music-service link (fixture) |
| PRV-4 radio mix URL form | **A (revised)** | Verified live on 2026-10-10: a mix's `/playlist?list=RD…` page is "unviewable". Mixes keep `watch?v=<seed>&list=RD…`; curated RDCLAK/RDAMPL lists use `/playlist`. Desktop had this bug too |
| PRV-5 collapse runs of spaces in names | A | Only future file names change |
| PRV-6 Spotify/Deezer artist pages | D | Not collections |
| PRV-7 short music links and the audio default | A | A short link that is not expanded yet (`spotify.link`, `spoti.fi`, `deezer.page.link`) still defaults to audio, as Android did: it can only lead to music (correctness first; lead, 2026-10-12). Desktop said video. It is routed to the music resolver either way. Found in phase 2 |
| PRV-8 which pages are music links | D | Only track, album and playlist links of Spotify, Apple Music and Deezer (with a valid id) are music links; other pages of those hosts (podcast shows and episodes, the home page) go to the download engine like desktop. Android treated every non-artist page of the host as a music link. Found in phase 2 |
| PRV-9 platform folder of an IPv6 host | A | Made file-system safe (`[__1]`); the desktop folder `[::1]` cannot be created on Windows. Found in phase 2 |
| PRV-10 link parsing, names and validation | D | One WHATWG-style parser for both apps: host case and IDN (punycode), default ports, user info, escapes and dot segments follow `new URL`. `validateUrl` returns the normalised link. Android no longer rejects links with spaces or quotes in them. `safeName` cuts at 80 code points (not UTF-16 units) and collapses all JavaScript whitespace. `videoId` follows the desktop rule (`gaming.youtube.com` has none, a longer `youtu.be` path takes its first eleven characters). Collection detection follows the desktop rules (SoundCloud, Bandcamp, Vimeo, Twitch, TikTok, Dailymotion, channel tabs). Unknown hosts keep a `m.` prefix in names; Pinterest, VK and more hosts are known |
| NAMING-1 library naming | P | Desktop (`outputSubfolders`, `outputStem`) and the Android library (`SpaceLogic`) name files differently: Android uses `Unknown artist`, 120-character names, only an exact ` - Topic` suffix, and repeats the artist when the title already starts with it. Both are kept in the core (`Naming.subfolders`/`stem` and `Naming.spaceSubfolder`/`spaceStem`) with one case set per app; merging them would move users' files and waits for the owner's decision |

## Music

| id | Decision | Note |
|---|---|---|
| MUS-1 strip "feat." credits before scoring | D | **Important**: this affects matching on Android |
| MUS-2 search the first two artists | D | |

## Dedupe

| id | Decision | Note |
|---|---|---|
| DED-1 "Artist - Title" split tier | A | Desktop adds it, with the same ±2 s duration and album rules |
| DED-2 title, artist and album noise rules | D | Android used its own rules (a different bracket, "- Topic" and feat. handling and an `(artist, title)` pair test); the desktop rules are now the only ones |
| DED-3 links to source ids | D | Links go through the core providers parser (`Providers.videoId`, `Providers.musicLink`), so PRV-10 applies: no id for `youtube-nocookie.com`, `/v/` paths or other YouTube sub-domains (desktop used to accept them) |
| DED-4 ISRC | new | Optional `isrc` on a recording: the same code (12 letters/digits, hyphens, spaces and case ignored) is the same recording, ranked after a shared source id and before the same bytes. Differing codes do not rule out a metadata match. Nothing in the apps supplies a code yet |
| DED-5 "feat"/"ft" need a word end | lead | Fixes a desktop bug both apps inherited: "feat" and "ft" split a credit only as whole words or with a dot, and "featuring" is one word. "Feather" stays one artist (was "her"); "A featuring B" gives a, b (was a, "uring b") |

## Versions

| id | Decision | Note |
|---|---|---|
| VER-1 accept only `-beta.N` prereleases | A | Strict on both |

## Playback

| id | Decision | Note |
|---|---|---|
| PLAY-1 one title order | A | "What plays next" in a folder, and the phone's natural title sort, now use the core's `Playback.compareTitles` on both apps: case and accents ignored, runs of digits (any script's, so Arabic-Indic too) compared as numbers. The desktop had used the browser's `Intl.Collator` (locale-aware), which can order punctuation and some letters differently; common code has no locale compare, so the Android rule wins. Added 2026-10-24 |
| PLAY-2 skips add up within 1000 ms of the last skip | new | The window counts from the last skip, so a steady run of taps keeps adding (+10, +20, +30); the other direction starts a new run |

## Spaces

| id | Decision | Note |
|---|---|---|
| SP-1 artist rule ignores " - Topic" | D | |
| SP-2 "youtube" source rule also matches the source id | D | |
| SP-3 drop domain-like tags | A | |
| SP-4 split "a, b" tags on commas | D | |
| SP-5 item key | — | Strip the fragment and trailing slash on both; whichever side doesn't, changes |
| SP-6 canonical stored source link | D | |
| SP-7 channel and its videos tab are one source | D | |
| SP-8 OLAK playlist is an album | D | Albums never follow |
| SP-9 RDCLAK watch link is finite | D | It is a curated playlist, not a mix |
| SP-10 reorder rejects an unknown or repeated id | D | Stale lists are rejected on both |
| SP-11 Following is opt-in (user feedback 2026-10-10) | **A (user)** | New collections are `off`. A playlist or channel follows only when the user turned on "Keep in sync" when downloading (mode `auto`, chosen interval, default 6 h) or later in Follow settings. Albums, radio mixes and own lists never follow. Stored collections keep their mode. Android drops its automatic default to match |
| SP-12 album rule ignores accents on both sides | A | The desktop folded only the album, so a rule value with an accent ("Björk Live") could never match; Android folds both. Found in phase 9 |
| SP-13 scope tag ties sort by code unit | P | The desktop used the browser's locale order for tags with equal counts; common code has no locale compare, so ties sort by code unit (A to Z for ordinary lower-case tags; only non-ASCII or punctuation-heavy tags, or tags that were not lower-cased, can sort differently). Found in phase 9 |
| SP-14 a stale default route falls back to the first folder space | D | Android fell back to "downloads" first; now the first folder space in hub order, as on desktop. The two differ only when a route points at a space that no longer exists and "downloads" is not first. Found in phase 9 |
| SP-15 smart rule values | D | Android now reads rule values like the desktop: values are trimmed and lower-cased; `collection` and `space` ignore case; `tag` does not strip `#` from a value; the artist rule reads `artist`, else `albumArtist`, and splits credits ("A feat. B & C" gives three artists); `source` matches the provider name or the id prefix, not the host; `addedWithin` with 0 days is not special-cased. Found in phase 9 |
| SP-16 tag cleaning | D | Android now cleans tags like the desktop (a value containing a site name or a leading `scheme://` is dropped wherever it appears; leading `#` removed). Android user tags are still trimmed of surrounding `#`, spaces, commas and semicolons first. Found in phase 9 |

## Download choice

| ID | Status | Decision |
|---|---|---|
| CHOICE-1 one size rule for the preview and the job | lead | `choice.ChoiceSize` decides the size shown before a download and the expected size of its job (`args.Labels.expectedSize` calls it), for both apps. A stream kept as it is (Original; Opus from an Opus source and M4A from an AAC source, both at Best) is the size of that stream. A conversion is its bitrate times the duration: the quality asked for, else 245 kbit/s for MP3, 256 for M4A, 160 for Opus at Best, 1411 for WAV. No duration: the source stream's size for a lossy format, nothing for WAV. FLAC has no estimate (before, the job showed the source's size, which a lossless file never is). A video is the size of its quality, and another audio track picked changes it. Every figure that is not one kept stream's own size is approximate. The phone's "up to" for MP3 at Best is gone: Best is the 245 kbit/s estimate like the desktop's. Fixtures: `fixtures/engine/choice/sizes.json`. Found in slice 2 of dev-docs/plans/DOWNLOAD-CHOICE.md (issue #5) |
| CHOICE-2 MP3 starts at 320 everywhere | owner | One song, a list and several links all start MP3 at 320 kbit/s on both apps (before: 320 for one song, Best for a list, and either for several links depending on the app). `DownloadPlan.preset("audio-mp3")` is MP3 at 320; M4A, Opus and FLAC presets keep Best. A person can still pick Best or another quality for one song. `choice.DownloadChoice.opening`, `forList` and `applyPreset` apply it. A link that lists nothing opens on Video (best available), the desktop's rule; a YouTube link that is not treated as music still opens as a video whatever the audio preset says. Fixtures: `fixtures/engine/choice/opening.json`. Found in slice 3 of dev-docs/plans/DOWNLOAD-CHOICE.md (issue #4) |
| CHOICE-3 one request, ranking stays per app | lead | `choice.DownloadChoice.request` builds what both apps send to the engine. A video sends the quality's height (none for 0), exact format id and frame rate, the container, and the codec of the quality when it is H.264, HEVC, VP9 or AV1, else `auto` (the phone's rule; the plan sorts by the codec when the exact id has expired, which `auto` could not narrow for MKV). The chosen audio language (`audioFormatId`) goes with a video that has no audio of its own; the phone never sent it before and has no language picker yet. Audio sends the quality only for MP3, M4A and Opus (the others `best`) and a source stream only for Original; no stream picked means the best one, the first the core ranks (the phone had picked the highest bitrate at open). Which formats the inspection lists stays per app on purpose: the phone prefers VP9 and skips DRM formats, Windows uses `auto` and lists DRM (fixture `formats/synthetic-media.json`). Fixtures: `fixtures/engine/choice/request.json`. Found in slice 4 of dev-docs/plans/DOWNLOAD-CHOICE.md (issue #7) |
| CHOICE-4 one set of words | lead | A quality row reads the height then the frame rate from 33 fps up (`1080p60`; 30, 31 and 32 fps are plain), through `choice.DownloadChoice.videoLabel` and `shownFps`; Windows had shown it from 31 fps in the row detail and the phone from 31 fps in the label. A size reads as `Labels.formatBytes` (1000 bytes is "1.0 KB"): the phone now calls it (it said "1000 B" and used 1024 steps), Windows' `formatBytes` keeps its own copy because it runs on every progress tick, and the shared fixtures (`args/labels.json`, op `bytes`) hold both to the same answers. The resolution badge (HD, FHD, 4K) is a phone-only feature. Fixtures: `fixtures/engine/choice/words.json`. Found in slice 5 of dev-docs/plans/DOWNLOAD-CHOICE.md (issue #6) |
