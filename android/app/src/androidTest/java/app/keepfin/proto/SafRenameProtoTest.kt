package app.keepfin.proto

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Probe 4: DocumentsContract.renameDocument on primary storage and the virtual SD. */
@RunWith(AndroidJUnit4::class)
class SafRenameProtoTest {
    private val p = ProbeSupport("KeepfinProbe4")
    private val dirName = "KeepfinProbe4"
    private var vol = "primary"

    @After fun cleanup() { p.releaseAll("KeepfinProbe4"); runCatching { p.shell("rm -rf ${p.fsPath(vol, dirName)}") } }

    @Test fun primary() = run("primary")
    @Test fun sd() = run(p.sdVolume() ?: throw AssertionError("no virtual SD volume mounted"))

    private fun read(uri: Uri): String? = runCatching { p.cr.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
    private fun exists(tree: Uri, docId: String): String = runCatching {
        p.cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, docId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) "yes(${it.getString(0)})" else "no rows" } ?: "null cursor"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }

    private fun run(volume: String) {
        vol = volume
        val label = if (volume == "primary") "PRIMARY" else "SD($volume)"
        p.shell("rm -rf ${p.fsPath(vol, dirName)}"); p.shell("mkdir -p ${p.fsPath(vol, dirName)}")
        val tree = p.pickTree(vol, dirName)
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        // (1) 200 renames of one file while a second thread lists the folder as fast as it can.
        var cur = p.create(tree, rootId, "application/octet-stream", "r0.bin")!!
        p.cr.openOutputStream(cur)!!.use { it.write("payload".toByteArray()) }
        val oldId0 = DocumentsContract.getDocumentId(cur)
        var idChanges = 0; var uriNullReturns = 0
        val stop = AtomicBoolean(false)
        val polls = AtomicInteger(); val both = AtomicInteger(); val neither = AtomicInteger(); val other = AtomicInteger(); val errors = AtomicInteger()
        val sample = StringBuilder()
        val poller = Thread {
            while (!stop.get()) {
                runCatching {
                    val names = p.children(tree, rootId).map { it.second }
                    polls.incrementAndGet()
                    when {
                        names.size == 1 -> {}
                        names.isEmpty() -> { neither.incrementAndGet(); if (sample.length < 300) sample.append("[none]") }
                        names.size == 2 -> { both.incrementAndGet(); if (sample.length < 300) sample.append(names.toString()) }
                        else -> other.incrementAndGet()
                    }
                }.onFailure { errors.incrementAndGet() }
            }
        }
        poller.start()
        val t0 = System.nanoTime()
        val lat = ArrayList<Long>()
        for (i in 1..200) {
            val oldId = DocumentsContract.getDocumentId(cur)
            val t = System.nanoTime()
            val next = DocumentsContract.renameDocument(p.cr, cur, "r$i.bin")
            lat += (System.nanoTime() - t) / 1000
            if (next == null) { uriNullReturns++; throw AssertionError("rename $i returned null") }
            if (DocumentsContract.getDocumentId(next) != oldId) idChanges++
            cur = next
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        stop.set(true); poller.join()
        val sorted = lat.sorted()
        p.log("$label: (1) 200 renames in $totalMs ms; per rename us min/median/max=${sorted.first()}/${sorted[100]}/${sorted.last()}; doc id changed on $idChanges of 200 (e.g. ${oldId0} -> ${DocumentsContract.getDocumentId(cur)}); returned null $uriNullReturns")
        p.log("$label: (1) concurrent folder listing: ${polls.get()} polls, both names=${both.get()}, neither=${neither.get()}, other counts=${other.get()}, poll errors=${errors.get()} $sample")
        p.log("$label: (1) content after 200 renames: ${read(cur)}; old id still resolves: ${exists(tree, oldId0)}")

        // (1b) the same file looked up by the old document id on a poller of single-document queries (old and new ids)
        var oldGone = 0; var newAndOld = 0; var neither2 = 0; var polls2 = 0
        val stop2 = AtomicBoolean(false)
        val poller2 = Thread {
            var oldId = DocumentsContract.getDocumentId(cur); var newId = rootId + "/r201.bin"
            while (!stop2.get()) {
                val o = exists(tree, oldId).startsWith("yes"); val n = exists(tree, newId).startsWith("yes"); polls2++
                if (o && n) newAndOld++ else if (!o && !n) neither2++ else if (!o && n) oldGone++
                if (!o && n) break
            }
        }
        poller2.start()
        val renamed = DocumentsContract.renameDocument(p.cr, cur, "r201.bin")
        poller2.join(3000); stop2.set(true)
        p.log("$label: (1b) single-document polls of old+new id around one rename: polls=$polls2, both=$newAndOld, neither=$neither2, flipped-to-new-only=$oldGone (neither>0 means a window with no name)")
        cur = renamed!!

        // (2) target name exists
        val a = p.create(tree, rootId, "application/octet-stream", "x1.bin")!!
        p.cr.openOutputStream(a)!!.use { it.write("AAA".toByteArray()) }
        val b = p.create(tree, rootId, "application/octet-stream", "x2.bin")!!
        p.cr.openOutputStream(b)!!.use { it.write("BBBB".toByteArray()) }
        val res = runCatching { DocumentsContract.renameDocument(p.cr, a, "x2.bin") }
        val after = p.children(tree, rootId).map { it.second }.sorted()
        p.log("$label: (2) rename x1.bin -> existing x2.bin: result=${res.map { it?.let { u -> DocumentsContract.getDocumentId(u) } }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message?.take(160)}" }}; names now $after")
        val nowX2 = p.children(tree, rootId).firstOrNull { it.second == "x2.bin" }?.first
        p.log("$label: (2) x2.bin content now: ${nowX2?.let { read(DocumentsContract.buildDocumentUriUsingTree(tree, it)) }} (BBBB = original target kept, AAA = replaced)")
        val uniq = p.children(tree, rootId).firstOrNull { it.second.startsWith("x2") && it.second != "x2.bin" }
        p.log("$label: (2) other x2-like entry: ${uniq?.second} content=${uniq?.let { read(DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }}")

        // (3) case-only rename and rename to the same name
        val c = p.create(tree, rootId, "application/octet-stream", "case.bin")!!
        val caseRes = runCatching { DocumentsContract.renameDocument(p.cr, c, "CASE.bin") }
        p.log("$label: (3) case-only rename case.bin -> CASE.bin: ${caseRes.map { it?.let { u -> DocumentsContract.getDocumentId(u) } }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message?.take(120)}" }}; names ${p.children(tree, rootId).map { it.second }.filter { it.equals("case.bin", true) }}")

        // (4) renaming a folder: do child document ids survive?
        val d = p.create(tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, "dir1")!!
        val dId = DocumentsContract.getDocumentId(d)
        val child = p.create(tree, dId, "application/octet-stream", "child.bin")!!
        val childId = DocumentsContract.getDocumentId(child)
        val d2 = DocumentsContract.renameDocument(p.cr, d, "dir2")
        val child2 = p.children(tree, DocumentsContract.getDocumentId(d2!!))
        p.log("$label: (4) folder rename: folder id $dId -> ${DocumentsContract.getDocumentId(d2)}; child id $childId -> ${child2.map { it.first }}; old child id resolves: ${exists(tree, childId)}")
        p.log("$label: DONE")
    }
}
