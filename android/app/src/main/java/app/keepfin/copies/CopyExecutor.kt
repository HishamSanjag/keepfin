package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.ActionKind
import app.keepfin.core.copies.Archive
import app.keepfin.core.copies.BaselineEntry
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.RemoveReason
import app.keepfin.core.copies.Stat
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What the library layer does on the main side. The executor stages verified bytes next to their destination; the host turns them into library operations. */
internal interface MainHost {
    /** A file only the copy has becomes an item (or, in a conflict, another revision of [Action.itemId]) at [destPath]. [stagedPath] sits next to it. */
    fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome

    /** The copy's bytes become a new revision of the item (the main file is replaced; what it held goes to the Keepfin trash). */
    fun reviseFromCopy(action: Action, stagedPath: String): String?

    /** The copy edited what the library deleted: restore it as a revision, for review. */
    fun restoreEditedDeleted(action: Action, stagedPath: String): String?

    /** A user-confirmed deletion on the copy becomes `item.delete`. */
    fun deleteItem(action: Action)
}

internal class ImportOutcome(val itemId: String, val revisionOp: String?, val path: String)

internal class Progress(val doneActions: Int, val totalActions: Int, val doneBytes: Long, val totalBytes: Long, val current: String?)

internal class ExecConfig(
    val main: FolderAccess,
    val copy: FolderAccess,
    val mode: CopyMode,
    val rules: RulesId,
    val runId: String,
    /** The generation this run commits at. */
    val generation: Long,
    val names: NameMap,
    val journal: Journal,
    val host: MainHost? = null,
    /** 2 on an HDD, 4 on an SSD (spec 5.6); a document provider is slow per call, so 2 is the default. */
    val parallelism: Int = 2,
    /** Files larger than this are skipped and reported (4 GiB - 1 on FAT32). */
    val maxFileBytes: Long? = null,
    val now: () -> Long = System::currentTimeMillis,
    val fault: FaultHook? = null,
    /** Pause: the current actions finish and the run stops. */
    val abort: () -> Boolean = { false },
    val onProgress: ((Progress) -> Unit)? = null,
    /** The copy volume's timestamp granularity: a file Keepfin just wrote is not hashed again for it. */
    val granularityMs: Long = 2000,
)

internal class SkippedAction(val n: Int, val kind: String, val path: String, val reason: String)

internal class ExecReport {
    /** done, paused, disconnected, crashed, diskFull */
    var status = "done"
    var committed = 0
    val skipped = ArrayList<SkippedAction>()
    var skippedCount = 0
    val failed = ArrayList<SkippedAction>()
    var failedCount = 0
    var bytes = 0L

    /** At least one action was skipped because a precondition no longer held: plan again. */
    var needsReplan = false
    val byKind = HashMap<String, Int>()
    var away: Away? = null
    var message: String? = null
}

private class Outcome(val ops: List<BaselineOp>)

private const val MAX_LISTED = 1000
private val EMPTY = Stat(0, 0)

/** Skip reasons that mean "the world changed, plan again". */
private val REPLAN = setOf("destination-not-absent", "destination-missing", "unexpected-destination", "source-missing", "hash-mismatch", "destination-appeared", "destination-occupied", "copy-file-missing", "busy", "earlier-step")

/**
 * The executor of copies (spec section 5). It runs a plan from the shared-core planner against the Keepfin folder (main) and a copy.
 * Every action is journaled in separate durable steps (intent, staged, preserved, published, committed), checks its preconditions right
 * before it mutates, and never overwrites or deletes anything outright: displaced bytes go to the copy's versions or trash first. A
 * precondition that does not hold skips the action and records it; the next plan decides again. Paths are the identity of a file (spec
 * 11.1 of the Android folder spec): a publish is a staged file renamed under a name nobody holds, and the provider's answer is checked.
 */
internal object CopyExecutor {
    fun execute(cfg: ExecConfig, actions: List<Action>, totalBytes: Long): ExecReport = Runner(cfg, actions.size, totalBytes).run(actions)

