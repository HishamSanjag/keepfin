package app.keepfin.core.sync

// The library state is a join-semilattice: every admitted operation becomes a small delta state, and the state
// is the join of all deltas. join is commutative, associative and idempotent; equality is structural.

/** Observed-remove set. Present when some add has not been removed. */
data class OrSet(val adds: Set<OpId> = emptySet(), val removes: Set<OpId> = emptySet()) {
    val present: Boolean get() = adds.any { it !in removes }

    fun join(other: OrSet): OrSet = OrSet(unionSets(adds, other.adds), unionSets(removes, other.removes))
}

/** Last-writer-wins register; the larger (hlc, op) wins, so the choice is total. */
data class Register(val hlc: Hlc, val op: OpId, val value: Jv) {
    fun join(other: Register): Register {
        val c = hlc.compareTo(other.hlc)
        if (c > 0) return this
        if (c < 0) return other
        val d = op.compareTo(other.op)
        if (d > 0) return this
        if (d < 0) return other
        // The same op always carries the same value (equivocation is stopped at admission); stay total anyway.
        return if (encodeCanonical(value) >= encodeCanonical(other.value)) this else other
    }
}

/** One `item.revise` op: bytes [content] of [size], following the revision events in [parents]. */
data class RevisionEvent(val content: String, val size: Long, val parents: Set<OpId>) {
    fun join(o: RevisionEvent): RevisionEvent {
        // Equal ops give identical events; this stays a lattice even for impossible input.
        val firstIsSmaller = content < o.content || (content == o.content && size <= o.size)
        val base = if (firstIsSmaller) this else o
        return RevisionEvent(base.content, base.size, unionSets(parents, o.parents))
    }
}

data class ItemState(
    val exists: OrSet = OrSet(),
    val fields: Map<String, Register> = emptyMap(),
    val revisions: Map<OpId, RevisionEvent> = emptyMap(),
    val deletedHeads: Set<OpId> = emptySet(),
) {
    fun join(o: ItemState) = ItemState(
        exists.join(o.exists),
        joinMaps(fields, o.fields) { a, b -> a.join(b) },
        joinMaps(revisions, o.revisions) { a, b -> a.join(b) },
        unionSets(deletedHeads, o.deletedHeads),
    )
}

/** Used for playlists and spaces: an exists set plus field registers. */
data class EntityState(val exists: OrSet = OrSet(), val fields: Map<String, Register> = emptyMap()) {
    fun join(o: EntityState) = EntityState(exists.join(o.exists), joinMaps(fields, o.fields) { a, b -> a.join(b) })
}

/** An entry's immutable facts. [op] is the op that carried them (the smaller wins if copies disagree). */
data class EntryFacts(val playlist: String, val item: String, val op: OpId)

internal fun <K, V> joinMaps(a: Map<K, V>, b: Map<K, V>, f: (V, V) -> V): Map<K, V> {
    if (b.isEmpty()) return a
    if (a.isEmpty()) return b
    val out = LinkedHashMap(a)
    for ((k, v) in b) {
        val x = out[k]
        out[k] = if (x == null) v else f(x, v)
    }
    return compactMap(out)
}

data class State(
    val items: Map<String, ItemState> = emptyMap(),
    val sets: Map<String, Map<String, OrSet>> = emptyMap(),
    val playlists: Map<String, EntityState> = emptyMap(),
    val spaces: Map<String, EntityState> = emptyMap(),
    val entries: Map<String, EntryFacts> = emptyMap(),
    val entryKey: Map<String, Register> = emptyMap(),
    val removedEntries: Set<String> = emptySet(),
    val placements: Map<String, Map<String, Register>> = emptyMap(), // item -> place -> register (path string or null)
    val places: Map<String, Map<String, Register>> = emptyMap(),
    val profile: Map<String, Register> = emptyMap(),
    /** Grow-only findings, e.g. `entry-conflict:<entryId>`. */
    val findings: Set<String> = emptySet(),
) {
    fun join(o: State): State {
        var found = findings + o.findings
        for ((k, v) in o.entries) {
            val x = entries[k] ?: continue
            if (x.playlist != v.playlist || x.item != v.item) found = found + (ENTRY_CONFLICT + k)
        }
        val regs: (Register, Register) -> Register = { a, b -> a.join(b) }
        val regMaps: (Map<String, Register>, Map<String, Register>) -> Map<String, Register> = { a, b -> joinMaps(a, b, regs) }
        return State(
            items = joinMaps(items, o.items) { a, b -> a.join(b) },
            sets = joinMaps(sets, o.sets) { a, b -> joinMaps(a, b) { x, y -> x.join(y) } },
            playlists = joinMaps(playlists, o.playlists) { a, b -> a.join(b) },
            spaces = joinMaps(spaces, o.spaces) { a, b -> a.join(b) },
            entries = joinMaps(entries, o.entries) { a, b -> if (a.op <= b.op) a else b },
            entryKey = joinMaps(entryKey, o.entryKey, regs),
            removedEntries = removedEntries + o.removedEntries,
            placements = joinMaps(placements, o.placements, regMaps),
            places = joinMaps(places, o.places, regMaps),
            profile = joinMaps(profile, o.profile, regs),
            findings = found,
        )
    }

    companion object {
        val EMPTY = State()
        const val ENTRY_CONFLICT = "entry-conflict:"
    }
}

