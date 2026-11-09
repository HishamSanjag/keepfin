package app.keepfin.move

import android.net.Uri
import android.provider.DocumentsContract
import app.keepfin.KeepfinApplication
import app.keepfin.MediaEngine
import app.keepfin.copies.CopiesRuntime
import app.keepfin.core.spaces.movedPath
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.merge.MergeFence
import java.io.File

/** [MoveHost] on the phone: the profile file, the open library, the fence that pauses it and the download history. */
internal class AndroidMoveHost(private val app: KeepfinApplication) : MoveHost {
    val profiles = ProfileStore(File(app.filesDir, "profiles.json"))

    private val engine: MediaEngine? get() = app.takeIf { it.engineCreated }?.engineApi as? MediaEngine

    override fun open(tree: String): FolderAccess = SafFolderAccess.open(app.contentResolver, Uri.parse(tree))

    override fun now(): Long = System.currentTimeMillis()

    /** The library stops taking writes and what it holds goes into the folder; downloads and copies wait (the merge fence, held process-wide). */
    override fun quiesce(libraryId: String) {
        MergeFence.enter(libraryId)
        engine?.folderLibrary?.publishPending()
    }

    override fun release(libraryId: String) = MergeFence.exit(libraryId)

    override fun switchProfile(move: FolderMove) {
        val profile = profiles.load().profiles.firstOrNull { it.libraryId == move.libraryId } ?: throw IllegalStateException("The library of this move is no longer on this phone.")
        if (profile.folderTreeUri != move.toTree) profiles.add(profile.copy(folderTreeUri = move.toTree), makeCurrent = false)
    }

    override fun reopen(move: FolderMove) {
        val e = engine ?: return
        e.reopenFolderLibrary()
        relocate(move)
    }

    /** Download history rows that named a file in the old place name the same file in the new one. Safe to repeat. */
    fun relocate(move: FolderMove) {
        val e = engine ?: return
        val newTree = Uri.parse(move.toTree)
        e.relocateFolderFiles { uri -> movedUri(newTree, move, uri) }
    }

    /** Why a move cannot start right now, in plain words, or null. */
    fun refusal(): String? = when {
        MergeFence.active -> "Keepfin is busy with your library. Try again when it has finished."
        CopiesRuntime.of(app)?.isRunning() == true -> "A copy is running. Wait for it to finish, then move your Keepfin folder."
        engine?.jobs?.value?.any { it.status.active } == true -> "Downloads are running. Wait for them to finish, then move your Keepfin folder."
        else -> null
    }

    companion object {
        /** The address in the new tree of the document [uri] names, when it is in the old folder; null for any other address. */
        fun movedUri(newTree: Uri, move: FolderMove, uri: String): String? {
            val u = Uri.parse(uri)
            if (u.authority != SafFolderAccess.LOCAL_AUTHORITY) return null
            val doc = runCatching { DocumentsContract.getDocumentId(u) }.getOrNull() ?: return null
            val moved = movedPath(move.fromDoc, move.toDoc, doc) ?: return null
            return DocumentsContract.buildDocumentUriUsingTree(newTree, moved).toString()
        }
    }
}
