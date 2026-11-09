package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.ReviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renames that travel as moves (#29) and the safety checks of the copies engine on the phone (#30). */
internal class CopySafetyTest {
    private fun count(r: RunReport, kind: String) = r.exec?.byKind?.get(kind) ?: 0

    /** The library keeps an item's id when it renames or moves a file: the desired set names the file at its new place by the same item. */
    private fun rename(rig: CopiesRig, from: String, to: String, text: String? = null) {
        val e = rig.desired.getValue(from)
        val bytes = rig.main.files.getValue(from)
        rig.main.files.remove(from)
        rig.desired.remove(from)
        rig.put(to, text ?: bytes.toString(Charsets.UTF_8), e.itemId)
    }

    // ---- renames travel as moves ----

    @Test fun aRenameIsAMoveOnTheCopyInEveryModeWithoutCopyingAgain() {
        for (mode in CopyMode.values()) {
            val rig = CopiesRig(mode)
            rig.put("Music/old name.mp3", "song")
            assertEquals("done", rig.run().status)
            rename(rig, "Music/old name.mp3", "Music/new name.mp3")
            val r = rig.run()
            assertEquals("$mode", 1, count(r, "MoveOnCopy"))
            assertEquals("$mode", 0, count(r, "CopyTo"))
            assertEquals("song", rig.text(rig.copy, "Music/new name.mp3"))
            assertNull(rig.text(rig.copy, "Music/old name.mp3"))
        }
    }

    @Test fun aMoveToAnotherSpaceFolderIsAMove() {
        val rig = CopiesRig()
        rig.put("Music/a.mp3", "tune")
        rig.run()
        rename(rig, "Music/a.mp3", "Favourites/a.mp3")
        val r = rig.run()
        assertEquals(1, count(r, "MoveOnCopy"))
        assertEquals("tune", rig.text(rig.copy, "Favourites/a.mp3"))
        assertNull(rig.text(rig.copy, "Music/a.mp3"))
    }

    @Test fun renameThenEditIsMoveThenCopyAndTheOldBytesAreKept() {
        val rig = CopiesRig()
        rig.put("a.mp3", "version one")
        rig.run()
        rename(rig, "a.mp3", "b.mp3", "version two!")
        val r = rig.run(confirmHeld = true) // replacing the only file is over the deletion guard
        assertEquals(1, count(r, "MoveOnCopy"))
        assertEquals(1, count(r, "CopyTo"))
        assertEquals("version two!", rig.text(rig.copy, "b.mp3"))
        assertEquals(listOf("version one"), rig.versionFiles().map { rig.text(rig.copy, it) })
    }

    @Test fun twoRenamesBeforeTheCopyWasToldAreOneMove() {
        val rig = CopiesRig()
        rig.put("a.mp3", "tune")
        rig.run()
        rename(rig, "a.mp3", "b.mp3")
        rename(rig, "b.mp3", "c.mp3")
        val r = rig.run()
        assertEquals(1, count(r, "MoveOnCopy"))
        assertEquals("tune", rig.text(rig.copy, "c.mp3"))
        assertNull(rig.text(rig.copy, "a.mp3"))
    }

    @Test fun aRenameOntoANameTheCopyHoldsForAnotherFileIsNotAMove() {
        val rig = CopiesRig()
        rig.put("a.mp3", "tune")
        rig.run()
        rig.write(rig.copy, "b.mp3", "somebody elses")
        rename(rig, "a.mp3", "b.mp3")
        val r = rig.run()
        assertEquals(0, count(r, "MoveOnCopy"))
        // a backup replaces it with the library's file and keeps the other bytes as a version: nothing is lost, and nothing was renamed onto it
        assertEquals("tune", rig.text(rig.copy, "b.mp3"))
        assertTrue(rig.versionFiles().any { rig.text(rig.copy, it) == "somebody elses" })
    }

    // ---- names that look the same ----

    @Test fun aCopyNeverGetsASecondVisuallyIdenticalName() {
        val rig = CopiesRig()
        rig.put("Café.mp3", "bytes")
        rig.write(rig.copy, "Café.mp3", "bytes") // the same song, spelled the other way
        val r = rig.run()
        assertEquals("done", r.status)
        assertEquals(0, count(r, "CopyTo"))
        assertEquals(1, rig.copy.files.keys.count { it.endsWith(".mp3") })
    }

    @Test fun aBaselineWrittenUnderAnotherRuleIsSortedAgainAndTheRuleIsRecorded() {
        val rig = CopiesRig()
        for (n in listOf("B.mp3", "a.mp3", "École.mp3", "zed.mp3")) rig.put(n, n)
        rig.run()
        val rel = Relationship.read(rig.stateDir)
        assertNotNull(rel.rules)
        Relationship.write(rig.stateDir, Relationship(rel.generation, rel.previewConfirmed, null, rel.auditedAt)) // written before the rule was recorded
        val again = rig.run()
        assertEquals("done", again.status)
        assertNull(again.exec)
        assertNotNull(Relationship.read(rig.stateDir).rules)
        assertEquals(4, Baseline.read(rig.stateDir).count())
    }

    // ---- files only in the cloud ----

    @Test fun aMainFileThatIsOnlyInTheCloudIsNeverReadOrCopiedAndNothingIsDestructive() {
        val rig = CopiesRig()
        rig.put("here.mp3", "local bytes")
        rig.put("cloud.mp3", "cloud bytes")
        rig.main.onlineOnly.add("cloud.mp3")
        val r = rig.run()
        assertEquals("local bytes", rig.text(rig.copy, "here.mp3"))
        assertNull(rig.text(rig.copy, "cloud.mp3"))
        assertFalse(r.main!!.complete)
        assertTrue(r.review.any { it.kind == ReviewKind.OnlineOnly && it.path == "cloud.mp3" })
        // later, when it is on the device, it is copied
        rig.main.onlineOnly.clear()
        val later = rig.run()
        assertEquals("cloud bytes", rig.text(rig.copy, "cloud.mp3"))
        assertEquals(1, count(later, "CopyTo"))
    }

    @Test fun aCopyFileThatIsOnlyInTheCloudIsNeverReplacedOrRemoved() {
        val rig = CopiesRig()
        rig.put("a.mp3", "one")
        rig.run()
        rig.put("a.mp3", "two two")
        rig.copy.onlineOnly.add("a.mp3")
        val r = rig.run()
        assertEquals("one", rig.text(rig.copy, "a.mp3"))
        assertEquals(0, count(r, "RemoveFromCopy"))
        assertFalse(r.copy!!.complete)
    }

    // ---- the 4 GiB limit of FAT ----

    @Test fun aFileTheCardCannotHoldIsNotAttemptedAndThePersonIsToldWhy() {
        val rig = CopiesRig()
        rig.put("small.mp3", "ok")
        rig.put("Film.mkv", "x".repeat(40))
        val r = rig.run(volumeFormat = "vfat", maxFileBytes = 20)
        assertEquals("vfat", r.copyFormat)
        assertEquals("ok", rig.text(rig.copy, "small.mp3"))
        assertNull(rig.text(rig.copy, "Film.mkv"))
        val note = r.review.firstOrNull { it.kind == ReviewKind.TooLarge }
        assertNotNull(note)
        assertEquals("Film.mkv", note!!.path)
        assertTrue(note.detail.contains("not copied"))
        assertEquals(0, r.exec!!.failedCount)
    }

    @Test fun theFatLimitComesFromTheFormatTheMountTableNames() {
        val mounts = listOf("/dev/block/vold/public:179,1 /mnt/media_rw/1234-ABCD vfat rw,dirsync", "/dev/block/dm-0 /data ext4 rw", "/dev/fuse /storage/emulated fuse rw")
        val card = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AKeepfin"
        assertEquals("1234-ABCD", VolumeFormat.volumeId(card))
        assertEquals("vfat", VolumeFormat.of(card, { mounts }))
        assertNull(VolumeFormat.of("content://com.android.externalstorage.documents/tree/primary%3AKeepfin", { mounts }))
        assertNull(VolumeFormat.of("peer:abc", { mounts }))
        assertNull(VolumeFormat.of("content://com.android.externalstorage.documents/tree/FFFF-0000%3AX", { mounts }))
        assertEquals(4_294_967_295L, app.keepfin.core.copies.fileSizeLimit(VolumeFormat.of(card, { mounts })))
    }

    // ---- a changed clock and the background audit ----

    @Test fun everyMtimeMovedByAnHourMakesTheNextRunHashEverything() {
        val rig = CopiesRig()
        for (i in 0 until 6) rig.put("s$i.mp3", "song $i")
        rig.run()
        val calm = rig.run()
        assertNull(calm.auditReason)
        assertEquals(0, calm.hashed)
        rig.copy.mtimeShiftMs = 3_600_000 // the card reports every time an hour later
        val shifted = rig.run()
        assertEquals("clock", shifted.auditReason)
        assertEquals(1L, shifted.auditHours)
        assertTrue("every file was hashed (${shifted.hashed})", shifted.hashed >= 6)
        assertEquals(0, count(shifted, "CopyTo"))
        assertNotNull(Relationship.read(rig.stateDir).auditedAt)
    }

    @Test fun theFirstCompletedRunCountsAsAnAuditAndAnAskedForAuditRenewsIt() {
        val rig = CopiesRig()
        rig.put("a.mp3", "a")
        rig.run()
        val first = Relationship.read(rig.stateDir).auditedAt
        assertNotNull(first)
        Thread.sleep(5)
        val r = rig.run(audit = true)
        assertEquals("asked", r.auditReason)
        assertTrue(Relationship.read(rig.stateDir).auditedAt!! >= first!!)
    }
}
