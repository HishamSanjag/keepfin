package app.keepfin.core.copies

/** A stream of sorted entries that can be started again (a file reader, a database cursor, a generator). */
typealias Source<T> = () -> Iterator<T>

/**
 * Everything one planning run reads (spec section 4). The four sources must be sorted by `(rules.key(path), path)`:
 * the planner merges them as streams and fails on a source that is out of order.
 */
class PlannerInput(
    val mode: CopyMode,
    val options: PlaceOptions,
    val rules: NameRules,
    /** D: what the place should hold. */
    val desired: Source<DesiredEntry>,
    /** M: the main folder's inventory. */
    val main: Source<InvLine>,
    /** C: the copy's inventory. */
    val copy: Source<InvLine>,
    /** L: the baseline of this relationship. An empty baseline makes the relationship new. */
    val baseline: Source<BaselineEntry>,
    val library: LibraryFacts,
    val mainComplete: Boolean = true,
    val copyComplete: Boolean = true,
    val generation: Generation = Generation.Equal,
    /** The user confirmed the preview of a new relationship: it may then remove and replace. */
    val previewConfirmed: Boolean = false,
    /** Paths whose deletion on the copy the user confirmed in the preview (two-way): they become `item.delete`. */
    val confirmedDeletes: Set<String> = emptySet(),
    val caps: Capabilities = Capabilities.folder,
    /** The copy volume's timestamp granularity: 2000 on FAT and exFAT, 1 on NTFS. */
    val timestampGranularityMs: Long = 2000,
    /** A full audit: every file on the copy is hashed. */
    val audit: Boolean = false,
    val freeMain: Long? = null,
    val freeCopy: Long? = null,
)

/**
 * The planner of spec section 4: pure, streaming and two-phase. [plan] merges D, M, C and L by path and decides every
 * path with the tables of 4.3 and 4.4. Where a decision needs the hash of a file on the copy that the [Evidence] does
 * not have, it asks: the answer is [PlanOutcome.NeedsHashes] with every hash it could ask for in this round, and the
 * executor computes them and calls [plan] again. A second round is needed only when a hash shows a file was not what
 * its stat suggested, so the loop ends after at most a few rounds.
 */
object Planner {
    fun plan(input: PlannerInput, evidence: Evidence = Evidence.NONE): PlanOutcome = Run(input, evidence).run()
}

private enum class St { SAME, CHANGED, NEW, ABSENT }

private class Pos(val key: String) {
    var d: DesiredEntry? = null
    var m: InvLine? = null
    var c: InvLine? = null
    var l: BaselineEntry? = null

    /** The copy file that a verified move renames onto this position (its baseline and line were moved here too). */
    var moveFrom: InvLine? = null
    var blocked = false
}

private class Res(val actions: List<Action>, val review: List<ReviewItem>, val redo: Boolean = false)

private val NOTHING = Res(emptyList(), emptyList())

private class Stream<T : Any>(private val iter: Iterator<T>, private val pathOf: (T) -> String, private val rules: NameRules, private val name: String) {
    var cur: T? = null
    var curKey: String = ""
    private var prevKey: String? = null
    private var prevPath: String? = null

    init {
        advance()
    }

    fun advance() {
        if (!iter.hasNext()) {
            cur = null
            return
        }
        val n = iter.next()
        val path = pathOf(n)
        val key = rules.key(path)
        val pk = prevKey
        if (pk != null) {
            val c = pk.compareTo(key)
            if (c > 0 || (c == 0 && prevPath!!.compareTo(path) > 0)) throw IllegalArgumentException("The $name stream is not sorted by name: '$prevPath' comes before '$path'")
        }
        prevKey = key
        prevPath = path
        cur = n
        curKey = key
    }
}

private class Run(private val input: PlannerInput, private val evidence: Evidence) {
    private val rules = input.rules
    private val mode = input.mode
    private val actions = ArrayList<Action>()
    private val review = ArrayList<ReviewItem>()
    private val requests = LinkedHashMap<String, HashRequest>()
    private var copyFiles = 0
    private var baselineCount = 0

    private val suspendDestructive = !input.mainComplete || !input.copyComplete || !input.caps.completeEnumeration ||
        input.generation == Generation.Lower || input.generation == Generation.Unknown
    private var additiveOnly = input.generation == Generation.Higher

