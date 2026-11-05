package app.keepfin.folder

import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.encodeSegment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeepfinStoreTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actorA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val actorB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private lateinit var root: File
    private lateinit var folder: FakeFolderAccess
    private val open = ArrayList<KeepfinStore>()
    private var clock = 1_700_000_000_000L
    private var idCounter = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("keepfin-store").toFile()
        folder = FakeFolderAccess()
    }

    @After fun tearDown() {
        open.forEach { it.abandon() }
        root.deleteRecursively()
    }

    private fun newId() = "cccccccc-cccc-4ccc-8ccc-%012d".format(++idCounter)

    private fun options(actor: String, device: String = actor, fl: Long = 3_600_000, ckptEvery: Int = 5_000, folder: FolderAccess = this.folder) = StoreOptions(
        folder = folder,
        libraryId = library,
        actor = actor,
        deviceDir = File(root, "device-$device"),
        outboxDir = File(root, "outbox-$device"),
        newId = ::newId,
        now = { clock++ },
        flushIdleMs = fl,
        checkpointEvery = ckptEvery,
        checkpointDelayMs = 5,
        retryMs = 10,
        state = OpLogState(),
    )

    private fun open(o: StoreOptions): KeepfinStore = KeepfinStore.open(o).get(10, TimeUnit.SECONDS).also { open += it }

    private fun set(field: String, value: String) = OpDraft("profile.set", mapOf("field" to JStr(field), "value" to JStr(value)))
    @Test fun segmentRoundTripKeepsTheSameState() {
        val a = open(options(actorA))
        a.apply((1..5).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        assertEquals(1, folder.segmentPaths().size)
        assertEquals(0, a.pendingOps)
        val views = a.viewsJson().get(10, TimeUnit.SECONDS)
        a.close().get(10, TimeUnit.SECONDS)

        // a new install of the same folder: no outbox, no device files
        val b = open(options(actorB))
        assertEquals(views, b.viewsJson().get(10, TimeUnit.SECONDS))
        assertEquals(1, b.stats!!.segmentsRead)
        assertEquals(5L, b.versionVector().get(10, TimeUnit.SECONDS)[actorA])
    }

    @Test fun outboxIsReplayedAfterACrash() {
        val a = open(options(actorA))
        a.apply((1..3).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        val views = a.viewsJson().get(10, TimeUnit.SECONDS)
        a.abandon() // a crash: no flush, no segment
        assertEquals(0, folder.segmentPaths().size)

        val again = open(options(actorA))
        assertEquals(views, again.viewsJson().get(10, TimeUnit.SECONDS))
        assertEquals(3, again.stats!!.outboxOps)
        again.flush().get(10, TimeUnit.SECONDS)
        assertEquals(1, folder.segmentPaths().size)
        // numbering goes on without a gap
        again.apply(listOf(set("name", "v4"))).get(10, TimeUnit.SECONDS)
        assertEquals(4L, again.versionVector().get(10, TimeUnit.SECONDS)[actorA])
    }

    @Test fun aTornLastOutboxLineIsDropped() {
        val a = open(options(actorA))
        a.apply((1..2).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        a.abandon()
        val outbox = File(root, "outbox-$actorA/ops.jsonl")
        outbox.appendText("{\"body\":{\"field\":\"na") // a crash in the middle of an append
        val again = open(options(actorA))
        assertEquals(2, again.stats!!.outboxOps)
        again.apply(listOf(set("name", "v3"))).get(10, TimeUnit.SECONDS)
        again.abandon()
        val third = open(options(actorA))
        assertEquals(3, third.stats!!.outboxOps) // the new op started on a clean line
    }

    @Test fun nothingIsAcknowledgedBeforeTheOutboxHasIt() {
        val a = open(options(actorA))
        a.apply(listOf(set("name", "x"))).get(10, TimeUnit.SECONDS)
        // the future completed, so the line is already on disk
        assertEquals(1, File(root, "outbox-$actorA/ops.jsonl").readLines().size)
    }

    private fun writeSegmentOf(actor: String, first: Long, count: Int, tag: String = "x"): String {
        val ops = (0 until count).map { i ->
            val seq = first + i
            Op(OpId(actor, seq), Hlc(1_700_000_000_000L + seq, 0, actor), "profile.set", mapOf("field" to JStr("name"), "value" to JStr("$tag$seq")))
        }
        val path = "${KeepfinStore.OPS_DIR}/$actor/" + app.keepfin.core.sync.segmentFileName(first, first + count - 1)
        folder.mkdirs(parentOf(path))
        folder.files[path] = encodeSegment(library, actor, ops)
        return path
    }

    @Test fun damagedAndIncompleteSegmentsAreIgnoredAndTheValidOneIsKept() {
        val good = writeSegmentOf(actorB, 1, 3)
        val bad = writeSegmentOf(actorB, 4, 3)
        folder.corrupt(bad, folder.files.getValue(bad).size - 20)
        val truncatedPath = writeSegmentOf(actorB, 7, 3)
        folder.files[truncatedPath] = folder.files.getValue(truncatedPath).copyOf(40)
        assertTrue(good in folder.files)

        val a = open(options(actorA))
        assertEquals(3L, a.versionVector().get(10, TimeUnit.SECONDS)[actorB])
        assertEquals(1, a.stats!!.segmentsRead)
        // not a finding yet: a file that is still being written is retried first
        assertTrue(a.findings().get(10, TimeUnit.SECONDS).none { it.startsWith("unreadable-segment") })
    }

    @Test fun aPersistentlyUnreadableSegmentBecomesAFindingAndNeverBlocksTheRest() {
        val bad = writeSegmentOf(actorB, 1, 3)
        folder.corrupt(bad, folder.files.getValue(bad).size - 20)
        val a = open(options(actorA))
        repeat(8) { a.rescan(true).get(10, TimeUnit.SECONDS) }
        assertTrue(a.findings().get(10, TimeUnit.SECONDS).any { it.startsWith("unreadable-segment:$actorB/") })
        // a later valid segment of another actor still merges
        writeSegmentOf("dddddddd-dddd-4ddd-8ddd-dddddddddddd", 1, 2)
        a.rescan(true).get(10, TimeUnit.SECONDS)
        assertEquals(2L, a.versionVector().get(10, TimeUnit.SECONDS)["dddddddd-dddd-4ddd-8ddd-dddddddddddd"])
    }

    @Test fun anIncompleteListingIsNeverReadAsDeletions() {
        writeSegmentOf(actorB, 1, 4)
        val a = open(options(actorA))
        val before = a.viewsJson().get(10, TimeUnit.SECONDS)
        val revision = a.revision
        folder.emptyListings = true // the volume answers with an empty listing and no error
        a.rescan(true).get(10, TimeUnit.SECONDS)
        assertEquals(before, a.viewsJson().get(10, TimeUnit.SECONDS))
        assertEquals(revision, a.revision)
        assertEquals(4L, a.versionVector().get(10, TimeUnit.SECONDS)[actorB])
        assertTrue(a.listingIncomplete)
        folder.emptyListings = false
        a.rescan(true).get(10, TimeUnit.SECONDS)
        assertFalse(a.listingIncomplete)
        assertEquals(before, a.viewsJson().get(10, TimeUnit.SECONDS))
    }

    @Test fun anUnavailableVolumeFailsTheCallAndKeepsTheState() {
        val a = open(options(actorA))
        a.apply(listOf(set("name", "kept"))).get(10, TimeUnit.SECONDS)
        folder.failWith = FolderError.Unavailable("card removed")
        val failure = runCatching { a.flush().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(failure?.cause is FolderError.Unavailable)
        assertEquals(1, a.pendingOps) // still in the outbox
        folder.failWith = null
        a.flush().get(10, TimeUnit.SECONDS)
        assertEquals(0, a.pendingOps)
        assertEquals(1, folder.segmentPaths().size)
    }

    @Test fun anotherDevicesSegmentIsMergedOnRescan() {
        val a = open(options(actorA))
        val seen = java.util.concurrent.atomic.AtomicInteger()
        a.onChange { remote -> if (remote) seen.incrementAndGet() }
        writeSegmentOf(actorB, 1, 2)
        a.rescan().get(10, TimeUnit.SECONDS)
        assertEquals(2L, a.versionVector().get(10, TimeUnit.SECONDS)[actorB])
        assertEquals(1, seen.get())
        a.rescan().get(10, TimeUnit.SECONDS)
        assertEquals(1, seen.get()) // nothing new
    }

    @Test fun aRestoredInstallGetsANewActor() {
        val first = open(options(actorA))
        first.apply((1..3).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        first.close().get(10, TimeUnit.SECONDS)
        // the same actor id again, but this device never wrote those ops (no outbox, no counter)
        val restored = open(options(actorA, device = "fresh"))
        assertTrue(restored.stats!!.rotated)
        assertNotEquals(actorA, restored.actorId)
        restored.apply(listOf(set("name", "mine"))).get(10, TimeUnit.SECONDS)
        restored.flush().get(10, TimeUnit.SECONDS)
        assertTrue(folder.segmentPaths().any { it.contains(restored.actorId) })
    }

    @Test fun checkpointPartsReopenWithTheSameState() {
        val a = open(options(actorA, ckptEvery = 4))
        a.apply((1..6).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        val views = a.viewsJson().get(10, TimeUnit.SECONDS)
        a.close().get(10, TimeUnit.SECONDS)
        assertTrue(folder.files.keys.any { it.startsWith("${KeepfinStore.CKPT_DIR}/") && it.endsWith(".ckpt") })

        val b = open(options(actorB))
        assertTrue(b.stats!!.checkpoint)
        assertEquals(0, b.stats!!.segmentsRead)
        assertEquals(views, b.viewsJson().get(10, TimeUnit.SECONDS))
    }

    @Test fun overlappingSegmentsThatDisagreeAreAnEquivocation() {
        writeSegmentOf(actorB, 1, 3, tag = "x")
        writeSegmentOf(actorB, 2, 3, tag = "y")
        val a = open(options(actorA))
        assertEquals(2L, a.stats!!.equivocations[actorB])
        assertTrue(a.findings().get(10, TimeUnit.SECONDS).any { it.startsWith("equivocation:$actorB") })
    }

    @Test fun onBackgroundEmptiesTheOutbox() {
        val a = open(options(actorA))
        a.apply((1..3).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        a.onBackground().get(10, TimeUnit.SECONDS)
        assertEquals(0L, File(root, "outbox-$actorA/ops.jsonl").length())
        assertTrue(File(root, "device-$actorA/seq/$actorA.json").readText().contains("\"lastSeq\":3"))
        // and the next start still knows it wrote those ops (no false rotation)
        a.abandon()
        assertFalse(open(options(actorA)).stats!!.rotated)
    }

    @Test fun aReadOnlyStoreMergesButNeverWrites() {
        writeSegmentOf(actorB, 1, 2)
        val before = folder.files.keys.toSet()
        val a = open(StoreOptions(folder, library, actorA, File(root, "d"), File(root, "o"), ::newId, { clock++ }, state = OpLogState(), readOnly = true))
        assertEquals(2L, a.versionVector().get(10, TimeUnit.SECONDS)[actorB])
        assertTrue(runCatching { a.apply(listOf(set("name", "x"))).get(10, TimeUnit.SECONDS) }.isFailure)
        assertEquals(before, folder.files.keys.toSet())
    }
}
