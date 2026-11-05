# progress

Engine progress steps -> aggregated progress. Since phase 5 of the shared core both apps run `app.keepfin.core.progress.ProgressParser` (`core/`): desktop through `gui/shared/core/progress.ts` and `gui/desktop/engine/progress.ts`, Android through `ProgressParser.kt`. The platform only differs in data: the wire format of the progress line (`ProgressWire`) and Android's aria2c lines.

`input`: `steps` (list) and optional `streamSizes` (map of format id -> inspected bytes; desktop passes it as `formatSizes`, Android as `expectedSize` = the sum) and `audioTarget` (desktop only needs it for the conversion phase name).

A step is either
- `{type: "line", text}`: a raw log line, fed to the parser as is; or
- `{type: "progress", status (downloading|finished), formatId, downloaded, total?, estimate?, speed?, eta?, fragmentIndex?, fragmentCount?}`: a progress event. The runner renders it in the platform's own template (the wire formats differ: desktop `KEEPFIN-P|id|status|downloaded|total|estimate|speed|eta|fragIndex|fragCount`, Android `[keepfin] status|downloaded|total|estimate|speed|eta|id`). A missing or null number is rendered as `NA`.

`expected`: a list of checkpoints `{after: <step index>, percent?, downloadedBytes?, totalBytes?, speed?, eta?, phase?, processing?}` compared after that step (subset match; `percent`, `speed` and `eta` within 1). `phase` is a key, never the visible wording: `merge`, `extract-audio`, `embed-cover`, `convert-cover`, `metadata`, `embed-subs`, `convert-subs`, `sponsorblock`, `modify-chapters`, `split-chapters`, `fixup`, `remux`, `convert-video`, `move-files`, or `null` for none.

Android-only: aria2c lines (`platforms: ["android"]`).