    private val historyKeys: Set<String> = input.library.placementMoves.flatMapTo(HashSet()) { listOf(rules.key(it.from), rules.key(it.to)) }
    private val contentMoves = input.caps.stableIds
    private val deferred = ArrayList<Pos>()
    private val aCandidates = HashMap<String, ArrayList<Pos>>()
    private val bCandidates = HashMap<String, ArrayList<Pos>>()

    // Renames the library made: an item the baseline recorded under one path that the desired set now places under another. Only
    // candidate positions are listed (the copy still has the old name, nothing sits at the new one), keyed by item.
    private val aItems = HashMap<String, ArrayList<Pos>>()
    private val bItems = HashMap<String, ArrayList<Pos>>()
    private var deferredCount = 0

    fun run(): PlanOutcome {
        val dS = Stream(input.desired(), { it.path }, rules, "desired")
        val mS = Stream(input.main(), { it.path }, rules, "main")
        val cS = Stream(input.copy(), { it.path }, rules, "copy")
        val lS = Stream(input.baseline(), { it.path }, rules, "baseline")
        while (true) {
            var key: String? = null
            if (dS.cur != null) key = dS.curKey
            if (mS.cur != null && (key == null || mS.curKey < key)) key = mS.curKey
            if (cS.cur != null && (key == null || cS.curKey < key)) key = cS.curKey
            if (lS.cur != null && (key == null || lS.curKey < key)) key = lS.curKey
            if (key == null) break
            val p = Pos(key)
            p.d = take(dS, key, "desired", p)
            p.m = take(mS, key, "main", p)
            p.c = take(cS, key, "copy", p)
            p.l = take(lS, key, "baseline", p)
            if (p.c != null) copyFiles++
            if (p.l != null) baselineCount++
            if (p.blocked) continue
            maybeDefer(p)?.let { decide(it) }
        }
        resolveDeferred()
        for (p in deferred) decide(p)
        if (requests.isNotEmpty()) return PlanOutcome.NeedsHashes(requests.values.toList())
        return PlanOutcome.Ready(assemble())
    }

    /** The entry of [s] at [key]; more than one entry at one key is a name collision, reported and never merged. */
    private fun <T : Any> take(s: Stream<T>, key: String, side: String, p: Pos): T? {
        if (s.cur == null || s.curKey != key) return null
        val first = s.cur!!
        s.advance()
        if (s.cur == null || s.curKey != key) return first
        val paths = ArrayList<String>()
        paths.add(pathOf(first))
        while (s.cur != null && s.curKey == key) {
            paths.add(pathOf(s.cur!!))
            s.advance()
        }
        p.blocked = true
        review.add(ReviewItem(ReviewKind.NameCollision, paths[0], detail = "$side: ${paths.joinToString(" | ")}"))
        return null
    }

    private fun pathOf(x: Any): String = when (x) {
        is DesiredEntry -> x.path
        is InvLine -> x.path
        is BaselineEntry -> x.path
        else -> throw IllegalStateException()
    }

    // ---- hashes ----

    private fun hashOf(side: Side, line: InvLine, why: String): String? {
        val h = evidence.hash(side, line)
        if (h == null) requests.getOrPut("$side|${line.path}") { HashRequest(side, line.path, line.size, line.mtimeMs, why) }
        return h
    }

    private fun statSame(line: InvLine, base: Stat): Boolean {
        if (line.size != base.size || line.mtimeMs != base.mtimeMs) return false
        val a = line.fileId
        val b = base.fileId
        return a == null || b == null || a == b
    }

    private fun withinGranularity(line: InvLine, scannedAtMs: Long): Boolean {
        val d = line.mtimeMs - scannedAtMs
        return (if (d < 0) -d else d) < input.timestampGranularityMs
    }

    /** The copy file's state against the baseline, and its hash when one was needed or known. UNKNOWN is signalled by a null state. */
    private class CopyView(val state: St?, val hash: String?, val assumed: Boolean)

