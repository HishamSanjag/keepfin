package app.keepfin

import app.keepfin.core.spaces.MusicVideoRows

/**
 * A video download of a recording that is a song you have joins that song, the way "Get the music video" does (dev-docs/plans/MUSIC-VIDEOS.md): the
 * same job with the song to attach to, saved beside it in its space. It never becomes a second item and is never lost in a music space.
 * Pure, so the rules are tested without an engine; Windows does the same (gui/desktop/manager/musicExtras.ts `joinSong`).
 */
internal object SongJoin {
    class Joined(val itemId: String, val space: String, val format: FormatRequest.Video, val options: DownloadOptions)

    /**
     * Nothing when [format] is not a video or [song] is null. Refused, in words, when the song has its video or one is on the way ([videoComing]).
     * The container becomes one a companion can be (mp4 for mkv), and nothing that writes extra files or cuts the video is kept.
     */
    fun of(song: SongOfLink?, videoComing: Boolean, format: FormatRequest, options: DownloadOptions): Joined? {
        if (format !is FormatRequest.Video || song == null) return null
        check(!song.hasVideo && !videoComing) { MusicVideoRows.HAS_VIDEO_ERROR }
        val video = if (format.container == "mkv") format.copy(container = "mp4") else format
        return Joined(song.itemId, song.space, video, options.copy(subtitles = SubtitleOptions(), sectionStart = null, sectionEnd = null, writeThumbnail = false))
    }
}
