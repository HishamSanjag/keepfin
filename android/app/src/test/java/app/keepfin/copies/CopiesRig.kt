package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.folder.FakeFolderAccess
import java.io.File
import java.security.MessageDigest

/** Two fake folders (the Keepfin folder and a copy), a state directory and the runner, for the executor and runner tests. */
internal class CopiesRig(val mode: CopyMode = CopyMode.Backup) {
    val main = FakeFolderAccess()
    val copy = FakeFolderAccess()
    val stateDir: File = java.nio.file.Files.createTempDirectory("kf-copies").toFile()
    val desired = LinkedHashMap<String, DesiredEntry>()
    val deleted = HashSet<String>()
    var runNo = 0

    init {
        copy.mkdirs(".keepfin")
        copy.publish(".keepfin/folder.json", """{"format":1,"libraryId":"lib","replicaId":"copy-replica","createdAt":"2026-10-16T00:00:00Z","generation":0}""".toByteArray())
    }

    fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Puts a library file on main and in the desired set. */
    fun put(path: String, text: String, item: String = "item-$path") {
        val bytes = text.toByteArray()
        main.mkdirs(path.substringBeforeLast('/', ""))
        main.files[path] = bytes
        desired[path] = DesiredEntry(path, item, "op-${runNo}-$path", sha(bytes), bytes.size.toLong())
    }

    /** Writes a file straight into a fake folder (with its directories), as someone editing the card would. */
    fun write(access: FakeFolderAccess, path: String, text: String) {
        access.mkdirs(path.substringBeforeLast('/', ""))
        access.files[path] = text.toByteArray()
    }

    fun drop(path: String, deleteItem: Boolean = true) {
        val e = desired.remove(path)
        main.files.remove(path)
        if (deleteItem && e != null) deleted.add(e.itemId)
    }

    fun text(access: FakeFolderAccess, path: String) = access.files[path]?.toString(Charsets.UTF_8)

    internal class Host : MainHost {
        val revised = ArrayList<String>()
        val imported = ArrayList<String>()
        val deletedItems = ArrayList<String>()
        lateinit var rig: CopiesRig
        override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
            rig.main.rename(stagedPath, destPath.substringAfterLast('/'))
            imported.add(destPath)
            return ImportOutcome("new-${imported.size}", "op-new", destPath)
        }

        override fun reviseFromCopy(action: Action, stagedPath: String): String? {
            rig.main.remove(action.path)
            rig.main.rename(stagedPath, action.path.substringAfterLast('/'))
            revised.add(action.path)
            return "op-rev"
        }

        override fun restoreEditedDeleted(action: Action, stagedPath: String): String? = reviseFromCopy(action, stagedPath)
        override fun deleteItem(action: Action) { deletedItems.add(action.path) }
    }

    val host = Host().also { it.rig = this }

    fun run(
        fault: FaultHook? = null,
        dryRun: Boolean = false,
        previewConfirmed: Boolean = true,
        confirmHeld: Boolean = false,
        useHost: Boolean = true,
        confirmedDeletes: Set<String> = emptySet(),
        desiredNow: Collection<DesiredEntry> = desired.values,
    ): RunReport {
        runNo++
        return CopyRunner.run(
            RunConfig(
                main = main, copy = copy, stateDir = stateDir, placeId = "place1", mode = mode, device = "test", desired = desiredNow.toList().asSequence(), deleted = deleted.toSet(),
                confirmedDeletes = confirmedDeletes, previewConfirmed = previewConfirmed, confirmHeld = confirmHeld, dryRun = dryRun, host = if (useHost) host else null, fault = fault,
                relistDelayMs = 0, parallelism = 1, runId = "run$runNo", mainReplica = "main-replica", copyReplica = "copy-replica", lockHeartbeatMs = 60_000,
            ),
        )
    }

    fun leftovers(access: FakeFolderAccess) = access.files.keys.filter { it.contains(".kftmp") }

    fun versionFiles() = copy.files.keys.filter { it.startsWith(".keepfin/versions/") && !it.endsWith("catalogue.jsonl") }
    fun trashFiles() = copy.files.keys.filter { it.startsWith(".keepfin/trash/") && !it.endsWith("index.jsonl") }

    fun planIsEmpty(): Boolean {
        val r = run(dryRun = true)
        return r.status == "preview" && r.summary!!.counts.values.sum() == 0
    }
}
