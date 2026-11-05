package app.keepfin.library.repo

import app.keepfin.LibraryItemKind
import app.keepfin.SpaceLogic

/** Linked spaces: looking for the MediaStore files of linked items again, and what the screens are told about them. Inner state is reached through [RepoCore]. */
internal class RepoLinked(private val core: RepoCore) {
    /** Looks for the files of linked spaces. One that is gone is only marked missing (it stays in the library); with no way to tell, nothing is marked. */
    internal fun checkLinked() {
        val media = core.linked ?: return
        val located = linkedItemRows().mapNotNull { r -> core.locators.get(r.first)?.let { r.first to it.uri } }
        val present = if (located.isEmpty()) emptySet() else media.existing(located.map { it.second }) ?: return
        val gone = located.filter { it.second !in present }.mapTo(HashSet()) { it.first }
        if (gone != core.missingLinked) {
            core.missingLinked = gone
            runCatching { core.publish() }
        }
    }

    /** The items of linked spaces: id, placement and the app's id of their space. */
    private fun linkedItemRows(): List<Triple<String, String, String>> =
        core.rd().rows("SELECT id, place, space FROM lib_index WHERE substr(place, 1, 7) = 'linked:'").map { Triple(it[0] as String, it[1] as String, core.small.modelIds[it[2] as String] ?: (it[2] as String)) }

    /** How many items of linked spaces have no address on this device yet (a reinstall, or a folder opened from another phone). */
    internal fun linkedNeedingRelink(): Int = linkedItemRows().count { core.locators.get(it.first) == null } + core.missingLinked.size

    internal fun linkedReadable(): Boolean = core.linked?.canRead() == true

    /**
     * "Find linked files": looks the files of linked spaces up again in MediaStore by relative path and name, and, where this device still
     * knows them, by size and modified time too. Covers items with no address on this device and items whose file was not found.
     * Needs the media-read permission. Blocking.
     */
    internal fun relinkLinked(): RelinkReport {
        val media = core.linked ?: return RelinkReport(0, linkedNeedingRelink())
        val spaces = core.small.spaces.associateBy { it.id }
        val kinds = core.rd().rows("SELECT id, media FROM lib_index WHERE substr(place, 1, 7) = 'linked:'").associate { it[0] as String to it[1] as String }
        val wanted = HashMap<String, Pair<String, LinkedLocator?>>() // item id -> (key, what this device knew)
        for ((id, place, spaceId) in linkedItemRows()) {
            val known = core.locators.get(id)
            if (known != null && id !in core.missingLinked) continue
            val key = known?.path?.takeIf { it.isNotEmpty() } ?: run {
                val space = spaces[spaceId]?.toSpace() ?: return@run null
                val kind = LibraryItemKind.entries.firstOrNull { it.wire == kinds[id] } ?: LibraryItemKind.OTHER
                SpaceLogic.folderPath(app.keepfin.StorageRoot.of(kind), space, linkedSub(place)) + "/" + linkedName(place)
            }
            if (key != null) wanted[id] = key to known
        }
        if (wanted.isEmpty()) return RelinkReport(0, 0)
        val found = media.find(wanted.values.map { it.first })
        val matches = HashMap<String, LinkedLocator>()
        for ((id, ask) in wanted) {
            val hit = found[ask.first.lowercase()] ?: continue
            if (ask.second != null && !ask.second!!.sameFileAs(hit)) continue
            matches[id] = hit
        }
        core.locators.putAll(matches)
        core.missingLinked = core.missingLinked - matches.keys
        runCatching { core.publish() }
        return RelinkReport(matches.size, wanted.size - matches.size)
    }
}
