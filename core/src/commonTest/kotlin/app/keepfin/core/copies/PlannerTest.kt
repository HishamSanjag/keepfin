package app.keepfin.core.copies

import app.keepfin.core.sync.A
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.PLACE
import app.keepfin.core.sync.add
import app.keepfin.core.sync.jObj
import app.keepfin.core.sync.logOf
import app.keepfin.core.sync.op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A relationship written as lists (sorted by the caller). */
internal class Rel(
    val mode: CopyMode = CopyMode.TwoWay,
    val d: List<DesiredEntry> = emptyList(),
    val m: List<InvLine> = emptyList(),
    val c: List<InvLine> = emptyList(),
    val l: List<BaselineEntry> = emptyList(),
    val copyHashes: Map<String, String> = emptyMap(),
    val library: LibraryFacts = SimpleLibraryFacts(),
    val rules: NameRules = NameRules.exact,
    val tweak: (PlannerInput) -> PlannerInput = { it },
) {
    fun input(
        previewConfirmed: Boolean = true,
        mainComplete: Boolean = true,
        copyComplete: Boolean = true,
        generation: Generation = Generation.Equal,
        caps: Capabilities = Capabilities.folder,
        options: PlaceOptions = PlaceOptions(),
    ) = tweak(
        PlannerInput(
            mode, options, rules, { d.iterator() }, { m.iterator() }, { c.iterator() }, { l.iterator() }, library,
            mainComplete, copyComplete, generation, previewConfirmed, emptySet(), caps,
        ),
    )

    fun plan(
        previewConfirmed: Boolean = true,
        mainComplete: Boolean = true,
        copyComplete: Boolean = true,
        generation: Generation = Generation.Equal,
        caps: Capabilities = Capabilities.folder,
        options: PlaceOptions = PlaceOptions(),
    ): Plan {
        val input = input(previewConfirmed, mainComplete, copyComplete, generation, caps, options)
        // the executor's part: hash what is asked. A copy file that still looks like the baseline's has the baseline's bytes.
        val evidence = Evidence { _, line ->
            copyHashes[line.path] ?: l.firstOrNull { it.copy.size == line.size && it.copy.mtimeMs == line.mtimeMs }?.contentId
        }
        return when (val o = Planner.plan(input, evidence)) {
            is PlanOutcome.Ready -> o.plan
            is PlanOutcome.NeedsHashes -> fail("hashes needed: ${o.requests}")
        }
    }
}

private fun entry(path: String, item: String, content: String, mainStat: Stat = Stat(100, 1_000_000), copyStat: Stat = Stat(100, 1_000_000)) =
    BaselineEntry(path, item, "op-$item", content, mainStat, copyStat, 90_000_000, 1)

private fun want(path: String, item: String, content: String) = DesiredEntry(path, item, "op-$item", content, 100)
private fun line(path: String, mtime: Long = 1_000_000, size: Long = 100) = InvLine(path, size, mtime)
private fun kinds(p: Plan) = p.actions.map { it.kind }

class PlannerTest {
    // ---- moves (spec 4.5) ----

    @Test
    fun aPlacementMoveBecomesARenameOnTheCopy() {
        val rel = Rel(
            d = listOf(want("B.mp3", "x", sha(1))), m = listOf(line("B.mp3")), c = listOf(line("A.mp3")), l = listOf(entry("A.mp3", "x", sha(1))),
            library = SimpleLibraryFacts(placementMoves = listOf(PlacementMove("x", "A.mp3", "B.mp3"))),
        )
        val p = rel.plan()
        assertEquals(listOf(ActionKind.MoveOnCopy), kinds(p))
        assertEquals("A.mp3" to "B.mp3", p.actions[0].path to p.actions[0].toPath)
        assertEquals(sha(1), p.actions[0].expect!!.contentId)
    }

    @Test
    fun renamePlusEditIsMoveThenCopy() {
        val rel = Rel(
            d = listOf(want("B.mp3", "x", sha(2))), m = listOf(line("B.mp3")), c = listOf(line("A.mp3")), l = listOf(entry("A.mp3", "x", sha(1))),
            library = SimpleLibraryFacts(placementMoves = listOf(PlacementMove("x", "A.mp3", "B.mp3"))),
        )
        val p = rel.plan()
        assertEquals(listOf(ActionKind.MoveOnCopy, ActionKind.CopyTo), kinds(p))
        assertEquals(sha(1), p.actions[1].expect!!.contentId) // the renamed file is replaced
    }

