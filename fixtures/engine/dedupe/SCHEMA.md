# dedupe

Skip-existing decisions, decided by the shared core (`app.keepfin.core.dedupe.Recordings`). Desktop: `gui/shared/core/dedupe.ts`, re-exported by `gui/shared/library.ts` (`sourceIdFromUrl`, `sameRecording`, `normalizeTitle`, `normalizeArtists`, `matchExisting`). Android: `SourceIds`, `Recordings` in `LibraryLogic.kt` delegate to it. (Computing the content key from a file is done at import time and is not covered; comparing two keys is, in `tiers.json`.)

A recording is `{artist?, title, album?, duration? (seconds)}`.

| `input.op` | input | expected |
|---|---|---|
| `sourceId` | `url` | the stable id (`youtube:<id>`, `spotify:<id>`, `apple-music:<id>`, `deezer:<id>`) or `null` |
| `same` | `a`, `b` (recordings) | `{match}`: the same recording by metadata (tier 3) |
| `existing` | `items` (list of `{id, space, title, artist?, albumArtist?, album?, duration?, sourceId?, missing?}`), `probe` (`{sourceId?, recording?}`) | `{itemId, by}`: the first item skip-existing would flag (`by` is `source` or `metadata`), both `null` when none; a source id match beats a metadata match; `missing` items never match |
| `normalizeTitle` | `title`, `artist?` | the normalised title string |
| `normalizeArtists` | `artist` | the list of normalised artists |
| `compare` | `a`, `b` (recordings that may also carry `sourceIds` (list), `contentKey`, `isrc`) | `{tier}`: `source`, `isrc`, `content`, `metadata` or `null`, strictest first |

`existing` items and probes may carry an `isrc`; the result's `by` can then be `isrc`. A recording code compares without hyphens or spaces, ignoring case; one that is not 12 letters/digits is ignored; two different codes do not rule out a metadata match.

Files: `source-ids.json`, `source-ids-more.json`, `same-recording.json`, `same-recording-more.json`, `titles.json`, `artists.json`, `tiers.json`, `existing.json`.
