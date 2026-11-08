package app.keepfin.folder

import app.keepfin.core.net.SoftwareSigner
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import app.keepfin.core.sync.encodeSegment
import app.keepfin.core.sync.segmentFileName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Data compaction of the segment log on a phone (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md): the same cases as the desktop's compaction.test.ts. */
class KeepfinCompactionTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actorA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val actorB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val signer = SoftwareSigner.generate()
    private lateinit var root: File
    private lateinit var folder: FakeFolderAccess
    private val open = ArrayList<KeepfinStore>()
    private var clock = 1_700_000_000_000L
    private var idCounter = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("keepfin-compact").toFile()
        folder = FakeFolderAccess()
    }

    @After fun tearDown() {
        open.forEach { it.abandon() }
        root.deleteRecursively()
    }

    private fun newId() = "cccccccc-cccc-4ccc-8ccc-%012d".format(++idCounter)

    private fun options(
        actor: String,
        device: String = actor,
        key: app.keepfin.core.net.DeviceSigner? = signer,
        hook: ((String, Long, Long, Int) -> Unit)? = null,
        compact: Boolean = false,
        leaseWaitMs: Long = 10_000,
        flushIdleMs: Long = 3_600_000,
        flushMaxAgeMs: Long = 300_000,
        now: () -> Long = { clock++ },
        compactDelayMs: Long = 3_600_000,
        compactSoonMs: Long = 3_600_000,
        compactGapMs: Long = 600_000,
    ) = StoreOptions(
        folder = folder, libraryId = library, actor = actor, deviceDir = File(root, "device-$device"), outboxDir = File(root, "outbox-$device"),
        newId = ::newId, now = now, flushIdleMs = flushIdleMs, flushMaxAgeMs = flushMaxAgeMs, checkpointDelayMs = 5, retryMs = 10, state = OpLogState(), signer = key,
        compact = compact, compactDelayMs = compactDelayMs, compactSoonMs = compactSoonMs, compactGapMs = compactGapMs, leaseWaitMs = leaseWaitMs, onCompactStep = hook,
    )

    private fun open(o: StoreOptions): KeepfinStore = KeepfinStore.open(o).get(10, TimeUnit.SECONDS).also { open += it }

    private fun set(field: String, value: String) = OpDraft("profile.set", mapOf("field" to JStr(field), "value" to JStr(value)))

    /** [flushes] flushes of [per] ops each, so the folder holds that many tiny segments. */
    private fun pile(store: KeepfinStore, flushes: Int, per: Int = 2, from: Int = 1): Int {
        var n = from
        repeat(flushes) {
            store.apply((1..per).map { set("name", "v${n++}") }).get(10, TimeUnit.SECONDS)
            store.flush().get(10, TimeUnit.SECONDS)
        }
        return n
    }

    private fun names(actor: String = actorA) = folder.segmentPaths().filter { it.contains("/$actor/") }.map { it.substringAfterLast('/') }

    /** Each op once, from whatever segment files the folder holds. */
    private fun opLines(files: Map<String, ByteArray> = folder.files): List<String> {
        val bySeq = HashMap<String, String>()
        for ((path, bytes) in files) {
            if (!path.endsWith(".seg")) continue
            val v = decodeSegment(bytes, library) as SegmentResult.Ok
            for (op in v.ops) bySeq["${v.actor}:${op.id.seq}"] = op.encode()
        }
        return bySeq.values.sorted()
    }

    private fun views(store: KeepfinStore) = store.viewsJson().get(10, TimeUnit.SECONDS)

    @Test fun aPileOfSmallSegmentsBecomesOneFileWithTheSameOpsSignedByTheSameKey() {
        val a = open(options(actorA))
        pile(a, 40)
        assertEquals(40, names().size)
        val before = opLines()
        val v = views(a)
        val stats = a.compact().get(10, TimeUnit.SECONDS)
        assertEquals(1, stats.merged)
        assertEquals(40, stats.removed)
        assertEquals(listOf(segmentFileName(1, 80)), names())
        assertEquals(before, opLines())
        val merged = decodeSegment(folder.files.getValue("${KeepfinStore.OPS_DIR}/$actorA/${segmentFileName(1, 80)}"), library) as SegmentResult.Ok
        assertEquals(deviceIdOf(signer.spki), merged.signer)
        assertEquals(v, views(a))
        assertEquals(0, a.compact().get(10, TimeUnit.SECONDS).merged)
        a.close().get(10, TimeUnit.SECONDS)
        assertEquals(v, views(open(options(actorA, device = "again"))))
    }

    @Test fun fewerThanEightSmallSegmentsAreLeftAlone() {
        val a = open(options(actorA))
        pile(a, 7)
        assertEquals(0, a.compact().get(10, TimeUnit.SECONDS).merged)
        assertEquals(7, names().size)
    }

    private fun crashAt(step: String, at: Int) {
        val armed = booleanArrayOf(false)
        val a = open(options(actorA, hook = { s, _, _, removed -> if (armed[0] && s == step && removed == at) throw IllegalStateException("simulated crash") }))
        pile(a, 30)
        val before = opLines()
        val v = views(a)
        armed[0] = true
        try {
            a.compact().get(10, TimeUnit.SECONDS)
            fail("the crash was not simulated")
        } catch (e: ExecutionException) {
            assertEquals("simulated crash", e.cause?.message)
        }
        a.abandon()
        assertEquals(before, opLines())
        // the folder as the crash left it opens to the same library, and the next round (or the start-up one) converges to one file
        val again = open(options(actorA, device = "second"))
        assertEquals(v, views(again))
        again.compact().get(10, TimeUnit.SECONDS)
        assertEquals(listOf(segmentFileName(1, 60)), names())
        assertEquals(before, opLines())
        assertEquals(v, views(again))
    }

    @Test fun aCrashAfterPublishLosesNothing() = crashAt("published", 0)
    @Test fun aCrashAfterVerifyLosesNothing() = crashAt("verified", 0)
    @Test fun aCrashBeforeTheFirstRemovalLosesNothing() = crashAt("removing", 0)
    @Test fun aCrashAfterSomeRemovalsLosesNothing() = crashAt("removing", 7)
    @Test fun aCrashAfterTheLastButOneRemovalLosesNothing() = crashAt("removing", 29)

    @Test fun everyIntermediateFolderStateOpensToTheSameLibrary() {
        val states = ArrayList<Map<String, ByteArray>>()
        val a = open(options(actorA, hook = { _, _, _, removed -> if (removed % 4 == 0) states += HashMap(folder.files) }))
        pile(a, 20)
        val v = views(a)
        a.compact().get(10, TimeUnit.SECONDS)
        states += HashMap(folder.files)
        assertTrue(states.size >= 5)
        for ((i, state) in states.withIndex()) {
            val f = FakeFolderAccess()
            f.mkdirs("${KeepfinStore.OPS_DIR}/$actorA")
            for ((p, b) in state) if (p.endsWith(".seg")) f.files[p] = b
            val reader = open(options(actorB, folder = f, device = "reader$i"))
            assertEquals("state $i", v, views(reader))
            assertTrue(reader.findings().get(10, TimeUnit.SECONDS).isEmpty())
        }
    }

    private fun options(actor: String, folder: FakeFolderAccess, device: String) =
        StoreOptions(folder = folder, libraryId = library, actor = actor, deviceDir = File(root, "device-$device"), outboxDir = File(root, "outbox-$device"), newId = ::newId, now = { clock++ }, flushIdleMs = 3_600_000, retryMs = 10, state = OpLogState(), compact = false)

    @Test fun aPeerThatHoldsBothTheMergedFileAndTheFilesItCoversDropsTheCoveredOnes() {
        val crash = booleanArrayOf(true)
        val a = open(options(actorA, hook = { s, _, _, removed -> if (crash[0] && s == "removing" && removed == 0) throw IllegalStateException("crash") }))
        pile(a, 20)
        val v = views(a)
        try { a.compact().get(10, TimeUnit.SECONDS) } catch (e: ExecutionException) { }
        crash[0] = false
        a.abandon()
        assertEquals(21, names().size)
        val b = open(options(actorB, compact = true))
        assertEquals(v, views(b))
        assertEquals(listOf(segmentFileName(1, 40)), names())
    }

    @Test fun aCoveredFileWhoseOpsDifferIsNeverRemoved() {
        val crash = booleanArrayOf(true)
        val a = open(options(actorA, hook = { s, _, _, removed -> if (crash[0] && s == "removing" && removed == 0) throw IllegalStateException("crash") }))
        pile(a, 10)
        try { a.compact().get(10, TimeUnit.SECONDS) } catch (e: ExecutionException) { }
        crash[0] = false
        a.abandon()
        val forged = (1L..2L).map { Op(OpId(actorA, it), Hlc(1_800_000_000_000L + it, 0, actorA), "profile.set", mapOf("field" to JStr("name"), "value" to JStr("forged$it"))) }
        folder.files["${KeepfinStore.OPS_DIR}/$actorA/${segmentFileName(1, 2)}"] = encodeSegment(library, actorA, forged, signer)
        val b = open(options(actorB, compact = true))
        assertTrue(b.findings().get(10, TimeUnit.SECONDS).any { it.startsWith("equivocation:$actorA") })
        assertEquals(11, names().size)
    }

    @Test fun compactionWaitsForATransferAndRemovesNoFileWhileOneIsOpen() {
        val a = open(options(actorA, leaseWaitMs = 150))
        pile(a, 20)
        val lease = a.hold()
        val busy = a.compact().get(10, TimeUnit.SECONDS)
        assertEquals("busy", busy.skipped)
        assertEquals(20, names().size)
        lease.close()
        assertEquals(1, a.compact().get(10, TimeUnit.SECONDS).merged)
        assertEquals(1, names().size)
    }

    @Test fun aTransferThatStartsAfterTheMergedFileIsWrittenKeepsTheOriginals() {
        val lease = arrayOfNulls<AutoCloseable>(1)
        val holder = arrayOfNulls<KeepfinStore>(1)
        val a = open(options(actorA, hook = { s, _, _, _ -> if (s == "verified" && lease[0] == null) lease[0] = holder[0]!!.hold() }))
        holder[0] = a
        pile(a, 20)
        val v = views(a)
        assertEquals("busy", a.compact().get(10, TimeUnit.SECONDS).skipped)
        assertEquals(21, names().size)
        lease[0]!!.close()
        val second = a.compact().get(10, TimeUnit.SECONDS)
        assertEquals(20, second.dropped)
        assertEquals(1, names().size)
        assertEquals(v, views(a))
    }

    @Test fun onlySegmentsSignedByTheCurrentKeyAreMerged() {
        val old = SoftwareSigner.generate()
        val first = open(options(actorA, key = old))
        val next = pile(first, 10)
        first.close().get(10, TimeUnit.SECONDS)
        val second = open(options(actorA, key = signer))
        pile(second, 10, from = next)
        assertEquals(20, names().size)
        assertEquals(1, second.compact().get(10, TimeUnit.SECONDS).merged)
        val left = names()
        assertEquals(11, left.size)
        assertEquals(segmentFileName(21, 40), left.last())
    }

    @Test fun theUnsignedPrefixOfADeviceThatLaterGotAKeyIsNeverMergedIntoTheSignedTail() {
        val first = open(options(actorA, key = null))
        val next = pile(first, 9)
        first.close().get(10, TimeUnit.SECONDS)
        val second = open(options(actorA))
        pile(second, 9, from = next)
        second.compact().get(10, TimeUnit.SECONDS)
        val left = names()
        assertEquals(10, left.size)
        val unsigned = decodeSegment(folder.files.getValue("${KeepfinStore.OPS_DIR}/$actorA/${left[0]}"), library) as SegmentResult.Ok
        assertEquals(null, unsigned.signer)
    }

    @Test fun aFlushIsBatchedAndNeverWaitsLongerThanTheMaximumAge() {
        var now = 1_000_000L
        val a = open(options(actorA, flushIdleMs = 400, flushMaxAgeMs = 1_000, now = { now }))
        a.apply(listOf(set("name", "v1"))).get(10, TimeUnit.SECONDS)
        Thread.sleep(100)
        assertEquals(0, names().size)
        // the time passes for the store's own clock; each new op resets the idle timer but the maximum age has run out
        now += 5_000
        a.apply(listOf(set("name", "v2"))).get(10, TimeUnit.SECONDS)
        val end = System.currentTimeMillis() + 3_000
        while (names().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertEquals(1, names().size)
    }

    @Test fun theDefaultBatchingHoldsOperationsUntilACloseOrAFlush() {
        val a = open(StoreOptions(folder = folder, libraryId = library, actor = actorA, deviceDir = File(root, "device-d"), outboxDir = File(root, "outbox-d"), newId = ::newId, now = { clock++ }, state = OpLogState(), signer = signer, compact = false))
        assertEquals(30_000L, StoreOptions(folder = folder, libraryId = library, actor = actorA, deviceDir = root, outboxDir = root, newId = ::newId, state = OpLogState()).flushIdleMs)
        a.apply((1..600).map { set("name", "v$it") }).get(10, TimeUnit.SECONDS)
        Thread.sleep(200)
        assertEquals(0, names().size)
        a.close().get(10, TimeUnit.SECONDS)
        assertEquals(1, names().size)
    }

    @Test fun aCloseWithEnoughSmallSegmentsCompactsThem() {
        val a = open(options(actorA, compact = true, compactGapMs = 0))
        pile(a, 12)
        a.close().get(10, TimeUnit.SECONDS)
        assertEquals(1, names().size)
    }

    @Test fun aFlushThatLeavesEightSmallSegmentsCompactsThemSoon() {
        val a = open(options(actorA, compact = true, compactGapMs = 0, compactSoonMs = 50))
        pile(a, 10)
        val end = System.currentTimeMillis() + 5_000
        while (names().size > 1 && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals(1, names().size)
        assertNotNull(a.actorId)
    }
}
