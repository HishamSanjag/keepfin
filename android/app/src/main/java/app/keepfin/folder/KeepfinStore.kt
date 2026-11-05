package app.keepfin.folder

import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.net.base64Encode
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.jObj
import app.keepfin.core.sync.sha256Hex
import app.keepfin.core.sqlstate.PartSource
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.Observed
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpDecode
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeOp
import app.keepfin.core.sync.decodeSegment
import app.keepfin.core.sync.encodeSegment
import app.keepfin.core.sync.segmentFileName
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** A change before it becomes an operation: its kind and body (dev-docs/plans/KEEPFIN-SYNC-SPEC.md). */
class OpDraft(val kind: String, val body: Map<String, Jv>)

class StoreOptions(
    val folder: FolderAccess,
    val libraryId: String,
    val actor: String,
    /** Device-only: the sequence counter lives in `<deviceDir>/seq/<actor>.json`. */
    val deviceDir: File,
    /** Device-only: holds `ops.jsonl`, the operations not yet in a segment. */
    val outboxDir: File,
    val newId: () -> String,
    val now: () -> Long = { System.currentTimeMillis() },
    /** The merge state (SQLite on a phone). */
    val state: MergeState,
    /** Idle time before the outbox is written as a segment. */
    val flushIdleMs: Long = 2_000,
    /** Operations in the outbox that flush at once. */
    val flushOps: Int = 500,
    /** Operations between checkpoints. */
    val checkpointEvery: Int = 5_000,
    val checkpointDelayMs: Long = 1_500,
    val segmentMaxOps: Int = 5_000,
    /** The first retry delay for a segment that cannot be read yet (doubling up to 30 s). */
    val retryMs: Long = 500,
    /** A newer-format folder: read and merge, never write. */
    val readOnly: Boolean = false,
    val appVersion: String = "",
    val deviceName: String = "Android",
    /** The actor was retired (a restored or cloned install was detected): the profile must record the new id. */
    val onRotate: ((oldActor: String, newActor: String) -> Unit)? = null,
    /** The device key: this actor's segments are written as `KFSEG 2` signed with it (network spec 9.1). Null: unsigned segments. */
    val signer: DeviceSigner? = null,
    /** Where the records of this library live under `.keepfin` (a folder that holds a combined library keeps them below `libraries/<id>`). */
    val layout: FolderLayout = FolderLayout.DEFAULT,
)

data class OpenStats(
    val ms: Long,
    val checkpoint: Boolean,
    val segmentsRead: Int,
    val opsAdmitted: Int,
    val outboxOps: Int,
    val rotated: Boolean,
    val equivocations: Map<String, Long>,
)

private class Pending(val op: Op, val text: String)

private data class SegmentFile(val ops: String, val actor: String, val first: Long, val last: Long, val name: String) {
    val path get() = "$ops/$actor/$name"
    val key get() = "$actor/$name"
}

/**
 * This device's view of the sync log in `<Keepfin folder>/.keepfin` (a port of `gui/desktop/store/store.ts`):
 *
 *   apply(drafts)   makes operations, appends them to the outbox (fsync) and only then admits them
 *   flush()         writes the outbox as an immutable segment `ops/<actor>/<first>-<last>.seg`
 *   checkpoints     parts `checkpoints/<shard>/<maxHlc>-<actor>.ckpt` every [StoreOptions.checkpointEvery] operations
 *   rescan()        admits other actors' new segments; a partial one is retried with backoff, never read as corruption at once
 *
 * One dedicated thread owns everything (never main). Every public call returns a future that completes on that thread.
 * Listings are never authority for deletion: the state only grows, and an incomplete listing only sets [listingIncomplete].
 */
