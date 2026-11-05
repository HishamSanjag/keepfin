package app.keepfin.library.repo

import app.keepfin.FollowReport
import app.keepfin.LibraryAccess
import app.keepfin.LibraryHost
import app.keepfin.LibrarySnapshot
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.sync.Jv
import app.keepfin.folder.sql.IndexReader
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import app.keepfin.folder.OpDraft as StoreDraft

/**
 * What the parts of [LibraryRepository] ([RepoTrash], [RepoCollections], [RepoImports], [RepoDownloads], [RepoMusicVideos], [RepoLinked])
 * may use of the repository. The repository builds this from its own private members; a part never reaches into the repository itself,
 * and the parts reach each other only through here. Each member is the repository's own, named and typed as there.
 */
internal interface RepoCore {
    val host: LibraryHost
    val deviceDir: File
    val imports: ImportSource?
    val linked: LinkedMedia?
    val locators: LinkedStore
    val library: StateFlow<LibrarySnapshot>
    val reports: MutableSharedFlow<FollowReport>
    /** The follow check results of the collections, kept on this device. */
    var follows: Map<String, FollowDevice>
    /** Jobs queued by a follow check: their items show up as new in the collection when they are saved. */
    val followJobs: MutableSet<String>
    val small: Small
    /** Items of linked spaces whose file was not found on the last check (they stay in the library). */
    var missingLinked: Set<String>

    fun now(): Long
    fun newId(): String
    fun log(message: String, error: Throwable?)
    fun backend(): RepositoryBackend
    fun rd(): IndexReader
    /** Publishes the collections and problems the screens show. */
    fun publish()

    fun readItems(ids: Collection<String>): Pair<List<LibItem>, Map<String, Long>>
    fun libItem(id: String): LibItem?
    fun presentIds(ids: Collection<String>): Set<String>
    fun placeOf(id: String): String?
    fun itemWithContentAt(path: String, contentId: String): String?
    fun hasItem(id: String): Boolean

    fun addItems(items: List<LibItem>, extra: List<app.keepfin.core.library.OpDraft> = emptyList())
    fun updateItems(ids: Collection<String>, transform: (LibItem) -> LibItem)
    fun commitSpaces(fresh: Boolean = false, change: (List<LibSpace>, LibRoutes) -> Pair<List<LibSpace>, LibRoutes>): Boolean
    fun commitCollections(change: (List<LibCollection>) -> List<LibCollection>): Boolean
    fun updateCollection(id: String, transform: (LibCollection) -> LibCollection): Boolean
    fun removeItems(gone: Set<String>)
    fun addFile(spaceId: String, subfolder: String, fileName: String, source: InputStream, template: LibItem): AddedFile
    fun collectionAdd(id: String, itemIds: List<String>)
    fun collectionReorder(id: String, itemIds: List<String>)

    fun uniqueIn(b: RepositoryBackend, dir: String, wanted: String): String
    fun sidecarsOf(b: RepositoryBackend, place: String): List<String>
    fun moveFile(b: RepositoryBackend, from: String, to: String)
    fun companionRecord(itemId: String, place: String, name: String, content: String, size: Long): StoreDraft?
    fun deleteLinked(items: List<LibItem>, gone: MutableSet<String>, fail: (String) -> Unit): LibraryAccess
    fun recordCompanionsOf(b: RepositoryBackend, songs: List<Triple<String, String, String>>)
}

/** The small part of the library that stays in memory: spaces, routes and collections (with their songs). */
internal class Small(
    val spaces: List<LibSpace>,
    val routes: LibRoutes,
    val collections: List<LibCollection>,
    /** The spaces and profile views, the base of every projection. */
    val base: Map<String, Jv>,
    /** Space wire ids to the app's ids. */
    val modelIds: Map<String, String>,
)

/** How many items one change or one page of files handles at a time. */
internal const val SLICE = 500

internal fun join(dir: String, name: String) = if (dir.isEmpty()) name else if (name.isEmpty()) dir else "$dir/$name"

internal fun jsonList(ids: Collection<String>): String = JSONArray(ids.toList()).toString()

internal fun sha256Hex(input: InputStream): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun kindOf(name: String, mime: String?): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime?.startsWith("audio/") == true || ext in setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac") -> "audio"
        mime?.startsWith("video/") == true || ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v") -> "video"
        mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "webp", "gif") -> "image"
        else -> "other"
    }
}
