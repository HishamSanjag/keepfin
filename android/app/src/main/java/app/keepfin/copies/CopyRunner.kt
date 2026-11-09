package app.keepfin.copies

import app.keepfin.core.copies.BaselineEntry
import app.keepfin.core.copies.Capabilities
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.core.copies.Evidence
import app.keepfin.core.copies.fileSizeLimit
import app.keepfin.core.copies.fileTooLargeWords
import app.keepfin.core.copies.onlineOnlyWords
import app.keepfin.core.copies.ReviewKind
import app.keepfin.core.copies.Generation
import app.keepfin.core.copies.HashRequest
import app.keepfin.core.copies.InvLine
import app.keepfin.core.copies.PlaceOptions
import app.keepfin.core.copies.PlacementMove
import app.keepfin.core.copies.Plan
import app.keepfin.core.copies.PlanOutcome
import app.keepfin.core.copies.PlanSummary
import app.keepfin.core.copies.Planner
import app.keepfin.core.copies.PlannerInput
import app.keepfin.core.copies.ReviewItem
import app.keepfin.core.copies.Side
import app.keepfin.core.copies.SimpleLibraryFacts
import app.keepfin.core.copies.ActionKind
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The hashes a planning round asked for, kept by the stat they were computed at (a hash is valid for that stat). */
internal class HashCache {
    private class Entry(val contentId: String, val size: Long, val mtimeMs: Long)

    private val map = HashMap<String, Entry>()

    @Synchronized
    fun set(side: Side, path: String, size: Long, mtimeMs: Long, contentId: String) {
        map["$side|$path"] = Entry(contentId, size, mtimeMs)
    }

    @Synchronized
    fun get(side: Side, path: String, size: Long, mtimeMs: Long): String? = map["$side|$path"]?.takeIf { it.size == size && it.mtimeMs == mtimeMs }?.contentId

    @Synchronized
    fun lookup(side: Side, path: String): Triple<String, Long, Long>? = map["$side|$path"]?.let { Triple(it.contentId, it.size, it.mtimeMs) }
}

internal class RunConfig(
    val main: FolderAccess,
    val copy: FolderAccess,
    /** Device-local folder for this relationship's baseline, journal and scratch files. */
    val stateDir: File,
    val placeId: String,
    val mode: CopyMode,
    val device: String,
    /** The desired set D of the place; any order. */
    val desired: Sequence<DesiredEntry>,
    val deleted: Set<String> = emptySet(),
    val placementMoves: List<PlacementMove> = emptyList(),
    val confirmedDeletes: Set<String> = emptySet(),
    val holdsKey: String = "",
    val optionsKey: String = "",
    val deleteGuardPercent: Int = 10,
    /** The place carries lyrics, subtitles and info files; off, those already on the copy stay. */
    val companions: Boolean = true,
    /** Songs' music videos go with them (the place's effective value: what the person chose, else whether the copy holds everything). */
    val musicVideos: Boolean = true,
    /** The user confirmed the preview of a new relationship. */
    val previewConfirmed: Boolean = false,
    /** The user confirmed a plan that the deletion guard held. */
    val confirmHeld: Boolean = false,
    /** Plan only (the preview of Add a copy): nothing is executed, no generation bumped, no baseline or manifest written. */
    val dryRun: Boolean = false,
    /** A full audit: hash every file on the copy. */
    val audit: Boolean = false,
    /** 2000 on FAT and exFAT (a memory card), 1 on NTFS. */
    val granularityMs: Long = 2000,
    val host: MainHost? = null,
    val parallelism: Int = 2,
    val maxFileBytes: Long? = null,
    /** The file system of the copy's volume (`vfat`, `exfat`, `ext4`...), when the host can tell: FAT limits a file to 4 GiB. */
    val volumeFormat: String? = null,
    val maxRounds: Int = 3,
    val now: () -> Long = System::currentTimeMillis,
    val runId: String? = null,
    val fault: FaultHook? = null,
    val abort: () -> Boolean = { false },
    val onProgress: ((Progress) -> Unit)? = null,
    /** Sort-run size; small values exercise the external sort in tests. */
    val sortChunk: Int = 50_000,
    /** Set when the copy nests with the Keepfin folder (the host knows both addresses). */
    val refusal: String? = null,
    val mainReplica: String? = null,
    val copyReplica: String? = null,
    val lockHeartbeatMs: Long = CopyLock.HEARTBEAT_MS,
    val lockStaleMs: Long = CopyLock.STALE_MS,
    /** Lets a listing that looks too small be listed again quickly in tests. */
    val relistDelayMs: Long = 300,
    /** Where a "derived export" is excluded from the copy's inventory. */
    val derivedFilter: ((String) -> Boolean)? = null,
)

