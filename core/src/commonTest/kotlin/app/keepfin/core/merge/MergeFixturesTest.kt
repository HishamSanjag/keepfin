package app.keepfin.core.merge

import app.keepfin.core.*
import app.keepfin.core.library.*
import app.keepfin.core.sync.*
import kotlin.test.*

class MergeFixturesTest {
    private fun obj(v: Jv) = MergeWire.obj(v)
    private fun arr(m: Map<String, Jv>, key: String): List<Jv> = (m[key] as? JArr)?.items ?: emptyList()
    private fun text(m: Map<String, Jv>, key: String) = MergeWire.str(m, key)
    private fun number(m: Map<String, Jv>, key: String) = MergeWire.num(m, key)
    private fun str(v: Jv) = (v as JStr).value
    private fun fixture(name: String): List<Map<String, Jv>> {
        val root = requireFixturesRoot().replace('\\', '/').substringBeforeLast('/') + "/merge"
        return arr(obj(MergeWire.parse(readTextFile(root + "/" + name + ".json"))), "cases").map { obj(it) }
    }

    private data class Run(val result: CollectedMerge, val source: List<SItem>, val target: List<TItem>, val config: MergeConfig)
    private fun run(c: Map<String, Jv>, pageSizeOverride: Int? = null): Run {
        val config = MergeWire.config(c.getValue("config"))
        var source = arr(c, "source").map { MergeWire.source(it) }
        val target = arr(c, "target").map { MergeWire.target(it) }
        val names = arr(c, "names").groupBy { val a = (it as JArr).items; str(a[0]) to str(a[1]) }
            .mapValues { (_, rows) -> rows.map { str((it as JArr).items[2]) } }
        val out = CollectedMerge()
        val ledger = InMemoryMergeLedger()
        val planner = MergePlanner(config, InMemoryTHashIndex(target, names), ledger, out)
        val expected = obj(c.getValue("expected"))
        val size = pageSizeOverride ?: number(c, "pageSize")?.toInt() ?: 200
        var needed = 0
        val reverse = c["reverse"] as? JObj
        for (page in source.chunked(size)) {
            fun accept(p: List<SItem>) = if (reverse == null) planner.items(p) else
                planner.reverseItems(p, text(reverse.members, "bActorInT")!!, number(reverse.members, "activationSeq")!!)
            val first = accept(page)
            if (!first.accepted) {
                needed += first.needsHash.size
                assertTrue(out.map.none { line -> page.any { it.id == line.s } }, "hash request emitted effects")
                val hashes = obj(c.getValue("rehash"))
                val corrected = page.map { s -> hashes[s.id]?.let { s.copy(file = s.file.copy(hash = str(it))) } ?: s }
                assertTrue(accept(corrected).accepted)
                source = source.map { s -> corrected.firstOrNull { it.id == s.id } ?: s }
            }
        }
        assertEquals(number(expected, "needsHash") ?: 0L, needed.toLong())
        val metadata = c["metadata"]
        if (metadata != null) {
            val model = MergeWire.metadata(metadata)
            planner.spaces(model.spaces)
            val rules = arr(obj(metadata), "playlists").associate {
                val row = obj(it); text(row, "id")!! to obj(row.getValue("fields"))["rules"]
            }
            planner.playlists(model.collections) { rules[it] }
        }
        planner.artwork(arr(c, "art").map { obj(it).let { m ->
            ArtFile(text(m, "ownerKind")!!, text(m, "ownerId")!!, text(m, "source")!!, text(m, "name")!!)
        } })
        planner.finish()
        assertEquals(arr(expected, "map").map { encodeCanonical(it) }, out.map.map { it.json() }, text(c, "name"))
        return Run(out, source, target, config)
    }

    private fun apply(log: OpLog, drafts: List<OpDraft>, actor: String, start: Long = 1, wall: Long = 100_000): Long {
        var seq = start
        for (d in drafts) {
            val op = Op(OpId(actor, seq), Hlc(wall + seq, 0, actor), d.kind, d.body)
            val admitted = log.admit(op.encode())
            assertTrue(admitted is Admission.Admitted, d.kind + ": " + admitted)
            seq++
        }
        return seq
    }

    private fun view(run: Run, replay: Boolean = false): Map<String, Jv> {
        val log = OpLog()
        val baseActor = "10000000-0000-4000-8000-000000000999"
        var seq = 1L
        for (t in run.target) {
            val item = t.fields
            val ds = LibraryMapping.diff(null, LibModel(listOf(item), emptyList(), emptyList(),
                LibRoutes("music", "downloads")), DiffContext(baseActor, run.config.replicaId, { error("no entries") },
                { 0 }, { emptyList() }, RecordingObserved()))
            seq = apply(log, ds, baseActor, seq, 1)
        }
        val drafts = run.result.batches.flatMap { it.drafts }
        val end = apply(log, drafts, run.config.actor)
        if (replay) apply(log, drafts, run.config.actor, end)
        return obj(MergeWire.parse(log.viewsJson()))
    }

