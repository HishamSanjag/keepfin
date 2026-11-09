@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.music

// The JavaScript surface of the music core. Names and shapes here are the TypeScript API
// (see gui/shared/core/music.ts): arrays instead of lists, nullable numbers, origins as wire strings.

@JsExport
class JsTrack(
    val title: String,
    val artists: Array<String>,
    val album: String? = null,
    val durationSec: Double? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val year: Int? = null,
)

@JsExport
class JsCandidate(
    val videoId: String,
    val url: String,
    val title: String,
    val artists: Array<String>,
    val channel: String? = null,
    val album: String? = null,
    val durationSec: Double? = null,
    val origin: String,
)

@JsExport
class JsMatch(val candidate: JsCandidate, val confidence: Double)

/** `kind` is "songsApi" (with `query`) or "flat" (with `target`, `origin` and `playlistEnd`). */
@JsExport
class JsSearch(
    val kind: String,
    val query: String? = null,
    val target: String? = null,
    val origin: String? = null,
    val playlistEnd: Int? = null,
)

private fun JsTrack.toTrack() = Track(title, artists.toList(), album, durationSec, trackNumber, trackCount, year)

private fun JsCandidate.toCandidate() =
    Candidate(videoId, url, title, artists.toList(), channel, album, durationSec, Origin.fromWire(origin))

private fun Candidate.toJs() =
    JsCandidate(videoId, url, title, artists.toTypedArray(), channel, album, durationSec, origin.wire)

private fun Match.toJs() = JsMatch(candidate.toJs(), confidence)

private fun Search.toJs(): JsSearch = when (this) {
    is Search.SongsApi -> JsSearch(kind = "songsApi", query = query)
    is Search.Flat -> JsSearch(kind = "flat", target = target, origin = origin.wire, playlistEnd = playlistEnd)
}

@JsExport
fun musicQuery(track: JsTrack): String = MusicMatching.query(track.toTrack())

@JsExport
fun musicSearches(track: JsTrack): Array<JsSearch> = MusicMatching.searches(track.toTrack()).map { it.toJs() }.toTypedArray()

@JsExport
fun musicSongsApiUrl(): String = MusicMatching.songsApiUrl

/** Header names and values as `[name, value]` pairs. */
@JsExport
fun musicSongsApiHeaders(): Array<Array<String>> =
    MusicMatching.songsApiHeaders.map { arrayOf(it.first, it.second) }.toTypedArray()

@JsExport
fun musicSongsApiBody(query: String): String = MusicMatching.songsApiBody(query)

@JsExport
fun musicParseSongsApi(json: String): Array<JsCandidate> =
    MusicMatching.parseSongsApi(json).map { it.toJs() }.toTypedArray()

@JsExport
fun musicFlatCandidates(json: String, origin: String): Array<JsCandidate> =
    MusicMatching.flatCandidates(json, Origin.fromWire(origin)).map { it.toJs() }.toTypedArray()

@JsExport
fun musicScore(track: JsTrack, candidate: JsCandidate): Double =
    MusicMatching.score(track.toTrack(), candidate.toCandidate())

@JsExport
fun musicRanked(track: JsTrack, candidates: Array<JsCandidate>): Array<JsMatch> =
    MusicMatching.ranked(track.toTrack(), candidates.map { it.toCandidate() }).map { it.toJs() }.toTypedArray()

@JsExport
fun musicBest(track: JsTrack, candidates: Array<JsCandidate>): JsMatch? =
    MusicMatching.best(track.toTrack(), candidates.map { it.toCandidate() })?.toJs()

@JsExport
fun musicIsConfident(match: JsMatch?): Boolean =
    MusicMatching.isConfident(match?.let { Match(it.candidate.toCandidate(), it.confidence) })

/** The same decision as [musicIsConfident] for a bare confidence, for callers that only keep the number. */
@JsExport
fun musicIsConfidentScore(confidence: Double): Boolean = confidence >= MusicMatching.CONFIDENT

@JsExport
fun musicAlternates(track: JsTrack, candidates: Array<JsCandidate>, exclude: Array<String>, limit: Int): Array<JsMatch> =
    MusicMatching.alternates(track.toTrack(), candidates.map { it.toCandidate() }, exclude.toSet(), limit)
        .map { it.toJs() }.toTypedArray()