    @Test
    fun aMoveByContentIsTakenOnlyWhenUnambiguous() {
        // main lost A.mp3 and gained B.mp3 with the same bytes; the library recorded no placement change
        fun rel(extraLost: Boolean): Rel {
            val d = mutableListOf(want("B.mp3", "x", sha(1)))
            val m = mutableListOf(line("B.mp3"))
            val c = mutableListOf(line("A.mp3"))
            val l = mutableListOf(entry("A.mp3", "x", sha(1)))
            if (extraLost) { // a second file with the same bytes also vanished from main: now two candidates
                c.add(line("A2.mp3"))
                l.add(entry("A2.mp3", "y", sha(1)))
            }
            return Rel(d = d, m = m, c = c.sortedBy { it.path }, l = l.sortedBy { it.path })
        }
        val one = rel(false).plan()
        assertEquals(listOf(ActionKind.MoveOnCopy), kinds(one))
        val two = rel(true).plan()
        assertTrue(ActionKind.MoveOnCopy !in kinds(two), "an ambiguous match is not a move")
        assertEquals(listOf(ActionKind.CopyTo), kinds(two)) // B.mp3 is copied; the old files stay (not held, listed)
        assertEquals(2, two.review.count { it.kind == ReviewKind.NotHeld })
    }

    @Test
    fun withoutStableIdsOnlyLibraryPlacementsMove() {
        val rel = Rel(d = listOf(want("B.mp3", "x", sha(1))), m = listOf(line("B.mp3")), c = listOf(line("A.mp3")), l = listOf(entry("A.mp3", "x", sha(1))))
        assertTrue(ActionKind.MoveOnCopy !in kinds(rel.plan(caps = Capabilities(stableIds = false))))
        assertEquals(listOf(ActionKind.MoveOnCopy), kinds(rel.plan()))
    }

    @Test
    fun aMoveOntoAnOccupiedNameIsNotAMove() {
        val rel = Rel(
            d = listOf(want("B.mp3", "x", sha(1))), m = listOf(line("B.mp3")), c = listOf(line("A.mp3"), line("B.mp3", 5_000_000)), l = listOf(entry("A.mp3", "x", sha(1))),
            copyHashes = mapOf("B.mp3" to sha(9)),
            library = SimpleLibraryFacts(placementMoves = listOf(PlacementMove("x", "A.mp3", "B.mp3"))),
        )
        assertTrue(ActionKind.MoveOnCopy !in kinds(rel.plan()))
    }

    @Test
    fun aMoveNeedsTheCopyFileToStillBeTheBaselineBytes() {
        val rel = Rel(
            d = listOf(want("B.mp3", "x", sha(1))), m = listOf(line("B.mp3")), c = listOf(line("A.mp3", 7_000_000)), l = listOf(entry("A.mp3", "x", sha(1))),
            copyHashes = mapOf("A.mp3" to sha(5)), // edited on the copy, timestamp changed: hash differs
            library = SimpleLibraryFacts(placementMoves = listOf(PlacementMove("x", "A.mp3", "B.mp3"))),
        )
        val p = rel.plan()
        assertTrue(ActionKind.MoveOnCopy !in kinds(p))
        assertTrue(ActionKind.ImportNew in kinds(p) || ActionKind.RemoveFromCopy in kinds(p) || p.review.isNotEmpty())
    }

    // ---- names (spec 3 and 5.4) ----

    @Test
    fun collidingNamesAreReportedAndNeverMerged() {
        val rel = Rel(
            rules = NameRules.caseInsensitiveAscii,
            d = listOf(want("a.mp3", "x", sha(1)), want("A.mp3", "y", sha(2))).sortedWith(compareBy({ NameRules.caseInsensitiveAscii.key(it.path) }, { it.path })),
            m = listOf(line("A.mp3"), line("a.mp3")).sortedBy { it.path },
        )
        val p = rel.plan()
        assertEquals(emptyList(), kinds(p))
        assertTrue(p.review.any { it.kind == ReviewKind.NameCollision })
    }

    @Test
    fun namesThatDifferOnlyInCaseAreOnePositionOnACaseInsensitiveDestination() {
        // the copy spells it differently: it is the same name there, so it is the same file and nothing is copied
        val rel = Rel(
            rules = NameRules.caseInsensitiveAscii,
            d = listOf(want("Song.mp3", "x", sha(1))), m = listOf(line("Song.mp3")), c = listOf(line("song.mp3")),
            copyHashes = mapOf("song.mp3" to sha(1)),
        )
        val p = rel.plan()
        assertEquals(listOf(ActionKind.Converge), kinds(p))
        assertTrue(p.review.none { it.kind == ReviewKind.NameCollision })
        // on a case-sensitive destination they are two files
        val exact = Rel(d = rel.d, m = rel.m, c = rel.c, copyHashes = rel.copyHashes).plan()
        assertTrue(ActionKind.CopyTo in kinds(exact))
    }

