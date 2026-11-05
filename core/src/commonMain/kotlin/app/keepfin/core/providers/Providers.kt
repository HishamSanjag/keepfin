package app.keepfin.core.providers

import app.keepfin.core.music.WS
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim

// URL -> provider identity and routing hints. The behaviour is the desktop app's (gui/shared/providers.ts, output.ts and
// validateUrl in contracts.ts), which Android now shares. Pure: no network, no clock.

data class MusicLink(
    /** `spotify`, `apple-music` or `deezer`. */
    val service: String,
    /** `track`, `album` or `playlist`. */
    val type: String,
    val id: String,
    /** Apple Music storefront (for example "us"); needed to rebuild page URLs. */
    val storefront: String? = null,
)

/** The result of [Providers.validateUrl]: the normalised link, or why the text is not acceptable. */
class UrlCheck(val href: String?, val problem: String?) {
    val valid: Boolean get() = href != null

    companion object {
        /** The text is not a string or is too long to be a pasted link. */
        const val TOO_LONG = "tooLong"

        /** The text does not parse as an absolute URL. */
        const val NOT_A_LINK = "notALink"

        /** A URL, but not an http(s) link, or one that carries credentials. */
        const val UNSUPPORTED = "unsupported"
    }
}

private class Provider(val name: String, val folder: String, val gallery: Boolean = false, val matches: (String) -> Boolean)

private fun exact(vararg hosts: String): (String) -> Boolean = { host -> host in hosts }

/** The regex `(^|\.)(a|b)$`: the host is one of the domains or a subdomain of one. */
private fun domains(vararg names: String): (String) -> Boolean = { host ->
    names.any { host == it || host.endsWith(".$it") }
}

/** The regex `(^|\.)pinterest\.[a-z.]+$`. */
private fun pinterest(host: String): Boolean {
    var from = 0
    while (true) {
        val at = host.indexOf("pinterest.", from)
        if (at < 0) return false
        if (at == 0 || host[at - 1] == '.') {
            val tail = host.substring(at + 10)
            if (tail.isNotEmpty() && tail.all { it in 'a'..'z' || it == '.' }) return true
        }
        from = at + 1
    }
}

// Order matters: YouTube Music must win over YouTube.
private val providers = listOf(
    Provider("YouTube Music", "YouTube", matches = exact("music.youtube.com")),
    Provider("YouTube", "YouTube", matches = domains("youtube.com", "youtu.be", "youtube-nocookie.com")),
    Provider("Spotify", "Spotify", matches = domains("spotify.com", "spotify.link", "spoti.fi")),
    Provider("Apple Music", "Apple Music", matches = exact("music.apple.com")),
    Provider("Deezer", "Deezer", matches = domains("deezer.com", "deezer.page.link", "dzr.page.link")),
    Provider("X", "X", matches = domains("twitter.com", "x.com", "t.co")),
    Provider("Instagram", "Instagram", matches = domains("instagram.com")),
    Provider("TikTok", "TikTok", matches = domains("tiktok.com")),
    Provider("Threads", "Threads", matches = domains("threads.net", "threads.com")),
    Provider("Vimeo", "Vimeo", matches = domains("vimeo.com")),
    Provider("Twitch", "Twitch", matches = domains("twitch.tv")),
    Provider("Facebook", "Facebook", matches = domains("facebook.com", "fb.watch", "fb.com")),
    Provider("Reddit", "Reddit", matches = domains("reddit.com", "redd.it")),
    Provider("SoundCloud", "SoundCloud", matches = domains("soundcloud.com", "snd.sc")),
    Provider("Bandcamp", "Bandcamp", matches = domains("bandcamp.com")),
    Provider("Bilibili", "Bilibili", matches = domains("bilibili.com", "b23.tv", "bilibili.tv")),
    Provider("Dailymotion", "Dailymotion", matches = domains("dailymotion.com", "dai.ly")),
    Provider("Pinterest", "Pinterest", gallery = true, matches = { pinterest(it) || domains("pin.it")(it) }),
    Provider("Imgur", "Imgur", gallery = true, matches = domains("imgur.com")),
    Provider("DeviantArt", "DeviantArt", gallery = true, matches = domains("deviantart.com")),
    Provider("ArtStation", "ArtStation", gallery = true, matches = domains("artstation.com")),
    Provider("Flickr", "Flickr", gallery = true, matches = domains("flickr.com", "flic.kr")),
    Provider("Tumblr", "Tumblr", gallery = true, matches = domains("tumblr.com")),
    Provider("Pixiv", "Pixiv", gallery = true, matches = domains("pixiv.net")),
    Provider("Danbooru", "Danbooru", gallery = true, matches = domains("donmai.us")),
    Provider("Bluesky", "Bluesky", matches = domains("bsky.app")),
    Provider("Kick", "Kick", matches = domains("kick.com")),
    Provider("Rumble", "Rumble", matches = domains("rumble.com")),
    Provider("Streamable", "Streamable", matches = domains("streamable.com")),
    Provider("Mixcloud", "Mixcloud", matches = domains("mixcloud.com")),
    Provider("Odysee", "Odysee", matches = domains("odysee.com")),
    Provider("Niconico", "Niconico", matches = domains("nicovideo.jp", "nico.ms")),
    Provider("VK", "VK", matches = domains("vk.com", "vkvideo.ru")),
    Provider("Snapchat", "Snapchat", matches = domains("snapchat.com")),
    Provider("LinkedIn", "LinkedIn", matches = domains("linkedin.com")),
)

