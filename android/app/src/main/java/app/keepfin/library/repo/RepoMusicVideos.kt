package app.keepfin.library.repo

import app.keepfin.library.repo.LibraryRepository.AttachedVideo
import app.keepfin.FfOutput
import app.keepfin.LibraryAccess
import app.keepfin.Lyrics
import app.keepfin.LyricsParser
import app.keepfin.SubtitleFile
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FolderError
import app.keepfin.folder.OpDraft as StoreDraft
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** The files beside a song (lyrics, subtitles) and its music video. Inner state is reached through [RepoCore]. */
internal class RepoMusicVideos(private val core: RepoCore) {
    fun lyrics(id: String): Lyrics? {
        val b = core.backend()
        val place = core.placeOf(id) ?: return null
        for (side in core.sidecarsOf(b, place).filter { it.endsWith(".lrc", ignoreCase = true) }) {
            val text = try { b.folder.read(side).toString(Charsets.UTF_8) } catch (e: FolderError) { null }
            LyricsParser.parse(text ?: continue)?.let { return it }
        }
        return embeddedLyrics(b, id, place)
    }

    /** Lyrics inside the song: a download keeps them there when it can, and a .lrc file is only its fallback. */
    private fun embeddedLyrics(b: RepositoryBackend, id: String, place: String): Lyrics? {
        val tools = core.host.tagTools?.takeIf { it.available() } ?: return null
        val temp = File.createTempFile("lyrics", "." + place.substringAfterLast('.', "tmp"))
        return try {
            val source = if (isLinkedPlace(place)) core.locators.get(id)?.let { core.imports?.open(it.uri) } else b.folder.openRead(place)
            (source ?: return null).use { input -> temp.outputStream().use { input.copyTo(it) } }
            tools.metadata(temp)?.let(FfOutput::lyrics)?.let(LyricsParser::parse)
        } catch (e: Exception) {
            core.log("The lyrics inside a song could not be read.", e)
            null
        } finally {
            temp.delete()
        }
    }

    fun chapters(id: String): List<app.keepfin.LibraryChapter> =
        try { core.libItem(id)?.chapters.orEmpty().map { app.keepfin.LibraryChapter(it.start, it.title) } } catch (e: Exception) { emptyList() }

    fun subtitles(id: String): List<SubtitleFile> {
        val b = core.backend()
        val place = core.placeOf(id) ?: return emptyList()
        val stem = place.substringAfterLast('/').substringBeforeLast('.')
        return core.sidecarsOf(b, place).filter { it.substringAfterLast('.').lowercase() in app.keepfin.SUBTITLE_EXTENSIONS }.map { path ->
            val name = path.substringAfterLast('/')
            val extension = name.substringAfterLast('.').lowercase()
            val language = name.removePrefix(stem).removeSuffix(".$extension").trim('.').takeIf { it.isNotEmpty() }
            SubtitleFile(b.uriFor(path), language, language?.let { java.util.Locale.forLanguageTag(it.substringBefore('-')).displayLanguage.takeIf { n -> n.isNotBlank() } } ?: "Subtitles",
                when (extension) { "srt" -> "application/x-subrip"; "ass", "ssa" -> "text/x-ssa"; else -> "text/vtt" })
        }.sortedBy { it.label }
    }

    // ------------------------------------------------------------------------------------------------ music videos (dev-docs/plans/MUSIC-VIDEOS.md)

    /** The music video file beside the song at [place] (`Song.video.mp4`), as a folder path, or null. */
    private fun videoBeside(b: RepositoryBackend, place: String): String? =
        core.sidecarsOf(b, place).firstOrNull { app.keepfin.core.copies.videoCompanionStem(it.substringAfterLast('/')) != null }

    fun musicVideoUri(id: String): String? {
        val b = core.backend()
        val place = core.placeOf(id) ?: return null
        return videoBeside(b, place)?.let { b.uriFor(it) }
    }

    /**
     * A finished music video download [media] becomes the video of the song [itemId]: copied (staged, verified, renamed; never over a
     * file) beside the song as `<song stem>.video.<ext>` and recorded on the song, so copies carry it. It is never a library item of
     * its own. Null when it cannot be the song's video (the song is gone or not in the Keepfin folder, already has one, or the
     * container is not one the players use): the caller keeps the download as an ordinary one. A [FolderError] when the copy failed
     * (nothing was published; the private file is untouched).
     */
    internal fun attachMusicVideo(itemId: String, media: File): AttachedVideo? {
        val b = core.backend()
        val item = core.libItem(itemId) ?: return null
        val place = item.place ?: return null
        if (item.kind != "audio" || isLinkedPlace(place) || b.folder.stat(place) == null) return null
        val suffix = app.keepfin.core.copies.videoCompanionSuffix(media.extension) ?: return null
        if (videoBeside(b, place) != null) return null
        val stem = place.substringAfterLast('/').substringBeforeLast('.')
        val target = join(place.substringBeforeLast('/', ""), stem + suffix)
        val saved = try { media.inputStream().use { b.folder.publishFrom(target, it) } }
        catch (e: FolderError.NameConflict) { return null }
        catch (e: Exception) { runCatching { b.folder.remove(target) }; throw e }
        core.companionRecord(itemId, place, target.substringAfterLast('/'), "sha256:${saved.sha256}", saved.size)?.let { record ->
            try { b.store.apply(listOf(record)).get(60, TimeUnit.SECONDS) } catch (e: Exception) {
                // the file is beside the song but unrecorded: the next look at the folder records it; the song still plays and shows it
                core.log("The music video could not be recorded.", e)
            }
        }
        // the file beside the song is the song's video: an item the scanner listed for it (same place and content) goes, so no "missing" ghost is left
        runCatching { core.itemWithContentAt(target, "sha256:${saved.sha256}")?.takeIf { it != itemId }?.let { core.removeItems(setOf(it)) } }
        runCatching { core.publish() }
        return AttachedVideo(itemId, target, b.uriFor(target))
    }

