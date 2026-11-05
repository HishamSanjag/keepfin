# choice

The download choice: what a preview offers, opens with and estimates, decided once by `app.keepfin.core.choice.DownloadChoice` (`core/`). Desktop through `gui/shared/core/choice.ts` (`gui/web/capture/choice.ts`, the preview), Android through `ui/Formatting.kt` (`FormatChoice`) and the preview screens. Plan: `dev-docs/plans/DOWNLOAD-CHOICE.md`.

`media` is what the inspection found, reduced to what the choice needs: `duration` (seconds, or null), `video` (a list of `{id, height, fps, hdr, codec, hasAudio, formatId, size, sizeApprox}`, best first) and `audio` (a list of `{formatId, codec, size, sizeApprox}`, best first). Every key but `id` and `formatId` may be left out (height 0, fps null, hdr false, hasAudio false, size null, sizeApprox false, codec "other").

`choice` is the picks: `mode` (`video` or `audio`), `videoId`, `container`, `target`, `quality`, `audioTrack`, `audioSource`. Left out: mode `video`, container `mp4`, target `mp3`, quality `320`, the others null.

| `input.op` | input | expected |
|---|---|---|
| `offer` | `media`, `choice` | `containers`: the containers offered in order, each `{value, allowed, reason}` (`reason` null when allowed) |
| `size` | `media`, `choice` | `{bytes, approx}`: the size of what the choice downloads, `bytes` null when nothing says; the rule is in `sizes.json` |
| `opening` | `media`, `start`: `{preset, url, music, youtubeMusic}` (`music` true for a song matched from a music service, `youtubeMusic` `audio` or `video`, default `audio`) | the choice a preview opens with (a subset of the choice keys) |
| `forList` | `preset` | the format a list or several links download: `{type, target, quality}` for audio, `{type, maxHeight, container, codec}` for video |
| `applyPreset` | `media`, `choice`, `preset` | what a preset makes of one link's choice; the choice stays as it is when the link has nothing of that kind |
| `pickTarget` | `choice`, `target` | the choice after picking an audio format: MP3 starts at 320, another format keeps the quality asked for; the picked source stream is cleared |
| `modes` | `media` | the modes the link offers, `["video","audio"]` in that order |
| `pickVideo` | `media`, `choice`, `videoId` | the choice after picking that quality: the container goes back to `mp4` when the new quality cannot use it |
| `request` | `media`, `choice` | what the choice sends to the engine: `{type:"video", maxHeight, formatId, audioFormatId, fps, codec, container}` or `{type:"audio", target, quality, formatId}` |
| `videoLabel` | `height`, `fps` | the words on a quality row: the height, the frame rate from 33 fps up, "Original" for no height |

Files: `containers.json`, `sizes.json`, `opening.json`, `request.json`, `words.json`.
