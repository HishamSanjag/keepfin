package app.keepfin.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySourceTest {
    private fun peer(name: String, reachable: Boolean = true, offers: Boolean = true, seen: Long? = null) = PlayCandidate(name, reachable, offers, seen)

    @Test fun localAlwaysWins() {
        assertEquals(PlayChoice.Local, PlaySource.choose(true, listOf(peer("PC"))))
        assertEquals(PlayChoice.Local, PlaySource.choose(true, emptyList()))
    }

    @Test fun aReachableDeviceThatOffersMediaPlaysIt() {
        assertEquals(PlayChoice.Peer("Tablet"), PlaySource.choose(false, listOf(peer("PC", reachable = false, seen = 5), peer("Tablet"))))
    }

    @Test fun aReachableHolderThatDoesNotOfferMediaIsNamedAsNotAllowing() {
        assertEquals(PlayChoice.NotAllowed("PC"), PlaySource.choose(false, listOf(peer("PC", offers = false))))
        assertEquals("On PC (playing from it is off)", PlaySource.hint(PlayChoice.NotAllowed("PC")))
    }

    @Test fun severalConnectedHoldersPreferThisNetwork() {
        val far = PlayCandidate("Tailnet PC", true, true, null, near = false)
        assertEquals(PlayChoice.Peer("Laptop"), PlaySource.choose(false, listOf(far, peer("Laptop"))))
    }

    @Test fun anUnreachableDeviceIsNamedWithTheMostRecentLastSeen() {
        val choice = PlaySource.choose(false, listOf(peer("Old", reachable = false, seen = 100), peer("PC", reachable = false, seen = 900), peer("Never", reachable = false)))
        assertEquals(PlayChoice.Unreachable("PC", 900), choice)
    }

    @Test fun noDevicesMeansNone() {
        assertEquals(PlayChoice.None, PlaySource.choose(false, emptyList()))
    }

    @Test fun hintsAndMessages() {
        assertNull(PlaySource.hint(PlayChoice.Local))
        assertEquals("On PC", PlaySource.hint(PlayChoice.Peer("PC")))
        assertEquals("On PC (not reachable)", PlaySource.hint(PlayChoice.Unreachable("PC", 1)))
        assertEquals("Not on this phone. No paired device is known to have it.", PlaySource.hint(PlayChoice.None))
        val now = 10_000_000L
        assertNull(PlaySource.blocked(PlayChoice.Peer("PC"), now))
        assertEquals("PC is not reachable. It was last seen 2 hours ago.", PlaySource.blocked(PlayChoice.Unreachable("PC", now - 2 * 3_600_000L), now))
        assertEquals("PC is not reachable.", PlaySource.blocked(PlayChoice.Unreachable("PC", null), now))
        assertNotNull(PlaySource.blocked(PlayChoice.None, now))
    }

    @Test fun agoReadsPlainly() {
        assertEquals("just now", PlaySource.ago(1000, 1000 + 30_000))
        assertEquals("1 minute ago", PlaySource.ago(0, 60_000))
        assertEquals("5 minutes ago", PlaySource.ago(0, 5 * 60_000))
        assertEquals("1 hour ago", PlaySource.ago(0, 3_600_000))
        assertEquals("3 days ago", PlaySource.ago(0, 3 * 86_400_000L))
    }

    @Test fun contentAddressesCarryTheHashAndName() {
        val hex = "a".repeat(64)
        assertEquals("keepfin-peer://content/$hex?name=Long+Song", PeerPlayback.contentUri("sha256:$hex", "Long Song"))
        assertNull(PeerPlayback.contentUri("sha256:nothex", "x"))
    }
}
