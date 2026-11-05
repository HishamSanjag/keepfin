package app.keepfin.core.spaces

import app.keepfin.core.music.jsTrim

/**
 * The words and the one counting rule of the download preview (dev-docs/plans/DOWNLOAD-PREVIEW.md), the same on Windows and the phone.
 * The screens stay each app's own; what both must say alike lives here.
 */
object PreviewWords {
    /** Both (a song with its video beside it): a music link's official video is still being looked for. */
    const val BOTH_LOOKING = "Looking for the music video…"

    /** Both is off because no official video was found (or the look failed). */
    const val BOTH_NONE = "No official music video was found, so Both is off."

    /** Both with an official video that was found: the pair stays in step. */
    const val BOTH_FOUND = "The song is saved with this video beside it. They stay on the same second."

    /** Both on a YouTube link: the link itself is the video. */
    const val BOTH_LINK = "The song is saved with this video beside it."

    /** The footer button when no playlist is chosen, and the title of its menu. */
    const val PLAYLIST_ADD = "Add to playlist"

    /** The menu choice that clears the playlist. */
    const val PLAYLIST_NONE = "No playlist"

    /** The menu choice that makes a playlist first. */
    const val PLAYLIST_NEW = "New playlist…"

    /** The footer button: "Add to playlist", or the chosen playlist's name. */
    fun playlistLabel(name: String?): String = if (name == null || jsTrim(name).isEmpty()) PLAYLIST_ADD else name

    /**
     * How many groups on the Extras tab differ from the person's defaults: one for each group changed. The screens decide, from their own
     * options, which groups differ and pass that here; [queue] is the collection-only choice that lives outside the options.
     */
    fun extrasCount(subtitles: Boolean, cut: Boolean, sponsor: Boolean, chapters: Boolean, inFile: Boolean, beside: Boolean, queue: Boolean): Int =
        listOf(subtitles, cut, sponsor, chapters, inFile, beside, queue).count { it }

    /** The Extras tab's label: "Extras", or "Extras · 2" once something is changed. */
    fun extrasLabel(changed: Int): String = if (changed > 0) "Extras · $changed" else "Extras"
}
