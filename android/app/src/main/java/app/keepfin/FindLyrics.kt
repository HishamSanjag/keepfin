package app.keepfin

import app.keepfin.core.lyrics.Lyrics as CoreLyrics
import app.keepfin.core.lyrics.LyricsQuery

/**
 * "Find lyrics" on a song that has none (dev-docs/plans/MUSIC-VIDEOS.md): lyrics are looked up once, when a song finishes downloading, so a song that
 * missed them stays without. This asks again with the same lookup (and its retries) and keeps what comes the way a finished download does. The
 * outcome and its words are the core's, so Windows and the phone read alike. Pure over the lookup and the store, so each outcome is tested.
 */
internal object FindLyrics {
    /**
     * Returns the core's outcome ("found", "busy" or "none"); [search] asks the lookup, [save] keeps the text
     * (it may throw: the caller says so).
     */
    fun run(item: LibraryItem, search: (LyricsQuery) -> LyricsLookup.Answer, save: (String) -> Unit): String {
        val artist = (item.artist ?: item.albumArtist)?.takeIf { it.isNotBlank() } ?: return CoreLyrics.outcome(found = false, gaveUpOnBusy = false)
        val answer = search(LyricsQuery(item.title, artist, item.album, item.duration?.takeIf { it > 0 }))
        val text = answer.text ?: return CoreLyrics.outcome(found = false, gaveUpOnBusy = answer.busy)
        save(text)
        return CoreLyrics.outcome(found = true, gaveUpOnBusy = false)
    }
}
