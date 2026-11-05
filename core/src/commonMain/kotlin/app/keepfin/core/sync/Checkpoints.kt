package app.keepfin.core.sync

// A checkpoint is a disposable cache: the state after a contiguous prefix of each actor's ops. It never holds
// anything the segments do not. Coverage is prefix-only; a writer with pending ops above a gap builds it from
// the prefix ops only (see OpLog.checkpoint), never from its live state.

const val CHECKPOINT_MAX_BYTES = 64 * 1024 * 1024

/**
 * [state] is exactly the join of the admitted ops in [coverage] (the prefixes 1..coverage[actor]), minus any
 * set-aside ops. [maxHlc] is the largest clock among the covered ops, so a writer that loads the checkpoint can
 * `Hlc.receive` it. [coverage] omits actors with 0.
 */
data class Checkpoint(
    val library: String,
    val semantics: Int,
    val coverage: Map<String, Long>,
    val maxHlc: Hlc?,
    val state: State,
) {
    /** Join of two checkpoints of the same library and semantics, or null when they cannot be merged. */
    fun merge(other: Checkpoint): Checkpoint? {
        if (other.library != library || other.semantics != semantics) return null
        val hlc = when {
            maxHlc == null -> other.maxHlc
            other.maxHlc == null -> maxHlc
            else -> maxOf(maxHlc, other.maxHlc)
        }
        return Checkpoint(library, semantics, VersionVectors.merge(coverage, other.coverage), hlc, state.join(other.state))
    }

    /** True when an equivocation at `(actor, s)` with `s <= coverage[actor]` makes this checkpoint unusable. */
    fun isDiscardedBy(equivocations: Map<String, Long>): Boolean =
        equivocations.any { (actor, s) -> s <= (coverage[actor] ?: 0L) }

    companion object {
        fun empty(library: String) = Checkpoint(library, SEMANTICS, emptyMap(), null, State.EMPTY)
    }
}

/**
 * Reading: take every checkpoint of [library] whose semantics equals this reader's (others are ignored), and
 * merge them all. Order and duplicates do not matter; "newest" is never used.
 */
fun mergeCheckpoints(checkpoints: List<Checkpoint>, library: String): Checkpoint {
    var out = Checkpoint.empty(library)
    for (cp in checkpoints) {
        if (cp.library != library || cp.semantics != SEMANTICS) continue
        out = out.merge(cp) ?: continue
    }
    return out
}

sealed class CheckpointError {
    object BadMagic : CheckpointError()
    class NewerFormat(val found: String) : CheckpointError()
    object TooLarge : CheckpointError()
    object Bom : CheckpointError()
    object MalformedUtf8 : CheckpointError()
    object CarriageReturn : CheckpointError()
    class BadHeader(val why: String) : CheckpointError()
    object MissingFinalNewline : CheckpointError()
    object TrailingData : CheckpointError()
    object Checksum : CheckpointError()
    class WrongLibrary(val found: String) : CheckpointError()

    /** Built with another semantics: ignored by readers. */
    class OtherSemantics(val found: Long) : CheckpointError()
    object NonCanonical : CheckpointError()
    class BadShape(val where: String) : CheckpointError()
}

sealed class CheckpointResult {
    class Ok(val checkpoint: Checkpoint) : CheckpointResult()
    class Err(val error: CheckpointError) : CheckpointResult()
}


/** `KFCKP 1`, a header line, then the state as one canonical line (section 10.3 of the spec). */
fun encodeCheckpoint(cp: Checkpoint): ByteArray = encodeFile(cp.library, cp.coverage, cp.maxHlc, cp.semantics, cp.state, null, -1)

/**
 * A checkpoint file, or with [shard] a part file (section 10.4): the header gains `"part":"<shard>"` and [state]
 * (already restricted to that shard, or the whole state, which is then projected as it is written) holds only the
 * shard's keys. [shardIndex] is the shard's index in [SHARD_IDS], or -1 for a whole checkpoint.
 */
