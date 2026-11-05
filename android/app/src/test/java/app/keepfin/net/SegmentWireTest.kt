package app.keepfin.net

import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.OpLogState
import app.keepfin.folder.StoreOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Segments on the wire (network spec 4): the version vector text, the frames and the storing of a received file. */
class SegmentWireTest {
    private val root = tempDir("keepfin-wire")
    private val stores = ArrayList<KeepfinStore>()

    @After fun tearDown() {
        stores.forEach { it.abandon() }
        root.deleteRecursively()
    }

    private fun segmentFrom(folder: FakeFolderAccess, ops: Int = 3): ByteArray {
        val store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = LIB, actor = ACTOR_A, deviceDir = File(root, "d"), outboxDir = File(root, "o"), newId = { java.util.UUID.randomUUID().toString() },
                flushIdleMs = 3_600_000, state = OpLogState(),
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
        store.apply((1..ops).map { OpDraft("profile.set", mapOf("field" to JStr("f$it"), "value" to JStr("v$it"))) }).get(10, TimeUnit.SECONDS)
        store.flush().get(10, TimeUnit.SECONDS)
        return folder.files.getValue(folder.segmentPaths().single())
    }

    @Test fun versionVectorsRoundTripAndBadOnesAreRejected() {
        val v = mapOf(ACTOR_A to 12L, ACTOR_B to 3L)
        assertEquals(v, SegmentFiles.parseVector(SegmentFiles.formatVector(v)))
        assertEquals(emptyMap<String, Long>(), SegmentFiles.parseVector(""))
        assertEquals("", SegmentFiles.formatVector(mapOf(ACTOR_A to 0L)))
        for (bad in listOf("nope", "$ACTOR_A", "$ACTOR_A:", "$ACTOR_A:-1", "$ACTOR_A:1x", "$ACTOR_A:1,$ACTOR_A:2", "$ACTOR_A:${"9".repeat(16)}", "not-a-uuid:1", ",")) {
            assertNull("accepted '$bad'", SegmentFiles.parseVector(bad))
        }
        assertNull(SegmentFiles.parseVector((1..600).joinToString(",") { "00000000-0000-4000-8000-%012d:1".format(it) }))
    }

    @Test fun framesRoundTripAndBrokenStreamsAreRefused() {
        val a = ByteArray(10) { 1 }
        val b = ByteArray(300) { 2 }
        val got = ArrayList<ByteArray>()
        FrameReader(1000, 10) { got += it }.readAll(ByteArrayInputStream(SegmentFiles.frame(a) + SegmentFiles.frame(b)))
        assertEquals(listOf(10, 300), got.map { it.size })
        fun fails(bytes: ByteArray, maxFrame: Int = 1000, maxFrames: Int = 10) = try {
            FrameReader(maxFrame, maxFrames) { }.readAll(ByteArrayInputStream(bytes)); false
        } catch (e: FrameError) { true }
        assertTrue(fails(SegmentFiles.frame(b).copyOf(100))) // ends inside a frame
        assertTrue(fails(byteArrayOf(0, 0, 0))) // ends inside a length
        assertTrue(fails(byteArrayOf(0, 0, 0, 0))) // an empty frame
        assertTrue(fails(SegmentFiles.frame(b), maxFrame = 100)) // too large
        assertTrue(fails(SegmentFiles.frame(a) + SegmentFiles.frame(a) + SegmentFiles.frame(a), maxFrames = 2)) // too many
        assertTrue(fails(byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))) // a length that would exhaust memory
    }

    @Test fun aReceivedSegmentIsStoredUnderItsOwnNameAndNeverTwice() {
        val sender = FakeFolderAccess()
        val bytes = segmentFrom(sender)
        val receiver = FakeFolderAccess()
        val first = SegmentFiles.ingest(receiver, LIB, bytes)
        assertTrue(first is Ingest.Stored)
        val path = "${KeepfinStore.OPS_DIR}/$ACTOR_A/0000000000000001-0000000000000003.seg"
        assertEquals(bytes.toList(), receiver.files.getValue(path).toList())
        assertTrue(SegmentFiles.ingest(receiver, LIB, bytes) is Ingest.Have)
        assertEquals(mapOf(ACTOR_A to 3L), SegmentFiles.vectorOf(SegmentFiles.list(receiver)))
    }

    @Test fun damagedOrForeignSegmentsAreRejectedBeforeAnythingIsWritten() {
        val bytes = segmentFrom(FakeFolderAccess())
        val receiver = FakeFolderAccess()
        for (bad in listOf(ByteArray(0), ByteArray(64), bytes.copyOf(bytes.size / 2), bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }, bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })) {
            assertTrue(SegmentFiles.ingest(receiver, LIB, bad) is Ingest.Rejected)
        }
        // a segment of another library
        assertTrue(SegmentFiles.ingest(receiver, "99999999-9999-4999-8999-999999999999", bytes) is Ingest.Rejected)
        assertTrue("nothing was written: ${receiver.files.keys}", receiver.files.isEmpty())
        try { SegmentFiles.ingest(receiver, LIB, ByteArray(SEGMENT_MAX_BYTES + 1)).let { assertTrue(it is Ingest.Rejected) } } catch (e: Exception) { fail() }
    }

    @Test fun missingForListsOnlyWhatThePeerLacks() {
        val list = listOf(SegInfo(ACTOR_A, 1, 5, "a", 1), SegInfo(ACTOR_A, 6, 9, "b", 1), SegInfo(ACTOR_B, 1, 2, "c", 1))
        assertEquals(listOf("b", "c"), SegmentFiles.missingFor(list, mapOf(ACTOR_A to 5L)).map { it.name })
        assertEquals(emptyList<SegInfo>(), SegmentFiles.missingFor(list, mapOf(ACTOR_A to 9L, ACTOR_B to 2L)))
        assertEquals(3, SegmentFiles.missingFor(list, emptyMap()).size)
    }
}
