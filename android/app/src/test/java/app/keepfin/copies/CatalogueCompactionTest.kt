package app.keepfin.copies

import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A copy's catalogues are append-only; past about 2,000 lines they are rewritten without their dead lines. Readers (this release's and
 * the 1.0.2 one, [released]) see the same entries before and after, and a crash while rewriting leaves the original whole.
 */
class CatalogueCompactionTest {
    private fun entry(n: Int, store: Store) = CatalogueEntry(
        "run-$n", store, if (store == Store.VERSIONS) "item-${n % 7}" else null, "rev-$n", "Music/song $n.mp3", "sha256:" + n.toString().padStart(64, '0'),
        if (store == Store.VERSIONS) "versions/item-${n % 7}/rev-$n-abc.mp3" else "trash/2026-11-01/run-$n/Music/song $n.mp3", 1_700_000_000_000L + n,
        if (n % 3 == 0) "deleted" else "replaced", "run",
    )

    private fun build(access: FolderAccess, store: Store, live: Int, voided: Int, expired: Int) {
        val lines = ArrayList<String>()
        var n = 0
        repeat(live) { lines += entry(++n, store).json().toString() }
        repeat(voided) { val e = entry(++n, store); lines += e.json().toString(); lines += JSONObject().put("void", e.id).toString() }
        repeat(expired) { val e = entry(++n, store); lines += e.json().toString(); lines += JSONObject().put("gone", e.id).toString() }
        access.mkdirs(parentPath(catalogueFile(store)))
        access.replace(catalogueFile(store), (lines.joinToString("\n") + "\n").toByteArray())
    }

    private fun seen(access: FolderAccess, store: Store) = Versions.liveEntries(access, store).map { it.json().toString() }

    /** The reader of release 1.0.2, frozen: the same lines in, the same live entries out (Versions.kt is unchanged since that tag). */
    private fun released(access: FolderAccess, store: Store): List<String> {
        val dead = HashSet<String>()
        val all = ArrayList<CatalogueEntry>()
        for (line in access.read(catalogueFile(store)).toString(Charsets.UTF_8).split('\n')) {
            if (line.isBlank()) continue
            try {
                val o = JSONObject(line)
                when {
                    o.has("void") -> dead.add(o.getString("void"))
                    o.has("gone") -> dead.add(o.getString("gone"))
                    o.has("id") && o.has("file") -> all.add(CatalogueEntry.of(o))
                }
            } catch (e: Exception) {
                // a torn tail
            }
        }
        return all.filter { it.id !in dead }.map { it.json().toString() }
    }

    @Test fun theCataloguesLoseTheirDeadLinesAndEveryReaderSeesTheSameEntries() {
        for (store in Store.values()) {
            val copy = FakeFolderAccess()
            build(copy, store, 300, 900, 700) // 3,500 lines
            val before = seen(copy, store)
            val oldBefore = released(copy, store)
            assertEquals(300, before.size)
            val out = CatalogueCompaction.compact(copy, store)!!
            assertEquals(3500, out.before)
            assertEquals(300, out.after)
            assertEquals(before, seen(copy, store))
            assertEquals(oldBefore, released(copy, store))
            assertEquals(before, oldBefore)
            assertEquals(300, copy.read(catalogueFile(store)).toString(Charsets.UTF_8).trimEnd().split('\n').size)
            assertNull("nothing left to do", CatalogueCompaction.compact(copy, store))
        }
    }

    @Test fun aSmallFileIsLeftAlone() {
        val copy = FakeFolderAccess()
        build(copy, Store.VERSIONS, 10, 500, 100)
        val bytes = copy.read(catalogueFile(Store.VERSIONS))
        assertNull(CatalogueCompaction.compact(copy, Store.VERSIONS))
        assertTrue(copy.read(catalogueFile(Store.VERSIONS)).contentEquals(bytes))
    }

    @Test fun anIdThatWasVoidedAndWrittenAgainStaysDeadAsTheReadersTreatIt() {
        val copy = FakeFolderAccess()
        val e = entry(1, Store.VERSIONS)
        val lines = listOf(e.json().toString(), JSONObject().put("void", e.id).toString(), e.json().put("revision", "again").toString()) + (0 until 2100).map { entry(100 + it, Store.VERSIONS).json().toString() }
        copy.mkdirs(".keepfin/versions")
        copy.replace(catalogueFile(Store.VERSIONS), (lines.joinToString("\n") + "\n").toByteArray())
        val before = seen(copy, Store.VERSIONS)
        assertEquals(2100, before.size)
        assertTrue(CatalogueCompaction.compact(copy, Store.VERSIONS) != null)
        assertEquals(before, seen(copy, Store.VERSIONS))
        assertEquals(before, released(copy, Store.VERSIONS))
    }

    @Test fun aCrashWhileRewritingLeavesTheOriginalWhole() {
        val inner = FakeFolderAccess()
        build(inner, Store.TRASH, 100, 1000, 0)
        val bytes = inner.read(catalogueFile(Store.TRASH))
        val before = seen(inner, Store.TRASH)
        val crashing = object : FolderAccess by inner {
            override fun replace(path: String, bytes: ByteArray) = throw FolderError.Io("power cut")
        }
        try {
            CatalogueCompaction.compact(crashing, Store.TRASH)
            fail("the crash must reach the caller")
        } catch (e: FolderError.Io) {
            // as expected
        }
        assertTrue(inner.read(catalogueFile(Store.TRASH)).contentEquals(bytes))
        assertEquals(before, seen(inner, Store.TRASH))
        assertEquals(0, CatalogueCompaction.compactAll(crashing)) // a failure of one never reaches the caller of compactAll
        assertTrue(CatalogueCompaction.compact(inner, Store.TRASH) != null)
        assertEquals(before, seen(inner, Store.TRASH))
    }

    @Test fun aLineAppendedWhileTheFileWasBeingRewrittenIsNotLost() {
        val inner = FakeFolderAccess()
        build(inner, Store.VERSIONS, 50, 1000, 0)
        var reads = 0
        val racing = object : FolderAccess by inner {
            override fun read(path: String): ByteArray {
                // the second read is the check before the switch: somebody appended in between
                if (++reads == 2) inner.replace(path, inner.read(path) + (entry(9999, Store.VERSIONS).json().toString() + "\n").toByteArray())
                return inner.read(path)
            }
        }
        assertNull(CatalogueCompaction.compact(racing, Store.VERSIONS))
        assertTrue(seen(inner, Store.VERSIONS).any { "run-9999" in it })
    }
}
