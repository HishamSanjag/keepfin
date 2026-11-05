package app.keepfin.core

import app.keepfin.core.after.AfterDownload
import app.keepfin.core.after.Facts
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/after: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value
private fun JsonObject.flag(key: String, default: Boolean): Boolean = (members[key] as? JsonBool)?.value ?: default

internal fun afterHandler(input: JsonObject): JsonValue = when (val op = input.string("op")) {
    "plan" -> {
        val facts = Facts(
            audio = input.flag("audio", false), hasFile = input.flag("hasFile", true), existing = input.flag("existing", false),
            video = input.string("video") ?: "none", playlist = input.flag("playlist", false), lyrics = input.flag("lyrics", false),
            library = input.string("library") ?: "folder",
        )
        obj(
            "steps" to arr(
                AfterDownload.plan(facts).map { obj("kind" to jstr(it.kind), "onFailure" to jstr(it.onFailure), "fallback" to JsonBool(it.fallback)) },
            ),
        )
    }
    else -> error("unknown after op $op")
}
