package app.keepfin.library.repo

import app.keepfin.CollectionLogic
import app.keepfin.LibraryAccess
import app.keepfin.RestoreResult
import app.keepfin.TrashEntry
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibraryMapping
import app.keepfin.folder.FolderError

/** The Keepfin trash: what a delete leaves in `trash/<item id>/`, the Trash page, Undo and Restore. Inner state is reached through [RepoCore]. */
internal class RepoTrash(private val core: RepoCore) {
    private companion object {
        const val TRASH_MEMORY = 2000
        /** How long a deleted file stays in the Keepfin trash (dev-docs/plans/KEEPFIN-FOLDER.md, `trash/`). */
        const val TRASH_DAYS = 30
        const val DAY_MS = 24L * 3_600_000L
        /** In `trash/<item id>/`: the delete was Delete for good, so the folder is erased once its Undo has run out (or at the next purge). */
        const val ERASE_MARK = ".erase"
        /** The folder a delete makes for one item: the item's id. */
        val TRASHED_ITEM = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        /** A trash folder the Trash page lists: the item id, which the Windows app may follow with " (2)" when the name was taken. */
        val TRASH_FOLDER = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{20})( \\(\\d+\\))?$")
        /** In `trash/<item id>/`: what Restore needs after a restart (see [TrashInfo]). */
        const val INFO_FILE = "item.json"
    }

    /**
     * What a delete left in the trash, for [restoreItems]: the item as the library held it (tags, favourite and this device's play count
     * included), the content it had, where the file stood and which sidecars went with it. Taken when the delete runs, never rebuilt
     * later. It lives in memory (Undo is offered for a minute or so); the files themselves stay in `trash/<id>/` in the Keepfin folder.
     */
    private class TrashedItem(val item: LibItem, val content: String?, val size: Long, val place: String, val sidecars: List<String>, val memberships: List<Membership>)

    /** A collection as it stood when a delete ran (shared by every item that delete took out of it): its id and the order of its items then. */
    private class Membership(val collection: String, val before: List<String>)

    private val trashedItems = object : LinkedHashMap<String, TrashedItem>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrashedItem>?) = size > TRASH_MEMORY
    }

    fun trashedAmong(ids: List<String>): List<String> = synchronized(trashedItems) { ids.filter { it in trashedItems } }

    /**
     * Empties what deletes left in the trash ([deleteItems] puts each item's files in `trash/<item id>/`) after [TRASH_DAYS] days, counted
     * from when the folder was made there, the one place a library delete removes bytes for good. The trash of copies (`trash/copies`,
     * with its own catalogue and expiry) and anything that is not an item's folder are left alone, as is a folder whose time the storage
     * does not say. Returns how many were emptied.
     */
    internal fun purgeTrash(): Int {
        val b = core.backend()
        val listing = try { b.folder.list(b.layout.trash) } catch (e: FolderError.NotFound) { return 0 }
        if (listing.possiblyIncomplete) return 0
        val cutoff = core.now() - TRASH_DAYS * 24L * 3_600_000L
        var emptied = 0
        for (e in listing.entries) {
            if (!e.isDirectory || !TRASHED_ITEM.matches(e.name)) continue
            if (synchronized(trashedItems) { e.name in trashedItems }) continue
            val old = e.modifiedMs > 0 && e.modifiedMs <= cutoff
            // a Delete for good whose notice never ended (the app closed first) goes now, whatever its age
            if (!old && b.folder.stat("${b.layout.trash}/${e.name}/$ERASE_MARK") == null) continue
            b.folder.remove("${b.layout.trash}/${e.name}")
            emptied++
        }
        return emptied
    }

    fun markForErase(ids: List<String>) {
        val b = core.backend()
        for (id in ids.filter { TRASHED_ITEM.matches(it) }) {
            try { b.folder.publish("${b.layout.trash}/$id/$ERASE_MARK", ByteArray(0)) } catch (e: FolderError) { core.log("Delete for good could not be marked", e) }
        }
    }

    fun eraseTrashed(ids: List<String>): Int {
        val b = core.backend()
        var erased = 0
        for (id in ids.filter { TRASHED_ITEM.matches(it) }) {
            synchronized(trashedItems) { trashedItems.remove(id) }
            try {
                if (b.folder.stat("${b.layout.trash}/$id") == null) continue
                b.folder.remove("${b.layout.trash}/$id")
                erased++
            } catch (e: FolderError) {
                // the mark stays: the next purge erases it
                core.log("A file deleted for good could not be erased yet", e)
            }
        }
        return erased
    }

    // ------------------------------------------------------------------------------------------------ the Trash page

    val keepsTrash: Boolean get() = true

    /** Written beside the files when a delete runs, so Restore needs nothing in memory (after a restart, or on another phone). */
    private fun writeTrashInfo(b: RepositoryBackend, dir: String, t: TrashedItem) {
        val info = TrashInfo(
            id = t.item.id, title = t.item.title, place = t.place, space = t.item.space, size = t.size, kind = t.item.kind, deletedAt = core.now(),
            content = t.content, sidecars = t.sidecars, item = t.item,
            places = t.memberships.map { TrashPlace(it.collection, TrashInfo.window(it.before, t.item.id)) },
        )
        try { b.folder.replace(join(dir, INFO_FILE), info.toBytes()) } catch (e: FolderError) { core.log("What restore needs could not be written to the trash", e) }
    }

    private fun readTrashInfo(b: RepositoryBackend, dir: String): TrashInfo? = try {
        if (b.folder.stat(join(dir, INFO_FILE)) == null) null else TrashInfo.parse(b.folder.read(join(dir, INFO_FILE)))
    } catch (e: FolderError) { null }

    /** The media file of a trash folder: the one the record names, else the largest file that is not a sidecar, a cover or the record. */
    private fun mediaOf(files: List<app.keepfin.folder.FolderEntry>, wanted: String?): app.keepfin.folder.FolderEntry? {
        val all = files.filter { !it.isDirectory && it.name != INFO_FILE && it.name != ERASE_MARK }
        all.firstOrNull { it.name == wanted }?.let { return it }
        val plain = all.filter { it.name.substringAfterLast('.', "").lowercase() !in app.keepfin.ALL_SIDECARS && app.keepfin.core.copies.videoCompanionStem(it.name) == null }
        return plain.filterNot { it.name.startsWith("art.") }.ifEmpty { plain }.maxByOrNull { it.size }
    }

    /**
     * What Restore needs for the trash folder [key], from the folder itself. With the record: the item exactly as it was. Without a
     * readable one: the file comes back as a fresh item of the space its kind goes to (a new copy of the library's work; same id when
     * the folder is named for one), with what its name says. Null when there is no media file or nowhere to put it.
     */
    private fun loadTrashed(b: RepositoryBackend, key: String): TrashedItem? {
        if (!TRASH_FOLDER.matches(key)) return null
        val dir = join(b.layout.trash, key)
        val files = try { b.folder.list(dir).entries } catch (e: FolderError.NotFound) { return null }
        val info = readTrashInfo(b, dir)
        val media = mediaOf(files, info?.place?.substringAfterLast('/')) ?: return null
        val record = info?.item
        // a record from elsewhere never puts a file outside the Keepfin folder or into its .keepfin data
        val place = (info?.place ?: record?.place)?.takeIf { p -> !p.startsWith("/") && !p.contains('\\') && p.split('/').none { it == ".." } && !p.startsWith("${app.keepfin.folder.FolderLayout.DATA_DIR}/") }
        if (record != null && place != null) {
            return TrashedItem(record.copy(place = place), info.content, info.size ?: media.size, place, info.sidecars, info.places.map { Membership(it.collection, it.before) })
        }
        val kind = kindOf(media.name, null)
        val routed = if (kind == "audio") core.small.routes.music else core.small.routes.other
        val space = core.small.spaces.firstOrNull { it.id == routed && it.type == "folder" } ?: core.small.spaces.firstOrNull { it.type == "folder" } ?: return null
        val target = join(space.folder, media.name)
        val id = info?.id ?: key.substringBefore(' ')
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        b.folder.openRead(join(dir, media.name)).use { s -> val buf = ByteArray(65536); while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        val item = LibItem(id = id, space = space.id, kind = kind, title = info?.title ?: media.name.substringBeforeLast('.', media.name), addedAt = core.now().toDouble(), place = target)
        return TrashedItem(item, "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }, media.size, target, emptyList(), emptyList())
    }

    fun trashEntries(): List<TrashEntry> {
        val b = core.backend()
        val listing = try { b.folder.list(b.layout.trash) } catch (e: FolderError.NotFound) { return emptyList() }
        val out = ArrayList<TrashEntry>()
        for (e in listing.entries) {
            if (!e.isDirectory || !TRASH_FOLDER.matches(e.name)) continue // the trash of copies and anything else is not ours
            try {
                val dir = join(b.layout.trash, e.name)
                if (b.folder.stat(join(dir, ERASE_MARK)) != null) continue // deleted for good: its notice ended, it only waits to be erased
                val files = b.folder.list(dir).entries
                val info = readTrashInfo(b, dir)
                val media = mediaOf(files, info?.place?.substringAfterLast('/')) ?: continue
                val deletedAt = info?.deletedAt ?: e.modifiedMs.takeIf { it > 0 } ?: core.now()
                val days = ((core.now() - deletedAt) / DAY_MS).coerceAtLeast(0)
                out += TrashEntry(
                    id = e.name, itemId = info?.item?.id ?: info?.id ?: e.name.substringBefore(' '),
                    title = info?.item?.title ?: info?.title ?: media.name.substringBeforeLast('.', media.name),
                    fileName = media.name, space = info?.item?.space ?: info?.space.orEmpty(), size = media.size.coerceAtLeast(0),
                    deletedAt = deletedAt, daysLeft = (TRASH_DAYS - days).coerceIn(0, TRASH_DAYS.toLong()).toInt(),
                )
            } catch (x: FolderError) { core.log("A trash folder could not be read", x) }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    fun eraseFromTrash(ids: List<String>): Int {
        val b = core.backend()
        var erased = 0
        for (id in ids.distinct().filter { TRASH_FOLDER.matches(it) }) {
            // an Undo that still held it must not bring it back later
            synchronized(trashedItems) { trashedItems.remove(id); trashedItems.remove(id.substringBefore(' ')) }
            try {
                if (b.folder.stat(join(b.layout.trash, id)) == null) continue
                b.folder.remove(join(b.layout.trash, id))
                erased++
            } catch (e: FolderError) {
                core.log("A file in the trash could not be erased", e)
            }
        }
        return erased
    }

    fun emptyTrash(): Int = eraseFromTrash(trashEntries().map { it.id })

    fun trashableCount(ids: List<String>): Int =
        ids.distinct().chunked(SLICE).sumOf { slice -> core.readItems(slice).first.count { it.place != null && !isLinkedPlace(it.place) } }

    /**
     * Brings deleted files back from the Keepfin trash: each file returns to the path it had (never over a file that has taken the
     * name since), its sidecars with it, and the item is written again with the same id, its tags, favourite and play count. The
     * delete took it out of its collections (their entries go with an item), so it goes back into each one that still exists, in front
     * of the song that followed it ([CollectionLogic.restoreOrder] on the collection as it is now, so changes made since stay). Items
     * that cannot come back are reported with the reason and stay in the trash.
     */
    fun restoreItems(ids: List<String>): RestoreResult {
        val b = core.backend()
        val back = ArrayList<TrashedItem>()
        val keys = HashMap<String, String>() // item id -> its trash folder name
        val restored = ArrayList<String>()
        val failed = ArrayList<String>()
        for (id in ids.distinct()) {
            // the Undo record in memory, else what the trash folder itself says (after a restart, or for a folder the Windows app made)
            val t = synchronized(trashedItems) { trashedItems[id] } ?: try { loadTrashed(b, id) } catch (e: FolderError) { null }
            if (t == null) { failed += "A deleted file is no longer in the Keepfin trash."; continue }
            if (core.hasItem(t.item.id)) { restored += t.item.id; synchronized(trashedItems) { trashedItems.remove(id); trashedItems.remove(t.item.id) }; continue }
            val dir = "${b.layout.trash}/$id"
            val inTrash = join(dir, t.place.substringAfterLast('/'))
            try {
                if (b.folder.stat(inTrash) == null) { failed += "“${t.item.title}” is no longer in the Keepfin trash."; continue }
                if (b.folder.stat(t.place) != null) { failed += "“${t.item.title}” could not come back: another file has its name now."; continue }
                b.folder.mkdirs(t.place.substringBeforeLast('/', ""))
                core.moveFile(b, inTrash, t.place)
                for (side in t.sidecars) {
                    val from = join(dir, side.substringAfterLast('/'))
                    try { if (b.folder.stat(from) != null && b.folder.stat(side) == null) core.moveFile(b, from, side) } catch (e: FolderError) { core.log("sidecar restore failed", e) }
                }
                // Undo of a Delete for good: nothing is left to erase
                try { if (b.folder.stat("$dir/$ERASE_MARK") != null) b.folder.remove("$dir/$ERASE_MARK") } catch (e: FolderError) { core.log("erase mark not cleared", e) }
            } catch (e: FolderError) {
                failed += "“${t.item.title}” could not come back: ${e.message}"
                continue
            }
            back += t
            keys[t.item.id] = id
        }
        if (back.isNotEmpty()) {
            core.addItems(back.map { it.item }, back.filter { it.content != null }.map { LibraryMapping.reviseDraft(it.item.id, it.content!!, it.size, emptyList()) })
            synchronized(trashedItems) { back.forEach { trashedItems.remove(it.item.id); keys[it.item.id]?.let { k -> trashedItems.remove(k) } } }
            // lyrics, subtitles and a music video come back with the song; their records are written again so copies carry them
            runCatching { core.recordCompanionsOf(b, back.filter { it.sidecars.isNotEmpty() }.map { Triple(it.item.id, it.place, it.item.kind) }) }.onFailure { core.log("The files beside a restored song could not be recorded.", it) }
            // the folder keeps nothing of an item that is back: its record goes, and the folder with it when nothing else is in it (the cover Windows keeps)
            for (t in back) {
                val dir = "${b.layout.trash}/${keys[t.item.id] ?: t.item.id}"
                try {
                    b.folder.remove("$dir/$INFO_FILE")
                    if (b.folder.list(dir).entries.isEmpty()) b.folder.remove(dir)
                } catch (e: FolderError) { core.log("trash folder not cleared", e) }
            }
            restored += back.map { it.item.id }
            // one pass per collection and delete: all the items that delete took out of it go back together
            val groups = java.util.IdentityHashMap<Membership, MutableSet<String>>()
            for (t in back) for (m in t.memberships) groups.getOrPut(m) { LinkedHashSet() } += t.item.id
            for ((m, removed) in groups) {
                val now = core.small.collections.firstOrNull { it.id == m.collection }?.itemIds ?: continue // the collection is gone: nothing to put back into
                val present = now.toHashSet()
                core.collectionAdd(m.collection, removed.filter { it !in present })
                val added = core.small.collections.firstOrNull { it.id == m.collection }?.itemIds ?: continue
                core.collectionReorder(m.collection, CollectionLogic.restoreOrder(m.before, added, removed))
            }
        }
        return RestoreResult(restored, failed)
    }

    fun deleteItems(ids: List<String>): LibraryAccess {
        val b = core.backend()
        val gone = HashSet<String>()
        val linkedItems = ArrayList<LibItem>()
        var failure: String? = null
        // the collections as they are now, for Undo (the delete takes the items out of them); one set per collection, shared by all the items
        val memberOf = core.small.collections.filter { it.itemIds.isNotEmpty() }.map { Membership(it.id, it.itemIds) to it.itemIds.toHashSet() }
        for (slice in ids.distinct().chunked(SLICE)) {
        val (items, plays) = core.readItems(slice)
        val known = items.associateBy { it.id }
        for (id in slice) {
            val item = known[id] ?: continue
            val place = item.place
            if (isLinkedPlace(place)) { linkedItems += item; continue }
            try {
                if (place != null && b.folder.stat(place) != null) {
                    val dir = "${b.layout.trash}/$id"
                    b.folder.mkdirs(dir)
                    // the same item deleted for good before and brought back: this delete is an ordinary one
                    if (b.folder.stat("$dir/$ERASE_MARK") != null) b.folder.remove("$dir/$ERASE_MARK")
                    val name = place.substringAfterLast('/')
                    val row = core.rd().rows("SELECT content, size FROM lib_index WHERE id = ?", listOf(id)).firstOrNull()
                    core.moveFile(b, place, join(dir, name))
                    val moved = ArrayList<String>()
                    for (side in core.sidecarsOf(b, place)) try { core.moveFile(b, side, join(dir, side.substringAfterLast('/'))); moved += side } catch (e: FolderError) { core.log("sidecar delete failed", e) }
                    val own = plays[id] ?: 0L
                    val kept = TrashedItem(item.copy(plays = if (own > 0) own.toDouble() else null), row?.get(0) as String?, (row?.get(1) as? Number)?.toLong() ?: 0L, place, moved, memberOf.filter { id in it.second }.map { it.first })
                    synchronized(trashedItems) { trashedItems[id] = kept }
                    writeTrashInfo(b, dir, kept)
                }
                gone += id
            } catch (e: FolderError) {
                if (failure == null) failure = e.message
            }
        }
        }
        var waiting: LibraryAccess = LibraryAccess.Done
        if (linkedItems.isNotEmpty()) waiting = core.deleteLinked(linkedItems, gone) { if (failure == null) failure = it }
        if (gone.isNotEmpty()) core.removeItems(gone)
        if (waiting !is LibraryAccess.Done) return waiting
        if (failure != null) throw IllegalStateException(if (gone.isEmpty()) failure else "Some files could not be deleted: $failure")
        return LibraryAccess.Done
    }
}
