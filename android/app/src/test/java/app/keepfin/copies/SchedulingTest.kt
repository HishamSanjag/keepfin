package app.keepfin.copies

import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchedulingTest {
    private val mb = 1024L * 1024

    @Test fun smallBackgroundRunsAlwaysGo() {
        assertFalse(Scheduling.hold(10, 100 * mb, charging = false, unmetered = false))
        assertFalse(Scheduling.hold(Scheduling.LARGE_FILES, Scheduling.LARGE_BYTES, charging = false, unmetered = false))
    }

    @Test fun largeRunsWaitForPowerOrAnUnmeteredNetwork() {
        assertTrue(Scheduling.hold(10, 501 * mb, charging = false, unmetered = false))
        assertTrue(Scheduling.hold(2_001, 1, charging = false, unmetered = false))
        assertFalse(Scheduling.hold(2_001, 600 * mb, charging = true, unmetered = false))
        assertFalse(Scheduling.hold(2_001, 600 * mb, charging = false, unmetered = true))
    }

    private fun card(replica: String, library: String = "lib"): FakeFolderAccess = FakeFolderAccess().also {
        it.mkdirs(".keepfin")
        it.publish(".keepfin/folder.json", """{"format":1,"libraryId":"$library","replicaId":"$replica","createdAt":"2026-10-16T00:00:00Z","generation":0}""".toByteArray())
    }

    @Test fun aCardThatCameBackUnderAnotherAddressIsFoundByItsReplicaId() {
        val trees = mapOf("content://a" to card("other"), "content://b" to card("mine"), "content://c" to card("mine", "another-library"))
        assertEquals("content://b", Recognition.find("mine", "lib", listOf("content://a", "content://b", "content://c"), emptySet()) { trees.getValue(it) })
    }

    @Test fun addressesOfOtherCopiesUnreadableAddressesAndNoMatchAreSkipped() {
        val gone = FakeFolderAccess().also { it.failWith = FolderError.NotFound("x") }
        val trees = mapOf("content://gone" to gone, "content://b" to card("mine"))
        val found = Recognition.find("mine", "lib", listOf("content://gone", "content://b"), setOf("content://b")) { trees.getValue(it) }
        assertNull(found)
        assertEquals("content://b", Recognition.find("mine", "lib", listOf("content://gone", "content://b"), emptySet()) { trees.getValue(it) })
        assertNull(Recognition.find("nobody", "lib", listOf("content://b"), emptySet()) { trees.getValue(it) })
    }
}
