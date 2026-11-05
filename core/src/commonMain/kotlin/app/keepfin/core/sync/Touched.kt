package app.keepfin.core.sync

// Incremental reading: which keys of the state an op changed (so a reader re-projects only those), the indexes that
// make a playlist's songs and an item's playlists cheap to find, and the shards a change falls in (checkpoint parts).

/** The 17 shards of a checkpoint (sync spec 10.4): the first hex digit of an id, or `meta`. */
internal val SHARD_IDS: List<String> = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f", "meta")

internal const val META_SHARD = 16

/** The shard of an item or entry id: its first hex digit, else `meta` (real ids are lower-case UUIDs). */
internal fun shardOfId(id: String): Int {
    if (id.isEmpty()) return META_SHARD
    val c = id[0]
    return when {
        c >= '0' && c <= '9' -> c - '0'
        c >= 'a' && c <= 'f' -> 10 + (c - 'a')
        else -> META_SHARD
    }
}

private const val ENTRY_CONFLICT_PREFIX = "entry-conflict:"

internal fun shardOfFinding(f: String): Int =
    if (f.startsWith(ENTRY_CONFLICT_PREFIX)) shardOfId(f.substring(ENTRY_CONFLICT_PREFIX.length)) else META_SHARD

internal fun shardIndexOfName(name: String): Int = SHARD_IDS.indexOf(name)

/** True when every key of [s] belongs to shard [shard] (an index into [SHARD_IDS]). */
internal fun stateBelongsToShard(s: State, shard: Int): Boolean {
    for (k in s.items.keys) if (shardOfId(k) != shard) return false
    for (k in s.placements.keys) if (shardOfId(k) != shard) return false
    for (k in s.entries.keys) if (shardOfId(k) != shard) return false
    for (k in s.entryKey.keys) if (shardOfId(k) != shard) return false
    for (k in s.removedEntries) if (shardOfId(k) != shard) return false
    for (f in s.findings) if (shardOfFinding(f) != shard) return false
    if (shard != META_SHARD) {
        return s.sets.isEmpty() && s.playlists.isEmpty() && s.spaces.isEmpty() && s.places.isEmpty() && s.profile.isEmpty()
    }
    return true
}

/** The part of [s] that belongs to [shard]. */
internal fun projectToShard(s: State, shard: Int): State {
    fun <V> pick(m: Map<String, V>): Map<String, V> {
        val out = LinkedHashMap<String, V>()
        for ((k, v) in m) if (shardOfId(k) == shard) out[k] = v
        return out
    }
    val meta = shard == META_SHARD
    val removed = LinkedHashSet<String>()
    for (k in s.removedEntries) if (shardOfId(k) == shard) removed.add(k)
    val found = LinkedHashSet<String>()
    for (f in s.findings) if (shardOfFinding(f) == shard) found.add(f)
    return State(
        items = pick(s.items),
        sets = if (meta) s.sets else emptyMap(),
        playlists = if (meta) s.playlists else emptyMap(),
        spaces = if (meta) s.spaces else emptyMap(),
        entries = pick(s.entries),
        entryKey = pick(s.entryKey),
        removedEntries = removed,
        placements = pick(s.placements),
        places = if (meta) s.places else emptyMap(),
        profile = if (meta) s.profile else emptyMap(),
        findings = found,
    )
}

