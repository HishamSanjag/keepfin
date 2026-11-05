package app.keepfin.ui.devices

import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyProgressUi
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.PeerCardUi
import app.keepfin.core.copies.CopyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSetupLogicTest {
    private val spaces = listOf("s1" to "Music", "s2" to "Videos", "s3" to "Downloads", "s4" to "Podcasts")

    @Test fun musicAndVideosAreOnToBeginWith() {
        val c = defaultChoices(spaces)
        assertEquals(setOf("s1", "s2"), c.spaces)
        assertFalse(c.favourites)
        assertEquals(SetupDirection.BOTH_WAYS, c.direction)
        assertTrue(c.auto)
    }

    @Test fun nothingChosenCannotMoveOn() {
        assertFalse(hasSelection(SetupChoices(), everything = false))
        assertTrue(hasSelection(SetupChoices(favourites = true), everything = false))
        assertTrue(hasSelection(SetupChoices(), everything = true))
    }

    @Test fun draftIsACopyOnTheDeviceBothWaysOrBackup() {
        val both = draftFor("dev1", "Work laptop", SetupChoices(setOf("s1"), favourites = true))
        assertEquals("peer:dev1", both.treeUri)
        assertEquals(CopyMode.TwoWay, both.mode)
        assertEquals(CopyHoldsUi(false, setOf("s1"), emptySet(), true), both.holds)
        assertTrue(both.auto)
        val only = draftFor("dev1", "Work laptop", SetupChoices(setOf("s1"), direction = SetupDirection.ONLY_TO, auto = false))
        assertEquals(CopyMode.Backup, only.mode)
        assertFalse(only.auto)
        assertTrue(draftFor("dev1", "X", SetupChoices(), everything = true).holds.all)
    }

    private fun ready(files: Int = 120, bytes: Long = 2L * 1024 * 1024 * 1024, imports: Int = 0, enough: Boolean? = true, free: Long? = 50L * 1024 * 1024 * 1024, diffs: List<Pair<String, String>> = emptyList(), more: Int = 0) =
        CopyPreview("p1", "ready", null, true, emptyList(), files, bytes, 0, diffs, more, free, enough, imports)

    @Test fun reviewSaysSizesRoomAndThatNothingIsRemoved() {
        val s = reviewSummary(ready(imports = 4), "Work laptop", SetupDirection.BOTH_WAYS)
        assertTrue(s.ready)
        assertEquals("To Work laptop: 120 files · 2.0 GB", s.toDevice)
        assertEquals("To this phone: 4 files from Work laptop", s.toHere)
        assertEquals("Enough room on Work laptop (50.0 GB free)", s.room)
        assertTrue(s.differs.isEmpty())
    }

    @Test fun onlyToHasNoComingBackLine() {
        assertNull(reviewSummary(ready(), "Work laptop", SetupDirection.ONLY_TO).toHere)
    }

    @Test fun notEnoughRoomIsNotReady() {
        val s = reviewSummary(ready(enough = false, free = 1024L * 1024), "Work laptop", SetupDirection.BOTH_WAYS)
        assertFalse(s.ready)
        assertTrue(s.problem!!.contains("does not have enough room"))
    }

    @Test fun differencesShowFiveThenCountTheRest() {
        val d = (1..8).map { "Music/song$it.flac" to "different" }
        val s = reviewSummary(ready(diffs = d, more = 20), "Work laptop", SetupDirection.BOTH_WAYS)
        assertEquals(5, s.differs.size)
        assertEquals("song1.flac: different", s.differs[0])
        assertEquals(23, s.differsMore)
    }

    @Test fun problemStatesSayWhatToDo() {
        for (status in listOf("refused", "disconnected", "busy")) {
            val s = reviewSummary(CopyPreview("p", status, null), "Work laptop", SetupDirection.BOTH_WAYS)
            assertFalse(status, s.ready)
            assertTrue(status, s.problem!!.contains("Check again"))
        }
        // held (the deletion guard) can still start, as on Windows: Keepfin asks before it acts
        val held = reviewSummary(CopyPreview("p", "held", null), "Work laptop", SetupDirection.BOTH_WAYS)
        assertTrue(held.ready)
        assertTrue(held.note!!.contains("Keepfin will ask you before it starts"))
        assertTrue(reviewSummary(CopyPreview("p", "disconnected", null), "Work laptop", SetupDirection.BOTH_WAYS).problem!!.contains("Work laptop is not connected"))
    }

    private fun card(status: CopyStatus, progress: CopyProgressUi? = null, review: Int = 0, online: Boolean = true, verified: Long? = null, waiting: String? = null) = CopyCardUi(
        "c1", "Copy on Work laptop", CopyMode.TwoWay, CopyHoldsUi(), "", online, status, review = review, progress = progress, lastVerifiedAt = verified,
        peer = PeerCardUi("dev1", "Work laptop", online, waiting),
    )

    @Test fun statusSentences() {
        assertEquals("Copying 120 of 800 files · 1.0 GB left", copyStatusSentence(card(CopyStatus.SYNCING, CopyProgressUi(120, 800, 1024L * 1024 * 1024, 2L * 1024 * 1024 * 1024, null)), "Work laptop"))
        assertTrue(copyStatusSentence(card(CopyStatus.UP_TO_DATE, verified = 1_700_000_000_000), "Work laptop").startsWith("Up to date · checked "))
        assertEquals("Up to date", copyStatusSentence(card(CopyStatus.UP_TO_DATE), "Work laptop"))
        assertEquals("Waiting for Work laptop", copyStatusSentence(card(CopyStatus.WAITING, online = false), "Work laptop"))
        assertEquals("3 files need a decision", copyStatusSentence(card(CopyStatus.REVIEW, review = 3), "Work laptop"))
        assertEquals("1 file needs a decision", copyStatusSentence(card(CopyStatus.UP_TO_DATE, review = 1), "Work laptop"))
        assertEquals("Waiting for Wi-Fi", copyStatusSentence(card(CopyStatus.WAITING, waiting = "Waiting for Wi-Fi"), "Work laptop"))
    }
}