internal fun encodeFile(
    library: String,
    coverage: Map<String, Long>,
    maxHlc: Hlc?,
    semantics: Int,
    state: State,
    shard: String?,
    shardIndex: Int,
    alreadyProjected: Boolean = false,
): ByteArray {
    // The body is written straight to UTF-8 bytes; StateWriter gives the bytes of encodeCanonical(StateCodec.toJson(state)).
    val sink = ByteSink((1 shl 16) + (if (shardIndex < 0) state.items.size * 512 else state.items.size / 16 * 512))
    StateWriter.write(sink, if (shardIndex >= 0 && !alreadyProjected) projectToShard(state, shardIndex) else state)
    sink.byte(10)
    val members = LinkedHashMap<String, Jv>()
    members["coverage"] = JObj(coverage.mapValues { JNum(it.value) })
    members["library"] = JStr(library)
    members["maxHlc"] = maxHlc?.let { JStr(it.wire()) } ?: JNull
    if (shard != null) members["part"] = JStr(shard)
    members["semantics"] = JNum(semantics.toLong())
    members["sha256"] = JStr(sha256HexRange(sink.buf, 0, sink.size))
    return joinFrameBytes("KFCKP", encodeCanonical(JObj(members)), sink.buf, sink.size)
}

fun decodeCheckpoint(bytes: ByteArray, expectedLibrary: String): CheckpointResult = decodeCheckpointWith(bytes, expectedLibrary, true)

/** The reference route only (no fast state reader): tests use it to prove the fast route rejects exactly the same files. */
internal fun decodeCheckpointReference(bytes: ByteArray, expectedLibrary: String): CheckpointResult =
    decodeCheckpointWith(bytes, expectedLibrary, false)

private fun decodeCheckpointWith(bytes: ByteArray, expectedLibrary: String, fast: Boolean): CheckpointResult =
    when (val r = decodeFile(bytes, expectedLibrary, fast, false)) {
        is FileResult.Ok -> CheckpointResult.Ok(r.checkpoint)
        is FileResult.Err -> CheckpointResult.Err(r.error)
    }

/** One shard of a checkpoint (section 10.4): [checkpoint] holds only that shard's keys. */
class CheckpointPart(val shard: String, val checkpoint: Checkpoint)

sealed class PartResult {
    class Ok(val part: CheckpointPart) : PartResult()
    class Err(val error: CheckpointError) : PartResult()
}

/** Reads a part file. A whole-checkpoint file is not a part (its header has no `part`), and a part is not a whole checkpoint. */
fun decodePart(bytes: ByteArray, expectedLibrary: String): PartResult = decodePartWith(bytes, expectedLibrary, true)

internal fun decodePartReference(bytes: ByteArray, expectedLibrary: String): PartResult = decodePartWith(bytes, expectedLibrary, false)

private fun decodePartWith(bytes: ByteArray, expectedLibrary: String, fast: Boolean): PartResult =
    when (val r = decodeFile(bytes, expectedLibrary, fast, true)) {
        is FileResult.Ok -> PartResult.Ok(CheckpointPart(r.shard!!, r.checkpoint))
        is FileResult.Err -> PartResult.Err(r.error)
    }

private sealed class FileResult {
    class Ok(val checkpoint: Checkpoint, val shard: String?) : FileResult()
    class Err(val error: CheckpointError) : FileResult()
}

private fun cpErr2(e: CheckpointError): FileResult = FileResult.Err(e)

