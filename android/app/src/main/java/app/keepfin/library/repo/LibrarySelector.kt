package app.keepfin.library.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.keepfin.LibraryHost
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpenFolderResult
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.folder.sql.SqlMergeState
import android.util.Log
import app.keepfin.folder.StoreOptions
import app.keepfin.net.NetRuntime
import app.keepfin.net.StoreNetLibrary
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Decides which library the engine wires (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 1, `profiles.json`): with a profile
 * the library runs on its Keepfin folder ([LibraryRepository]); without one the engine uses [app.keepfin.NoLibrary].
 */
internal object LibrarySelector {
    /** The repository for the current profile, or null when there is none (nothing is opened or read yet: [LibraryRepository.start] opens). */
    fun create(context: Context, host: LibraryHost, profiles: ProfileStore = ProfileStore(File(context.filesDir, "profiles.json")), forMigration: Boolean = false): LibraryRepository? {
        // while an earlier library is being brought over, the MediaStore library keeps running; only the setup itself opens the folder
        if (!forMigration && File(context.filesDir, "migration/active.json").isFile) return null
        // an unfinished combine or separate is settled before the profile is read: it decides which library this folder holds
        app.keepfin.merge.MergeRuntime.recoverBeforeOpen(context)
        val profile = profiles.current() ?: return null
        val deviceDir = profiles.deviceDir(context.filesDir, profile.id)
        val appVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val tree = Uri.parse(profile.folderTreeUri)
        val statePath = File(deviceDir, "state/state.db").path
        val remembered = FolderIds(File(deviceDir, "folder-ids.json"))
        fun signerId() = (if (NetRuntime.available) NetRuntime.signer(context) else null)?.let { app.keepfin.core.net.deviceIdOf(it.spki) }
        fun newState(libraryId: String, replicaId: String) =
            // the merge state is SQLite next to the profile's device data: a cache of the segments, rebuilt when it does not fit
            SqlMergeState(statePath, libraryId, replicaId, signerId(), AndroidSqlHost()) { Log.w("KeepfinState", it) }
        // the state the early view opened, handed to the folder's open when the folder is the one remembered
        var early: LocalStart? = null
        return LibraryRepository(
            host = host,
            openLocal = {
                // nothing of the storage provider is asked here: the ids are the ones the folder had last time, the addresses are built
                // from the tree (as SafFolderAccess.documentUriFor builds them)
                val ids = remembered.read()?.takeIf { it.first == profile.libraryId && File(statePath).isFile }
                ids?.let { (libraryId, replicaId) ->
                    val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                    LocalStart(newState(libraryId, replicaId), replicaId, { path ->
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, if (path.isEmpty()) rootId else "$rootId/$path").toString()
                    }).also { early = it }
                }
            },
            open = {
                val signer = if (NetRuntime.available) NetRuntime.signer(context) else null
                val access = SafFolderAccess.open(context.contentResolver, tree)
                val opened = KeepfinFolders.open(access) as? OpenFolderResult.Ok
                    ?: throw IllegalStateException("The Keepfin folder could not be read. Pick it again in Storage.")
                val view = early?.takeIf { !it.dropped }
                val fits = view != null && view.replicaId == opened.folder.replicaId && remembered.read()?.first == opened.folder.libraryId
                // another folder than last time: the repository takes the early view down (closing its state) and opens again
                if (view != null && !fits) throw EarlyViewMismatch()
                val state = (if (fits) view?.state else null) ?: newState(opened.folder.libraryId, opened.folder.replicaId)
                remembered.write(opened.folder.libraryId, opened.folder.replicaId)
                Log.i("KeepfinState", state.probe())
                val store = KeepfinStore.open(
                    StoreOptions(
                        folder = access, libraryId = opened.folder.libraryId, actor = profile.actorId, deviceDir = deviceDir, outboxDir = File(deviceDir, "outbox"), state = state,
                        newId = { UUID.randomUUID().toString() }, readOnly = opened.readOnly, appVersion = appVersion, deviceName = Build.MODEL ?: "Android",
                        onRotate = { _, actor -> profiles.updateActor(profile.id, actor) }, layout = opened.folder.layout,
                        // the device key signs this device's segments (KFSEG 2) so paired devices can verify who wrote them
                        signer = signer,
                    ),
                ).get()
                if (NetRuntime.available) {
                    // the devices service serves and syncs the open library; it drops out by itself when the store closes
                    NetRuntime.register(StoreNetLibrary(opened.folder.libraryId, profile.name, access, store, state, opened.folder.replicaId, layout = opened.folder.layout))
                }
                RepositoryBackend(store, access, opened.folder.replicaId, state, uriFor = { path -> access.documentUriFor(path).toString() }).also { it.layout = opened.folder.layout }
            },
            deviceDir = deviceDir,
            imports = ResolverImports(context.contentResolver),
            linked = MediaStoreLinked(context),
        )
    }
}

/** The library and place ids of the profile's folder the last time it opened, so the next start can show the library before it opens. */
internal class FolderIds(private val file: File) {
    fun read(): Pair<String, String>? = try {
        val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
        json.getString("libraryId") to json.getString("replicaId")
    } catch (e: Exception) {
        null
    }

    fun write(libraryId: String, replicaId: String) {
        if (read() == libraryId to replicaId) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("libraryId", libraryId).put("replicaId", replicaId).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) { /* the next start waits for the folder, as before */ }
    }
}

/** Picked documents (`ACTION_OPEN_DOCUMENT` uris) read through the content resolver. */
internal class ResolverImports(private val cr: ContentResolver) : ImportSource {
    override fun open(uri: String): InputStream = cr.openInputStream(Uri.parse(uri)) ?: throw java.io.FileNotFoundException(uri)
    override fun displayName(uri: String): String =
        cr.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: Uri.parse(uri).lastPathSegment.orEmpty()
    override fun mimeType(uri: String): String? = cr.getType(Uri.parse(uri))
}