    private fun copyView(c: InvLine?, l: BaselineEntry?, force: Boolean, needContentWhenNew: Boolean): CopyView {
        if (c == null) return CopyView(St.ABSENT, null, false)
        if (l == null) {
            if (!needContentWhenNew) return CopyView(St.NEW, null, false)
            val h = hashOf(Side.Copy, c, "new file on the copy") ?: return CopyView(null, null, false)
            return CopyView(St.NEW, h, false)
        }
        val needs = force || input.audit || !statSame(c, l.copy) || withinGranularity(c, l.scannedAtMs)
        if (!needs) return CopyView(St.SAME, null, true)
        val why = if (force) "about to replace or remove" else if (input.audit) "full audit" else "stat differs from the baseline"
        val h = hashOf(Side.Copy, c, why) ?: return CopyView(null, null, false)
        return CopyView(if (h == l.contentId) St.SAME else St.CHANGED, h, false)
    }

    // ---- deferral: positions that may be the two ends of a move wait until the whole merge was seen ----

    private fun maybeDefer(p: Pos): Pos? {
        if (historyKeys.isNotEmpty() && p.key in historyKeys && deferredCount < MAX_DEFERRED) return defer(p)
        if (deferredCount >= MAX_DEFERRED) return p
        val l = p.l
        val c = p.c
        val d = p.d
        var held = false
        // the library's own renames (spec 4.5): the same item, another placement. No file identity is needed.
        if (l != null && d == null && c != null && !input.library.isDeleted(l.itemId)) {
            aItems.getOrPut(l.itemId) { ArrayList() }.add(p)
            held = true
        } else if (d != null && l == null && c == null) {
            bItems.getOrPut(d.itemId) { ArrayList() }.add(p)
            held = true
        }
        if (contentMoves) {
            // A: the baseline has it, main lost it, the copy still holds it unchanged
            if (l != null && p.m == null && c != null && statSame(c, l.copy) && !input.library.isDeleted(l.itemId)) {
                aCandidates.getOrPut(l.contentId) { ArrayList() }.add(p)
                held = true
            }
            // B: main has it under a name the baseline does not know, the copy has nothing there
            if (d != null && l == null && p.m != null && c == null) {
                bCandidates.getOrPut(d.contentId) { ArrayList() }.add(p)
                held = true
            }
        }
        return if (held) defer(p) else p
    }

    private fun defer(p: Pos): Pos? {
        deferred.add(p)
        deferredCount++
        return null
    }

    private fun resolveDeferred() {
        if (deferred.isEmpty()) return
        val byKey = HashMap<String, Pos>()
        for (p in deferred) byKey[p.key] = p
        // moves the library recorded
        for (mv in input.library.placementMoves) {
            val src = byKey[rules.key(mv.from)] ?: continue
            val dst = byKey[rules.key(mv.to)] ?: continue
            if (src === dst) continue
            if (src.l?.itemId != mv.itemId || dst.d?.itemId != mv.itemId) continue
            tryMove(src, dst)
        }
        // renames the library made without telling the planner: the same item at another placement. One candidate on each side, or
        // (an item with several files: companions, conflict heads) one per content.
        for ((item, srcs) in aItems) {
            val dsts = bItems[item] ?: continue
            if (srcs.size == 1 && dsts.size == 1) {
                tryMove(srcs[0], dsts[0])
                continue
            }
            val byContent = HashMap<String, ArrayList<Pos>>()
            for (s in srcs) s.l?.let { byContent.getOrPut(it.contentId) { ArrayList() }.add(s) }
            val dstByContent = HashMap<String, ArrayList<Pos>>()
            for (d in dsts) d.d?.let { dstByContent.getOrPut(it.contentId) { ArrayList() }.add(d) }
            for ((content, a) in byContent) {
                val b = dstByContent[content] ?: continue
                if (a.size == 1 && b.size == 1) tryMove(a[0], b[0])
            }
        }
        // unexplained renames, by content, only when exactly one file lost its name and exactly one gained it
        for ((content, srcs) in aCandidates) {
            val dsts = bCandidates[content] ?: continue
            if (srcs.size == 1 && dsts.size == 1) tryMove(srcs[0], dsts[0])
        }
    }

