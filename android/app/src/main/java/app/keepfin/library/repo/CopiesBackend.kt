package app.keepfin.library.repo

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.sql.CopiesSql

/** The Keepfin folder, this device's replica id and the library's id, plus read access to the merge state for copies. */
internal class CopiesBackend(
    val folder: FolderAccess,
    val replicaId: String,
    val libraryId: String,
    private val reader: ((CopiesSql) -> Any?) -> Any?,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> read(block: (CopiesSql) -> T): T = reader { block(it) } as T
}
