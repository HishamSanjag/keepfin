package app.keepfin.core.sync

// Reading the state. Nothing here is stored; everything is computed from the State.

const val PLAYS_PREFIX = "plays@"

enum class ItemStatus {
    /** Ops about the item arrived before its add: not shown. */
    Unknown,
    Present,
    Deleted,
    DeleteConflict,
}

/** Revision events that are not a parent of another event of the item. Unknown parents are ignored. */
internal fun headsOf(s: ItemState): List<OpId> {
    if (s.revisions.isEmpty()) return emptyList()
    val parents = HashSet<OpId>()
    for (e in s.revisions.values) parents.addAll(e.parents)
    val heads = s.revisions.keys.filter { it !in parents }.sorted()
    // A cycle (only from a buggy writer) leaves no head: every event counts as a head.
    return if (heads.isEmpty()) s.revisions.keys.sorted() else heads
}

fun State.hasRevisionCycle(item: String): Boolean {
    val s = items[item] ?: return false
    if (s.revisions.isEmpty()) return false
    val parents = HashSet<OpId>()
    for (e in s.revisions.values) parents.addAll(e.parents)
    return s.revisions.keys.all { it in parents }
}

fun State.isItemPresent(item: String): Boolean = items[item]?.exists?.present == true

/** Revision events (op ids) that are heads, sorted. */
fun State.itemHeads(item: String): List<OpId> = items[item]?.let { headsOf(it) } ?: emptyList()

/** The content ids of the heads, in head order. */
fun State.headContents(item: String): List<String> {
    val s = items[item] ?: return emptyList()
    return headsOf(s).map { s.revisions.getValue(it).content }
}

/** More than one head: a content conflict, resolved by a revise whose parents are all of the heads. */
fun State.hasContentConflict(item: String): Boolean = itemHeads(item).size > 1

/** Heads revised concurrently with a delete: absent item with adds, and a head its deletes never saw. Sorted. */
fun State.deleteConflictHeads(item: String): List<OpId> {
    val s = items[item] ?: return emptyList()
    if (s.exists.adds.isEmpty() || s.exists.present) return emptyList()
    return headsOf(s).filter { it !in s.deletedHeads }
}

fun State.hasDeleteConflict(item: String): Boolean = deleteConflictHeads(item).isNotEmpty()

fun State.itemStatus(item: String): ItemStatus {
    val s = items[item] ?: return ItemStatus.Unknown
    return when {
        s.exists.adds.isEmpty() -> ItemStatus.Unknown
        s.exists.present -> ItemStatus.Present
        hasDeleteConflict(item) -> ItemStatus.DeleteConflict
        else -> ItemStatus.Deleted
    }
}

fun State.itemField(item: String, field: String): Jv? = items[item]?.fields?.get(field)?.value

/** The play count: the sum of every `plays@<actor>` number field. */
fun State.plays(item: String): Long {
    val s = items[item] ?: return 0L
    var sum = 0L
    for ((name, r) in s.fields) {
        if (name.startsWith(PLAYS_PREFIX)) {
            val v = r.value
            if (v is JNum) sum += v.value
        }
    }
    return sum
}

fun State.setMembers(set: String): List<String> =
    (sets[set] ?: emptyMap()).filter { it.value.present }.keys.sorted()

fun State.isPlaylistPresent(p: String): Boolean = playlists[p]?.exists?.present == true

fun State.isSpacePresent(s: String): Boolean = spaces[s]?.exists?.present == true

data class PlaylistSong(val entry: String, val item: String, val key: String)

/**
 * A present playlist's songs: live entries whose item is present or in a delete conflict, ordered by
 * (key, entry id). A deleted or unknown playlist has none (its entries return if it is restored).
 */
fun State.playlistSongs(playlist: String): List<PlaylistSong> {
    if (!isPlaylistPresent(playlist)) return emptyList()
    val out = ArrayList<PlaylistSong>()
    for ((entry, facts) in entries) {
        if (facts.playlist != playlist || entry in removedEntries) continue
        val status = itemStatus(facts.item)
        if (status != ItemStatus.Present && status != ItemStatus.DeleteConflict) continue
        val key = (entryKey[entry]?.value as? JStr)?.value ?: ""
        out.add(PlaylistSong(entry, facts.item, key))
    }
    return out.sortedWith(Comparator { a, b ->
        val c = a.key.compareTo(b.key)
        if (c != 0) c else a.entry.compareTo(b.entry)
    })
}

data class ItemView(
    val status: ItemStatus,
    val heads: List<OpId>,
    val headContents: List<String>,
    val deleteConflict: List<OpId>,
    val fields: Map<String, Jv>,
    val plays: Long,
)

data class ContainerView(val present: Boolean, val fields: Map<String, Jv>)

data class PlaylistView(val present: Boolean, val fields: Map<String, Jv>, val songs: List<PlaylistSong>)

/** Everything a reader can see; two equal states give equal views. */
data class Views(
    val items: Map<String, ItemView>,
    val sets: Map<String, List<String>>,
    val playlists: Map<String, PlaylistView>,
    val spaces: Map<String, ContainerView>,
    val placements: Map<String, Map<String, Jv>>,
    val places: Map<String, Map<String, Jv>>,
    val profile: Map<String, Jv>,
    /** The state's findings plus `revision-cycle:<item>` for items whose revisions have no head. */
    val findings: Set<String>,
)

private fun fieldValues(fields: Map<String, Register>): Map<String, Jv> = fields.mapValues { it.value.value }

fun State.views(): Views {
    val playlistIds = HashSet<String>(playlists.keys)
    for (f in entries.values) playlistIds.add(f.playlist)
    val found = HashSet<String>(findings)
    for (id in items.keys) if (hasRevisionCycle(id)) found.add("revision-cycle:$id")
    return Views(
        items = items.mapValues { (id, s) ->
            ItemView(itemStatus(id), itemHeads(id), headContents(id), deleteConflictHeads(id), fieldValues(s.fields), plays(id))
        },
        sets = sets.keys.associateWith { setMembers(it) },
        playlists = playlistIds.associateWith {
            val p = playlists[it]
            PlaylistView(p?.exists?.present == true, fieldValues(p?.fields ?: emptyMap()), playlistSongs(it))
        },
        spaces = spaces.mapValues { ContainerView(it.value.exists.present, fieldValues(it.value.fields)) },
        placements = placements.mapValues { fieldValues(it.value) },
        places = places.mapValues { fieldValues(it.value) },
        profile = fieldValues(profile),
        findings = found,
    )
}