    @Test
    fun anUnsortedStreamIsRefused() {
        val rel = Rel(m = listOf(line("b.mp3"), line("a.mp3")))
        assertFailsWith<IllegalArgumentException> { rel.plan() }
    }

    // ---- the guard, and incomplete knowledge (spec 4.6, principle 5) ----

    private fun mirrorWithRemovals(files: Int, removals: Int): Rel {
        val paths = (0 until files).map { "f" + it.toString().padStart(6, '0') + ".mp3" }
        val kept = paths.drop(removals)
        return Rel(
            mode = CopyMode.Mirror,
            d = kept.map { want(it, "i$it", sha(1)) },
            m = kept.map { line(it) },
            c = paths.map { line(it) },
            l = paths.map { entry(it, "i$it", sha(1)) },
            library = SimpleLibraryFacts(paths.take(removals).map { "i$it" }.toSet()),
            copyHashes = emptyMap(),
        )
    }

    @Test
    fun theGuardHoldsLargeShares() {
        // 1,000 files, the default 10%: 100 removals pass, 101 are held
        assertTrue(!mirrorWithRemovals(1000, 100).plan().held)
        assertTrue(mirrorWithRemovals(1000, 101).plan().held)
        // the percentage is the place's
        assertTrue(mirrorWithRemovals(1000, 60).plan(options = PlaceOptions(deleteGuardPercent = 5)).held)
    }

    @Test
    fun theGuardOnSmallCopies() {
        // small copies: never more than half of them, however few
        assertTrue(!mirrorWithRemovals(10, 5).plan().held)
        assertTrue(mirrorWithRemovals(10, 6).plan().held)
        assertTrue(mirrorWithRemovals(3, 2).plan().held)
        assertTrue(!mirrorWithRemovals(3, 1).plan().held)
        // and the 20-action floor: 20 of 100 is over 10% but under the floor, and under half
        assertTrue(!mirrorWithRemovals(100, 20).plan().held)
        assertTrue(mirrorWithRemovals(100, 21).plan().held)
    }

    @Test
    fun theGuardCountsReplacementsToo() {
        // 12 files, 7 of which main changed: 7 replacements are more than half of the copy
        val paths = (0 until 12).map { "f$it.mp3".replace("f", "f0") }.sorted()
        val rel = Rel(
            mode = CopyMode.Backup,
            d = paths.mapIndexed { i, p -> want(p, "i$i", if (i < 7) sha(2) else sha(1)) },
            m = paths.map { line(it) },
            c = paths.map { line(it) },
            l = paths.mapIndexed { i, p -> entry(p, "i$i", sha(1)) },
            copyHashes = paths.associateWith { sha(1) },
        )
        val p = rel.plan()
        assertEquals(7, p.summary.destructive)
        assertTrue(p.held)
    }

    @Test
    fun incompleteKnowledgeSuspendsEveryDestructiveAction() {
        val rel = mirrorWithRemovals(50, 3)
        assertEquals(3, rel.plan().actions.size)
        for ((label, plan) in listOf(
            "main inventory" to rel.plan(mainComplete = false),
            "copy inventory" to rel.plan(copyComplete = false),
            "lower generation" to rel.plan(generation = Generation.Lower),
            "unknown generation" to rel.plan(generation = Generation.Unknown),
            "no complete listing" to rel.plan(caps = Capabilities(completeEnumeration = false)),
        )) {
            assertEquals(emptyList(), plan.actions, label)
            assertEquals(3, plan.review.count { it.kind == ReviewKind.Suspended }, label)
        }
    }

    @Test
    fun additiveWorkStillHappensWhenKnowledgeIsIncomplete() {
        val rel = Rel(mode = CopyMode.Mirror, d = listOf(want("n.mp3", "n", sha(1))), m = listOf(line("n.mp3")))
        assertEquals(listOf(ActionKind.CopyTo), kinds(rel.plan(copyComplete = false)))
    }

