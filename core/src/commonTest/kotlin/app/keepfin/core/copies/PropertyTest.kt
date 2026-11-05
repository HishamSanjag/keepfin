package app.keepfin.core.copies

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * The properties of spec 4.7 on random trees and histories. The test models a library, a main folder, a copy, the
 * copy's versions and trash, and the library's own revision history and trash; it runs the planner, applies each plan
 * with the executor's preconditions asserted (a wrong precondition is a planner bug), and checks the five properties.
 *
 * Assumptions of the model, as in the executor: the library ingests main edits at once and keeps replaced revisions
 * (so main bytes displaced by a revision are not lost); an item.delete moves its main file to the library's trash.
 */

private class FileV(val hash: String, val mtime: Long)

private class MItem(val id: String, var path: String, var head: String, var deleted: Boolean = false)

private val POOL = listOf("m/a.mp3", "m/b.mp3", "m/c.mp3", "m/d.mp3", "v/e.mp4", "v/f.mp4", "x/g.flac", "x/h.flac", "x/i.flac", "j.mp3", "k.mp3", "l.mp3")

private class World(val mode: CopyMode, val rnd: Random) {
    val items = LinkedHashMap<String, MItem>()
    var mainFs = HashMap<String, FileV>()
    var copyFs = HashMap<String, FileV>()
    var baseline = HashMap<String, BaselineEntry>()

    /** every byte version that is kept somewhere other than a live file: copy versions and trash, library history and trash */
    val preserved = ArrayList<String>()
    var moves = ArrayList<PlacementMove>()
    var clock = 1_000_000L
    var hashN = 100
    var itemN = 0
    var generation = 1L

    fun newHash() = sha(++hashN)
    fun tick(): Long {
        clock += 5000
        return clock
    }

    fun itemAt(path: String) = items.values.firstOrNull { !it.deleted && it.path == path }

    fun freePath(): String? = POOL.shuffled(rnd).firstOrNull { it !in mainFs && it !in copyFs && itemAt(it) == null }

    /** the library follows main: a new file is a new item, an edit is a new revision (the old one is kept) */
    fun ingest() {
        for ((path, f) in mainFs) {
            val item = itemAt(path)
            if (item == null) {
                items["i${++itemN}"] = MItem("i$itemN", path, f.hash)
            } else if (item.head != f.hash) {
                preserved.add(item.head)
                item.head = f.hash
            }
        }
    }

    fun randomOp() {
        when (rnd.nextInt(12)) {
            0, 1 -> freePath()?.let { mainFs[it] = FileV(newHash(), tick()); ingest() }
            2 -> mainFs.keys.randomOrNull(rnd)?.let { mainFs[it] = FileV(newHash(), tick()); ingest() }
            3 -> copyFs.keys.randomOrNull(rnd)?.let { copyFs[it] = FileV(newHash(), tick()) }
            4 -> freePath()?.let { copyFs[it] = FileV(newHash(), tick()) }
            5 -> copyFs.keys.randomOrNull(rnd)?.let { copyFs.remove(it) }
            6 -> mainFs.keys.randomOrNull(rnd)?.let { mainFs.remove(it) } // physically gone: the library was not told
            7 -> items.values.filter { !it.deleted }.randomOrNull(rnd)?.let { // item.delete
                preserved.add(mainFs.remove(it.path)?.hash ?: it.head)
                it.deleted = true
            }
            8 -> items.values.filter { !it.deleted && it.path in mainFs }.randomOrNull(rnd)?.let { item -> // a rename in the library
                val to = freePath() ?: return
                mainFs[to] = mainFs.remove(item.path)!!
                moves.add(PlacementMove(item.id, item.path, to))
                item.path = to
            }
            9 -> mainFs.entries.toList().randomOrNull(rnd)?.let { (path, f) -> if (path !in copyFs) copyFs[path] = FileV(f.hash, tick()) } // the user copies a file in by hand
            10 -> items.values.filter { it.deleted }.randomOrNull(rnd)?.let { copyFs[it.path]?.let { _ -> } } // nothing: a deleted item's file lingers on the copy
            else -> mainFs.keys.randomOrNull(rnd)?.let { mainFs[it] = FileV(newHash(), tick()); ingest() }
        }
    }

    // ---- the inventories, as the executor would scan them ----

    fun lines(fs: Map<String, FileV>) = fs.entries.sortedBy { it.key }.map { InvLine(it.key, 100, it.value.mtime) }

