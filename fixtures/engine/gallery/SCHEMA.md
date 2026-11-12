# gallery

The gallery engine's `-j` output (a JSON list of message tuples) to a photo post. Decided by `app.keepfin.core.gallery.Gallery` (`core/`): desktop through `gui/shared/core/gallery.ts` (`gui/desktop/engine/gallery.ts`). Messages: `[2, directory]`, `[3, fileUrl, meta]`, `[-1, {error, message}]` (a failure the engine reports while exiting 0).

| input | expected |
|---|---|
| `url` (the link), `output` (the engine's output as text), optional `limit` (default 200) | `kind`: `post`, `failed` or `empty`. `post`: `title`, `uploader` (or null), `category`, `provider`, `previews`, `count`, `truncated`. `failed`: `message` |

Rules:

- Not a JSON list, or a list with no files and no failure message: `empty`. No files and a failure line: `failed` with the first non-blank message.
- Text fields are trimmed and non-blank, cut to 500 characters (60 for the category, 200 for names, 120 for description and content).
- Title: album title (directory album, else the first file's), directory title, file title, description, content, else `<Provider> post`. Category: the first file's, the directory's, else `gallery`. Uploader: author name, user name, username, uploader, owner.
- Previews: the first 12 files whose link starts with `https://` and whose extension (meta `extension`, else `ext`, else the link's, before any `?` or `#`) is jpg, jpeg, png, webp, gif, avif or bmp.
- `count` is the larger of the files read and the largest declared `count`; `truncated` when it is above `limit` or the files reach `limit`.

## Items and range

`post` also has `items`: every file read, in the engine's output order, capped at `limit`: `position` (1-based, what the engine's range selects), `url`, `photo` (the same image-extension rule as previews, without the `https://` requirement), and for a video (not a photo) `cover` when the file's meta has a `display_url` starting with `https://` (a video of a post has no image link of its own). A post can mix videos and photos (an Instagram carousel): videos arrive as `ytdl:` links, the engine hands them to the download engine when saving, and all of them are saved into the same folder.

`saved.json` rule `label`: input `photos` and `videos` (counts); expected `label`: "6 photos", "1 video", "2 photos, 8 videos". Rule `savedLabel` for a mixed label reads "7 of 10 items" while some files are missing.

`range.json`: input `chosen` (positions) and `count`; expected `range` (null when every position 1..count is chosen, else sorted and compact, for example `1,3-5`) or `invalid: true` for an empty list, a position outside 1..count, a repeat, or more than 500 values.

## Public posts

`public.json`, decided by `app.keepfin.core.gallery.PublicPost`: a post its site shows to signed-out visitors, read from the pages the download engine prints. Three inputs:

- `link`: expected `supports` (an Instagram `/p/<code>` link, also under a profile name).
- `pages` (the engine's output): expected `answer`, the line after the notice of the post request (base64), or null when the notice or the page is missing.
- `url` and `answer` (the decoded page), optional `limit`: the same result as a gallery read. Files come from `carousel_media` (else the post itself): a photo is its first `image_versions2` candidate (else `display_uri`); a video is its first `video_versions` link with the picture as `cover`. Title: the caption cut to 120, else `<Provider> post`; uploader: full name, else username. `empty` (the apps use the usual route) for unreadable or gated answers, a file without an `https://` link, and a post that is one video.

