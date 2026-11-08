# compaction

Which of a device's own small segment files are merged into fewer larger ones, and which files a larger file of the same actor covers. Decided by `app.keepfin.core.sync.SegmentCompaction` (`core/`): desktop through `gui/shared/core/sync.ts`, Android directly. The design is `dev-docs/plans/KEEPFIN-DATA-COMPACTION.md` (decisions R3, R5, R9).

Input: `segments` (explicit `actor`, `first`, `last`, `bytes`, `signer`, `ok`, `named`) or `runs` (`actor`, `count` contiguous files of `ops` ops each, continuing after the actor's previous file; `skip` leaves a gap after it), `currentSigner`, `blocked` (actors with problems or equivocations), `op` (`plan` by default, or `subsumed`).

| file | expected |
|---|---|
| `plan.json` | `merges`: `<actor>:<first>-<last>:<number of input files>` in order. Tiers: 8 or more consecutive small files (under 256 ops) become files of up to 2,000 ops; 4 or more consecutive tier-1 files (256 to 2,000 ops), or any tier-1 file just made, become files of up to 5,000 ops. Only files signed by `currentSigner`, never across a gap, a named file or another signer; an actor with an unreadable file or an overlap, or in `blocked`, is left alone |
| `subsumed.json` | `subsumed`: `[index, by]` pairs: the file at `index` is contained in the larger kept file at `by` (same actor) |
