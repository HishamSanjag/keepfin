package app.keepfin.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayFromTest {
    private fun h(name: String, online: Boolean = true, media: Boolean = true, near: Boolean = true, seen: Long? = null) = PlayHolder(name, online, media, near, seen)
    private fun line(list: List<PlayHolder>) = PlayFrom.choose(list).let { PlayFrom.text(it, list, "this PC") }

    @Test fun nobodyKnownSaysSoHonestly() {
        assertEquals("Not on this PC. No paired device is known to have it.", line(emptyList()))
        assertEquals(PlayHintKind.Unknown, PlayFrom.choose(emptyList()).kind)
    }

    @Test fun aConnectedHolderThatAllowsPlayingIsNamed() {
        assertEquals("On Desk PC", line(listOf(h("Desk PC"))))
    }

    @Test fun severalConnectedHoldersPreferThisNetwork() {
        assertEquals("On Laptop", line(listOf(h("Tailnet PC", near = false), h("Laptop"))))
        assertEquals("On First", line(listOf(h("First"), h("Second"))))
    }

    @Test fun aConnectedHolderBeatsAnAwayOne() {
        assertEquals("On Laptop", line(listOf(h("Desk PC", online = false, seen = 900), h("Laptop"))))
    }

    @Test fun aHolderThatDoesNotAllowPlayingIsSaidSo() {
        assertEquals("On Desk PC (playing from it is off)", line(listOf(h("Desk PC", media = false))))
        assertEquals("On Laptop", line(listOf(h("Desk PC", media = false), h("Laptop"))))
    }

    @Test fun awayHoldersNameTheMostRecentlySeen() {
        assertEquals("On B (not reachable)", line(listOf(h("A", online = false, seen = 100), h("B", online = false, seen = 900), h("C", online = false))))
    }
}