    /** Splits the plan into groups that must run in order (a conflict: import, then replace; a move followed by a copy). */
    fun groups(actions: List<Action>, rules: RulesId): List<Pair<Int, List<Action>>> {
        val out = ArrayList<Pair<Int, List<Action>>>()
        var start = 0
        var group = ArrayList<Action>()
        var keys = HashSet<String>()
        for ((n, a) in actions.withIndex()) {
            val ks = listOfNotNull(rules.key(a.path), a.toPath?.let { rules.key(it) })
            if (group.isNotEmpty() && ks.none { it in keys }) {
                out.add(start to group)
                group = ArrayList()
                keys = HashSet()
                start = n
            }
            group.add(a)
            keys.addAll(ks)
        }
        if (group.isNotEmpty()) out.add(start to group)
        return out
    }

    private class Archived(val store: Store, val archive: String, val entry: CatalogueEntry)

    private class Runner(private val cfg: ExecConfig, private val totalActions: Int, private val totalBytes: Long) {
        private val report = ExecReport()
        private val versions = CatalogueWriter(cfg.copy, Store.VERSIONS)
        private val trash = CatalogueWriter(cfg.copy, Store.TRASH)
        private var doneActions = 0
        private val doneBytes = AtomicLong()
        private var lastProgress = 0L
        private var current: String? = null
        private val lock = Any()

        private fun copyPhysical(lib: String) = cfg.names.physical(lib)

        private fun fault(step: String, n: Int, a: Action?) {
            cfg.fault?.at(step, n, a)
        }

        private fun catalogue(store: Store) = if (store == Store.VERSIONS) versions else trash

        // ---- running ----

        fun run(actions: List<Action>): ExecReport {
            val limit = cfg.parallelism.coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(limit) { r -> Thread(r, "keepfin-copy-exec").also { it.isDaemon = true } }
            val slots = Semaphore(limit)
            var failure: Throwable? = null
            try {
                for ((start, group) in groups(actions, cfg.rules)) {
                    if (cfg.abort()) {
                        report.status = "paused"
                        break
                    }
                    if (synchronized(lock) { failure != null }) break
                    slots.acquire()
                    pool.execute {
                        try {
                            runGroup(start, group)
                        } catch (t: Throwable) {
                            synchronized(lock) { if (failure == null) failure = t }
                        } finally {
                            slots.release()
                        }
                    }
                }
                pool.shutdown()
                pool.awaitTermination(7, TimeUnit.DAYS)
            } finally {
                pool.shutdownNow()
                emitProgress(true)
                if (!cfg.journal.isDead) runCatching { flushCatalogues() }
            }
            when (val f = failure) {
                null -> {}
                is CrashError -> report.status = "crashed"
                is DisconnectedError -> {
                    report.status = "disconnected"
                    report.away = f.away
                    report.message = f.message
                }
                is DiskFullStop -> {
                    report.status = "diskFull"
                    report.message = f.message
                }
                else -> throw f
            }
            if (report.status == "done" && cfg.abort()) report.status = "paused"
            report.needsReplan = report.skipped.any { it.reason in REPLAN } || report.skippedCount > report.skipped.size
            return report
        }

        private fun flushCatalogues() {
            versions.flush()
            trash.flush()
        }

