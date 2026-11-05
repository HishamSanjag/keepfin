package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun sha(n: Int) = "sha256:" + n.toString().padStart(64, '0')

/** One path of a relationship, in the states of the decision tables. */
internal class Fx(
    val mode: CopyMode,
    val l: BaselineEntry?,
    val d: DesiredEntry?,
    val m: InvLine?,
    val c: InvLine?,
    /** hash of the copy's file when it has one */
    val copyHash: String? = null,
    val deleted: Boolean = false,
    val extra: (PlannerInput) -> PlannerInput = { it },
) {
    fun plan(confirmedDeletes: Set<String> = emptySet(), previewConfirmed: Boolean = true, caps: Capabilities = Capabilities.folder): Plan {
        val evidence = MapEvidence(if (c != null && copyHash != null) mapOf(MapEvidence.keyOf(Side.Copy, c) to copyHash) else emptyMap())
        val input = PlannerInput(
            mode, PlaceOptions(), NameRules.exact,
            desired = { listOfNotNull(d).iterator() },
            main = { listOfNotNull(m).iterator() },
            copy = { listOfNotNull(c).iterator() },
            baseline = { listOfNotNull(l).iterator() },
            library = SimpleLibraryFacts(if (deleted && l != null) setOf(l.itemId) else emptySet()),
            previewConfirmed = previewConfirmed,
            confirmedDeletes = confirmedDeletes,
            caps = caps,
        )
        return when (val o = Planner.plan(extra(input), evidence)) {
            is PlanOutcome.Ready -> o.plan
            is PlanOutcome.NeedsHashes -> fail("unexpected hash requests: ${o.requests}")
        }
    }
}

private const val P = "Music/a.mp3"
private val OLD = sha(1)
private val NEWER = sha(2)
private val OTHER = sha(3)

private fun base(content: String = OLD) = BaselineEntry(P, "item1", "op1", content, Stat(100, 10_000_000), Stat(100, 10_000_000), 50_000_000, 1)
private fun desired(content: String) = DesiredEntry(P, "item1", "op1", content, 100)
private val mainLine = InvLine(P, 100, 10_000_000)
private val copySame = InvLine(P, 100, 10_000_000)
private val copyEdited = InvLine(P, 120, 20_000_000)

private fun kinds(p: Plan) = p.actions.map { it.kind }

class CellTest {
    // main state, copy state -> the table cell, written as fixtures
    private fun twoWay(ms: String, cs: String, deleted: Boolean = false): Fx {
        val l = if (ms == "new") null else base()
        val d = if (deleted) null else desired(if (ms == "changed") NEWER else if (ms == "new") NEWER else OLD)
        val m = if (ms == "absent") null else mainLine
        val (c, h) = when (cs) {
            "same" -> copySame to OLD
            "changed" -> copyEdited to OTHER
            "new" -> copyEdited to (if (ms == "new") NEWER else OTHER)
            "newDifferent" -> copyEdited to OTHER
            else -> null to null
        }
        return Fx(CopyMode.TwoWay, l, d, m, c, h, deleted)
    }

    @Test
    fun twoWaySameSame() = assertEquals(emptyList(), kinds(twoWay("same", "same").plan()))

    @Test
    fun twoWaySameChangedRevisesFromTheCopy() {
        val p = twoWay("same", "changed").plan()
        assertEquals(listOf(ActionKind.ReviseFromCopy), kinds(p))
        assertEquals(OTHER, p.actions[0].contentId)
    }

    @Test
    fun twoWaySameAbsentRecopiesAndOffersTheProposal() {
        val p = twoWay("same", "absent").plan()
        assertEquals(listOf(ActionKind.CopyTo), kinds(p))
        assertEquals(null, p.actions[0].expect!!.contentId) // the destination must be absent
        assertTrue(p.review.any { it.kind == ReviewKind.CopyDeletedProposal })
    }

