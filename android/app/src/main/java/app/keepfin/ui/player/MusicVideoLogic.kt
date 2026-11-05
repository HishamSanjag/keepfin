package app.keepfin.ui.player

import app.keepfin.ui.library.VideoRequest
import java.util.Locale

/**
 * The pure decisions behind a song's music video (dev-docs/plans/MUSIC-VIDEOS.md): what the player surfaces do when the playing entry changes, and the
 * words the "Get the music video" sheet shows. Where a switch goes on is the core's ([app.keepfin.core.spaces.Songs.switchStart]).
 */
object MusicVideoLogic {
    /** What the surfaces do when the entry that plays changes. */
    sealed interface Follow {
        data object Nothing : Follow
        /** Show this song's music video on the video screen. */
        data class Open(val id: String) : Follow
        /** The video side ended in a song (or the file was missing): back to the music player. */
        data object Close : Follow
    }

    /**
     * [cur] is the entry that plays, [shown] the video screen's request, [onPlayer] whether the music player is open. The video screen follows
     * the queue: while the screen is open the next song's video replaces it, a song without one closes it; with no video screen the picture is shown in place in the full player and needs no request.
     * An entry the playback service has not resolved yet says nothing about itself.
     */
    fun follow(cur: QueueEntry?, shown: VideoRequest?, onPlayer: Boolean): Follow = when {
        cur == null || !cur.resolved -> Follow.Nothing
        cur.musicVideo -> if (shown != null && (shown.id != cur.id || !shown.musicVideo)) Follow.Open(cur.id) else Follow.Nothing
        shown?.musicVideo == true -> Follow.Close
        else -> Follow.Nothing
    }

    /** "3.2B views", "512K views", "812 views", "1 view"; null when the count is unknown. */
    fun views(count: Long?): String? {
        if (count == null || count < 0) return null
        fun scaled(value: Double, unit: String): String {
            val text = if (value < 10) String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") else value.toLong().toString()
            return "$text$unit views"
        }
        return when {
            count == 1L -> "1 view"
            count >= 1_000_000_000L -> scaled(count / 1_000_000_000.0, "B")
            count >= 1_000_000L -> scaled(count / 1_000_000.0, "M")
            count >= 1_000L -> scaled(count / 1_000.0, "K")
            else -> "$count views"
        }
    }

    /** "3:35" or "1:02:03"; null when the length is unknown. */
    fun length(seconds: Double?): String? {
        if (seconds == null || !seconds.isFinite() || seconds <= 0) return null
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
