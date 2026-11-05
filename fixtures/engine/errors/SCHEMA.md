# errors

Failure text -> class and retry kind. Desktop: `friendlyError`, `scrubToolNames`, `errorDetailText` in `gui/shared/errors.ts`. Android: `ErrorMessages.kt` (`friendly`, `classify`, `scrub`, `detail`), `RetryPolicy` in `Retry.kt`.

| `input.op` | input | expected |
|---|---|---|
| `classify` | `text` (raw engine stderr/log) | `{class, retry}` |
| `scrub` | `text` | `{noToolNames: true}` |

Classes: `botCheck`, `ageRestricted`, `signInRequired` (private, members-only, login wall), `rateLimited`, `regionBlocked`, `notStarted`, `removed`, `unavailable` (the generic "didn't return this video"), `unsupported`, `drm`, `formatUnavailable`, `extractorBroken`, `forbidden`, `diskFull`, `permission`, `processorMissing`, `processing`, `cookiesUnreadable` (desktop only), `network`, `unknown`.

Retry kinds: `permanent` (no automatic retry), `retryable` (plain backoff 5/15/30 s), `engine` (refresh the engine, then retry), `rateLimited` (60/180/300 s), `botCheck` (one retry after 120 s).

Every `classify` and `scrub` case also asserts that the user-facing message (and the detail text) matches none of `yt-dlp|yt_dlp|youtube-?dl|ffmpeg|ffprobe|gallery-dl|aria2`, case-insensitive. The class is derived from the platform's message (the runner holds a table of message fragments -> class); wording is never compared.

## `retry` (added in phase 6)

| `input.op` | input | expected |
|---|---|---|
| `retry` | `text`, `retriesUsed` (automatic retries already scheduled), `crashed` (optional; the engine process died, so only exception text exists) | `null` (no automatic retry) or `{retry, delayMs, engineRelated, rateLimited, refreshEngine}` |

At most three retries. Delays: 5/15/30 s (`retryable`, `engine`), 60/180/300 s (`rateLimited`), one retry after 120 s (`botCheck`). `refreshEngine` is true from the second retry of an engine failure (and at once for the failures that only get one retry). Platform differences are data: Android refreshes the engine on a 403 (ERR-3) and gives an unrecognised failure or "no formats found" one engine refresh and one retry (`platforms` cases); the desktop keeps the full budget. `retry` cases do not carry `noToolNames`.

The core also classifies the text the Android app adds after it confirmed a regional restriction (`keepfin-restricted:music` / `:other`) as `regionBlocked`, permanent.