/** All 17 projections of [s] in one pass (writing every part of a large state would otherwise scan it 17 times). */
internal fun partitionByShard(s: State): Array<State> {
    val n = SHARD_IDS.size
    val items = Array(n) { LinkedHashMap<String, ItemState>() }
    val placements = Array(n) { LinkedHashMap<String, Map<String, Register>>() }
    val entries = Array(n) { LinkedHashMap<String, EntryFacts>() }
    val entryKey = Array(n) { LinkedHashMap<String, Register>() }
    val removed = Array(n) { LinkedHashSet<String>() }
    val findings = Array(n) { LinkedHashSet<String>() }
    for ((k, v) in s.items) items[shardOfId(k)][k] = v
    for ((k, v) in s.placements) placements[shardOfId(k)][k] = v
    for ((k, v) in s.entries) entries[shardOfId(k)][k] = v
    for ((k, v) in s.entryKey) entryKey[shardOfId(k)][k] = v
    for (k in s.removedEntries) removed[shardOfId(k)].add(k)
    for (f in s.findings) findings[shardOfFinding(f)].add(f)
    return Array(n) { i ->
        val meta = i == META_SHARD
        State(
            items = items[i],
            sets = if (meta) s.sets else emptyMap(),
            playlists = if (meta) s.playlists else emptyMap(),
            spaces = if (meta) s.spaces else emptyMap(),
            entries = entries[i],
            entryKey = entryKey[i],
            removedEntries = removed[i],
            placements = placements[i],
            places = if (meta) s.places else emptyMap(),
            profile = if (meta) s.profile else emptyMap(),
            findings = findings[i],
        )
    }
}

/**
 * Keys whose views may have changed since the last [OpLog.takeTouched]. [all] after a rebuild (an equivocation or a
 * checkpoint load): everything may have changed and the other lists are empty.
 */
class Touched(
    val all: Boolean,
    val items: List<String>,
    val playlists: List<String>,
    val spaces: List<String>,
    val sets: List<String>,
    val places: List<String>,
    val profile: Boolean,
    val findings: Boolean,
) {
    companion object {
        val ALL = Touched(true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), true, true)
    }
}

/** What a remove or delete must observe, read from the state. */
class Observed(
    val items: Map<String, ObservedItem>,
    val playlists: Map<String, List<OpId>>,
    val spaces: Map<String, List<OpId>>,
    val setElements: Map<String, Map<String, List<OpId>>>,
)

class ObservedItem(val tags: List<OpId>, val heads: List<OpId>)

/** Per-op key tracking and the indexes behind partial views. Owned by [OpLog]; reads the accumulator's live state. */
internal class Tracker {
    var all = false
        private set
    private var items = LinkedHashSet<String>()
    private var playlists = LinkedHashSet<String>()
    private var spaces = LinkedHashSet<String>()
    private var sets = LinkedHashSet<String>()
    private var places = LinkedHashSet<String>()
    private var profile = false
    private var findings = false

    /** Shards changed since their part was last written (a bit per index of [SHARD_IDS]). */
    var dirty: Int = (1 shl SHARD_IDS.size) - 1
        private set

    /** Entry ids by playlist (entries are grow-only, so this only grows). */
    val playlistEntries = HashMap<String, LinkedHashSet<String>>()
    // item -> its playlist (a String), or the set of its playlists when it is in several: most items are in one
    private val itemPlaylists = HashMap<String, Any>()

    /** Items whose revision events have no head. */
    val cycleItems = LinkedHashSet<String>()

    fun markShardClean(shard: Int) {
        dirty = dirty and (1 shl shard).inv()
    }

    fun markAllDirty() {
        dirty = (1 shl SHARD_IDS.size) - 1
    }

    /** Forget everything seen so far and rebuild the indexes from [live]; the next take reports `all`. */
    fun reset(live: State) {
        all = true
        items = LinkedHashSet()
        playlists = LinkedHashSet()
        spaces = LinkedHashSet()
        sets = LinkedHashSet()
        places = LinkedHashSet()
        profile = false
        findings = false
        markAllDirty()
        playlistEntries.clear()
        itemPlaylists.clear()
        cycleItems.clear()
        for ((e, f) in live.entries) index(e, f)
        for ((id, s) in live.items) if (s.revisions.isNotEmpty() && live.hasRevisionCycle(id)) cycleItems.add(id)
    }

    private fun index(entry: String, f: EntryFacts) {
        playlistEntries.getOrPut(f.playlist) { LinkedHashSet() }.add(entry)
        val cur = itemPlaylists[f.item]
        if (cur == null) {
            itemPlaylists[f.item] = f.playlist
        } else if (cur is String) {
            if (cur != f.playlist) itemPlaylists[f.item] = hashSetOf(cur, f.playlist)
        } else {
            @Suppress("UNCHECKED_CAST")
            (cur as HashSet<String>).add(f.playlist)
        }
    }

