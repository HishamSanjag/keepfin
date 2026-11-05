package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Touched keys, partial views, observed tags and checkpoint parts (sync spec 10.4). */
class IncrementalTest {
    private val lib = TEST_LIB

    // ---- a reader's model of the views, kept up to date from partial views ----

    private class Model {
        var items = LinkedHashMap<String, Jv>()
        var playlists = LinkedHashMap<String, Jv>()
        var spaces = LinkedHashMap<String, Jv>()
        var sets = LinkedHashMap<String, Jv>()
        var placements = LinkedHashMap<String, Jv>()
        var places = LinkedHashMap<String, Jv>()
        var profile: Jv = JObj(emptyMap())
        var findings: Jv = JArr(emptyList())

        private fun byId(a: Jv?): LinkedHashMap<String, Jv> {
            val out = LinkedHashMap<String, Jv>()
            for (x in (a as JArr).items) out[((x as JObj).members["id"] as JStr).value] = x
            return out
        }

        fun replaceAll(full: JObj) {
            val m = full.members
            items = byId(m["items"])
            playlists = byId(m["playlists"])
            spaces = byId(m["spaces"])
            sets = LinkedHashMap((m["sets"] as JObj).members)
            placements = LinkedHashMap((m["placements"] as JObj).members)
            places = LinkedHashMap((m["places"] as JObj).members)
            profile = m["profile"]!!
            findings = m["findings"]!!
        }

        fun apply(partial: JObj) {
            val m = partial.members
            for (g in (m["goneItems"] as JArr).items) items.remove((g as JStr).value)
            items.putAll(byId(m["items"]))
            playlists.putAll(byId(m["playlists"]))
            spaces.putAll(byId(m["spaces"]))
            for ((name, arr) in (m["sets"] as JObj).members) if ((arr as JArr).items.isEmpty()) sets.remove(name) else sets[name] = arr
            placements.putAll((m["placements"] as JObj).members)
            places.putAll((m["places"] as JObj).members)
            m["profile"]?.let { profile = it }
            m["findings"]?.let { findings = it }
        }

        fun render(): String = encodeCanonical(
            jObj(
                "findings" to findings,
                "items" to JArr(items.keys.sorted().map { items.getValue(it) }),
                "placements" to JObj(placements),
                "places" to JObj(places),
                "playlists" to JArr(playlists.keys.sorted().map { playlists.getValue(it) }),
                "profile" to profile,
                "sets" to JObj(sets),
                "spaces" to JArr(spaces.keys.sorted().map { spaces.getValue(it) }),
            ),
        )
    }

    private fun fullJson(log: OpLog): String = encodeCanonical(parseLoose(log.viewsJson())!!)

    private fun step(log: OpLog, model: Model, label: String) {
        val touched = log.takeTouched()
        val partial = parseLoose(log.partialViewsJson(touched)) as JObj
        if (touched.all) model.replaceAll(partial) else model.apply(partial)
        assertEquals(fullJson(log), model.render(), label)
    }

    @Test
    fun partialViewsOnTopOfThePreviousViewsEqualTheFullViewsAtEveryStep() {
        var steps = 0
        for (seed in 1..40) {
            val rnd = Random(seed)
            val ops = OpGen(seed, 4).generate(120 + seed * 3)
            val delivered = if (seed % 2 == 0) ops.shuffled(rnd) else ops
            val log = OpLog()
            val model = Model()
            model.replaceAll(parseLoose(log.viewsJson()) as JObj)
            var i = 0
            while (i < delivered.size) {
                val n = 1 + rnd.nextInt(if (seed % 3 == 0) 40 else 6)
                for (o in delivered.subList(i, minOf(delivered.size, i + n))) {
                    log.admit(o)
                    if (rnd.nextInt(7) == 0) log.admit(o) // duplicates touch nothing
                }
                i += n
                step(log, model, "seed=$seed after $i ops")
                steps++
            }
        }
        assertTrue(steps > 500)
    }