internal class GenerationInfo(var copy: Long?, var relationship: Long?, var state: Generation, var committedAt: Long?)

internal class RunReport {
    /** done, paused, disconnected, crashed, busy, held, refused, preview, diskFull */
    var status = "done"
    var reason: String? = null
    var away: Away? = null
    var rounds = 0
    var generation = GenerationInfo(null, null, Generation.Equal, null)
    var recovery: RecoveryReport? = null
    var main: InventoryResult? = null
    var copy: InventoryResult? = null
    var summary: PlanSummary? = null
    var held = false
    var holdReason: String? = null
    var additiveOnly = false
    var review: List<ReviewItem> = emptyList()
    var reviewCount = 0
    var exec: ExecReport? = null
    var manifest: ManifestResult? = null
    var hashed = 0
    val timings = LinkedHashMap<String, Long>()

    /** Why every file was hashed this run: `asked`, or `clock` (a time zone or daylight saving shift was seen). */
    var auditReason: String? = null
    var auditHours = 0L
    var copyFormat: String? = null
}

/**
 * One sync run of one relationship (main folder, copy folder, place): recognise and lock the copy, recover an interrupted run,
 * inventory both sides, plan (hashing what the planner asks for), execute, fold the verified results into the baseline and write the
 * coverage manifest. Port of `gui/desktop/copies/run.ts`; the planner is the shared core's.
 */