    /** Renames the copy's file onto [dst] when the copy still holds the baseline's bytes at [src] and has nothing at [dst]. */
    private fun tryMove(src: Pos, dst: Pos) {
        val sl = src.l ?: return
        val sc = src.c ?: return
        if (dst.l != null || dst.c != null || src.moveFrom != null || dst.moveFrom != null) return
        val view = copyView(sc, sl, force = false, needContentWhenNew = false)
        if (view.state != St.SAME) return
        val dPath = dst.d?.path ?: return
        dst.l = sl.copy(path = dPath)
        dst.c = sc
        dst.moveFrom = sc
        src.l = null
        src.c = null
    }

    // ---- the decision of one path ----

    private fun decide(p: Pos) {
        var force = false
        for (attempt in 0 until 2) {
            val r = evaluate(p, force)
            if (r == null) return // hashes requested
            if (r.redo) {
                force = true
                continue
            }
            commit(r)
            return
        }
    }

    private fun act(
        kind: ActionKind,
        path: String,
        d: DesiredEntry? = null,
        itemId: String? = d?.itemId,
        contentId: String? = d?.contentId,
        bytes: Long = d?.bytes ?: 0,
        toPath: String? = null,
        expect: Expect? = null,
        archive: Archive? = null,
        reason: RemoveReason? = null,
        displaced: Long = 0,
        review: Boolean = false,
        revisionOp: String? = d?.revisionOp,
    ) = Action(kind, path, toPath, itemId, revisionOp, contentId, bytes, displaced, expect, archive, reason, review)

    private fun isDestructive(a: Action): Boolean = when (a.kind) {
        ActionKind.RemoveFromCopy, ActionKind.DeleteItem -> true
        ActionKind.CopyTo, ActionKind.ReviseFromCopy -> a.expect?.contentId != null
        else -> false
    }

    private fun isAdditive(a: Action): Boolean = when (a.kind) {
        ActionKind.CopyTo -> a.expect?.contentId == null
        ActionKind.ImportNew, ActionKind.RestoreFromCopy, ActionKind.Converge, ActionKind.ForgetBaseline, ActionKind.RestoreEditedDeleted -> true
        ActionKind.ReviseFromCopy -> a.expect?.contentId == null
        else -> false
    }

    /** Applies the safety filters to one position's actions as a group: all of them or none. */
    private fun commit(r: Res) {
        review.addAll(r.review)
        if (r.actions.isEmpty()) return
        if (suspendDestructive && r.actions.any { isDestructive(it) }) {
            review.add(ReviewItem(ReviewKind.Suspended, r.actions[0].path, r.actions[0].itemId, suspendReason(), r.actions[0]))
            return
        }
        if (additiveOnly && !r.actions.all { isAdditive(it) }) {
            review.add(ReviewItem(ReviewKind.Difference, r.actions[0].path, r.actions[0].itemId, "withheld until the preview is confirmed", r.actions[0]))
            return
        }
        actions.addAll(r.actions)
    }

    private fun suspendReason(): String = when {
        !input.mainComplete -> "the main inventory is incomplete"
        !input.copyComplete -> "the copy inventory is incomplete"
        !input.caps.completeEnumeration -> "the transport cannot list completely"
        input.generation == Generation.Lower -> "the copy looks restored or cloned (lower generation)"
        else -> "the copy's generation is unknown"
    }

    private fun res(vararg a: Action) = Res(a.toList(), emptyList())

    private fun withReview(r: Res, vararg items: ReviewItem) = Res(r.actions, r.review + items.toList())

    /** Null when hashes were requested. A [Res] with `redo` asks for the copy hash before something destructive. */
    private fun evaluate(p: Pos, force: Boolean): Res? {
        val d = p.d
        val l = p.l
        val moveAction = p.moveFrom?.let { from ->
            val to = p.d!!.path
            act(ActionKind.MoveOnCopy, from.path, toPath = to, itemId = l?.itemId, contentId = l?.contentId, expect = Expect(l?.contentId, from.fileId))
        }
        val inner = if (d == null) notHeld(p, force) else held(p, d, force)
        if (inner == null) return null
        if (inner.redo) return inner
        return if (moveAction == null) inner else Res(listOf(moveAction) + inner.actions, inner.review)
    }