    @Test
    fun equivocationAndCheckpointLoadReportEverything() {
        val ops = (1L..4L).map { setField(A, it, it, "title", JStr("v$it")) }
        val log = logOf(ops)
        log.takeTouched()
        val variant = setField(A, 2, 2, "title", JStr("other")) // same id, other bytes
        assertTrue(log.admit(variant) is Admission.Equivocation)
        val t = log.takeTouched()
        assertTrue(t.all)
        assertTrue(t.items.isEmpty())
        assertTrue(!log.takeTouched().all) // taken
        // starting from a checkpoint
        val cp = logOf(ops).checkpoint(lib)
        val fresh = assertNotNull(OpLog.fromCheckpoint(cp))
        assertTrue(fresh.takeTouched().all)
        val model = Model()
        model.replaceAll(parseLoose(OpLog().viewsJson()) as JObj)
        step(assertNotNull(OpLog.fromCheckpoint(cp)), model, "after a checkpoint load")
    }

    @Test
    fun touchedKeysAreExactEnoughToBeCheap() {
        val e1 = "e1000000-0000-0000-0000-000000000001"
        val log = OpLog()
        log.admit(add(A, 1, fields = mapOf("media" to JStr("audio"))))
        log.admit(op(A, 2, 2, "playlist.add", "playlist" to JStr(PL)))
        log.admit(op(A, 3, 3, "entry.insert", "playlist" to JStr(PL), "entry" to JStr(e1), "item" to JStr(ITEM), "key" to JStr("a0")))
        var t = log.takeTouched()
        assertEquals(listOf(ITEM), t.items)
        assertEquals(listOf(PL), t.playlists)
        // a field edit touches the item only
        log.admit(setField(A, 4, 4, "title", JStr("x")))
        t = log.takeTouched()
        assertEquals(listOf(ITEM), t.items)
        assertTrue(t.playlists.isEmpty() && t.sets.isEmpty() && !t.profile && !t.findings)
        // a move touches the playlist
        log.admit(op(A, 5, 5, "entry.move", "entry" to JStr(e1), "key" to JStr("a1")))
        t = log.takeTouched()
        assertTrue(t.items.isEmpty())
        assertEquals(listOf(PL), t.playlists)
        // an item's deletion changes its presence in the playlist
        log.admit(delete(A, 6, listOf(OpId(A, 1)), emptyList()))
        t = log.takeTouched()
        assertEquals(listOf(ITEM), t.items)
        assertEquals(listOf(PL), t.playlists)
        // sets, profile, findings
        log.admit(op(A, 7, 7, "set.add", "set" to JStr("favourites"), "element" to JStr(ITEM)))
        log.admit(op(A, 8, 8, "profile.set", "field" to JStr("name"), "value" to JStr("n")))
        log.admit(op(A, 9, 9, "item.set", "item" to JNum(5))) // invalid
        t = log.takeTouched()
        assertEquals(listOf("favourites"), t.sets)
        assertTrue(t.profile && t.findings)
        // a duplicate and an unknown kind touch nothing
        log.admit(op(A, 8, 8, "profile.set", "field" to JStr("name"), "value" to JStr("n")))
        log.admit(op(A, 10, 10, "future.thing", "x" to JNum(1)))
        t = log.takeTouched()
        assertTrue(t.items.isEmpty() && t.playlists.isEmpty() && t.sets.isEmpty() && !t.profile && !t.findings)
    }

    // ---- observed ----

    private fun applied(o: Op) = o.interpret() is Interpretation.Applied

    private fun idsOf(v: Jv?): List<OpId> = ((v as? JArr)?.items ?: emptyList()).map { OpId.parse((it as JStr).value)!! }

