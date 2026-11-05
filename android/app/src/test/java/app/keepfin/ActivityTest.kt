package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ActivityTest {
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.ENGLISH
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12) = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    // Thursday 15 October 2026.
    private val now = at(2026, 10, 15)
    private val day = 24L * 60 * 60 * 1000

    private fun entry(
        id: String, kind: ActivityKind = ActivityKind.BATCH_DONE, atMs: Long = now, read: Boolean = false, away: Boolean = false,
        target: ActivityTarget = ActivityTarget(collectionId = "c"),
    ) = ActivityEntry(id, kind, atMs, "t-$id", read = read, target = target, away = away)

    private fun job(
        id: String, status: JobStatus, group: JobGroup? = null, error: String? = null, saveError: String? = null, attempt: Int = 1,
        space: String? = "music", kind: MediaKind = MediaKind.AUDIO, provider: String? = "YouTube", existing: ExistingMatch? = null,
    ) = Job(
        id = id, url = "https://www.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = null, duration = null, provider = provider, label = "",
        kind = kind, status = status, percent = if (status == JobStatus.COMPLETED) 100f else 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null,
        expectedSize = null, fileSize = null, phase = null, error = error, file = null, publicUri = null, publicPath = null, saveError = saveError,
        createdAt = 0, completedAt = null, attempt = attempt, group = group, space = space, existing = existing,
    )

    private val trip = JobGroup("trip", "Road Trip", "https://music.youtube.com/playlist?list=trip")
    private fun ctx(foreground: Boolean = false, sync: Set<String> = emptySet()) = ActivityContext(
        now = now, foreground = foreground,
        spaceName = { if (it == "music") "Music" else null }, isMusicSpace = { it == "music" }, syncJobIds = sync,
    )

    // ---- pruning ---------------------------------------------------------------------------------

    @Test fun keepsTheNewest200AndNothingOlderThan30Days() {
        val many = (1..250).map { entry("e$it", atMs = now - it * 60_000L) }
        val pruned = ActivityLogic.prune(many, now)
        assertEquals(200, pruned.size)
        assertEquals("e1", pruned.first().id)
        assertEquals("e200", pruned.last().id)

        val old = listOf(entry("fresh", atMs = now - 29 * day), entry("stale", atMs = now - 31 * day), entry("edge", atMs = now - 30 * day))
        assertEquals(listOf("fresh", "edge"), ActivityLogic.prune(old, now).map { it.id })
    }

    // ---- grouping --------------------------------------------------------------------------------

    @Test fun dayLabelsAreTodayYesterdayWeekdayThenDate() {
        fun label(ms: Long) = ActivityLogic.dayLabel(ms, now, zone, locale)
        assertEquals("Today", label(at(2026, 10, 15, 1)))
        assertEquals("Today", label(now + 5_000))
        assertEquals("Yesterday", label(at(2026, 10, 14, 23)))
        assertEquals("Tuesday", label(at(2026, 10, 13)))
        assertEquals("Friday", label(at(2026, 10, 9)))
        assertEquals("8 Oct", label(at(2026, 10, 8)))
        assertEquals("30 Dec 2025", label(at(2025, 12, 30)))
    }

    @Test fun groupsByDayNewestFirst() {
        val list = listOf(
            entry("old", atMs = at(2026, 10, 9)), entry("a", atMs = at(2026, 10, 15, 9)), entry("b", atMs = at(2026, 10, 15, 11)),
            entry("y", atMs = at(2026, 10, 14, 8)),
        )
        val groups = ActivityLogic.group(list, now, zone, locale)
        assertEquals(listOf("Today", "Yesterday", "Friday"), groups.map { it.label })
        assertEquals(listOf("b", "a"), groups[0].entries.map { it.id })
    }

    // ---- merge-update of sync-found -------------------------------------------------------------------

    @Test fun syncFoundUpdatesTheSameEntryWhileItsBatchRuns() {
        var list = ActivityLogic.recordSyncFound(emptyList(), "gym", "Gym Mix", listOf("j1", "j2"), true, "Workout", now, away = false)
        assertEquals(1, list.size)
        assertEquals("Gym Mix got 2 new songs", list[0].title)
        assertEquals("Downloading to Workout", list[0].detail)
        // Read, then a second check queues one more while the batch still runs: same entry, three songs, unread again.
        list = list.map { it.copy(read = true) }
        list = ActivityLogic.recordSyncFound(list, "gym", "Gym Mix", listOf("j3", "j2"), true, "Workout", now + 1000, away = true)
        assertEquals(1, list.size)
        assertEquals("Gym Mix got 3 new songs", list[0].title)
        assertEquals(listOf("j1", "j2", "j3"), list[0].target.jobIds)
        assertFalse(list[0].read)
        assertTrue(list[0].away)
        // Another collection gets its own entry.
        list = ActivityLogic.recordSyncFound(list, "run", "Daily run", listOf("r1"), false, null, now + 2000, away = false)
        assertEquals(2, list.size)
        assertEquals("Daily run got 1 new item", list.first { it.target.collectionId == "run" }.title)
    }

    @Test fun aFinishedBatchIsClosedAndANewCheckStartsAFreshEntry() {
        var list = ActivityLogic.recordSyncFound(emptyList(), "gym", "Gym Mix", listOf("j1", "j2", "j3"), true, "Workout", now, away = false)
        val pending = ActivityLogic.settleSync(list) { if (it == "j3") SyncOutcome.PENDING else SyncOutcome.SAVED }
        assertSame(list, pending)
        val allSaved = ActivityLogic.settleSync(list) { SyncOutcome.SAVED }
        assertEquals("All 3 saved", allSaved[0].detail)
        assertFalse(allSaved[0].open)
        val mixed = ActivityLogic.settleSync(list) { if (it == "j3") SyncOutcome.FAILED else SyncOutcome.SAVED }
        assertEquals("2 saved, 1 failed", mixed[0].detail)
        list = ActivityLogic.recordSyncFound(allSaved, "gym", "Gym Mix", listOf("j9"), true, "Workout", now + 5000, away = false)
        assertEquals(2, list.size)
    }

    // ---- counts, actions, away -----------------------------------------------------------------------

    @Test fun unreadAndProblemCounts() {
        val list = listOf(
            entry("a", read = true), entry("b"), entry("c", ActivityKind.FAILED), entry("d", ActivityKind.SIGN_IN, read = true),
        )
        assertEquals(2, ActivityLogic.unreadCount(list))
        assertEquals(2, ActivityLogic.problemCount(list))
        assertEquals(listOf("b", "c"), ActivityLogic.filter(list, ActivityFilter.UNREAD).map { it.id })
        assertEquals(listOf("c", "d"), ActivityLogic.filter(list, ActivityFilter.PROBLEMS).map { it.id })
        assertEquals(4, ActivityLogic.filter(list, ActivityFilter.ALL).size)
    }

    @Test fun actionsComeFromKindAndTarget() {
        fun actions(kind: ActivityKind, target: ActivityTarget) = ActivityLogic.actions(entry("x", kind, target = target)).map { it.label }
        assertEquals(listOf("Open"), actions(ActivityKind.SYNC_FOUND, ActivityTarget(collectionId = "c")))
        assertEquals(emptyList<String>(), actions(ActivityKind.SYNC_FOUND, ActivityTarget()))
        assertEquals(listOf("Review"), actions(ActivityKind.SYNC_REVIEW, ActivityTarget(collectionId = "c")))
        assertEquals(listOf("Open"), actions(ActivityKind.BATCH_DONE, ActivityTarget(groupId = "g")))
        assertEquals(listOf("Retry", "Details"), actions(ActivityKind.FAILED, ActivityTarget(jobIds = listOf("j"))))
        assertEquals(listOf("What's new"), actions(ActivityKind.APP_UPDATED, ActivityTarget(version = "1")))
        assertEquals(listOf("Install"), actions(ActivityKind.UPDATE_READY, ActivityTarget(version = "1")))
        assertEquals(listOf("Sign in"), actions(ActivityKind.SIGN_IN, ActivityTarget(provider = "YouTube")))
    }

    @Test fun awayCountIsUnreadEntriesCreatedWhileAwayExceptTheUpdateNote() {
        val list = listOf(
            entry("a", away = true), entry("b", away = true, read = true), entry("c", away = false),
            entry("d", ActivityKind.APP_UPDATED, away = true), entry("e", ActivityKind.FAILED, away = true),
        )
        assertEquals(2, ActivityLogic.awayCount(list))
    }

    // ---- recording from download endings ---------------------------------------------------------------

    @Test fun aBatchIsRecordedOnceWhenTheLastItemEnds() {
        val queue = listOf(job("1", JobStatus.COMPLETED, trip), job("2", JobStatus.COMPLETED, trip), job("3", JobStatus.DOWNLOADING, trip))
        assertTrue(ActivityLogic.forTerminal(queue[0], queue, ctx()).isEmpty())
        val done = queue.map { if (it.id == "3") it.copy(status = JobStatus.COMPLETED) else it }
        val entries = ActivityLogic.forTerminal(done[2], done, ctx())
        assertEquals(1, entries.size)
        val batch = entries.single()
        assertEquals(ActivityKind.BATCH_DONE, batch.kind)
        assertEquals("Road Trip finished", batch.title)
        assertEquals("3 of 3 songs saved to Music", batch.detail)
        assertEquals("trip", batch.target.groupId)
    }

    @Test fun aFailedItemInABatchGivesOneFailedEntryNotOnePerItem() {
        val queue = listOf(
            job("1", JobStatus.COMPLETED, trip), job("2", JobStatus.FAILED, trip, error = "Not available in your region"),
            job("3", JobStatus.FAILED, trip, error = "Network problem. Check your connection and retry."),
        )
        val entries = ActivityLogic.forTerminal(queue[2], queue, ctx())
        assertEquals(listOf(ActivityKind.BATCH_DONE, ActivityKind.FAILED), entries.map { it.kind })
        val failed = entries[1]
        assertEquals("2 songs from Road Trip failed", failed.title)
        assertEquals("Not available in your region", failed.detail)
        assertEquals(listOf("2", "3"), failed.target.jobIds)
        assertEquals("1 of 3 songs saved to Music", entries[0].detail)
    }

    @Test fun aSingleDownloadThatFinishedInFrontIsNotRecorded() {
        val done = job("s", JobStatus.COMPLETED)
        assertTrue(ActivityLogic.forTerminal(done, listOf(done), ctx(foreground = true)).isEmpty())
        val away = ActivityLogic.forTerminal(done, listOf(done), ctx(foreground = false))
        assertEquals(1, away.size)
        assertEquals("Song s finished", away[0].title)
        assertEquals("Saved to Music", away[0].detail)
    }

    @Test fun aSingleFailureIsRecordedEvenInFront() {
        val failed = job("f", JobStatus.FAILED, error = "That quality isn't available for this media.")
        val entries = ActivityLogic.forTerminal(failed, listOf(failed), ctx(foreground = true))
        assertEquals(1, entries.size)
        assertEquals(ActivityKind.FAILED, entries[0].kind)
        assertEquals("Song f failed", entries[0].title)
        assertEquals(listOf("f"), entries[0].target.jobIds)
    }

    @Test fun nothingIsRecordedWhileAnItemWaitsForAnAutomaticRetry() {
        val retrying = job("r", JobStatus.QUEUED, error = null).copy(retryCount = 1)
        assertTrue(ActivityLogic.forTerminal(retrying, listOf(retrying), ctx()).isEmpty())
        // Only an exhausted budget (or a permanent failure) reaches fail(): the retry policy is the gate.
        val transient = "ERROR: unable to download video data: HTTP Error 503"
        assertNotNull(RetryPolicy.next(0, transient))
        assertNull(RetryPolicy.next(RetryPolicy.MAX, transient))
    }

    @Test fun anExpiredSignInGivesASignInEntryOnce() {
        val queue = listOf(
            job("1", JobStatus.FAILED, trip, error = "Sign in required: this content is private or members-only. Sign in from Settings → Downloads → Website sign-in, then retry."),
            job("2", JobStatus.FAILED, trip, error = ErrorMessages.BOT_MESSAGE),
        )
        val entries = ActivityLogic.forTerminal(queue[1], queue, ctx())
        assertEquals(1, entries.count { it.kind == ActivityKind.SIGN_IN })
        val signIn = entries.first { it.kind == ActivityKind.SIGN_IN }
        assertEquals("Sign in to YouTube again", signIn.title)
        assertEquals("Private or members-only downloads need it", signIn.detail)
        assertEquals("YouTube", signIn.target.provider)
    }

    @Test fun aSyncBatchIsCoveredByItsOwnEntryNotABatchDone() {
        val sync = JobGroup("gym", "Gym Mix", "https://music.youtube.com/playlist?list=gym")
        val queue = listOf(job("1", JobStatus.COMPLETED, sync), job("2", JobStatus.COMPLETED, sync))
        assertTrue(ActivityLogic.forTerminal(queue[1], queue, ctx(sync = setOf("1", "2"))).isEmpty())
        val single = job("s", JobStatus.COMPLETED, sync)
        assertTrue(ActivityLogic.forTerminal(single, listOf(single), ctx(sync = setOf("s"))).isEmpty())
    }

    @Test fun existingRecordingsAndCancelledJobsAreNotNews() {
        val existing = job("e", JobStatus.COMPLETED, existing = ExistingMatch(itemId = null, uri = "content://x", space = "music", by = "x"))
        assertTrue(ActivityLogic.forTerminal(existing, listOf(existing), ctx()).isEmpty())
        val cancelled = job("c", JobStatus.CANCELLED)
        assertTrue(ActivityLogic.forTerminal(cancelled, listOf(cancelled), ctx()).isEmpty())
    }

    @Test fun wordsUseSongsForMusicAndItemsOtherwise() {
        assertEquals("1 song", "1 " + ActivityLogic.noun(true, 1))
        assertEquals("2 items", "2 " + ActivityLogic.noun(false, 2))
        assertEquals("Lo-fi Study has 31 new songs waiting", ActivityLogic.syncReviewTitle("Lo-fi Study", 31, true))
        assertEquals("More than 20, so they wait for you", ActivityLogic.syncReviewDetail(20))
    }

    // ---- the store ---------------------------------------------------------------------------------

    private val library = LibrarySettings()

    @Test fun storeRecordsABatchOnceEvenWhenEveryItemReportsItsEnd() {
        var clock = now
        val store = ActivityStore(null, { clock }, foreground = { true })
        val done = listOf(job("1", JobStatus.COMPLETED, trip), job("2", JobStatus.COMPLETED, trip), job("3", JobStatus.COMPLETED, trip))
        done.forEach { store.onJobTerminal(it, done, library); clock += 10 }
        assertEquals(1, store.entries.value.size)
        assertEquals(ActivityKind.BATCH_DONE, store.entries.value[0].kind)
        assertEquals("3 of 3 songs saved to Music", store.entries.value[0].detail)
    }

    @Test fun storeIgnoresAForegroundSingleButKeepsOneFinishedWhileAway() {
        val done = job("s", JobStatus.COMPLETED)
        val front = ActivityStore(null, { now }, foreground = { true })
        front.onJobTerminal(done, listOf(done), library)
        assertTrue(front.entries.value.isEmpty())
        val away = ActivityStore(null, { now }, foreground = { false })
        away.onJobTerminal(done, listOf(done), library)
        assertEquals(1, away.entries.value.size)
        assertTrue(away.entries.value[0].away)
    }

    @Test fun storeRecordsFailureOnlyForRealFailures() {
        val store = ActivityStore(null, { now }, foreground = { true })
        val retrying = job("r", JobStatus.QUEUED).copy(retryCount = 2)
        store.onJobTerminal(retrying, listOf(retrying), library)
        assertTrue(store.entries.value.isEmpty())
        val failed = job("r", JobStatus.FAILED, error = "Network problem. Check your connection and retry.")
        store.onJobTerminal(failed, listOf(failed), library)
        assertEquals(listOf(ActivityKind.FAILED), store.entries.value.map { it.kind })
    }

    @Test fun storeSettlesASyncEntryWhenItsDownloadsEnd() {
        val store = ActivityStore(null, { now }, foreground = { false })
        val running = listOf(job("1", JobStatus.DOWNLOADING), job("2", JobStatus.DOWNLOADING))
        store.recordSyncFound("gym", "Gym Mix", listOf("1", "2"), true, "Workout", running)
        assertTrue(store.entries.value[0].open)
        val one = listOf(job("1", JobStatus.COMPLETED), job("2", JobStatus.DOWNLOADING))
        store.onJobTerminal(one[0], one, library)
        assertTrue(store.entries.value[0].open)
        val both = listOf(job("1", JobStatus.COMPLETED), job("2", JobStatus.FAILED, error = "x"))
        store.onJobTerminal(both[1], both, library)
        val sync = store.entries.value.first { it.kind == ActivityKind.SYNC_FOUND }
        assertFalse(sync.open)
        assertEquals("1 saved, 1 failed", sync.detail)
    }

    @Test fun readRemoveAndAwayCountOncePerStart() {
        val store = ActivityStore(null, { now }, foreground = { false })
        store.record(entry("a"))
        store.record(entry("b"))
        store.record(entry("c", ActivityKind.APP_UPDATED), away = false)
        assertEquals(2, store.takeAwayCount())
        assertEquals(0, store.takeAwayCount())
        store.markRead(listOf("a"))
        assertEquals(listOf("b", "c"), store.entries.value.filter { !it.read }.map { it.id }.sorted())
        store.markAllRead()
        assertEquals(0, ActivityLogic.unreadCount(store.entries.value))
        store.remove("a")
        assertEquals(listOf("b", "c"), store.entries.value.map { it.id }.sorted())
    }

    @Test fun signInRefreshesInsteadOfPilingUp() {
        val store = ActivityStore(null, { now }, foreground = { true })
        val failed = job("1", JobStatus.FAILED, error = ErrorMessages.BOT_MESSAGE)
        store.onJobTerminal(failed, listOf(failed), library)
        store.markAllRead()
        val again = job("2", JobStatus.FAILED, error = ErrorMessages.BOT_MESSAGE)
        store.onJobTerminal(again, listOf(failed, again), library)
        assertEquals(1, store.entries.value.count { it.kind == ActivityKind.SIGN_IN })
        assertFalse(store.entries.value.first { it.kind == ActivityKind.SIGN_IN }.read)
    }

    @Test fun theFileRoundTripsAndPrunesWhenWritten() {
        val dir = File(System.getProperty("java.io.tmpdir"), "activity-test-" + System.nanoTime()).apply { mkdirs() }
        try {
            val file = File(dir, "activity.json")
            val store = ActivityStore(file, { now }, foreground = { false })
            store.record(entry("a", ActivityKind.FAILED, target = ActivityTarget(groupId = "g", jobIds = listOf("j1", "j2"))))
            store.record(entry("old", atMs = now - 40 * day))
            store.flush()
            val loaded = ActivityStore(file, { now }, foreground = { true }).entries.value
            assertEquals(listOf("a"), loaded.map { it.id })
            assertEquals(ActivityTarget(groupId = "g", jobIds = listOf("j1", "j2")), loaded[0].target)
            assertTrue(loaded[0].away)
            assertFalse(File(dir, "activity.json.tmp").exists())
        } finally { dir.deleteRecursively() }
    }
}
