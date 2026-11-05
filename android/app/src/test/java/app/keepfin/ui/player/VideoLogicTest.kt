package app.keepfin.ui.player

import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.library.LibraryViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of the playback core: milliseconds in and out of VideoLogic, the list cap and the folder lookup on library
 * items. The rules themselves are the shared core's and are checked for both apps by fixtures/engine/playback.
 */
class VideoLogicTest {
    private fun video(id: String, title: String = id, folder: String = "f") =
        LibraryItem(id = id, space = "s", uri = id, folder = folder, kind = LibraryItemKind.VIDEO, title = title, size = 1, modifiedAt = 0, addedAt = 0)

    @Test fun skipsInTheSameDirectionWithinASecondAddUp() {
        var run = VideoLogic.accumulate(null, true, 1_000)
        assertEquals("+10 s", VideoLogic.skipLabel(run))
        run = VideoLogic.accumulate(run, true, 1_800)
        run = VideoLogic.accumulate(run, true, 2_700)
        assertEquals("+30 s", VideoLogic.skipLabel(run))
        assertEquals(2_700.0, run.at, 0.0)
    }

    @Test fun aPauseOrAnotherDirectionStartsOver() {
        val run = VideoLogic.accumulate(VideoLogic.accumulate(null, true, 0), true, 500)
        assertEquals("+10 s", VideoLogic.skipLabel(VideoLogic.accumulate(run, true, 1_501)))
        assertEquals("−10 s", VideoLogic.skipLabel(VideoLogic.accumulate(run, false, 700)))
        assertEquals("−20 s", VideoLogic.skipLabel(VideoLogic.accumulate(VideoLogic.accumulate(null, false, 0), false, 900)))
    }

    @Test fun aTapContinuesARunOnlyOnTheSameSideInsideTheWindow() {
        val run = VideoLogic.accumulate(null, false, 0)
        assertTrue(VideoLogic.continues(run, false, 1_000))
        assertFalse(VideoLogic.continues(run, false, 1_001))
        assertFalse(VideoLogic.continues(run, true, 100))
        assertFalse(VideoLogic.continues(null, true, 100))
    }

    @Test fun resumeNeedsMoreThanTenSecondsAndNotTheLastEightPercent() {
        assertEquals(0L, VideoLogic.resumeStartMs(null, 600.0, false))
        assertEquals(0L, VideoLogic.resumeStartMs(10.0, 600.0, false))
        assertEquals(10_500L, VideoLogic.resumeStartMs(10.5, 600.0, false))
        assertEquals(551_000L, VideoLogic.resumeStartMs(551.0, 600.0, false)) // 91.8 %
        assertEquals(0L, VideoLogic.resumeStartMs(552.0, 600.0, false)) // exactly 92 %
        assertEquals(0L, VideoLogic.resumeStartMs(590.0, 600.0, false))
        assertEquals(0L, VideoLogic.resumeStartMs(300.0, 600.0, true)) // marked watched
        assertEquals(300_000L, VideoLogic.resumeStartMs(300.0, null, false)) // length unknown
        assertEquals(300_000L, VideoLogic.resumeStartMs(300.0, 0.0, false))
    }

    @Test fun chapterLookupAndScrubCrossing() {
        val starts = listOf(0L, 135_000L, 400_000L)
        assertEquals(0, VideoLogic.chapterIndexAt(starts, 0))
        assertEquals(0, VideoLogic.chapterIndexAt(starts, 134_999))
        assertEquals(1, VideoLogic.chapterIndexAt(starts, 135_000))
        assertEquals(2, VideoLogic.chapterIndexAt(starts, 9_999_999))
        assertEquals(-1, VideoLogic.chapterIndexAt(listOf(5_000L), 1_000))
        assertEquals(-1, VideoLogic.chapterIndexAt(emptyList(), 1_000))
        assertTrue(VideoLogic.crossedChapter(starts, 100_000, 140_000))
        assertTrue(VideoLogic.crossedChapter(starts, 500_000, 100_000))
        assertFalse(VideoLogic.crossedChapter(starts, 10_000, 20_000))
        assertFalse(VideoLogic.crossedChapter(emptyList(), 10_000, 900_000))
    }

    @Test fun speedsAndLabels() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), VideoLogic.SPEEDS)
        assertEquals("Speed 1.5×", VideoLogic.speedPill(1.5f))
        assertEquals("Off", VideoLogic.sleepSummary(false, false, 0))
        assertEquals("End of video", VideoLogic.sleepSummary(true, true, 0))
        assertEquals("14:59 left", VideoLogic.sleepSummary(true, false, 899_000))
        assertEquals("1:02:03", VideoLogic.timeLabel(3_723_000))
        assertEquals("0:09", VideoLogic.timeLabel(9_999))
    }

    @Test fun neighboursFollowTheListTheVideoWasOpenedFrom() {
        val list = listOf("a", "b", "c")
        assertEquals("c", LibraryViews.neighbourInContext(list, "b", 1))
        assertEquals("a", LibraryViews.neighbourInContext(list, "b", -1))
        assertNull(LibraryViews.neighbourInContext(list, "c", 1))
        assertNull(LibraryViews.neighbourInContext(list, "a", -1))
        assertNull(LibraryViews.neighbourInContext(list, "zzz", 1))
    }

    @Test fun theListIsCappedAroundTheOpenedVideo() {
        val ids = (0 until 1200).map { "v$it" }
        val window = LibraryViews.windowAround(ids, "v600", 500)
        assertEquals(500, window.size)
        assertTrue("v600" in window)
        assertEquals("v350", window.first())
        val start = LibraryViews.windowAround(ids, "v3", 500)
        assertEquals("v0", start.first()); assertEquals(500, start.size)
        val end = LibraryViews.windowAround(ids, "v1199", 500)
        assertEquals("v1199", end.last()); assertEquals(500, end.size)
        assertEquals(ids.take(3), LibraryViews.windowAround(ids.take(3), "v1", 500))
    }

    @Test fun folderFallbackUsesNaturalTitleOrder() {
        val all = listOf(video("10", "Episode 10"), video("2", "Episode 2"), video("1", "Episode 1"), video("x", "Episode 3", folder = "other"))
        assertEquals("2", LibraryViews.nextInFolder(all[2], all)?.id)
        assertEquals("10", LibraryViews.nextInFolder(all[1], all)?.id)
        assertNull(LibraryViews.nextInFolder(all[0], all))
        assertEquals("2", LibraryViews.previousInFolder(all[0], all)?.id)
        assertNull(LibraryViews.previousInFolder(all[2], all))
    }
}
