package app.keepfin.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicVideosTest {
    @Test
    fun viewsAreReadExactly() {
        assertEquals(3_200_000_000L, MusicVideos.viewCount("3.2B views"))
        assertEquals(882_000_000L, MusicVideos.viewCount("882M views"))
        assertEquals(18_000L, MusicVideos.viewCount("18K views"))
        assertEquals(9_500L, MusicVideos.viewCount("9.5K views"))
        assertEquals(3_250L, MusicVideos.viewCount("3.25K views"))
        assertEquals(56L, MusicVideos.viewCount("56 views"))
        assertEquals(1L, MusicVideos.viewCount("1 view"))
    }

    @Test
    fun anythingElseIsNoViewCount() {
        for (text in listOf("Adele", "6:07", "views", "3.2B", "3.2501K views", "3. views", "1.5 views", "x1M views", "")) assertNull(MusicVideos.viewCount(text), text)
    }

    @Test
    fun titlesLoseDecorationsAndFeaturingButKeepWhatMakesAnotherRecording() {
        assertEquals("Hello", MusicVideos.cleanTitle("Hello (Official Music Video)", listOf("Adele")))
        assertEquals("Night Swim", MusicVideos.cleanTitle("Mira Halden - Night Swim [4K] (feat. Someone)", listOf("Mira Halden")))
        assertEquals("Night Swim (Live)", MusicVideos.cleanTitle("Night Swim (Live) (Official Video)", listOf("Mira Halden")))
        // a dash that does not follow the artist stays: it is part of the title
        assertEquals("Stairway To Heaven - Remaster", MusicVideos.cleanTitle("Stairway To Heaven - Remaster", listOf("Led Zeppelin")))
        assertEquals("Stairway To Heaven - Remaster", MusicVideos.cleanTitle("Led Zeppelin - Stairway To Heaven - Remaster (Official Video)", listOf("Led Zeppelin")))
        // a credit naming another recording stays
        assertEquals("Night Swim (feat. Someone - Live Remix)", MusicVideos.cleanTitle("Night Swim (feat. Someone - Live Remix)", listOf("Mira Halden")))
    }

    @Test
    fun countsBeyondWhatJavaScriptHoldsExactlyAreRefused() {
        assertEquals(9_007_199_254_740_991L, MusicVideos.viewCount("9007199254740991 views"))
        assertNull(MusicVideos.viewCount("9007199254740993 views"))
        assertNull(MusicVideos.viewCount("9223372036854775808 views"))
        assertNull(MusicVideos.viewCount("9999999999999B views"))
    }

    @Test
    fun theQueryIsTheFirstArtistAndTheTitle() {
        assertEquals("Bob Marley & The Wailers Is This Love", MusicVideos.query(listOf("Bob Marley & The Wailers", "Other"), " Is This Love "))
    }
}