/**
 * Joins many deltas into one state without copying the whole state each time (State.join is linear in the state size,
 * so folding n deltas with it is quadratic). It applies exactly the rules of State.join, in place.
 */
internal class StateAccumulator(initial: State = State.EMPTY) {
    private val items = LinkedHashMap(initial.items)
    // The inner maps of `sets` are owned and changed in place (a favourites set can hold many elements);
    // snapshot() copies them, so a snapshot never changes afterwards.
    private val sets = LinkedHashMap<String, LinkedHashMap<String, OrSet>>().also { out ->
        for ((k, v) in initial.sets) out[k] = LinkedHashMap(v)
    }
    private val playlists = LinkedHashMap(initial.playlists)
    private val spaces = LinkedHashMap(initial.spaces)
    private val entries = LinkedHashMap(initial.entries)
    private val entryKey = LinkedHashMap(initial.entryKey)
    private val removedEntries = LinkedHashSet(initial.removedEntries)
    private val placements = LinkedHashMap(initial.placements)
    private val places = LinkedHashMap(initial.places)
    private val profile = LinkedHashMap(initial.profile)
    private val findings = LinkedHashSet(initial.findings)

    /**
     * The accumulated state, read in place: its maps ARE the accumulator's maps and change with every [add].
     * For reading right now only; never keep it, and never change it. [snapshot] gives a stable copy.
     */
    val live: State = State(items, sets, playlists, spaces, entries, entryKey, removedEntries, placements, places, profile, findings)

    private fun <K, V> merge(into: MutableMap<K, V>, from: Map<K, V>, f: (V, V) -> V) {
        if (from.isEmpty()) return
        for ((k, v) in from) {
            val x = into[k]
            into[k] = if (x == null) v else f(x, v)
        }
    }

    private val joinItem: (ItemState, ItemState) -> ItemState = { a, b -> a.join(b) }
    private val joinEntity: (EntityState, EntityState) -> EntityState = { a, b -> a.join(b) }
    private val joinReg: (Register, Register) -> Register = { a, b -> a.join(b) }
    private val joinRegMap: (Map<String, Register>, Map<String, Register>) -> Map<String, Register> = { a, b -> joinMaps(a, b, joinReg) }
    private val pickEntry: (EntryFacts, EntryFacts) -> EntryFacts = { a, b -> if (a.op <= b.op) a else b }

    fun add(d: State) {
        if (d.findings.isNotEmpty()) findings.addAll(d.findings)
        for ((k, v) in d.entries) {
            val x = entries[k] ?: continue
            if (x.playlist != v.playlist || x.item != v.item) findings.add(State.ENTRY_CONFLICT + k)
        }
        merge(items, d.items, joinItem)
        for ((name, els) in d.sets) {
            val inner = sets.getOrPut(name) { LinkedHashMap() }
            for ((e, o) in els) {
                val x = inner[e]
                inner[e] = if (x == null) o else x.join(o)
            }
        }
        merge(playlists, d.playlists, joinEntity)
        merge(spaces, d.spaces, joinEntity)
        merge(entries, d.entries, pickEntry)
        merge(entryKey, d.entryKey, joinReg)
        if (d.removedEntries.isNotEmpty()) removedEntries.addAll(d.removedEntries)
        merge(placements, d.placements, joinRegMap)
        merge(places, d.places, joinRegMap)
        merge(profile, d.profile, joinReg)
    }

    fun snapshot(): State {
        val setsCopy = LinkedHashMap<String, Map<String, OrSet>>(sets.size * 2)
        for ((k, v) in sets) setsCopy[k] = LinkedHashMap(v)
        return State(
            LinkedHashMap(items), setsCopy, LinkedHashMap(playlists), LinkedHashMap(spaces), LinkedHashMap(entries),
            LinkedHashMap(entryKey), LinkedHashSet(removedEntries), LinkedHashMap(placements), LinkedHashMap(places),
            LinkedHashMap(profile), LinkedHashSet(findings),
        )
    }
}

/** The join of all [deltas]. */
fun joinAll(deltas: Iterable<State>): State {
    val acc = StateAccumulator()
    for (d in deltas) acc.add(d)
    return acc.snapshot()
}
