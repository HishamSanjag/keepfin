package app.keepfin.merge

import app.keepfin.core.library.OpDraft as CoreDraft
import app.keepfin.core.merge.Batch
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract section 3: the journal file and the batch receipt protocol (never import twice). */
class MergeJournalTest {
    private lateinit var root: File
    private val stores = ArrayList<KeepfinStore>()
    private val lib = "22222222-2222-4222-8222-222222222222"
    private val actor = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Before fun setUp() { root = Files.createTempDirectory("keepfin-merge").toFile() }
    @After fun tearDown() { stores.forEach { it.abandon() }; root.deleteRecursively() }

    private fun journal(id: String = "m1", stage: MergeStage = MergeStage.Planned) = MergeJournal(
        mergeId = id, stage = stage, peerDeviceId = "peer", keepMine = true, treeUri = "content://tree", sProfileId = "p1", tProfileId = "p2",
        sLibraryId = "s", sReplicaId = "sr", sActor = "sa", sName = "Mine", sRecords = "", tLibraryId = lib, tName = "Main", startedAt = 5,
    )

    @Test fun theJournalRoundTripsEveryField() {
        val store = JournalStore(File(root, "merges"))
        val j = journal().copy(
            stage = MergeStage.Separating, paused = true, tReplicaId = "tr", tActor = "ta", plan = PlanInfo(3, 10, 10, 2, 1, true),
            receipt = Receipt(1, 7, 4, "hash"), applied = 1, artCopied = 2, mapPublished = true, activationSeq = 9, activatedAt = 11, keepUntil = 99,
            profileSwitched = true, outsideRemapped = true, error = "x",
            separate = SeparateState(SeparateStep.Published, false, true, 2, 1, Receipt(0, 1, 2, "h"), 3, 2, Receipt(1, 3, 4, "g"), 12, 13, true),
        )
        store.write(j)
        assertEquals(j, store.read("m1"))
        assertEquals(j, store.unfinished())
        assertNull(store.active())
    }

    @Test fun unfinishedAndActiveAreTold() {
        val store = JournalStore(File(root, "merges"))
        store.write(journal("a", MergeStage.Active).copy(startedAt = 1))
        store.write(journal("b", MergeStage.Describing).copy(startedAt = 2))
        store.write(journal("c", MergeStage.Cleaned).copy(startedAt = 3))
        assertEquals("b", store.unfinished()!!.mergeId)
        assertEquals("a", store.active()!!.mergeId)
        assertEquals(listOf("c", "b", "a"), store.all().map { it.mergeId })
    }

    @Test fun aTornJournalFileIsIgnoredNotFatal() {
        val store = JournalStore(File(root, "merges"))
        store.write(journal())
        store.file("m1").writeText("{\"mergeId\":\"m1\",\"sta")
        assertNull(store.read("m1"))
        assertNull(store.unfinished())
    }

    @Test fun everyStageHasAUniqueWireNameAndOnlyTheLiveOnesAreUnfinished() {
        assertEquals(MergeStage.entries.size, MergeStage.entries.map { it.wire }.toSet().size)
        for (s in MergeStage.entries) assertEquals(s, MergeStage.of(s.wire))
        assertFalse(MergeStage.Active.unfinished)
        assertTrue(MergeStage.Activating.unfinished)
        assertTrue(MergeStage.Separating.unfinished)
        assertFalse(MergeStage.Separated.unfinished)
    }

    // ---- batch receipts -----------------------------------------------------------------------------------------------

    private fun open(dev: String, folder: FakeFolderAccess): Pair<KeepfinStore, LibraryReader> {
        val deviceDir = File(root, "dev-$dev")
        val state = SqlMergeState(File(deviceDir, "state/state.db").path, lib, "rep", null, JdbcHost())
        val store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = lib, actor = actor, deviceDir = deviceDir, outboxDir = File(deviceDir, "outbox"),
                newId = { java.util.UUID.randomUUID().toString() }, state = state, flushIdleMs = 3_600_000,
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
        return store to LibraryReader(store, state, "rep")
    }