    @Test
    fun twoWaySameAbsentWithAConfirmedDeletionDeletesTheItem() {
        val p = twoWay("same", "absent").plan(confirmedDeletes = setOf(P))
        assertEquals(listOf(ActionKind.DeleteItem), kinds(p))
        assertEquals(RemoveReason.ConfirmedDelete, p.actions[0].reason)
    }

    @Test
    fun twoWayChangedSameCopiesToTheCopyAndKeepsTheOldBytes() {
        val p = twoWay("changed", "same").plan()
        assertEquals(listOf(ActionKind.CopyTo), kinds(p))
        assertEquals(OLD, p.actions[0].expect!!.contentId)
        assertEquals(Archive.Trash, p.actions[0].archive)
    }

    @Test
    fun twoWayChangedChangedDifferentKeepsBoth() {
        val p = twoWay("changed", "changed").plan()
        assertEquals(listOf(ActionKind.ImportNew, ActionKind.CopyTo), kinds(p))
        assertTrue(p.actions[0].toPath!!.contains("conflict"))
        assertEquals(OTHER, p.actions[1].expect!!.contentId) // the copy's bytes are what is displaced, and they were imported first
    }

    @Test
    fun twoWayChangedChangedEqualConverges() {
        val fx = Fx(CopyMode.TwoWay, base(), desired(NEWER), mainLine, copyEdited, NEWER)
        assertEquals(listOf(ActionKind.Converge), kinds(fx.plan()))
    }

    @Test
    fun twoWayChangedAbsentCopiesTheEdit() {
        val p = twoWay("changed", "absent").plan()
        assertEquals(listOf(ActionKind.CopyTo), kinds(p))
        assertEquals(null, p.actions[0].expect!!.contentId)
    }

    @Test
    fun twoWayNewNewDifferentMakesTwoItems() {
        val p = twoWay("new", "newDifferent").plan()
        assertEquals(listOf(ActionKind.ImportNew, ActionKind.CopyTo), kinds(p))
        assertEquals(null, p.actions[0].itemId) // a new item
        assertTrue(p.actions[0].toPath!!.contains("copy"))
    }

    @Test
    fun twoWayNewNewEqualConverges() = assertEquals(listOf(ActionKind.Converge), kinds(twoWay("new", "new").plan()))

    @Test
    fun twoWayNewAbsentCopies() = assertEquals(listOf(ActionKind.CopyTo), kinds(twoWay("new", "absent").plan()))

    @Test
    fun twoWayAbsentSameRestoresFromTheCopy() = assertEquals(listOf(ActionKind.RestoreFromCopy), kinds(twoWay("absent", "same").plan()))

    @Test
    fun twoWayAbsentSameAfterAnItemDeleteRemovesToTrash() {
        val p = twoWay("absent", "same", deleted = true).plan()
        assertEquals(listOf(ActionKind.RemoveFromCopy), kinds(p))
        assertEquals(RemoveReason.ItemDeleted, p.actions[0].reason)
        assertEquals(Archive.Trash, p.actions[0].archive)
        assertEquals(OLD, p.actions[0].expect!!.contentId)
    }

    @Test
    fun twoWayAbsentChangedIsImportedWhenJustAbsent() = assertEquals(listOf(ActionKind.ReviseFromCopy), kinds(twoWay("absent", "changed").plan()))

    @Test
    fun twoWayAbsentChangedAfterADeleteKeepsTheEditForReview() {
        val p = twoWay("absent", "changed", deleted = true).plan()
        assertEquals(listOf(ActionKind.RestoreEditedDeleted), kinds(p))
        assertTrue(p.actions[0].review)
    }

    @Test
    fun twoWayAbsentNewImportsAsANewItem() {
        val fx = Fx(CopyMode.TwoWay, null, null, null, copyEdited, OTHER)
        val p = fx.plan()
        assertEquals(listOf(ActionKind.ImportNew), kinds(p))
        assertEquals(null, p.actions[0].itemId)
    }

    @Test
    fun twoWayAbsentAbsentDeletedIsDone() {
        val fx = Fx(CopyMode.TwoWay, base(), null, null, null, null, deleted = true)
        assertEquals(listOf(ActionKind.ForgetBaseline), kinds(fx.plan()))
    }

