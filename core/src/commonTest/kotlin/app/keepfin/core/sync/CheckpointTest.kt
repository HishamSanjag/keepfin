package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckpointTest {
    private val lib = TEST_LIB

    /** A log holding, for each actor, the first [cut] ops (prefixes) of [ops]. */
    private fun prefixLog(ops: List<Op>, cuts: Map<String, Long>): OpLog =
        logOf(ops.filter { it.id.seq <= (cuts[it.id.actor] ?: 0L) })

    @Test
    fun incomparableCheckpointsMergeToTheSameStateAsReplayingEverything() {
        for (seed in 1..25) {
            val rnd = Random(seed)
            val gen = OpGen(seed, 4)
            val ops = gen.generate(160)
            val top = HashMap<String, Long>()
            for (op in ops) top[op.id.actor] = maxOf(top[op.id.actor] ?: 0L, op.id.seq)
            val cutsA = top.mapValues { rnd.nextLong(0, it.value + 1) }
            val cutsB = top.mapValues { rnd.nextLong(0, it.value + 1) }
            val cpA = prefixLog(ops, cutsA).checkpoint(lib)
            val cpB = prefixLog(ops, cutsB).checkpoint(lib)
            val merged = assertNotNull(cpA.merge(cpB), "seed=$seed")
            assertEquals(merged, assertNotNull(cpB.merge(cpA)), "merge commutes seed=$seed")
            assertEquals(merged, assertNotNull(merged.merge(cpA)), "merge absorbs seed=$seed")
            assertEquals(VersionVectors.merge(cutsA.filter { it.value > 0 }, cutsB.filter { it.value > 0 }), merged.coverage, "coverage seed=$seed")
            // the merged state is the state of the pointwise-larger prefixes
            val cuts = VersionVectors.merge(cutsA, cutsB)
            assertEquals(prefixLog(ops, cuts).state, merged.state, "merged state seed=$seed")
            // read it, then admit every op from the segments: covered ones are skipped
            val log = assertNotNull(OpLog.fromCheckpoint(merged), "seed=$seed")
            for (op in ops.shuffled(rnd)) log.admit(op)
            val all = logOf(ops)
            assertEquals(all.state, log.state, "replay seed=$seed")
            assertEquals(all.versionVector, log.versionVector, "vv seed=$seed")
            assertContentEquals(encodeCheckpoint(all.checkpoint(lib)), encodeCheckpoint(log.checkpoint(lib)), "bytes seed=$seed")
        }
    }

    @Test
    fun coverageIsPrefixOnlyWhenPendingOpsSitAboveAGap() {
        val ops = (1L..5L).map { op(A, it, it, "profile.set", "field" to JStr("name"), "value" to JStr("v$it")) }
        val log = OpLog()
        for (i in listOf(0, 1, 3, 4)) log.admit(ops[i]) // op 3 is missing
        assertEquals(JStr("v5"), log.state.profile["name"]!!.value) // the live state includes the pending ops
        val cp = log.checkpoint(lib)
        assertEquals(mapOf(A to 2L), cp.coverage)
        assertEquals(JStr("v2"), cp.state.profile["name"]!!.value) // the checkpoint does not
        assertEquals(Hlc(2, 0, A), cp.maxHlc)
        assertEquals(cp, logOf(ops.take(2)).checkpoint(lib))
        // a log started from it learns the rest without double counting
        val again = assertNotNull(OpLog.fromCheckpoint(cp))
        for (o in ops) again.admit(o)
        assertEquals(logOf(ops).state, again.state)
    }

    @Test
    fun aDifferentSemanticsIsIgnoredWhenReading() {
        val ops = (1L..3L).map { op(A, it, it, "profile.set", "field" to JStr("name"), "value" to JStr("v$it")) }
        val good = logOf(ops).checkpoint(lib)
        val foreign = good.copy(semantics = 2, coverage = mapOf(A to 99L), state = State.EMPTY)
        assertEquals(good, mergeCheckpoints(listOf(foreign, good, good), lib))
        assertEquals(Checkpoint.empty(lib), mergeCheckpoints(listOf(foreign), lib))
        assertEquals(null, good.merge(foreign))
        assertEquals(null, OpLog.fromCheckpoint(foreign))
        // on the wire: an unreadable-semantics file reports OtherSemantics, and a different library WrongLibrary
        val text = encodeCheckpoint(good).decodeToString()
        val newer = text.replace("\"semantics\":1", "\"semantics\":2")
        val rewritten = reseal(newer)
        val r = decodeCheckpoint(rewritten.encodeToByteArray(), lib) as CheckpointResult.Err
        assertTrue(r.error is CheckpointError.OtherSemantics)
        assertTrue(mergeCheckpoints(emptyList(), lib) == Checkpoint.empty(lib))
    }

    /** Recomputes the header checksum (the body is the last line). */
    private fun reseal(file: String): String {
        val lines = file.split("\n")
        val body = lines[2] + "\n"
        val header = lines[1].replace(Regex("\"sha256\":\"[0-9a-f]{64}\""), "\"sha256\":\"" + sha256Hex(body) + "\"")
        return lines[0] + "\n" + header + "\n" + body
    }

    @Test
    fun encodingRoundTripsAndIsStable() {
        for (seed in 1..15) {
            val rnd = Random(seed)
            val ops = OpGen(seed, 4).generate(140).filter { rnd.nextInt(100) < 80 }
            val cp = logOf(ops).checkpoint(lib)
            val bytes = encodeCheckpoint(cp)
            val back = decodeCheckpoint(bytes, lib) as CheckpointResult.Ok
            assertEquals(cp, back.checkpoint, "seed=$seed")
            assertContentEquals(bytes, encodeCheckpoint(back.checkpoint), "stable encoding seed=$seed")
        }
    }

    @Test
    fun badCheckpointsAreRejectedWithTypedErrors() {
        val good = encodeCheckpoint(logOf(OpGen(3, 3).generate(40)).checkpoint(lib))
        val text = good.decodeToString()
        fun err(t: String, library: String = lib) = (decodeCheckpoint(t.encodeToByteArray(), library) as CheckpointResult.Err).error
        assertTrue(err("") is CheckpointError.BadMagic)
        assertTrue(err("KFCKP 2" + text.substring(7)) is CheckpointError.NewerFormat)
        assertTrue(err("KFSEG 1" + text.substring(7)) is CheckpointError.BadMagic)
        assertTrue(err("KFCKP 1\n{") is CheckpointError.BadHeader)
        assertTrue(err(text, "other") is CheckpointError.WrongLibrary)
        assertTrue(err(text.dropLast(1)) is CheckpointError.MissingFinalNewline)
        assertTrue(err(text + "{}\n") is CheckpointError.TrailingData)
        assertTrue(err("KFCKP 1\n" + text.substring(8).replace("\n", "\r\n")) is CheckpointError.CarriageReturn)
        val flipped = text.substring(0, text.length - 3) + "1}\n"
        assertTrue(err(flipped) is CheckpointError.Checksum)
        assertTrue(decodeCheckpoint(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + good, lib).let { it is CheckpointResult.Err && it.error is CheckpointError.Bom })
        // a state that is canonical JSON but the wrong shape, or missing a key, or unsorted
        fun withState(state: String): String {
            val lines = text.split("\n")
            return reseal(lines[0] + "\n" + lines[1] + "\n" + state + "\n")
        }
        assertTrue(err(withState("[]")) is CheckpointError.BadShape)
        assertTrue(err(withState("{}")) is CheckpointError.BadShape)
        val full = encodeCanonical(StateCodec.toJson(State.EMPTY))
        assertTrue(decodeCheckpoint(withState(full).encodeToByteArray(), lib) is CheckpointResult.Ok)
        assertTrue(err(withState(full.replace("\"findings\":[]", "\"findings\":[\"b\",\"a\"]"))) is CheckpointError.NonCanonical)
        assertEquals(null, Checkpoint.empty(lib).merge(Checkpoint.empty("x")))
    }

    @Test
    fun goldenBytes() {
        val u1 = "11111111-1111-1111-1111-111111111111"
        val e1 = "e1000000-0000-0000-0000-000000000001"
        val log = OpLog()
        val ops = listOf(
            Op(OpId(A, 1), Hlc(1700000000000, 0, A), "item.add", mapOf("item" to JStr(u1), "fields" to jObj("media" to JStr("audio"), "title" to JStr("café")))),
            Op(OpId(A, 2), Hlc(1700000000000, 1, A), "item.revise", mapOf("item" to JStr(u1), "content" to JStr(content(1)), "size" to JNum(42), "parents" to JArr(emptyList()))),
            Op(OpId(B, 1), Hlc(1700000000001, 0, B), "item.revise", mapOf("item" to JStr(u1), "content" to JStr(content(2)), "size" to JNum(7), "parents" to JArr(listOf(JStr("$A:2"))))),
            Op(OpId(B, 2), Hlc(1700000000002, 0, B), "item.place", mapOf("item" to JStr(u1), "place" to JStr(PLACE), "path" to JStr("Music/a.mp3"))),
            Op(OpId(A, 3), Hlc(1700000000003, 0, A), "set.add", mapOf("set" to JStr("favourites"), "element" to JStr(u1))),
            Op(OpId(A, 4), Hlc(1700000000004, 0, A), "playlist.add", mapOf("playlist" to JStr(PL), "fields" to jObj("name" to JStr("Mix")))),
            Op(OpId(A, 5), Hlc(1700000000005, 0, A), "entry.insert", mapOf("playlist" to JStr(PL), "entry" to JStr(e1), "item" to JStr(u1), "key" to JStr("a0V"))),
            Op(OpId(B, 3), Hlc(1700000000006, 0, B), "profile.set", mapOf("field" to JStr("name"), "value" to JStr("😀"))),
            Op(OpId(B, 4), Hlc(1700000000007, 0, B), "item.delete", mapOf("item" to JStr(u1), "observedTags" to JArr(listOf(JStr("$A:1"))), "observedHeads" to JArr(listOf(JStr("$A:2"))))),
        )
        for (o in ops) log.admit(o)
        val bytes = encodeCheckpoint(log.checkpoint(lib))
        assertEquals(GOLDEN, bytes.decodeToString())
        val back = decodeCheckpoint(GOLDEN.encodeToByteArray(), lib) as CheckpointResult.Ok
        assertEquals(log.checkpoint(lib), back.checkpoint)
        // the state really is a delete conflict (B revised concurrently with A's delete observing only A:2)
        assertEquals(ItemStatus.DeleteConflict, back.checkpoint.state.itemStatus(u1))
    }

    companion object {
        const val GOLDEN = "KFCKP 1\n{\"coverage\":{\"a0000000-0000-0000-0000-00000000000a\":5,\"b0000000-0000-0000-0000-00000000000b\":4},\"library\":\"00000000-0000-0000-0000-0000000000aa\",\"maxHlc\":\"1700000000007.000000.b0000000-0000-0000-0000-00000000000b\",\"semantics\":1,\"sha256\":\"e027d5f5a9b35937e693772a0bbdd93cfd3f4675030317da6b2cdba3c6deadf7\"}\n{\"entries\":{\"e1000000-0000-0000-0000-000000000001\":{\"item\":\"11111111-1111-1111-1111-111111111111\",\"op\":\"a0000000-0000-0000-0000-00000000000a:5\",\"playlist\":\"33333333-3333-3333-3333-333333333333\"}},\"entryKey\":{\"e1000000-0000-0000-0000-000000000001\":{\"hlc\":\"1700000000005.000000.a0000000-0000-0000-0000-00000000000a\",\"op\":\"a0000000-0000-0000-0000-00000000000a:5\",\"value\":\"a0V\"}},\"findings\":[],\"items\":{\"11111111-1111-1111-1111-111111111111\":{\"deletedHeads\":[\"a0000000-0000-0000-0000-00000000000a:2\"],\"exists\":{\"adds\":[\"a0000000-0000-0000-0000-00000000000a:1\"],\"removes\":[\"a0000000-0000-0000-0000-00000000000a:1\"]},\"fields\":{\"media\":{\"hlc\":\"1700000000000.000000.a0000000-0000-0000-0000-00000000000a\",\"op\":\"a0000000-0000-0000-0000-00000000000a:1\",\"value\":\"audio\"},\"title\":{\"hlc\":\"1700000000000.000000.a0000000-0000-0000-0000-00000000000a\",\"op\":\"a0000000-0000-0000-0000-00000000000a:1\",\"value\":\"caf\u00e9\"}},\"revisions\":{\"a0000000-0000-0000-0000-00000000000a:2\":{\"content\":\"sha256:1ef0ae7bbe4ce6c99ab744fe8c27582178d69c660538ef6a4b201cf5a944e17a\",\"parents\":[],\"size\":42},\"b0000000-0000-0000-0000-00000000000b:1\":{\"content\":\"sha256:3460ebae1c45bfd069074b365281354cfdf41b82ffb05c7eedd6775446fcd3a4\",\"parents\":[\"a0000000-0000-0000-0000-00000000000a:2\"],\"size\":7}}}},\"placements\":{\"11111111-1111-1111-1111-111111111111\":{\"55555555-5555-5555-5555-555555555555\":{\"hlc\":\"1700000000002.000000.b0000000-0000-0000-0000-00000000000b\",\"op\":\"b0000000-0000-0000-0000-00000000000b:2\",\"value\":\"Music/a.mp3\"}}},\"places\":{},\"playlists\":{\"33333333-3333-3333-3333-333333333333\":{\"exists\":{\"adds\":[\"a0000000-0000-0000-0000-00000000000a:4\"],\"removes\":[]},\"fields\":{\"name\":{\"hlc\":\"1700000000004.000000.a0000000-0000-0000-0000-00000000000a\",\"op\":\"a0000000-0000-0000-0000-00000000000a:4\",\"value\":\"Mix\"}}}},\"profile\":{\"name\":{\"hlc\":\"1700000000006.000000.b0000000-0000-0000-0000-00000000000b\",\"op\":\"b0000000-0000-0000-0000-00000000000b:3\",\"value\":\"\uD83D\uDE00\"}},\"removedEntries\":[],\"sets\":{\"favourites\":{\"11111111-1111-1111-1111-111111111111\":{\"adds\":[\"a0000000-0000-0000-0000-00000000000a:3\"],\"removes\":[]}}},\"spaces\":{}}\n"
    }
}
