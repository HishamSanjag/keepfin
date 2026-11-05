package app.keepfin.core.music

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// Reading untyped JSON the way the first (desktop) implementation read JavaScript objects.

/** Property [key] of an object; null when [element] is not an object or has no such key. */
internal fun JsonValue?.prop(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)

/** JavaScript truthiness of a parsed JSON value. */
internal fun truthy(value: JsonValue?): Boolean = when (value) {
    null, is JsonNull -> false
    is JsonString -> value.value.isNotEmpty()
    is JsonBool -> value.value
    is JsonNumber -> value.value != 0.0 && !value.value.isNaN()
    else -> true
}

/** A non-blank string, trimmed and cut to [max] characters; anything else is null. */
internal fun str(value: JsonValue?, max: Int = 500): String? {
    val string = (value as? JsonString)?.value ?: return null
    val trimmed = jsTrim(string)
    return if (trimmed.isEmpty()) null else trimmed.take(max)
}

/** A finite number above zero; anything else is null. */
internal fun positive(value: JsonValue?): Double? {
    val number = (value as? JsonNumber)?.value ?: return null
    return if (number.isFinite() && number > 0) number else null
}

/** The text of a run's `text` as JavaScript's String() would show it for strings, numbers and booleans; otherwise empty. */
internal fun textOf(run: JsonValue?): String = when (val text = run.prop("text")) {
    is JsonString -> text.value
    is JsonBool -> text.value.toString()
    is JsonNumber -> if (text.value == kotlin.math.floor(text.value) && kotlin.math.abs(text.value) < 1e15) text.value.toLong().toString() else text.value.toString()
    else -> ""
}

internal fun stringOf(run: JsonValue?): String? = (run.prop("text") as? JsonString)?.value

internal fun isArrayIndex(key: String): Boolean {
    if (key.isEmpty() || key.length > 10) return false
    if (key.any { it !in '0'..'9' }) return false
    if (key.length > 1 && key[0] == '0') return false
    return key.length < 10 || key <= "4294967294"
}

/** Object.values order in JavaScript: integer-like keys ascending first, then the others as written. */
internal fun jsValues(node: JsonObject): List<JsonValue> {
    val keys = node.members.keys
    val indexKeys = keys.filter(::isArrayIndex).sortedWith(compareBy({ it.length }, { it }))
    return (indexKeys + keys.filterNot(::isArrayIndex)).map { node.members.getValue(it) }
}
