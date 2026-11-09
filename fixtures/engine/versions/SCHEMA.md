# versions

Release ordering, update channels and release-notes highlights. Since phase 8 of the shared core all of it is decided by `app.keepfin.core.versions.Releases` (`core/`): desktop through `gui/shared/core/versions.ts` (re-exported by `gui/shared/updates.ts`), Android through `UpdateRelease.newer` and `updateHighlights`. Versions are written without a `v` prefix. Only `X.Y.Z` and `X.Y.Z-beta.N` are versions.

| `input.op` | input | expected |
|---|---|---|
| `compare` | `a`, `b` | the sign of a - b: `-1`, `0` or `1` (text that is not a version sorts lowest) |
| `pick` | `channel` (`stable` or `beta`), `current`, `versions` (list, any order) | the version the channel offers, or `null` (nothing newer than `current`) |
| `valid` | `value` | `true` when `value` is a version |
| `whatsNewDue` | `seen` (the last version whose notes were seen, or `null`), `current` | `true` when `current` is newer than `seen`; `false` for a fresh install (`null`), the same version, a downgrade or unreadable text |
| `highlights` | `notes`, `platform` (`windows` or `android`), optional `limit` (default 3) | the list of short plain-text bullets for the update dialog |

Files: `compare.json`, `channels.json` (includes the 0.5.5-beta.N upgrade paths), `valid.json`, `highlights.json`, `whatsNew.json`.
