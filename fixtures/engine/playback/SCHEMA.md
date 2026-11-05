# playback

Decisions every Keepfin player makes the same way on both apps: skips that add up, where playback resumes, chapters, which item plays next and the time label. Decided by `app.keepfin.core.playback.Playback` (`core/`): desktop through `gui/shared/core/playback.ts` (the video player in `gui/web/player/`), Android through `ui/player/VideoLogic.kt` and the video screen. Times are seconds; `now` is milliseconds.

| `input.op` | input | expected |
|---|---|---|
| `skips` | `steps`: list of `{delta, now}` | the label after each step (`"+10 s"`, `"−15 s"`); a skip the same way within 1000 ms of the last adds up |
| `announce` | `total` | the screen-reader text for a run |
| `resume` | `position`, `duration` (or null), `watched` | the start in seconds, or `null` (from the beginning) |
| `chapterAt` | `starts`, `time` | the index of the chapter playing, `-1` before the first |
| `chapterTarget` | `starts`, `time`, `dir` (-1 or 1) | where previous/next chapter goes, or `null` |
| `crossed` | `starts`, `from`, `to` | `true` when the two times are in different chapters |
| `list` | `context`, `folder` (or null) | the list next/previous follow: the context, or the folder when the context has one item |
| `neighbour` | `list`, `id`, `dir` | the next (1) or previous (-1) id, or `null` |
| `folderOrder` | `items`: list of `{id, title}` | the ids in natural title order (case and accents ignored, digits as numbers, ties by id) |
| `clock` | `seconds` | `"m:ss"` or `"h:mm:ss"` |
| `speeds` | (none) | the speeds offered |

Files: `skips.json`, `resume.json`, `chapters.json`, `next.json`, `labels.json`.
