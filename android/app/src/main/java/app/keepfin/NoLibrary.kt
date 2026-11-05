package app.keepfin

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The library before the Keepfin folder exists (first start, or a migration under way): reads are empty and every change says what to do. */
object NoLibrary : LibraryApi {
    const val NEEDS_FOLDER = "Set up your Keepfin folder first."

    private fun refuse(): Nothing = throw IllegalStateException(NEEDS_FOLDER)

    override val library: StateFlow<LibrarySnapshot> = MutableStateFlow(LibrarySnapshot())
    override val browse: LibraryBrowse = BrowseHolder()
    override val followReports: SharedFlow<FollowReport> = MutableSharedFlow()

    override fun rescan() {}
    override fun item(id: String): LibraryItem? = null
    override fun saveSpace(space: Space, moveExisting: Boolean) = refuse()
    override fun deleteSpace(id: String) = refuse()
    override fun move(itemIds: List<String>, spaceId: String): LibraryAccess = refuse()
    override fun deleteItems(ids: List<String>): LibraryAccess = refuse()
    override fun confirmationResult(ids: List<String>, granted: Boolean) {}
    override fun recordPlayback(id: String, position: Double?, finished: Boolean) {}
    override fun setFavorite(id: String, favorite: Boolean) = refuse()
    override fun rename(id: String, name: String): LibraryAccess = refuse()
    override fun editInfo(id: String, edit: TagEdit): LibraryAccess = refuse()
    override fun collection(id: String): LibraryCollection? = null
    override fun collectionSave(id: String?, name: String, itemIds: List<String>, space: String?): String = refuse()
    override fun collectionDelete(id: String) = refuse()
    override fun collectionAdd(id: String, itemIds: List<String>) = refuse()
    override fun collectionRemove(id: String, itemIds: List<String>) = refuse()
    override fun collectionReorder(id: String, itemIds: List<String>) = refuse()
    override fun collectionOpened(id: String) {}
    override fun collectionFollow(id: String, update: FollowUpdate) = refuse()
    override fun collectionCheck(id: String?) {}
    override fun collectionReview(id: String) {}
    override fun libraryTag(ids: List<String>, add: List<String>, remove: List<String>) = refuse()
    override fun libraryAddFrom(itemIds: List<String>, collectionIds: List<String>, spaceId: String, copy: Boolean): LibraryAccess = refuse()
    override fun spaceReorder(ids: List<String>) = refuse()
    override fun lyrics(id: String): Lyrics? = null
    override fun subtitles(id: String): List<SubtitleFile> = emptyList()
    override fun importFiles(uris: List<String>, spaceId: String): ImportResult = refuse()
    override fun earlierFilesPermissions(): List<String> = emptyList()
    override fun adoptEarlierFiles(): Int = 0
    override fun existing(spaceId: String, entries: List<PlaylistEntry>): Set<String> = emptySet()
    override fun existingFor(spaceId: String, media: MediaInfo): ExistingMatch? = null
    override fun defaultSpaceFor(url: String, uploader: String?): String? = null
}