    @Test
    fun aHigherGenerationAndANewRelationshipOnlyAdd() {
        // an existing relationship whose copy was changed in between by another computer
        val rel = Rel(
            mode = CopyMode.Mirror,
            d = listOf(want("a.mp3", "a", sha(2)), want("n.mp3", "n", sha(1))),
            m = listOf(line("a.mp3"), line("n.mp3")),
            c = listOf(line("a.mp3")),
            l = listOf(entry("a.mp3", "a", sha(1))),
            copyHashes = mapOf("a.mp3" to sha(1)),
        )
        val p = rel.plan(generation = Generation.Higher)
        assertEquals(listOf(ActionKind.CopyTo), kinds(p))
        assertEquals("n.mp3", p.actions[0].path)
        assertTrue(p.additiveOnly)
        assertEquals(listOf(ReviewKind.Difference), p.review.map { it.kind })
        // a new relationship (no baseline): copies what is missing, lists the rest, until the preview is confirmed
        val fresh = Rel(
            mode = CopyMode.Mirror,
            d = listOf(want("a.mp3", "a", sha(2)), want("n.mp3", "n", sha(1))),
            m = listOf(line("a.mp3"), line("n.mp3")),
            c = listOf(line("a.mp3", 9)),
            copyHashes = mapOf("a.mp3" to sha(7)),
        )
        val first = fresh.plan(previewConfirmed = false)
        assertEquals(listOf(ActionKind.CopyTo), kinds(first))
        assertTrue(first.additiveOnly)
        assertEquals(listOf(ReviewKind.Difference), first.review.map { it.kind })
        val confirmed = fresh.plan(previewConfirmed = true)
        assertEquals(2, confirmed.actions.size)
        assertTrue(!confirmed.additiveOnly)
    }

    @Test
    fun theFreeSpaceCheckCoversStagingAndDisplacedBytes() {
        val rel = Rel(
            mode = CopyMode.Backup,
            d = listOf(want("a.mp3", "a", sha(2)).copy(bytes = 500), want("n.mp3", "n", sha(1)).copy(bytes = 300)),
            m = listOf(line("a.mp3"), line("n.mp3")),
            c = listOf(line("a.mp3", size = 200)),
            l = listOf(entry("a.mp3", "a", sha(1))),
            copyHashes = mapOf("a.mp3" to sha(1)),
            tweak = { PlannerInput(it.mode, it.options, it.rules, it.desired, it.main, it.copy, it.baseline, it.library, freeCopy = 900) },
        )
        val p = rel.plan()
        // 500 + 300 staged, 200 displaced into versions
        assertEquals(1000, p.summary.copySpace.needed)
        assertEquals(false, p.summary.copySpace.enough)
        assertEquals(mapOf(ActionKind.CopyTo to 2), p.summary.counts)
        assertEquals(800L, p.summary.bytes[ActionKind.CopyTo])
    }

    // ---- hashes ----

    @Test
    fun aTimestampWithinTheGranularityOfTheLastScanIsHashed() {
        val c = InvLine("a.mp3", 100, 90_000_500) // equal to the baseline's stat but written 0.5 s before the scan
        val rel = Rel(
            d = listOf(want("a.mp3", "a", sha(1))), m = listOf(line("a.mp3", 90_000_500)), c = listOf(c),
            l = listOf(BaselineEntry("a.mp3", "a", "op", sha(1), Stat(100, 90_000_500), Stat(100, 90_000_500), 90_000_000, 1)),
        )
        val o = Planner.plan(rel.input(), Evidence.NONE)
        assertTrue(o is PlanOutcome.NeedsHashes && o.requests.single().path == "a.mp3")
        // a finer file system (granularity 1 ms) trusts the stat
        val fine = Rel(
            d = rel.d, m = rel.m, c = rel.c, l = rel.l,
            tweak = { PlannerInput(it.mode, it.options, it.rules, it.desired, it.main, it.copy, it.baseline, it.library, timestampGranularityMs = 1) },
        )
        assertTrue(Planner.plan(fine.input(), Evidence.NONE) is PlanOutcome.Ready)
    }

    @Test
    fun aFullAuditHashesEveryFile() {
        val rel = Rel(
            d = listOf(want("a.mp3", "a", sha(1))), m = listOf(line("a.mp3")), c = listOf(line("a.mp3")), l = listOf(entry("a.mp3", "a", sha(1))),
            tweak = { PlannerInput(it.mode, it.options, it.rules, it.desired, it.main, it.copy, it.baseline, it.library, audit = true) },
        )
        assertTrue(Planner.plan(rel.input(), Evidence.NONE) is PlanOutcome.NeedsHashes)
        // the audit finds a timestamp-preserving edit
        val edited = Planner.plan(rel.input(), Evidence { _, _ -> sha(8) })
        assertEquals(listOf(ActionKind.ReviseFromCopy), kinds((edited as PlanOutcome.Ready).plan))
    }

    // ---- the desired set and the place (spec 2) ----

