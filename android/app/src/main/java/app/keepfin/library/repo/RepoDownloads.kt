package app.keepfin.library.repo

import app.keepfin.FfOutput
import app.keepfin.library.repo.LibraryRepository.SavedDownload
import app.keepfin.CollectionKind
import app.keepfin.CollectionLogic
import app.keepfin.ExistingMatch
import app.keepfin.Job
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItemKind
import app.keepfin.MediaInfo
import app.keepfin.PlaylistEntry
import app.keepfin.Providers
import app.keepfin.Recording
import app.keepfin.Recordings
import app.keepfin.SongOfLink
import app.keepfin.SourceIds
import app.keepfin.SourceTags
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SyncRequest
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibSource
import app.keepfin.core.library.LibraryMapping
import app.keepfin.folder.FolderError
import app.keepfin.folder.OpDraft as StoreDraft
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Finished downloads saved into the folder, their collections, and the checks for a recording the library already holds. Inner state is reached through [RepoCore]. */
internal class RepoDownloads(private val core: RepoCore) {
    private fun known(item: LibItem, content: String?): Recording = Recording(
        sourceIds = SourceIds.of(item.sourceUrl) + listOfNotNull(item.sourceId), contentKey = content,
        artist = item.artist ?: item.albumArtist, title = item.title, album = item.album, duration = item.duration,
    )

    private val titleSplit = Regex("\\s+[-\u2013\u2014]\\s+")