    @Test
    fun twoWayAbsentAbsentNotDeletedIsMissingEverywhere() {
        val p = twoWay("absent", "absent").plan()
        assertEquals(emptyList(), kinds(p))
        assertEquals(listOf(ReviewKind.MissingEverywhere), p.review.map { it.kind })
    }

    // ---- backup and mirror ----

    private fun bm(mode: CopyMode, ms: String, cs: String, deleted: Boolean = false, held: Boolean = true): Fx {
        val l = if (ms == "new" || (ms == "absent" && cs == "new")) null else base()
        val d = if (!held || deleted) null else desired(if (ms == "changed" || ms == "new") NEWER else OLD)
        val m = if (ms == "absent") null else mainLine
        val (c, h) = when (cs) {
            "same" -> copySame to OLD
            "changed" -> copyEdited to OTHER
            "new" -> copyEdited to (if (ms == "new") NEWER else OTHER)
            "newDifferent" -> copyEdited to OTHER
            else -> null to null
        }
        return Fx(mode, l, d, m, c, h, deleted)
    }

    @Test
    fun backupAndMirrorCells() {
        for (mode in listOf(CopyMode.Backup, CopyMode.Mirror)) {
            val archive = if (mode == CopyMode.Backup) Archive.Versions else Archive.Trash
            assertEquals(emptyList(), kinds(bm(mode, "same", "same").plan()), "$mode same/same")
            var p = bm(mode, "same", "changed").plan()
            assertEquals(listOf(ActionKind.CopyTo), kinds(p), "$mode same/changed (restore)")
            assertEquals(archive, p.actions[0].archive)
            assertEquals(OTHER, p.actions[0].expect!!.contentId)
            p = bm(mode, "same", "absent").plan()
            assertEquals(listOf(ActionKind.CopyTo), kinds(p), "$mode same/absent")
            assertEquals(null, p.actions[0].expect!!.contentId)
            p = bm(mode, "changed", "same").plan()
            assertEquals(listOf(ActionKind.CopyTo), kinds(p), "$mode changed/same")
            assertEquals(archive, p.actions[0].archive)
            p = bm(mode, "changed", "changed").plan()
            assertEquals(listOf(ActionKind.CopyTo), kinds(p), "$mode changed/changed")
            assertEquals(archive, p.actions[0].archive)
            assertEquals(listOf(ActionKind.CopyTo), kinds(bm(mode, "changed", "absent").plan()), "$mode changed/absent")
            assertEquals(listOf(ActionKind.Converge), kinds(Fx(mode, null, desired(NEWER), mainLine, copyEdited, NEWER).plan()), "$mode new/new equal")
            p = Fx(mode, null, desired(NEWER), mainLine, copyEdited, OTHER).plan()
            assertEquals(listOf(ActionKind.CopyTo), kinds(p), "$mode new/new different")
            assertEquals(archive, p.actions[0].archive)
            assertEquals(listOf(ActionKind.CopyTo), kinds(bm(mode, "new", "absent").plan()), "$mode new/absent")
            // main lost it, but it is still wanted: missing source, nothing is removed
            for (cs in listOf("same", "changed")) {
                p = bm(mode, "absent", cs).plan()
                assertEquals(emptyList(), kinds(p), "$mode absent/$cs but still held")
                assertTrue(p.review.any { it.kind == ReviewKind.MissingSource })
            }
            p = bm(mode, "absent", "new").plan()
            assertEquals(emptyList(), kinds(p), "$mode absent/new")
            assertEquals(listOf(ReviewKind.UnknownFile), p.review.map { it.kind })
            assertEquals(emptyList(), kinds(Fx(mode, null, null, null, null).plan()), "$mode absent/absent")
        }
    }

