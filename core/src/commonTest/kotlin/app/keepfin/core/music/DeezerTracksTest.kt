package app.keepfin.core.music

import kotlin.test.Test
import kotlin.test.assertEquals

class DeezerTracksTest {
    private val track = """{"id":3135556,"title":"Harder, Better, Faster, Stronger","isrc":"GBDUW0000059","duration":226,"artist":{"name":"Daft Punk"}}"""

    @Test
    fun aTrackResponseGivesItsCode() {
        assertEquals(mapOf("3135556" to "GBDUW0000059"), DeezerTracks.isrcs(track))
    }

    @Test
    fun aPageOrPlaylistGivesEachTracksCodeAndSkipsMissingOnes() {
        val page = """{"data":[{"id":1,"isrc":"gb-duw-00-00053"},{"id":2},{"id":3,"isrc":""},{"id":"4","isrc":"ES6601404373"}],"total":4}"""
        assertEquals(mapOf("1" to "GBDUW0000053", "4" to "ES6601404373"), DeezerTracks.isrcs(page))
        val playlist = """{"id":908622995,"title":"List","tracks":{"data":[{"id":7,"isrc":"GBUM71505902"}]}}"""
        assertEquals(mapOf("7" to "GBUM71505902"), DeezerTracks.isrcs(playlist))
    }

    @Test
    fun anAlbumWithoutCodesAndUnusableTextGiveNothing() {
        assertEquals(emptyMap(), DeezerTracks.isrcs("""{"id":302127,"title":"Discovery","tracks":{"data":[{"id":3135553,"title":"One More Time"}]}}"""))
        assertEquals(emptyMap(), DeezerTracks.isrcs("""{"id":1,"isrc":"not a code"}"""))
        assertEquals(emptyMap(), DeezerTracks.isrcs("<html>"))
        assertEquals(emptyMap(), DeezerTracks.isrcs("""{"error":{"message":"no data"}}"""))
    }
}
