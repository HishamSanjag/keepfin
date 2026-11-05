package app.keepfin.core.providers

import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim

// Where a download goes and what it is called: sub-folders and file name stems.
//
// Two algorithms live here because the apps' libraries differ on purpose-less but user-visible details (folder for an
// unknown artist, name length, who counts as the artist) and changing either would move existing users' files:
//  - subfolders / stem: the desktop app (gui/shared/library.ts outputSubfolders, outputStem).
//  - spaceSubfolder / spaceStem: the Android library (LibraryLogic.kt SpaceLogic).
// Both are pure; the date comes from a parameter. The fixtures in fixtures/engine/providers/naming.json pin each one.

/** Everything the desktop naming reads from a job. [groupTitle] is null when the job is not part of a collection. */
data class PathJob(
    val url: String,
    val title: String,
    val uploader: String? = null,
    /** Milliseconds since the epoch (UTC). */
    val createdAt: Long = 0,
    val groupTitle: String? = null,
    val musicArtist: String? = null,
    val musicAlbum: String? = null,
    val musicTrack: String? = null,
)

/** Everything the Android library naming reads from a saved file. */
data class SpaceContext(
    val title: String,
    val uploader: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Link used for the platform layout (the source link for Spotify/Apple/Deezer jobs). */
    val url: String = "",
    val groupTitle: String? = null,
    val groupKind: String? = null,
    val audio: Boolean = false,
    val dateMillis: Long = 0,
)

object Naming {
    private fun isBlank(value: String?) = value == null || jsTrim(value).isEmpty()

    /** `YYYY-MM-DD` of a time in UTC. */
    fun isoDate(millis: Long): String {
        val days = millis.floorDiv(86_400_000L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + (if (month <= 2) 1 else 0)
        fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"
    }

    private fun endsWithIgnoringAsciiCase(value: String, suffix: String) =
        value.length >= suffix.length && asciiLower(value.substring(value.length - suffix.length)) == suffix

    private fun isJsSpace(c: Char) = jsTrim(c.toString()).isEmpty()

    /** "Daft Punk - Topic" (YouTube's auto-generated artist channels) and "...VEVO" -> the artist; null when nothing is left. */
    fun channelArtist(uploader: String?): String? {
        if (uploader == null) return null
        var text = uploader
        // The suffix: whitespace, a dash, whitespace, "topic", then the end.
        if (endsWithIgnoringAsciiCase(text, "topic")) {
            var end = text.length - 5
            var spaces = 0
            while (end > 0 && isJsSpace(text[end - 1])) { end--; spaces++ }
            if (spaces > 0 && end > 0 && text[end - 1] == '-') {
                end--
                var before = 0
                while (end > 0 && isJsSpace(text[end - 1])) { end--; before++ }
                if (before > 0) text = text.substring(0, end)
            }
        }
        if (endsWithIgnoringAsciiCase(text, "vevo")) text = text.substring(0, text.length - 4)
        return jsTrim(text).ifEmpty { null }
    }

    /** The artist behind a channel name for the Android library: only an exact " - Topic" suffix goes. */
    fun artistOf(author: String?): String? {
        if (author == null) return null
        val trimmed = jsTrim(author)
        return jsTrim(if (trimmed.endsWith(" - Topic")) trimmed.removeSuffix(" - Topic") else trimmed).ifEmpty { null }
    }

    // ---- desktop ---------------------------------------------------------------------------------------------

    /** Sub-folder segments inside a space for a layout (`flat`, `collection`, `platform` or `artist-album`). */
    fun subfolders(layout: String, job: PathJob): List<String> = when (layout) {
        "collection" -> if (job.groupTitle != null) listOf(Providers.safeName(job.groupTitle)) else emptyList()
        "platform" -> listOf(Providers.safeName(Providers.folder(job.url)))
        "artist-album" -> {
            val artist = job.musicArtist ?: channelArtist(job.uploader)
            when {
                artist.isNullOrEmpty() -> emptyList()
                !job.musicAlbum.isNullOrEmpty() -> listOf(Providers.safeName(artist), Providers.safeName(job.musicAlbum))
                else -> listOf(Providers.safeName(artist))
            }
        }
        else -> emptyList()
    }

    /** File name stem (no extension) for a job in a space with this naming style. */
    fun stem(naming: String, job: PathJob): String {
        val title = Providers.safeName(job.title)
        val uploader = if (!job.uploader.isNullOrEmpty()) Providers.safeName(job.uploader) else ""
        return when (naming) {
            "title-uploader" -> if (uploader.isNotEmpty()) Providers.safeName("$title - $uploader") else title
            "uploader-title" -> if (uploader.isNotEmpty()) Providers.safeName("$uploader - $title") else title
            "date-title" -> Providers.safeName("${isoDate(job.createdAt)} - $title")
            "artist-title" -> {
                val artist = job.musicArtist ?: channelArtist(job.uploader)
                val track = Providers.safeName(job.musicTrack ?: job.title)
                if (artist.isNullOrEmpty()) {
                    track
                } else {
                    val lead = Providers.safeName(artist)
                    if (track.lowercase().startsWith(lead.lowercase() + " - ")) track else Providers.safeName("$lead - $track")
                }
            }
            else -> title
        }
    }

    // ---- Android library -------------------------------------------------------------------------------------

    /** Sub-path ("" or "A/B") below the space folder for a file saved with a layout. */
    fun spaceSubfolder(layout: String, ctx: SpaceContext): String = when (layout) {
        "collection" -> if (isBlank(ctx.groupTitle)) "" else Providers.safeName(ctx.groupTitle!!)
        "platform" -> if (isBlank(ctx.url)) "" else Providers.folder(ctx.url)
        "artist-album" -> if (!ctx.audio) "" else {
            val artist = artistOf(ctx.artist ?: ctx.uploader)?.let { Providers.safeName(it) } ?: "Unknown artist"
            val album = (ctx.album ?: ctx.groupTitle?.takeIf { ctx.groupKind == "album" })?.takeIf { !isBlank(it) }?.let { Providers.safeName(it) }
            if (album != null) "$artist/$album" else artist
        }
        else -> ""
    }

    /** File name (without extension) in the style [naming] (`title`, `title-uploader`, `uploader-title`, `date-title`, `artist-title`). */
    fun spaceStem(naming: String, ctx: SpaceContext): String {
        val title = if (isBlank(ctx.title)) "Untitled" else ctx.title
        val uploader = artistOf(ctx.uploader)
        val raw = when (naming) {
            "title-uploader" -> if (uploader != null) "$title - $uploader" else title
            "uploader-title" -> if (uploader != null) "$uploader - $title" else title
            "date-title" -> "${isoDate(ctx.dateMillis)} - $title"
            "artist-title" -> (artistOf(ctx.artist) ?: uploader)?.let { "$it - $title" } ?: title
            else -> title
        }
        return Providers.safeName(raw, 120)
    }
}
