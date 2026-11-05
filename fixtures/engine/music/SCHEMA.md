# music

Matching a Spotify/Apple Music/Deezer recording to YouTube Music. Both platforms run these ops through the shared core (`core/`, package `app.keepfin.core.music`): desktop via the adapter `gui/shared/core/music.ts`, Android via `MusicFixtureRunner` (`android/app/src/sharedTest`). Source-page parsers (Spotify embed, Apple, Deezer) are not covered here; each side tests them against its own page captures.

`track`: `{title, artists (list), album?, durationSec?, trackNumber?, trackCount?, year?}` (position on the album, tracks on the source album, release year; used by the album ops). `candidates`: list of `{id (11 characters), title, channel?, artists?, durationSec?, origin? (ytm-song | ytm-flat | youtube)}`. Desktop uses `origin` and `artists`; Android uses `channel` (when absent it falls back to the artists joined) and ignores `origin`.

| `input.op` | extra input | expected |
|---|---|---|
| `best` | `track`, `candidates` | `{bestId (or null), minConfidence?}`; `minConfidence` is a floor on the winner's confidence (scores differ per platform, so exact values are never compared) |
| `alternates` | `track`, `candidates`, `exclude` (ids already tried or the original) | `{ids}`: candidates with confidence >= 0.6, within 7 s of the source length when both lengths are known, not excluded, one per id, best first, at most 3 |
| `query` | `track` | the search text |
| `songsApi` | `file` (a capture in `music/samples/`) or `data` (inline response JSON) | `{candidates: [{id, title, artists, album?, durationSec?, origin}]}`: the songs found in a YouTube Music songs-search response. A renderer without a usable video id or title is skipped; artists without links come from the first " • " group, split on commas and ampersands |
| `flat` | `file` or `data` (engine flat-search JSON with `entries`), `origin` | `{candidates: [{id, url, title, artists, channel?, durationSec?, origin}]}`: entries with a valid 11-character id and a title. `artists` is the channel (or uploader) without " - Topic", `channel` keeps it; a non-positive length is absent; `ytm-flat` links to music.youtube.com |
| `searches` | `track` | the ordered list of searches a platform runs: `{kind: "songsApi", query}`, then `{kind: "flat", target, origin: "ytm-flat", playlistEnd: 8}`, then `{kind: "flat", target, origin: "youtube", playlistEnd: 8}` |
| `pick` | `track`, `searches` (one entry per search: a list of candidates as in `best`, or `"fail"` for a search that errors) | `{bestId (or null), searchesRun}`: searches run in order, results accumulate, and the run stops once the best confidence so far is >= 0.8; `searchesRun` counts attempts, failures included. Equal confidence keeps the earlier result |

`songsApi` and `flat` take `file` names relative to `fixtures/engine/music/samples/`; runners skip directories named `samples` when loading cases. `songs-api.json`, `flat.json`, `searches.json` and `pick.json` were added for the shared core; their `expected` values are the desktop output, reviewed.

## Album ops

Matching through the album (`albums.json`): find the same album on YouTube Music, then the track at the same place on it. Captures are trimmed real YouTube Music responses in `samples/` (`ytmusic-search-albums-*.json` for the albums search, `ytmusic-album-*.json` for an album page); `albums.json` expected values for `albumSearch` / `albumPage` were generated from the captures by a script independent of the core, then reviewed.

| `input.op` | extra input | expected |
|---|---|---|
| `albumQuery` | `track` | `{applies, query?}`: `applies` is false when the track has no (or a blank) album; `query` is the first artist then the album title |
| `albumSearch` | `file` or `data` (an albums search response) | `{albums: [{id (MPREb_ browse id), title, artists, year?, kind?}]}`: `kind` is Album, EP or Single. Rows without an album id or title are skipped |
| `albumPage` | `file` or `data` (an album browse response) | `{title, artists, year?, trackCount, tracks: [{id, title, artists, durationSec?, position}]}` or `null` when the response holds no album. `trackCount` counts every row; a row without a playable video is skipped but keeps its numbering; a track row without artists takes the album's artists; `position` is the row's number |
| `albumHint` | `track`, `candidates` (songs-search results with `album`) | `{album}`: for a track without an album, the album of the best candidate (at or above the minimum confidence); otherwise `null` |
| `albumRank` | `track`, `search` (an albums-search capture in `samples/`) | `{ids}`: the albums worth fetching, best first. Another title (without edition words), another artist, a release year more than one year away, or a different edition (deluxe, anniversary, drumless, "Taylor's Version"... either way round) is not worth fetching |
| `album` | `track`, `search` (capture), `pages` (browse id -> capture file, or `"fail"` for a page that cannot be read) | the album route end to end: `{kind: "matched" \| "none", browseId, albumTitle, position, videoId, title, fetched}`. The runner loops: ask `AlbumMatching.step` for the next step, fetch (read the capture) the page it names, ask again. `fetched` lists the album ids fetched in order (at most two); a `kind: "none"` hands over to the songs search |

## Music videos

A song's official music video (`videos.json`, `MusicVideos`, dev-docs/plans/MUSIC-VIDEOS.md). Captures are trimmed real YouTube Music videos-search answers in `samples/` (`ytmusic-search-videos-*.json`: the first six rows, without pictures, menus or tracking). Expected values were decided by hand from a listing of each capture made by a script independent of the core, then reviewed.

| `input.op` | extra input | expected |
|---|---|---|
| `videosParse` | `file` or `data` (a videos-search answer) | `{candidates: [{id, type, title, artists: [{name, browseId}], views?, durationSec?}]}`: `type` is the music video type without `MUSIC_VIDEO_TYPE_` (OMV, UGC, ATV, OFFICIAL_SOURCE_MUSIC...) or "" when absent; `views` is exact ("3.2B views" is 3200000000). A row without a video id or a title is skipped |
| `videoBest` | `file` or `data`, `artists`, `title` | `{videoId}` or `{videoId: null}`: an official video (OMV) among the first six rows, by one of the song's artists (each credited artist counts), not Official Audio, lyric, Visualizer, cover, slowed... (unless the song's own title says so), whose title without decorations and featuring credits is the song's title with the same qualifiers (live, remix, acoustic...). Several: the most watched |
