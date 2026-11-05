package app.keepfin.setup

import app.keepfin.LibraryItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each folder button does, per Android version, and how the files an earlier Keepfin saved are found. */
class RecommendedPlaceTest {
    private class FakeMaker(var mediaStore: Boolean = true, var file: Boolean = true, var throws: Boolean = false) : RecommendedPlace.Maker {
        val calls = ArrayList<String>()
        private fun run(name: String, ok: Boolean): Boolean { calls += name; if (throws) throw IllegalStateException("refused"); return ok }
        override fun viaMediaStore() = run("mediastore", mediaStore)
        override fun viaFile() = run("file", file)
    }

    private fun open(o: RecommendedPlace.Outcome) = (o as RecommendedPlace.Outcome.Open).documentId

    @Test fun routesFollowTheAndroidVersion() {
        assertEquals(RecommendedPlace.Route.MEDIASTORE, RecommendedPlace.route(36, false))
        assertEquals(RecommendedPlace.Route.MEDIASTORE, RecommendedPlace.route(29, false))
        assertEquals(RecommendedPlace.Route.FILE, RecommendedPlace.route(28, true))
        assertEquals(RecommendedPlace.Route.ASK_PERMISSION, RecommendedPlace.route(26, false))
    }

    @Test fun newerAndroidMakesTheFolderThroughMediaStoreAndOpensThePickerInsideIt() {
        val m = FakeMaker()
        assertEquals("primary:Documents/Keepfin", open(RecommendedPlace.prepare(34, false, m)))
        assertEquals(listOf("mediastore"), m.calls)
    }

    @Test fun whenMediaStoreRefusesThePickerOpensAtDocuments() {
        val m = FakeMaker(mediaStore = false)
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.prepare(33, false, m)))
        assertEquals(listOf("mediastore"), m.calls)
    }

    @Test fun neverBlocksWhenNothingCanBeMade() {
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.prepare(34, false, FakeMaker(mediaStore = false))))
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.prepare(34, false, FakeMaker(throws = true))))
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.prepare(27, true, FakeMaker(file = false))))
    }

    @Test fun androidNineAndOlderAsksForStoragePermissionFirst() {
        val m = FakeMaker()
        assertEquals(RecommendedPlace.Outcome.AskPermission, RecommendedPlace.prepare(28, false, m))
        assertTrue(m.calls.isEmpty())
        assertEquals(RecommendedPlace.DOCUMENT_ID, open(RecommendedPlace.prepare(28, true, m)))
        assertEquals(listOf("file"), m.calls)
    }

    @Test fun permissionAnswerOpensThePickerEitherWay() {
        assertEquals(RecommendedPlace.DOCUMENT_ID, open(RecommendedPlace.afterPermission(true, FakeMaker())))
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.afterPermission(false, FakeMaker())))
        assertEquals(RecommendedPlace.DOCUMENTS_ID, open(RecommendedPlace.afterPermission(true, FakeMaker(file = false))))
    }

    @Test fun anExistingKeepfinFolderIsReusedBecauseTheSamePlaceIsOpened() {
        // a reinstall: the folder is there already; making it again changes nothing and the picker opens in it
        val m = FakeMaker()
        assertEquals(RecommendedPlace.DOCUMENT_ID, open(RecommendedPlace.prepare(34, false, m)))
        assertEquals(RecommendedPlace.DOCUMENT_ID, open(RecommendedPlace.prepare(34, false, m)))
    }

    @Test fun otherPlacesOpenAtTheTopOfTheStorage() {
        assertEquals("primary:", RecommendedPlace.STORAGE_ROOT_ID)
    }

    @Test fun documentsItselfIsRefusedButTheKeepfinFolderInsideIsFine() {
        assertTrue(FolderPlaces.forbidden("primary:Documents"))
        assertFalse(FolderPlaces.forbidden(RecommendedPlace.DOCUMENT_ID))
        assertEquals("Internal storage > Documents > Keepfin", FolderPlaces.label(RecommendedPlace.DOCUMENT_ID))
    }

    @Test fun aChosenFolderContainingKeepfinOffersThatOne() {
        assertEquals("keepfin", FolderPlaces.innerName(listOf("Music", "keepfin")))
        assertNull(FolderPlaces.innerName(listOf("Music", "Keepfin Music")))
    }

    // ---- files an earlier Keepfin saved

    private fun row(n: Int, path: String, name: String, kind: LibraryItemKind = LibraryItemKind.AUDIO, owned: Boolean = true) =
        EarlierRow("content://media/external/file/$n", path, name, kind, 100L * n, owned = owned, title = "Song $n")

    @Test fun earlierFilesGroupBySpaceAndKeepTheirSubfolder() {
        val inv = EarlierFiles.inventory(listOf(
            row(1, "Music/Keepfin/", "a.mp3"), row(2, "Music/Keepfin Music/Album/", "b.mp3", owned = false),
            row(3, "Movies/Keepfin/", "c.mp4", LibraryItemKind.VIDEO), row(4, "Download/Keepfin/", "d.zip", LibraryItemKind.OTHER),
            row(5, "Download/Keepfin/", "c.en.vtt", LibraryItemKind.OTHER), row(6, "Pictures/Other/", "x.jpg", LibraryItemKind.IMAGE),
        ))
        assertEquals(4, inv.itemCount)
        assertEquals(listOf(2, 1, 1), listOf("music", "videos", "downloads").map { id -> inv.spaces.first { it.space.id == id }.count })
        assertEquals("Album", inv.items.first { it.item.id.endsWith("/2") }.item.folder)
        assertEquals(1, inv.spaces.first { it.space.id == "music" }.foreign)
        val ui = EarlierFiles.summary(inv)
        assertEquals(4, ui.files)
        assertEquals(listOf("Downloads", "Music", "Videos").sorted(), ui.places.map { it.first }.sorted())
    }

    @Test fun emptyPlacesAreNotInTheSummary() {
        val ui = EarlierFiles.summary(EarlierFiles.inventory(listOf(row(1, "Music/Keepfin/", "a.mp3"))))
        assertEquals(listOf("Music" to 1), ui.places)
    }

    @Test fun onlyFilesTheLibraryDidNotListAreOffered() {
        val base = EarlierFiles.inventory(listOf(row(1, "Music/Keepfin/", "a.mp3")))
        val rows = listOf(row(1, "Music/Keepfin/", "a.mp3"), row(2, "Music/Keepfin/", "b.mp3"), row(3, "Music/Keepfin/", "b.lrc", LibraryItemKind.OTHER))
        val extra = EarlierFiles.untracked(base, rows)
        assertEquals(listOf("content://media/external/file/2"), extra.map { it.uri })
        val merged = EarlierFiles.merge(base, EarlierFiles.inventory(extra))
        assertEquals(2, merged.itemCount)
        assertEquals(2, merged.spaces.first { it.space.id == "music" }.count)
    }
}
