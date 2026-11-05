package app.keepfin.proto

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Probe 6: revoking the persisted grant mid-copy, ejecting the virtual SD mid-copy, and ENOSPC. */
@RunWith(AndroidJUnit4::class)
class SafFailureProtoTest {
    private val p = ProbeSupport("KeepfinProbe6")
    private val dirName = "KeepfinProbe6"
    private var vol = "primary"
    private val mb = 1 shl 20
    private val uriFile get() = File(p.ctx.filesDir, "probe6.uri")

    @After fun cleanup() {
        p.releaseAll("KeepfinProbe6")
        val v = p.sdVolume()
        if (v != null) runCatching { p.shell("rm -rf ${p.fsPath(v, dirName)}"); p.shell("rm -f ${p.fsPath(v, "probe6-fill.bin")}") }
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
    }

    private fun err(e: Throwable) = "${e.javaClass.name}: ${e.message?.take(200)}"
    private fun <T> attempt(f: () -> T): String = runCatching { f().toString() }.getOrElse { "EXC " + err(it) }

    /** Writes [total] MB in 1 MB chunks, fsync every 4 MB; [hook] is called once when [hookAtMb] MB are written. Returns (written MB, failure). */
    private fun copyLoop(doc: Uri, total: Int, hookAtMb: Int, sync: Boolean = true, hook: () -> Unit): Pair<Int, Throwable?> {
        val chunk = ByteArray(mb) { (it * 7).toByte() }
        var written = 0
        try {
            p.cr.openOutputStream(doc)!!.use { out ->
                val fd = (out as FileOutputStream).fd
                while (written < total) {
                    out.write(chunk); written++
                    if (sync && written % 4 == 0) fd.sync()
                    if (written == hookAtMb) hook()
                    Thread.sleep(5)
                }
            }
        } catch (e: Throwable) { return written to e }
        return written to null
    }

    private fun prepare(volume: String): Pair<Uri, String> {
        vol = volume
        p.shell("rm -rf ${p.fsPath(vol, dirName)}"); p.shell("mkdir -p ${p.fsPath(vol, dirName)}")
        val tree = p.pickTree(vol, dirName)
        return tree to DocumentsContract.getTreeDocumentId(tree)
    }