        private fun runGroup(start: Int, group: List<Action>) {
            var broken: String? = null
            for ((i, a) in group.withIndex()) {
                val n = start + i
                if (broken != null) {
                    record(n, a, true, "earlier-step")
                    continue
                }
                if (cfg.abort() && i > 0) {
                    // the group's remaining steps stay unrun; the next plan resumes them
                    record(n, a, true, "paused")
                    broken = "paused"
                    continue
                }
                current = a.path
                try {
                    val out = runAction(n, a)
                    cfg.journal.append(JournalRecord.Committed(cfg.runId, n, out.ops), false)
                    fault("committed", n, a)
                    synchronized(lock) {
                        report.committed++
                        report.byKind[a.kind.name] = (report.byKind[a.kind.name] ?: 0) + 1
                    }
                    doneBytes.addAndGet(a.bytes)
                } catch (e: CrashError) {
                    cfg.journal.kill()
                    throw e
                } catch (e: DisconnectedError) {
                    throw e
                } catch (e: DiskFullStop) {
                    throw e
                } catch (e: SkipError) {
                    if (cfg.journal.isDead) throw CrashError("journal-dead")
                    record(n, a, true, e.reason)
                    broken = e.reason
                } catch (e: Throwable) {
                    if (cfg.journal.isDead) throw CrashError("journal-dead")
                    val folder = e as? FolderError
                    if (folder != null) {
                        awayOf(folder)?.let { throw DisconnectedError(it, "The copy is not reachable.", folder) }
                        if (folder is FolderError.DiskFull) throw DiskFullStop(folder.message ?: "The copy is full.", folder)
                        if (copyGone()) throw DisconnectedError(Away.UNAVAILABLE, "The copy is not reachable.", folder)
                    }
                    record(n, a, false, e.message ?: e.javaClass.simpleName)
                    broken = "failed"
                }
                synchronized(lock) { doneActions++ }
                emitProgress(false)
            }
        }

        /** The copy root cannot be read any more (unplugged, share gone): its `folder.json` is not there. A missing file is never this. */
        private fun copyGone(): Boolean = try {
            cfg.copy.stat(Generations.FOLDER_JSON) == null
        } catch (e: FolderError) {
            true
        }

        private fun record(n: Int, a: Action, skipped: Boolean, reason: String) {
            val rec = SkippedAction(n, a.kind.name, a.path, reason)
            synchronized(lock) {
                if (skipped) {
                    report.skippedCount++
                    if (report.skipped.size < MAX_LISTED) report.skipped.add(rec)
                } else {
                    report.failedCount++
                    if (report.failed.size < MAX_LISTED) report.failed.add(rec)
                }
            }
            try {
                cfg.journal.append(if (skipped) JournalRecord.Skipped(cfg.runId, n, reason) else JournalRecord.Failed(cfg.runId, n, reason), false)
            } catch (e: CrashError) {
                // the journal is dead: the run ends
            }
        }

        private fun emitProgress(force: Boolean) {
            val cb = cfg.onProgress ?: return
            val t = cfg.now()
            synchronized(lock) {
                if (!force && t - lastProgress < 250) return
                lastProgress = t
            }
            cb(Progress(doneActions, totalActions, doneBytes.get(), totalBytes, current))
        }

        private val onBytes: (Long) -> Unit = { report.bytes += it }

        // ---- one action ----

        private fun runAction(n: Int, a: Action): Outcome = when (a.kind) {
            ActionKind.CopyTo -> copyTo(n, a)
            ActionKind.MoveOnCopy -> moveOnCopy(n, a)
            ActionKind.RemoveFromCopy -> removeFromCopy(n, a)
            ActionKind.Converge -> converge(n, a)
            ActionKind.ForgetBaseline -> {
                intent(n, a, null, null)
                Outcome(listOf(BaselineOp(a.path, null)))
            }
            ActionKind.RestoreFromCopy -> restoreFromCopy(n, a)
            ActionKind.ImportNew, ActionKind.ReviseFromCopy, ActionKind.RestoreEditedDeleted -> viaHost(n, a)
            ActionKind.DeleteItem -> {
                intent(n, a, null, null)
                val host = cfg.host?.takeIf { true } ?: throw SkipError("needs-library", "No library host to record the deletion.")
                host.deleteItem(a)
                Outcome(emptyList())
            }
        }

        private fun intent(n: Int, a: Action, tmp: String?, dest: String?) {
            cfg.journal.append(JournalRecord.Intent(cfg.runId, n, a, tmp, dest), true)
            fault("intent", n, a)
            // a vanished copy root must not be recreated by a mkdir further down: that is a disconnect, not an absence
            if (copyGone()) throw DisconnectedError(Away.UNAVAILABLE, "The copy is not reachable.")
        }

