package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Test

/** Undo of "Remove from collection": the songs go back where they were, and what changed since stays. */
class RestoreOrderTest {
    private fun restore(before: String, now: String, removed: String) =
        CollectionLogic.restoreOrder(before.toList().map(Char::toString), now.toList().map(Char::toString), removed.toList().map(Char::toString).toSet()).joinToString("")

    @Test fun oneSongGoesBackToItsPlace() = assertEquals("abcde", restore("abcde", "abde", "c"))

    @Test fun theFirstAndTheLastGoBack() = assertEquals("abcde", restore("abcde", "bcd", "ae"))

    @Test fun neighboursRemovedTogetherKeepTheirOrder() = assertEquals("abcde", restore("abcde", "ae", "bcd"))

    @Test fun everythingRemovedComesBackInOrder() = assertEquals("abc", restore("abc", "", "abc"))

    @Test fun aReorderMadeSinceStays() =
        // c was removed from abcde, then e was dragged to the top: c goes back in front of d, where it was
        assertEquals("eabcd", restore("abcde", "eabd", "c"))

    @Test fun aSongAddedSinceStays() = assertEquals("abcdx", restore("abcd", "abdx", "c"))

    @Test fun aSongPutBackAlreadyIsNotDoubled() = assertEquals("abc", restore("abc", "abc", "b"))
}
