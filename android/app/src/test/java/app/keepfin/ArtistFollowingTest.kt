package app.keepfin

import app.keepfin.core.music.ArtistReleaseItem
import app.keepfin.core.music.VideoArtist
import app.keepfin.core.music.VideoCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Followed artists through [FollowingHost]: a scripted channel search and release listing stand in for the network. */
class ArtistFollowingTest {
    private class Host : FollowingHost {
        var channel: () -> String? = { "UCpale0001" }
        var releases: () -> List<ArtistReleaseItem> = { emptyList() }
        var listingError: String? = null
        var metered = false
        val kept = ArrayList<String>()
        var keepFails = false
        val discovered = ArrayList<String>()
        override fun resolveChannel(name: String, key: String) = channel()
        override fun listReleases(channelId: String): List<ArtistReleaseItem> {
            discovered += channelId
            if (listingError != null) error(listingError!!)
            return releases()
        }
        override fun keep(url: String): Int { check(!keepFails) { "queue full" }; kept += url; return 3 }
        override fun metered() = metered
    }

    private class Memory : FollowingStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private var now = 1_800_000_000_000L
    private val host = Host()
    private val store = Memory()
    private val following = ArtistFollowing(host, store, clock = { now })

    private fun release(id: String, title: String = "Release $id", kind: String = "Album") = ArtistReleaseItem("https://music.youtube.com/playlist?list=OLAK5uy_$id", title, kind, "t-$id")

    @Test fun followingTheSameArtistTwiceIsOneFollowWhateverTheSpelling() {
        val first = following.follow("Pale Cassette")!!
        assertEquals(first.id, following.follow("pale  cassette feat. Oren Vale")!!.id)
        assertEquals(1, following.state.value.artists.size)
        assertNull(following.follow("   "))
    }

