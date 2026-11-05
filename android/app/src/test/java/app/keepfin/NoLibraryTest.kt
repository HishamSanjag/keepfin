package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoLibraryTest {
    @Test fun readsAreEmpty() {
        assertTrue(NoLibrary.library.value.collections.isEmpty())
        assertNull(NoLibrary.item("x"))
        assertNull(NoLibrary.collection("x"))
        assertTrue(NoLibrary.existing("music", emptyList()).isEmpty())
        assertEquals(0, NoLibrary.browse.count(ItemQuery()))
    }

    @Test fun aChangeSaysWhatToDo() {
        try {
            NoLibrary.setFavorite("x", true)
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("Set up your Keepfin folder first.", e.message)
        }
    }
}
