package app.keepfin

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Follow options to change; null fields stay as they are. */
data class FollowUpdate(
    val mode: FollowMode? = null,
    val intervalHours: Int? = null,
    val wifiOnly: Boolean? = null,
    val notify: Boolean? = null,
    val confirmAbove: Int? = null,
)

/** What [LibraryApi.restoreItems] did: the items that are back, and a sentence for each one that could not come back. */
data class RestoreResult(val restored: List<String>, val problems: List<String>)

/**
 * Library surface used by the Compose UI: spaces, gallery items and playback bookkeeping.
 * Obtain it with `engine.libraryApi` ([EngineApi.libraryApi]). Implemented by the Keepfin folder library; [NoLibrary] stands in before setup.
 * Blocking calls (everything that is not a flow or a plain getter) must run off the main thread.
 * Playback itself runs in [PlaybackService] (Media3 MediaLibraryService); the UI connects with [PlaybackClient].
 *
 * Space settings live in [EngineSettings.library]: toggles, routing and the player options change through
 * `EngineApi.updateSettings`; spaces change through [saveSpace] / [deleteSpace], which validate folders.
 */
interface LibraryApi {
    /** The collections, scan state and problems of the library (small). Items are read through [browse]. */
    val library: StateFlow<LibrarySnapshot>

    /** Paged reads of the items: lists, groups, counts and single items (D-A4). Blocking; call off the main thread. */
    val browse: LibraryBrowse

    /** Re-reads the space folders (MediaStore) and refreshes [library]. Safe to call often; coalesced. */
    fun rescan()

    /** One item by id, or null. Blocking (a read of the index): call off the main thread. */
    fun item(id: String): LibraryItem?

    /**
     * Creates or updates a space (validated: unique folder, a single clean path). With [moveExisting], files of a re-pointed
     * space move to the new folder. Throws IllegalArgumentException with a user-readable message when invalid.
     */
    fun saveSpace(space: Space, moveExisting: Boolean = false)

    /** Forgets a space; its files stay where they are. Routes that pointed to it fall back to the defaults. "downloads" cannot be removed. */
    fun deleteSpace(id: String)

    /**
     * Moves the items' files into [spaceId]'s folder (same root, RELATIVE_PATH update, collision-safe names) and updates history paths.
     * Failures leave originals untouched. Files from before a reinstall return [LibraryAccess.NeedsConfirmation].
     */
    fun move(itemIds: List<String>, spaceId: String): LibraryAccess

    /** Deletes the item's file (the UI confirms first) and its history entry's file reference. See [deleteItems]. */
    fun deleteItem(id: String): LibraryAccess = deleteItems(listOf(id))

    /** Deletes files. Keepfin-owned files go immediately; others return a system confirmation ([LibraryAccess.NeedsConfirmation]). */
    fun deleteItems(ids: List<String>): LibraryAccess

    /**
     * Of [ids] (just passed to [deleteItems]), those whose file went to the Keepfin trash and can come back with [restoreItems].
     * Empty when this library keeps no trash (files of the system's media library are deleted for good).
     */
    fun trashedAmong(ids: List<String>): List<String> = emptyList()

    /** How many of [ids] are files in the Keepfin folder (a delete moves them to its trash, where Undo finds them); 0 without a trash. */
    fun trashableCount(ids: List<String>): Int = 0

    /**
     * Delete for good: marks what a delete of [ids] put in the Keepfin trash to be erased. [eraseTrashed] erases it when the Undo
     * notice ends; a mark left by an app that closed first is erased at the next purge of the trash, whatever its age.
     */
    fun markForErase(ids: List<String>) {}

    /** Erases the Keepfin trash folders of [ids] now (the Undo of a Delete for good ran out). Returns how many went. */
    fun eraseTrashed(ids: List<String>): Int = 0

    /**
     * Undo of a delete: brings the files of [ids] back from the Keepfin trash to the paths they had and puts the items back with the
     * same id, tags, favourite, play count and collection places. Never replaces a file that has taken the name since; what cannot
     * come back is reported in [RestoreResult.problems] (plain words) and stays in the trash.
     */
    fun restoreItems(ids: List<String>): RestoreResult = RestoreResult(emptyList(), listOf("These files cannot be brought back."))

    /** True when this library keeps a Keepfin trash (the library is in the Keepfin folder); the Trash page says otherwise when false. */
    val keepsTrash: Boolean get() = false

    /**
     * What is in the Keepfin trash, newest deletion first (the Trash page). Reads the trash folders on disk, so it works after a restart;
     * folders marked for erasing and the trash of copies are never listed. Blocking: call off the main thread.
     */
    fun trashEntries(): List<TrashEntry> = emptyList()

    /** Deletes the trash entries [ids] (their [TrashEntry.id]) for good, now. Returns how many went. Blocking. */
    fun eraseFromTrash(ids: List<String>): Int = 0

    /** Erases every entry [trashEntries] lists, now. Returns how many went. Blocking. */
    fun emptyTrash(): Int = 0
    /** Call after a [LibraryAccess.NeedsConfirmation] flow ended: refreshes the library and cleans history references of deleted files. */
    fun confirmationResult(ids: List<String>, granted: Boolean)

    /** Saves the resume position (null clears it) and counts a play when [finished] or after the first 30 s. */
    fun recordPlayback(id: String, position: Double?, finished: Boolean)

    fun setFavorite(id: String, favorite: Boolean)

    /** Renames the item's file (and its lyrics/subtitle sidecars) safely, collision-checked; the title tag is unchanged. */
    fun rename(id: String, name: String): LibraryAccess