        private fun statOf(access: FolderAccess, path: String): Stat? {
            val e = access.stat(path) ?: return null
            if (e.isDirectory) return null
            return Stat(e.size, e.modifiedMs)
        }

        private fun entry(a: Action, lib: String, contentId: String, main: Stat, copy: Stat, itemId: String? = null, revisionOp: String? = a.revisionOp): BaselineEntry =
            // a file Keepfin just wrote and verified is not "just transferred" at the next scan: its time is old enough for the granularity rule
            BaselineEntry(lib, itemId ?: a.itemId ?: "", revisionOp, contentId, main, copy, maxOf(cfg.now(), copy.mtimeMs + cfg.granularityMs), cfg.generation)

        private inline fun <T> io(block: () -> T): T = try {
            block()
        } catch (e: FolderError.Io) {
            val c = e.cause
            if (c is SkipError) throw c else throw e
        }

        /** Streams [srcPath] of [source] to a new staged file [tmp] of [target], verified against the pinned [expected] content id. */
        private fun stage(n: Int, a: Action, source: FolderAccess, srcPath: String, target: FolderAccess, tmp: String, expected: String): Stat {
            val src = statOf(source, srcPath) ?: throw SkipError("source-missing", "The source $srcPath is gone.")
            val limit = cfg.maxFileBytes
            if (limit != null && src.size > limit) throw SkipError("too-large", "The file is larger than the copy can hold.")
            fault("transfer", n, a)
            val input = try {
                source.openRead(srcPath)
            } catch (e: FolderError.NotFound) {
                throw SkipError("source-missing", "The source $srcPath is gone.")
            }
            target.mkdirs(parentPath(tmp))
            val result = try {
                io { target.publishFrom(tmp, GuardedInput(input, cfg.abort, onBytes, src.size, expected)) }
            } catch (e: FolderError.NameConflict) {
                throw SkipError("busy", "The staging name was taken.")
            }
            if (CONTENT_PREFIX + result.sha256 != expected) {
                runCatching { target.remove(tmp) }
                throw SkipError("hash-mismatch", "The source no longer matches its pinned content.")
            }
            cfg.journal.append(JournalRecord.Staged(cfg.runId, n, tmp, expected), false)
            fault("staged", n, a)
            return src
        }

        /** The destination's own state checked against the plan's expectation, before anything is staged. */
        private fun checkDestination(a: Action, dest: String): Stat? {
            val st = statOf(cfg.copy, dest)
            val expect = a.expect
            if (expect == null || expect.contentId == null) {
                if (st != null) throw SkipError("destination-not-absent", "${a.path} is already on the copy.")
                return null
            }
            if (st == null) throw SkipError("destination-missing", "${a.path} is no longer on the copy.")
            return st
        }

        // ---- CopyTo: stage, verify, preserve, publish ----

        private fun copyTo(n: Int, a: Action): Outcome {
            val contentId = a.contentId ?: throw IllegalStateException("A copy needs a pinned content id.")
            val dest = cfg.names.resolve(a.path)
            val tmp = "$dest.kftmp-${cfg.runId}-$n"
            intent(n, a, tmp, dest)
            val existing = checkDestination(a, dest)
            val mainStat = stage(n, a, cfg.main, a.path, cfg.copy, tmp, contentId)
            var archived: Archived? = null
            try {
                if (existing != null) archived = preserve(n, a, dest, a.archive ?: Archive.Trash, a.expect!!.contentId!!, "replaced")
                fault("before-publish", n, a)
                try {
                    cfg.copy.rename(tmp, leafName(dest))
                } catch (e: FolderError.NameConflict) {
                    throw SkipError("destination-appeared", "${a.path} appeared on the copy.")
                }
            } catch (e: CrashError) {
                throw e
            } catch (e: Throwable) {
                runCatching { cfg.copy.remove(tmp) }
                val gone = runCatching { cfg.copy.stat(dest) == null }.getOrDefault(false)
                if (archived != null && gone) restoreArchived(archived, dest)
                throw e
            }
            cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
            fault("published", n, a)
            val copyStat = statOf(cfg.copy, dest) ?: Stat(mainStat.size, cfg.now())
            return Outcome(listOf(BaselineOp(a.path, entry(a, a.path, contentId, mainStat, copyStat))))
        }