    private fun plan(dir: File, sizes: List<Int>) {
        val w = PlanWriter(dir)
        var n = 0
        sizes.forEachIndexed { i, size ->
            w.batch(Batch.of(i, (0 until size).map { CoreDraft("profile.set", mapOf("field" to JStr("f${n++}"), "value" to JStr("v"))) }))
        }
        w.close()
    }

    @Test fun aCrashInsideABatchAppliesEachDraftExactlyOnce() {
        // every prefix of a 7 draft batch: draft count already applied when the app died
        for (applied in 0..7) {
            val dir = File(root, "plan-$applied").also { it.mkdirs() }
            plan(dir, listOf(7, 3))
            val folder = FakeFolderAccess()
            val (store, reader) = open("a$applied", folder)
            // the journal says batch 0 is about to be applied, then the app applies `applied` drafts and dies
            val receipt = Receipt(0, reader.actorSeq(actor), 7, PlanFiles.batch(dir, 0).draftsHash)
            if (applied > 0) store.apply(PlanFiles.batch(dir, 0).drafts.take(applied).map { OpDraft(it.kind, it.body) }).get(10, TimeUnit.SECONDS)
            store.abandon()
            val (again, reader2) = open("a$applied", folder)
            var saved = BatchApplier.State(0, receipt)
            BatchApplier.apply(again, reader2, { again.actorId }, dir, 2, saved, save = { }, cancelled = { false }) { _, _ -> }
            assertEquals("applied $applied", 10L, reader2.actorSeq(actor))
            assertEquals(2, saved.applied)
            assertNull(saved.receipt)
            val fields = again.viewsJson().get(10, TimeUnit.SECONDS)
            for (i in 0 until 10) assertTrue("f$i present once", fields.split("\"f$i\"").size == 2)
        }
    }

    @Test fun aReceiptThatDoesNotMatchItsBatchIsRefused() {
        val dir = File(root, "plan-x").also { it.mkdirs() }
        plan(dir, listOf(2))
        val (store, reader) = open("x", FakeFolderAccess())
        val bad = BatchApplier.State(0, Receipt(0, 0, 2, "not-the-hash"))
        try {
            BatchApplier.apply(store, reader, { store.actorId }, dir, 1, bad, save = { }, cancelled = { false }) { _, _ -> }
            throw AssertionError("expected a failure")
        } catch (e: MergeFailure) {
            assertNotNull(e.message)
        }
        assertEquals(0L, reader.actorSeq(actor))
    }

    @Test fun pausingStopsBetweenBatchesAndResumeContinuesWhereItStopped() {
        val dir = File(root, "plan-p").also { it.mkdirs() }
        plan(dir, listOf(2, 2, 2))
        val (store, reader) = open("p", FakeFolderAccess())
        val state = BatchApplier.State(0, null)
        var stopAfter = 1
        try {
            BatchApplier.apply(store, reader, { store.actorId }, dir, 3, state, save = { }, cancelled = { state.applied >= stopAfter }) { _, _ -> }
        } catch (e: MergePaused) { }
        assertEquals(1, state.applied)
        assertEquals(2L, reader.actorSeq(actor))
        stopAfter = 99
        BatchApplier.apply(store, reader, { store.actorId }, dir, 3, state, save = { }, cancelled = { false }) { _, _ -> }
        assertEquals(6L, reader.actorSeq(actor))
    }

    @Test fun theFenceBuffersPlaysAndGivesThemBackOnce() {
        MergeFence.clear()
        MergeFence.enter("lib")
        assertTrue(MergeFence.downloadsWait && MergeFence.copiesWait && MergeFence.blocksWrites("lib"))
        MergeFence.bufferPlayback("lib", "i1", 3.0, false)
        MergeFence.bufferPlayback("lib", "i1", 9.0, false)
        MergeFence.bufferPlayback("lib", "i1", null, true)
        var released = 0
        val off = MergeFence.onRelease { released++ }
        MergeFence.exit("lib")
        off()
        assertFalse(MergeFence.downloadsWait)
        assertEquals(1, released)
        val plays = MergeFence.drainPlayback("lib")
        assertEquals(listOf(true to null), plays.map { it.finished to it.position })
        assertTrue(MergeFence.drainPlayback("lib").isEmpty())
    }
}