    private fun touchPlaylist(p: String) {
        if (!all) playlists.add(p)
    }

    private fun dirtyId(id: String) {
        dirty = dirty or (1 shl shardOfId(id))
    }

    private val metaBit = 1 shl META_SHARD

    /** Called before [delta] is added: an entry about to be overwritten tells which playlist it leaves. */
    fun beforeAdd(delta: State, live: State) {
        if (delta.entries.isEmpty() || all) return
        for (e in delta.entries.keys) {
            val old = live.entries[e] ?: continue
            playlists.add(old.playlist)
        }
    }

    /** Called after [delta] was added to the accumulator whose state is [live]. [findingsGrew]: the state gained a finding. */
    fun afterAdd(delta: State, live: State, findingsGrew: Boolean) {
        for ((k, i) in delta.items) {
            if (!all) items.add(k)
            dirtyId(k)
            val moves = i.exists.adds.isNotEmpty() || i.exists.removes.isNotEmpty() || i.revisions.isNotEmpty() || i.deletedHeads.isNotEmpty()
            if (moves && !all) {
                val ps = itemPlaylists[k]
                if (ps is String) {
                    playlists.add(ps)
                } else if (ps != null) {
                    @Suppress("UNCHECKED_CAST")
                    playlists.addAll(ps as HashSet<String>)
                }
            }
            if (i.revisions.isNotEmpty()) {
                val cyc = live.hasRevisionCycle(k)
                val changed = if (cyc) cycleItems.add(k) else cycleItems.remove(k)
                if (changed) findings = true
            }
        }
        for (k in delta.placements.keys) {
            if (!all) items.add(k)
            dirtyId(k)
        }
        for ((e, f) in delta.entries) {
            dirtyId(e)
            val won = live.entries[e]
            if (won != null) {
                index(e, won)
                touchPlaylist(won.playlist)
            }
            if (won == null || won != f) {
                index(e, f)
                touchPlaylist(f.playlist)
            }
        }
        for (e in delta.entryKey.keys) {
            dirtyId(e)
            val f = live.entries[e]
            if (f != null) touchPlaylist(f.playlist)
        }
        for (e in delta.removedEntries) {
            dirtyId(e)
            val f = live.entries[e]
            if (f != null) touchPlaylist(f.playlist)
        }
        if (delta.sets.isNotEmpty()) {
            dirty = dirty or metaBit
            if (!all) sets.addAll(delta.sets.keys)
        }
        if (delta.playlists.isNotEmpty()) {
            dirty = dirty or metaBit
            if (!all) playlists.addAll(delta.playlists.keys)
        }
        if (delta.spaces.isNotEmpty()) {
            dirty = dirty or metaBit
            if (!all) spaces.addAll(delta.spaces.keys)
        }
        if (delta.places.isNotEmpty()) {
            dirty = dirty or metaBit
            if (!all) places.addAll(delta.places.keys)
        }
        if (delta.profile.isNotEmpty()) {
            dirty = dirty or metaBit
            profile = true
        }
        if (findingsGrew || delta.findings.isNotEmpty()) {
            findings = true
            for (f in delta.findings) dirty = dirty or (1 shl shardOfFinding(f))
        }
    }

    /** An op was kept as invalid: the findings list gained `invalid:<id>`. */
    fun invalidAdded() {
        findings = true
    }

    fun take(): Touched {
        val t = if (all) {
            Touched.ALL
        } else {
            Touched(false, items.toList(), playlists.toList(), spaces.toList(), sets.toList(), places.toList(), profile, findings)
        }
        all = false
        items = LinkedHashSet()
        playlists = LinkedHashSet()
        spaces = LinkedHashSet()
        sets = LinkedHashSet()
        places = LinkedHashSet()
        profile = false
        findings = false
        return t
    }
}