        /** Journals the catalogue entry first, then moves the displaced bytes aside (spec 5.1 step 3). */
        private fun preserve(n: Int, a: Action, dest: String, store: Archive, contentId: String, why: String): Archived {
            val kind = if (store == Archive.Versions) Store.VERSIONS else Store.TRASH
            val entry = Versions.planArchive(cfg.copy, kind, ArchiveInfo(a.itemId, null, contentId, a.path, dest, cfg.runId, n, cfg.now(), why))
            val archive = ".keepfin/${entry.file}"
            cfg.journal.append(JournalRecord.Preserved(cfg.runId, n, dest, archive, entry, false), true)
            catalogue(kind).append(entry)
            fault("preserve-before-move", n, a)
            try {
                Versions.moveVerified(cfg.copy, dest, archive, contentId, cfg.abort)
            } catch (e: SkipError) {
                catalogue(kind).void(entry.id)
                throw e
            } catch (e: FolderError) {
                catalogue(kind).void(entry.id)
                throw e
            }
            fault("preserved", n, a)
            if (catalogue(kind).pendingCount() >= 100) catalogue(kind).flush()
            return Archived(kind, archive, entry)
        }

        private fun restoreArchived(archived: Archived, dest: String) {
            try {
                Versions.moveVerified(cfg.copy, archived.archive, dest, null)
                catalogue(archived.store).void(archived.entry.id)
            } catch (e: Exception) {
                // the bytes stay in the archive, which is safe; recovery or the user can restore them
            }
        }

        // ---- RemoveFromCopy: to the trash, never deleted ----

        private fun removeFromCopy(n: Int, a: Action): Outcome {
            val physical = copyPhysical(a.path)
            intent(n, a, null, physical)
            val st = statOf(cfg.copy, physical) ?: return Outcome(listOf(BaselineOp(a.path, null))) // already gone: only the baseline entry remains
            val contentId = a.expect?.contentId ?: hashPath(cfg.copy, physical, cfg.abort).contentId
            if (st.size < 0) throw SkipError("unexpected-destination")
            preserve(n, a, physical, a.archive ?: Archive.Trash, contentId, if (a.reason == RemoveReason.ItemDeleted) "deleted" else "removed")
            cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
            return Outcome(listOf(BaselineOp(a.path, null)))
        }

        // ---- MoveOnCopy: a rename, hash-checked ----

        private fun moveOnCopy(n: Int, a: Action): Outcome {
            val toPath = a.toPath ?: throw IllegalStateException("A move needs a destination.")
            val from = copyPhysical(a.path)
            val to = cfg.names.resolve(toPath)
            intent(n, a, null, to)
            val st = statOf(cfg.copy, from) ?: throw SkipError("copy-file-missing", "${a.path} is no longer on the copy.")
            val expected = a.expect?.contentId
            val contentId: String
            try {
                if (parentPath(from) == parentPath(to)) {
                    contentId = hashPath(cfg.copy, from, cfg.abort).contentId
                    if (expected != null && contentId != expected) throw SkipError("unexpected-destination", "${a.path} on the copy changed since it was planned.")
                    cfg.copy.rename(from, leafName(to))
                } else {
                    cfg.copy.mkdirs(parentPath(to))
                    contentId = Versions.moveVerified(cfg.copy, from, to, expected, cfg.abort)
                }
            } catch (e: FolderError.NameConflict) {
                throw SkipError("destination-occupied", "$toPath is taken on the copy.")
            }
            cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
            fault("published", n, a)
            val copyStat = statOf(cfg.copy, to) ?: st
            val mainStat = statOf(cfg.main, toPath) ?: EMPTY
            return Outcome(listOf(BaselineOp(a.path, null), BaselineOp(toPath, entry(a, toPath, contentId, mainStat, copyStat, a.itemId, null))))
        }