internal object CopyRunner {
    fun run(cfg: RunConfig): RunReport {
        val report = RunReport()
        val now = cfg.now
        val runId = cfg.runId ?: ("r" + java.lang.Long.toString(now(), 36) + java.util.UUID.randomUUID().toString().take(4))
        fun timer(name: String, start: Long) {
            report.timings[name] = (report.timings[name] ?: 0L) + (System.currentTimeMillis() - start)
        }
        CopyInventory.relistDelayMs = cfg.relistDelayMs

        try {
            cfg.copy.list("") // an unmounted volume lists as unreachable here, never as "not a Keepfin folder"
            if (cfg.refusal != null) return report.also { it.status = "refused"; it.reason = cfg.refusal }
            if (Generations.readFolderJson(cfg.copy) == null) return report.also { it.status = "refused"; it.reason = "The folder is not a Keepfin folder." }
        } catch (e: FolderError) {
            val away = awayOf(e) ?: Away.UNAVAILABLE
            if (e is FolderError.NotFound || away != null) return report.also { it.status = "disconnected"; it.away = away; it.reason = "The copy is not reachable." }
            throw e
        }

        val lockResult = CopyLock.acquire(cfg.copy, cfg.device, runId, now, cfg.lockHeartbeatMs, cfg.lockStaleMs)
        if (lockResult !is CopyLock.Companion.Result.Ok) {
            report.status = "busy"
            report.reason = if ((lockResult as CopyLock.Companion.Result.Refused).reason == "held") "Another run holds this copy." else "The copy changed while its lock was taken over."
            return report
        }
        val lock = lockResult.lock

        val tmpDir = File(cfg.stateDir, "tmp-$runId")
        try {
            tmpDir.mkdirs()
            val journalFile = File(cfg.stateDir, "journal.jsonl")
            val rules = CopyInventory.probeRules(cfg.copy)
            val names = NameMap.load(cfg.copy)
            // the baseline file is sorted by the name rule it was written under: sort it again when the rule changed (or was never recorded)
            run {
                val recorded = Relationship.read(cfg.stateDir)
                if (recorded.rules != rules.name && Baseline.file(cfg.stateDir).isFile) {
                    Baseline.resort(cfg.stateDir, rules, tmpDir, cfg.sortChunk)
                    if (!cfg.dryRun) Relationship.write(cfg.stateDir, Relationship(recorded.generation, recorded.previewConfirmed, rules.name, recorded.auditedAt))
                }
            }
            report.copyFormat = cfg.volumeFormat
            val sizeLimit = cfg.maxFileBytes ?: fileSizeLimit(cfg.volumeFormat)

            // 1. an interrupted run: reconcile the files with its journal, then fold what it committed
            var t = System.currentTimeMillis()
            if (journalFile.isFile) {
                report.recovery = Recovery.recover(journalFile, cfg.copy, cfg.main)
                Baseline.foldCommits(cfg.stateDir, journalFile, rules, tmpDir, null, cfg.sortChunk)
                Recovery.archiveJournal(journalFile)
            }
            timer("recovery", t)

            // 2. the generation check (spec 7)
            var rel = Relationship.read(cfg.stateDir)
            val copyGen = Generations.read(cfg.copy)
            // a run of ours that crashed after bumping the generation: the bump is ours (same lock token), not another computer's
            val recovery = report.recovery
            if (recovery != null && copyGen != null && copyGen != rel.generation) {
                val token = Generations.lockToken(cfg.copy)
                if (recovery.runs.any { it.first == token && it.second == copyGen }) {
                    rel = Relationship(copyGen, rel.previewConfirmed, rel.rules, rel.auditedAt)
                    Relationship.write(cfg.stateDir, rel)
                }
            }
            val genState = Generations.compare(copyGen, rel.generation)
            report.generation = GenerationInfo(copyGen, rel.generation, genState, null)
            var runGeneration = copyGen ?: 0L
            val previewConfirmed = cfg.previewConfirmed || rel.previewConfirmed
            val desiredFile = File(cfg.stateDir, "desired.jsonl")
            t = System.currentTimeMillis()
            writeDesired(cfg.desired, desiredFile, rules, tmpDir, cfg.sortChunk)
            timer("desired", t)

            val cache = HashCache()
            val freeMain = runCatching { cfg.main.freeBytes() }.getOrNull()
            val freeCopy = runCatching { cfg.copy.freeBytes() }.getOrNull()
            var bumped = false
            var audited = false
            var lastScanStart = now()
            for (round in 1..cfg.maxRounds) {
                report.rounds = round
                if (cfg.abort()) {
                    report.status = "paused"
                    break
                }
                // 3. inventories (sorted, on disk)
                t = System.currentTimeMillis()
                lastScanStart = now()
                val mainInv = File(tmpDir, "main.inv")
                val copyInv = File(tmpDir, "copy.inv")
                report.main = CopyInventory.scan(cfg.main, mainInv, rules, tmpDir, null, null, cfg.abort, cfg.sortChunk)
                report.copy = CopyInventory.scan(cfg.copy, copyInv, rules, tmpDir, names, cfg.derivedFilter ?: derivedFilter(cfg.copy), cfg.abort, cfg.sortChunk)
                timer("inventory", t)
                // files only in the cloud were left out of the listings (so never hashed or read) and the side is not fully known this run:
                // nothing destructive happens, and the executor refuses any action that touches their names
                val onlineOnly = report.main!!.onlineOnly.map { it to "main" } + report.copy!!.onlineOnly.map { it to "copy" }
                val onlineKeys = onlineOnly.mapTo(HashSet()) { rules.key(it.first) }

                // a time zone or daylight saving shift makes every stat untrustworthy: hash everything this run (spec 3)
                var audit = cfg.audit
                if (audit) report.auditReason = "asked"
                else if (Baseline.file(cfg.stateDir).isFile) {
                    val shift = ClockCheck.detect(Baseline.file(cfg.stateDir), copyInv, rules)
                    if (shift.detected) {
                        audit = true
                        report.auditReason = "clock"
                        report.auditHours = shift.hours
                    }
                }
                audited = audited || audit

                // 4. plan; hash what the planner asks for; plan again
                val readers = ReaderSet()
                val evidence = Evidence { side, line -> cache.get(side, line.path, line.size, line.mtimeMs) }
                var plan: Plan? = null
                try {
                    for (attempt in 0..6) {
                        t = System.currentTimeMillis()
                        val input = PlannerInput(
                            mode = cfg.mode,
                            options = PlaceOptions(versionsKeepDays = 90, deleteGuardPercent = cfg.deleteGuardPercent, companions = cfg.companions, musicVideos = cfg.musicVideos),
                            rules = rules.rules,
                            desired = readers.source(desiredFile, Codec::desiredOf),
                            main = readers.source(mainInv, Codec::invOf),
                            copy = readers.source(copyInv, Codec::invOf),
                            baseline = readers.source(Baseline.file(cfg.stateDir), Codec::baselineOf),
                            library = SimpleLibraryFacts(cfg.deleted, cfg.placementMoves),
                            mainComplete = report.main!!.complete,
                            copyComplete = report.copy!!.complete,
                            generation = genState,
                            previewConfirmed = previewConfirmed,
                            confirmedDeletes = cfg.confirmedDeletes,
                            // a document provider has no stable file ids: moves follow the library's placements only
                            caps = Capabilities(stableIds = false),
                            timestampGranularityMs = cfg.granularityMs,
                            audit = audit,
                            freeMain = freeMain,
                            freeCopy = freeCopy,
                        )
                        val outcome = Planner.plan(input, evidence)
                        readers.closeAll()
                        timer("plan", t)
                        if (outcome is PlanOutcome.Ready) {
                            plan = outcome.plan
                            break
                        }
                        if (attempt >= 6) throw IllegalStateException("The planner kept asking for hashes; the files changed while they were read.")
                        t = System.currentTimeMillis()
                        val got = hashRequests((outcome as PlanOutcome.NeedsHashes).requests, cfg, names, cache)
                        timer("hash", t)
                        report.hashed += got
                        if (got == 0) throw IllegalStateException("The files the planner asked about are no longer there; scan again.")
                    }
                } finally {
                    readers.closeAll()
                }
                val ready = plan ?: throw IllegalStateException("No plan.")

                report.summary = ready.summary
                report.held = ready.held
                report.holdReason = ready.holdReason
                report.additiveOnly = ready.additiveOnly
                report.reviewCount = ready.review.size
                report.review = ready.review.take(200)
                // what the executors know and the planner does not: cloud files left alone, files a FAT card cannot hold
                val notes = ArrayList<ReviewItem>()
                for ((p, _) in onlineOnly) notes.add(ReviewItem(ReviewKind.OnlineOnly, p, null, onlineOnlyWords(p.substringAfterLast('/'))))
                if (sizeLimit != null) {
                    for (a in ready.actions) if (a.kind == ActionKind.CopyTo && a.bytes > sizeLimit) notes.add(ReviewItem(ReviewKind.TooLarge, a.path, null, fileTooLargeWords(a.path.substringAfterLast('/'), cfg.volumeFormat)))
                }
                if (notes.isNotEmpty()) {
                    report.reviewCount += notes.size
                    report.review = (report.review + notes).take(200)
                }
                if (cfg.dryRun) {
                    report.status = "preview"
                    break
                }
                if (ready.held && !cfg.confirmHeld) {
                    report.status = "held"
                    break
                }
                if (ready.isEmpty) break

                // 5. execute (the generation is bumped once, at the start of the first run that changes the copy)
                val changesCopy = listOf(ActionKind.CopyTo, ActionKind.MoveOnCopy, ActionKind.RemoveFromCopy).any { (ready.summary.counts[it] ?: 0) > 0 }
                if (changesCopy && !bumped && genState != Generation.Lower && genState != Generation.Unknown) {
                    runGeneration = Generations.bump(cfg.copy, runId)
                    bumped = true
                }
                val journal = Journal.open(journalFile)
                journal.append(JournalRecord.Run(runId, now(), runGeneration), true)
                t = System.currentTimeMillis()
                val exec = CopyExecutor.execute(
                    ExecConfig(
                        cfg.main, cfg.copy, cfg.mode, rules, "$runId.$round", runGeneration, names, journal, cfg.host, cfg.parallelism, sizeLimit, now, cfg.fault, cfg.abort, cfg.onProgress, cfg.granularityMs, onlineKeys,
                    ),
                    // a listing the provider could not vouch for says nothing about what the copy lacks: no baseline entry is forgotten on it
                    if (report.copy!!.complete && report.main!!.complete) ready.actions else ready.actions.filter { it.kind != ActionKind.ForgetBaseline },
                    ready.summary.bytes.values.sum(),
                )
                timer("execute", t)
                if (exec.status != "crashed") runCatching { journal.append(JournalRecord.End("$runId.$round"), true) }
                journal.close()
                report.exec = mergeExec(report.exec, exec)
                if (exec.status == "crashed") {
                    report.status = "crashed"
                    return report // like a killed process: no folding, no cleanup, the next run recovers
                }

                // 6. fold the verified results into the baseline; a hash that proved a file unchanged refreshes its stat
                t = System.currentTimeMillis()
                Baseline.foldCommits(
                    cfg.stateDir, journalFile, rules, tmpDir,
                    refresh = { e ->
                        val h = cache.lookup(Side.Copy, e.path)
                        if (h != null && h.first == e.contentId && (h.second != e.copy.size || h.third != e.copy.mtimeMs)) {
                            e.copy(copy = app.keepfin.core.copies.Stat(h.second, h.third, e.copy.fileId), scannedAtMs = lastScanStart)
                        } else e
                    },
                    chunk = cfg.sortChunk,
                )
                // an action left open by a lost card or a full disk is settled by recovery at the next run, which needs the journal
                if (exec.status == "done" || exec.status == "paused") Recovery.archiveJournal(journalFile)
                timer("fold", t)
                when (exec.status) {
                    "disconnected" -> {
                        report.status = "disconnected"
                        report.away = exec.away
                        report.reason = exec.message
                    }
                    "paused" -> report.status = "paused"
                    "diskFull" -> {
                        report.status = "diskFull"
                        report.reason = exec.message ?: "The copy is full."
                    }
                }
                if (report.status != "done") break
                if (!exec.needsReplan) break
            }

            // 7. what is verified: the generation, the previewed state and the coverage manifest
            if (report.status in setOf("done", "paused", "disconnected", "held", "diskFull")) {
                if (genState != Generation.Lower && genState != Generation.Unknown) {
                    val generation = if (bumped) runGeneration else Generations.read(cfg.copy) ?: runGeneration
                    // the first completed run hashed everything it copied; a run that audited (or saw the clock change) is the new last audit
                    val auditedAt = if (report.status == "done" && (audited || rel.auditedAt == null)) now() else rel.auditedAt
                    Relationship.write(cfg.stateDir, Relationship(generation, previewConfirmed, rules.name, auditedAt))
                    report.generation.committedAt = generation
                }
                if (report.status == "done" && genState != Generation.Lower && genState != Generation.Unknown) {
                    t = System.currentTimeMillis()
                    report.manifest = Manifest.write(cfg.stateDir, cfg.main, cfg.copy, cfg.placeId, report.generation.committedAt ?: runGeneration, cfg.holdsKey, cfg.optionsKey, cfg.mainReplica, cfg.copyReplica, now(), tmpDir)
                    timer("manifest", t)
                }
            }
            return report
        } catch (e: DisconnectedError) {
            report.status = "disconnected"
            report.away = e.away
            report.reason = e.message
            return report
        } catch (e: SkipError) {
            if (e.reason == "paused") {
                report.status = "paused"
                return report
            }
            throw e
        } catch (e: FolderError) {
            val away = awayOf(e)
            if (away != null) {
                report.status = "disconnected"
                report.away = away
                report.reason = e.message
                return report
            }
            throw e
        } finally {
            if (report.status == "crashed") {
                lock.abandon() // a killed process leaves its lock behind
            } else {
                tmpDir.deleteRecursively()
                runCatching { lock.release() }
            }
        }
    }

