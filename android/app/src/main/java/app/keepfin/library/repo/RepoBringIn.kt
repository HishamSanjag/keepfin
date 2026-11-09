package app.keepfin.library.repo

import app.keepfin.BringInResult
import app.keepfin.LibraryAccess
import app.keepfin.OutsideSpace
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.spaces.defaultFolderName
import app.keepfin.folder.FolderError
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * "Bring them in" (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D3 and D4): a linked space is pointed at its default folder in the Keepfin folder, and its
 * files either move in (each copied and checked, then its record points at the copy, then the original goes) or stay where they are. Every item
 * keeps its id, so playlists, favourites and plays are kept; only the place changes. Safe to run again: it handles whatever is still outside.
 */
internal class RepoBringIn(private val core: RepoCore) {
    private fun wire(appId: String) = LibraryMapping.spaceWireId(appId)

    private fun outsideItems(spaceId: String): List<Pair<String, String>> =
        core.rd().rows("SELECT id, place FROM lib_index WHERE space = ?1 AND substr(place, 1, 7) = 'linked:' ORDER BY id", listOf(wire(spaceId))).map { it[0] as String to it[1] as String }

    /** The folder spaces that are linked, with how many of their files are still outside. */
    fun outsideSpaces(): List<OutsideSpace> = core.small.spaces.filter { it.linked && it.type == "folder" }.map { s ->
        val count = core.rd().rows("SELECT count(*) FROM lib_index WHERE space = ?1 AND substr(place, 1, 7) = 'linked:'", listOf(wire(s.id))).firstOrNull()?.get(0)?.let { (it as Number).toInt() } ?: 0
        OutsideSpace(s.id, s.name, count, defaultFolder(s.id, s.name))
    }

    /** The default folder of a space that is free: its name made safe, with a number when another space already uses it. */
    private fun defaultFolder(id: String, name: String): String {
        val taken = core.small.spaces.filter { it.id != id && !it.linked }.map { it.folder.lowercase() }.toSet()
        val base = defaultFolderName(id, name)
        var candidate = base
        var n = 2
        while (candidate.lowercase() in taken) candidate = "$base $n".also { n++ }
        return candidate
    }

    /**
     * Brings [spaceId] in. [move] false is Leave: the space saves new files in the Keepfin folder and the files it has stay where they are.
     * [onProgress] gets (files done, files in all, the file now). [cancelled] is asked between files; what is done stays done.
     */
    fun bringIn(spaceId: String, move: Boolean, onProgress: (Int, Int, String) -> Unit, cancelled: () -> Boolean): BringInResult {
        val space = core.small.spaces.firstOrNull { it.id == spaceId && it.type == "folder" } ?: return BringInResult(0, 0, emptyList(), null)
        val folder = if (space.linked) defaultFolder(space.id, space.name) else space.folder
        if (space.linked) {
            // new files go to the default folder whatever happens to the old ones
            core.commitSpaces { current, routes -> current.map { if (it.id == spaceId) it.copy(linked = false, folder = folder) else it } to routes }
        }
        val items = outsideItems(spaceId)
        if (!move || items.isEmpty()) return BringInResult(0, items.size, emptyList(), null)
        val b = core.backend()
        val media = core.imports ?: return BringInResult(0, items.size, listOf("Keepfin cannot read the files outside its folder on this phone."), null)
        val target = space.copy(linked = false, folder = folder)
        val failed = ArrayList<String>()
        val copied = ArrayList<Pair<LibItem, String>>() // the item and the place of its checked copy
        var done = 0
        for (slice in items.chunked(SLICE)) {
            val known = core.readItems(slice.map { it.first }).first.associateBy { it.id }
            for ((id, place) in slice) {
                if (cancelled()) break
                val item = known[id] ?: continue
                onProgress(done, items.size, item.title)
                val where = core.locators.get(id)
                if (where == null) { failed += "${item.title} must be found again first (Settings > Storage > Find linked files)."; done++; continue }
                val dir = join(target.folder, if (target.layout == "flat") "" else linkedSub(place))
                try {
                    b.folder.mkdirs(dir)
                    val name = core.uniqueIn(b, dir, linkedName(place))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val result = DigestInputStream(media.open(where.uri), digest).use { b.folder.publishFrom(join(dir, name), it) }
                    // the copy was read back by the folder; its fingerprint must also be the original's
                    if (result.sha256 != digest.digest().joinToString("") { "%02x".format(it) }) { b.folder.remove(join(dir, name)); throw FolderError.Io("did not read back the same") }
                    copied += item to join(dir, name)
                } catch (e: FolderError) {
                    failed += "${item.title}: ${e.message}"
                } catch (e: java.io.IOException) {
                    failed += "${item.title} could not be read."
                }
                done++
            }
            if (copied.isNotEmpty()) {
                // the records point at the checked copies before any original is touched
                val placed = copied.associate { it.first.id to it.second }
                core.updateItems(placed.keys) { it.copy(place = placed.getValue(it.id)) }
            }
            if (cancelled()) break
        }
        onProgress(done, items.size, "")
        val access = removeOriginals(copied.map { it.first })
        return BringInResult(copied.size, items.size - copied.size, failed, access as? LibraryAccess.NeedsConfirmation)
    }

    /** The originals of the files that were brought in: Android asks the person first for any it did not make. */
    private fun removeOriginals(items: List<LibItem>): LibraryAccess {
        if (items.isEmpty()) return LibraryAccess.Done
        val gone = HashSet<String>()
        val result = core.deleteLinked(items, gone) { }
        core.locators.remove(items.map { it.id }.toSet() - result.idsAwaiting())
        return result
    }

    private fun LibraryAccess.idsAwaiting(): Set<String> = (this as? LibraryAccess.NeedsConfirmation)?.ids?.toSet() ?: emptySet()
}
