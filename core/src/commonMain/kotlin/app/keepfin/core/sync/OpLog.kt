package app.keepfin.core.sync

// Admission (spec section 4): ops reach the state only through OpLog.admit, a pure function of the op's bytes
// and the ops already held. The log keeps every op's bytes (also unknown and invalid ones) and rebuilds the
// state deterministically from the retained ops.

sealed class Admission {
    /** New, valid, known kind: stored, and its delta joined into the state. */
    object Admitted : Admission()

    /** The same op id with byte-identical bytes: ignored. */
    object Duplicate : Admission()

    /** A valid envelope with an unknown kind (newer semantics): stored and counted, not applied. */
    object Kept : Admission()

    /** The body breaks the kind's schema: stored and counted, not applied, finding `invalid:<opId>`. */
    class Invalid(val reason: String) : Admission()

    /** The same op id with different bytes: both stored, the actor's ops from that seq on are set aside. */
    object Equivocation : Admission()

    /** A valid op from a set-aside range of an equivocating actor: stored and counted, not applied. */
    object SetAside : Admission()

    /**
     * A valid op the library's membership refuses (see [Membership]): stored and counted, never applied. [reason] is
     * `unsigned`, `unauthorized` or `revoked`.
     */
    class SetAsideBy(val reason: String) : Admission()

    /**
     * The log was started from a checkpoint that covers this op and does not retain its bytes, so nothing can be
     * compared and the op is not stored. Comparing overlapping segments (to catch an equivocation below a
     * checkpoint's coverage) is the store's job: it must then discard the checkpoint (see Checkpoint.isDiscardedBy).
     */
    object Covered : Admission()

    /** The bytes are not a canonical op envelope; nothing is stored. */
    class Malformed(val problem: OpProblem) : Admission()
}

private enum class HeldKind { Applied, Kept, Invalid }

/** One retained op: its canonical text and what became of it. The op itself is read from the text when needed. */
private class Held(val text: String, val kind: HeldKind, val signer: String? = null, val member: Boolean = false)

/** The op a retained text decodes to (it decoded when it was admitted). */
private fun opOfText(text: String): Op =
    OpTextReader(text).read() ?: (decodeOp(text) as OpDecode.Ok).op

/** The delta of a retained applied op. */
private fun deltaOfText(text: String): State =
    (judgeOp(opOfText(text), utf8LengthOrMinusOne(text)) as Interpretation.Applied).delta

private class SlowCheckpoint(val version: Int, val state: State, val maxHlc: Hlc?)

