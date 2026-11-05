package app.keepfin.setup

import app.keepfin.LibraryCodec
import app.keepfin.LibraryCollection
import app.keepfin.LibrarySettings
import app.keepfin.Space
import app.keepfin.SpaceType

/** What one earlier space holds (read-only counts for the setup screen). */
internal class InvSpace(val space: Space, val items: List<LibraryCodec.Rec>) {
    val count: Int get() = items.size
    val bytes: Long get() = items.sumOf { it.item.size }

    /** Files this install created (deleting them needs no consent). */
    val owned: Int get() = items.count { it.owned }

    /** Files from before a reinstall: Android asks once per batch before they are removed. */
    val foreign: Int get() = items.count { !it.owned }
}

/**
 * The earlier library (`filesDir/library.json`) grouped by space. Read-only: nothing here touches a file
 * (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 5, step 2).
 */
internal class LegacyInventory(
    val spaces: List<InvSpace>,
    val collections: List<LibraryCollection>,
    val settings: LibrarySettings,
) {
    val items: List<LibraryCodec.Rec> get() = spaces.flatMap { it.items }
    val itemCount: Int get() = spaces.sumOf { it.count }
    val bytes: Long get() = spaces.sumOf { it.bytes }
    val favourites: Int get() = items.count { it.item.favorite }
    val playlists: Int get() = collections.count { it.kind == app.keepfin.CollectionKind.OWN }
    val downloaded: Int get() = collections.size - playlists
    val smartSpaces: List<Space> get() = settings.spaces.filter { it.type == SpaceType.SMART }
}

internal object LegacyInventories {
    /** Reads the library file text. An item whose space no longer exists is counted under Downloads. */
    fun read(libraryJson: String, settings: LibrarySettings): LegacyInventory {
        val data = LibraryCodec.decodeFile(libraryJson)
        val folderSpaces = settings.spaces.filter { it.type == SpaceType.FOLDER }
        val known = folderSpaces.map { it.id }.toSet()
        val fallback = folderSpaces.firstOrNull { it.id == "downloads" }?.id ?: folderSpaces.firstOrNull()?.id ?: "downloads"
        val grouped = data.items.groupBy { if (it.item.space in known) it.item.space else fallback }
        val spaces = folderSpaces.map { InvSpace(it, grouped[it.id].orEmpty()) }
        return LegacyInventory(spaces, data.collections, settings)
    }
}
