# providers

URL -> provider identity, routing and naming. Since phase 2 of the shared core both apps run the same code, `core/src/commonMain/kotlin/app/keepfin/core/providers` (`Url`, `Providers`, `Naming`); the desktop reaches it through `gui/shared/core/providers.ts`, Android through `app.keepfin.Providers` and `SpaceLogic`. The desktop runner is `gui/shared/fixtures.test.ts`, the Android runner is `ProvidersFixtureRunner` (JVM unit test and device test) and the core runs every case on the JVM and on JavaScript (`core/src/commonTest/.../ProvidersFixtures.kt`).

Link parsing follows the WHATWG URL Standard (what `new URL` does), the desktop behaviour, not `java.net.URI`.

| `input.op` | input | expected |
|---|---|---|
| `name` | `url`, optional `extractor` (an extractor key) | provider name (`YouTube Music`, `YouTube`, `X`, ... else the extractor or the host) |
| `folder` | `url` | platform output folder (made safe as a folder name) |
| `musicService` | `url` | `spotify`, `apple-music`, `deezer` or `null` (track, album or playlist links and the short links; not YouTube Music) |
| `musicLink` | `url` | parsed link `{service, type, id, storefront?}` or `null` |
| `collection` | `url` | whether the link is certainly a collection (playlist, channel, album) |
| `playlistUrl` | `url` | the playlist url a watch link belongs to, or `null` |
| `preferAudio` | `url`, optional `youtubeMusic` (`audio`, the default, or `video`) | whether the link defaults to the Audio tab |
| `gallery` | `url` | image-post host |
| `videoId` | `url` | the 11-character YouTube video id, or `null` |
| `safeName` | `value`, optional `max` (default 80) | a name safe as a file or folder name |
| `validateUrl` | `value` | `{valid, href?}`: validity and the normalised link (`href`). The error text differs per app and is not compared |
| `url` | `value` | the parsed link `{scheme, username, password, host, port, path, query, fragment, href}` or `null` when `new URL` would throw. `host` is `null` without an authority (`javascript:`, `mailto:`); `port` is `null` for a default or missing port; an empty `query` or `fragment` is `null` |
| `isoDate` | `millis` | `YYYY-MM-DD` (UTC) |
| `channelArtist` | `value` | an artist without a ` - Topic` or `VEVO` suffix, or `null` |
| `outputSubfolders` | `layout` (`flat`, `collection`, `platform`, `artist-album`), `job` `{url, title, uploader?, createdAt, group?: {title}, music?: {artist?, album?, track?}}` | list of folder names (desktop library) |
| `outputStem` | `naming` (`title`, `title-uploader`, `uploader-title`, `date-title`, `artist-title`), `job` | file name stem (desktop library) |
| `spaceSubfolder` | `layout`, `context` `{title, uploader?, artist?, album?, url?, groupTitle?, groupKind?, audio?, dateMillis?}` | sub-path `""` or `A/B` (Android library) |
| `spaceStem` | `naming`, `context` | file name stem (Android library) |

The two libraries name files differently on purpose-less details (NAMING-1 in `DECISIONS.md`), so `outputSubfolders` / `outputStem` cases are desktop cases and `spaceSubfolder` / `spaceStem` cases are Android cases (`platforms`); the core implements both.

Files: `names-and-folders.json`, `routing.json`, `music-links.json`, `safe-names-and-urls.json` (the original 93), `urls.json` (URL parsing edge cases), `url-routing.json` (routing on links with ports, user info, escapes, fragments, trailing dots, IDN), `validate-urls.json`, `safe-names-more.json`, `naming.json`, `shared-core.json` (decisions PRV-7 to PRV-9).