// Download engine extractor keys whose display name differs from the host-derived one ("" = no name of its own).
private val extractorNames = mapOf(
    "Youtube" to "YouTube",
    "YoutubeTab" to "YouTube",
    "Soundcloud" to "SoundCloud",
    "BiliBili" to "Bilibili",
    "TwitchVod" to "Twitch",
    "TwitchClips" to "Twitch",
    "Twitter" to "X",
    "Generic" to "",
)

private val shortMusicHosts = setOf("spotify.link", "spoti.fi", "link.deezer.com", "deezer.page.link", "dzr.page.link")
private val shortSpotifyHosts = setOf("spotify.link", "spoti.fi")

private fun isIdChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

private fun isId(value: String?, min: Int, max: Int): Boolean =
    value != null && value.length in min..max && value.all { isIdChar(it) }

private fun isDigits(value: String?, min: Int, max: Int): Boolean =
    value != null && value.length in min..max && value.all { it in '0'..'9' }

private fun isAlnum(value: String?, min: Int, max: Int): Boolean =
    value != null && value.length in min..max && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

private fun isTwoLetters(value: String?) = value != null && value.length == 2 && value.all { it in 'a'..'z' || it in 'A'..'Z' }

private val unsafeNameChars = "<>:\"/\\|?*%"
private val whitespaceRuns = Regex("$WS+")

/** The non-empty segments of a path, like `pathname.split('/').filter(Boolean)`. */
private fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

private fun String.stripLeadingWww(): String = if (startsWith("www.")) substring(4) else this

private fun String.stripLeadingWwwOrM(): String = if (startsWith("www.")) substring(4) else if (startsWith("m.")) substring(2) else this

private fun parse(url: String?): Url? = if (url == null) null else Url.parse(url)

object Providers {
    // ---- hosts -----------------------------------------------------------------------------------------------

    /** The lower-case host without a leading `www.`; empty when the text is not a URL. */
    fun host(url: String): String = parse(url)?.let { asciiLower(it.hostname).stripLeadingWww() } ?: ""

    private fun lookup(url: String): Provider? {
        val host = host(url)
        return if (host.isEmpty()) null else providers.firstOrNull { it.matches(host) }
    }

    /** Human provider name; falls back to the download engine's extractor key, then the host. */
    fun name(url: String, extractor: String? = null): String {
        lookup(url)?.let { return it.name }
        if (!extractor.isNullOrEmpty()) {
            val key = if (extractor.startsWith("gallery-dl:")) extractor.substring("gallery-dl:".length) else extractor
            val mapped = extractorNames[key]
            if (!mapped.isNullOrEmpty()) return mapped
            if (mapped == null && asciiLower(key) != "generic") return key.take(1).uppercase() + key.drop(1)
        }
        return host(url).ifEmpty { "Web" }
    }

    /** Output folder for the 'platform' layout. YouTube Music shares the "YouTube" folder. Always safe as a folder name. */
    fun folder(url: String): String {
        val known = lookup(url)
        return safeName(known?.folder ?: host(url).trimEnd('.').ifEmpty { "Web" })
    }

    /** Hosts whose posts are mostly images: route to the gallery engine before the download engine. */
    fun isGallery(url: String): Boolean = lookup(url)?.gallery == true

    fun isYouTube(url: String?): Boolean {
        val parsed = parse(url) ?: return false
        return domains("youtube.com", "youtu.be", "youtube-nocookie.com")(asciiLower(parsed.hostname))
    }

