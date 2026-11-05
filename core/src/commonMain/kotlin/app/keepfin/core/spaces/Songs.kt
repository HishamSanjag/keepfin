package app.keepfin.core.spaces

/**
 * What counts as a song (dev-docs/plans/MUSIC-VIDEOS.md, Part A): audio anywhere, and a video kept in a music space, which is listed
 * with the songs and plays as its sound. A space never hides what it holds: its counts and every music list read this one rule.
 */
object Songs {
    /** Beside a song that is a video file. */
    const val VIDEO_TAG = "Video"

    /** Opens the picture of a song that is a video. */
    const val WATCH = "Watch video"

    /** [kind]: the item's kind (audio, video, image, other); [spaceKind]: its space's kind (music, video, mixed), null when unknown. */
    fun isSong(kind: String, spaceKind: String?): Boolean = kind == "audio" || (kind == "video" && spaceKind == "music")

    /** A song that is a video file: it shows the Video tag and offers Watch video. */
    fun isVideoSong(kind: String, spaceKind: String?): Boolean = kind == "video" && spaceKind == "music"

    /** How far from the end of the other side a switch may land (seconds): its last second still plays, then it finishes as it would. */
    const val SWITCH_END_MARGIN = 1.0

    /**
     * Song | Video keeps one place (dev-docs/plans/MUSIC-VIDEOS.md): the other side starts at the second you were at, in seconds. [otherLength] is
     * its length, null or zero when unknown. A place past the other side's end, or in its last second, lands [SWITCH_END_MARGIN] before the end,
     * so that side plays what is left and finishes on its own (the end of the shorter side). A negative or unusable place starts at 0.
     */
    fun switchStart(position: Double, otherLength: Double?): Double {
        val at = if (position.isFinite() && position > 0) position else 0.0
        val length = otherLength?.takeIf { it.isFinite() && it > 0 } ?: return at
        return minOf(at, maxOf(0.0, length - SWITCH_END_MARGIN))
    }
}
