package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.music.AlbumMatching
import app.keepfin.core.music.AlbumPage
import app.keepfin.core.music.AlbumStep
import app.keepfin.core.music.Candidate
import app.keepfin.core.music.Match
import app.keepfin.core.music.MusicMatching
import app.keepfin.core.music.MusicVideos
import app.keepfin.core.music.Origin
import app.keepfin.core.music.Search
import app.keepfin.core.music.Track

// fixtures/engine/music: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun strings(value: JsonValue?): List<String>? = (value as? JsonArray)?.items?.map { (it as JsonString).value }

private fun trackOf(input: JsonObject): Track {
    val t = input.members.getValue("track") as JsonObject
    return Track(
        title = t.string("title")!!,
        artists = strings(t.members["artists"]) ?: emptyList(),
        album = t.string("album"),
        durationSec = t.number("durationSec"),
        trackNumber = t.number("trackNumber")?.toInt(),
        trackCount = t.number("trackCount")?.toInt(),
        year = t.number("year")?.toInt(),
    )
}

private fun candidateOf(c: JsonObject): Candidate {
    val id = c.string("id")!!
    val channel = c.string("channel")
    val artists = strings(c.members["artists"])
        ?: if (!channel.isNullOrEmpty()) listOf(channel.removeSuffix(" - Topic")) else emptyList()
    return Candidate(
        videoId = id,
        url = "https://music.youtube.com/watch?v=$id",
        title = c.string("title")!!,
        artists = artists,
        channel = channel,
        album = c.string("album"),
        durationSec = c.number("durationSec"),
        origin = Origin.fromWire(c.string("origin") ?: "youtube"),
    )
}

private fun candidatesOf(list: JsonValue?): List<Candidate> = (list as JsonArray).items.map { candidateOf(it as JsonObject) }

private fun candidateJson(c: Candidate, withUrl: Boolean): JsonObject = obj(
    "id" to jstr(c.videoId),
    *(if (withUrl) arrayOf("url" to jstr(c.url)) else emptyArray()),
    "title" to jstr(c.title),
    "artists" to arr(c.artists.map { jstr(it) }),
    "channel" to jstr(c.channel),
    "album" to jstr(c.album),
    "durationSec" to jnum(c.durationSec),
    "origin" to jstr(c.origin.wire),
)

private fun albumJson(a: app.keepfin.core.music.AlbumCandidate): JsonObject = obj(
    "id" to jstr(a.browseId), "title" to jstr(a.title), "artists" to arr(a.artists.map { jstr(it) }),
    "year" to jnum(a.year?.toDouble()), "kind" to jstr(a.kind),
)

private fun pageJson(p: AlbumPage): JsonObject = obj(
    "title" to jstr(p.title), "artists" to arr(p.artists.map { jstr(it) }), "year" to jnum(p.year?.toDouble()), "trackCount" to jnum(p.trackCount),
    "tracks" to arr(
        p.tracks.map {
            obj(
                "id" to jstr(it.videoId), "title" to jstr(it.title), "artists" to arr(it.artists.map { a -> jstr(a) }),
                "durationSec" to jnum(it.durationSec), "position" to jnum(it.position),
            )
        },
    ),
)

private fun matchBest(match: Match?): JsonObject = obj(
    "bestId" to jstr(match?.candidate?.videoId),
    "confidence" to jnum(match?.confidence),
)

private fun sampleOrData(input: JsonObject): String {
    val file = input.string("file")
    return if (file != null) readSample("music", file) else show(input.members.getValue("data"))
}

