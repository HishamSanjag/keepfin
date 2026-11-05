# args

Structured download intent -> a semantic plan. Both apps run `app.keepfin.core.args` (`DownloadPlan`, `Labels`). Desktop: `gui/shared/core/args.ts`, re-exported by `gui/shared/policy.ts` (`validateFormatRequest`, `validateOptions`, `presetFormat`, `formatArgs`, `jobQuote`). Android: `ArgsPolicy.kt` (`validate`, `preset`, `download`) and `Labels` in `Formats.kt`.

`format` uses the desktop wire shape: `{type: "video", maxHeight?, formatId?, audioFormatId?, fps?, codec (default "auto"), container (default "mp4")}` or `{type: "audio", target, quality (default "best"), formatId?}`.

`options` (all keys optional; a missing boolean is false, a missing `sponsorBlock` is `"off"`): `subtitles {enabled, languages, auto, embed}`, `embedThumbnail`, `embedMetadata`, `embedChapters`, `writeThumbnail`, `sponsorBlock` (`off|mark|remove`), `section {start?, end?}` (seconds).

| `input.op` | input | expected |
|---|---|---|
| `plan` | `format`, `options`, optional `squareCover` (a YouTube audio source) | the plan below |
| `args` | `format`, `options`, optional `squareCover`, optional `chapterTemplate` | the full list of arguments (format selection, then options), in order |
| `validateFormat` | `format` | `{valid}` |
| `validateOptions` | `options` | `{valid}` |
| `preset` | `preset` (`best`, `2160`, `1440`, `1080`, `720`, `480`, `audio-mp3`, `audio-m4a`, `audio-opus`, `audio-flac`), optional `container` (default `mp4`; video presets only) | video: `{type, maxHeight (null for best), codec, container}`; audio: `{type, target, quality}` |
| `presetValid` | `preset` | `{valid}` |
| `metadata` | optional `title` | the embedded-tag arguments for a download (`--parse-metadata` pairs) |
| `tags` | `title`, `artist`, `album`, `track`, `year` (Android resolver downloads) | the `--parse-metadata` pairs |
| `quote` | `format`, `video` (each `{formatId, height, fps, hdr, size, sizeApprox}`), `audio` (each `{formatId, size, sizeApprox}`), optional `duration`, optional `withSize` (default true) | `{label, expectedSize (bytes or null), approx}` |
| `bytes` | `bytes` | the size text ("143 MB", "1.5 KB"; empty for nothing usable) |

Files: `plans-*.json` and `arglists.json` (the arguments), `presets*.json`, `validation*.json`, `tags.json`, `labels.json` (the label and expected size, ARGS-13).

Since phase 4 of the shared core both apps build these arguments with `app.keepfin.core.args` (`DownloadPlan`: `validate`, `preset`, `plan`, `args`; `Labels`). The platform is data: `PlanPlatform(coverArtForOpusFlac)` is true on the desktop and the phone's engine setting on Android (ARGS-9). The desktop-only extras (`splitChapters`, `writeDescription`, `writeInfoJson`, the chapter template) are options of the core that Android does not set, so their cases are marked `desktop`. Tool paths, the JS runtime, network, cookies, multi-connection transfers and the progress/output flags are not in the plan: each app adds them around `DownloadPlan.args`.

The plan is built from the argument list the core returns for the request (no network, tool or output arguments), by this reading of the flags:

```
selector      value of -f, with bestaudio -> ba, bestvideo -> bv, best -> b
sort          value of -S, else null
merge, remux  --merge-output-format, --remux-video, else null
audio         when -x is present: {format: --audio-format (absent or "best" -> "original"),
                                   quality: --audio-quality for mp3|m4a|opus, else null}; otherwise null
subtitles     when --write-subs: {langs: --sub-langs, auto: --write-auto-subs present, embed: --embed-subs present}; otherwise null
embedThumbnail, writeThumbnail, embedMetadata, embedChapters   flag present
squareCover   --ppa present
sponsorBlock  "remove" for --sponsorblock-remove, "mark" for --sponsorblock-mark, else null
section       from --download-sections "*S-E": {start: number, end: number or null for "inf"}
```
