package app.keepfin.core.spaces

/** What the video row of a song reads of a library item. Times are milliseconds since the epoch, lengths seconds, sizes bytes. */
class VideoSource(
    val id: String,
    val kind: String,
    val title: String,
    val artist: String?,
    val hasVideo: Boolean,
    val videoDuration: Double? = null,
    val videoSize: Double? = null,
    val addedAt: Long = 0,
)

/** A song's music video as a row of its own. [itemId] is the song; the cover is the song's. */
class MusicVideoRow(
    val id: String,
    val itemId: String,
    val title: String,
    val artist: String?,
    val duration: Double?,
    val size: Double?,
    val addedAt: Long,
)

/**
 * A song's music video shown on its own (dev-docs/plans/MUSIC-VIDEOS.md, "A video is usable on its own"). The file stays the song's companion:
 * the row is only how it is shown, so it is never counted as a library item, and it joins no playlist, favorites or tags (those stay
 * the song's). Both apps list, name and word it here.
 */
object MusicVideoRows {
    const val LABEL = "Music video"
    const val SHELF = "Music videos"
    const val SEE_ALL = "See all"
    const val GO_TO_SONG = "Go to song"
    const val DELETE = "Delete video"
    const val DELETE_ASK = "Delete this music video? The song stays."

    /** Capture, Video tab: the link is a song you have and this video will be added to it. */
    const val JOIN_NOTE = "You have this song. This video will be added to it."

    /** Capture, Video tab: the song you have already has its video. */
    const val HAS_VIDEO_NOTE = "You have this song and its video."

    /** Capture, Audio tab of a YouTube link: also keep the link itself as the song's video. */
    const val KEEP_LINK = "Also keep this video"

    /** Said when a download is refused because the song has its video. */
    const val HAS_VIDEO_ERROR = "This song already has its music video."

    private const val SUFFIX = "#video"

    /** The id of a song's video row. */
    fun rowId(itemId: String): String = itemId + SUFFIX

    /** The song's id behind a video row id, null when [rowId] is not one. */
    fun itemIdOf(rowId: String): String? = if (rowId.endsWith(SUFFIX) && rowId.length > SUFFIX.length) rowId.removeSuffix(SUFFIX) else null

    /** The rows of [items]: songs (audio) that have a video, newest first, then by title and id. */
    fun list(items: List<VideoSource>): List<MusicVideoRow> =
        items.filter { it.kind == "audio" && it.hasVideo }
            .map { MusicVideoRow(rowId(it.id), it.id, it.title, it.artist, it.videoDuration?.takeIf { d -> d > 0 }, it.videoSize?.takeIf { s -> s > 0 }, it.addedAt) }
            .sortedWith(compareByDescending<MusicVideoRow> { it.addedAt }.thenBy { it.title.lowercase() }.thenBy { it.id })
}