    fun input(
        confirmed: Boolean = true,
        mainComplete: Boolean = true,
        copyComplete: Boolean = true,
        gen: Generation = Generation.Equal,
        holdsAll: Boolean = true,
        confirmedDeletes: Set<String> = emptySet(),
    ): PlannerInput {
        val desired = items.values.filter { !it.deleted && (holdsAll || it.id.hashCode() % 2 == 0) }.sortedBy { it.path }
            .map { DesiredEntry(it.path, it.id, "op-${it.id}", it.head, 100) }
        val m = lines(mainFs)
        val c = lines(copyFs)
        val l = baseline.values.sortedBy { it.path }
        val facts = SimpleLibraryFacts(items.values.filter { it.deleted }.map { it.id }.toSet(), moves)
        return PlannerInput(
            mode, PlaceOptions(), NameRules.exact, { desired.iterator() }, { m.iterator() }, { c.iterator() }, { l.iterator() }, facts,
            mainComplete, copyComplete, gen, confirmed, confirmedDeletes,
        )
    }

    fun plan(input: PlannerInput): Plan {
        val known = HashMap<String, String>()
        repeat(6) {
            val evidence = Evidence { _, line ->
                known[line.path]?.takeIf { true }
            }
            when (val o = Planner.plan(input, evidence)) {
                is PlanOutcome.Ready -> return o.plan
                is PlanOutcome.NeedsHashes -> for (r in o.requests) {
                    val f = copyFs[r.path] ?: error("hash asked for a file the copy does not have: ${r.path}")
                    assertEquals(r.mtimeMs, f.mtime, "the request names the stat it saw")
                    known[r.path] = f.hash
                }
            }
        }
        fail("the planner kept asking for hashes")
    }

    // ---- the executor, with its preconditions asserted ----

    fun stats(path: String) = Stat(100, mainFs[path]?.mtime ?: 0) to Stat(100, copyFs[path]?.mtime ?: 0)

    fun commit(path: String, item: String, content: String) {
        val (ms, cs) = stats(path)
        baseline[path] = BaselineEntry(path, item, "op-$item", content, ms, cs, 1_000_000_000L, generation)
    }

