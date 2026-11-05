package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following is opt-in ("Keep in sync" at download time), and checks say what they found in the app. */
class OptInFollowTest {
    private fun job(id: String, status: JobStatus, percent: Float, createdAt: Long, collection: String? = "c1") = Job(
        id = id, url = "https://music.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = null, duration = null, provider = null, label = "",
        kind = MediaKind.AUDIO, status = status, percent = percent, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = createdAt, completedAt = null, attempt = 1,
        collectionId = collection,
    )

    @Test fun playlistsAndChannelsDoNotFollowByDefault() {
        assertEquals(FollowMode.OFF, CollectionLogic.defaultFollow(CollectionKind.PLAYLIST, false)!!.mode)
        assertEquals(FollowMode.OFF, CollectionLogic.defaultFollow(CollectionKind.CHANNEL, false)!!.mode)
        assertEquals(FollowMode.OFF, CollectionLogic.defaultFollow(CollectionKind.ALBUM, false)!!.mode)
        assertNull(CollectionLogic.defaultFollow(CollectionKind.OWN, false))
        assertNull(CollectionLogic.defaultFollow(CollectionKind.PLAYLIST, infinite = true))
        // Nothing is due while every collection is off, so the scheduler has nothing to run.
        val c = LibraryCollection("c1", "Gym Mix", CollectionKind.PLAYLIST, "music", source = CollectionSource("https://www.youtube.com/playlist?list=PLabc", "YouTube"),
            follow = CollectionLogic.defaultFollow(CollectionKind.PLAYLIST, false), createdAt = 1, updatedAt = 1)
        assertTrue(CollectionLogic.due(listOf(c), now = 10_000_000_000L, metered = false).isEmpty())
    }

    @Test fun downloadingWithKeepInSyncCreatesAnAutomaticFollowWithThoseOptions() {
        val sync = SyncRequest(intervalHours = 24, wifiOnly = false)
        val created = CollectionLogic.followFor(CollectionKind.PLAYLIST, false, sync)!!
        assertEquals(FollowMode.AUTO, created.mode); assertEquals(24, created.intervalHours); assertFalse(created.wifiOnly)
        assertEquals(FollowMode.AUTO, CollectionLogic.followFor(CollectionKind.CHANNEL, false, SyncRequest())!!.mode)
        // Without it nothing changes: the default is off, an existing mode is kept.
        assertEquals(FollowMode.OFF, CollectionLogic.followFor(CollectionKind.PLAYLIST, false, null)!!.mode)
        assertEquals(FollowMode.MANUAL, CollectionLogic.followFor(CollectionKind.PLAYLIST, false, null, Follow(FollowMode.MANUAL))!!.mode)
        // Downloading into an existing collection with it on turns following on and takes the options.
        val updated = CollectionLogic.followFor(CollectionKind.PLAYLIST, false, SyncRequest(168, true), Follow(FollowMode.OFF, intervalHours = 6, confirmAbove = 50))!!
        assertEquals(FollowMode.AUTO, updated.mode); assertEquals(168, updated.intervalHours); assertTrue(updated.wifiOnly); assertEquals(50, updated.confirmAbove)
        // An interval that is not offered is ignored: the follow stays off.
        assertEquals(FollowMode.OFF, CollectionLogic.followFor(CollectionKind.PLAYLIST, false, SyncRequest(5))!!.mode)
        // Albums, own lists and radio mixes never follow.
        assertEquals(FollowMode.OFF, CollectionLogic.followFor(CollectionKind.ALBUM, false, sync)!!.mode)
        assertNull(CollectionLogic.followFor(CollectionKind.OWN, false, sync))
        assertNull(CollectionLogic.followFor(CollectionKind.PLAYLIST, true, sync))
    }

    @Test fun keepInSyncIsOfferedForPlaylistsAndChannelsOnly() {
        assertTrue(CollectionLogic.syncOffered("https://www.youtube.com/playlist?list=PLabc123"))
        assertTrue(CollectionLogic.syncOffered("https://www.youtube.com/@blender"))
        assertFalse(CollectionLogic.syncOffered("https://music.youtube.com/playlist?list=OLAKabc"))
        assertFalse(CollectionLogic.syncOffered("https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk"))
        assertFalse(CollectionLogic.syncOffered("https://www.youtube.com/playlist?list=PLabc", groupKind = "mix"))
    }

    @Test fun checkNowAlwaysAnswersAndBackgroundChecksOnlyWhenTheyFindSomething() {
        fun report(found: Int = 0, held: Int = 0, error: String? = null, manual: Boolean = true, noun: String = "song") = FollowReport("c1", "Gym Mix", found, held, error, manual, noun)
        val found = CollectionLogic.checkNotice(report(found = 3))!!
        assertEquals("Found 3 new songs in Gym Mix · Downloading", found.text); assertEquals(NoticeAction.SHOW, found.action)
        assertEquals("Found 1 new song in Gym Mix · Downloading", CollectionLogic.checkNotice(report(found = 1))!!.text)
        assertEquals("Found 2 new items in Gym Mix · Downloading", CollectionLogic.checkNotice(report(found = 2, noun = "item"))!!.text)
        assertEquals("No new songs in Gym Mix", CollectionLogic.checkNotice(report())!!.text)
        assertNull(CollectionLogic.checkNotice(report())!!.action)
        val held = CollectionLogic.checkNotice(report(held = 28))!!
        assertEquals("28 new in Gym Mix · Review", held.text); assertEquals(NoticeAction.REVIEW, held.action)
        assertEquals("Couldn't check Gym Mix. No connection.", CollectionLogic.checkNotice(report(error = "No connection."))!!.text)
        // Background: quiet unless there is news.
        assertNull(CollectionLogic.checkNotice(report(manual = false)))
        assertNull(CollectionLogic.checkNotice(report(manual = false, error = "No connection.")))
        assertEquals(NoticeAction.SHOW, CollectionLogic.checkNotice(report(found = 2, manual = false))!!.action)
    }

    @Test fun progressCountsTheRunningBatchWithRealValuesOnly() {
        val jobs = listOf(
            job("old", JobStatus.COMPLETED, 100f, createdAt = 1_000),
            job("a", JobStatus.COMPLETED, 100f, createdAt = 500_000), job("b", JobStatus.DOWNLOADING, 50f, createdAt = 500_001), job("c", JobStatus.QUEUED, 0f, createdAt = 500_002),
            job("other", JobStatus.DOWNLOADING, 10f, createdAt = 500_003, collection = "c2"),
        )
        val p = CollectionLogic.downloadProgress(jobs, "c1")!!
        assertEquals("Downloading 1 of 3 new", p.label)
        assertEquals(1f / 2f, p.fraction, 0.0001f)   // (100 + 50 + 0) / 300
        assertEquals(listOf("b", "c"), p.active.map { it.id })
        // Nothing active: no strip.
        assertNull(CollectionLogic.downloadProgress(jobs.filter { it.status == JobStatus.COMPLETED }, "c1"))
        assertNull(CollectionLogic.downloadProgress(jobs, "missing"))
    }
}
