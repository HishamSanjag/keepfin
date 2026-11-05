package app.keepfin.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnavailableTest {
    @Test
    fun youTubeMarksPrivateAndDeletedVideosByTitle() {
        assertEquals(Unavailable.PRIVATE, Unavailable.reason("[Private video]", null, null, youtube = true))
        assertEquals(Unavailable.DELETED, Unavailable.reason(" [Deleted video] ", null, null, youtube = true))
    }

    @Test
    fun aYouTubeEntryWithOnlyItsIdIsGone() {
        // what a playlist lists for a video whose channel was terminated (checked against a real playlist, 2026-10-24)
        assertEquals(Unavailable.GONE, Unavailable.reason(null, null, null, youtube = true))
        assertEquals(Unavailable.GONE, Unavailable.reason("", "", 0.0, youtube = true))
    }

    @Test
    fun anythingWithATitleAChannelOrALengthIsFine() {
        assertNull(Unavailable.reason("Song", "Artist", 200.0, youtube = true))
        assertNull(Unavailable.reason(null, "Artist", null, youtube = true))
        assertNull(Unavailable.reason(null, null, 120.0, youtube = true))
        // other sites list entries without a title: not a sign of anything
        assertNull(Unavailable.reason(null, null, null, youtube = false))
    }
}
