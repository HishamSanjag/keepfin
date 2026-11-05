package app.keepfin.core.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Times checkpoint encoding and decoding at the size of a large library. Numbers are printed; the limits are generous. */
class CheckpointBenchTest {
    private fun bigCheckpoint(count: Int): Checkpoint {
        val actor = "a1000000-0000-4000-8000-000000000001"
        val log = OpLog()
        for (i in 0 until count) {
            val item = "b" + (1000000 + i).toString() + "-0000-4000-8000-000000000001"
            val fields = jObj(
                "media" to JStr("audio"),
                "title" to JStr("Track $i"),
                "artist" to JStr("Artist ${i % 500}"),
                "album" to JStr("Album ${i % 2000}"),
                "duration" to JNum(180L + i % 100),
            )
            log.admit(Op(OpId(actor, i + 1L), Hlc(1700000000000L + i, 0, actor), "item.add", mapOf("item" to JStr(item), "fields" to fields)))
        }
        return log.checkpoint(TEST_LIB)
    }

    @Test
    fun twentyThousandItemsEncodeAndDecodeInTime() {
        val count = 20_000
        val cp = bigCheckpoint(count)
        assertEquals(count, cp.state.items.size)
        val t0 = TimeSource.Monotonic.markNow()
        val bytes = encodeCheckpoint(cp)
        val encodeMs = t0.elapsedNow().inWholeMilliseconds
        val t1 = TimeSource.Monotonic.markNow()
        val back = decodeCheckpoint(bytes, TEST_LIB)
        val decodeMs = t1.elapsedNow().inWholeMilliseconds
        println("checkpoint timing for $count items: encode $encodeMs ms, decode $decodeMs ms (${bytes.size / 1000000.0} MB)")
        assertTrue(back is CheckpointResult.Ok, "decode failed")
        assertEquals(cp, back.checkpoint)
        assertContentEquals(bytes, encodeCheckpoint(back.checkpoint))
        assertTrue(decodeMs < 3000, "decode took $decodeMs ms")
        assertTrue(encodeMs < 3000, "encode took $encodeMs ms")
    }
}
