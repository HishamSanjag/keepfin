package app.keepfin

import app.keepfin.core.providers.UrlCheck
import app.keepfin.core.providers.Url
import app.keepfin.core.providers.Providers as Core

/**
 * URL knowledge: validation, provider names, output folders and routing hints. The decisions are made by the shared core
 * (core/, package app.keepfin.core.providers), the same code the desktop app runs; this object keeps the names and messages the
 * Android code already uses.
 */
object Providers {
    /** The normalised http(s) link; throws [IllegalArgumentException] with the message the app shows when the text is not one. */
    fun validateUrl(value: String): String {
        val check = Core.validateUrl(value)
        return check.href ?: throw IllegalArgumentException(
            if (check.problem == UrlCheck.TOO_LONG) "Link is too long." else "Paste a complete HTTP or HTTPS link.",
        )
    }

    /** Lower-case host without a leading "www." or "m."; empty when the text is not a URL. */
    fun host(url: String): String = Core.host(url).removePrefix("m.")

    /** Human provider name; YouTube Music stays distinct from YouTube. */
    fun name(url: String, extractor: String? = null): String = Core.name(url, extractor)

    /** Platform output folder name. YouTube Music shares the "YouTube" folder, like desktop. */
    fun folder(url: String): String = Core.folder(url)

    fun safeName(value: String, max: Int = 80): String = Core.safeName(value, max)

    /** spotify | apple-music | deezer when the link belongs to a music service handled by the resolver (short links included). */
    fun musicSource(url: String): String? = Core.musicService(url)

    /** True for every music.youtube.com link (watch, playlist, browse/album, radio mix) and shares from the YouTube Music app. */
    fun isYouTubeMusic(url: String): Boolean = Core.isYouTubeMusic(url)

    /**
     * What a link should default to: "audio" for music sources (Spotify/Apple/Deezer always, YouTube Music unless the user
     * prefers video there), "video" for everything else including youtube.com and youtu.be.
     */
    fun defaultKind(url: String, youTubeMusicAudio: Boolean = true): String = if (Core.prefersAudio(url, youTubeMusicAudio)) "audio" else "video"

    /** A YouTube link not treated as music opens as a video, whatever the default quality says. */
    fun startsAsVideo(url: String, youTubeMusicAudio: Boolean = true): Boolean = Core.startsAsVideo(url, youTubeMusicAudio)

    /** The first value of a query parameter, decoded; null when it is missing or empty. */
    fun queryParameter(url: String, name: String): String? = Url.parse(url)?.queryParam(name)?.takeIf { it.isNotEmpty() }

    fun isYouTubeHost(url: String): Boolean = Core.isYouTube(url)

    /** A watch link inside a playlist/mix: the collection URL the user may want instead. */
    fun playlistUrlOf(url: String): String? = Core.playlistUrl(url)

    /** Links that are always collections; the engine still trusts the download engine's _type for everything else. */
    fun isCollectionUrl(url: String): Boolean = Core.isCollection(url)
}
