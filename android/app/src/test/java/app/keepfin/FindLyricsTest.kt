package app.keepfin

import app.keepfin.core.lyrics.Lyrics
import app.keepfin.core.lyrics.LyricsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Find lyrics" on a song that has none: lyrics, "No lyrics found", or a service that could not be reached (dev-docs/plans/MUSIC-VIDEOS.md). */
class FindLyricsTest {
    private val song = LibraryItem(id = "s", space = "music", uri = "u", folder = "", kind = LibraryItemKind.AUDIO, title = "Midnight City", artist = "M83", album = "Hurry Up", duration = 244.0, size = 1, modifiedAt = 0, addedAt = 0)

    @Test fun foundLyricsAreKeptAndSaidSo() {
        var kept: String? = null
        var asked: LyricsQuery? = null
        val outcome = FindLyrics.run(song, search = { asked = it; LyricsLookup.Answer("[00:01.00] a", false) }, save = { kept = it })
        assertEquals("found", outcome)
        assertEquals("[00:01.00] a", kept)
        assertEquals(LyricsQuery("Midnight City", "M83", "Hurry Up", 244.0), asked)
        assertEquals("Lyrics added.", Lyrics.outcomeWords(outcome))
    }

    @Test fun noLyricsAreNothingKeptAndSaidSoPlainly() {
        var saved = false
        val outcome = FindLyrics.run(song, search = { LyricsLookup.Answer(null, false) }, save = { saved = true })
        assertEquals("none", outcome)
        assertEquals(false, saved)
        assertEquals("No lyrics found", Lyrics.outcomeWords(outcome))
    }

    @Test fun aServiceThatCouldNotBeReachedReadsDifferentlyFromNoLyrics() {
        val outcome = FindLyrics.run(song, search = { LyricsLookup.Answer(null, true) }, save = { error("nothing to keep") })
        assertEquals("busy", outcome)
        assertEquals("Could not reach the lyrics service. Try again later.", Lyrics.outcomeWords(outcome))
    }

    @Test fun aSongWithoutAnArtistIsNotSearched() {
        val outcome = FindLyrics.run(song.copy(artist = null), search = { error("no artist, no search") }, save = { error("nothing to keep") })
        assertEquals("none", outcome)
    }

    @Test fun theLookupSaysBusyOnlyWhenNoRealAnswerCame() {
        val q = LyricsQuery("Midnight City", "M83", "Hurry Up", 244.0)
        // still busy after the retries: not "no lyrics"
        assertEquals(true, LyricsLookup.search(q, pause = {}) { 503 to "" }.busy)
        assertEquals(true, LyricsLookup.search(q, pause = {}) { null }.busy)
        // a real "not found" is final and not busy
        val none = LyricsLookup.search(q, pause = {}) { 404 to "" }
        assertNull(none.text)
        assertEquals(false, none.busy)
        // the exact lookup was busy but the search found some: it is found
        var calls = 0
        val search = """[{"id":1,"trackName":"Midnight City","artistName":"M83","duration":243.5,"instrumental":false,"plainLyrics":"b","syncedLyrics":null}]"""
        val found = LyricsLookup.search(q, pause = {}) { url -> calls++; if ("/api/get" in url) 503 to "" else 200 to search }
        assertEquals("b", found.text)
        assertTrue(calls > 1)
    }
}
