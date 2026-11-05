package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.playback.Playback
import app.keepfin.core.playback.SkipRun

// fixtures/engine/playback: see SCHEMA.md there.

private fun JsonObject.num(key: String): Double? = (members[key] as? JsonNumber)?.value
private fun JsonObject.text(key: String): String = (members.getValue(key) as JsonString).value
private fun JsonObject.numbers(key: String): List<Double> = (members.getValue(key) as JsonArray).items.map { (it as JsonNumber).value }
private fun JsonObject.strings(key: String): List<String>? = (members[key] as? JsonArray)?.items?.map { (it as JsonString).value }

internal fun playbackHandler(input: JsonObject): JsonValue {
    return when (val op = (input.members["op"] as JsonString).value) {
        "skips" -> {
            var run: SkipRun? = null
            arr((input.members.getValue("steps") as JsonArray).items.map { step ->
                step as JsonObject
                run = Playback.accumulateSkip(run, step.num("delta")!!, step.num("now")!!)
                jstr(Playback.skipLabel(run!!.total))
            })
        }
        "announce" -> jstr(Playback.skipAnnouncement(input.num("total")!!))
        "resume" -> jnum(Playback.resumeFrom(input.num("position"), input.num("duration"), (input.members["watched"] as JsonBool).value))
        "chapterAt" -> jnum(Playback.chapterAt(input.numbers("starts"), input.num("time")!!))
        "chapterTarget" -> jnum(Playback.chapterTarget(input.numbers("starts"), input.num("time")!!, input.num("dir")!!.toInt()))
        "crossed" -> JsonBool(Playback.crossedChapter(input.numbers("starts"), input.num("from")!!, input.num("to")!!))
        "list" -> arr(Playback.playbackList(input.strings("context")!!, input.strings("folder")).map { jstr(it) })
        "neighbour" -> jstr(Playback.neighbour(input.strings("list")!!, input.text("id"), input.num("dir")!!.toInt()))
        "folderOrder" -> {
            val items = (input.members.getValue("items") as JsonArray).items.map { it as JsonObject }
            arr(Playback.folderOrder(items.map { it.text("id") }, items.map { it.text("title") }).map { jstr(it) })
        }
        "clock" -> jstr(Playback.clock(input.num("seconds")!!))
        "timeLeft" -> jstr(Playback.timeLeft(input.num("seconds")!!))
        "speeds" -> arr(Playback.SPEEDS.map { jnum(it) })
        else -> error("unknown playback op $op")
    }
}