private fun decodeFile(bytes: ByteArray, expectedLibrary: String, fast: Boolean, wantPart: Boolean): FileResult {
    val frame = when (val f = splitFrame(bytes, "KFCKP", CHECKPOINT_MAX_BYTES)) {
        is FrameResult.Bad -> return cpErr2(
            when (f.problem) {
                FrameProblem.Bom -> CheckpointError.Bom
                FrameProblem.TooLarge -> CheckpointError.TooLarge
                FrameProblem.BadMagic -> CheckpointError.BadMagic
                FrameProblem.NewerFormat -> CheckpointError.NewerFormat(f.found)
                FrameProblem.MalformedUtf8 -> CheckpointError.MalformedUtf8
                FrameProblem.CarriageReturn -> CheckpointError.CarriageReturn
                FrameProblem.NoHeader -> CheckpointError.BadHeader("no header line")
            },
        )
        is FrameResult.Ok -> f.frame
    }
    val headerResult = decodeCanonical(frame.header)
    if (headerResult !is CanonicalResult.Ok) return cpErr2(CheckpointError.BadHeader("header is not canonical JSON"))
    val h = (headerResult.value as? JObj)?.members ?: return cpErr2(CheckpointError.BadHeader("header is not an object"))
    val expectedKeys = if (wantPart) setOf("coverage", "library", "maxHlc", "part", "semantics", "sha256") else setOf("coverage", "library", "maxHlc", "semantics", "sha256")
    if (h.keys != expectedKeys) return cpErr2(CheckpointError.BadHeader("wrong header fields"))
    val shard: String? = if (wantPart) (h["part"] as? JStr)?.value else null
    val shardIndex = if (shard != null) shardIndexOfName(shard) else -1
    if (wantPart && shardIndex < 0) return cpErr2(CheckpointError.BadHeader("part"))
    val library = (h["library"] as? JStr)?.value ?: return cpErr2(CheckpointError.BadHeader("library"))
    val sha = (h["sha256"] as? JStr)?.value ?: return cpErr2(CheckpointError.BadHeader("sha256"))
    val semantics = (h["semantics"] as? JNum)?.value ?: return cpErr2(CheckpointError.BadHeader("semantics"))
    val coverageJson = (h["coverage"] as? JObj)?.members ?: return cpErr2(CheckpointError.BadHeader("coverage"))
    val maxHlc: Hlc? = when (val m = h["maxHlc"]) {
        is JNull -> null
        is JStr -> Hlc.parse(m.value) ?: return cpErr2(CheckpointError.BadHeader("maxHlc"))
        else -> return cpErr2(CheckpointError.BadHeader("maxHlc"))
    }
    val coverage = LinkedHashMap<String, Long>()
    for ((actor, n) in coverageJson) {
        val v = (n as? JNum)?.value
        if (!isUuid(actor) || v == null || v < 1) return cpErr2(CheckpointError.BadHeader("coverage"))
        coverage[actor] = v
    }

    val bodyBytes = frame.bodyBytes
    val last = bodyBytes.size - 1
    if (last < 0 || bodyBytes[last] != 10.toByte()) return cpErr2(CheckpointError.MissingFinalNewline)
    // the frame scan counted the line feeds of the body: the final one is the only one allowed
    if (frame.bodyLineFeeds > 1) return cpErr2(CheckpointError.TrailingData)
    if (sha256HexRange(bodyBytes, 0, bodyBytes.size) != sha) return cpErr2(CheckpointError.Checksum)
    if (library != expectedLibrary) return cpErr2(CheckpointError.WrongLibrary(library))
    if (semantics != SEMANTICS.toLong()) return cpErr2(CheckpointError.OtherSemantics(semantics))

    if (fast) {
        // One pass over the bytes. It accepts only text that is exactly the canonical encoding of the state it reads,
        // which is what the reference route below establishes with a parse and a re-encode.
        val quick = StateReader(bodyBytes, 0, last).read()
        if (quick != null && (!wantPart || stateBelongsToShard(quick, shardIndex))) {
            return FileResult.Ok(Checkpoint(library, SEMANTICS, coverage, maxHlc, quick), shard)
        }
    }
    val line = frame.body.substring(0, frame.body.length - 1)

    val json = when (val r = decodeCanonical(line)) {
        is CanonicalResult.Ok -> r.value
        is CanonicalResult.Bad -> return cpErr2(if (r.problem == CanonicalProblem.Syntax) CheckpointError.BadShape("state") else CheckpointError.NonCanonical)
    }
    val state = try {
        StateCodec.fromJson(json)
    } catch (e: StateCodec.Shape) {
        return cpErr2(CheckpointError.BadShape(e.where))
    }
    if (wantPart && !stateBelongsToShard(state, shardIndex)) return cpErr2(CheckpointError.BadShape("shard"))
    // A State has exactly one encoding: unsorted or duplicated arrays are rejected.
    if (encodeCanonical(StateCodec.toJson(state)) != line) return cpErr2(CheckpointError.NonCanonical)
    return FileResult.Ok(Checkpoint(library, SEMANTICS, coverage, maxHlc, state), shard)
}

/** State to and from the JSON used inside checkpoints. Every key is always present. */
internal object StateCodec {
    class Shape(val where: String) : Exception(where)

    private fun obj(v: Jv?, where: String): Map<String, Jv> = (v as? JObj)?.members ?: throw Shape(where)
    private fun arr(v: Jv?, where: String): List<Jv> = (v as? JArr)?.items ?: throw Shape(where)
    private fun str(v: Jv?, where: String): String = (v as? JStr)?.value ?: throw Shape(where)
    private fun opId(v: Jv?, where: String): OpId = OpId.parse(str(v, where)) ?: throw Shape(where)

    private fun strs(set: Collection<String>) = JArr(set.sorted().map { JStr(it) })
    private fun ids(set: Collection<OpId>) = JArr(set.sorted().map { JStr(it.toString()) })

