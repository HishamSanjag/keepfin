package app.keepfin.copies

import app.keepfin.core.net.SoftwareSigner
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.encodeSegment
import app.keepfin.core.sync.segmentFileName
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** What data compaction changes on the copy side (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R4, R5, R6) on a phone. */
class CopiesCompactionTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val signer = SoftwareSigner.generate()

    private fun ops(from: Long, to: Long, tag: String = "v") =
        (from..to).map { Op(OpId(actor, it), Hlc(1_700_000_000_000L + it, 0, actor), "profile.set", mapOf("field" to JStr("name"), "value" to JStr("$tag$it"))) }

    private fun put(folder: FakeFolderAccess, list: List<Op>) {
        val path = "${KeepfinStore.OPS_DIR}/$actor/${segmentFileName(list.first().id.seq, list.last().id.seq)}"
        folder.mkdirs("${KeepfinStore.OPS_DIR}/$actor")
        folder.files[path] = encodeSegment(library, actor, list, signer)
    }

    private fun names(folder: FakeFolderAccess) = folder.segmentPaths().map { it.substringAfterLast('/') }

    @Test fun aPlaceKeepsItsNewestThreeManifests() {
        val folder = FakeFolderAccess()
        folder.mkdirs(".keepfin/places/p1")
        for (n in 1..7) folder.files[".keepfin/places/p1/manifest-$n.jsonl"] = "{}\n".toByteArray()
        Manifest.prune(folder, "p1")
        assertEquals(listOf("manifest-5.jsonl", "manifest-6.jsonl", "manifest-7.jsonl"), folder.files.keys.map { it.substringAfterLast('/') }.sorted())
        // numbering goes on from the highest
        Manifest.prune(folder, "p1")
        assertEquals(3, folder.files.size)
    }

    @Test fun coveredFilesOnACopyAreRemovedOnceTheMergedFileHoldsTheSameOps() {
        val copy = FakeFolderAccess()
        val all = ops(1, 6)
        put(copy, all)
        put(copy, all.subList(0, 2))
        put(copy, all.subList(2, 4))
        assertEquals(2, SegmentPrune.pruneCovered(copy, library))
        assertEquals(listOf(segmentFileName(1, 6)), names(copy))
    }

    @Test fun aCopyKeepsAFileTheMainFolderStillLists() {
        val copy = FakeFolderAccess()
        val all = ops(1, 4)
        put(copy, all)
        put(copy, all.subList(0, 2))
        val kept = SegmentPrune.pruneCovered(copy, library) { it.name == segmentFileName(1, 2) }
        assertEquals(0, kept)
        assertEquals(2, names(copy).size)
    }

    @Test fun aCoveredFileWhoseOpsDifferIsNotRemoved() {
        val copy = FakeFolderAccess()
        put(copy, ops(1, 4))
        put(copy, ops(1, 2, tag = "forged"))
        assertEquals(0, SegmentPrune.pruneCovered(copy, library))
        assertEquals(2, names(copy).size)
    }

    @Test fun aFileThatDoesNotDecodeLeavesItsActorAlone() {
        val copy = FakeFolderAccess()
        put(copy, ops(1, 4))
        put(copy, ops(1, 2))
        val small = "${KeepfinStore.OPS_DIR}/$actor/${segmentFileName(1, 2)}"
        copy.files[small] = "not a segment".toByteArray()
        assertEquals(0, SegmentPrune.pruneCovered(copy, library))
        assertEquals(2, names(copy).size)
    }

    @Test fun mainsCoveredFilesAreFoundByName() {
        val main = FakeFolderAccess()
        val all = ops(1, 6)
        put(main, all)
        put(main, all.subList(0, 3))
        val files = app.keepfin.net.SegmentFiles.list(main)
        assertEquals(listOf(segmentFileName(1, 3)), SegmentPrune.coveredByName(files).map { it.first.name })
    }
}