    @Test fun revoke() {
        val (tree, rootId) = prepare("primary")
        val doc = p.create(tree, rootId, "application/octet-stream", "copy.bin")!!
        uriFile.writeText(tree.toString())
        val (written, fail) = copyLoop(doc, 150, 60) {
            p.cr.releasePersistableUriPermission(tree, p.rwFlags)
            p.log("REVOKE: released persisted grant at 60 MB; persisted now=${p.cr.persistedUriPermissions.any { it.uri == tree }}")
        }
        p.log("REVOKE: copy of 150 MB: wrote $written MB, failure=${fail?.let { err(it) }} (null = the already-open stream kept working)")
        val size = attempt { p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) / mb else -1 } }
        p.log("REVOKE: size of copy.bin by query afterwards (MB): $size")
        p.log("REVOKE: in the same process after release: list children -> ${attempt { p.children(tree, rootId).map { it.second } }}")
        p.log("REVOKE: in the same process after release: createDocument -> ${attempt { p.create(tree, rootId, "application/octet-stream", "after.bin") }}")
        p.log("REVOKE: in the same process after release: openOutputStream of a new stream on copy.bin -> ${attempt { p.cr.openOutputStream(doc, "wa")!!.use { it.write(1) }; "ok" }}")
        p.log("REVOKE: tree uri saved for the restart check; run the revokedAfterRestart test next (fresh process)")
    }

    @Test fun revokedAfterRestart() {
        val tree = Uri.parse(uriFile.readText())
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        p.log("RESTART: persisted grants listing this tree: ${p.cr.persistedUriPermissions.count { it.uri == tree }}")
        p.log("RESTART: list children -> ${attempt { p.children(tree, rootId).map { it.second } }}")
        p.log("RESTART: createDocument -> ${attempt { p.create(tree, rootId, "application/octet-stream", "again.bin") }}")
        p.log("RESTART: openOutputStream on an existing doc -> ${attempt { p.cr.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/copy.bin"), "wa")!!.close(); "ok" }}")
        p.log("RESTART: DONE")
    }

    @Test fun eject() {
        // a probe from phase 0, run on purpose only (-Pandroid.testInstrumentationRunnerArguments.probes=true): unmounting a card with open files lets Android end this process, which stops the rest of a test run
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("probes") == "true")
        val sd = p.sdVolume() ?: throw AssertionError("no virtual SD")
        val volLine = p.shell("sm list-volumes").lines().first { it.startsWith("public:") }.split(" ")[0]
        val (tree, rootId) = prepare(sd)
        uriFile.writeText(tree.toString())
        val doc = p.create(tree, rootId, "application/octet-stream", "copy.bin")!!
        val (written, fail) = copyLoop(doc, 150, 60) {
            p.log("EJECT: about to run sm unmount $volLine at 60 MB (if this process is killed the next line never appears)")
            val o = p.shell("sm unmount $volLine")
            p.log("EJECT: sm unmount returned [$o]; this process survived")
        }
        p.log("EJECT: copy of 150 MB: wrote $written MB before failure=${fail?.let { err(it) }}")
    }

    /** Fresh process after the unmount: what the saved tree does while the volume is out and after it comes back. */
    @Test fun ejectAfterUnmount() {
        // a probe from phase 0, run on purpose only (-Pandroid.testInstrumentationRunnerArguments.probes=true): it unmounts the card with files open, which can end this process
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("probes") == "true")
        val tree = Uri.parse(uriFile.readText())
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val volLine = p.shell("sm list-volumes").lines().first { it.startsWith("public:") }.split(" ")[0]
        p.log("EJECT2: volume state: ${p.shell("sm list-volumes").lines().firstOrNull { it.startsWith("public:") }?.trim()}; persisted grant listed: ${p.cr.persistedUriPermissions.any { it.uri == tree }}")
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/copy.bin")
        p.log("EJECT2: while unmounted, list children -> ${attempt { p.children(tree, rootId).map { it.second } }}")
        p.log("EJECT2: while unmounted, createDocument -> ${attempt { p.create(tree, rootId, "application/octet-stream", "while-out.bin") }}")
        p.log("EJECT2: while unmounted, query doc size -> ${attempt { p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -2 } }}")
        p.log("EJECT2: while unmounted, open for write -> ${attempt { p.cr.openOutputStream(doc, "wa")!!.close(); "ok" }}")
        p.shell("sm mount $volLine")
        var back = -1L; val t0 = System.nanoTime()
        while ((System.nanoTime() - t0) / 1_000_000 < 30_000) { if (p.shell("sm list-volumes").lines().any { it.startsWith("public:") && it.contains("mounted") }) { back = (System.nanoTime() - t0) / 1_000_000; break }; Thread.sleep(250) }
        p.log("EJECT2: remounted after $back ms")
        Thread.sleep(1500)
        p.log("EJECT2: after remount, same tree uri: persisted=${p.cr.persistedUriPermissions.any { it.uri == tree }}; list children -> ${attempt { p.children(tree, rootId).map { it.second } }}")
        p.log("EJECT2: after remount, partial copy.bin size (MB) -> ${attempt { p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) / mb else -2 } }}")
        p.log("EJECT2: after remount, createDocument -> ${attempt { p.create(tree, rootId, "application/octet-stream", "after-back.bin") }}")
        p.log("EJECT2: DONE")
    }
    @Test fun enospcNoSync() {
        val sd = p.sdVolume() ?: throw AssertionError("no virtual SD")
        val (tree, rootId) = prepare(sd)
        val fill = p.fsPath(sd, "probe6-fill.bin")
        val freeMb = p.shell("df -k /storage/$sd").lines().last { it.isNotBlank() }.trim().split(Regex("\\s+"))[3].toInt() / 1024
        p.shell("dd if=/dev/zero of=$fill bs=1048576 count=${freeMb - 30}")
        val doc = p.create(tree, rootId, "application/octet-stream", "copy.bin")!!
        val (written, fail) = copyLoop(doc, 100, Int.MAX_VALUE, false) {}
        p.log("ENOSPC-nosync: wrote $written MB of 100 (about 30 free) with no fsync, failure=${fail?.let { err(it) }}; size by query now ${attempt { p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) / mb else -2 } }} MB")
        p.shell("rm -f $fill")
        p.log("ENOSPC-nosync: DONE")
    }

    @Test fun enospc() {
        val sd = p.sdVolume() ?: throw AssertionError("no virtual SD")
        val (tree, rootId) = prepare(sd)
        val fill = p.fsPath(sd, "probe6-fill.bin")
        val dfBefore = p.shell("df -k /storage/$sd").lines().last { it.isNotBlank() }.trim()
        val freeMb = p.shell("df -k /storage/$sd").lines().last { it.isNotBlank() }.trim().split(Regex("\\s+"))[3].toInt() / 1024
        val fillMb = freeMb - 30
        p.shell("dd if=/dev/zero of=$fill bs=1048576 count=$fillMb")
        p.log("ENOSPC: df before fill [$dfBefore]; after filling $fillMb MB [${p.shell("df -k /storage/$sd").lines().last { it.isNotBlank() }.trim()}]")
        val doc = p.create(tree, rootId, "application/octet-stream", "copy.bin")!!
        val (written, fail) = copyLoop(doc, 100, Int.MAX_VALUE) {}
        p.log("ENOSPC: wrote $written MB of 100 before failure=${fail?.let { err(it) }} (about 30 MB were free)")
        p.log("ENOSPC: size of the partial file by query (MB, truncated): ${attempt { p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) / mb else -2 } }}")
        p.log("ENOSPC: disk full now; createDocument -> ${attempt { p.create(tree, rootId, "application/octet-stream", "next.bin") }}")
        val d2 = runCatching { p.create(tree, rootId, "application/octet-stream", "next2.bin") }.getOrNull()
        p.log("ENOSPC: write 1 MB into a new file when full -> ${attempt { copyLoop(d2!!, 1, Int.MAX_VALUE) {}.let { "wrote ${it.first} MB, failure=${it.second?.let { e -> err(e) }}" } }}")
        p.log("ENOSPC: renameDocument when full -> ${attempt { DocumentsContract.renameDocument(p.cr, doc, "renamed.bin")?.let { DocumentsContract.getDocumentId(it) } }}")
        p.log("ENOSPC: deleting the partial file -> ${attempt { DocumentsContract.deleteDocument(p.cr, doc) }}; df [${p.shell("df -k /storage/$sd").lines().last { it.isNotBlank() }.trim()}]")
        p.shell("rm -f $fill")
        p.log("ENOSPC: DONE")
    }
}
