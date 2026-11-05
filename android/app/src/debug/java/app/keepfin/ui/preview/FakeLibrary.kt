package app.keepfin.ui.preview

import app.keepfin.ExistingMatch
import app.keepfin.ImportResult
import app.keepfin.LibraryAccess
import app.keepfin.LibraryApi
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySnapshot
import app.keepfin.LibraryBrowse
import app.keepfin.MemoryBrowse
import app.keepfin.LyricLine
import app.keepfin.Lyrics
import app.keepfin.MediaInfo
import app.keepfin.CollectionKind
import app.keepfin.Follow
import app.keepfin.FollowUpdate
import app.keepfin.LibraryCollection
import app.keepfin.PlaylistEntry
import app.keepfin.Space
import app.keepfin.SubtitleFile
import app.keepfin.TagEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug-only [LibraryApi] with realistic fixtures: a Music space with two albums, loose songs and a playlist, a Videos space with
 * a watched, a half-watched and a fresh video in a collection folder, and Downloads with a mixed file. Items have no real files,
 * so playback is not possible; items use no art (the UI shows its placeholder).
 */
class FakeLibrary(private val spaceNames: () -> List<Space>, private val editSpaces: ((List<Space>) -> List<Space>) -> Unit = {}) : LibraryApi {
    private val now = System.currentTimeMillis()
    private val flow = MutableStateFlow(LibrarySnapshot())
    private val version = MutableStateFlow(0L)
    private val state = Box(fixtures())
    override val library: StateFlow<LibrarySnapshot> = flow
    private val memoryBrowse: LibraryBrowse = MemoryBrowse({ state.value.items }, version, { state.value.collections }, { spaceNames().filter { it.kind == app.keepfin.SpaceKind.MUSIC }.map { it.id }.toSet() })

    /** A scale run points the screens at a real library (the index) while everything else stays fake. */
    @Volatile var browseOverride: LibraryBrowse? = null
    override val browse: LibraryBrowse get() = browseOverride ?: memoryBrowse

    /** What the fake holds: the items too (the real snapshot carries only the collections; items are read through [browse]). */
    data class FakeSnapshot(
        val items: List<LibraryItem> = emptyList(),
        val collections: List<LibraryCollection> = emptyList(),
        val scanning: Boolean = false,
        val scannedAt: Long? = null,
        val problems: List<String> = emptyList(),
    ) {
        fun snapshot() = LibrarySnapshot(collections, scanning, scannedAt, problems)
    }

    private inner class Box(initial: FakeSnapshot) {
        private var current = initial
        init { flow.value = initial.snapshot() }
        var value: FakeSnapshot
            get() = current
            set(v) { current = v; flow.value = v.snapshot(); version.value = version.value + 1 }
        fun update(transform: (FakeSnapshot) -> FakeSnapshot) { value = transform(current) }
    }
    val calls = CopyOnWriteArrayList<String>()
    private val reports = kotlinx.coroutines.flow.MutableSharedFlow<app.keepfin.FollowReport>(extraBufferCapacity = 16)
    override val followReports: kotlinx.coroutines.flow.SharedFlow<app.keepfin.FollowReport> = reports
    /** What the next "Check now" finds: new items found (they would be downloading) and items held for review. */
    @Volatile var nextCheckFound = 0
    @Volatile var nextCheckHeld = 0
    /** Titles the fake treats as "already in" the space for badges and Capture. */
    @Volatile var existingTitles: Set<String> = setOf("Sintel Theme", "Big Buck Bunny Theme")

    private fun song(id: String, title: String, artist: String, album: String?, track: Int?, folder: String, minutesAgo: Long, plays: Int = 0, favorite: Boolean = false, duration: Double = 200.0, space: String = "music") =
        LibraryItem(id = "content://media/external/audio/media/$id", space = space, uri = "content://media/external/audio/media/$id", folder = folder, kind = LibraryItemKind.AUDIO,
            title = title, artist = artist, album = album, albumArtist = artist, track = track, year = 2012, duration = duration, size = 7_000_000, modifiedAt = now, addedAt = now - minutesAgo * 60_000,
            plays = plays, lastPlayedAt = if (plays > 0) now - minutesAgo * 30_000 else null, favorite = favorite, hasLyrics = id.toInt() % 3 == 0, gain = -3.5, disc = 1, genre = "Soundtrack")