    @Test fun generatedFixturesOnBothTargets() {
        for (c in fixture("planner")) {
            val name = text(c, "name")!!
            val run = run(c)
            val out = run.result
            val expected = obj(c.getValue("expected"))
            val drafts = out.batches.flatMap { it.drafts }
            number(expected, "drafts")?.let { assertEquals(it, drafts.size.toLong(), name) }
            assertTrue(out.batches.all { it.drafts.size <= 500 })
            assertTrue(drafts.filter { it.kind == "item.place" }.all { it.body["place"] == JStr(run.config.replicaId) })
            assertTrue(drafts.filter { (it.body["field"] as? JStr)?.value?.startsWith("plays@") == true }
                .all { it.body["field"] == JStr("plays@" + run.config.actor) })
            assertTrue(drafts.none { it.kind == "profile.set" || it.kind.endsWith(".delete") }, name)
            if ("reviewKinds" in expected) assertEquals(arr(expected, "reviewKinds").map { str(it) },
                out.review.map { it.kind.wire }, name)
            if ("alternatives" in expected) assertEquals(arr(expected, "alternatives").map { str(it) },
                out.review.filter { it.kind == ReviewKind.ManyMatches }.map { it.related }, name)
            if (expected["noPlacements"] == JBool(true)) assertTrue(drafts.none { it.kind == "item.place" }, name)
            text(expected, "draftsJson")?.let {
                assertEquals(it, draftsJson(drafts), name)
                assertEquals(text(expected, "draftsHash"), out.batches.single().draftsHash, name)
            }
            if ("batchSizes" in expected) assertEquals(arr(expected, "batchSizes").map { (it as JNum).value },
                out.batches.map { it.drafts.size.toLong() }, name)
            val views = view(run)
            val rows = arr(views, "items")
            if (listOf("plays", "lastPlayedAt", "position", "watched", "keptTitle").any { it in expected }) {
                val item = obj(rows.single())
                val fields = obj(item.getValue("fields"))
                for (field in listOf("lastPlayedAt", "position", "watched"))
                    expected[field]?.let { assertEquals(it, fields[field], name + " " + field) }
                expected["plays"]?.let {
                    assertEquals(it, item["plays"], name)
                    val replayed = obj(arr(view(run, replay = true), "items").single())
                    assertEquals(it, replayed["plays"], "play count imported twice")
                    val counter = drafts.single { d -> d.body["field"] == JStr("plays@" + run.config.actor) }
                    assertEquals(expected["ownPlays"], counter.body["value"])
                }
                expected["keptTitle"]?.let { assertEquals(it, fields["title"], name) }
                if ("differing" in expected) assertEquals(arr(expected, "differing").map { str(it) }.toSet(),
                    out.review.filter { it.kind == ReviewKind.DifferingField }.map { it.field }.toSet(), name)
                if ("setTags" in expected) {
                    val id = out.map.single().t
                    val tags = obj(views.getValue("sets"))["tags:" + id]
                    assertEquals(expected["setTags"], tags)
                }
            }
            if ("playlist" in expected) {
                val playlist = text(expected, "playlist")!!
                val space = text(expected, "space")!!
                val pd = drafts.single { it.kind == "playlist.add" }
                assertEquals(JStr(playlist), pd.body["playlist"])
                val pf = obj(pd.body.getValue("fields"))
                assertEquals(JStr(out.map.first().t), pf["coverItemId"])
                assertEquals(JStr(space), pf["space"])
                assertEquals(expected["entries"], JArr(drafts.filter { it.kind == "entry.insert" }.map { it.body.getValue("item") }))
                assertTrue(pf["follow"] is JObj && pf["source"] is JObj)
                val sf = obj(drafts.single { it.kind == "space.add" }.body.getValue("fields"))
                assertEquals(JNum(3), sf["order"])
                val rules = obj(sf.getValue("rules"))
                val rr = arr(rules, "rules").map { obj(it) }
                assertEquals(JArr(listOf(JStr(out.map.first().t))), rr[0]["values"])
                assertEquals(JArr(listOf(JStr(playlist))), rr[1]["values"])
                assertEquals(JArr(listOf(JStr(out.map.first().s))), rr[2]["values"], "artist text must not remap")
                assertEquals(2, out.art.size)
                assertTrue(out.art.all { it.target.startsWith("art/") })
                val sets = obj(views.getValue("sets"))
                assertEquals(JArr(listOf(JStr("external-source-id"))), sets["seen:" + playlist])
                assertEquals(JArr(listOf(JStr(out.map[0].t))), sets["new:" + playlist])
                assertEquals(JArr(listOf(JStr(out.map[1].t))), sets["removed:" + playlist])
            }
            // Repeat with different page boundaries: canonical drafts, hashes, maps and review must stay identical.
            val twice = run(c, 1).result
            assertEquals(out.batches.map { MergeWire.batch(it) }, twice.batches.map { MergeWire.batch(it) }, name)
            assertEquals(out.map, twice.map, name)
            assertEquals(out.review, twice.review, name)
            assertEquals(out.art, twice.art, name)
        }
    }