class KeepfinStore private constructor(private val o: StoreOptions) {
    companion object {
        const val DATA_DIR = KeepfinFolders.DATA_DIR
        const val OPS_DIR = "$DATA_DIR/ops"
        const val CKPT_DIR = "$DATA_DIR/checkpoints"
        const val ACTORS_DIR = "$DATA_DIR/actors"
        /** Written once a membership root is held: a folder with this marker is opened by replaying segments (checkpoints carry no membership). */
        const val MEMBERSHIP_MARKER = "$DATA_DIR/membership"
        private val SEGMENT = Regex("^(\\d{16})-(\\d{16})\\.seg$")
        private const val OUTBOX_COMPACT_BYTES = 4_000_000
        private const val FOLD_DELAY_MS = 3_000L

        /** Opens the store; the returned future completes when the state is merged. */
        fun open(options: StoreOptions): CompletableFuture<KeepfinStore> {
            val store = KeepfinStore(options)
            return store.submit { store.init(); store }
        }
    }

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "keepfin-store").also { it.isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }
    private val state: MergeState = o.state
    private val deviceId: String? = o.signer?.let { deviceIdOf(it.spki) }
    private var enforcedNoted = false
    private var actor: String = o.actor
    private var hlc: Hlc? = null
    private var nextSeq = 1L
    private var pending = ArrayList<Pending>()
    private val processed = HashSet<String>()
    private val retries = HashMap<String, Pair<Int, Long>>() // key -> attempts, not before
    private val problems = LinkedHashMap<String, String>()
    private var equivocations: Map<String, Long> = emptyMap()
    private val flushedLast = HashMap<String, Long>()
    private var opsSinceCheckpoint = 0
    private var outboxBytes = 0L
    private var closed = false
    private var flushTimer: ScheduledFuture<*>? = null
    private var retryTimer: ScheduledFuture<*>? = null
    private var checkpointTimer: ScheduledFuture<*>? = null
    private var foldTimer: ScheduledFuture<*>? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(remote: Boolean) -> Unit>()

    @Volatile var stats: OpenStats? = null
        private set

    /** True when the last listing was empty or smaller than the one before: nothing was concluded from it. */
    @Volatile var listingIncomplete = false
        private set

    /** The last failure of a background flush, rescan or checkpoint (needs access, folder unavailable, disk full...). */
    @Volatile var lastError: Throwable? = null
        private set

    @Volatile var revision = 0L
        private set

    @Volatile var pendingOps = 0
        private set

    /** Segment files this store has written since it opened (the network layer lists the folder again when it changes). */
    @Volatile var segmentsWritten = 0L
        private set

    val actorId: String get() = actor
    val isClosed: Boolean get() = closed
    val libraryId: String get() = o.libraryId

    // ------------------------------------------------------------------ plumbing

    private fun <T> submit(work: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        try {
            executor.execute {
                try {
                    f.complete(work())
                } catch (t: Throwable) {
                    f.completeExceptionally(t)
                }
            }
        } catch (e: RejectedExecutionException) {
            f.completeExceptionally(IllegalStateException("The library store is closed."))
        }
        return f
    }

    private fun schedule(delayMs: Long, work: () -> Unit): ScheduledFuture<*>? =
        try {
            executor.schedule({
                try {
                    work()
                } catch (t: Throwable) {
                    lastError = t
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }

    fun onChange(listener: (remote: Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun emit(remote: Boolean) {
        for (l in listeners) runCatching { l(remote) }
    }

    private fun seqFile(a: String = actor) = File(File(o.deviceDir, "seq"), "$a.json")
    private val outboxFile get() = File(o.outboxDir, "ops.jsonl")

    // ------------------------------------------------------------------ open

    private fun listSegments(): List<SegmentFile> {
        val out = ArrayList<SegmentFile>()
        var incomplete = false
        val actors = try {
            o.folder.list(o.layout.ops).also { if (it.possiblyIncomplete) incomplete = true }.entries.filter { it.isDirectory }
        } catch (e: FolderError.NotFound) {
            emptyList()
        }
        for (dir in actors) {
            val listing = try {
                o.folder.list("${o.layout.ops}/${dir.name}")
            } catch (e: FolderError.NotFound) {
                continue
            }
            if (listing.possiblyIncomplete) incomplete = true
            for (f in listing.entries) {
                if (f.isDirectory) continue
                val m = SEGMENT.matchEntire(f.name) ?: continue
                out += SegmentFile(o.layout.ops, dir.name, m.groupValues[1].toLong(), m.groupValues[2].toLong(), f.name)
            }
        }
        listingIncomplete = incomplete
        return out.sortedWith(compareBy<SegmentFile>({ it.actor }, { it.first }, { it.last }))
    }

    private fun decode(file: SegmentFile): SegmentResult? =
        try {
            decodeSegment(o.folder.read(file.path), o.libraryId)
        } catch (e: FolderError.NotFound) {
            null // not readable yet (a rename in flight); retried
        }

    private fun init() {
        val started = System.currentTimeMillis()
        if (!o.readOnly) {
            o.folder.mkdirs(o.layout.ops)
            o.folder.mkdirs(o.layout.checkpoints)
            o.folder.mkdirs(o.layout.actors)
        }
        o.outboxDir.mkdirs()
        val segments = listSegments()
        for (s in segments) flushedLast[s.actor] = maxOf(flushedLast[s.actor] ?: 0L, s.last)

        // Overlapping segment files of one actor must agree op for op; a difference is an equivocation.
        val decoded = HashMap<String, SegmentResult?>()
        fun decodeCached(file: SegmentFile): SegmentResult? = decoded.getOrPut(file.key) { decode(file) }
        val found = HashMap<String, Long>()
        val byActor = segments.groupBy { it.actor }
        for ((a, files) in byActor) {
            val active = ArrayList<SegmentFile>()
            for (file in files) {
                active.removeAll { it.last < file.first }
                for (other in active) {
                    val x = decodeCached(other) as? SegmentResult.Ok ?: continue
                    val y = decodeCached(file) as? SegmentResult.Ok ?: continue
                    val from = maxOf(other.first, file.first)
                    val to = minOf(other.last, file.last)
                    var seq = from
                    while (seq <= to) {
                        if (x.ops[(seq - other.first).toInt()].encode() != y.ops[(seq - file.first).toInt()].encode()) {
                            found[a] = minOf(found[a] ?: Long.MAX_VALUE, seq)
                            break
                        }
                        seq++
                    }
                }
                active += file
            }
        }
        equivocations = found

        // checkpoint parts first; without usable parts everything is replayed
        var usedCheckpoint = false
        enforcedNoted = o.folder.stat(o.layout.membership) != null
        val parts = if (enforcedNoted) emptyList() else readParts()
        // always asked: a state kept from the last run (SQLite) is used as it is, and a new one is marked ready
        usedCheckpoint = state.loadParts(parts, o.libraryId, equivocations) && state.versionVector().isNotEmpty()
        val coverage = state.versionVector()
        var segmentsRead = 0
        var admitted = 0
        for (s in segments) {
            if (s.last <= (coverage[s.actor] ?: 0L)) {
                processed += s.key
                continue
            }
            val value = decodeCached(s)
            if (value == null) {
                scheduleRetry(s.key)
                continue
            }
            if (value is SegmentResult.Err) {
                noteUnreadable(s.key, value.error.javaClass.simpleName)
                continue
            }
            value as SegmentResult.Ok
            segmentsRead++
            processed += s.key
            val above = coverage[s.actor] ?: 0L
            val skip = maxOf(0L, above - value.first + 1).toInt()
            val lines = value.ops.drop(skip).map { it.encode() }
            for (r in state.admit(lines, value.signer)) if (r == AdmitKind.Admitted || r == AdmitKind.Kept) admitted++
        }
        // each variant of an equivocated op is admitted so the state sets the actor's suffix aside
        for ((a, files) in byActor) {
            if (a !in equivocations) continue
            for (f in files) (decodeCached(f) as? SegmentResult.Ok)?.let { state.admit(it.ops.map { op -> op.encode() }, it.signer) }
        }

        // the outbox: operations written before a crash; a torn last line is dropped
        var outboxOps = 0
        val own = ArrayList<Long>()
        val outboxText = if (outboxFile.exists()) outboxFile.readText(Charsets.UTF_8) else ""
        outboxBytes = outboxText.toByteArray(Charsets.UTF_8).size.toLong()
        val lines = outboxText.split('\n').toMutableList()
        val tail = lines.removeAt(lines.size - 1)
        if (tail.isNotEmpty()) {
            val kept = lines.joinToString("") { "$it\n" }
            writeLocalAtomic(outboxFile, kept.toByteArray(Charsets.UTF_8))
            outboxBytes = kept.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val records = ArrayList<Pending>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val op = (decodeOp(line) as? OpDecode.Ok)?.op ?: continue
            records += Pending(op, line)
        }
        for (batch in records.chunked(1500)) {
            val results = state.admit(batch.map { it.text }, deviceId.takeIf { batch[0].op.id.actor == actor })
            batch.forEachIndexed { i, rec ->
                if (results[i] == AdmitKind.Malformed) return@forEachIndexed
                outboxOps++
                if (rec.op.id.actor == actor) own += rec.op.id.seq
                if (rec.op.id.seq > (flushedLast[rec.op.id.actor] ?: 0L)) pending += rec
            }
        }
        pending.sortWith(compareBy<Pending>({ it.op.id.actor }, { it.op.id.seq }))
        rebuildIfNeeded()
        noteEnforced()

        // a restored or cloned install: the folder holds ops of this actor beyond what this device ever wrote
        val seqState = readSeq()
        val localLast = maxOf(seqState, own.maxOrNull() ?: 0L)
        val folderLast = maxOf(flushedLast[actor] ?: 0L, coverage[actor] ?: 0L)
        var rotated = false
        if (folderLast > localLast) {
            val retired = actor
            actor = o.newId()
            rotated = true
            o.onRotate?.invoke(retired, actor)
        }
        nextSeq = if (rotated) 1L else localLast + 1
        hlc = state.maxHlc()?.let { Hlc.receive(null, it, o.now(), actor) }
        opsSinceCheckpoint = admitted
        pendingOps = pending.size
        stats = OpenStats(System.currentTimeMillis() - started, usedCheckpoint, segmentsRead, admitted, outboxOps, rotated, equivocations)
        if (!o.readOnly) runCatching { writeActorNote() }
        if (pending.isNotEmpty() && !o.readOnly) scheduleFlush(0)
        if (retries.isNotEmpty()) armRetry()
    }

    private fun readSeq(): Long {
        val f = seqFile()
        if (!f.exists()) return 0L
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)).optLong("lastSeq", 0L) }.getOrDefault(0L)
    }

    private fun writeActorNote() {
        val note = "{\n  \"name\": ${JSONObject.quote(o.deviceName)},\n  \"platform\": \"android\",\n  \"appVersion\": ${JSONObject.quote(o.appVersion)},\n" +
            "  \"lastSeenAt\": ${JSONObject.quote(java.time.Instant.ofEpochMilli(o.now()).toString())}\n}"
        o.folder.replace("${o.layout.actors}/$actor.json", note.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ reading

    /** The whole state as canonical JSON text (tests and diagnostics). */
    fun viewsJson(): CompletableFuture<String> = submit { state.viewsJson() }

    fun versionVector(): CompletableFuture<Map<String, Long>> = submit { state.versionVector() }

    /** What deletes and removes of these keys must observe (see [MergeState.observed]). */
    fun observed(
        items: List<String> = emptyList(),
        playlists: List<String> = emptyList(),
        spaces: List<String> = emptyList(),
        setElements: List<Pair<String, String>> = emptyList(),
    ): CompletableFuture<Observed> = submit { state.observed(items, playlists, spaces, setElements) }

    fun findings(): CompletableFuture<List<String>> = submit {
        val out = ArrayList<String>(state.findings())
        for ((a, s) in equivocations) out += "equivocation:$a:$s"
        for ((k, p) in problems) out += "unreadable-segment:$k:$p"
        out
    }

    // ------------------------------------------------------------------ writing

    /**
     * Turns drafts into operations. They are appended to the outbox and fsynced before they are admitted, so a change that
     * returned is never lost (on this device). "In the Keepfin folder" is true only after a flush.
     */
    fun apply(drafts: List<OpDraft>): CompletableFuture<Unit> {
        if (drafts.isEmpty()) return CompletableFuture.completedFuture(Unit)
        return submit { applyNow(drafts) }
    }

    private fun applyNow(drafts: List<OpDraft>) {
        run {
            check(!closed) { "The library store is closed." }
            check(!o.readOnly) { "This Keepfin folder was made by a newer version and is read-only." }
            var clock = hlc
            var seq = nextSeq
            val ops = ArrayList<Pending>(drafts.size)
            val now = o.now()
            for (d in drafts) {
                clock = Hlc.tick(clock, now, actor)
                val op = Op(OpId(actor, seq), clock, d.kind, d.body)
                ops += Pending(op, op.encode())
                seq++
            }
            o.outboxDir.mkdirs()
            val chunk = ops.joinToString("") { "${it.text}\n" }
            appendDurable(outboxFile, chunk)
            outboxBytes += chunk.toByteArray(Charsets.UTF_8).size
            for (batch in ops.chunked(2000)) {
                for (r in state.admit(batch.map { it.text }, deviceId)) {
                    if (r != AdmitKind.Admitted && r != AdmitKind.Kept) throw IllegalStateException("The library rejected its own change ($r).")
                }
            }
            pending.addAll(ops)
            nextSeq = seq
            hlc = clock
            opsSinceCheckpoint += ops.size
            pendingOps = pending.size
            revision++
            noteEnforced()
            scheduleFlush(if (pending.size >= o.flushOps) 0 else o.flushIdleMs)
            armFold()
            emit(false)
        }
    }

    /** The state's write-ahead log is folded into its file a few seconds after the last change (a commit never pays for it). */
    private fun armFold() {
        if (closed || foldTimer != null) return
        foldTimer = schedule(FOLD_DELAY_MS) {
            foldTimer = null
            if (!closed) runCatching { state.checkpoint() }
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        if (closed || o.readOnly) return
        flushTimer?.cancel(false)
        flushTimer = schedule(delayMs) {
            flushTimer = null
            if (!closed) flushNow(false)
        }
    }

    /** Writes the outbox as segments (and a checkpoint when due). */
    fun flush(): CompletableFuture<Unit> = submit {
        flushTimer?.cancel(false)
        flushTimer = null
        flushNow(false)
    }

    /** The app goes to the background: everything is in a segment and the outbox is emptied. */
    fun onBackground(): CompletableFuture<Unit> = submit {
        flushTimer?.cancel(false)
        flushTimer = null
        flushNow(true)
        // idle: fold the state's write-ahead log into its file
        runCatching { state.checkpoint() }
    }

    private fun flushNow(compact: Boolean) {
        try {
            flushInner(compact)
            lastError = null
        } catch (e: FolderError) {
            lastError = e
            throw e
        }
    }

    private fun flushInner(compact: Boolean) {
        if (pending.isEmpty()) {
            if (compact && outboxBytes > 0) compactOutbox()
            return
        }
        val maxOps = minOf(o.segmentMaxOps, 10_000)
        val runs = ArrayList<ArrayList<Pending>>()
        var runBytes = 0
        for (item in pending) {
            val run = runs.lastOrNull()
            val last = run?.lastOrNull()
            if (run != null && last != null && last.op.id.actor == item.op.id.actor && last.op.id.seq + 1 == item.op.id.seq && run.size < maxOps && runBytes < 4_000_000) {
                run += item
                runBytes += item.text.length
            } else {
                runs += arrayListOf(item)
                runBytes = item.text.length
            }
        }
        val done = HashSet<Pending>()
        try {
            for (run in runs) {
                val a = run[0].op.id.actor
                val first = run[0].op.id.seq
                val last = run.last().op.id.seq
                val bytes = encodeSegment(o.libraryId, a, run.map { it.op }, if (a == actor) o.signer else null)
                val name = segmentFileName(first, last)
                val path = "${o.layout.ops}/$a/$name"
                val existing = o.folder.stat(path)
                if (existing != null) {
                    // written by an earlier run that stopped before emptying the outbox: it must be the same bytes
                    if (!sameSegment(o.folder.read(path), bytes, a)) throw IllegalStateException("A different segment already exists at $path.")
                } else {
                    try {
                        o.folder.publish(path, bytes)
                    } catch (e: FolderError.NameConflict) {
                        if (!sameSegment(o.folder.read(path), bytes, a)) throw IllegalStateException("A different segment already exists at $path.")
                    }
                }
                processed += "$a/$name"
                segmentsWritten++
                flushedLast[a] = maxOf(flushedLast[a] ?: 0L, last)
                done.addAll(run)
            }
        } finally {
            if (done.isNotEmpty()) {
                pending = ArrayList(pending.filter { it !in done })
                pendingOps = pending.size
            }
        }
        if (compact || outboxBytes > OUTBOX_COMPACT_BYTES) compactOutbox()
        if (opsSinceCheckpoint >= o.checkpointEvery) scheduleCheckpoint()
    }

    /** The counter is saved before the outbox is emptied, so a restored install is told from a crash. */
    private fun compactOutbox() {
        writeLocalAtomic(seqFile(), "{\"actor\":${JSONObject.quote(actor)},\"lastSeq\":${nextSeq - 1}}".toByteArray(Charsets.UTF_8))
        val rest = pending.joinToString("") { "${it.text}\n" }
        writeLocalAtomic(outboxFile, rest.toByteArray(Charsets.UTF_8))
        outboxBytes = rest.toByteArray(Charsets.UTF_8).size.toLong()
    }

    // ------------------------------------------------------------------ checkpoints

    /** The checkpoint parts as sources: nothing is read until the state asks (a part is tens of megabytes; the whole checkpoint is hundreds). */
    private fun readParts(): List<PartSource> {
        val out = ArrayList<PartSource>()
        for (shard in CHECKPOINT_SHARDS) {
            val listing = try {
                o.folder.list("${o.layout.checkpoints}/$shard")
            } catch (e: FolderError.NotFound) {
                continue
            }
            for (f in listing.entries) {
                if (f.isDirectory || !f.name.endsWith(".ckpt")) continue
                val path = "${o.layout.checkpoints}/$shard/${f.name}"
                out += object : PartSource {
                    // a part that vanished or cannot be read is a cache miss: an empty source is skipped by the loader
                    override fun head(): ByteArray = try {
                        o.folder.openRead(path).use { s ->
                            val buffer = ByteArray(4_300)
                            var n = 0
                            while (n < buffer.size) { val r = s.read(buffer, n, buffer.size - n); if (r < 0) break; n += r }
                            buffer.copyOf(n)
                        }
                    } catch (e: FolderError.NotFound) { ByteArray(0) }

                    override fun bytes(): ByteArray = try { o.folder.read(path) } catch (e: FolderError.NotFound) { ByteArray(0) }
                }
            }
        }
        return out
    }

    private fun scheduleCheckpoint() {
        if (closed || checkpointTimer != null || o.readOnly) return
        checkpointTimer = schedule(o.checkpointDelayMs) {
            checkpointTimer = null
            if (!closed) writeParts()
        }
    }

    private fun writeParts() {
        if (closed || o.readOnly || opsSinceCheckpoint < o.checkpointEvery) return
        // a checkpoint never holds an operation that is not in a segment yet
        if (pending.isNotEmpty()) flushInner(false)
        opsSinceCheckpoint = 0
        val name = "${state.maxHlc()?.wire() ?: "0000000000000.000000"}-$actor.ckpt"
        for (shard in state.dirtyShards()) {
            val bytes = state.partBytes(o.libraryId, shard) ?: continue
            val path = "${o.layout.checkpoints}/$shard/$name"
            try {
                o.folder.publish(path, bytes)
            } catch (e: FolderError.NameConflict) {
                o.folder.replace(path, bytes)
            }
            // parts are caches: this actor keeps its newest of each shard
            val old = try {
                o.folder.list("${o.layout.checkpoints}/$shard").entries
            } catch (e: FolderError.NotFound) {
                emptyList()
            }
            for (f in old) if (f.name != name && f.name.endsWith("-$actor.ckpt")) runCatching { o.folder.remove("${o.layout.checkpoints}/$shard/${f.name}") }
        }
    }

    // ------------------------------------------------------------------ other actors

    private fun scheduleRetry(key: String) {
        val attempts = (retries[key]?.first ?: 0) + 1
        val delay = minOf(o.retryMs * (1L shl minOf(attempts - 1, 20)), 30_000L)
        retries[key] = Pair(attempts, System.currentTimeMillis() + delay)
    }

    private fun noteUnreadable(key: String, reason: String) {
        // the first tries are another device still writing the file; only a persistent failure is reported
        scheduleRetry(key)
        if ((retries[key]?.first ?: 0) >= 6) problems[key] = reason
    }

    private fun armRetry() {
        if (closed || retryTimer != null || retries.isEmpty()) return
        val next = retries.values.minOf { it.second }
        retryTimer = schedule(maxOf(20L, next - System.currentTimeMillis())) {
            retryTimer = null
            rescanNow(false)
        }
    }

    /** An equivocation moved a set-aside range: the state is rebuilt from every segment and the outbox (rare, so it is simple and blocking). */
    private fun rebuildIfNeeded() {
        if (!state.needsRebuild()) return
        val files = listSegments()
        // one cursor, two views: the state asks for an op line and then for the signer of that same line, so the files are read once, lazily
        var lastSigner: String? = null
        val lines = sequence {
            for (f in files) (decode(f) as? SegmentResult.Ok)?.let { r -> for (op in r.ops) { lastSigner = r.signer; yield(op.encode()) } }
            for (p in pending) { lastSigner = deviceId.takeIf { p.op.id.actor == actor }; yield(p.text) }
        }.iterator()
        val signers = object : Iterator<String?> {
            override fun hasNext() = true
            override fun next(): String? = lastSigner
        }
        state.rebuild(lines, signers)
    }

    /** Reads segments that arrived since the last look and admits their operations. */
    fun rescan(force: Boolean = false): CompletableFuture<Unit> = submit { rescanNow(force) }

    private fun rescanNow(force: Boolean) {
        if (closed) return
        var changed = false
        try {
            for (s in listSegments()) {
                if (s.key in processed) continue
                val retry = retries[s.key]
                if (retry != null && retry.second > System.currentTimeMillis() && !force) continue
                val value = try {
                    decode(s)
                } catch (e: FolderError.NotFound) {
                    null
                }
                if (value == null) {
                    scheduleRetry(s.key)
                    continue
                }
                if (value is SegmentResult.Err) {
                    noteUnreadable(s.key, value.error.javaClass.simpleName)
                    continue
                }
                value as SegmentResult.Ok
                processed += s.key
                retries.remove(s.key)
                problems.remove(s.key)
                flushedLast[s.actor] = maxOf(flushedLast[s.actor] ?: 0L, s.last)
                for (r in state.admit(value.ops.map { it.encode() }, value.signer)) {
                    if (r == AdmitKind.Admitted || r == AdmitKind.Kept) {
                        changed = true
                        opsSinceCheckpoint++
                    } else if (r == AdmitKind.Equivocation) changed = true
                }
            }
            lastError = null
        } catch (e: FolderError) {
            lastError = e
            throw e
        } finally {
            if (changed) {
                rebuildIfNeeded()
                noteEnforced()
                state.maxHlc()?.let { hlc = Hlc.receive(hlc, it, o.now(), actor) }
                revision++
                armFold()
                emit(true)
            }
            armRetry()
        }
    }

    /** True when [existing] holds the same ops as the segment [fresh] would (a signature differs from run to run, so bytes are not compared). */
    private fun sameSegment(existing: ByteArray, fresh: ByteArray, expectedActor: String): Boolean {
        if (existing.contentEquals(fresh)) return true
        val a = decodeSegment(existing, o.libraryId) as? SegmentResult.Ok ?: return false
        val b = decodeSegment(fresh, o.libraryId) as? SegmentResult.Ok ?: return false
        return a.actor == expectedActor && a.first == b.first && a.last == b.last && a.ops.size == b.ops.size && a.ops.indices.all { a.ops[it].encode() == b.ops[it].encode() }
    }

    // ------------------------------------------------------------------ membership (network spec 9.1 #1 and #2)

    /** Remembers for cold opens that a membership root is held (the folder is then replayed from segments, not from checkpoints). */
    private fun noteEnforced() {
        if (enforcedNoted || o.readOnly || !state.membership().active) return
        enforcedNoted = true
        runCatching { o.folder.replace(o.layout.membership, "membership is enforced for this library\n".toByteArray(Charsets.UTF_8)) }
    }

    /** Who may write, as the member ops admitted so far define it. */
    fun membership(): CompletableFuture<MembershipView> = submit { state.membership() }

    /** Runs [work] on the store thread against the merge state (point queries that must see a consistent state). */
    fun <T> withState(work: (MergeState) -> T): CompletableFuture<T> = submit { work(state) }

    /**
     * The first signed op of an enforced library: signs the freezing of unsigned history (every actor's last segment is named by
     * its hash). A no-op when a membership root is held. With [grant] the first grant goes into the same segment, so no peer ever
     * holds an enforced library that does not know it.
     */
    fun enableMembership(grant: MemberGrant? = null): CompletableFuture<Unit> = submit {
        val signer = o.signer ?: throw IllegalStateException("This device has no key to sign with.")
        check(!closed && !o.readOnly) { "The library store is closed or read-only." }
        if (state.membership().active) return@submit
        flushNow(false)
        val last = HashMap<String, SegmentFile>()
        for (s in listSegments()) { val known = last[s.actor]; if (known == null || s.last > known.last) last[s.actor] = s }
        val actors = last.entries.sortedBy { it.key }.map { (a, s) ->
            jObj("actor" to JStr(a), "seq" to JNum(s.last), "lastSegmentHash" to JStr(sha256Hex(o.folder.read(s.path))))
        }
        val drafts = ArrayList<OpDraft>()
        drafts += OpDraft("member.genesis", mapOf("deviceKey" to JStr(base64Encode(signer.spki)), "actors" to JArr(actors)))
        if (grant != null) drafts += grantDraft(grant.actor, grant.spkiBase64, grant.perms, 1)
        applyNow(drafts)
        flushNow(false)
    }

    private fun grantDraft(actor: String, spkiBase64: String, perms: List<String>, epoch: Long) =
        OpDraft("member.grant", mapOf("actor" to JStr(actor), "deviceKey" to JStr(spkiBase64), "perms" to JArr(perms.map { JStr(it) }), "epoch" to JNum(epoch)))

    private fun requireAdmin(): MembershipView {
        val info = state.membership()
        check(info.active) { "Devices are not enabled for this library yet." }
        check(info.members[actor]?.perms?.contains("admin") == true) { "Only an administrator device can add or remove devices." }
        return info
    }

    /** `member.grant`: lets [actor] write with the key [spkiBase64]. Needs admin. */
    fun grantMember(actor: String, spkiBase64: String, perms: List<String>): CompletableFuture<Unit> = submit {
        val info = requireAdmin()
        applyNow(listOf(grantDraft(actor, spkiBase64, perms, (info.members[actor]?.epoch ?: 0L) + 1)))
        flushNow(false)
    }

    /** `member.revoke`: [actor]'s ops above [cutoffSeq] are set aside, never applied. Needs admin. */
    fun revokeMember(actor: String, cutoffSeq: Long): CompletableFuture<Unit> = submit {
        requireAdmin()
        applyNow(listOf(OpDraft("member.revoke", mapOf("actor" to JStr(actor), "cutoff" to jObj("seq" to JNum(cutoffSeq), "segmentHash" to JStr(""))))))
        flushNow(false)
    }

    // ------------------------------------------------------------------ end

    /** Writes everything that is pending (segments, a due checkpoint), then stops the store thread. */
    fun close(): CompletableFuture<Unit> {
        val done = submit {
            if (!closed) {
                flushTimer?.cancel(false)
                retryTimer?.cancel(false)
                checkpointTimer?.cancel(false)
                foldTimer?.cancel(false)
                if (!o.readOnly) {
                    flushNow(true)
                    writeParts()
                }
                closed = true
                runCatching { state.close() }
            }
        }
        done.whenComplete { _, _ -> executor.shutdown() }
        return done
    }

    /** Stops the thread without writing anything (tests that simulate a crash). */
    fun abandon() {
        closed = true
        executor.shutdownNow()
    }
}

// ---------------------------------------------------------------------- device-local files

private fun appendDurable(file: File, text: String) {
    file.parentFile?.mkdirs()
    FileOutputStream(file, true).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
    }
}

/** Writes a device-local file by a temp name, fsync and rename. */
internal fun writeLocalAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID().toString().take(8)}.tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    if (!temp.renameTo(file)) {
        // some file systems do not replace; remove and retry once
        file.delete()
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("Could not write ${file.name}.")
        }
    }
}
