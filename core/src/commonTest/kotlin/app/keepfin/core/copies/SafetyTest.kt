package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals

class SafetyTest {
    @Test fun filesOnlyHereAreNamedAndOfferACopy() {
        val s = librarySafety(2, 164, listOf(OnePlace("songs", 81, "Hisham2", true), OnePlace("videos", 1, "Hisham2", true)), "this PC")
        assertEquals(Safety("warn", "82 files have only one copy", "81 songs and 1 video are only in your main storage on this PC.", true), s)
    }

    @Test fun filesOnlyElsewhereNeedNoCopyFromHere() {
        val s = librarySafety(3, 2000, listOf(OnePlace("videos", 1200, "Desk PC", false)), "this phone")
        assertEquals(Safety("warn", "1,200 files have only one copy", "1,200 videos are only on Desk PC.", false), s)
    }

    @Test fun oneSongOnlyHereAndSomeElsewhere() {
        val s = librarySafety(3, 20, listOf(OnePlace("other", 2, "Desk PC", false), OnePlace("songs", 1, "Pixel", true)), "this phone")
        assertEquals("3 files have only one copy", s.title)
        assertEquals("1 song is only in your main storage on this phone. 2 other files are only on Desk PC.", s.detail)
        assertEquals(true, s.keepCopy)
    }

    @Test fun filesAreSummedUpByKindLargestFirst() {
        val lrc = (1..52).map { "Music/rr/Song $it.LRC" }
        assertEquals("52 lyrics files and 1 video", filesSummary(lrc + "Downloads\\YouTube\\clip.mp4"))
        assertEquals("1 song", filesSummary(listOf("a.mp3")))
        assertEquals("2 subtitle files, 1 picture and 1 other file", filesSummary(listOf("a.en.srt", "b.vtt", "cover.jpg", "notes")))
    }

    @Test fun onePlaceEverythingCopiedAndEmpty() {
        assertEquals(Safety("warn", "Your library is only on this PC", "Keep a copy on a drive, in the cloud or on another device, so one broken disk cannot lose it.", true), librarySafety(1, 5, emptyList(), "this PC"))
        assertEquals(Safety("safe", "Your library is in 2 places", "Every file is in at least two of them.", false), librarySafety(2, 5, emptyList(), "this PC"))
        assertEquals("quiet", librarySafety(1, 0, emptyList(), "this PC").tone)
    }
}