    private fun held(p: Pos, d: DesiredEntry, force: Boolean): Res? {
        val m = p.m
        val c = p.c
        val l = p.l
        val twoWay = mode == CopyMode.TwoWay
        val ms = when {
            m == null -> St.ABSENT
            l == null -> St.NEW
            l.itemId == d.itemId && d.contentId == l.contentId -> St.SAME
            else -> St.CHANGED
        }
        val needContent = twoWay || m != null
        val cv = copyView(c, l, force, needContent && ms != St.ABSENT || twoWay)
        val cs = cv.state ?: return null
        val cHash = cv.hash
        // where the copy's file is when this position's actions run (a move renames it first)
        val cNow = if (p.moveFrom != null) d.path else c?.path
        val copyPath = cNow ?: d.path
        val archiveSide = if (mode == CopyMode.Backup) Archive.Versions else Archive.Trash

        fun copyTo(archive: Archive?, expectContent: String?): Action = act(
            ActionKind.CopyTo, copyPath, d, expect = if (c == null) Expect(null) else Expect(expectContent, c.fileId),
            archive = archive, displaced = if (c == null) 0 else c.size,
        )

        fun converge() = act(ActionKind.Converge, d.path, d, expect = Expect(d.contentId))

        // replacing or removing a copy file whose state is only assumed from its stat needs its hash first
        fun needsProof(): Res? = if (c != null && cv.assumed && !force) Res(emptyList(), emptyList(), redo = true) else null

        if (twoWay) {
            return when (ms) {
                St.SAME -> when (cs) {
                    St.SAME -> NOTHING
                    St.CHANGED -> res(act(ActionKind.ReviseFromCopy, d.path, d, contentId = cHash, bytes = c!!.size, itemId = l!!.itemId, expect = Expect(l.contentId)))
                    St.ABSENT -> if (d.path in input.confirmedDeletes) {
                        res(act(ActionKind.DeleteItem, d.path, d, reason = RemoveReason.ConfirmedDelete))
                    } else {
                        Res(
                            listOf(copyTo(null, null)),
                            listOf(ReviewItem(ReviewKind.CopyDeletedProposal, d.path, d.itemId, "the copy no longer has it; it is copied back unless you confirm the deletion")),
                        )
                    }
                    St.NEW -> NOTHING
                }
                St.CHANGED -> when (cs) {
                    St.SAME -> needsProof() ?: res(copyTo(twoWayArchive(l, d), l!!.contentId))
                    St.CHANGED -> if (cHash == d.contentId) res(converge()) else needsProof() ?: conflict(p, d, c!!, cNow!!, cHash!!)
                    St.ABSENT -> res(copyTo(null, null))
                    St.NEW -> NOTHING
                }
                St.NEW -> when (cs) {
                    St.NEW -> if (cHash == d.contentId) res(converge()) else needsProof() ?: twoItems(p, d, c!!, cNow!!, cHash!!)
                    St.ABSENT -> res(copyTo(null, null))
                    else -> NOTHING
                }
                St.ABSENT -> when (cs) {
                    St.SAME -> res(act(ActionKind.RestoreFromCopy, d.path, d, contentId = l!!.contentId, bytes = c!!.size, itemId = l.itemId))
                    St.CHANGED -> res(act(ActionKind.ReviseFromCopy, d.path, d, contentId = cHash, bytes = c!!.size, itemId = l!!.itemId))
                    St.NEW -> if (cHash == d.contentId) {
                        res(act(ActionKind.RestoreFromCopy, d.path, d, contentId = cHash, bytes = c!!.size))
                    } else {
                        Res(
                            listOf(act(ActionKind.ImportNew, c!!.path, d, itemId = null, contentId = cHash, bytes = c.size, toPath = Names.copyPath(d.path, cHash!!))),
                            listOf(ReviewItem(ReviewKind.MissingSource, d.path, d.itemId, "the file is missing on main; the copy holds a different file under that name")),
                        )
                    }
                    St.ABSENT -> Res(emptyList(), listOf(ReviewItem(ReviewKind.MissingEverywhere, d.path, d.itemId, "on neither side")))
                }
            }
        }

        // backup and mirror: the main folder is the authority; the copy's own edits are never taken
        return when (ms) {
            St.SAME, St.CHANGED, St.NEW -> when (cs) {
                St.SAME -> if (ms == St.SAME) NOTHING else needsProof() ?: res(copyTo(archiveSide, l!!.contentId))
                St.CHANGED -> if (cHash == d.contentId) res(converge()) else needsProof() ?: res(copyTo(archiveSide, cHash))
                St.NEW -> if (cHash == d.contentId) res(converge()) else needsProof() ?: res(copyTo(archiveSide, cHash))
                St.ABSENT -> res(copyTo(null, null))
            }
            St.ABSENT -> when (cs) {
                St.SAME, St.CHANGED -> Res(emptyList(), listOf(ReviewItem(ReviewKind.MissingSource, d.path, d.itemId, "missing on main; nothing is removed from the copy")))
                St.NEW -> Res(emptyList(), listOf(ReviewItem(ReviewKind.UnknownFile, c!!.path, null, "a file Keepfin did not put there")))
                St.ABSENT -> Res(emptyList(), listOf(ReviewItem(ReviewKind.MissingSource, d.path, d.itemId, "on neither side")))
            }
        }
    }