        // ---- Converge: only the baseline is written, after the copy's bytes are proven ----

        private fun converge(n: Int, a: Action): Outcome {
            val contentId = a.contentId ?: throw IllegalStateException("A converge needs a content id.")
            val dest = copyPhysical(a.path)
            intent(n, a, null, dest)
            val st = statOf(cfg.copy, dest) ?: throw SkipError("copy-file-missing", "${a.path} is no longer on the copy.")
            if (hashPath(cfg.copy, dest, cfg.abort).contentId != contentId) throw SkipError("unexpected-destination", "${a.path} on the copy changed since it was planned.")
            val mainStat = statOf(cfg.main, a.path) ?: EMPTY
            return Outcome(listOf(BaselineOp(a.path, entry(a, a.path, contentId, mainStat, st))))
        }

        // ---- RestoreFromCopy: main lost the file; the copy's verified bytes go back to the same path ----

        private fun restoreFromCopy(n: Int, a: Action): Outcome {
            val contentId = a.contentId ?: throw IllegalStateException("A restore needs a content id.")
            val dest = a.path
            val tmp = "$dest.kftmp-${cfg.runId}-$n"
            intent(n, a, tmp, dest)
            if (statOf(cfg.main, dest) != null) throw SkipError("destination-not-absent", "${a.path} is on main again.")
            val copySt = stage(n, a, cfg.copy, copyPhysical(a.path), cfg.main, tmp, contentId)
            try {
                cfg.main.rename(tmp, leafName(dest))
            } catch (e: FolderError.NameConflict) {
                runCatching { cfg.main.remove(tmp) }
                throw SkipError("destination-appeared", "${a.path} appeared on main.")
            } catch (e: Throwable) {
                runCatching { cfg.main.remove(tmp) }
                throw e
            }
            cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
            val mainStat = statOf(cfg.main, dest) ?: Stat(copySt.size, cfg.now())
            return Outcome(listOf(BaselineOp(a.path, entry(a, a.path, contentId, mainStat, copySt))))
        }

        // ---- actions that become library operations: the executor stages verified bytes, the host places them ----

        private fun viaHost(n: Int, a: Action): Outcome {
            val contentId = a.contentId ?: throw IllegalStateException("This action needs a content id.")
            val host = cfg.host
            val finalPath = if (a.kind == ActionKind.ImportNew) uniqueName(a.toPath ?: a.path) { p -> statOf(cfg.main, p) != null } else a.path
            val tmp = "$finalPath.kftmp-${cfg.runId}-$n"
            intent(n, a, tmp, finalPath)
            if (host == null) throw SkipError("needs-library", "No library host to record this change.")
            val staged = stage(n, a, cfg.copy, copyPhysical(a.path), cfg.main, tmp, contentId)
            try {
                var itemId = a.itemId
                var revisionOp: String? = null
                when (a.kind) {
                    ActionKind.ImportNew -> {
                        val r = host.importNew(a, tmp, finalPath)
                        cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
                        // a new item under the copy's own name converges at once; any other name is copied later
                        if (a.toPath != null || finalPath != a.path) return Outcome(emptyList())
                        itemId = r.itemId
                        revisionOp = r.revisionOp
                    }
                    ActionKind.ReviseFromCopy -> revisionOp = host.reviseFromCopy(a, tmp)
                    else -> revisionOp = host.restoreEditedDeleted(a, tmp)
                }
                if (a.kind != ActionKind.ImportNew) cfg.journal.append(JournalRecord.Published(cfg.runId, n), false)
                val mainStat = statOf(cfg.main, finalPath) ?: EMPTY
                val copyStat = statOf(cfg.copy, copyPhysical(a.path)) ?: staged
                val id = itemId ?: return Outcome(emptyList())
                return Outcome(listOf(BaselineOp(a.path, entry(a, a.path, contentId, mainStat, copyStat, id, revisionOp))))
            } finally {
                runCatching { if (cfg.main.stat(tmp) != null) cfg.main.remove(tmp) }
            }
        }
    }
}
