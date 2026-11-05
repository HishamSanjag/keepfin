package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import kotlin.math.abs
import kotlin.math.floor

/** JavaScript-style number text: whole numbers without a fraction. */
internal fun numberText(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

/** Compact JSON text of a value (test helper; the core itself only needs to read JSON). */
internal fun show(value: JsonValue?): String = when (value) {
    null -> "<absent>"
    is JsonNull -> "null"
    is JsonBool -> value.value.toString()
    is JsonNumber -> numberText(value.value)
    is JsonString -> jsonQuote(value.value)
    is JsonArray -> value.items.joinToString(",", "[", "]") { show(it) }
    is JsonObject -> value.members.entries.joinToString(",", "{", "}") { jsonQuote(it.key) + ":" + show(it.value) }
}

internal fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

internal fun arr(items: List<JsonValue>): JsonArray = JsonArray(items)

internal fun jstr(value: String?): JsonValue = if (value == null) JsonNull else JsonString(value)

internal fun jnum(value: Double?): JsonValue = if (value == null) JsonNull else JsonNumber(value)

internal fun jnum(value: Int): JsonValue = JsonNumber(value.toDouble())