    fun musicVideoFacts(id: String): app.keepfin.MusicVideoFacts? {
        val b = core.backend()
        val path = videoBeside(b, core.placeOf(id) ?: return null) ?: return null
        return b.folder.stat(path)?.let { app.keepfin.MusicVideoFacts(b.uriFor(path), it.size) }
    }

    /** Lyrics found for a song: kept as `<song stem>.lrc` beside it and recorded on it, as a finished download keeps one, so copies and sync carry it. */
    fun saveLyrics(id: String, text: String) {
        val b = core.backend()
        val place = core.placeOf(id)?.takeIf { !isLinkedPlace(it) } ?: throw IllegalStateException("Lyrics can't be saved beside this song.")
        val name = place.substringAfterLast('/').substringBeforeLast('.') + ".lrc"
        val saved = try { text.toByteArray(Charsets.UTF_8).inputStream().use { b.folder.publishFrom(join(place.substringBeforeLast('/', ""), name), it) } }
        catch (e: FolderError.NameConflict) { throw IllegalStateException("A lyrics file with this name is already beside the song.") }
        core.companionRecord(id, place, name, "sha256:${saved.sha256}", saved.size)?.let { record ->
            try { b.store.apply(listOf(record)).get(60, TimeUnit.SECONDS) } catch (e: Exception) { core.log("The lyrics could not be recorded.", e) }
        }
        runCatching { core.publish() }
    }

    fun removeMusicVideo(id: String): LibraryAccess {
        val b = core.backend()
        val place = core.placeOf(id) ?: throw IllegalStateException("This song is not on this device.")
        if (isLinkedPlace(place)) throw IllegalStateException("This song has no music video.")
        val files = core.sidecarsOf(b, place).filter { app.keepfin.core.copies.videoCompanionStem(it.substringAfterLast('/')) != null }
        val drafts = ArrayList<StoreDraft>()
        val fields = recordedCompanionSizes(id).keys.filter { it.startsWith("companion:.video.") }.toMutableSet()
        for (path in files) {
            app.keepfin.core.copies.companionSuffix(place.substringAfterLast('/'), path.substringAfterLast('/'))?.let { fields += app.keepfin.core.copies.companionField(it) }
        }
        for (path in files) b.folder.remove(path)
        for (field in fields) drafts += StoreDraft("item.set", mapOf("item" to app.keepfin.core.sync.JStr(id), "field" to app.keepfin.core.sync.JStr(field), "value" to app.keepfin.core.sync.JNull))
        if (drafts.isNotEmpty()) b.store.apply(drafts).get(60, TimeUnit.SECONDS)
        runCatching { core.publish() }
        return LibraryAccess.Done
    }

    /** The companion fields an item records, by field name to the size of the file. */
    private fun recordedCompanionSizes(id: String): Map<String, Long> {
        val text = core.rd().rows("SELECT CASE WHEN instr(fields, '\"companion:') > 0 THEN fields END FROM item_view WHERE id = ?", listOf(id)).firstOrNull()?.get(0) as? String ?: return emptyMap()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().filter { it.startsWith("companion:") }.mapNotNull { k -> o.optJSONObject(k)?.let { k to it.optLong("size", -1L) } }.toMap()
    }

    /**
     * Records the lyrics, subtitles and music video beside restored songs that have no record (or whose file has another size): an item
     * that comes back from the trash is written again without them. Each file is read once.
     */
    fun recordCompanionsOf(b: RepositoryBackend, songs: List<Triple<String, String, String>>) {
        val drafts = ArrayList<StoreDraft>()
        for ((id, place, kind) in songs) {
            if (isLinkedPlace(place)) continue
            val recorded = recordedCompanionSizes(id)
            for (side in core.sidecarsOf(b, place)) {
                val name = side.substringAfterLast('/')
                val suffix = app.keepfin.core.copies.companionSuffix(place.substringAfterLast('/'), name) ?: continue
                if (app.keepfin.core.copies.isVideoCompanion(suffix) && kind != "audio") continue
                val size = b.folder.stat(side)?.size ?: continue
                if (recorded[app.keepfin.core.copies.companionField(suffix)] == size) continue
                val sha = try { b.folder.openRead(side).use(::sha256Hex) } catch (e: Exception) { continue }
                core.companionRecord(id, place, name, "sha256:$sha", size)?.let { drafts += it }
            }
        }
        if (drafts.isNotEmpty()) b.store.apply(drafts).get(60, TimeUnit.SECONDS)
    }
}