    private fun orSet(s: OrSet) = jObj("adds" to ids(s.adds), "removes" to ids(s.removes))
    private fun reg(r: Register) = jObj("hlc" to JStr(r.hlc.wire()), "op" to JStr(r.op.toString()), "value" to r.value)
    private fun regs(m: Map<String, Register>) = JObj(m.mapValues { reg(it.value) })

    private fun entity(e: EntityState) = jObj("exists" to orSet(e.exists), "fields" to regs(e.fields))

    fun toJson(s: State): Jv = jObj(
        "entries" to JObj(
            s.entries.mapValues {
                jObj("item" to JStr(it.value.item), "op" to JStr(it.value.op.toString()), "playlist" to JStr(it.value.playlist))
            },
        ),
        "entryKey" to regs(s.entryKey),
        "findings" to strs(s.findings),
        "items" to JObj(
            s.items.mapValues { (_, i) ->
                jObj(
                    "deletedHeads" to ids(i.deletedHeads),
                    "exists" to orSet(i.exists),
                    "fields" to regs(i.fields),
                    "revisions" to JObj(
                        i.revisions.mapKeys { it.key.toString() }.mapValues { (_, e) ->
                            jObj("content" to JStr(e.content), "parents" to ids(e.parents), "size" to JNum(e.size))
                        },
                    ),
                )
            },
        ),
        "placements" to JObj(s.placements.mapValues { regs(it.value) }),
        "places" to JObj(s.places.mapValues { regs(it.value) }),
        "playlists" to JObj(s.playlists.mapValues { entity(it.value) }),
        "profile" to regs(s.profile),
        "removedEntries" to strs(s.removedEntries),
        "sets" to JObj(s.sets.mapValues { (_, els) -> JObj(els.mapValues { orSet(it.value) }) }),
        "spaces" to JObj(s.spaces.mapValues { entity(it.value) }),
    )

    private fun readStrs(v: Jv?, where: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (x in arr(v, where)) out.add(str(x, where))
        return out
    }

    private fun readIds(v: Jv?, where: String): Set<OpId> {
        val out = LinkedHashSet<OpId>()
        for (x in arr(v, where)) out.add(opId(x, where))
        return out
    }

    private fun readOrSet(v: Jv?, where: String): OrSet {
        val m = obj(v, where)
        return OrSet(readIds(m["adds"], "$where.adds"), readIds(m["removes"], "$where.removes"))
    }

    private fun readReg(v: Jv?, where: String): Register {
        val m = obj(v, where)
        val hlc = Hlc.parse(str(m["hlc"], "$where.hlc")) ?: throw Shape("$where.hlc")
        return Register(hlc, opId(m["op"], "$where.op"), m["value"] ?: throw Shape("$where.value"))
    }

    private fun readRegs(v: Jv?, where: String): Map<String, Register> {
        val out = LinkedHashMap<String, Register>()
        for ((k, x) in obj(v, where)) out[k] = readReg(x, "$where.$k")
        return out
    }

    private fun readRegMaps(v: Jv?, where: String): Map<String, Map<String, Register>> {
        val out = LinkedHashMap<String, Map<String, Register>>()
        for ((k, x) in obj(v, where)) out[k] = readRegs(x, "$where.$k")
        return out
    }

    private fun readEntities(v: Jv?, where: String): Map<String, EntityState> {
        val out = LinkedHashMap<String, EntityState>()
        for ((k, x) in obj(v, where)) {
            val m = obj(x, "$where.$k")
            out[k] = EntityState(readOrSet(m["exists"], "$where.$k.exists"), readRegs(m["fields"], "$where.$k.fields"))
        }
        return out
    }

