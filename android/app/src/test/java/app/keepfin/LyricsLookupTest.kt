package app.keepfin

import app.keepfin.core.lyrics.LyricsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyrics rules themselves (requests, choosing, retry decisions) are the shared core's and run from fixtures/engine/lyrics. */
class LyricsLookupTest {
    private val exact = """{"id":9,"trackName":"Midnight City","artistName":"M83","duration":244,"instrumental":false,"plainLyrics":"a","syncedLyrics":"[00:01.00] a"}"""
    private val q = LyricsQuery("M83 - Midnight City (Official Video)", "M83", "Hurry Up", 244.0)

    @Test fun findUsesTheExactHitThenFallsBackToSearchOn404() {
        val calls = ArrayList<String>()
        assertEquals("[00:01.00] a", LyricsLookup.find(q) { url -> calls.add(url); if ("/api/get" in url) 200 to exact else null })
        assertEquals(1, calls.size)
        assertTrue("track_name=Midnight+City&" in calls[0])
        val search = """[{"id":1,"trackName":"Midnight City","artistName":"M83","duration":243.5,"instrumental":false,"plainLyrics":"b","syncedLyrics":null}]"""
        val seen = ArrayList<String>()
        assertEquals("b", LyricsLookup.find(q) { url -> seen.add(url); if ("/api/get" in url) 404 to "" else 200 to search })
        assertEquals(2, seen.size)
        assertTrue("/api/search" in seen[1])
    }

    @Test fun plainWordsFromTheExactMatchGiveWayToTimedLyricsFromTheSearch() {
        val plain = exact.replace(""""syncedLyrics":"[00:01.00] a"""", """"syncedLyrics":null""")
        assertEquals("[00:01.00] a", LyricsLookup.find(q) { url -> if ("/api/get" in url) 200 to plain else 200 to "[$exact]" })
        assertEquals("a", LyricsLookup.find(q) { url -> if ("/api/get" in url) 200 to plain else 200 to "[$plain]" })
    }

    @Test fun findNeverThrowsOnBrokenAnswers() {
        val q = LyricsQuery("Midnight City", "M83", duration = 244.0)
        assertNull(LyricsLookup.find(q, pause = {}) { 200 to "not json" })
        assertNull(LyricsLookup.find(q, pause = {}) { throw IllegalStateException("network down") })
        assertNull(LyricsLookup.find(q, pause = {}) { null })
        assertNull(LyricsLookup.find(LyricsQuery("", ""), pause = {}) { 200 to "{}" })
    }

    @Test fun aBusyServiceIsAskedAgainAfterTwoThenSixSeconds() {
        val calls = ArrayList<String>()
        val waits = ArrayList<Long>()
        val text = LyricsLookup.find(q, pause = { waits.add(it) }) { url ->
            calls.add(url)
            if (calls.size < 3) 503 to """{"message":"The server is busy, please retry in a moment"}""" else 200 to exact
        }
        assertEquals("[00:01.00] a", text)
        assertEquals(3, calls.size)
        assertTrue(calls.all { "/api/get" in it })
        assertEquals(listOf(2_000L, 6_000L), waits)
    }

    @Test fun aServiceThatStaysBusyGivesUpAfterThreeTriesPerRequest() {
        var asked = 0
        assertNull(LyricsLookup.find(q, pause = {}) { asked++; 503 to "" })
        assertEquals(6, asked)
    }

    @Test fun notFoundIsFinalAndNeverWaits() {
        val calls = ArrayList<String>()
        val waits = ArrayList<Long>()
        assertNull(LyricsLookup.find(q, pause = { waits.add(it) }) { url -> calls.add(url); 404 to "" })
        assertEquals(2, calls.size)
        assertTrue("/api/get" in calls[0] && "/api/search" in calls[1])
        assertTrue(waits.isEmpty())
    }

    @Test fun cancellingStopsAskingAndWaiting() {
        var cancelled = false
        var asked = 0
        val waits = ArrayList<Long>()
        assertNull(LyricsLookup.find(q, cancelled = { cancelled }, pause = { waits.add(it); cancelled = true }) { asked++; 503 to "" })
        assertEquals(1, asked)
        assertEquals(listOf(2_000L), waits)
    }
}