    fun describe(): String =
        "items=" + items.values.joinToString { "${it.id}@${it.path}=${it.head.takeLast(3)}${if (it.deleted) "(deleted)" else ""}" } +
            "\n main=" + mainFs.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.hash.takeLast(3)}" } +
            "\n copy=" + copyFs.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.hash.takeLast(3)}" } +
            "\n base=" + baseline.values.sortedBy { it.path }.joinToString { "${it.path}:${it.itemId}=${it.contentId.takeLast(3)}" } +
            "\n moves=$moves"

    fun apply(plan: Plan) {
        val snapshot = describe()
        for (a in plan.actions) {
            try {
                applyOne(a)
            } catch (e: Throwable) {
                throw AssertionError("action $a failed (${e.message})\nplan=${plan.actions.map { it.kind to it.path }}\nbefore:\n$snapshot", e)
            }
        }
        if (plan.actions.isNotEmpty()) generation++
        moves.clear()
    }

    fun applyOne(a: Action) {
        run {
            when (a.kind) {
                ActionKind.CopyTo -> {
                    val src = mainFs[a.path] ?: fail("CopyTo of a file main does not have: ${a.path}")
                    assertEquals(a.contentId, src.hash, "the pinned revision is what main holds")
                    val dest = copyFs[a.path]
                    if (a.expect!!.contentId == null) assertTrue(dest == null, "the destination was expected absent: ${a.path}")
                    else assertEquals(a.expect.contentId, dest?.hash, "the destination is what the plan expected: ${a.path}")
                    if (dest != null && a.archive != null) preserved.add(dest.hash)
                    copyFs[a.path] = FileV(src.hash, tick())
                    commit(a.path, a.itemId!!, src.hash)
                }
                ActionKind.MoveOnCopy -> {
                    val f = copyFs.remove(a.path) ?: fail("move of a file the copy does not have: ${a.path}")
                    assertEquals(a.expect!!.contentId, f.hash)
                    assertTrue(a.toPath!! !in copyFs, "a move never lands on an occupied name")
                    copyFs[a.toPath] = f
                    val old = baseline.remove(a.path)!!
                    commit(a.toPath, old.itemId, old.contentId)
                }
                ActionKind.RemoveFromCopy -> {
                    val f = copyFs[a.path] ?: fail("removal of a file the copy does not have")
                    assertEquals(a.expect!!.contentId, f.hash, "removal expected exactly this content")
                    assertEquals(Archive.Trash, a.archive)
                    preserved.add(f.hash)
                    copyFs.remove(a.path)
                    baseline.remove(a.path)
                    val item = items[a.itemId]
                    when (a.reason) {
                        RemoveReason.ItemDeleted -> assertTrue(item?.deleted == true, "removed for an item.delete that does not exist")
                        RemoveReason.NoLongerHeld -> {
                            assertEquals(CopyMode.Mirror, mode)
                            assertTrue(item?.deleted != true)
                        }
                        else -> fail("a removal needs a reason")
                    }
                }
                ActionKind.ImportNew -> {
                    val f = copyFs[a.path] ?: fail("import of a file the copy does not have: ${a.path}")
                    assertEquals(a.contentId, f.hash)
                    val to = a.toPath ?: a.path
                    assertTrue(to !in mainFs, "an import never replaces a file on main: $to")
                    mainFs[to] = FileV(f.hash, tick())
                    ingest()
                }
                ActionKind.ReviseFromCopy -> {
                    val item = items[a.itemId] ?: fail("revision of an unknown item")
                    val f = copyFs[a.path] ?: copyFs[item.path] ?: fail("revise from a missing copy file")
                    assertEquals(a.contentId, f.hash)
                    val old = mainFs[item.path]
                    if (a.expect != null) assertEquals(a.expect.contentId, old?.hash)
                    if (old != null) preserved.add(old.hash)
                    mainFs[item.path] = FileV(f.hash, tick())
                    item.head = f.hash
                    commit(item.path, item.id, f.hash)
                }
                ActionKind.RestoreFromCopy -> {
                    val item = itemAt(a.path) ?: fail("restore of an unknown item")
                    val f = copyFs[a.path] ?: fail("restore from a missing copy file")
                    assertEquals(a.contentId, f.hash)
                    assertTrue(item.path !in mainFs, "a restore never replaces a main file")
                    mainFs[item.path] = FileV(f.hash, tick())
                    item.head = f.hash
                    commit(item.path, item.id, f.hash)
                }
                ActionKind.RestoreEditedDeleted -> {
                    val item = items[a.itemId] ?: fail("unknown item")
                    val f = copyFs[a.path] ?: fail("missing copy file")
                    assertEquals(a.contentId, f.hash)
                    assertTrue(item.path !in mainFs && itemAt(item.path) == null)
                    item.deleted = false
                    item.head = f.hash
                    mainFs[item.path] = FileV(f.hash, tick())
                    commit(item.path, item.id, f.hash)
                }
                ActionKind.DeleteItem -> {
                    val item = itemAt(a.path) ?: fail("delete of an unknown item")
                    preserved.add(mainFs.remove(item.path)?.hash ?: item.head)
                    item.deleted = true
                }
                ActionKind.Converge -> {
                    val item = itemAt(a.path)!!
                    assertEquals(mainFs[a.path]!!.hash, copyFs[a.path]!!.hash, "converge only when the bytes are equal")
                    commit(a.path, item.id, item.head)
                }
                ActionKind.ForgetBaseline -> baseline.remove(a.path)
            }
        }
    }

    fun versionSet(): Set<String> {
        val s = HashSet<String>()
        mainFs.values.forEach { s.add(it.hash) }
        copyFs.values.forEach { s.add(it.hash) }
        s.addAll(preserved)
        return s
    }
}

private fun isDestructive(a: Action) = when (a.kind) {
    ActionKind.RemoveFromCopy, ActionKind.DeleteItem -> true
    ActionKind.CopyTo, ActionKind.ReviseFromCopy -> a.expect?.contentId != null
    else -> false
}

private fun <T> List<T>.randomOrNull(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]

class PropertyTest {
    private fun modes() = CopyMode.values().toList()

