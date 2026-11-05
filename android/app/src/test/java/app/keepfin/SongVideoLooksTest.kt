package app.keepfin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The look for a collection song's official video, once it has completed (dev-docs/plans/MUSIC-VIDEOS.md, phase 5). */
class SongVideoLooksTest {
    private val video = MusicVideoMatch("dQw4w9WgXcQ", "Agent 327 (Official Video)", "Blender Studio", 1L, 215.0)
    private val now = Executor { it.run() }

    private fun record(id: String = "song", status: JobStatus = JobStatus.COMPLETED, findVideo: Boolean = true): JobRecord {
        val job = Job(
            id = id, url = "https://music.youtube.com/watch?v=aaaaaaaaaaa", title = id, thumbnail = null, uploader = null, duration = null, provider = null, label = "", kind = MediaKind.AUDIO,
            status = status, percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = null, error = null,
            file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 1L, completedAt = null, attempt = 1,
        )
        return JobRecord(job, FormatRequest.Audio("mp3", "320"), DownloadOptions(), findVideo = findVideo)
    }

    private class Run(
        val executor: Executor, val hasVideo: Boolean = false, val linked: Boolean = false, val known: Boolean = true,
        val lookup: () -> MusicVideoLookup,
    ) {
        val looks = AtomicInteger()
        val queued = java.util.concurrent.CopyOnWriteArrayList<Pair<String, MusicVideoMatch>>()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val done = java.util.concurrent.CopyOnWriteArrayList<String>()
        val held = AtomicInteger()
        val subject = SongVideoLooks(
            executor,
            song = { r -> if (known) SongVideoLooks.Song("item-${r.id}", listOf("Blender Studio"), "Agent 327 – Theme") else null },
            hasVideo = { hasVideo }, linked = { linked },
            find = { artists, title -> looks.incrementAndGet(); assertEquals(listOf("Blender Studio") to "Agent 327 – Theme", artists to title); lookup() },
            queue = { song, _, found -> queued.add(song.id to found) },
            log = { message, _ -> logs.add(message) },
            done = { done.add(it) },
            hold = { held.incrementAndGet(); AutoCloseable { held.decrementAndGet() } },
        )
    }

    @Test fun aCompletedSongWithTheMarkLooksOnceAndQueuesOneLinkedVideo() {
        val run = Run(now) { MusicVideoLookup.Found(video) }
        run.subject.completed(record())
        assertEquals(1, run.looks.get())
        assertEquals(listOf("song" to video), run.queued.toList())
    }

    @Test fun everyLookEndsItsMarkAndKeepsTheAppWorkingUntilThen() {
        for (lookup in listOf<() -> MusicVideoLookup>({ MusicVideoLookup.None }, { MusicVideoLookup.Failed("offline") }, { error("boom") })) {
            val pending = java.util.ArrayDeque<Runnable>()
            val run = Run(Executor { pending.add(it) }, lookup = lookup)
            run.subject.completed(record())
            assertEquals("the service is held while the look waits", 1, run.held.get())
            assertEquals(emptyList<String>(), run.done)
            pending.poll()!!.run()
            // whatever it found, the job stops asking (a restart resumes only looks that never ran) and the hold is let go
            assertEquals(listOf("song"), run.done)
            assertEquals(0, run.held.get())
        }
    }

    @Test fun aSongWithoutTheMarkOrNotCompletedIsNeverLookedUp() {
        val run = Run(now) { MusicVideoLookup.Found(video) }
        run.subject.completed(record(findVideo = false))
        for (status in listOf(JobStatus.QUEUED, JobStatus.DOWNLOADING, JobStatus.FAILED, JobStatus.CANCELLED)) run.subject.completed(record(status = status))
        assertEquals(0, run.looks.get())
        assertTrue(run.queued.isEmpty())
    }

    @Test fun aSongThatAlreadyHasAVideoQueuesNothingAndDoesNotLook() {
        val run = Run(now, hasVideo = true) { MusicVideoLookup.Found(video) }
        run.subject.completed(record())
        assertEquals(0, run.looks.get())
        assertTrue(run.queued.isEmpty())
    }

    @Test fun aVideoJobAlreadyLinkedToTheSongQueuesNothingAndDoesNotLook() {
        // a Retry or a restart completes the song again
        val run = Run(now, linked = true) { MusicVideoLookup.Found(video) }
        run.subject.completed(record())
        assertEquals(0, run.looks.get())
        assertTrue(run.queued.isEmpty())
    }

    @Test fun aSongThatIsNotInTheLibraryIsLeftAlone() {
        val run = Run(now, known = false) { MusicVideoLookup.Found(video) }
        run.subject.completed(record())
        assertEquals(0, run.looks.get())
        assertTrue(run.queued.isEmpty())
    }

    @Test fun noOfficialVideoLeavesTheSongAlone() {
        val run = Run(now) { MusicVideoLookup.None }
        run.subject.completed(record())
        assertEquals(1, run.looks.get())
        assertTrue(run.queued.isEmpty())
    }

    @Test fun aFailedLookIsQuietAndLeavesTheSongAlone() {
        val failed = Run(now) { MusicVideoLookup.Failed("Couldn't look for the music video. Check your connection and try again.") }
        failed.subject.completed(record())
        assertTrue(failed.queued.isEmpty())
        assertEquals(1, failed.logs.size)
        val thrown = Run(now) { throw IllegalStateException("offline") }
        thrown.subject.completed(record())
        assertTrue(thrown.queued.isEmpty())
        assertEquals(1, thrown.logs.size)
    }

    @Test fun aVideoThatCannotBeQueuedIsQuietAndLeavesTheSongAlone() {
        val logs = ArrayList<String>()
        val subject = SongVideoLooks(now, { SongVideoLooks.Song("i", listOf("a"), "t") }, { false }, { false }, { _, _ -> MusicVideoLookup.Found(video) },
            { _, _, _ -> throw IllegalArgumentException("Queue is full.") }, { message, _ -> logs.add(message) })
        subject.completed(record())
        assertEquals(1, logs.size)
    }

    @Test fun looksRunOneAtATime() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val active = AtomicInteger()
            val most = AtomicInteger()
            val done = CountDownLatch(40)
            val subject = SongVideoLooks(pool, { r -> SongVideoLooks.Song(r.id, listOf("a"), "t") }, { false }, { false },
                { _, _ ->
                    most.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    Thread.sleep(2)
                    active.decrementAndGet(); done.countDown()
                    MusicVideoLookup.None
                }, { _, _, _ -> })
            repeat(40) { subject.completed(record("song-$it")) }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(1, most.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun theMarkIsSavedWithTheQueueAndAnOlderQueueWithoutItReadsAsOff() {
        val marked = record("a")
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(marked, record("b", findVideo = false))))
        assertTrue(back[0].findVideo)
        assertFalse(back[1].findVideo)
        // a queue written before this option has no such key
        val old = JobCodec.encode(record("c")).also { it.remove("findVideo") }
        assertFalse(JobCodec.decode(JSONObject(old.toString())).findVideo)
    }
}