    private fun writeDesired(entries: Sequence<DesiredEntry>, file: File, rules: RulesId, tmpDir: File, chunk: Int): Int {
        val sorter = ExternalSorter(tmpDir, chunk, "desired")
        var seq = 0L
        for (e in entries) sorter.add(SortRec(rules.key(e.path), e.path, seq++, Codec.desired(e)))
        file.parentFile?.mkdirs()
        val w = LineWriter(file)
        sorter.sorted().use { it.forEach { rec -> w.write(rec.text) } }
        w.finish(false)
        return w.count
    }

    /** Hashes the requested files with bounded concurrency. A file that vanished is left unhashed (the next inventory sees it). */
    private fun hashRequests(requests: List<HashRequest>, cfg: RunConfig, names: NameMap, cache: HashCache): Int {
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "keepfin-copy-hash").also { it.isDaemon = true } }
        val done = AtomicInteger()
        try {
            val futures = requests.map { r ->
                pool.submit(Callable {
                    val access = if (r.side == Side.Copy) cfg.copy else cfg.main
                    val path = if (r.side == Side.Copy) names.physical(r.path) else r.path
                    try {
                        val h = hashPath(access, path, cfg.abort)
                        cache.set(r.side, r.path, r.size, r.mtimeMs, h.contentId)
                        done.incrementAndGet()
                    } catch (e: FolderError.NotFound) {
                        // vanished: the next inventory sees it
                    }
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return done.get()
    }

    /** The paths the runner must leave out of the copy's inventory: derived exports are not library content. */
    private fun derivedFilter(copy: FolderAccess): ((String) -> Boolean)? {
        val recorded = Exports.recordedPaths(copy)
        if (recorded.isEmpty()) return null
        val lower = recorded.mapTo(HashSet()) { it.lowercase() }
        return { p -> p.lowercase() !in lower }
    }

    private fun mergeExec(a: ExecReport?, b: ExecReport): ExecReport {
        if (a == null) return b
        val r = ExecReport()
        r.status = b.status
        r.committed = a.committed + b.committed
        r.skipped.addAll((a.skipped + b.skipped).take(1000))
        r.skippedCount = a.skippedCount + b.skippedCount
        r.failed.addAll((a.failed + b.failed).take(1000))
        r.failedCount = a.failedCount + b.failedCount
        r.bytes = a.bytes + b.bytes
        r.needsReplan = b.needsReplan
        r.byKind.putAll(a.byKind)
        for ((k, v) in b.byKind) r.byKind[k] = (r.byKind[k] ?: 0) + v
        r.away = b.away
        r.message = b.message
        return r
    }
}
