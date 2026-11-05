# lyrics

Lyrics from LRCLIB for downloaded songs: the requests, reading the answers, and what to do with an answer that did not work. All of it is decided by `app.keepfin.core.lyrics.Lyrics` (`core/`): desktop through `gui/shared/core/lyrics.ts` (used by `gui/desktop/engine/lyrics.ts`), Android through `LyricsLookup`. Each app does its own requests and its own waiting. Durations are seconds; waits are milliseconds.

| `input.op` | input | expected |
|---|---|---|
| `normalize` | `value` | the key titles and artists are compared by |
| `clean` | `title`, optional `artist` | the title used for the lookup |
| `getUrl` | `title`, `artist`, optional `album`, `duration` | the exact-match request, or `null` when the length is unknown or not above 0 |
| `searchUrl` | `title`, `artist` | the search request |
| `exact` | `record` (one LRCLIB record) | the lyrics in the exact-match answer, or `null` |
| `search` | `title`, `artist`, optional `duration`, `records` (the search answer) | the lyrics of the best record, or `null` |
| `retry` | `status` (HTTP status, or `null` for no answer or a timeout), `attempt` (tries made so far, from 1) | the wait before the next try, or `null` to stop |
| `isTimed` | `text` | whether the lyrics carry line times (`find.json`) |
| `preferTimed` | optional `exact`, `found` | the one that can follow the song; plain words only when nothing is timed |
| `busy` | `status` (or `null`) | whether the status means the service could not help just now (`find.json`): no answer, 429 and 5xx |
| `outcome` | `found`, `gaveUpOnBusy` | `found`, `busy` (nothing came and a request got no answer) or `none` |
| `outcomeWords` | `outcome` | the line for a search that ended: "No lyrics found", "Could not reach the lyrics service. Try again later.", and "Lyrics added." for `found` (a menu that shows nothing else; in the lyrics panel the lyrics appear) |
| `findWords` | none | the action: "Find lyrics" |

`exact` and `search` are given the answers as JSON text, the way each app receives them. A record has `trackName`, `artistName`, `albumName`, `duration`, `instrumental`, `plainLyrics`, `syncedLyrics`. `retry`: 404 and the other refusals (4xx except 429) stop at once; 429, every 5xx and no answer try again, three tries in all, after 2000 ms then 6000 ms.

Files: `normalize.json`, `clean.json`, `urls.json`, `choose.json` (exact and search answers, titles in several scripts), `retry.json`, `find.json` (Find lyrics on a song that has none).