@JsExport
fun musicNormalize(value: String): String = MusicMatching.normalize(value)

@JsExport
fun musicCoreTitle(title: String): String = MusicMatching.coreTitle(title)

/** The matching thresholds, so callers never repeat the numbers. */
@JsExport
class JsMusicConstants(
    val minConfidence: Double,
    val confident: Double,
    val alternateMinConfidence: Double,
    val alternateMaxDeltaSec: Double,
    val maxAlternates: Int,
)

@JsExport
fun musicConstants(): JsMusicConstants = JsMusicConstants(
    MusicMatching.MIN_CONFIDENCE,
    MusicMatching.CONFIDENT,
    MusicMatching.ALTERNATE_MIN_CONFIDENCE,
    MusicMatching.ALTERNATE_MAX_DELTA_SEC,
    MusicMatching.MAX_ALTERNATES,
)

// -- albums -----------------------------------------------------------------------------------------------------------

@JsExport
class JsAlbumCandidate(
    val browseId: String,
    val title: String,
    val artists: Array<String>,
    val year: Int? = null,
    val kind: String? = null,
)

@JsExport
class JsAlbumTrack(
    val videoId: String,
    val title: String,
    val artists: Array<String>,
    val durationSec: Double? = null,
    val position: Int,
)

@JsExport
class JsAlbumPage(
    val title: String,
    val artists: Array<String>,
    val year: Int? = null,
    val trackCount: Int,
    val tracks: Array<JsAlbumTrack>,
)

@JsExport
class JsAlbumMatch(
    val browseId: String,
    val albumTitle: String,
    val albumConfidence: Double,
    val position: Int,
    val candidate: JsCandidate,
    val confidence: Double,
)

/** An album page that was fetched; [page] is null when it could not be read. */
@JsExport
class JsAlbumFetched(val browseId: String, val page: JsAlbumPage? = null)

/** `kind` is "fetch" (with `browseId`), "matched" (with `match`) or "none". */
@JsExport
class JsAlbumStep(val kind: String, val browseId: String? = null, val match: JsAlbumMatch? = null)

private fun JsAlbumCandidate.toAlbum() = AlbumCandidate(browseId, title, artists.toList(), year, kind)

private fun AlbumCandidate.toJs() = JsAlbumCandidate(browseId, title, artists.toTypedArray(), year, kind)

private fun JsAlbumPage.toPage() =
    AlbumPage(title, artists.toList(), year, trackCount, tracks.map { AlbumTrack(it.videoId, it.title, it.artists.toList(), it.durationSec, it.position) })

private fun AlbumPage.toJs() = JsAlbumPage(
    title, artists.toTypedArray(), year, trackCount,
    tracks.map { JsAlbumTrack(it.videoId, it.title, it.artists.toTypedArray(), it.durationSec, it.position) }.toTypedArray(),
)

private fun AlbumMatch.toJs() = JsAlbumMatch(browseId, albumTitle, albumConfidence, position, candidate.toJs(), confidence)

@JsExport
fun musicAlbumApplies(track: JsTrack): Boolean = AlbumMatching.applies(track.toTrack())

@JsExport
fun musicAlbumQuery(track: JsTrack): String = AlbumMatching.query(track.toTrack())

@JsExport
fun musicAlbumSearchBody(query: String): String = AlbumMatching.searchBody(query)

@JsExport
fun musicAlbumBrowseUrl(): String = AlbumMatching.browseUrl

/** The album page request body, or null when the id is not an album id. */
@JsExport
fun musicAlbumBrowseBody(browseId: String): String? = AlbumMatching.browseBody(browseId)

@JsExport
fun musicParseAlbumSearch(json: String): Array<JsAlbumCandidate> =
    AlbumMatching.parseSearch(json).map { it.toJs() }.toTypedArray()

@JsExport
fun musicParseAlbumPage(json: String): JsAlbumPage? = AlbumMatching.parsePage(json)?.toJs()

@JsExport
fun musicAlbumRank(track: JsTrack, albums: Array<JsAlbumCandidate>): Array<JsAlbumCandidate> =
    AlbumMatching.rank(track.toTrack(), albums.map { it.toAlbum() }).map { it.toJs() }.toTypedArray()

