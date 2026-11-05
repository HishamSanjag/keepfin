package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind the Undo of library actions: each one reverts only what its action changed and keeps what changed since. */
class UndoLogicTest {
    private fun item(id: String = "a", title: String = "Title", artist: String? = "Artist", album: String? = null, track: Int? = null, year: Int? = null, genre: String? = null) =
        LibraryItem(id = id, space = "music", uri = "u:$id", folder = "", kind = LibraryItemKind.AUDIO, title = title, artist = artist, album = album, track = track, year = year,
            genre = genre, size = 1, modifiedAt = 0, addedAt = 0)

    // ---- tags

    @Test fun tagUndoRemovesWhatWasAddedAndReAddsWhatWasRemoved() {
        val before = mapOf("a" to listOf("rock"), "b" to listOf("rock", "night"), "c" to emptyList())
        val steps = UndoLogic.tagInverse(before, add = listOf("night", "road"), remove = listOf("rock"))
        // a: gained night+road, lost rock; b: gained road, lost rock (night was there already); c: gained night+road
        assertEquals(
            setOf(
                UndoLogic.TagStep(listOf("a"), add = listOf("rock"), remove = listOf("night", "road")),
                UndoLogic.TagStep(listOf("b"), add = listOf("rock"), remove = listOf("road")),
                UndoLogic.TagStep(listOf("c"), add = emptyList(), remove = listOf("night", "road")),
            ),
            steps.toSet(),
        )
    }

    @Test fun tagUndoGroupsItemsThatNeedTheSameChangeAndSkipsUnchangedOnes() {
        val before = mapOf("a" to listOf("x"), "b" to listOf("x"), "c" to listOf("rock"))
        val steps = UndoLogic.tagInverse(before, add = listOf("rock"), remove = emptyList())
        // c already had rock: nothing to undo for it
        assertEquals(listOf(UndoLogic.TagStep(listOf("a", "b"), add = emptyList(), remove = listOf("rock"))), steps)
        assertTrue(UndoLogic.tagInverse(mapOf("c" to listOf("rock")), add = listOf("rock"), remove = emptyList()).isEmpty())
    }

    @Test fun tagUndoOnlyTouchesTheTagsOfTheAction() {
        // the user added "later" after the action; the steps never mention it, so applying them keeps it
        val steps = UndoLogic.tagInverse(mapOf("a" to listOf("rock")), add = listOf("night"), remove = emptyList())
        val nowOnTheItem = listOf("rock", "night", "later")
        val undone = steps.single().let { TagLogic.editUserTags(nowOnTheItem, it.add, it.remove) }
        assertEquals(listOf("rock", "later"), undone)
    }

    // ---- move

    @Test fun moveUndoGroupsByOriginAndKeepsFilesMovedAgainSince() {
        val origins = mapOf("a" to "music", "b" to "music", "c" to "videos", "d" to "workout", "e" to null as String?)
        val now = mapOf("a" to "workout", "b" to "videos", "c" to "workout", "d" to "workout", "e" to "workout")
        val back = UndoLogic.moveBack(origins, "workout") { now[it] }
        // b was moved again since (now in videos); d was already in the target; e had no known origin
        assertEquals(mapOf("music" to listOf("a"), "videos" to listOf("c")), back)
    }

    // ---- info

    @Test fun infoUndoWritesBackOnlyTheFieldsTheEditChanged() {
        val before = item(title = "Old", artist = "Band", album = null, track = 3)
        val inverse = UndoLogic.editInverse(before, TagEdit(title = "New", album = "Album", track = 5))
        assertEquals(TagEdit(title = "Old", album = "", track = 3), inverse)
    }

    @Test fun infoUndoClearsANumberTheEditSetAndIgnoresUnchangedFields() {
        val before = item(year = null, genre = "Rock")
        assertEquals(TagEdit(year = 0), UndoLogic.editInverse(before, TagEdit(year = 1999)))
        assertNull("the same value changes nothing", UndoLogic.editInverse(before, TagEdit(genre = "Rock")))
    }

    @Test fun infoUndoIsNotOfferedWhenTheCoverWasReplaced() {
        assertNull(UndoLogic.editInverse(item(), TagEdit(title = "New", coverUri = "content://picked/1")))
    }

    // ---- space

    @Test fun aRemovedSpaceComesBackInFrontOfTheOneThatFollowedItsPredecessor() {
        val before = listOf("downloads", "music", "gym", "videos")
        assertEquals(listOf("downloads", "music", "gym", "videos"), UndoLogic.reinsert(before, listOf("downloads", "music", "videos"), "gym"))
        // a space made since stays where it is; the removed one goes after the closest earlier space still there
        assertEquals(listOf("downloads", "music", "gym", "videos", "new"), UndoLogic.reinsert(before, listOf("downloads", "music", "videos", "new"), "gym"))
        // its predecessor is gone too: it goes to the front
        assertEquals(listOf("gym", "x"), UndoLogic.reinsert(listOf("a", "gym"), listOf("x"), "gym"))
    }

    private fun space(id: String, type: SpaceType = SpaceType.FOLDER) = Space(id, id, SpaceKind.MIXED, id, type = type)

    @Test fun routesFallBackWhenTheirSpaceIsRemovedAndComeBackOnlyIfNotChangedSince() {
        val kept = listOf(space("downloads"), space("music"))
        val after = UndoLogic.routesAfterRemoval(kept, musicRoute = "gym", otherRoute = "downloads", removed = "gym")
        assertEquals("music" to "downloads", after)
        // untouched since: the route points to the space again
        assertEquals("gym" to "downloads", UndoLogic.routesAfterRestore("gym", "gym" to "downloads", after, "music" to "downloads"))
        // the user chose another route meanwhile: it stays
        assertEquals("downloads" to "downloads", UndoLogic.routesAfterRestore("gym", "gym" to "downloads", after, "downloads" to "downloads"))
        // a route that never pointed to the space is left alone
        assertEquals("music" to "videos", UndoLogic.routesAfterRestore("gym", "music" to "downloads", "music" to "downloads", "music" to "videos"))
    }

    // ---- delete

    @Test fun deleteNoticesNameWhereFilesWent() {
        assertEquals("Deleted “Song”", UndoLogic.deletedNotice(listOf("Song"), 1, 1))
        assertEquals("Deleted 3 files", UndoLogic.deletedNotice(emptyList(), 3, 3))
        assertEquals("Deleted 3 files. Files outside the Keepfin folder were removed from the phone for good.", UndoLogic.deletedNotice(emptyList(), 3, 2))
        assertEquals("Deleted “Song”. It was removed from the phone for good.", UndoLogic.deletedNotice(listOf("Song"), 1, 0))
    }

    @Test fun aPartialRestoreIsSaidHonestly() {
        assertNull(UndoLogic.restoreProblem(2, RestoreResult(listOf("a", "b"), emptyList())))
        assertEquals("Brought back 1 of 2. “B” could not come back: another file has its name now.",
            UndoLogic.restoreProblem(2, RestoreResult(listOf("a"), listOf("“B” could not come back: another file has its name now."))))
        assertEquals("Nothing could be brought back. x (and 1 more)", UndoLogic.restoreProblem(2, RestoreResult(emptyList(), listOf("x", "y"))))
    }
}
