package app.keepfin.ui.model

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.keepfin.EngineApi
import app.keepfin.FindLyrics
import app.keepfin.FormatRequest
import app.keepfin.LibraryApi
import app.keepfin.LibraryItem
import app.keepfin.LyricsLookup
import app.keepfin.MediaInfo
import app.keepfin.MusicVideoMatch
import app.keepfin.Providers
import app.keepfin.SongOfLink
import app.keepfin.SourceIds
import app.keepfin.core.lyrics.Lyrics
import app.keepfin.core.spaces.Songs
import app.keepfin.ui.Capture
import app.keepfin.ui.StudioViewModel.Companion.friendly
import app.keepfin.ui.player.MusicVideoLengths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [SongVideoFlow] uses of the ViewModel. */
internal interface SongVideoFlowHost : Notices {
    val scope: CoroutineScope
    val engine: EngineApi
    val lib: LibraryApi
    val capture: Capture
    val libraryEnabled: Boolean
    fun <T : Application> getApplication(): T
}

/**
 * Songs and their videos (dev-docs/plans/MUSIC-VIDEOS.md), as the screens ask for them: what Capture says about a link that is a song you have, the
 * YouTube link kept as its own video, where the other side of Song | Video starts, and Find lyrics. The rules are the core's (Songs, MusicVideoRows, Lyrics).
 */
internal class SongVideoFlow(private val host: SongVideoFlowHost) {
    /** Capture, Video tab: the song you have behind the inspected link (the video will be added to it). Null while unknown or when it matches none. */
    var captureSong by mutableStateOf<SongOfLink?>(null)
        private set

    /** Capture, Audio tab of a YouTube link: also keep the link itself as the song's video. Off until the person checks it. */
    var keepLink by mutableStateOf(false)
        private set

    /** Grows when lyrics were found for a song: an open lyrics panel looks again. */
    var lyricsStamp by mutableIntStateOf(0)
        private set

    fun chooseKeepLink(on: Boolean) { keepLink = on }

    /** The lyrics lookup "Find lyrics" asks (LRCLIB, with its retries); tests put their own. */
    var lyricsSearch: (app.keepfin.core.lyrics.LyricsQuery) -> LyricsLookup.Answer = { LyricsLookup.search(it) }

    /** Forgets both: a new link, a closed review. */
    fun reset() { captureSong = null; keepLink = false }

    /** Looks for the song behind [media] in the background; a late answer for another link is dropped. */
    fun lookForSong(media: MediaInfo) {
        captureSong = null
        if (!host.libraryEnabled) return
        host.scope.launch {
            val found = try { withContext(Dispatchers.IO) { host.lib.songOf(media) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if ((host.capture as? Capture.Single)?.media?.url == media.url) captureSong = found
        }
    }

    /** The YouTube video id of a link that is a plain YouTube video (not YouTube Music), else null. */
    private fun ownVideoId(media: MediaInfo): String? {
        val link = media.webpageUrl ?: media.url
        if (media.match != null || !Providers.startsAsVideo(link, host.engine.settings.value.ytMusicAudio)) return null
        return SourceIds.of(link).firstOrNull { it.startsWith("youtube:") }?.removePrefix("youtube:")
    }

    /** Whether the Audio tab of [media] offers "Also keep this video": a YouTube link kept as audio, with the library on. */
    fun offersLinkVideo(media: MediaInfo, request: FormatRequest): Boolean = host.libraryEnabled && request is FormatRequest.Audio && ownVideoId(media) != null

    /** The link's own video as the song's video when the box is checked, else null. */
    fun linkVideo(media: MediaInfo, request: FormatRequest): MusicVideoMatch? {
        if (!keepLink || !offersLinkVideo(media, request)) return null
        return MusicVideoMatch(ownVideoId(media) ?: return null, media.title, media.uploader.orEmpty(), null, media.duration, link = media.url)
    }

    /**
     * Where the other side of Song | Video starts, in ms: the same second, or 1 s before the end of the other side when that is shorter
     * ([Songs.switchStart], the core's rule). Blocking (reads the file's length the first time); call off the main thread.
     */
    fun startOnOtherSide(id: String, toVideo: Boolean, positionMs: Long): Long {
        val other = if (toVideo) host.lib.musicVideoFacts(id)?.let { MusicVideoLengths.of(host.getApplication(), id, it) } else host.lib.item(id)?.duration
        return (Songs.switchStart(positionMs / 1000.0, other) * 1000).toLong()
    }

    /** Find lyrics for [item] and say what came of it: lyrics, "No lyrics found", or that the service could not be reached. */
    fun findLyrics(item: LibraryItem) {
        host.scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    if (host.lib.lyrics(item.id) != null) return@withContext null
                    FindLyrics.run(item, search = lyricsSearch, save = { text -> host.lib.saveLyrics(item.id, text) })
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not save the lyrics.")); return@launch }
            if (outcome == null) { host.say(ALREADY); return@launch }
            host.say(Lyrics.outcomeWords(outcome))
            if (outcome == "found") lyricsStamp++
        }
    }

    companion object {
        /** Said when the song has lyrics already (a menu entry can be out of date): nothing is searched. */
        const val ALREADY = "This song already has lyrics."
    }
}