    @Test
    fun theDesiredSetFollowsHoldsPlacementsAndConflictHeads() {
        val replica = "99999999-9999-9999-9999-999999999999"
        val i1 = "11111111-1111-1111-1111-111111111111"
        val i2 = "22222222-2222-2222-2222-222222222222"
        val i3 = "33333333-3333-3333-3333-333333333333"
        val ops = listOf(
            add(A, 1, fields = mapOf("media" to JStr("audio"), "space" to JStr("music"))).let { it.copy(body = it.body + ("item" to JStr(i1))) },
            op(A, 2, 2, "item.add", "item" to JStr(i2), "fields" to jObj("space" to JStr("video"))),
            op(A, 3, 3, "item.add", "item" to JStr(i3)),
            op(A, 4, 4, "item.place", "item" to JStr(i1), "place" to JStr(replica), "path" to JStr("Music/one.mp3")),
            op(A, 5, 5, "item.place", "item" to JStr(i2), "place" to JStr(replica), "path" to JStr("Video/two.mp4")),
            op(A, 6, 6, "item.place", "item" to JStr(i3), "place" to JStr(replica), "path" to JStr("Music/three.mp3")),
            op(A, 7, 7, "set.add", "set" to JStr("favourites"), "element" to JStr(i3)),
            op(A, 8, 8, "item.revise", "item" to JStr(i1), "content" to JStr(sha(11)), "size" to JNum(11), "parents" to JArr(emptyList())),
            op(A, 9, 9, "item.revise", "item" to JStr(i1), "content" to JStr(sha(12)), "size" to JNum(12), "parents" to JArr(emptyList())), // concurrent: two heads
            op(A, 10, 10, "item.revise", "item" to JStr(i2), "content" to JStr(sha(21)), "size" to JNum(21), "parents" to JArr(emptyList())),
            op(A, 11, 11, "item.revise", "item" to JStr(i3), "content" to JStr(sha(31)), "size" to JNum(31), "parents" to JArr(emptyList())),
        )
        val state = logOf(ops).state
        val spaces = desiredSetFrom(state, replica, Holds(spaces = setOf("music")))
        assertEquals(listOf("Music/one (conflict ${sha(12).substringAfter(':').take(8)}).mp3", "Music/one.mp3").sorted(), spaces.entries.map { it.path })
        assertEquals(2, spaces.entries.count { it.itemId == i1 })
        val favourites = desiredSetFrom(state, replica, Holds(favourites = true))
        assertEquals(listOf(i3), favourites.entries.map { it.itemId }.distinct())
        val everything = desiredSetFrom(state, replica, Holds(all = true))
        assertEquals(setOf(i1, i2, i3), everything.entries.map { it.itemId }.toSet())
        assertEquals(everything.entries.map { it.path }.sorted(), everything.entries.map { it.path })
        // an item without a path on the main folder cannot be copied yet
        assertEquals(listOf(i1, i2, i3), desiredSetFrom(state, "88888888-8888-8888-8888-888888888888", Holds(all = true)).withoutPath)
        assertEquals(emptyList(), desiredSetFrom(state, replica, Holds()).entries)
    }

    @Test
    fun aPlaceIsReadFromItsRegisters() {
        val ops = listOf(
            op(A, 1, 1, "place.set", "place" to JStr(PLACE), "field" to JStr("mode"), "value" to JStr("backup")),
            op(A, 2, 2, "place.set", "place" to JStr(PLACE), "field" to JStr("name"), "value" to JStr("Backup drive")),
            op(A, 3, 3, "place.set", "place" to JStr(PLACE), "field" to JStr("holds"), "value" to jObj("spaces" to JArr(listOf(JStr("music"))), "favourites" to JBool(true))),
            op(A, 4, 4, "place.set", "place" to JStr(PLACE), "field" to JStr("options"), "value" to jObj("deleteGuardPercent" to JNum(5), "mp3" to JBool(true))),
            op(A, 5, 5, "place.set", "place" to JStr(PLACE), "field" to JStr("schedule"), "value" to jObj("everyHours" to JNum(6))),
        )
        val config = placeConfigOf(logOf(ops).state, PLACE)!!
        assertEquals(CopyMode.Backup, config.mode)
        assertEquals("Backup drive", config.name)
        assertEquals(Holds(spaces = setOf("music"), favourites = true), config.holds)
        // a part copy that never chose leaves music videos out
        assertEquals(PlaceOptions(mp3 = true, deleteGuardPercent = 5, musicVideos = false), config.options)
        assertEquals(6, config.everyHours)
        assertNull(placeConfigOf(logOf(emptyList()).state, PLACE))
        // an unknown mode is no place
        assertNull(placeConfigFrom(PLACE, mapOf("mode" to JStr("sideways"))))
        val unused = OpId(A, 1)
        assertEquals(A, unused.actor)
    }
}
