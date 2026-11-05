package app.keepfin

import app.keepfin.ui.jobs.needsAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsAndRemovedTest {
    private fun job(id: String, status: JobStatus = JobStatus.COMPLETED, group: JobGroup? = null, createdAt: Long = 0, removed: Boolean = false, saveError: String? = null) = Job(
        id = id, url = "https://music.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = null, duration = null, provider = null, label = "",
        kind = MediaKind.AUDIO, status = status, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = saveError, createdAt = createdAt, completedAt = null, attempt = 1,
        group = group, filesRemoved = removed,
    )

    private fun item(id: String, jobId: String?, space: String = "music", folder: String = "My Mix") = LibraryItem(
        id = id, space = space, uri = id, folder = folder, kind = LibraryItemKind.AUDIO, title = "Song $id", size = 1, modifiedAt = 1, addedAt = 1, jobId = jobId,
    )

    private val playlist = JobGroup("g1", "My Mix", "https://music.youtube.com/playlist?list=xyz", kind = "playlist")

    private fun migrate(items: List<LibraryItem>, jobs: List<Job>) =
        CollectionLogic.fromJobGroups(jobs, items.mapNotNull { i -> i.jobId?.let { it to i } }.toMap(), emptyList(), 1_000L)

    @Test fun aDownloadedPlaylistBecomesACollectionInPlaylistOrder() {
        // Jobs are created playlist-ordered (createdAt + index); library items arrive in "recently added" order, not playlist order.
        val jobs = listOf(job("c", group = playlist, createdAt = 12), job("a", group = playlist, createdAt = 10), job("b", group = playlist, createdAt = 11), job("solo"))
        val items = listOf(item("i-b", "b"), item("i-c", "c"), item("i-a", "a"), item("i-solo", "solo"), item("i-import", null))
        val collections = migrate(items, jobs)
        assertEquals(1, collections.size)
        val c = collections.single()
        assertEquals("g1", c.id)
        assertEquals("My Mix", c.name)
        assertEquals(CollectionKind.PLAYLIST, c.kind)
        assertEquals(listOf("i-a", "i-b", "i-c"), c.itemIds)
        assertEquals("music", c.space)
        assertEquals("https://www.youtube.com/playlist?list=xyz", CollectionLogic.sourceKey(c.source!!.url))
        // Every job of the group counts as seen, so a later follow never re-downloads them.
        assertEquals(jobs.filter { it.group != null }.map { CollectionLogic.itemKey(it.url) }.toSet(), c.seen.toSet())
        // The user's deselected items from back then are unknown, so migrated collections do not follow by themselves.
        assertEquals(FollowMode.OFF, c.follow?.mode)
    }

    @Test fun songsThatWereDeletedDropOutAndAnEmptyCollectionDisappears() {
        val jobs = listOf(job("a", group = playlist, createdAt = 1), job("b", group = playlist, createdAt = 2))
        assertEquals(listOf("i-b"), migrate(listOf(item("i-b", "b")), jobs).single().itemIds)
        assertTrue(migrate(emptyList(), jobs).isEmpty())
    }

    @Test fun theSameSourceInTwoGroupsMergesIntoOneCollection() {
        val other = playlist.copy(id = "g2")
        val jobs = listOf(job("a", group = playlist, createdAt = 1), job("b", group = other, createdAt = 2))
        val result = migrate(listOf(item("i-a", "a", space = "music"), item("i-b", "b", space = "music")), jobs)
        assertEquals(1, result.size)
        assertEquals(listOf("i-a", "i-b"), result.single().itemIds)
    }

    @Test fun anExistingCollectionIsNeverOverwrittenByTheMigration() {
        val jobs = listOf(job("a", group = playlist, createdAt = 1))
        val mine = LibraryCollection("g1", "Renamed", CollectionKind.PLAYLIST, "music", listOf("i-a"), createdAt = 1, updatedAt = 1)
        val result = CollectionLogic.fromJobGroups(jobs, mapOf("a" to item("i-a", "a")), listOf(mine), 5L)
        assertEquals(listOf(mine), result)
    }

    @Test fun groupSummaryReportsRemovedFilesWithoutCountingThemAsSaved() {
        val all = (1..5).map { job("r$it", group = playlist, removed = true) }
        val summaryAll = GroupSummary.of(all)
        assertEquals(0, summaryAll.saved)
        assertEquals(5, summaryAll.removed)
        assertEquals("All 5 files removed", summaryAll.finalText())
        val partial = GroupSummary.of(all.take(2) + (3..5).map { job("k$it", group = playlist) })
        assertEquals(3, partial.saved)
        assertEquals("2 of 5 files removed", partial.removedText())
        assertEquals("File removed", GroupSummary.of(listOf(job("one", removed = true))).removedText())
    }

    @Test fun aRemovedJobRoundTripsThroughTheHistoryFile() {
        val record = JobRecord(job("a", removed = true), FormatRequest.Video(), DownloadOptions())
        val decoded = JobCodec.decodeFile(JobCodec.encodeFile(listOf(record)))
        assertTrue(decoded.single().job.filesRemoved)
    }

    @Test fun removingFailedMembersUpdatesTheGroupSummaryAndNeverTouchesTheSeenSet() {
        val g = JobGroup("g", "Mix", "https://music.youtube.com/playlist?list=x")
        val saved = (1..29).map { job("ok$it", group = g, createdAt = it.toLong()) }
        val failed = listOf(job("bad1", JobStatus.FAILED, g, 100), job("bad2", JobStatus.FAILED, g, 101))
        fun text(children: List<Job>) = app.keepfin.ui.jobs.groupSummaryText(children, GroupSummary.of(children))
        assertEquals("29 saved · 2 failed", text(saved + failed))
        assertEquals("29 saved · 1 failed", text(saved + failed.drop(1)))
        assertEquals("All saved: counted as done, no failures listed", "29 of 29 done", text(saved))
        val waiting = job("w", JobStatus.QUEUED, g, 102).copy(retryAt = 5_000L)
        assertTrue(waiting.needsAttention())
        assertTrue(failed.all { it.needsAttention() } && job("c", JobStatus.CANCELLED, g).needsAttention() && job("u").copy(saveError = "x").needsAttention())
        // A removed item's key stays in the collection's seen list (jobs and collections are separate stores), so a follow check never re-downloads it.
        val seen = listOf("https://music.youtube.com/watch?v=bad1", "https://music.youtube.com/watch?v=ok1")
        assertTrue(CollectionLogic.newKeys(listOf("https://music.youtube.com/watch?v=bad1", "https://music.youtube.com/watch?v=ok1"), seen).isEmpty())
    }
}