    private fun twoWayArchive(l: BaselineEntry?, d: DesiredEntry): Archive? =
        if (!input.caps.conditionalMutation || l == null || l.itemId != d.itemId) Archive.Trash else null

    /** The same file changed on both sides: the copy's bytes are kept on main under another name, then main's version goes over. */
    private fun conflict(p: Pos, d: DesiredEntry, c: InvLine, cNow: String, cHash: String): Res {
        val keep = Names.conflictPath(d.path, cHash)
        return Res(
            listOf(
                act(ActionKind.ImportNew, cNow, d, itemId = d.itemId, contentId = cHash, bytes = c.size, toPath = keep, review = true),
                act(ActionKind.CopyTo, cNow, d, expect = Expect(cHash, c.fileId), archive = Archive.Trash, displaced = c.size),
            ),
            emptyList(),
        )
    }

    /** Two different new files under one name: the copy's becomes its own item under a collision name, then main's goes over. */
    private fun twoItems(p: Pos, d: DesiredEntry, c: InvLine, cNow: String, cHash: String): Res {
        val other = Names.copyPath(d.path, cHash)
        return Res(
            listOf(
                act(ActionKind.ImportNew, cNow, d, itemId = null, contentId = cHash, bytes = c.size, toPath = other),
                act(ActionKind.CopyTo, cNow, d, expect = Expect(cHash, c.fileId), archive = Archive.Trash, displaced = c.size),
            ),
            emptyList(),
        )
    }