    private fun runWorld(mode: CopyMode, seed: Int, rounds: Int, check: (World, Plan, Set<String>) -> Unit = { _, _, _ -> }) {
        val w = World(mode, Random(seed * 31 + mode.ordinal))
        repeat(2 + w.rnd.nextInt(4)) { w.randomOp() }
        // the first sync of a new relationship only adds; its preview is then confirmed
        val first = w.plan(w.input(confirmed = false))
        assertTrue(first.actions.none { isDestructive(it) }, "a new relationship never destroys ($mode seed $seed)")
        assertTrue(first.additiveOnly)
        w.apply(first)
        repeat(rounds) { round ->
            val before = w.versionSet()
            // property 2, 3: apply, then nothing divergent is gone and every removal is authorised (asserted while applying)
            var plan = w.plan(w.input())
            w.apply(plan)
            // a conflict or a two-item resolution needs a second pass to copy the preserved twin; run until quiet
            var guard = 0
            while (guard++ < 4) {
                val next = w.plan(w.input())
                if (next.actions.isEmpty()) break
                w.apply(next)
                plan = next
            }
            val after = w.versionSet()
            val lost = before - after
            assertTrue(lost.isEmpty(), "byte versions were lost: $lost ($mode seed $seed round $round)")
            // property 1: idempotence
            val again = w.plan(w.input()); assertTrue(again.actions.isEmpty(), "re-planning after a run must be empty ($mode seed $seed round $round): ${again.actions.map { it.kind to it.path }}\n${w.describe()}")
            check(w, plan, before)
            repeat(1 + w.rnd.nextInt(5)) { w.randomOp() }
        }
    }

    @Test
    fun idempotencePreservationAndNoAbsenceDeletes() {
        for (mode in modes()) for (seed in 1..250) runWorld(mode, seed, 4)
    }

    @Test
    fun twoWayConvergesBothWays() {
        for (seed in 1..250) {
            runWorld(CopyMode.TwoWay, seed + 5000, 3) { w, _, _ ->
                // A and B agree on every file they hold (everything is held)
                val a = w.mainFs.mapValues { it.value.hash }
                val b = w.copyFs.mapValues { it.value.hash }
                // files a physically-gone item or a held-back edit left behind are the only differences allowed: both plans must still be empty
                assertTrue(w.plan(w.input()).actions.isEmpty())
                // the same drive synced from the other computer: roles swapped, baseline stats swapped
                val swapped = World(CopyMode.TwoWay, w.rnd)
                swapped.items.putAll(w.items)
                swapped.mainFs = HashMap(w.copyFs)
                swapped.copyFs = HashMap(w.mainFs)
                swapped.baseline = HashMap(w.baseline.mapValues { it.value.copy(main = it.value.copy, copy = it.value.main) })
                val sp = swapped.plan(swapped.input()); assertTrue(sp.actions.none { it.kind != ActionKind.ForgetBaseline }, "the swapped sync is empty too (seed $seed): ${sp.actions.map { it.kind to it.path }}\n${swapped.describe()}")
                if (a.keys.toSet() == b.keys.toSet()) assertEquals(a, b, "equal paths hold equal bytes (seed $seed)")
            }
        }
    }

    @Test
    fun incompleteKnowledgeNeverDestroys() {
        for (mode in modes()) for (seed in 1..200) {
            val w = World(mode, Random(seed * 17 + mode.ordinal))
            repeat(4 + w.rnd.nextInt(6)) { w.randomOp() }
            w.apply(w.plan(w.input()))
            repeat(6) { w.randomOp() }
            val variants = listOf(
                w.input(mainComplete = false), w.input(copyComplete = false), w.input(gen = Generation.Lower), w.input(gen = Generation.Unknown),
            )
            for ((i, input) in variants.withIndex()) {
                val plan = w.plan(input)
                assertTrue(plan.actions.none { isDestructive(it) }, "a destructive action with incomplete knowledge (variant $i, $mode seed $seed)")
            }
            // and nothing a plan with incomplete knowledge does can lose a version either
            val before = w.versionSet()
            w.apply(w.plan(variants[1]))
            assertTrue((before - w.versionSet()).isEmpty())
        }
    }

    @Test
    fun aMirrorOnlyRemovesWhatTheLibraryDeletedOrNoLongerHolds() {
        // property 3 on partial holds: only the removals the executor can justify, and never because a file was merely absent
        for (seed in 1..200) {
            val w = World(CopyMode.Mirror, Random(seed))
            repeat(8) { w.randomOp() }
            val removals = ArrayList<Action>()
            repeat(3) {
                val plan = w.plan(w.input(holdsAll = it != 1))
                removals += plan.actions.filter { a -> a.kind == ActionKind.RemoveFromCopy }
                w.apply(plan)
                repeat(3) { w.randomOp() }
            }
            for (r in removals) assertTrue(r.reason == RemoveReason.ItemDeleted || r.reason == RemoveReason.NoLongerHeld)
        }
    }
}
