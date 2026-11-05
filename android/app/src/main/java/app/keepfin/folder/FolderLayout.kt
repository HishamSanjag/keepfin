package app.keepfin.folder

/**
 * Where the records of one library live (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 1). A folder that was never combined keeps them in
 * `.keepfin/` itself ([recordsPrefix] empty, format 1). A combined folder names the active library's records root in `folder.json`
 * (`records`, for example `libraries/<id>`), and every records path (`ops/ checkpoints/ actors/ art/ trash/ membership`) sits below it.
 * `folder.json`, `README.txt`, `.nomedia` and `lock` stay in `.keepfin/`; media paths never change.
 */
class FolderLayout(val recordsPrefix: String = "") {
    init {
        require(!recordsPrefix.startsWith("/") && !recordsPrefix.endsWith("/") && !recordsPrefix.contains("..")) { "A records prefix is a relative path." }
    }

    val root: String = if (recordsPrefix.isEmpty()) DATA_DIR else "$DATA_DIR/$recordsPrefix"
    val ops: String = "$root/ops"
    val checkpoints: String = "$root/checkpoints"
    val actors: String = "$root/actors"
    val art: String = "$root/art"
    val trash: String = "$root/trash"
    val membership: String = "$root/membership"
    val joinRecord: String = "$root/join.json"

    /** The lasting S-to-T map and review of one combine (append-only, travels with the folder). */
    fun mergeDir(mergeId: String): String = "$root/merge/$mergeId"

    /** The directories a library needs before its first operation. */
    val subdirs: List<String> get() = listOf(ops, checkpoints, actors, art, trash)

    override fun equals(other: Any?): Boolean = other is FolderLayout && other.recordsPrefix == recordsPrefix
    override fun hashCode(): Int = recordsPrefix.hashCode()
    override fun toString(): String = "FolderLayout($root)"

    companion object {
        const val DATA_DIR = ".keepfin"
        val DEFAULT = FolderLayout("")
        fun forLibrary(libraryId: String) = FolderLayout("libraries/$libraryId")
    }
}
