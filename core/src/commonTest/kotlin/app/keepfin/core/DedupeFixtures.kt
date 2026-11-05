package app.keepfin.core

import app.keepfin.core.dedupe.Recording
import app.keepfin.core.dedupe.Recordings
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/dedupe: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value

private fun JsonObject.obj(key: String): JsonObject? = members[key] as? JsonObject

/** A recording as the fixtures write it: artist, title, album, duration, and optionally sourceIds, contentKey and isrc. */
private fun recordingOf(r: JsonObject) = Recording(
    title = r.string("title") ?: "",
    artist = r.string("artist"),
    album = r.string("album"),
    duration = r.number("duration"),
    sourceIds = (r.members["sourceIds"] as? JsonArray)?.items?.map { (it as JsonString).value }?.toSet().orEmpty(),
    contentKey = r.string("contentKey"),
    isrc = r.string("isrc"),
)

internal fun dedupeHandler(input: JsonObject): JsonValue {
    return when (val op = input.string("op")) {
        "sourceId" -> jstr(Recordings.sourceId(input.string("url")))
        "normalizeTitle" -> jstr(Recordings.normalizeTitle(input.string("title")!!, input.string("artist")))
        "normalizeArtists" -> arr(Recordings.normalizeArtists(input.string("artist")).map { jstr(it) })
        "same" -> obj("match" to JsonBool(Recordings.sameRecording(recordingOf(input.obj("a")!!), recordingOf(input.obj("b")!!))))
        "compare" -> obj("tier" to jstr(Recordings.compare(recordingOf(input.obj("a")!!), recordingOf(input.obj("b")!!))?.wire))
        "existing" -> {
            val known = (input.members.getValue("items") as JsonArray).items.map { it as JsonObject }
                .filter { it.bool("missing") != true }
                .map { i ->
                    i.string("id")!! to Recording(
                        title = i.string("title")!!,
                        artist = i.string("artist") ?: i.string("albumArtist"),
                        album = i.string("album"),
                        duration = i.number("duration"),
                        sourceIds = setOfNotNull(i.string("sourceId")),
                        isrc = i.string("isrc"),
                    )
                }
            val probe = input.obj("probe")!!
            val r = probe.obj("recording")
            val candidate = Recording(
                title = r?.string("title") ?: "",
                artist = r?.string("artist"),
                album = r?.string("album"),
                duration = r?.number("duration"),
                sourceIds = setOfNotNull(probe.string("sourceId")),
                isrc = probe.string("isrc") ?: r?.string("isrc"),
            )
            val found = Recordings.find(candidate, known)
            obj("itemId" to jstr(found?.first), "by" to jstr(found?.second?.wire))
        }
        else -> error("unknown dedupe op $op")
    }
}