    /** A path no desired item names (not held by this place, deleted, or moved away without a record). */
    private fun notHeld(p: Pos, force: Boolean): Res? {
        val c = p.c
        val l = p.l
        val twoWay = mode == CopyMode.TwoWay
        val mirror = mode == CopyMode.Mirror
        if (l == null) {
            if (c == null) return NOTHING
            if (twoWay) {
                val h = hashOf(Side.Copy, c, "new file on the copy") ?: return null
                return res(act(ActionKind.ImportNew, c.path, null, itemId = null, contentId = h, bytes = c.size))
            }
            return Res(emptyList(), listOf(ReviewItem(ReviewKind.UnknownFile, c.path, null, "a file Keepfin did not put there")))
        }
        val deleted = input.library.isDeleted(l.itemId)
        // a lyrics, subtitle or info file of an item (Companions.kt): never a revision of the item, never an item of its own
        val suffix = companionOfEntry(l.revisionOp)
        val companion = suffix != null
        val cv = copyView(c, l, force, false)
        val cs = cv.state ?: return null
        if (cs == St.ABSENT) return res(act(ActionKind.ForgetBaseline, l.path, null, itemId = l.itemId, contentId = l.contentId, revisionOp = l.revisionOp))
        c!!
        // the place stopped carrying companions (or music videos): the ones already on the copy stay, even on a mirror (left out is not deleted)
        if (suffix != null && !deleted && !(if (isVideoCompanion(suffix)) input.options.musicVideos else input.options.companions)) return NOTHING
        val proof = if (cv.assumed && !force) Res(emptyList(), emptyList(), redo = true) else null
        val h = cv.hash ?: l.contentId
        fun remove(reason: RemoveReason) = act(
            ActionKind.RemoveFromCopy, c.path, null, itemId = l.itemId, contentId = h, bytes = c.size,
            expect = Expect(h, c.fileId), archive = Archive.Trash, reason = reason, displaced = c.size, revisionOp = l.revisionOp,
        )
        if (deleted) {
            return when {
                twoWay && cs == St.SAME -> proof ?: res(remove(RemoveReason.ItemDeleted))
                // the item is gone: its lyrics edited on the copy wait in the copy's trash instead of bringing the item back
                twoWay && companion -> proof ?: res(remove(RemoveReason.ItemDeleted))
                twoWay -> res(act(ActionKind.RestoreEditedDeleted, c.path, null, itemId = l.itemId, contentId = h, bytes = c.size, review = true))
                mirror -> proof ?: res(remove(RemoveReason.ItemDeleted))
                else -> Res(emptyList(), listOf(ReviewItem(ReviewKind.KeptOnBackup, c.path, l.itemId, "deleted in the library; kept on the backup until you clean up")))
            }
        }
        return when {
            // an edit at a name no item holds any more (renamed in the library, or no longer held) is kept as a new item:
            // revising through a stale baseline would keep revising the wrong path
            twoWay -> if (cs == St.CHANGED && !companion) {
                res(act(ActionKind.ImportNew, c.path, null, itemId = null, contentId = h, bytes = c.size))
            } else {
                Res(emptyList(), listOf(ReviewItem(ReviewKind.NotHeld, c.path, l.itemId, "no longer held by this copy; left in place")))
            }
            mirror -> proof ?: res(remove(RemoveReason.NoLongerHeld))
            else -> Res(emptyList(), listOf(ReviewItem(ReviewKind.NotHeld, c.path, l.itemId, "no longer held; kept on the backup")))
        }
    }

    // ---- the plan ----

    private fun assemble(): Plan {
        val counts = LinkedHashMap<ActionKind, Int>()
        val bytes = LinkedHashMap<ActionKind, Long>()
        var destructive = 0
        var mainNeeded = 0L
        var copyNeeded = 0L
        for (a in actions) {
            counts[a.kind] = (counts[a.kind] ?: 0) + 1
            bytes[a.kind] = (bytes[a.kind] ?: 0L) + a.bytes
            val copySide = a.kind == ActionKind.RemoveFromCopy || (a.kind == ActionKind.CopyTo && a.expect?.contentId != null)
            if (copySide) destructive++
            when (a.kind) {
                ActionKind.CopyTo -> copyNeeded += a.bytes + (if (a.archive != null) a.displacedBytes else 0L)
                ActionKind.RemoveFromCopy -> copyNeeded += a.displacedBytes
                ActionKind.ImportNew, ActionKind.ReviseFromCopy, ActionKind.RestoreFromCopy, ActionKind.RestoreEditedDeleted -> mainNeeded += a.bytes
                else -> {}
            }
        }
        // the deletion guard (spec 4.6): more than max(percent of the files, 20) destructive actions, or more than half of the copy
        val files = copyFiles
        val threshold = maxOf(files * input.options.deleteGuardPercent / 100, 20)
        var held = false
        var reason: String? = null
        if (destructive > threshold) {
            held = true
            reason = "$destructive removals and replacements are more than $threshold"
        } else if (destructive * 2 > files) {
            held = true
            reason = "$destructive removals and replacements are more than half of the copy's $files files"
        }
        val summary = PlanSummary(
            counts, bytes, destructive, files,
            SpaceCheck(mainNeeded, input.freeMain, input.freeMain?.let { it >= mainNeeded }),
            SpaceCheck(copyNeeded, input.freeCopy, input.freeCopy?.let { it >= copyNeeded }),
        )
        return Plan(actions, review, summary, held, reason, additiveOnly = additiveOnly || (baselineCount == 0 && !input.previewConfirmed))
    }

    private companion object {
        const val MAX_DEFERRED = 100_000
    }

    init {
        // a new relationship (no baseline at all) only adds until its preview is confirmed
        if (!input.previewConfirmed && !input.baseline().hasNext()) additiveOnly = true
    }
}