    /** True for music.youtube.com links of any shape (watch, playlist, browse, radio mixes). */
    fun isYouTubeMusic(url: String?): Boolean {
        val parsed = parse(url) ?: return false
        return asciiLower(parsed.hostname) == "music.youtube.com"
    }

    // ---- music services --------------------------------------------------------------------------------------

    /** Short links that must be expanded (by following redirects) before parsing. */
    fun isShortMusicLink(url: String): Boolean = host(url) in shortMusicHosts

    /** Parses Spotify, Apple Music and Deezer links. Null for anything else. */
    fun musicLink(url: String): MusicLink? {
        val parsed = Url.parse(url) ?: return null
        val host = asciiLower(parsed.hostname)
        val parts = segmentsOf(parsed.path)
        val types = setOf("track", "album", "playlist")
        if (host == "open.spotify.com" || host == "play.spotify.com") {
            // /intl-xx/track/id and /embed/track/id are equivalent to /track/id
            val rest = parts.filterIndexed { i, p -> !(i == 0 && isSpotifyPrefix(p)) }
            val type = rest.getOrNull(0)
            val id = rest.getOrNull(1)
            if (type != null && type in types && isAlnum(id, 8, 40)) return MusicLink("spotify", type, id!!)
            return null
        }
        if (host == "music.apple.com") {
            // /{storefront}/{album|playlist|song}/{slug?}/{id}[?i=trackId]
            val storefront = parts.getOrNull(0)
            val kind = parts.getOrNull(1)
            val id = parts.lastOrNull()
            if (storefront == null || kind == null || id == null || !isTwoLetters(storefront)) return null
            val track = parsed.queryParam("i")
            if (kind == "album" && track != null && isDigits(track, 1, 20)) return MusicLink("apple-music", "track", track, storefront)
            if (kind == "song" && isDigits(id, 1, 20)) return MusicLink("apple-music", "track", id, storefront)
            if (kind == "album" && isDigits(id, 1, 20)) return MusicLink("apple-music", "album", id, storefront)
            if (kind == "playlist" && isApplePlaylistId(id)) return MusicLink("apple-music", "playlist", id, storefront)
            return null
        }
        if (host == "deezer.com" || host.endsWith(".deezer.com")) {
            // /{lang?}/{track|album|playlist}/{id}
            val rest = if (parts.isNotEmpty() && isTwoLetters(parts[0])) parts.drop(1) else parts
            val type = rest.getOrNull(0)
            val id = rest.getOrNull(1)
            if (type != null && type in types && isDigits(id, 1, 20)) return MusicLink("deezer", type, id!!)
        }
        return null
    }

    /** The regex `^(intl-[a-z-]+|embed)$` with the i flag. */
    private fun isSpotifyPrefix(part: String): Boolean {
        val lower = asciiLower(part)
        if (lower == "embed") return true
        return lower.startsWith("intl-") && lower.length > 5 && lower.substring(5).all { it in 'a'..'z' || it == '-' }
    }

    /** The regex `^pl\.[A-Za-z0-9-]{4,100}$`. */
    private fun isApplePlaylistId(id: String): Boolean =
        id.startsWith("pl.") && id.length - 3 in 4..100 && id.substring(3).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /** Canonical public URL for a parsed music link. */
    fun musicLinkUrl(link: MusicLink): String = when (link.service) {
        "spotify" -> "https://open.spotify.com/${link.type}/${link.id}"
        "deezer" -> "https://www.deezer.com/${link.type}/${link.id}"
        else -> "https://music.apple.com/${link.storefront?.takeIf { it.isNotEmpty() } ?: "us"}/${if (link.type == "track") "song" else link.type}/${link.id}"
    }

    /**
     * `spotify`, `apple-music` or `deezer` when the link belongs to a music service handled by matching (never downloaded as
     * such), including the services' short links. YouTube Music is not one of them. Artist pages are not links to download.
     */
    fun musicService(url: String): String? {
        musicLink(url)?.let { return it.service }
        val host = host(url)
        return when {
            host in shortSpotifyHosts -> "spotify"
            host in shortMusicHosts -> "deezer"
            else -> null
        }
    }

    /**
     * Music links default to the Audio tab and audio presets. Spotify, Apple Music and Deezer links always do; YouTube Music
     * follows the user's setting. A short link that is not expanded yet (spotify.link, spoti.fi...) is not a music link: the
     * desktop app decides after expanding it (the old Android rule said audio; see PRV-7 in DECISIONS.md).
     */
    fun prefersAudio(url: String?, youTubeMusicAudio: Boolean = true): Boolean {
        if (url == null || url.isEmpty()) return false
        if (musicLink(url) != null || isShortMusicLink(url)) return true
        return youTubeMusicAudio && isYouTubeMusic(url)
    }