    @Test
    fun observedMatchesWhatTheOpsGive() {
        for (seed in 1..30) {
            val gen = OpGen(seed, 4)
            val ops = gen.generate(150 + seed * 4).shuffled(Random(seed))
            val log = logOf(ops)
            val adds = HashMap<String, MutableSet<OpId>>()
            val removes = HashMap<String, MutableSet<OpId>>()
            val revs = HashMap<String, MutableMap<OpId, List<OpId>>>()
            fun key(kind: String, a: String, b: String = "") = "$kind|$a|$b"
            for (o in ops) {
                if (!applied(o)) continue
                val b = o.body
                fun s(name: String) = (b[name] as? JStr)?.value ?: ""
                when (o.kind) {
                    "item.add" -> adds.getOrPut(key("item", s("item"))) { HashSet() }.add(o.id)
                    "item.delete" -> removes.getOrPut(key("item", s("item"))) { HashSet() }.addAll(idsOf(b["observedTags"]))
                    "item.revise" -> revs.getOrPut(s("item")) { HashMap() }[o.id] = idsOf(b["parents"])
                    "playlist.add" -> adds.getOrPut(key("playlist", s("playlist"))) { HashSet() }.add(o.id)
                    "playlist.delete" -> removes.getOrPut(key("playlist", s("playlist"))) { HashSet() }.addAll(idsOf(b["observedTags"]))
                    "space.add" -> adds.getOrPut(key("space", s("space"))) { HashSet() }.add(o.id)
                    "space.delete" -> removes.getOrPut(key("space", s("space"))) { HashSet() }.addAll(idsOf(b["observedTags"]))
                    "set.add" -> adds.getOrPut(key("set", s("set"), s("element"))) { HashSet() }.add(o.id)
                    "set.remove" -> removes.getOrPut(key("set", s("set"), s("element"))) { HashSet() }.addAll(idsOf(b["observedTags"]))
                }
            }
            fun live(k: String): List<OpId> = ((adds[k] ?: emptySet()) - (removes[k] ?: emptySet())).sorted()
            val setElements = listOf("favourites", "tags:x").flatMap { s -> listOf("e1", "e2", "e3").map { Pair(s, it) } }
            val got = log.observed(gen.items, gen.playlists, gen.spaces, setElements)
            for (item in gen.items) {
                assertEquals(live(key("item", item)), got.items.getValue(item).tags, "tags seed=$seed $item")
                val r = revs[item] ?: emptyMap()
                val parents = r.values.flatten().toSet()
                var heads = r.keys.filter { it !in parents }
                if (heads.isEmpty()) heads = r.keys.toList()
                assertEquals(heads.sorted(), got.items.getValue(item).heads, "heads seed=$seed $item")
            }
            for (p in gen.playlists) assertEquals(live(key("playlist", p)), got.playlists.getValue(p), "playlist seed=$seed")
            for (sp in gen.spaces) assertEquals(live(key("space", sp)), got.spaces.getValue(sp), "space seed=$seed")
            for ((set, el) in setElements) assertEquals(live(key("set", set, el)), got.setElements.getValue(set).getValue(el), "set seed=$seed")
        }
        // keys the state lacks come back empty
        val empty = OpLog().observed(listOf(ITEM), listOf(PL), emptyList(), listOf(Pair("s", "e")))
        assertTrue(empty.items.getValue(ITEM).tags.isEmpty() && empty.items.getValue(ITEM).heads.isEmpty())
        assertTrue(empty.playlists.getValue(PL).isEmpty() && empty.setElements.getValue("s").getValue("e").isEmpty())
    }

    // ---- parts ----

    private fun allParts(log: OpLog): List<Pair<String, ByteArray>> =
        log.dirtyShards().map { Pair(it, assertNotNull(log.partBytes(lib, it))) }

    private fun decode(name: String, bytes: ByteArray): CheckpointPart {
        val fast = decodePart(bytes, lib)
        val slow = decodePartReference(bytes, lib)
        if (fast !is PartResult.Ok || slow !is PartResult.Ok) fail("part $name did not decode: fast=${(fast as? PartResult.Err)?.error} slow=${(slow as? PartResult.Err)?.error}")
        assertEquals(name, fast.part.shard)
        assertEquals(slow.part.checkpoint, fast.part.checkpoint, name)
        return fast.part
    }

    @Test
    fun partsRoundTripAndEveryKeyLivesInItsShard() {
        for (seed in 1..25) {
            val log = logOf(OpGen(seed, 4).generate(100 + seed * 5))
            val parts = allParts(log)
            assertEquals(SHARD_IDS, parts.map { it.first })
            assertEquals(emptyList(), log.dirtyShards())
            val decoded = parts.map { decode(it.first, it.second) }
            val back = assertNotNull(OpLog.fromParts(decoded, lib), "seed=$seed")
            assertEquals(log.state, back.state, "seed=$seed")
            assertEquals(log.versionVector, back.versionVector, "vv seed=$seed")
            assertEquals(log.maxHlc, back.maxHlc, "hlc seed=$seed")
            // each part holds only its shard's keys
            for (p in decoded) assertTrue(stateBelongsToShard(p.checkpoint.state, shardIndexOfName(p.shard)), p.shard)
        }
    }

