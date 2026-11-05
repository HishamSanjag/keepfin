# formats

yt-dlp info JSON -> video/audio options. Desktop: `gui/shared/formats.ts`. Android: `Formats.kt`.

| `input.op` | input | expected |
|---|---|---|
| `media` | `url`; the info as `info` (inline object) or `infoFile` (name in `samples/`); optional `pickVideo` / `pickAudio` (lists of video option ids / audio format ids: only those are returned, in that order, `null` for an id that does not exist; absent = the full list) | object: `provider`, `title`, `uploader`, `duration`, `isLive`, `chapters`, `playlistUrl`, `thumbnail`, `music` (`artist`, `album`, `track`, `releaseYear` or `null`), `videoIds` (all option ids, in order), `video` (list of `id`, `height`, `fps`, `hdr`, `codec`, `formatId`, `hasAudio`, `size`, `sizeApprox`), `audio` (list of `formatId`, `codec`, `language`, `size`, `sizeApprox`), `hasVideoOptions`, `hasAudioOptions` |
| `size` | `format` (a raw yt-dlp format dict), optional `duration` | `{size, approx}` (`size` null when unknown) |
| `codec` | `kind` (`video` or `audio`), `value` (raw codec string) | the codec name (`h264`, `hevc`, `vp9`, `av1`, `aac`, `opus`, `mp3`, `vorbis`, `flac`, `other`) |
| `title` | `title` (raw yt-dlp title, may be absent), optional `uploader`, `provider` (display name, e.g. `Facebook`), optional `kind` (`video` default, `audio`, `post`) | the clean title (a string) |
| `subtitles` | `info` | list of `{lang, auto}` |
| `pageUrl` | `url`; the info as `info` or `infoFile` | the page the link is known by |
| `heightLabel` | `height` | `720p`, or `Original` when the height is unknown (0) |

`size` (and `video[].size`) compare within 1 byte.

## Clean title rule (`title` op, also applied by `media` and by collection entries)

Posts (Facebook, Instagram, TikTok, X, Reddit) use their whole caption as the title. Both platforms apply the same rule where the title is created (`gui/shared/title.ts`, `TitleRules.kt`), so rows, notifications, library items, file names and tags get the short one:

1. Take the first non-empty line; collapse whitespace (ASCII whitespace plus nbsp, U+2000-200A, U+2028/9, U+202F, U+205F, U+3000, BOM).
2. Provider `Facebook` only: remove a leading `<n> views/reactions/comments/shares` list joined by `·` and ending in `|` (counts like `1.2K`, `3.4M`); then drop the text if it only equals the uploader (case-insensitive) and remove a trailing ` | <uploader>`. Other providers keep ` | ` untouched.
3. Remove URLs (`http(s)://` or `www.` up to the next space). If every word is a `#hashtag`/`@mention` (more than a bare mark) keep the first three; otherwise drop the trailing run of them (mid-sentence ones stay).
4. Repeatedly trim `- - - | · • :` and a trailing `...` or `…` at both ends (hyphen, en dash, em dash, pipe, middle dot, bullet, colon).
5. More than 100 code points: cut at the last space at or before index 100, but not before index 60 (else hard cut at 100), trim trailing separators, `,` and `;`, append `…`.
6. Nothing left: `<Provider> video` (`post` or `audio` by `kind`) plus ` by <uploader>` when known.

The full original text is not lost: with metadata embedding on, the description tag is the page description, else the original title text (`--parse-metadata %(description,title)s:%(meta_description)s`), and the title tag is the clean title.

## Implementation

Both apps run the shared core (`core/`, package `app.keepfin.core.formats`: `Formats`, `Title`). The info JSON goes in as text and is parsed once in common code; the desktop adapter is `gui/shared/core/formats.ts`, the Android one is `Formats.kt`. The only differences are data: `FormatOptions.codecPreference` (`auto` ranks av1 and vp9 equal, `vp9` ranks vp9 above av1: FMT-5) and `FormatOptions.skipDrm` (FMT-10). The desktop runs with `auto`/`false`, Android with `vp9`/`true`; a case limited to one platform says so in `platforms`.