internal fun musicHandler(input: JsonObject): JsonValue {
    return when (val op = input.string("op")) {
        "best" -> matchBest(MusicMatching.best(trackOf(input), candidatesOf(input.members["candidates"])))
        "alternates" -> {
            val exclude = strings(input.members["exclude"])?.toSet() ?: emptySet()
            val matches = MusicMatching.alternates(trackOf(input), candidatesOf(input.members["candidates"]), exclude)
            obj("ids" to arr(matches.map { jstr(it.candidate.videoId) }))
        }
        "query" -> jstr(MusicMatching.query(trackOf(input)))
        "songsApi" -> {
            val candidates = MusicMatching.parseSongsApi(sampleOrData(input))
            obj("candidates" to arr(candidates.map { candidateJson(it, withUrl = false) }))
        }
        "flat" -> {
            val candidates = MusicMatching.flatCandidates(sampleOrData(input), Origin.fromWire(input.string("origin")!!))
            obj("candidates" to arr(candidates.map { candidateJson(it, withUrl = true) }))
        }
        "searches" -> arr(
            MusicMatching.searches(trackOf(input)).map { search ->
                when (search) {
                    is Search.SongsApi -> obj("kind" to jstr("songsApi"), "query" to jstr(search.query))
                    is Search.Flat -> obj(
                        "kind" to jstr("flat"), "target" to jstr(search.target), "origin" to jstr(search.origin.wire),
                        "playlistEnd" to jnum(search.playlistEnd),
                    )
                }
            },
        )
        "pick" -> {
            // Searches run in order and their candidates accumulate; stop once the best so far is confident.
            val track = trackOf(input)
            val found = ArrayList<Candidate>()
            var run = 0
            for (result in (input.members.getValue("searches") as JsonArray).items) {
                run++
                if (result is JsonString) {
                    check(result.value == "fail") { "a search is a candidate list or \"fail\"" }
                } else {
                    found.addAll(candidatesOf(result))
                    if (MusicMatching.isConfident(MusicMatching.best(track, found))) break
                }
            }
            val best = MusicMatching.best(track, found)
            obj("bestId" to jstr(best?.candidate?.videoId), "searchesRun" to jnum(run))
        }
        "albumSearch" -> obj("albums" to arr(AlbumMatching.parseSearch(sampleOrData(input)).map(::albumJson)))
        "albumPage" -> AlbumMatching.parsePage(sampleOrData(input))?.let(::pageJson) ?: JsonNull
        "albumQuery" -> {
            val track = trackOf(input)
            obj("applies" to JsonBool(AlbumMatching.applies(track)), "query" to jstr(AlbumMatching.query(track)))
        }
        "albumHint" -> obj("album" to jstr(AlbumMatching.hint(trackOf(input), candidatesOf(input.members["candidates"]))))
        "albumRank" -> {
            val albums = AlbumMatching.parseSearch(readSample("music", input.string("search")!!))
            obj("ids" to arr(AlbumMatching.rank(trackOf(input), albums).map { jstr(it.browseId) }))
        }
        "album" -> {
            // The platform loop: ask for the next step, fetch (here: read the capture) what it names, ask again.
            val track = trackOf(input)
            val albums = AlbumMatching.parseSearch(readSample("music", input.string("search")!!))
            val pages = input.members.getValue("pages") as JsonObject
            val fetched = LinkedHashMap<String, AlbumPage?>()
            var result: JsonObject? = null
            for (round in 0 until 6) {
                when (val step = AlbumMatching.step(track, albums, fetched)) {
                    is AlbumStep.Fetch -> {
                        val file = pages.string(step.browseId) ?: error("no page for ${step.browseId}")
                        fetched[step.browseId] = if (file == "fail") null else AlbumMatching.parsePage(readSample("music", file))
                    }
                    is AlbumStep.Matched -> {
                        val m = step.match
                        result = obj(
                            "kind" to jstr("matched"), "browseId" to jstr(m.browseId), "albumTitle" to jstr(m.albumTitle),
                            "position" to jnum(m.position), "videoId" to jstr(m.candidate.videoId), "title" to jstr(m.candidate.title),
                            "fetched" to arr(fetched.keys.map { jstr(it) }),
                        )
                        break
                    }
                    AlbumStep.None -> {
                        result = obj("kind" to jstr("none"), "fetched" to arr(fetched.keys.map { jstr(it) }))
                        break
                    }
                }
            }
            result ?: error("the album steps did not finish")
        }
        "videosParse" -> obj(
            "candidates" to arr(
                MusicVideos.parse(sampleOrData(input)).map {
                    obj(
                        "id" to jstr(it.videoId), "type" to jstr(it.type), "title" to jstr(it.title),
                        "artists" to arr(it.artists.map { a -> obj("name" to jstr(a.name), "browseId" to jstr(a.browseId)) }),
                        "views" to jnum(it.views?.toDouble()), "durationSec" to jnum(it.durationSec),
                    )
                },
            ),
        )
        "videoBest" -> {
            val candidates = MusicVideos.parse(sampleOrData(input))
            val ids = strings(input.members["artistIds"]) ?: emptyList()
            obj("videoId" to jstr(MusicVideos.best(strings(input.members["artists"])!!, input.string("title")!!, candidates, ids)?.videoId))
        }
        else -> error("unknown music op $op")
    }
}

/** `minConfidence` is a floor on the winner's confidence; everything else is a subset comparison. */
internal fun compareMusic(actual: JsonValue, expected: JsonValue, where: String, failures: MutableList<String>) {
    if (expected !is JsonObject || "minConfidence" !in expected.members) return compareSubset(actual, expected, where, failures)
    val floor = expected.number("minConfidence")!!
    val confidence = (actual as? JsonObject)?.number("confidence") ?: 0.0
    if (confidence < floor) failures.add("$where: confidence $confidence < $floor")
    compareSubset(actual, JsonObject(expected.members - "minConfidence"), where, failures)
}