class OpLog private constructor(
    private val base: Checkpoint?,
    adopted: StateAccumulator?,
    private val baseStateFn: (() -> State)?,
) {
    constructor() : this(null, null, null)

    private val held = LinkedHashMap<OpId, Held>()
    private val variantTexts = HashMap<OpId, MutableSet<String>>()
    private val equivocatedFrom = HashMap<String, Long>()
    private val vector = LinkedHashMap<String, Long>()
    private val pendingSeqs = LinkedHashMap<String, MutableSet<Long>>()
    private fun baseState(): State = baseStateFn?.invoke() ?: base?.state ?: State.EMPTY

    private var acc = adopted ?: StateAccumulator(baseState())
    private var snapshot: State? = null
    private val tracker = Tracker()
    private val invalidIds = LinkedHashSet<OpId>()
    private var heldMaxHlc: Hlc? = null
    private var version = 0
    private var slowCheckpoint: SlowCheckpoint? = null
    private val members = Membership()

    /** The library's membership as the member ops held so far define it. */
    val membership: Membership get() = members

    /**
     * Gives the log the member ops (with their signers) it must know, for a log started from a checkpoint (checkpoints do
     * not carry membership). Ops already held are judged again.
     */
    fun restoreMembership(records: List<SignedOp>) {
        var changed = false
        for (r in records) if (!members.add(r.op, r.signer).none) changed = true
        if (changed) {
            rebuild()
            version++
            tracker.invalidAdded()
        }
    }

    init {
        if (base != null) {
            vector.putAll(base.coverage)
            tracker.reset(acc.live)
        }
    }

    /** The state: the base checkpoint's state joined with the deltas of retained, applied, not set-aside ops. */
    val state: State
        get() = snapshot ?: acc.snapshot().also { snapshot = it }

    /** The same state read in place (no copy): valid only until the next admission, and never to be changed. */
    internal val liveState: State get() = acc.live

    /** Entry ids by playlist, for building song lists without scanning every entry. */
    internal val playlistEntries: Map<String, Set<String>> get() = tracker.playlistEntries

    /** The highest contiguous seq stored per actor. Kept, invalid and equivocated ops count. */
    val versionVector: Map<String, Long> get() = LinkedHashMap(vector)

    /** Seqs held above a gap, per actor. Advertised separately from the vector. */
    val pending: Map<String, Set<Long>> get() = pendingSeqs.mapValues { it.value.toSet() }

    /** Per equivocating actor, the first seq at which two variants exist. Ops from there on are not applied. */
    val setAside: Map<String, Long> get() = LinkedHashMap(equivocatedFrom)

    /** `invalid:<opId>`, `equivocation:<actor>:<seq>` and the state's own findings. */
    val findings: Set<String>
        get() {
            val out = LinkedHashSet<String>(acc.live.findings)
            for (id in invalidIds) out.add("invalid:$id")
            for (id in variantTexts.keys) out.add("equivocation:${id.actor}:${id.seq}")
            return out
        }

    /** [findings] plus `revision-cycle:<item>` for items whose revisions have no head. */
    internal fun allFindings(): Set<String> {
        val out = LinkedHashSet<String>(findings)
        for (id in tracker.cycleItems) out.add("revision-cycle:$id")
        return out
    }

    /** The largest clock among retained ops and the base checkpoint, for the writer's `Hlc.receive` duty. */
    val maxHlc: Hlc?
        get() {
            val a = base?.maxHlc
            val b = heldMaxHlc
            return when {
                a == null -> b
                b == null -> a
                else -> maxOf(a, b)
            }
        }

    fun has(id: OpId): Boolean = held.containsKey(id)

    /** Canonical text of a held op (the lexically smallest variant if the id was equivocated), for re-sending. */
    fun encoded(id: OpId): String? = variants(id).firstOrNull()

    /** Every stored byte string for [id] (more than one only after an equivocation), sorted. */
    fun variants(id: OpId): List<String> {
        val v = variantTexts[id]
        if (v != null) return v.sorted()
        val h = held[id] ?: return emptyList()
        return listOf(h.text)
    }

    private fun isSetAside(id: OpId): Boolean {
        val s = equivocatedFrom[id.actor] ?: return false
        return id.seq >= s
    }

    /** Why membership refuses a held op, or null. */
    private fun memberReason(id: OpId, h: Held): String? =
        if (h.member) members.memberOpVerdict(id, h.signer) else members.verdict(id.actor, id.seq, h.signer)

    private fun rebuild() {
        val fresh = StateAccumulator(baseState())
        for ((id, h) in held) {
            if (h.kind == HeldKind.Applied && !isSetAside(id) && memberReason(id, h) == null) fresh.add(deltaOfText(h.text))
        }
        acc = fresh
        snapshot = null
        tracker.reset(acc.live)
    }

    fun admit(op: Op, signer: String? = null): Admission = admit(op.encode(), signer)

    /** Admits one canonical op line given as text (what `admit(text.encodeToByteArray())` does, without the copies). */
    fun admit(text: String, signer: String? = null): Admission {
        val size = utf8LengthOrMinusOne(text)
        if (size >= 0) {
            val quick = OpTextReader(text).read()
            if (quick != null) return admitDecoded(quick, text, size, signer)
        }
        return admitReference(text.encodeToByteArray(), signer)
    }

    fun admit(bytes: ByteArray, signer: String? = null): Admission {
        if (scanUtf8(bytes, 0, bytes.size) == UTF8_OK && !hasBomBytes(bytes)) {
            val text = bytes.decodeToString()
            val quick = OpTextReader(text).read()
            if (quick != null) return admitDecoded(quick, text, bytes.size, signer)
        }
        return admitReference(bytes, signer)
    }

    /** The reference route: strict decode through the generic JSON tree, then the same judgement. */
    internal fun admitReferenceForTest(bytes: ByteArray): Admission = admitReference(bytes)

    private fun admitReference(bytes: ByteArray, signer: String? = null): Admission {
        val op = when (val d = decodeOp(bytes)) {
            is OpDecode.Bad -> return Admission.Malformed(d.problem)
            is OpDecode.Ok -> d.op
        }
        return admitDecoded(op, bytes.decodeToString(), bytes.size, signer)
    }

    private fun admitDecoded(op: Op, text: String, byteSize: Int, signer: String? = null): Admission {
        val id = op.id

        val existing = held[id]
        if (existing != null) {
            if (existing.text == text) return Admission.Duplicate
            val texts = variantTexts.getOrPut(id) { LinkedHashSet<String>().also { it.add(existing.text) } }
            if (!texts.add(text)) return Admission.Duplicate
            version++
            val from = equivocatedFrom[id.actor]
            if (from == null || id.seq < from) {
                equivocatedFrom[id.actor] = id.seq
                rebuild()
            }
            tracker.invalidAdded() // the findings list gained `equivocation:<id>`
            return Admission.Equivocation
        }
        if (id.seq <= (base?.coverage?.get(id.actor) ?: 0L)) return Admission.Covered

        val interpretation = judgeOp(op, byteSize)
        val kind = when (interpretation) {
            is Interpretation.Applied -> HeldKind.Applied
            is Interpretation.Unknown -> HeldKind.Kept
            is Interpretation.Invalid -> HeldKind.Invalid
        }
        val isMember = kind == HeldKind.Applied && isMemberKind(op.kind)
        held[id] = Held(text, kind, signer, isMember)
        version++
        val hm = heldMaxHlc
        if (hm == null || op.hlc > hm) heldMaxHlc = op.hlc
        advance(id)
        if (isMember) {
            val change = members.add(op, signer)
            if (change.all || change.actors.any { vector.containsKey(it) || pendingSeqs.containsKey(it) }) {
                // the verdicts of ops already held may have changed: judge them all again
                rebuild()
                tracker.invalidAdded()
            }
        }
        val equivocated = isSetAside(id)
        val memberWhy = if (equivocated || kind != HeldKind.Applied) null else memberReason(id, held.getValue(id))
        val aside = equivocated || memberWhy != null
        if (kind == HeldKind.Invalid) {
            invalidIds.add(id)
            tracker.invalidAdded()
        }
        if (kind == HeldKind.Applied && !aside) {
            val delta = (interpretation as Interpretation.Applied).delta
            val live = acc.live
            tracker.beforeAdd(delta, live)
            val before = live.findings.size
            acc.add(delta)
            tracker.afterAdd(delta, live, live.findings.size > before)
            snapshot = null
        }
        return when (interpretation) {
            is Interpretation.Applied -> if (equivocated) Admission.SetAside else if (memberWhy != null) Admission.SetAsideBy(memberWhy) else Admission.Admitted
            is Interpretation.Unknown -> Admission.Kept
            is Interpretation.Invalid -> Admission.Invalid(interpretation.reason)
        }
    }

    private fun advance(id: OpId) {
        val top = vector[id.actor] ?: 0L
        if (id.seq == top + 1) {
            var next = id.seq
            val pend = pendingSeqs[id.actor]
            if (pend != null) {
                while (pend.remove(next + 1)) next++
                if (pend.isEmpty()) pendingSeqs.remove(id.actor)
            }
            vector[id.actor] = next
        } else {
            pendingSeqs.getOrPut(id.actor) { LinkedHashSet() }.add(id.seq)
        }
    }

    // ---- incremental reading ----

    /** The keys whose views may have changed since the last call (`all` after a rebuild or a checkpoint load). */
    fun takeTouched(): Touched = tracker.take()

    private fun liveTags(s: OrSet): List<OpId> {
        if (s.adds.isEmpty()) return emptyList()
        val out = ArrayList<OpId>(s.adds.size)
        for (a in s.adds) if (a !in s.removes) out.add(a)
        if (out.size > 1) out.sort()
        return out
    }

    /** What a remove or delete needs to observe for the listed keys (keys the state lacks come back empty). */
    fun observed(
        items: List<String> = emptyList(),
        playlists: List<String> = emptyList(),
        spaces: List<String> = emptyList(),
        setElements: List<Pair<String, String>> = emptyList(),
    ): Observed {
        val live = acc.live
        val itemOut = LinkedHashMap<String, ObservedItem>()
        for (id in items) {
            val s = live.items[id]
            itemOut[id] = if (s == null) ObservedItem(emptyList(), emptyList()) else ObservedItem(liveTags(s.exists), headsOf(s))
        }
        val plOut = LinkedHashMap<String, List<OpId>>()
        for (id in playlists) plOut[id] = live.playlists[id]?.let { liveTags(it.exists) } ?: emptyList()
        val spOut = LinkedHashMap<String, List<OpId>>()
        for (id in spaces) spOut[id] = live.spaces[id]?.let { liveTags(it.exists) } ?: emptyList()
        val setOut = LinkedHashMap<String, MutableMap<String, List<OpId>>>()
        for ((set, el) in setElements) {
            setOut.getOrPut(set) { LinkedHashMap() }[el] = live.sets[set]?.get(el)?.let { liveTags(it) } ?: emptyList()
        }
        return Observed(itemOut, plOut, spOut, setOut)
    }

    // ---- checkpoints ----

    /** True when the live state is exactly a checkpoint's state: nothing above a gap and no equivocation. */
    private fun liveIsCheckpoint(): Boolean = pendingSeqs.isEmpty() && equivocatedFrom.isEmpty() && variantTexts.isEmpty()

    private fun checkpointCap(): LinkedHashMap<String, Long> {
        val cap = LinkedHashMap<String, Long>()
        for ((actor, top) in vector) {
            val from = equivocatedFrom[actor]
            var limit = if (from != null) minOf(top, from - 1) else top
            val cutoff = members.cutoff(actor)
            if (cutoff != null) limit = minOf(limit, cutoff)
            if (limit > 0) cap[actor] = limit
        }
        return cap
    }

    /** The checkpoint state by folding the retained ops of the covered prefixes (the slow route, for gaps and equivocations). */
    private fun foldCheckpoint(cap: Map<String, Long>): Pair<State, Hlc?> {
        val s = StateAccumulator(baseState())
        var m = base?.maxHlc
        for ((id, h) in held) {
            if (id.seq > (cap[id.actor] ?: 0L)) continue
            val op = opOfText(h.text)
            if (h.kind == HeldKind.Applied && memberReason(id, h) == null) s.add((judgeOp(op, utf8LengthOrMinusOne(h.text)) as Interpretation.Applied).delta)
            if (m == null || op.hlc > m) m = op.hlc
        }
        return Pair(s.snapshot(), m)
    }

    /**
     * A checkpoint of [library] over the contiguous prefixes only: ops held above a gap are never included, and
     * an equivocating actor's coverage stops before the set-aside range.
     */
    fun checkpoint(library: String): Checkpoint {
        val cap = checkpointCap()
        if (liveIsCheckpoint()) return Checkpoint(library, SEMANTICS, cap, maxHlc, acc.snapshot())
        val (state, m) = foldCheckpoint(cap)
        return Checkpoint(library, SEMANTICS, cap, m, state)
    }

    private var partition: Pair<Int, Array<State>>? = null

    /** The part of [src] in shard [index]. When several shards are due, all are cut in one pass and kept until the next admission. */
    private fun projection(src: State, index: Int): State {
        val cached = partition
        if (cached != null && cached.first == version) return cached.second[index]
        var due = 0
        for (i in SHARD_IDS.indices) if ((tracker.dirty shr i) and 1 == 1) due++
        if (due < 4) return projectToShard(src, index)
        val all = partitionByShard(src)
        partition = Pair(version, all)
        return all[index]
    }

    /** The shards whose part is out of date: all of them until each has been written once. */
    fun dirtyShards(): List<String> {
        val out = ArrayList<String>()
        for (i in SHARD_IDS.indices) if ((tracker.dirty shr i) and 1 == 1) out.add(SHARD_IDS[i])
        return out
    }

    /**
     * The part file of [shard] (sync spec 10.4): the checkpoint of this log, restricted to that shard's keys. Marks the
     * shard clean. Null for a name that is not a shard.
     */
    fun partBytes(library: String, shard: String): ByteArray? {
        val index = shardIndexOfName(shard)
        if (index < 0) return null
        val cap = checkpointCap()
        val bytes: ByteArray
        if (liveIsCheckpoint()) {
            bytes = encodeFile(library, cap, maxHlc, SEMANTICS, projection(acc.live, index), shard, index, true)
        } else {
            var cached = slowCheckpoint
            if (cached == null || cached.version != version) {
                val (state, m) = foldCheckpoint(cap)
                cached = SlowCheckpoint(version, state, m)
                slowCheckpoint = cached
            }
            bytes = encodeFile(library, cap, cached.maxHlc, SEMANTICS, projection(cached.state, index), shard, index, true)
        }
        tracker.markShardClean(index)
        if (tracker.dirty == 0) {
            partition = null
            slowCheckpoint = null
        }
        return bytes
    }

    /** The views of the [touched] keys as JSON (see [partialViewsText]). */
    fun partialViewsJson(touched: Touched): String =
        acc.live.partialViewsText(touched, { tracker.playlistEntries[it] }, { allFindings() })

    /** The views of the whole state as JSON (the same text as `viewsJsonText` of the snapshot, without copying it). */
    fun viewsJson(): String = acc.live.viewsJsonText(allFindings())

    companion object {
        /**
         * Starts a log from the parts of a checkpoint (sync spec 10.4): per-shard merge, library coverage = the minimum
         * over the 17 shards. Null when unusable: an equivocation at or below the coverage any part claims.
         */
        fun fromParts(parts: List<CheckpointPart>, library: String, equivocations: Map<String, Long> = emptyMap(), members: List<SignedOp> = emptyList()): OpLog? {
            val reader = PartsReader(library)
            for (p in parts) reader.add(p)
            return fromMerged(reader.build(), equivocations)?.also { if (members.isNotEmpty()) it.restoreMembership(members) }
        }

        internal fun fromMerged(m: MergedParts, equivocations: Map<String, Long>): OpLog? {
            for ((actor, s) in equivocations) if (s <= (m.highest[actor] ?: 0L)) return null
            // the merged accumulator is adopted as the log's state: no second copy of a large library
            val cp = Checkpoint(m.library, SEMANTICS, m.coverage, m.maxHlc, State.EMPTY)
            val log = OpLog(cp, m.acc, { m.baseState() })
            for ((actor, s) in equivocations) log.equivocatedFrom[actor] = s
            if (equivocations.isNotEmpty()) log.rebuild()
            return log
        }

        /**
         * Starts a log from [checkpoint]. Returns null when the checkpoint is unusable: another semantics, or an
         * equivocation (from [equivocations], actor to first seq) at or below its coverage.
         */
        fun fromCheckpoint(checkpoint: Checkpoint, equivocations: Map<String, Long> = emptyMap(), members: List<SignedOp> = emptyList()): OpLog? {
            if (checkpoint.semantics != SEMANTICS) return null
            if (checkpoint.isDiscardedBy(equivocations)) return null
            val log = OpLog(checkpoint, null, null)
            for ((actor, s) in equivocations) log.equivocatedFrom[actor] = s
            if (equivocations.isNotEmpty()) log.rebuild()
            if (members.isNotEmpty()) log.restoreMembership(members)
            return log
        }
    }
}

/** The size, depth and schema rules admission applies to a decoded op of [byteSize] bytes of canonical text. */
internal fun judgeOp(op: Op, byteSize: Int): Interpretation = when {
    byteSize > MAX_OP_BYTES -> Interpretation.Invalid("the op line is larger than $MAX_OP_BYTES bytes")
    1 + jsonDepth(JObj(op.body)) > MAX_JSON_DEPTH -> Interpretation.Invalid("the op nests deeper than $MAX_JSON_DEPTH")
    else -> op.interpret()
}
