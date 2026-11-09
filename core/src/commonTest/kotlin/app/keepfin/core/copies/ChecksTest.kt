package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksTest {
    // ---- names that look the same ----

    private val composed = "Café.mp3"
    private val decomposed = "Café.mp3"

    @Test
    fun composedAndDecomposedAccentsAreOneNameOnEveryRuleThatKnowsUnicode() {
        assertEquals(NameRules.composed.key(composed), NameRules.composed.key(decomposed))
        assertEquals(NameRules.folded.key(composed), NameRules.folded.key(decomposed))
        // the older rules told them apart
        assertTrue(NameRules.exact.key(composed) != NameRules.exact.key(decomposed))
    }

    @Test
    fun theFoldedRuleIgnoresLetterCaseBeyondAscii() {
        assertEquals(NameRules.folded.key("Music/CAFÉ.mp3"), NameRules.folded.key("music/café.MP3"))
        assertEquals(NameRules.folded.key("Δια.mp3"), NameRules.folded.key("δΙΑ.mp3"))
        assertEquals(NameRules.folded.key("Привет.mp3"), NameRules.folded.key("пРИВЕТ.mp3"))
        // the composed rule is case sensitive
        assertTrue(NameRules.composed.key("A.mp3") != NameRules.composed.key("a.mp3"))
    }

    @Test
    fun aNameIsKeptAsItIsWhenFoldingWouldChangeItsLength() {
        // a capital I with a dot has a two-character lower case: it stays, on every platform
        assertEquals("İstanbul", NameRules.folded.key("İSTANBUL").take(1) + "stanbul")
        assertEquals(NameRules.folded.key("İx"), NameRules.folded.key("İX"))
    }

    @Test
    fun anAsciiNameCostsNothing() {
        val p = "Music/Some Song.mp3"
        assertTrue(NameRules.composed.key(p) === p)
        assertEquals("music/some song.mp3", NameRules.folded.key(p))
    }

    @Test
    fun theRulesAreFoundById() {
        assertEquals(NameRules.exact, NameRules.byId("exact"))
        assertEquals(NameRules.caseInsensitiveAscii, NameRules.byId("ascii"))
        assertEquals(NameRules.composed, NameRules.byId("nfc"))
        assertEquals(NameRules.folded, NameRules.byId("unicode"))
        assertNull(NameRules.byId("other"))
    }

    @Test
    fun aPlannerNeverCreatesASecondVisuallyIdenticalName() {
        // main has the composed spelling, the copy already holds the decomposed one with the same bytes
        fun entry(path: String) = BaselineEntry(path, "x", "op-x", "sha256:" + "1".repeat(64), Stat(100, 1_000_000), Stat(100, 1_000_000), 90_000_000, 1)
        val rel = Rel(
            d = listOf(DesiredEntry(composed, "x", "op-x", "sha256:" + "1".repeat(64), 100)),
            m = listOf(InvLine(composed, 100, 1_000_000)),
            c = listOf(InvLine(decomposed, 100, 1_000_000)),
            l = listOf(entry(decomposed)),
            rules = NameRules.folded,
        )
        val outcome = Planner.plan(rel.input(), Evidence.NONE)
        val plan = (outcome as PlanOutcome.Ready).plan
        assertTrue(plan.actions.none { it.kind == ActionKind.CopyTo && it.expect?.contentId == null }, "no second file is created: ${plan.actions}")
    }

    // ---- the 4 GB limit of FAT ----

    @Test
    fun onlyFatHasTheFourGigabyteLimit() {
        assertEquals(4_294_967_295L, fileSizeLimit("FAT32"))
        assertEquals(4_294_967_295L, fileSizeLimit("vfat"))
        assertEquals(4_294_967_295L, fileSizeLimit(" FAT "))
        assertNull(fileSizeLimit("exFAT"))
        assertNull(fileSizeLimit("NTFS"))
        assertNull(fileSizeLimit("ext4"))
        assertNull(fileSizeLimit(null))
    }

    @Test
    fun theWordsForATooLargeFileSayWhyAndThatNothingWasCopied() {
        val words = fileTooLargeWords("Film.mkv", "FAT32")
        assertTrue("Film.mkv" in words && "FAT32" in words && "not copied" in words)
    }

    // ---- files that are only in the cloud ----

    @Test
    fun cloudPlaceholdersAreRecognisedByTheirAttributes() {
        assertTrue(isOnlineOnlyWindows(0x400000L)) // RECALL_ON_DATA_ACCESS
        assertTrue(isOnlineOnlyWindows(0x400000L or 0x20L or 0x400L)) // with archive and reparse point
        assertTrue(isOnlineOnlyWindows(0x40000L)) // RECALL_ON_OPEN
        assertTrue(isOnlineOnlyWindows(0x1000L)) // OFFLINE
        assertFalse(isOnlineOnlyWindows(0x20L)) // archive
        assertFalse(isOnlineOnlyWindows(0x20L or 0x400L)) // a hydrated placeholder or a plain reparse point
        assertFalse(isOnlineOnlyWindows(0L))
    }

    // ---- a changed clock ----

    private fun shift(vararg deltas: Long, unchanged: Int = 0): ClockShift {
        val s = ClockShift()
        var t = 1_700_000_000_000L
        for (d in deltas) { s.add(t, t + d); t += 1_000_000 }
        repeat(unchanged) { s.add(t, t); t += 1_000_000 }
        return s
    }

    private val h = 3_600_000L

    @Test
    fun everyFileShiftedByAWholeNumberOfHoursIsAClockChange() {
        val s = shift(h, h, h, h, h, h)
        assertTrue(s.detected)
        assertEquals(1L, s.hours)
        assertTrue(shift(-h, -h, -h + 1_000, -h, -h).detected) // FAT rounds to two seconds
        assertEquals(-1L, shift(-h, -h, -h, -h).hours)
    }

    @Test
    fun aFewEditedFilesAreNotAClockChange() {
        assertFalse(shift(h, h, unchanged = 20).detected) // too few
        assertFalse(shift(5_000, 90_000, 7 * 60_000L, 1_234_567, 99_999).detected) // edits, not whole hours
        assertFalse(shift(h, h, h, h, unchanged = 100).detected) // a handful among many
        assertFalse(ClockShift().detected)
    }

    @Test
    fun mixedOffsetsAreNotOneShift() {
        assertFalse(shift(h, 2 * h, 3 * h, 4 * h, 5 * h, 6 * h).detected)
    }

    // ---- the background audit ----

    @Test
    fun anAuditIsDueThirtyDaysAfterTheLastOne() {
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        assertFalse(auditDue(null, now))
        assertFalse(auditDue(now - 29 * day, now))
        assertTrue(auditDue(now - 30 * day, now))
        assertTrue(auditDue(now - 200 * day, now))
    }
}
