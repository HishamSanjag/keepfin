package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.versions.Channel
import app.keepfin.core.versions.Platform
import app.keepfin.core.versions.Releases

// fixtures/engine/versions: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

internal fun versionsHandler(input: JsonObject): JsonValue {
    return when (val op = input.string("op")) {
        "compare" -> jnum(Releases.compare(input.string("a")!!, input.string("b")!!))
        "pick" -> {
            val versions = (input.members.getValue("versions") as JsonArray).items.map { (it as JsonString).value }
            jstr(Releases.pick(versions, Channel.of(input.string("channel")), input.string("current")!!))
        }
        "valid" -> JsonBool(Releases.isValid(input.string("value")!!))
        "highlights" -> {
            val limit = (input.members["limit"] as? JsonNumber)?.value?.toInt() ?: 3
            arr(Releases.highlights(input.string("notes"), Platform.of(input.string("platform")), limit).map { jstr(it) })
        }
        else -> error("unknown versions op $op")
    }
}