    private fun video(id: String, title: String, folder: String, minutesAgo: Long, duration: Double, position: Double? = null, watched: Boolean = false, space: String = "videos") =
        LibraryItem(id = "content://media/external/video/media/$id", space = space, uri = "content://media/external/video/media/$id", folder = folder, kind = LibraryItemKind.VIDEO,
            title = title, duration = duration, size = 180_000_000, modifiedAt = now, addedAt = now - minutesAgo * 60_000, width = 1920, height = 1080,
            position = position, watched = watched, plays = if (watched || position != null) 1 else 0, lastPlayedAt = if (watched || position != null) now - minutesAgo * 20_000 else null)

    private fun fixtures(): FakeSnapshot {
        val items = listOf(
            song("101", "Big Buck Bunny Theme", "Blender Studio", "Open Movies OST", 1, "Open Movies OST", 30, plays = 12, favorite = true),
            song("102", "Sintel Theme", "Blender Studio", "Open Movies OST", 2, "Open Movies OST", 29, plays = 4),
            song("103", "Tears of Steel Suite", "Blender Studio", "Open Movies OST", 3, "Open Movies OST", 28, duration = 301.0),
            song("104", "Cosmos Laundromat Waltz", "Blender Studio", "Open Movies OST", 4, "Open Movies OST", 27, plays = 1),
            song("105", "Agent 327 Main Title", "Ludwig Göransson", "Agent 327", 1, "Agent 327", 300, plays = 7, favorite = true, duration = 236.0),
            song("106", "Barbershop Chase", "Ludwig Göransson", "Agent 327", 2, "Agent 327", 299, duration = 184.0),
            song("107", "Spring Awakening", "Esther Wolf", null, null, "", 900, plays = 2),
            song("108", "Llamigos Fanfare", "Esther Wolf", null, null, "", 1_200),
            video("201", "Spring - Blender Open Movie", "", 60, 464.0, position = 190.0),
            video("202", "Sintel - Open Movie", "Open Movies", 120, 888.0, watched = true),
            video("203", "Tears of Steel", "Open Movies", 180, 734.0),
            video("204", "Cosmos Laundromat - First Cycle", "Open Movies", 240, 731.0, position = 60.0),
            song("301", "Interview audio grab", "Unknown", null, null, "", 20_000, space = "downloads", duration = 1_800.0),
            video("302", "Caminandes 3 - Llamigos", "", 15, 150.0, space = "downloads"),
        )
        val ids = items.associateBy { it.title }
        return FakeSnapshot(
            items = items.sortedByDescending { it.addedAt },
            collections = listOf(
                LibraryCollection("pl-1", "Focus", CollectionKind.OWN, "music", listOfNotNull(ids["Sintel Theme"]?.id, ids["Spring Awakening"]?.id, ids["Barbershop Chase"]?.id), createdAt = now - 86_400_000, updatedAt = now - 3_600_000),
                LibraryCollection("pl-2", "Favorites mix", CollectionKind.OWN, "music", listOfNotNull(ids["Big Buck Bunny Theme"]?.id, ids["Agent 327 Main Title"]?.id), createdAt = now - 172_800_000, updatedAt = now - 7_200_000),
            ),
            scannedAt = now,
        )
    }

    fun reset() { state.value = fixtures() }

    /** Marks library item [itemId] as the file of download [jobId], the link a finished download has once its file is scanned. */
    fun linkJob(itemId: String, jobId: String) { state.update { s -> s.copy(items = s.items.map { if (it.id == itemId) it.copy(jobId = jobId) else it }) } }

    /** Spaces v2 fixtures: tags, followed collections (new, held back, removed upstream), more spaces and smart spaces. */
    fun loadSpacesV2() { state.value = FakeLibraryData.snapshot(now) }

    /** Marks the song [id] (default: "Sintel Theme") as keeping its music video beside it, for the Song | Video tests; returns it. */
    fun addSongWithMusicVideo(id: String = "content://media/external/audio/media/102"): LibraryItem {
        edit(id) { it.copy(musicVideo = true) }
        return item(id)!!
    }

