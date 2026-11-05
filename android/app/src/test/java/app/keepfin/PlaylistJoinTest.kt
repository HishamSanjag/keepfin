package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Add to playlist" from the download review: a finished download's item joins the chosen playlist; nothing else happens. */
class PlaylistJoinTest {
    private fun playlist(id: String, kind: CollectionKind = CollectionKind.OWN, items: List<String> = emptyList()) =
        LibraryCollection(id, id, kind, "music", items, createdAt = 0, updatedAt = 0)

    /** A tiny library: the collections it holds, and what the join added to them. */
    private class Shelf(vararg collections: LibraryCollection) {
        val held = collections.associateBy { it.id }.toMutableMap()
        fun join(playlist: String?, items: List<String>) =
            PlaylistJoin.join(playlist, items, { held[it] }) { id, add -> held[id] = held.getValue(id).let { it.copy(itemIds = it.itemIds + add) } }
    }

    @Test fun aSavedItemJoinsTheChosenPlaylistInOrder() {
        val shelf = Shelf(playlist("road"))
        assertEquals(2, shelf.join("road", listOf("a", "b")))
        assertEquals(listOf("a", "b"), shelf.held.getValue("road").itemIds)
    }

    @Test fun anItemThatWasAlreadyThereJoinsAsThatItemAndAddingTwiceDoesNotDuplicate() {
        val shelf = Shelf(playlist("road"), playlist("gym"))
        assertEquals(1, shelf.join("road", listOf("song-you-had")))
        assertEquals(1, shelf.join("gym", listOf("song-you-had")))
        assertEquals(0, shelf.join("gym", listOf("song-you-had")))
        assertEquals(listOf("song-you-had"), shelf.held.getValue("gym").itemIds)
    }

    @Test fun noPlaylistADeletedPlaylistAndAnAutomaticOneAddNothing() {
        val shelf = Shelf(playlist("album", CollectionKind.ALBUM))
        assertEquals(0, shelf.join(null, listOf("a")))
        assertEquals(0, shelf.join("deleted-since", listOf("a")))
        assertEquals(0, shelf.join("album", listOf("a")))
        assertEquals(emptyList<String>(), shelf.held.getValue("album").itemIds)
    }

    @Test fun aFailedDownloadHasNoItemToAdd() {
        val shelf = Shelf(playlist("road"))
        assertEquals(0, shelf.join("road", emptyList()))
    }
}