    @Test
    fun backupKeepsAndMirrorRemovesWhatWasDeletedOrIsNoLongerHeld() {
        // deleted in the library
        var p = bm(CopyMode.Backup, "absent", "same", deleted = true).plan()
        assertEquals(emptyList(), kinds(p))
        assertEquals(listOf(ReviewKind.KeptOnBackup), p.review.map { it.kind })
        p = bm(CopyMode.Mirror, "absent", "same", deleted = true).plan()
        assertEquals(listOf(ActionKind.RemoveFromCopy), kinds(p))
        assertEquals(Archive.Trash, p.actions[0].archive)
        assertEquals(RemoveReason.ItemDeleted, p.actions[0].reason)
        // the copy edited it: a backup keeps it, a mirror removes the edited bytes into trash
        p = bm(CopyMode.Mirror, "absent", "changed", deleted = true).plan()
        assertEquals(OTHER, p.actions[0].expect!!.contentId)
        assertEquals(emptyList(), kinds(bm(CopyMode.Backup, "absent", "changed", deleted = true).plan()))
        // no longer held (the item still exists)
        p = bm(CopyMode.Mirror, "same", "same", held = false).plan()
        assertEquals(listOf(ActionKind.RemoveFromCopy), kinds(p))
        assertEquals(RemoveReason.NoLongerHeld, p.actions[0].reason)
        p = bm(CopyMode.Backup, "same", "same", held = false).plan()
        assertEquals(emptyList(), kinds(p))
        assertEquals(listOf(ReviewKind.NotHeld), p.review.map { it.kind })
        // two-way: leaving a playlist never removes anything
        assertEquals(emptyList(), kinds(Fx(CopyMode.TwoWay, base(), null, mainLine, copySame, OLD).plan()))
    }

    @Test
    fun aDeletedItemIsForgottenWhenBothSidesAreGone() {
        for (mode in CopyMode.values()) {
            assertEquals(listOf(ActionKind.ForgetBaseline), kinds(Fx(mode, base(), null, null, null, null, deleted = true).plan()), "$mode")
        }
    }

    @Test
    fun aFileOnTheCopyNobodyOwnsIsAdoptedTwoWayAndListedOtherwise() {
        val fx = { mode: CopyMode -> Fx(mode, null, null, null, copyEdited, OTHER) }
        assertEquals(listOf(ActionKind.ImportNew), kinds(fx(CopyMode.TwoWay).plan()))
        for (mode in listOf(CopyMode.Backup, CopyMode.Mirror)) {
            val p = fx(mode).plan()
            assertEquals(emptyList(), kinds(p))
            assertEquals(listOf(ReviewKind.UnknownFile), p.review.map { it.kind })
        }
    }

    @Test
    fun aReplacementOfAnAssumedFileWaitsForItsHash() {
        // the copy's stat equals the baseline, so it is assumed unchanged; replacing it needs proof first
        val c = copySame
        val input = PlannerInput(
            CopyMode.Mirror, PlaceOptions(), NameRules.exact,
            desired = { listOf(desired(NEWER)).iterator() }, main = { listOf(mainLine).iterator() },
            copy = { listOf(c).iterator() }, baseline = { listOf(base()).iterator() }, library = SimpleLibraryFacts(),
        )
        val first = Planner.plan(input, Evidence.NONE) as PlanOutcome.NeedsHashes
        assertEquals(listOf(Side.Copy to P), first.requests.map { it.side to it.path })
        // the hash shows the file really is the baseline: replace it
        val same = Planner.plan(input, MapEvidence(mapOf(MapEvidence.keyOf(Side.Copy, c) to OLD))) as PlanOutcome.Ready
        assertEquals(listOf(ActionKind.CopyTo), kinds(same.plan))
        assertEquals(OLD, same.plan.actions[0].expect!!.contentId)
        // the hash shows it was edited with its timestamp preserved: a different cell, and the edit is archived, never lost
        val edited = Planner.plan(input, MapEvidence(mapOf(MapEvidence.keyOf(Side.Copy, c) to OTHER))) as PlanOutcome.Ready
        assertEquals(OTHER, edited.plan.actions[0].expect!!.contentId)
        assertEquals(Archive.Trash, edited.plan.actions[0].archive)
    }
}
