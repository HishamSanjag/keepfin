package app.keepfin.core.music

/** The recording to find: what a music service (Spotify, Apple Music, Deezer) says it is. */
data class Track(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    /** Length in seconds. Null or zero means unknown. */
    val durationSec: Double? = null,
    /** Position on the album (1-based), when the source says. */
    val trackNumber: Int? = null,
    /** Tracks on the source album, when the source says. */
    val trackCount: Int? = null,
    /** Release year, when the source says. */
    val year: Int? = null,
)

/** Where a search result came from. YouTube Music "songs" results are official audio. */
enum class Origin(val wire: String) {
    YTM_SONG("ytm-song"),
    YTM_FLAT("ytm-flat"),
    YOUTUBE("youtube");

    companion object {
        fun fromWire(value: String): Origin =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown origin: $value")
    }
}

data class Candidate(
    val videoId: String,
    val url: String,
    val title: String,
    val artists: List<String>,
    val channel: String? = null,
    val album: String? = null,
    /** Length in seconds. Null or zero means unknown. */
    val durationSec: Double? = null,
    val origin: Origin,
)

/** [confidence] is rounded to 0.01. */
data class Match(val candidate: Candidate, val confidence: Double)

/** A search a platform has to run, in order. The core never performs requests. */
sealed interface Search {
    val origin: Origin

    /** POST [MusicMatching.songsApiUrl] with [MusicMatching.songsApiBody] of [query] (the songs filter). */
    data class SongsApi(val query: String) : Search {
        override val origin: Origin get() = Origin.YTM_SONG
    }

    /** Run the engine's flat search of [target] (a search page or a search prefix) and read [playlistEnd] entries. */
    data class Flat(val target: String, override val origin: Origin, val playlistEnd: Int = 8) : Search
}