    /** Adds a video kept in the Music space (a downloaded music video): a song with a Video tag, for the Songs tests. */
    fun addMusicVideo(): LibraryItem = video("501", "Kerala (Official Video)", "", 10, 260.0, space = "music")
        .copy(artist = "Bonobo", album = "Migration").also { v -> state.update { s -> s.copy(items = (s.items + v).sortedByDescending { it.addedAt }) } }

    /** Adds the "Watch later" space's videos, for the Spaces hub screenshots and tests. */
    fun addWatchLater() {
        state.update { s ->
            s.copy(items = (s.items + listOf(
                video("401", "Lofoten in Winter", "", 50, 3_000.0, position = 1_850.0, space = "watch-later"),
                video("402", "Glass Half", "", 70, 190.0, space = "watch-later"),
                video("403", "Hero", "", 90, 210.0, space = "watch-later"),
            )).sortedByDescending { it.addedAt })
        }
    }
    fun setSnapshot(snapshot: FakeSnapshot) { state.value = snapshot }

    /** The items the fake holds. */
    val items: List<LibraryItem> get() = state.value.items

    private fun edit(id: String, transform: (LibraryItem) -> LibraryItem) = state.update { s -> s.copy(items = s.items.map { if (it.id == id) transform(it) else it }) }

    override fun rescan() { calls.add("rescan") }
    override fun item(id: String): LibraryItem? = state.value.items.firstOrNull { it.id == id }
    /** The last space saved, with the rules it carried. */
    @Volatile var lastSavedSpace: Space? = null
    override fun saveSpace(space: Space, moveExisting: Boolean) {
        calls.add("saveSpace:${space.id}")
        lastSavedSpace = space
        editSpaces { list -> if (list.any { it.id == space.id }) list.map { if (it.id == space.id) space else it } else list + space }
    }
    override fun deleteSpace(id: String) { calls.add("deleteSpace:$id"); editSpaces { list -> list.filter { it.id != id } } }
    override fun move(itemIds: List<String>, spaceId: String): LibraryAccess { calls.add("move:$spaceId"); itemIds.forEach { id -> edit(id) { it.copy(space = spaceId) } }; return LibraryAccess.Done }
    override fun deleteItems(ids: List<String>): LibraryAccess {
        calls.add("delete:${ids.joinToString()}")
        if (keepsTrash) state.value.items.filter { it.id in ids }.forEach { trash[it.id] = it; trashTimes[it.id] = System.currentTimeMillis() to 30 }
        state.update { s -> s.copy(items = s.items.filter { it.id !in ids }) }
        return LibraryAccess.Done
    }

    /** When true, deletes act like the Keepfin folder: files go to a trash Undo can bring them back from. Off by default. */
    @Volatile override var keepsTrash = false
    private val trash = java.util.concurrent.ConcurrentHashMap<String, LibraryItem>()
    /** When each trashed item was deleted and how many days it has left (the Trash page shows both). */
    private val trashTimes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    /** Puts [item] in the trash as if it had been deleted [daysAgo] days ago (tests of the Trash page). */
    fun putInTrash(item: LibraryItem, daysAgo: Int = 0) {
        keepsTrash = true
        trash[item.id] = item
        trashTimes[item.id] = (System.currentTimeMillis() - daysAgo * 86_400_000L) to (30 - daysAgo).coerceAtLeast(0)
    }

    override fun trashEntries(): List<app.keepfin.TrashEntry> = if (!keepsTrash) emptyList() else trash.values.map {
        val (at, left) = trashTimes[it.id] ?: (System.currentTimeMillis() to 30)
        app.keepfin.TrashEntry(it.id, it.id, it.title, it.title + ".mp3", it.space, it.size, at, left)
    }.sortedByDescending { it.deletedAt }