    fun fromJson(v: Jv?): State {
        val m = obj(v, "state")
        val items = LinkedHashMap<String, ItemState>()
        for ((k, x) in obj(m["items"], "items")) {
            val i = obj(x, "items.$k")
            val revisions = LinkedHashMap<OpId, RevisionEvent>()
            for ((r, e) in obj(i["revisions"], "items.$k.revisions")) {
                val em = obj(e, "items.$k.revisions.$r")
                revisions[OpId.parse(r) ?: throw Shape("items.$k.revisions")] = RevisionEvent(
                    str(em["content"], "items.$k.revisions.$r.content"),
                    (em["size"] as? JNum)?.value ?: throw Shape("items.$k.revisions.$r.size"),
                    readIds(em["parents"], "items.$k.revisions.$r.parents"),
                )
            }
            items[k] = ItemState(
                readOrSet(i["exists"], "items.$k.exists"),
                readRegs(i["fields"], "items.$k.fields"),
                revisions,
                readIds(i["deletedHeads"], "items.$k.deletedHeads"),
            )
        }
        val sets = LinkedHashMap<String, Map<String, OrSet>>()
        for ((k, x) in obj(m["sets"], "sets")) {
            val els = LinkedHashMap<String, OrSet>()
            for ((e, o) in obj(x, "sets.$k")) els[e] = readOrSet(o, "sets.$k.$e")
            sets[k] = els
        }
        val entries = LinkedHashMap<String, EntryFacts>()
        for ((k, x) in obj(m["entries"], "entries")) {
            val e = obj(x, "entries.$k")
            entries[k] = EntryFacts(str(e["playlist"], "entries.$k"), str(e["item"], "entries.$k"), opId(e["op"], "entries.$k"))
        }
        return State(
            items = items,
            sets = sets,
            playlists = readEntities(m["playlists"], "playlists"),
            spaces = readEntities(m["spaces"], "spaces"),
            entries = entries,
            entryKey = readRegs(m["entryKey"], "entryKey"),
            removedEntries = readStrs(m["removedEntries"], "removedEntries"),
            placements = readRegMaps(m["placements"], "placements"),
            places = readRegMaps(m["places"], "places"),
            profile = readRegs(m["profile"], "profile"),
            findings = readStrs(m["findings"], "findings"),
        )
    }
}

/** The result of merging parts: the library coverage and clock, the merged state in an accumulator ready to adopt. */
internal class MergedParts(
    val library: String,
    val coverage: Map<String, Long>,
    val maxHlc: Hlc?,
    /** The highest coverage any part (stale ones too) claims for each actor. */
    val highest: Map<String, Long>,
    val acc: StateAccumulator,
    /** The merged state of each shard that had a part (kept so the base state can be rebuilt after an equivocation). */
    val shardStates: List<State>,
) {
    fun baseState(): State {
        val all = StateAccumulator()
        for (s in shardStates) all.add(s)
        return all.snapshot()
    }
}

/**
 * Reading parts (section 10.4). Per shard: join the states and take the pointwise maximum of the coverages. The
 * library's coverage is the pointwise MINIMUM over all 17 shards, a missing shard counting as 0, and the state is
 * the union of the merged shards. Ops above that minimum are admitted again by the caller (join is idempotent).
 */
internal class PartsReader(private val library: String) {
    private val states = Array(SHARD_IDS.size) { ArrayList<State>() }
    private val coverages = Array(SHARD_IDS.size) { LinkedHashMap<String, Long>() }
    private var maxHlc: Hlc? = null
    private val highest = LinkedHashMap<String, Long>()

    /** Adds one decoded part. Parts of another library or semantics are ignored. */
    fun add(part: CheckpointPart) {
        val cp = part.checkpoint
        if (cp.library != library || cp.semantics != SEMANTICS) return
        val i = shardIndexOfName(part.shard)
        if (i < 0) return
        states[i].add(cp.state)
        for ((actor, n) in cp.coverage) {
            if (n > (coverages[i][actor] ?: 0L)) coverages[i][actor] = n
            if (n > (highest[actor] ?: 0L)) highest[actor] = n
        }
        val m = cp.maxHlc
        if (m != null && (maxHlc == null || m > maxHlc!!)) maxHlc = m
    }

    fun build(): MergedParts {
        val coverage = LinkedHashMap<String, Long>()
        for (actor in highest.keys) {
            var low = Long.MAX_VALUE
            for (i in SHARD_IDS.indices) {
                val v = if (states[i].isNotEmpty()) (coverages[i][actor] ?: 0L) else 0L
                if (v < low) low = v
            }
            if (low > 0 && low != Long.MAX_VALUE) coverage[actor] = low
        }
        val merged = ArrayList<State>()
        for (i in SHARD_IDS.indices) {
            val list = states[i]
            when (list.size) {
                0 -> {}
                1 -> merged.add(list[0])
                else -> {
                    val one = StateAccumulator()
                    for (st in list) one.add(st)
                    merged.add(one.snapshot())
                }
            }
        }
        val all = StateAccumulator()
        for (st in merged) all.add(st)
        return MergedParts(library, coverage, maxHlc, highest, all, merged)
    }
}