    /** Rewrites tags (title, artist, album, track, year, genre, cover) inside the file. Throws a readable error when the format cannot be edited. */
    fun editInfo(id: String, edit: TagEdit): LibraryAccess

    /** One collection by id, or null. */
    fun collection(id: String): LibraryCollection?

    /**
     * Creates (null id) or updates a collection and returns its id. A new one is the user's own list ([CollectionKind.OWN]) in [space]
     * (default: the space of its first item). Items are kept by reference; files never move.
     */
    fun collectionSave(id: String?, name: String, itemIds: List<String>, space: String? = null): String

    /** Forgets a collection. Never deletes files. */
    fun collectionDelete(id: String)

    /** Appends items by reference, skipping ones already in it. */
    fun collectionAdd(id: String, itemIds: List<String>)

    /** Removes items from the collection only. The files stay. */
    fun collectionRemove(id: String, itemIds: List<String>)

    /** New play order; ids not listed keep their relative order at the end. */
    fun collectionReorder(id: String, itemIds: List<String>)

    /** The collection was opened: clears its "new" items. */
    fun collectionOpened(id: String)

    /** Changes follow settings (null fields stay). Throws a readable error for own lists and radio mixes, which can't be followed. */
    fun collectionFollow(id: String, update: FollowUpdate)

    /** "Check now": a one-time background check of [id], or of every due collection when null. Returns at once. */
    fun collectionCheck(id: String?)

    /** What finished follow checks found: the in-app notice for "Check now" and for background checks while the app is open. Never replays. */
    val followReports: SharedFlow<FollowReport>

    /** "Review": downloads the items a check found but held back because there were more than `confirmAbove`. */
    fun collectionReview(id: String)

    /** Adds and removes the user's own tags on items. */
    fun libraryTag(ids: List<String>, add: List<String> = emptyList(), remove: List<String> = emptyList())

    /**
     * Adds library items (and the items of [collectionIds]) to a folder space: moved (the default, with the same consent flow as [move])
     * or copied (the original stays). Throws for smart spaces. Adding to a collection is [collectionAdd] and never moves files.
     */
    fun libraryAddFrom(itemIds: List<String>, collectionIds: List<String>, spaceId: String, copy: Boolean = false): LibraryAccess

    /** New hub order for every space, built-ins included. Spaces not listed keep their relative order at the end. */
    fun spaceReorder(ids: List<String>)

    // Old playlist names, kept for one release.
    fun savePlaylist(id: String?, name: String, itemIds: List<String>): String = collectionSave(id, name, itemIds)
    fun deletePlaylist(id: String) = collectionDelete(id)
    fun addToPlaylist(id: String, itemIds: List<String>) = collectionAdd(id, itemIds)

    /** Embedded lyrics or a .lrc sidecar, parsed; null when none. */
    fun lyrics(id: String): Lyrics?

    /** Sidecar subtitle files (.vtt, .srt, .ass) next to a video. */
    fun subtitles(id: String): List<SubtitleFile>

    /** The uri of the music video kept beside a song (`Song.video.mp4`, [LibraryItem.musicVideo]), or null. Blocking: call off the main thread. */
    fun musicVideoUri(id: String): String? = null

    /** The song in any space that [media] is the same recording as (by source id, then artist, title and length), or null. Blocking. */
    fun songOf(media: MediaInfo): SongOfLink? = null

    /** The uri and size of the music video beside a song, or null without one. Blocking. */
    fun musicVideoFacts(id: String): MusicVideoFacts? = null

    /** Stores [text] as the lyrics of a song the way a finished download does (a `.lrc` beside it, recorded for sync and copies). Blocking; throws when it cannot. */
    fun saveLyrics(id: String, text: String) { throw IllegalStateException("This library cannot store lyrics.") }

    /** Deletes the music video file beside a song and its record. The song stays. Throws a readable error when the song is not on this device. */
    fun removeMusicVideo(id: String): LibraryAccess = throw IllegalStateException("This library keeps no music videos.")

    /** The chapters of a video, in order; empty when it has none or the library does not keep them. */
    fun chapters(id: String): List<LibraryChapter> = emptyList()

    /** Copies picked documents (ACTION_OPEN_DOCUMENT uris) into [spaceId]; returns how many were added. Identical files are skipped. */
    fun import(uris: List<String>, spaceId: String): Int = importFiles(uris, spaceId).added

    /** Same as [import] with the details the UI needs for its message. */
    fun importFiles(uris: List<String>, spaceId: String): ImportResult

    /**
     * Permissions the UI must request before [adoptEarlierFiles] (READ_MEDIA_AUDIO + READ_MEDIA_VIDEO on Android 13+,
     * READ_EXTERNAL_STORAGE on 12 and below, none on 9 or below with the legacy write permission). Empty when already granted.
     */
    fun earlierFilesPermissions(): List<String>

    /** After a reinstall (with media-read permission granted): re-adopts earlier Keepfin files in the space folders; returns how many. */
    fun adoptEarlierFiles(): Int

    /** URLs of [entries] whose recording is already in [spaceId] (for "Already in Music" badges). */
    fun existing(spaceId: String, entries: List<PlaylistEntry>): Set<String>

    /** The match for a single inspected link, or null: drives "Already in Music" with Play and Download anyway on Capture. */
    fun existingFor(spaceId: String, media: MediaInfo): ExistingMatch?

    /** The default space for a link: the music route for music-service/YouTube Music links, else the other route. Null while the library is off. */
    fun defaultSpaceFor(url: String, uploader: String? = null): String?
}