    @Test fun theFirstCheckRecordsTheCatalogueAndFeedsNothing() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a"), release("b")) }
        assertEquals(0, following.check(artist.id, manual = true))
        val state = following.state.value
        assertTrue(state.releases.isEmpty())
        assertEquals(2, state.artists.single().seen.size)
        assertEquals("UCpale0001", host.discovered.single())
        assertEquals("https://music.youtube.com/channel/UCpale0001", state.artists.single().url)
        assertNull(state.artists.single().error)
    }

    @Test fun laterChecksAddOnlyWhatIsNewDatedByWhenItWasFound() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        now += 3 * ReleaseDay
        host.releases = { listOf(release("a"), release("b", "Night Ferry", "Single")) }
        assertEquals(1, following.check(artist.id, manual = true))
        val found = following.state.value.releases.single()
        assertEquals("Night Ferry", found.title)
        assertEquals("Single", found.kind)
        assertEquals(now, found.foundAt)
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_b", found.url)
        // the same listing again adds nothing
        assertEquals(0, following.check(artist.id, manual = true))
        assertEquals(1, following.state.value.releases.size)
    }

    @Test fun releasesOlderThanSixtyDaysArePruned() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        now += ReleaseDay
        host.releases = { listOf(release("a"), release("b")) }
        following.check(artist.id, manual = true)
        now += 61 * ReleaseDay
        following.check(artist.id, manual = true)
        assertTrue(following.state.value.releases.isEmpty())
    }

    @Test fun unfollowRemovesTheArtistAndItsReleases() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        host.releases = { listOf(release("a"), release("b")) }
        following.check(artist.id, manual = true)
        following.unfollow(artist.id)
        assertTrue(following.state.value.artists.isEmpty())
        assertTrue(following.state.value.releases.isEmpty())
        assertFalse(following.any())
    }

    @Test fun keepDownloadsThroughTheHostThenMarksTheReleaseKeptAndAFailureLeavesItInTheFeed() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { emptyList() }
        following.check(artist.id, manual = true)
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        val id = following.state.value.releases.single().id
        host.keepFails = true
        try { following.keep(id); org.junit.Assert.fail("expected the failure") } catch (_: IllegalStateException) {}
        assertFalse(following.state.value.releases.single().kept)
        host.keepFails = false
        assertEquals(3, following.keep(id))
        assertEquals(listOf(id), host.kept)
        assertTrue(following.state.value.releases.single().kept)
    }

    @Test fun nothingDownloadsWithoutKeep() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { emptyList() }
        following.check(artist.id, manual = true)
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        assertTrue(host.kept.isEmpty())
    }

    @Test fun anArtistThatCannotBeFoundOrAFailedListingIsRecordedAsAnError() {
        val artist = following.follow("Nobody Known")!!
        host.channel = { null }
        assertEquals(0, following.check(artist.id, manual = true))
        assertTrue(following.state.value.artists.single().error!!.contains("couldn't find Nobody Known"))
        host.channel = { "UCx0000001" }
        host.listingError = "UCx0000001: This channel does not have a releases tab (yt-dlp)"
        following.check(artist.id, manual = true)
        assertEquals("Keepfin couldn't check this artist right now. It will try again later.", following.state.value.artists.single().error)
        assertFalse(following.state.value.artists.single().checkedOnce)
        host.listingError = null
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        assertNull(following.state.value.artists.single().error)
        assertTrue(following.state.value.artists.single().checkedOnce)
    }

    @Test fun theAutomaticCheckWaitsTwelveHoursAndSkipsAMeteredNetworkButCheckNowDoesNot() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a")) }
        following.check(null, manual = false)
        assertEquals(1, host.discovered.size)
        now += 6 * 3_600_000L
        following.check(null, manual = false)
        assertEquals("not due yet", 1, host.discovered.size)
        now += 7 * 3_600_000L
        host.metered = true
        following.check(null, manual = false)
        assertEquals("metered", 1, host.discovered.size)
        following.check(artist.id, manual = true)
        assertEquals(2, host.discovered.size)
    }

    @Test fun theStateSurvivesARestart() {
        val artist = following.follow("Pale Cassette")!!
        host.releases = { listOf(release("a")) }
        following.check(artist.id, manual = true)
        host.releases = { listOf(release("a"), release("b")) }
        following.check(artist.id, manual = true)
        val again = ArtistFollowing(host, store, clock = { now })
        assertEquals(following.state.value.copy(checking = false), again.state.value)
        assertTrue(again.state.value.isFollowing("Pale Cassette"))
    }

    @Test fun oneArtistThatFailsDoesNotStopTheOthers() {
        val first = following.follow("Pale Cassette")!!
        val second = following.follow("Glum Aleks")!!
        host.channel = { "UCany000001" }
        var calls = 0
        host.releases = { if (calls++ == 0) error("boom") else listOf(release("a")) }
        following.check(null, manual = true)
        val state = following.state.value
        assertEquals(ArtistFollowing.CHECK_FAILED, state.artists.first { it.id == first.id }.error)
        assertNull(state.artists.first { it.id == second.id }.error)
        assertTrue(state.artists.first { it.id == second.id }.checkedOnce)
    }

    @Test fun anArtistStoredByTheFirstReleaseCheckIsCheckedAfreshAndItsOldErrorIsDropped() {
        val old = """{"v":1,"artists":[{"id":"a1","name":"Ariana Grande","key":"ariana grande","url":"https://www.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA/releases","checkedOnce":false,"seen":[],"checkedAt":1799999999000,"error":"The download engine stopped: UC0076UMUgEng8HORUw_MYHA: This channel does not have a releases tab"}],"releases":[]}"""
        store.text = old
        val again = ArtistFollowing(host, store, clock = { now })
        val artist = again.state.value.artists.single()
        assertNull(artist.error)
        assertNull(artist.checkedAt)
        host.releases = { listOf(release("a")) }
        again.check(null, manual = false)
        assertEquals(listOf("UC0076UMUgEng8HORUw_MYHA"), host.discovered)
        assertEquals("https://music.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA", again.state.value.artists.single().url)
    }

    @Test fun theChannelIsOnlyTakenWhenTheNameAndTheChannelIdFit() {
        val rows = listOf(
            VideoCandidate("v1", "OMV", "Song", listOf(VideoArtist("Pale Cassettes", "UCwrong"), VideoArtist("Pale Cassette", "MPREb_album")), null, null),
            VideoCandidate("v2", "OMV", "Song", listOf(VideoArtist("Pale Cassette", "UCpale")), null, null),
        )
        assertEquals("UCpale", ArtistFollowing.pickChannel(rows, "pale cassette"))
        assertNull(ArtistFollowing.pickChannel(rows, "oren vale"))
    }

    private companion object { const val ReleaseDay = 86_400_000L }
}