    override fun eraseFromTrash(ids: List<String>): Int { calls.add("eraseTrash:${ids.joinToString()}"); return ids.count { trash.remove(it) != null } }
    /** How long Empty trash takes (a test of the "Emptying the trash…" dialog sets it). */
    @Volatile var emptyTrashMs = 0L
    override fun emptyTrash(): Int { calls.add("emptyTrash"); Thread.sleep(emptyTrashMs); return trash.size.also { trash.clear() } }
    override fun trashableCount(ids: List<String>): Int = if (keepsTrash) ids.count { item(it) != null } else 0
    override fun trashedAmong(ids: List<String>): List<String> = ids.filter { trash.containsKey(it) }
    override fun restoreItems(ids: List<String>): app.keepfin.RestoreResult {
        val back = ids.mapNotNull { trash.remove(it) }
        calls.add("restore:${back.joinToString { it.id }}")
        state.update { s -> s.copy(items = (s.items + back).sortedByDescending { it.addedAt }) }
        return app.keepfin.RestoreResult(back.map { it.id }, emptyList())
    }
    override fun markForErase(ids: List<String>) { calls.add("markErase:${ids.joinToString()}") }
    override fun eraseTrashed(ids: List<String>): Int { calls.add("erase:${ids.joinToString()}"); return ids.count { trash.remove(it) != null } }
    override fun confirmationResult(ids: List<String>, granted: Boolean) { calls.add("confirmation:$granted") }
    override fun recordPlayback(id: String, position: Double?, finished: Boolean) { calls.add("playback:$id"); edit(id) { it.copy(position = if (finished) null else position, plays = it.plays + if (finished) 1 else 0) } }
    override fun setFavorite(id: String, favorite: Boolean) { calls.add("favorite:$id:$favorite"); edit(id) { it.copy(favorite = favorite) } }
    override fun rename(id: String, name: String): LibraryAccess { calls.add("rename:$id:$name"); return LibraryAccess.Done }
    override fun editInfo(id: String, edit: TagEdit): LibraryAccess {
        calls.add("editInfo:$id")
        edit(id) { it.copy(title = edit.title ?: it.title, artist = edit.artist ?: it.artist, album = edit.album ?: it.album, track = edit.track ?: it.track, year = edit.year ?: it.year, genre = edit.genre ?: it.genre) }
        return LibraryAccess.Done
    }
    override fun collection(id: String): LibraryCollection? = state.value.collections.firstOrNull { it.id == id }
    override fun collectionSave(id: String?, name: String, itemIds: List<String>, space: String?): String {
        val key = id ?: "pl-${state.value.collections.size + 1}-${System.nanoTime()}"
        val now = System.currentTimeMillis()
        state.update { s ->
            val exists = s.collections.any { it.id == key }
            s.copy(collections = if (exists) s.collections.map { if (it.id == key) it.copy(name = name, itemIds = itemIds, updatedAt = now) else it }
                else s.collections + LibraryCollection(key, name, CollectionKind.OWN, space ?: "music", itemIds, createdAt = now, updatedAt = now))
        }
        return key
    }
    private fun editCollection(id: String, transform: (LibraryCollection) -> LibraryCollection) =
        state.update { s -> s.copy(collections = s.collections.map { if (it.id == id) transform(it) else it }) }
    override fun collectionDelete(id: String) { calls.add("collectionDelete:$id"); state.update { s -> s.copy(collections = s.collections.filter { it.id != id }) } }
    override fun collectionAdd(id: String, itemIds: List<String>) { calls.add("collectionAdd:$id"); editCollection(id) { it.copy(itemIds = it.itemIds + itemIds.filter { x -> x !in it.itemIds }) } }
    override fun collectionRemove(id: String, itemIds: List<String>) { calls.add("collectionRemove:$id"); editCollection(id) { it.copy(itemIds = it.itemIds.filter { x -> x !in itemIds }) } }
    override fun collectionReorder(id: String, itemIds: List<String>) { calls.add("collectionReorder:$id"); editCollection(id) { it.copy(itemIds = itemIds) } }
    override fun collectionOpened(id: String) { editCollection(id) { it.copy(newItemIds = emptyList()) } }
    override fun collectionFollow(id: String, update: FollowUpdate) {
        calls.add("collectionFollow:$id:${update.mode}:${update.intervalHours}:${update.wifiOnly}:${update.notify}:${update.confirmAbove}")
        editCollection(id) { c ->
            val f = c.follow ?: Follow()
            c.copy(follow = f.copy(
                mode = update.mode ?: f.mode, intervalHours = update.intervalHours ?: f.intervalHours, wifiOnly = update.wifiOnly ?: f.wifiOnly,
                notify = update.notify ?: f.notify, confirmAbove = update.confirmAbove ?: f.confirmAbove,
            ))
        }
    }
    override fun collectionCheck(id: String?) {
        calls.add("collectionCheck:$id")
        editCollection(id ?: return) { c -> c.copy(follow = (c.follow ?: Follow()).copy(lastCheckedAt = System.currentTimeMillis(), lastError = null)) }
        collection(id)?.let { c -> reports.tryEmit(app.keepfin.FollowReport(c.id, c.name, nextCheckFound, nextCheckHeld, null, manual = true)) }
    }
    override fun collectionReview(id: String) {
        calls.add("collectionReview:$id")
        editCollection(id) { c -> c.copy(follow = c.follow?.copy(pending = null)) }
    }
    override fun libraryTag(ids: List<String>, add: List<String>, remove: List<String>) {
        calls.add("libraryTag:${ids.joinToString()}")
        ids.forEach { id -> edit(id) { it.copy(userTags = app.keepfin.TagLogic.editUserTags(it.userTags, add, remove)) } }
    }
    override fun libraryAddFrom(itemIds: List<String>, collectionIds: List<String>, spaceId: String, copy: Boolean): LibraryAccess {
        calls.add("addFrom:$spaceId:${if (copy) "copy" else "move"}")
        val ids = (itemIds + collectionIds.flatMap { collection(it)?.itemIds.orEmpty() }).distinct()
        if (!copy) ids.forEach { id -> edit(id) { it.copy(space = spaceId) } }
        return LibraryAccess.Done
    }
    override fun spaceReorder(ids: List<String>) { calls.add("spaceReorder:${ids.joinToString()}") }
    override fun lyrics(id: String): Lyrics? = if (item(id)?.hasLyrics == true) Lyrics(true, listOf(LyricLine(0, "Down by the river"), LyricLine(8_000, "Where the bunny sleeps"), LyricLine(16_000, "Morning light comes in"), LyricLine(24_000, "And the forest wakes"))) else null
    override fun subtitles(id: String): List<SubtitleFile> = emptyList()
    override fun musicVideoUri(id: String): String? = if (item(id)?.musicVideo == true) "content://keepfin.test/music-video/${id.substringAfterLast('/')}" else null
    override fun removeMusicVideo(id: String): LibraryAccess { calls.add("removeMusicVideo:$id"); edit(id) { it.copy(musicVideo = false) }; return LibraryAccess.Done }
    /** The song a video link is the same recording as ([songOf]); null: none. Tests set it for the Capture line. */
    @Volatile var songOfLink: app.keepfin.SongOfLink? = null
    override fun songOf(media: MediaInfo): app.keepfin.SongOfLink? = songOfLink
    override fun musicVideoFacts(id: String): app.keepfin.MusicVideoFacts? = musicVideoUri(id)?.let { app.keepfin.MusicVideoFacts(it, 5_000_000L) }
    override fun saveLyrics(id: String, text: String) { calls.add("saveLyrics:$id:$text"); edit(id) { it.copy(hasLyrics = true) } }
    /** "Tears of Steel" (video 203) has three chapters; every other video has none. */
    override fun chapters(id: String): List<app.keepfin.LibraryChapter> =
        if (id.endsWith("/video/media/203")) listOf(app.keepfin.LibraryChapter(0.0, "Intro"), app.keepfin.LibraryChapter(135.0, "The bridge"), app.keepfin.LibraryChapter(400.0, "Chapter 3"))
        else emptyList()
    override fun importFiles(uris: List<String>, spaceId: String): ImportResult { calls.add("import:$spaceId:${uris.size}"); return ImportResult(uris.size, 0, 0) }
    override fun earlierFilesPermissions(): List<String> = emptyList()
    override fun adoptEarlierFiles(): Int = 0
    override fun existing(spaceId: String, entries: List<PlaylistEntry>): Set<String> = entries.filter { it.title in existingTitles }.map { it.url }.toSet()
    override fun existingFor(spaceId: String, media: MediaInfo): ExistingMatch? =
        media.title.takeIf { it in existingTitles }?.let { t -> state.value.items.firstOrNull { it.title == t }?.let { ExistingMatch(it.id, it.uri, spaceId, "metadata") } }
    override fun defaultSpaceFor(url: String, uploader: String?): String? =
        if (app.keepfin.Providers.musicSource(url) != null || app.keepfin.Providers.isYouTubeMusic(url)) "music" else "downloads"
}