    @Test
    fun anySubsetOfStalePartsPlusReplayGivesTheStateOfAFullReplay() {
        for (seed in 1..40) {
            val rnd = Random(1000 + seed)
            val ops = OpGen(seed, 4).generate(140 + seed)
            val cut = rnd.nextInt(ops.size)
            val full = logOf(ops)
            val stale = allParts(logOf(ops.take(cut))).toMap()
            val fresh = allParts(full).toMap()
            val chosen = ArrayList<CheckpointPart>()
            val used = ArrayList<String>()
            for (shard in SHARD_IDS) {
                val roll = rnd.nextInt(10)
                val bytes = when {
                    roll == 0 -> null // a missing shard
                    roll < 5 -> stale.getValue(shard)
                    else -> fresh.getValue(shard)
                } ?: continue
                chosen.add(decode(shard, bytes))
                used.add(shard)
                if (rnd.nextInt(6) == 0) chosen.add(decode(shard, stale.getValue(shard))) // both generations of a shard
            }
            val log = assertNotNull(OpLog.fromParts(chosen, lib), "seed=$seed")
            for (o in ops.shuffled(rnd)) log.admit(o)
            assertEquals(full.state, log.state, "state seed=$seed used=$used cut=$cut")
            assertEquals(full.versionVector, log.versionVector, "vv seed=$seed")
        }
    }

    @Test
    fun theMinimumCoverageRuleAndAMissingShard() {
        val ops = OpGen(3, 3).generate(120)
        val half = allParts(logOf(ops.take(60))).toMap()
        val full = allParts(logOf(ops)).toMap()
        // every shard fresh: the coverage is the full log's
        val allFresh = SHARD_IDS.map { decode(it, full.getValue(it)) }
        assertEquals(logOf(ops).versionVector.filter { it.value > 0 }, assertNotNull(OpLog.fromParts(allFresh, lib)).versionVector)
        // one stale shard pulls the minimum down to its coverage
        val mixed = SHARD_IDS.map { decode(it, if (it == "7") half.getValue(it) else full.getValue(it)) }
        assertEquals(logOf(ops.take(60)).versionVector, assertNotNull(OpLog.fromParts(mixed, lib)).versionVector)
        // a missing shard: coverage 0
        val missing = SHARD_IDS.filter { it != "a" }.map { decode(it, full.getValue(it)) }
        assertEquals(emptyMap(), assertNotNull(OpLog.fromParts(missing, lib)).versionVector)
        // an equivocation at or below a coverage some part claims makes the parts unusable
        val actor = ops.first().id.actor
        assertNull(OpLog.fromParts(mixed, lib, mapOf(actor to 1L)))
        assertNotNull(OpLog.fromParts(mixed, lib, mapOf(actor to 100000L)))
    }

    @Test
    fun partsOfALogWithAGapMatchTheProjectedCheckpoint() {
        val ops = (1L..6L).map { setField(A, it, it, "field$it", JStr("v$it")) }
        val log = OpLog()
        for (i in listOf(0, 1, 3, 4, 5)) log.admit(ops[i]) // op 3 is missing
        val cp = log.checkpoint(lib)
        val decoded = allParts(log).map { decode(it.first, it.second) }
        for (p in decoded) {
            assertEquals(cp.coverage, p.checkpoint.coverage)
            assertEquals(cp.maxHlc, p.checkpoint.maxHlc)
            assertEquals(projectToShard(cp.state, shardIndexOfName(p.shard)), p.checkpoint.state, p.shard)
        }
        assertEquals(cp.state, assertNotNull(OpLog.fromParts(decoded, lib)).state)
    }

    @Test
    fun onlyChangedShardsAreDirty() {
        val e1 = "e1000000-0000-0000-0000-000000000001"
        val item = "7f000000-0000-4000-8000-000000000001"
        val log = OpLog()
        log.admit(op(A, 1, 1, "item.add", "item" to JStr(item)))
        log.admit(op(A, 2, 2, "playlist.add", "playlist" to JStr(PL)))
        log.admit(op(A, 3, 3, "entry.insert", "playlist" to JStr(PL), "entry" to JStr(e1), "item" to JStr(item), "key" to JStr("a0")))
        assertEquals(SHARD_IDS, log.dirtyShards())
        allParts(log)
        assertEquals(emptyList(), log.dirtyShards())
        log.admit(setField(A, 4, 4, "title", JStr("x")).let { op(A, 4, 4, "item.set", "item" to JStr(item), "field" to JStr("title"), "value" to JStr("x")) })
        assertEquals(listOf("7"), log.dirtyShards())
        allParts(log)
        log.admit(op(A, 5, 5, "entry.move", "entry" to JStr(e1), "key" to JStr("a1")))
        assertEquals(listOf("e"), log.dirtyShards())
        allParts(log)
        log.admit(op(A, 6, 6, "set.add", "set" to JStr("favourites"), "element" to JStr(item)))
        assertEquals(listOf("meta"), log.dirtyShards())
        log.admit(op(A, 7, 7, "item.place", "item" to JStr(item), "place" to JStr(PLACE), "path" to JStr("a.mp3")))
        assertEquals(listOf("7", "meta"), log.dirtyShards())
        // writing a named shard cleans only that one
        assertNotNull(log.partBytes(lib, "7"))
        assertEquals(listOf("meta"), log.dirtyShards())
        assertNull(log.partBytes(lib, "nope"))
    }

