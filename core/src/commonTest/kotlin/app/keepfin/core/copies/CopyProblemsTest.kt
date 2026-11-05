package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CopyProblemsTest {
    private val unsupported = "This drive does not support how Keepfin saves files."

    @Test
    fun theOwnersGoogleDriveErrorIsADriveThatCannotSaveTheWayKeepfinDoes() {
        val raw = "EISDIR: illegal operation on a directory, link 'G:\\My Drive\\Media\\Keepfin\\Abyss (from Kaiju No. 8).lrc.kftmp-rmvmd78pajh14.1-1' -> 'G:\\My Drive\\Media\\Keepfin\\Abyss.lrc'"
        assertEquals(unsupported, copyFailureReason(raw))
    }

    @Test
    fun nodeErrorsGetPlainReasons() {
        assertEquals("The drive is full.", copyFailureReason("ENOSPC: no space left on device, write"))
        assertEquals("Keepfin is not allowed to write there.", copyFailureReason("EACCES: permission denied, open 'D:\\x'"))
        assertEquals("Keepfin is not allowed to write there.", copyFailureReason("EPERM: operation not permitted, rename 'a' -> 'b'"))
        assertEquals("A file there is in use by another program.", copyFailureReason("EBUSY: resource busy or locked, rename 'a' -> 'b'"))
        assertEquals("The drive or folder is no longer there.", copyFailureReason("ENOENT: no such file or directory, open 'E:\\x'"))
        assertEquals("A file name is too long for this drive.", copyFailureReason("ENAMETOOLONG: name too long, open 'x'"))
        assertEquals(unsupported, copyFailureReason("EXDEV: cross-device link not permitted, link 'a' -> 'b'"))
        assertEquals(unsupported, copyFailureReason("ENOTSUP: operation not supported on socket, link 'a' -> 'b'"))
    }

    @Test
    fun theCopyEnginesOwnSentencesAreUnderstood() {
        assertEquals("A file there is in use by another program.", copyFailureReason("Music/Song.mp3 is in use on the copy."))
        assertEquals("A file is larger than this drive can hold.", copyFailureReason("The file is larger than the copy can hold."))
        assertEquals("A file in your library changed while it was being copied.", copyFailureReason("The source Music/Song.mp3 is gone."))
    }

    @Test
    fun androidMessagesAreUnderstood() {
        assertEquals("The drive is full.", copyFailureReason("open failed: ENOSPC (No space left on device)"))
        assertEquals("Keepfin is not allowed to write there.", copyFailureReason("java.io.IOException: open failed: EACCES (Permission denied)"))
        assertEquals("The drive or folder is no longer there.", copyFailureReason("java.io.FileNotFoundException: /storage/1234-5678/Keepfin/a.mp3: open failed: ENOENT (No such file or directory)"))
    }

    @Test
    fun aFileNameNeverDecidesTheReason() {
        // the quoted path says "no space left" but the cause is a permission
        assertEquals("Keepfin is not allowed to write there.", copyFailureReason("EACCES: permission denied, open 'D:\\Songs\\no space left on device.mp3'"))
    }

    @Test
    fun anythingElseSaysSomethingStoppedTheCopy() {
        assertEquals(FALLBACK_PROBLEM, copyFailureReason("EIO: i/o error"))
        assertEquals(FALLBACK_PROBLEM, copyFailureReason(""))
    }

    @Test
    fun noReasonShowsACodeOrATempName() {
        val all = listOf("EISDIR: x", "ENOSPC: x", "EACCES: x", "EBUSY: x", "ENOENT: x", "ENAMETOOLONG: x", "EIO: x", "weird .kftmp-abc")
        for (r in all) {
            val text = copyFailureReason(r)
            assertFalse(text.contains("kftmp"))
            assertFalse(Regex("[A-Z]{4,}").containsMatchIn(text.replace("Keepfin", "")))
        }
    }

    @Test
    fun failuresGroupByReasonMostCommonFirst() {
        val reasons = List(3) { "ENOSPC: no space left" } + List(5) { "EISDIR: illegal operation on a directory" } + listOf("EIO: i/o error")
        assertEquals(
            listOf(ProblemGroup(unsupported, 5), ProblemGroup("The drive is full.", 3), ProblemGroup(FALLBACK_PROBLEM, 1)),
            copyProblemGroups(reasons),
        )
        assertEquals(emptyList(), copyProblemGroups(emptyList()))
    }

    @Test
    fun theHeadlinesCountFiles() {
        assertEquals("1 file could not be copied", copyProblemTitle(1))
        assertEquals("50 files could not be copied", copyProblemTitle(50))
        assertEquals("1,250 files could not be copied", copyProblemTitle(1250))
        assertEquals("1 file not copied", copyProblemShort(1))
        assertEquals("50 files not copied", copyProblemShort(50))
        assertEquals("200,000 files not copied", copyProblemShort(200000))
    }
}