    @Test fun recoveryEveryPrefixIncludingZeroAndFull() {
        val c = fixture("planner").first { text(it, "name")!!.startsWith("batch boundary") }
        for (batch in run(c).result.batches) {
            for (m in 0..batch.drafts.size) {
                assertEquals(batch.drafts, batch.drafts.take(m) + remainingDrafts(batch, m))
            }
            assertFailsWith<IllegalArgumentException> { remainingDrafts(batch, -1) }
            assertFailsWith<IllegalArgumentException> { remainingDrafts(batch, batch.drafts.size + 1) }
            assertFailsWith<IllegalArgumentException> { Batch(batch.index, batch.drafts, "wrong receipt") }
        }
    }

    @Test fun separateUsesCurrentPlacementsAndExplicitChoices() {
        for (c in fixture("separate")) {
            val config = MergeWire.config(c.getValue("config"))
            val targets = arr(c, "target").map { MergeWire.target(it) }
            val out = CollectedMerge()
            val observed = object : ObservedLookup {
                override fun item(id: String) = ObservedTags(listOf(config.actor + ":1"), listOf(config.actor + ":2"))
                override fun playlist(id: String): List<String> = emptyList()
                override fun space(id: String): List<String> = emptyList()
                override fun member(set: String, element: String): List<String> = emptyList()
            }
            val planner = SeparatePlanner(config.replicaId, InMemoryTHashIndex(targets), observed, out)
            val choices = obj(c.getValue("choices"))
            planner.items(arr(c, "map").map { MergeWire.map(it) }) {
                if (choices[it] == JStr("Remove")) KeepChoice.Remove else KeepChoice.Keep
            }
            planner.finish()
            val drafts = out.batches.flatMap { it.drafts }
            val expected = obj(c.getValue("expected"))
            assertEquals(expected["deletes"], JArr(drafts.filter { it.kind == "item.delete" }.map { it.body.getValue("item") }))
            assertEquals(expected["clears"], JArr(drafts.filter { it.kind == "item.place" }.map { it.body.getValue("item") }))
            assertTrue(drafts.filter { it.kind == "item.place" }.all { it.body["path"] == JNull })
            assertTrue(drafts.filter { it.kind == "item.delete" }.all { (it.body["observedHeads"] as JArr).items.isNotEmpty() })
        }
    }

    @Test fun pageValidationRejectsOutOfOrderAndInvalidPlacementBeforeEffects() {
        val c = fixture("planner").first { text(it, "name") == "empty T" }
        val s = MergeWire.source(arr(c, "source").single())
        val out = CollectedMerge()
        val p = MergePlanner(MergeWire.config(c.getValue("config")), InMemoryTHashIndex(emptyList()), InMemoryMergeLedger(), out)
        assertFailsWith<IllegalArgumentException> { p.items(listOf(s, s)) }
        assertFailsWith<IllegalArgumentException> { p.items(listOf(s.copy(pathOnB = "../escape"))) }
        assertTrue(out.map.isEmpty())
        assertTrue(p.items(listOf(s)).accepted)
        assertFailsWith<IllegalArgumentException> { p.items(listOf(s)) }
        p.finish()
        assertFailsWith<IllegalStateException> { p.items(emptyList()) }
    }

    @Test fun metadataUnionUsesTheSameMapping() {
        val c = fixture("planner").first { text(it, "name") == "empty T" }
        val s = MergeWire.source(arr(c, "source").single())
        val out = CollectedMerge()
        val cfg = MergeWire.config(c.getValue("config"))
        val p = MergePlanner(cfg, InMemoryTHashIndex(emptyList()), InMemoryMergeLedger(), out)
        p.items(listOf(s)) { SMetadata(true, listOf("tag"), 5.0, 200.0, 12.5, true) }
        p.finish()
        val views = view(Run(out, listOf(s), emptyList(), cfg))
        val row = obj(arr(views, "items").single())
        assertEquals(JNum(5), row["plays"])
        assertEquals(JNum(12500), obj(row.getValue("fields"))["position"])
        assertEquals(JArr(listOf(JStr(out.map.single().t))), obj(views.getValue("sets"))["favourites"])
    }
}