    // ---- part strictness ----

    private fun withBody(file: ByteArray, body: ByteArray): ByteArray {
        var n1 = 0
        while (file[n1] != 10.toByte()) n1++
        var n2 = n1 + 1
        while (file[n2] != 10.toByte()) n2++
        val header = file.decodeToString(n1 + 1, n2)
        val old = header.substringAfter("\"sha256\":\"").substringBefore("\"")
        val fixed = header.replace(old, sha256HexReference(body))
        return (file.decodeToString(0, n1 + 1) + fixed + "\n").encodeToByteArray() + body
    }

    private fun bodyOf(file: ByteArray): ByteArray {
        var n = 0
        var lines = 0
        while (lines < 2) {
            if (file[n] == 10.toByte()) lines++
            n++
        }
        return file.copyOfRange(n, file.size)
    }

    private fun sameOutcome(file: ByteArray, label: String): PartResult {
        val fast = decodePart(file, lib)
        val slow = decodePartReference(file, lib)
        when (slow) {
            is PartResult.Ok -> {
                if (fast !is PartResult.Ok) fail("$label: fast rejected what the reference accepts")
                assertEquals(slow.part.shard, fast.part.shard)
                assertEquals(slow.part.checkpoint, fast.part.checkpoint, label)
            }
            is PartResult.Err -> {
                if (fast !is PartResult.Err) fail("$label: fast accepted what the reference rejects (${slow.error})")
                assertEquals(slow.error::class, fast.error::class, label)
            }
        }
        return fast
    }

    @Test
    fun partsAreStrictLikeCheckpoints() {
        val log = logOf(OpGen(21, 3).generate(200))
        val parts = allParts(log).toMap()
        val whole = encodeCheckpoint(log.checkpoint(lib))
        // a whole checkpoint is not a part, and a part is not a whole checkpoint
        assertTrue(decodePart(whole, lib) is PartResult.Err)
        assertTrue(decodeCheckpoint(parts.getValue("meta"), lib) is CheckpointResult.Err)
        assertTrue(decodePart(parts.getValue("meta"), "other") is PartResult.Err)
        // a key in the wrong shard
        val a = parts.getValue("1")
        val b = parts.getValue("2")
        if (bodyOf(b).size > 100) {
            val wrong = withBody(a, bodyOf(b))
            val r = sameOutcome(wrong, "wrong shard")
            assertTrue(r is PartResult.Err)
        }
        // a part name that is not a shard
        val renamed = a.decodeToString().replace("\"part\":\"1\"", "\"part\":\"z\"").encodeToByteArray()
        assertTrue(sameOutcome(renamed, "bad name") is PartResult.Err)
        // damage: the fast route and the reference route decide alike
        val rnd = Random(9)
        val file = parts.getValue("meta")
        val body = bodyOf(file)
        val alphabet = byteArrayOf(32, 34, 44, 58, 91, 93, 123, 125, 92, 48, 49, 45, 97, 117, 0xC3.toByte(), 10, 13)
        var rejected = 0
        for (round in 0 until 400) {
            val bytes = body.toMutableList()
            val at = rnd.nextInt(bytes.size - 1)
            when (rnd.nextInt(4)) {
                0 -> bytes.removeAt(at)
                1 -> bytes[at] = alphabet[rnd.nextInt(alphabet.size)]
                2 -> bytes.add(at, alphabet[rnd.nextInt(alphabet.size)])
                else -> bytes.add(at, bytes[at])
            }
            if (sameOutcome(withBody(file, bytes.toByteArray()), "round $round") is PartResult.Err) rejected++
        }
        assertTrue(rejected > 250, "rejected $rejected")
        assertContentEquals(file, file)
    }
}
