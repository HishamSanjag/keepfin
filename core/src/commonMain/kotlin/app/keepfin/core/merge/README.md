# M1: pure library merge planner

The contract is dev-docs/plans/LIBRARY-MERGE-CONTRACT.md sections 3, 5 and 7. This package performs no I/O or clock reads.
LibraryMapping owns field encoding, numeric scaling, collections, follows and space encoding. MergeFieldRules names the decisions;
FieldCoverageTest scans both its field writers and every library model property.

## Host protocol

1. Freeze the input views for this pass (including T's fields, item.add clocks and current revision hashes). Construct MergeConfig
   with a stable mergeId, B's new T actor and replica, and firstSpaceOrder after T's existing spaces.
2. Supply THashIndex: lookup returns ids ordered by (item.add HLC, id), item fetches a view, sameName returns every exact-name pair
   in stable id order. Use an indexed on-disk table rather than scanning T for every S item.
3. Supply MergeLedger backed by scratch storage: firstSource/rememberContent index hashes encountered in S; mapped/record provide
   indexed access to the S-to-T map. Start with fresh scratch data for each planning pass. The in-memory adapters are for tests and
   small libraries. The planner itself retains only its last ordering key, counters and at most 500 drafts.
4. Call items(page) in strict (item.add HLC, id) order across pages. SMetadata can accompany each item through a callback; the LibItem
   already carries the same favourites/tags/plays/playback fields. An explicit SMetadata play total replaces the record total.
   A rejected page emits no drafts/map/review. Re-hash each NeedsHash against the size/mtime just observed and resubmit that page.
   If the read fails, resubmit readable=false. Hashes supplied by the host must describe the observed size.
5. After all item pages, call spaces, playlists and artwork in a fixed order, then finish. Send complete entities within metadata
   pages (including ordered entries); mapping may temporarily materialise the drafts of one entity. Item references must be in S's
   map; a dangling reference fails instead of silently dropping metadata. All source spaces, including built-ins, get fresh identities.
   T's routes and defaults stay unchanged. Source space order is preserved after firstSpaceOrder. Smart-rule references are remapped
   by their reference fields, including collection/space/item values; free text stays unchanged. seen values are external source ids,
   whereas new/removed sets contain mapped item ids. Embedded follows travel with their collection. Optional playlist rules can be
   supplied through the rules callback. Media sidecars stay at their paths; records sidecars/artwork are ArtFile copy instructions
   into records/art, with original source paths and remapped owner ids.
6. Stream MergeSink outputs to a plan/journal. A batch hashes the canonical UTF-8 JSON array of {body,kind}, without LF.
   Persist the receipt before applying the batch, including actorSeqBefore as required by the contract. remainingDrafts(batch,m)
   validates its hash and returns exactly the unapplied suffix. Never re-run imports as increments.
   Sink/ledger failure aborts a planning pass. To regenerate after failure, start at the beginning with fresh scratch data; stable
   inputs and mergeId reproduce identical batches. Applying/resuming a saved plan uses receipts, not a partly populated planner.

Readable files trust the revision only when both size and mtime hints match. Missing/unreadable files become their own mapped items
without placements even if their recorded hash matches T. Several T matches emit one manyMatches review per alternative and choose
the oldest; repeated S content always gets separate items, including recorded revisions whose file is missing. Paths are relative to B and never rewritten.

On linked items, T's ordinary fields stay; differing S fields are review entries. Playback registers use explicit per-field HLCs
when supplied; otherwise lastPlayedAt dates the playback snapshot and T wins ties. Favourites/user tags are set additions.
The play count is an absolute plays@B-actor counter equal to S's total, so applying it again cannot add it twice.

## Reverse and Separate

Use a fresh MergePlanner configured for S's actor/replica and S's index/ledger. reverseItems selects only items added by B's T actor
with addSeq > activationSeq; it uses the exact same rules as forward items. Supply any relevant new entities through the metadata
methods. Existing items' post-activation edits are excluded.

SeparatePlanner implements section 7.2 only: pass map pages, current T placements and per-target Keep/Remove choices. Default Keep;
items introduced by the merge with no other replica holding them are deleted; Remove also deletes introduced items held elsewhere.
Linked items are never deleted. All remaining B placements are cleared. Deletes require current observed add tags and revision heads.
This only returns metadata drafts. Fencing B's executors, durable publication, acknowledgement, trash restoration and pointer switches
belong to M2/M3; the host must enforce them before applying these drafts.

## Desktop facade

core/src/jsMain/kotlin/app/keepfin/core/merge/Facade.kt exports NetMergePlanner, NetMergeSeparatePlanner and netMergeRemainingDrafts.
All callbacks are synchronous, cursor-based, and exchange canonical JSON strings. Cursor null means end, null afterId means first.

Item pages are arrays of:
- id, added (HLC wire), revisionHash, size, mtimeHint, placements (replica -> relative path), fieldClocks (field -> HLC wire)
- fields: existing sync-view field object (duration/position in milliseconds), plays, favourite, userTags
- Source only: pathOnB, file: {readable,size,mtime,hash?}, addActor/addSeq for reverse filtering

Metadata input is the existing views shape {playlists,spaces,sets}; rows are present:true with fields, and playlists have songs:[{item}].
Artwork pages are [{ownerKind:"item"|"playlist"|"space",ownerId,source,name}]. See generated fixtures for complete examples.
Targets and source pages must represent a consistent planning snapshot; concurrent changes require host revalidation.

## Verification

Regenerate fixtures with node fixtures/merge/generate.mjs from the repository root. It never runs/imports the planner.
Run gradlew.bat jvmTest jsNodeTest from core, then npm run core:build at the repository root.
The full scale test runs 200,000 S against 200,000 T on both JVM and Node, with a 60-second planning budget and 200-item pages.
It reports index construction separately; the in-memory index and ledger intentionally retain library-sized data.

## Verification recorded 2026-10-21

Final source checks (Windows PC, JDK 21; PATH quotes stripped and compiler execution set to in-process for this sandbox):

- From core: gradlew.bat jvmTest jsNodeTest. JVM: 219 tests, 0 failures, 0 errors, 0 skipped.
  Node: 221 tests, 0 failures, 0 errors, 0 skipped. Seven merge test methods include 20 generated merge scenarios and one Separate
  scenario. Every recovery prefix is checked for both a 500-draft batch and its 40-draft tail.
- From the root: npm.cmd run core:build. npm.cmd selects the same npm CLI without PowerShell's blocked npm.ps1 entry point.
- The built, minified JavaScript facade was exercised through its public callbacks against all 20 merge fixtures and the Separate
  fixture, including receipt-suffix exports. All passed.
- The fixture generator was rerun and its output compared byte-for-byte. No differences.

Selected actual output:

    BUILD SUCCESSFUL in 3m
    20 actionable tasks: 13 executed, 7 up-to-date

    MERGE SCALE S=200000 T=200000: index 473 ms; plan 3284 ms; drafts 400000; map 200000; review 100000; page=200 batch<=500
    MERGE SCALE S=200000 T=200000: index 953 ms; plan 7312 ms; drafts 400000; map 200000; review 100000; page=200 batch<=500

    BUILD SUCCESSFUL in 26s
    15 actionable tasks: 8 executed, 7 up-to-date
    core:build: copied 2 files to build\core-js (minified)

The first scale line is JVM; the second is Node. Both full-scale cases ran, neither was skipped. Index construction is reported
separately from planning. These are PC measurements; no phone benchmark or platform integration/device test was run.
Production SQLite/disk adapters, durable application, executor fencing, physical artwork copying, acknowledgement and activation
remain M2/M3 responsibilities. M1 supplies their interfaces, copy instructions and deterministic plans.
Existing Gradle deprecation, expect/actual and unsigned-type warnings remain outside this change.
