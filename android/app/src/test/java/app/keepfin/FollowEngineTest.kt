package app.keepfin

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The follow check through [FollowHost]: an in-memory library and a scripted source stand in for the engine. */
class FollowEngineTest {
    private class Enqueued(val entries: List<PlaylistEntry>, val group: JobGroup, val space: String, val collectionId: String)

    private class Host : FollowHost {
        val collections = LinkedHashMap<String, LibraryCollection>()
        var source: () -> PlaylistDiscovery = { error("no source") }
        var metered = false
        val enqueued = ArrayList<Enqueued>()
        val reviews = ArrayList<Int>()
        override val settings = MutableStateFlow(EngineSettings())
        override val library = object : FollowEngine.FollowLibrary {
            override fun collectionsNow() = collections.values.toList()
            override fun collection(id: String) = collections[id]
            override fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection) = collections[id]?.let(transform)?.also { collections[id] = it }
            override fun markFollowJobs(jobIds: Collection<String>) {}
            override fun sourceKeysOf(itemId: String) = emptySet<String>()
            override fun report(report: FollowReport) {}
        }
        override fun discover(url: String, session: String, start: Int, end: Int) = source()
        override fun lastProfile(collectionId: String): Pair<FormatRequest, DownloadOptions>? = null
        override fun enqueue(session: String, entries: List<PlaylistEntry>, format: FormatRequest?, options: DownloadOptions, group: JobGroup, space: String, collectionId: String): List<String> {
            enqueued += Enqueued(entries, group, space, collectionId)
            return entries.map { "job-${it.position}" }
        }
        override fun dropSession(session: String) {}
        override fun heldForReview(c: LibraryCollection, follow: Follow, count: Int, noun: String) { reviews += count }
        override fun found(c: LibraryCollection, follow: Follow, ids: List<String>, noun: String) {}
        override fun metered() = metered
    }

    private val url = "https://www.youtube.com/playlist?list=PLabc"
    private val host = Host()
    private val engine = FollowEngine(host, clock = { NOW })

    private fun entry(id: String, position: Int) = PlaylistEntry("https://www.youtube.com/watch?v=$id", id, thumbnail = null, uploader = null, duration = null, position = position)
    private fun discovery(vararg entries: PlaylistEntry, state: String = "completed", error: String? = null) =
        PlaylistDiscovery("d", url, "Gym Mix", entries.toList(), state, error, 200, false)

    private fun collection(
        id: String = "c1", kind: CollectionKind = CollectionKind.PLAYLIST, seen: List<String> = emptyList(), itemIds: List<String> = emptyList(),
        follow: Follow = Follow(mode = FollowMode.AUTO, wifiOnly = false),
    ) = LibraryCollection(id, "Gym Mix", kind, "downloads", itemIds, source = CollectionSource(url, "youtube"), follow = follow, seen = seen, createdAt = 0, updatedAt = 0)
        .also { host.collections[id] = it }

    @Test fun aDeletedItemIsNeverDownloadedAgainByACheck() {
        val a = entry("aaaaaaaaaaa", 1)
        // the item was downloaded, then deleted: it is no longer in the collection but its key stays in `seen`
        collection(seen = listOf(CollectionLogic.itemKey(a.url)), itemIds = emptyList())
        host.source = { discovery(a) }
        val outcome = engine.check("c1", review = false, manual = true)
        assertEquals(1, outcome.checked)
        assertTrue("nothing may be queued", outcome.queued.isEmpty())
        assertTrue(host.enqueued.isEmpty())
        val after = host.collections.getValue("c1")
        assertEquals(NOW, after.follow!!.lastCheckedAt)
        assertNull(after.follow!!.lastError)
        assertEquals(listOf(CollectionLogic.itemKey(a.url)), after.seen)
    }

    @Test fun newItemsAboveTheLimitWaitForReviewThenDownloadInSourceOrder() {
        val first = entry("aaaaaaaaaaa", 1)
        val second = entry("bbbbbbbbbbb", 2)
        collection(follow = Follow(mode = FollowMode.AUTO, wifiOnly = false, confirmAbove = 0))
        host.source = { discovery(second, first) }
        val held = engine.check("c1", review = false, manual = true)
        assertEquals(2, held.held)
        assertTrue(held.queued.isEmpty())
        assertTrue(host.enqueued.isEmpty())
        assertEquals(listOf(2), host.reviews)
        assertEquals(2, host.collections.getValue("c1").follow!!.pending!!.count)
        assertTrue("a held check does not mark items as seen", host.collections.getValue("c1").seen.isEmpty())

        val reviewed = engine.check("c1", review = true, manual = true)
        assertEquals(listOf("job-1", "job-2"), reviewed.queued)
        val queued = host.enqueued.single()
        assertEquals("downloaded in the order of the source", listOf(1, 2), queued.entries.map { it.position })
        assertEquals("c1", queued.collectionId)
        assertEquals("downloads", queued.space)
        val c = host.collections.getValue("c1")
        assertNull(c.follow!!.pending)
        assertEquals(listOf(first, second).map { CollectionLogic.itemKey(it.url) }.toSet(), c.seen.toSet())
    }

    @Test fun aFailingSourceRecordsAFriendlyErrorAndWaitsForTheNextInterval() {
        val a = entry("aaaaaaaaaaa", 1)
        collection(follow = Follow(mode = FollowMode.AUTO, wifiOnly = false, lastCheckedAt = null))
        host.source = { throw RuntimeException("ERROR: yt-dlp HTTP Error 404: Not Found") }
        val outcome = engine.check("c1", review = false, manual = true)
        assertEquals(1, outcome.errors)
        val follow = host.collections.getValue("c1").follow!!
        val message = checkNotNull(follow.lastError) { "lastError missing" }
        assertFalse(message, Regex("yt-?dlp|ffmpeg|gallery-dl|aria2", RegexOption.IGNORE_CASE).containsMatchIn(message))
        assertNotNull("an error still counts as a check, so it is not retried before the next interval", follow.lastCheckedAt)
        assertTrue(CollectionLogic.due(host.collectionsNowList(), NOW + 60_000, metered = false).isEmpty())
        // a source that answers empty and failed gets the friendly default
        host.source = { discovery(state = "failed") }
        assertEquals(1, engine.check("c1", review = false, manual = true).errors)
        assertTrue(host.collections.getValue("c1").follow!!.lastError!!.isNotBlank())
        // the next good check clears it
        host.source = { discovery(a) }
        engine.check("c1", review = false, manual = true)
        assertNull(host.collections.getValue("c1").follow!!.lastError)
    }

    @Test fun wifiOnlyCollectionsAreSkippedByTheScheduleOnAMeteredNetwork() {
        collection(follow = Follow(mode = FollowMode.AUTO, wifiOnly = true, lastCheckedAt = null))
        host.source = { discovery(entry("aaaaaaaaaaa", 1)) }
        host.metered = true
        assertEquals(0, engine.checkDue().checked)
        assertNull(host.collections.getValue("c1").follow!!.lastCheckedAt)
        // "Check now" is the user asking: it ignores the Wi-Fi-only setting
        assertEquals(1, engine.check("c1", review = false, manual = true).checked)
        // an unmetered network is checked by the schedule
        host.mutateLastChecked("c1", null)
        host.metered = false
        assertEquals(1, engine.checkDue().checked)
        // ...and so is a collection that may use mobile data
        host.mutateLastChecked("c1", null)
        host.collections["c1"] = host.collections.getValue("c1").let { it.copy(follow = it.follow!!.copy(wifiOnly = false)) }
        host.metered = true
        assertEquals(1, engine.checkDue().checked)
    }

    @Test fun ownListsAndMixesCannotBeFollowed() {
        collection(id = "own", kind = CollectionKind.OWN)
        assertEquals(0, engine.check("own", review = false, manual = true).checked)
        host.collections["mix"] = host.collections.getValue("own").copy(id = "mix", kind = CollectionKind.PLAYLIST, source = CollectionSource("https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa", "youtube"))
        assertEquals("a radio mix has no end to follow", 0, engine.check("mix", review = false, manual = true).checked)
        assertTrue(host.enqueued.isEmpty())
    }

    private fun Host.collectionsNowList() = collections.values.toList()
    private fun Host.mutateLastChecked(id: String, at: Long?) {
        collections[id] = collections.getValue(id).let { it.copy(follow = it.follow!!.copy(lastCheckedAt = at)) }
    }

    private companion object { const val NOW = 1_700_000_000_000L }
}