    /** Items of the space and kind that could be the same recording as [candidate]: they share a source id, the bytes, or a normalised title. */
    private fun candidatesFor(spaceId: String, kind: LibraryItemKind, candidate: Recording): List<String> {
        val wire = LibraryMapping.spaceWireId(spaceId)
        val out = LinkedHashSet<String>()
        if (candidate.sourceIds.isNotEmpty()) {
            for (r in core.rd().rows("SELECT i.id FROM lib_sources s JOIN lib_index i ON i.id = s.id WHERE s.sid IN (SELECT value FROM json_each(?1)) AND i.space = ?2 AND i.media = ?3", listOf(jsonList(candidate.sourceIds), wire, kind.wire))) out += r[0] as String
        }
        candidate.contentKey?.let { content ->
            for (r in core.rd().rows("SELECT id FROM lib_index WHERE content = ?1 AND space = ?2 AND media = ?3", listOf(content, wire, kind.wire))) out += r[0] as String
        }
        // candidates by title words: the normalised words of the candidate (and of the part after "Artist - ") must all be in the item's title
        val words = LinkedHashSet<List<String>>()
        fun add(title: String, artist: String?) {
            val w = app.keepfin.core.dedupe.Recordings.normalizeTitle(title, artist).split(' ').filter { it.length > 1 }.take(6)
            if (w.isNotEmpty()) words += w
        }
        add(candidate.title, candidate.artist)
        titleSplit.split(candidate.title, 2).takeIf { it.size == 2 && it[0].trim().isNotEmpty() && it[1].trim().isNotEmpty() }?.let { add(it[1].trim(), it[0].trim()) }
        for (w in words) {
            val match = "title : (" + w.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" } + ")"
            for (r in core.rd().rows("SELECT i.id FROM lib_index i WHERE i.n IN (SELECT rowid FROM lib_fts WHERE lib_fts MATCH ?1 LIMIT 400) AND i.space = ?2 AND i.media = ?3", listOf(match, wire, kind.wire))) out += r[0] as String
        }
        return out.toList()
    }

    private fun match(spaceId: String, kind: LibraryItemKind, candidate: Recording): ExistingMatch? {
        val b = core.backend()
        val ids = candidatesFor(spaceId, kind, candidate)
        if (ids.isEmpty()) return null
        val contents = HashMap<String, String?>()
        for (r in core.rd().rows("SELECT id, content FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(jsonList(ids)))) contents[r[0] as String] = r[1] as String?
        val library = core.readItems(ids).first.map { it to known(it, contents[it.id]) }
        val (item, by) = Recordings.find(candidate, library) ?: return null
        return ExistingMatch(item.id, item.place?.let { if (isLinkedPlace(it)) core.locators.get(item.id)?.uri ?: "" else b.uriFor(it) } ?: "", spaceId, by)
    }

    private fun folderSpace(id: String): Space? = core.small.spaces.firstOrNull { it.id == id && it.type == "folder" }?.toSpace()

    private fun kindFor(space: Space, url: String) = when (space.kind) {
        SpaceKind.MUSIC -> LibraryItemKind.AUDIO
        SpaceKind.VIDEO -> LibraryItemKind.VIDEO
        SpaceKind.MIXED -> if (Providers.defaultKind(url) == "audio") LibraryItemKind.AUDIO else LibraryItemKind.VIDEO
    }

    fun existing(spaceId: String, entries: List<PlaylistEntry>): Set<String> {
        val space = folderSpace(spaceId) ?: return emptySet()
        return entries.filter { entry ->
            val title = entry.title.orEmpty()
            if (title.isBlank() && SourceIds.of(entry.url).isEmpty()) return@filter false
            match(space.id, kindFor(space, entry.url), Recording(SourceIds.of(entry.url), null, entry.artist ?: entry.uploader, title, entry.album, entry.duration)) != null
        }.map { it.url }.toSet()
    }

    private fun recordingOf(media: MediaInfo): Recording {
        val ids = SourceIds.of(media.url) + SourceIds.of(media.webpageUrl) + SourceIds.of(media.match?.sourceUrl) + SourceIds.of(media.match?.matchedUrl)
        return Recording(ids, null, media.music?.artist ?: media.uploader, media.music?.track ?: media.title, media.music?.album, media.duration)
    }

    /**
     * The song, in any folder space, that [media] is the same recording as, and whether it keeps its music video. [existingFor] looks for the kind the
     * download will be, so a video never met the song it is the video of (it was kept as a stranger, or lost in a music space).
     */
    fun songOf(media: MediaInfo): SongOfLink? {
        val candidate = recordingOf(media)
        for (space in core.small.spaces.filter { it.type == "folder" }.map { it.toSpace() }) {
            val id = match(space.id, LibraryItemKind.AUDIO, candidate)?.itemId ?: continue
            val has = (core.rd().rows("SELECT musicVideo FROM lib_index WHERE id = ?", listOf(id)).firstOrNull()?.get(0) as? Number)?.toLong() == 1L
            return SongOfLink(id, space.id, has)
        }
        return null
    }

    fun existingFor(spaceId: String, media: MediaInfo): ExistingMatch? {
        val space = folderSpace(spaceId) ?: return null
        val candidate = recordingOf(media)
        val kind = if (space.kind == SpaceKind.MIXED) (if (media.video.isEmpty() && media.audio.isNotEmpty()) LibraryItemKind.AUDIO else kindFor(space, media.url)) else kindFor(space, media.url)
        return match(space.id, kind, candidate)
    }

    private fun jobItemsFile() = File(core.deviceDir, "job-items.json")

    /** Device-only: which item a finished download became (the history opens its file through it). */
    @Volatile var jobItems: Map<String, String> = emptyMap()

    /** The same, the other way round: item id to job id. */
    @Volatile var jobOfItem: Map<String, String> = emptyMap()

    fun readJobItems(): Map<String, String> {
        val f = jobItemsFile()
        if (!f.isFile) return emptyMap()
        return try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    internal fun itemOfJob(jobId: String): String? = jobItems[jobId]

    internal fun rememberJobItem(jobId: String, itemId: String) {
        val next = HashMap(jobItems)
        next[jobId] = itemId
        jobItems = next
        jobOfItem = next.entries.associate { it.value to it.key }
        val json = JSONObject()
        for ((k, v) in next) json.put(k, v)
        writeLocalAtomic(jobItemsFile(), json.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Publishes a finished download (kept in app-private storage until now) into the space folder (temp name, verify, rename), adds
     * the item to the library and to its collection. Throws a [FolderError] when the copy failed; nothing is claimed then and the
     * private file is untouched. Sidecars are best effort and reported through [SavedDownload.sidecarProblems].
     */
    internal fun saveDownload(job: Job, tags: SourceTags?, spaceId: String, subfolder: String, stem: String, media: File, sidecars: List<File>, chapters: List<app.keepfin.core.library.LibChapter>? = null): SavedDownload {
        val b = core.backend()
        val fileName = if (media.extension.isEmpty()) stem else "$stem.${media.extension}"
        val kind = kindOf(fileName, null)
        val audio = kind == "audio"
        val hasLyrics = audio && (sidecars.any { it.extension.equals("lrc", ignoreCase = true) } || core.host.tagTools?.takeIf { it.available() }?.metadata(media)?.let(FfOutput::lyrics) != null)
        val template = LibItem(
            id = "", space = spaceId, kind = kind, title = (tags?.title ?: job.title).ifBlank { stem },
            artist = if (audio) tags?.artist ?: job.uploader else tags?.artist, album = tags?.album, track = tags?.track?.toDouble(), year = tags?.year?.toDouble(),
            duration = job.duration, sourceUrl = job.match?.sourceUrl ?: job.url, sourceId = SourceIds.primary(job), addedAt = core.now().toDouble(),
            tags = if (core.host.settings.value.library.importTags) job.tags.takeIf { it.isNotEmpty() } else null,
            chapters = if (kind == "video") chapters else null, hasLyrics = if (hasLyrics) true else null,
        )
        val added = core.addFile(spaceId, subfolder, fileName, media.inputStream(), template)
        val place = core.placeOf(added.itemId) ?: throw FolderError.Io("The saved file could not be found in the Keepfin folder.")
        val problems = ArrayList<String>()
        val uris = ArrayList<String>()
        if (added.duplicateOf == null) {
            val dir = place.substringBeforeLast('/', "")
            val placedStem = place.substringAfterLast('/').substringBeforeLast('.', place.substringAfterLast('/'))
            val records = ArrayList<StoreDraft>()
            for (side in sidecars) {
                val suffix = side.name.removePrefix(media.nameWithoutExtension)
                try {
                    val target = core.uniqueIn(b, dir, placedStem + suffix)
                    val saved = side.inputStream().use { b.folder.publishFrom(join(dir, target), it) }
                    uris += b.uriFor(join(dir, target))
                    // lyrics, subtitles and the cover beside the file travel with it to every copy (the core's rules: Companions.kt)
                    core.companionRecord(added.itemId, place, target, "sha256:${saved.sha256}", saved.size)?.let { records += it }
                } catch (e: FolderError) {
                    problems += "${side.name}: ${e.message}"
                }
            }
            if (records.isNotEmpty()) runCatching { b.store.apply(records).get(60, TimeUnit.SECONDS) }.onFailure { core.log("The lyrics and subtitles of a download could not be recorded.", it) }
        }
        rememberJobItem(job.id, added.itemId)
        job.collectionId?.let { collectionId ->
            val reverse = jobItems.entries.associate { it.value to it.key }
            val jobsById = core.host.jobs.value.associateBy { it.id }
            val flagged = core.followJobs.remove(job.id) // queued by a follow check: the item shows up as new
            core.updateCollection(collectionId) { c ->
                if (added.itemId in c.itemIds) c
                else c.copy(
                    itemIds = CollectionLogic.insertInOrder(c.itemIds, added.itemId, job.collectionIndex) { other -> reverse[other]?.let { jobsById[it]?.collectionIndex } },
                    coverItemId = c.coverItemId ?: added.itemId, removedItemIds = c.removedItemIds - added.itemId,
                    newItemIds = if (flagged) (c.newItemIds + added.itemId).distinct() else c.newItemIds,
                )
            }
        }
        runCatching { core.publish() }
        return SavedDownload(added.itemId, b.uriFor(place), place, uris, problems, added.duplicateOf != null)
    }

    /** The collection id a download of [sourceUrl] belongs to: the existing one for the same canonical link, else [preferred] (when it is an id) or a new one. */
    internal fun collectionIdFor(sourceUrl: String, preferred: String?): String {
        val key = CollectionLogic.sourceKey(sourceUrl)
        val all = core.small.collections
        return all.firstOrNull { c -> c.kind != "own" && c.source?.let { CollectionLogic.sourceKey(it.url) } == key }?.id
            ?: preferred?.takeIf { wanted -> app.keepfin.core.sync.isUuid(wanted) && all.none { it.id == wanted } } ?: core.newId()
    }

    /** Creates (or reuses, by canonical source link) the collection of a queued playlist, album or channel. */
    internal fun ensureCollection(sourceUrl: String, name: String, kind: CollectionKind, space: String, seenKeys: Collection<String>, infinite: Boolean, preferredId: String?, sync: SyncRequest? = null): LibraryCollection {
        val key = CollectionLogic.sourceKey(sourceUrl)
        val time = core.now().toDouble()
        var result: LibCollection? = null
        core.commitCollections { all ->
            val existing = all.firstOrNull { c -> c.kind != "own" && c.source?.let { CollectionLogic.sourceKey(it.url) } == key }
            if (existing != null) {
                val current = existing.follow?.toFollow(core.follows[existing.id])
                val follow = if (sync == null) existing.follow else CollectionLogic.followFor(CollectionKind.entries.firstOrNull { it.wire == existing.kind } ?: kind, CollectionLogic.infinite(existing.source?.url.orEmpty()), sync, current)?.toLib()
                val merged = existing.copy(seen = (existing.seen + seenKeys).distinct(), follow = follow, updatedAt = time)
                result = merged
                all.map { if (it.id == existing.id) merged else it }
            } else {
                val id = preferredId?.takeIf { wanted -> app.keepfin.core.sync.isUuid(wanted) && all.none { it.id == wanted } } ?: core.newId()
                val source = CollectionLogic.canonicalSource(sourceUrl)
                val created = LibCollection(
                    id = id, name = name.trim().ifEmpty { "Collection" }.take(80), kind = kind.wire, space = space, itemIds = emptyList(), seen = seenKeys.distinct(),
                    createdAt = time, updatedAt = time, source = LibSource(source.url, source.provider, source.id), follow = CollectionLogic.followFor(kind, infinite, sync)?.toLib(),
                )
                result = created
                all + created
            }
        }
        return result!!.toCollection(core.follows)
    }

    /** Job start: the match when the space skips existing recordings and one is already there. */
    internal fun findExisting(job: Job, record: Recording): ExistingMatch? {
        if (!core.host.settings.value.library.enabled || job.force) return null
        val space = folderSpace(job.space ?: return null) ?: return null
        if (!space.skipExisting) return null
        return match(space.id, if (job.kind == app.keepfin.MediaKind.AUDIO) LibraryItemKind.AUDIO else LibraryItemKind.VIDEO, record)
    }
}