    /** A YouTube link that is not treated as music always opens as a video, whatever the default quality says. Keeping it as a song is the user's choice. */
    fun startsAsVideo(url: String?, youTubeMusicAudio: Boolean = true): Boolean = isYouTube(url) && !prefersAudio(url, youTubeMusicAudio)

    // ---- collections -----------------------------------------------------------------------------------------

    /**
     * Heuristic for links that are certainly collections, so inspection can use a cheap flat probe instead of a full
     * extraction. Single items inside a playlist (watch?v=...&list=...) are NOT collections.
     */
    fun isCollection(url: String): Boolean {
        val parsed = Url.parse(url) ?: return false
        val host = asciiLower(parsed.hostname).stripLeadingWwwOrM()
        val path = parsed.path
        val music = musicLink(url)
        if (music != null) return music.type != "track"
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            if (path == "/watch" || path.startsWith("/shorts/") || path.startsWith("/live/")) return false
            if (path == "/playlist" && !parsed.queryParam("list").isNullOrEmpty()) return true
            if (path.startsWith("/browse/MPRE") || path.startsWith("/browse/VL") || path.startsWith("/browse/OLAK")) return true
            return isYouTubeChannelPath(path)
        }
        val segments = path.split('/') // "/a/b" -> ["", "a", "b"]
        if (host == "soundcloud.com") {
            if (segments.size >= 2 && segments[1].isNotEmpty()) {
                val rest = segments.drop(2)
                if (rest.isEmpty() || rest == listOf("")) return true
                val first = rest[0]
                val tail = rest.drop(1)
                if (first == "sets") return tail.isNotEmpty() && tail[0].isNotEmpty() && (tail.size == 1 || (tail.size == 2 && tail[1].isEmpty()))
                if (first == "tracks" || first == "albums" || first == "likes" || first == "reposts") return tail.isEmpty() || tail == listOf("")
            }
            return false
        }
        if (host == "bandcamp.com" || host.endsWith(".bandcamp.com")) {
            return path.startsWith("/album/") || path == "/music" || path == "/music/" || path == "/"
        }
        if (host == "vimeo.com") {
            return path.startsWith("/showcase/") || path.startsWith("/channels/") || path.startsWith("/album/") || path.startsWith("/groups/")
        }
        if (host == "twitch.tv") {
            return segments.size in 3..4 && segments[0].isEmpty() && segments[1].isNotEmpty() &&
                (segments[2] == "videos" || segments[2] == "clips") && (segments.size == 3 || segments[3].isEmpty())
        }
        if (host == "tiktok.com") {
            return segments.size in 2..3 && segments[0].isEmpty() && segments[1].length > 1 && segments[1].startsWith("@") &&
                (segments.size == 2 || segments[2].isEmpty())
        }
        if (host == "dailymotion.com") return path.startsWith("/playlist/")
        return false
    }

    /**
     * The regex `^/(@[^/]+|c/[^/]+|channel/[^/]+|user/[^/]+)(/(videos|shorts|streams|playlists|releases|podcasts|featured)?)?/?$`.
     */
    private fun isYouTubeChannelPath(path: String): Boolean {
        val segments = path.split('/')
        if (segments.size < 2 || segments[0].isNotEmpty()) return false
        var next: Int
        val first = segments[1]
        if (first.length > 1 && first.startsWith("@")) {
            next = 2
        } else if (first == "c" || first == "channel" || first == "user") {
            if (segments.size < 3 || segments[2].isEmpty()) return false
            next = 3
        } else return false
        val rest = segments.drop(next)
        val tabs = setOf("videos", "shorts", "streams", "playlists", "releases", "podcasts", "featured")
        return when (rest.size) {
            0 -> true
            1 -> rest[0].isEmpty() || rest[0] in tabs
            2 -> (rest[0].isEmpty() && rest[1].isEmpty()) || (rest[0] in tabs && rest[1].isEmpty())
            else -> false
        }
    }

    /** A radio mix list (RD + seed, RDMM, RDAMVM...), not a curated RDCLAK/RDAMPL YouTube Music playlist. */
    fun isRadioMix(list: String): Boolean = list.startsWith("RD") && !list.startsWith("RDCLAK") && !list.startsWith("RDAMPL")

    /** The playlist link a single-item watch link belongs to, or null (radio mixes keep their seed video). */
    fun playlistUrl(url: String): String? {
        val parsed = Url.parse(url) ?: return null
        val host = asciiLower(parsed.hostname).stripLeadingWwwOrM()
        val list = parsed.queryParam("list")
        if (list.isNullOrEmpty() || !isId(list, 2, 80)) return null
        val base = if (host == "music.youtube.com") "https://music.youtube.com" else "https://www.youtube.com"
        // Radio mixes exist only relative to their seed video: YouTube calls their /playlist page "unviewable".
        val video = if (host == "youtu.be") parsed.path.substring(1) else parsed.queryParam("v")
        val mix = isRadioMix(list) && video != null && video.length == 11 && isId(video, 11, 11)
        if ((host == "youtube.com" || host == "music.youtube.com") && parsed.path == "/watch") {
            return if (mix) "$base/watch?v=$video&list=$list" else "$base/playlist?list=$list"
        }
        if (host == "youtu.be") return if (mix) "$base/watch?v=$video&list=$list" else "$base/playlist?list=$list"
        return null
    }

    // ---- ids and names ---------------------------------------------------------------------------------------

    /** The 11-character video id of a YouTube or YouTube Music link, else null. */
    fun videoId(url: String): String? {
        val parsed = Url.parse(url) ?: return null
        val host = asciiLower(parsed.hostname).stripLeadingWww()
        if (host == "youtu.be") {
            val first = parsed.path.substring(1)
            return if (first.length >= 11 && isId(first.substring(0, 11), 11, 11)) first.substring(0, 11) else null
        }
        if (host == "youtube.com" || host == "music.youtube.com" || host == "m.youtube.com") {
            val fromQuery = parsed.queryParam("v")
            val id = fromQuery ?: pathVideoId(parsed.path)
            return if (id != null && isId(id, 11, 11)) id else null
        }
        return null
    }

    /** The id in `/shorts/<id>`, `/embed/<id>` or `/live/<id>`: the 11 id characters right after the prefix. */
    private fun pathVideoId(path: String): String? {
        for (prefix in listOf("/shorts/", "/embed/", "/live/")) {
            if (path.startsWith(prefix) && path.length >= prefix.length + 11) {
                val id = path.substring(prefix.length, prefix.length + 11)
                if (isId(id, 11, 11)) return id
            }
        }
        return null
    }

    /**
     * A name safe as a file or folder name: reserved characters become `_`, whitespace collapses, trailing dots and spaces go,
     * the result keeps at most [max] characters (never splitting a surrogate pair), reserved device names get a `_` prefix.
     */
    fun safeName(value: String, max: Int = 80): String {
        val replaced = StringBuilder(value.length)
        for (c in value) replaced.append(if (c in unsafeNameChars || c.code <= 0x1F) '_' else c)
        val trimmed = jsTrim(whitespaceRuns.replace(replaced.toString(), " ")).trimEnd('.', ' ')
        var kept = 0
        var cut = 0
        while (cut < trimmed.length && kept < max) {
            cut += if (trimmed[cut].isHighSurrogate() && cut + 1 < trimmed.length && trimmed[cut + 1].isLowSurrogate()) 2 else 1
            kept++
        }
        val cleaned = trimmed.substring(0, cut).trimEnd('.', ' ').ifEmpty { "Untitled" }
        return if (isReservedDeviceName(cleaned)) "_$cleaned" else cleaned
    }

    /** The regex `^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)` with the i flag. */
    private fun isReservedDeviceName(name: String): Boolean {
        val lower = asciiLower(name)
        fun endsHere(length: Int) = lower.length == length || lower[length] == '.'
        for (device in listOf("con", "prn", "aux", "nul")) if (lower.startsWith(device) && endsHere(3)) return true
        for (device in listOf("com", "lpt")) {
            if (lower.startsWith(device) && lower.length >= 4 && lower[3] in '1'..'9' && endsHere(4)) return true
        }
        return false
    }

    /** Checks a pasted link: an absolute http(s) URL without credentials, at most 4096 characters. */
    fun validateUrl(value: String): UrlCheck {
        if (value.length > 4096) return UrlCheck(null, UrlCheck.TOO_LONG)
        val parsed = Url.parse(jsTrim(value)) ?: return UrlCheck(null, UrlCheck.NOT_A_LINK)
        if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return UrlCheck(null, UrlCheck.UNSUPPORTED)
        }
        return UrlCheck(parsed.href, null)
    }
}