@JsExport
fun musicAlbumStep(track: JsTrack, albums: Array<JsAlbumCandidate>, fetched: Array<JsAlbumFetched>): JsAlbumStep {
    val pages = HashMap<String, AlbumPage?>()
    for (entry in fetched) pages[entry.browseId] = entry.page?.toPage()
    return when (val step = AlbumMatching.step(track.toTrack(), albums.map { it.toAlbum() }, pages)) {
        is AlbumStep.Fetch -> JsAlbumStep("fetch", browseId = step.browseId)
        is AlbumStep.Matched -> JsAlbumStep("matched", match = step.match.toJs())
        AlbumStep.None -> JsAlbumStep("none")
    }
}
/** The album to look for when the track has none: the album of the best songs-search result; null when there is none. */
@JsExport
fun musicAlbumHint(track: JsTrack, candidates: Array<JsCandidate>): String? =
    AlbumMatching.hint(track.toTrack(), candidates.map { it.toCandidate() })

// ---- a song's official music video (MusicVideos.kt) ----

@JsExport
class JsVideoArtist(val name: String, val browseId: String)

/** `type` is OMV (an official video), UGC, ATV... or "" when unknown; `views` and `durationSec` are null when not shown. */
@JsExport
class JsVideoCandidate(
    val videoId: String,
    val type: String,
    val title: String,
    val artists: Array<JsVideoArtist>,
    val views: Double? = null,
    val durationSec: Double? = null,
)

private fun VideoCandidate.toJs() = JsVideoCandidate(videoId, type, title, artists.map { JsVideoArtist(it.name, it.browseId) }.toTypedArray(), views?.toDouble(), durationSec)

private fun JsVideoCandidate.toVideo() = VideoCandidate(videoId, type, title, artists.map { VideoArtist(it.name, it.browseId) }, views?.toLong(), durationSec)

/** The videos search request body for [query] (sent like the songs search). */
@JsExport
fun musicVideosSearchBody(query: String): String = MusicVideos.searchBody(query)

/** The search text for a song: its first artist, then its title. */
@JsExport
fun musicVideosQuery(artists: Array<String>, title: String): String = MusicVideos.query(artists.toList(), title)

@JsExport
fun musicVideosParse(json: String): Array<JsVideoCandidate> = MusicVideos.parse(json).map { it.toJs() }.toTypedArray()

/** The song's official music video among a search's rows, or null: Keepfin never guesses. */
@JsExport
fun musicVideosBest(artists: Array<String>, title: String, candidates: Array<JsVideoCandidate>, artistIds: Array<String> = emptyArray()): JsVideoCandidate? =
    MusicVideos.best(artists.toList(), title, candidates.map { it.toVideo() }, artistIds.toList())?.toJs()

/** Track id and ISRC pairs of a Deezer response (see DeezerTracks). */
@JsExport
fun musicDeezerIsrcs(json: String): Array<Array<String>> =
    DeezerTracks.isrcs(json).map { arrayOf(it.key, it.value) }.toTypedArray()

// -- followed artist releases (see ArtistReleases) ----------------------------------------------------------------------

@JsExport
class JsArtistRelease(val link: String, val title: String, val kind: String, val thumbnail: String?)

@JsExport
fun musicArtistBrowseUrl(): String = ArtistReleases.browseUrl

/** The artist page request body for a channel id, or null when it is not one. */
@JsExport
fun musicArtistBrowseBody(channelId: String): String? = ArtistReleases.browseBody(channelId)

/** The albums and singles (or latest uploads) in an artist page response. Throws when the text is not JSON. */
@JsExport
fun musicArtistReleases(json: String): Array<JsArtistRelease> =
    ArtistReleases.parse(json).map { JsArtistRelease(it.link, it.title, it.kind, it.thumbnail) }.toTypedArray()

@JsExport
fun musicArtistChannelUrl(channelId: String): String = ArtistReleases.channelUrl(channelId)

@JsExport
fun musicArtistChannelId(url: String): String? = ArtistReleases.channelId(url)

@JsExport
fun musicArtistLegacyAddress(url: String): Boolean = ArtistReleases.isLegacyAddress(url)
