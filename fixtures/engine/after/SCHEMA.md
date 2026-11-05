# after

What happens when a download finishes: the ordered steps and what a failure of each one means, decided once by `app.keepfin.core.after.AfterDownload` (`core/`). Desktop through `gui/shared/core/after.ts` (`gui/desktop/manager/afterDownload.ts`), Android through `AfterDownloadSteps.kt`. Plan: `dev-docs/plans/AFTER-DOWNLOAD.md`.

`input.op` is always `plan`. `input` is the facts of a finished job:

| key | meaning | default |
|---|---|---|
| `audio` | the job downloads audio (lyrics apply to audio only) | false |
| `hasFile` | a file was downloaded; false for a recording that was already in the library | true |
| `existing` | the recording was already in the library (nothing was downloaded) | false |
| `video` | `none`, or the fate of a music video download: `attach` (its song is in the library), `wait` (its song is still on its way), `ordinary` (no song to take it) | `none` |
| `playlist` | the review chose a playlist to join | false |
| `lyrics` | the lyrics setting is on | false |
| `library` | `folder` (the Keepfin folder library) or `legacy` (the older library; a music video has no song to go beside there) | `folder` |

`expected` is `steps`: the steps in the order they run, each `{kind, onFailure, fallback}`.

Kinds: `lyrics`, `save`, `attachVideo`, `waitForSong`, `joinPlaylist`, `cover`, `notify`, `notifyExisting` ("Already in ..."), `settleVideos` (videos that waited for this song are kept beside it; a song that asked for its video looks for it).

`onFailure`: `continue` (the job still completes), `notSaved` (the job completes as "Not saved" with Save again; the steps that need the saved item find none), `saveOrdinary` (the video is saved as an ordinary download instead). `fallback` is true on the step that runs only when the one before it declined or failed.

Files: `plan.json`.
