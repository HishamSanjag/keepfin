package app.keepfin.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The scale benchmark. By default it runs a 20,000-item library (and prints its numbers); with KEEPFIN_SCALE=200000
 * it runs the target library of 200,000 items, 2,000 playlists and 10 places and holds the budgets of the desktop spec
 * section 10 (with generous margins, since a test machine is shared).
 */
class ScaleBenchTest {
    private val actor = "a1000000-0000-4000-8000-000000000001"
    private val partLimit = 64 * 1024 * 1024

    private fun id(n: Int, kind: Int): String {
        val h = ((n.toLong() * 2654435761L) and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        return h + "-" + kind.toString(16).padStart(4, '0') + "-4000-8000-000000000001"
    }

    private inline fun <T> timed(out: MutableMap<String, Long>, name: String, f: () -> T): T {
        val t = TimeSource.Monotonic.markNow()
        val r = f()
        out[name] = t.elapsedNow().inWholeMilliseconds
        return r
    }

    @Test
    fun theTargetLibraryStaysWithinItsBudgets() {
        val scale = scaleEnv()?.toIntOrNull()
        val n = scale ?: 20_000
        val full = n >= 200_000
        val playlists = maxOf(1, n / 100)
        val lines = ArrayList<String>(n * 2 + playlists + 20)
        var seq = 0L
        fun next(kind: String, body: Map<String, Jv>): String {
            seq++
            return Op(OpId(actor, seq), Hlc(1_700_000_000_000L + seq, 0, actor), kind, body).encode()
        }
        for (p in 0 until playlists) lines.add(next("playlist.add", mapOf("playlist" to JStr(id(p, 2)), "fields" to jObj("name" to JStr("Playlist $p")))))
        for (i in 0 until n) {
            lines.add(
                next(
                    "item.add",
                    mapOf(
                        "item" to JStr(id(i, 1)),
                        "fields" to jObj(
                            "media" to JStr("audio"), "title" to JStr("Track $i"), "artist" to JStr("Artist ${i % 500}"),
                            "album" to JStr("Album ${i % 2000}"), "duration" to JNum(180L + i % 100),
                        ),
                    ),
                ),
            )
        }
        val keys = listOf("a0", "a1", "a2", "a3")
        for (i in 0 until n) {
            lines.add(
                next(
                    "entry.insert",
                    mapOf("playlist" to JStr(id(i % playlists, 2)), "entry" to JStr(id(i, 3)), "item" to JStr(id(i, 1)), "key" to JStr(keys[i % 4])),
                ),
            )
        }
        for (pl in 0 until 10) lines.add(next("place.set", mapOf("place" to JStr(id(pl, 4)), "field" to JStr("name"), "value" to JStr("Place $pl"))))
        val edits = ArrayList<String>()
        for (k in 0 until 101) {
            val item = id((k * 7919) % n, 1)
            edits.add(next("item.set", mapOf("item" to JStr(item), "field" to JStr("title"), "value" to JStr("Edited $k"))))
        }

        val ms = LinkedHashMap<String, Long>()
        var logRef: OpLog? = OpLog()
        val log = logRef!!
        timed(ms, "admit all (${lines.size} ops)") { for (l in lines) log.admit(l) }
        timed(ms, "takeTouched after the bulk") { log.takeTouched() }
        timed(ms, "admit 100 edits") { for (k in 0 until 100) log.admit(edits[k]) }
        val partial = timed(ms, "takeTouched + viewsFor (100 items)") {
            val t = log.takeTouched()
            log.partialViewsJson(t)
        }
        assertTrue(partial.contains("Edited 0"))
        val touched100 = log.observed(listOf(id(0, 1)))
        assertTrue(touched100.items.getValue(id(0, 1)).tags.isNotEmpty())

        val parts = LinkedHashMap<String, ByteArray>()
        timed(ms, "checkpointParts (all 17)") { for (s in log.dirtyShards()) parts[s] = assertNotNull(log.partBytes(TEST_LIB, s)) }
        assertEquals(emptyList(), log.dirtyShards())
        val expectedVector = log.versionVector // the coverage of the shards written now; the one rewritten later is newer
        val biggest = parts.values.maxOf { it.size }
        assertTrue(biggest < partLimit, "a part is $biggest bytes")
        log.admit(edits[100])
        val dirty = log.dirtyShards()
        assertEquals(1, dirty.size)
        timed(ms, "checkpointParts (1 dirty shard)") { parts[dirty[0]] = assertNotNull(log.partBytes(TEST_LIB, dirty[0])) }
        // a cold open starts from the files alone: let go of the log and the op lines first
        val expectedItems = log.liveState.items.size
        logRef = null
        lines.clear()
        edits.clear()
        val decoded = ArrayList<CheckpointPart>()
        timed(ms, "decode 17 parts") {
            for ((s, b) in parts) {
                val r = decodePart(b, TEST_LIB)
                assertTrue(r is PartResult.Ok, "part $s")
                decoded.add(r.part)
            }
        }
        val back = timed(ms, "fromCheckpointParts (merge + start the log)") { assertNotNull(OpLog.fromParts(decoded, TEST_LIB)) }
        assertEquals(expectedItems, back.liveState.items.size)
        assertEquals(expectedVector, back.versionVector)
        timed(ms, "full views JSON") { back.viewsJson().length }
        println(
            "SCALE n=$n playlists=$playlists: " + ms.entries.joinToString(", ") { "${it.key} ${it.value} ms" } +
                "; parts ${parts.values.sumOf { it.size } / 1_000_000.0} MB total, largest ${biggest / 1_000_000.0} MB",
        )
        val reload = ms.getValue("decode 17 parts") + ms.getValue("fromCheckpointParts (merge + start the log)")
        if (full) {
            assertTrue(ms.getValue("takeTouched + viewsFor (100 items)") < 50, "viewsFor too slow")
            assertTrue(ms.getValue("checkpointParts (1 dirty shard)") < 3000, "one dirty part too slow")
            assertTrue(reload < 15000, "reload too slow: $reload ms")
        }
    }
}
