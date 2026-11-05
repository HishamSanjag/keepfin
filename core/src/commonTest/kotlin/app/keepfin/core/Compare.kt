package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import kotlin.math.abs

/**
 * The comparison rules of fixtures/engine/README.md: `expected` is a subset of `actual`.
 * Objects: every expected key must match. Arrays: same length, element by element. null: null or absent.
 * Numbers compare as doubles; a key named `size` may differ by 1.
 */
internal fun compareSubset(actual: JsonValue?, expected: JsonValue, where: String, failures: MutableList<String>) {
    when (expected) {
        is JsonNull -> if (actual != null && actual !is JsonNull) failures.add("$where: expected null, got ${show(actual)}")
        is JsonArray -> {
            if (actual !is JsonArray) {
                failures.add("$where: expected an array, got ${show(actual)}")
            } else if (actual.items.size != expected.items.size) {
                failures.add("$where: array length ${actual.items.size}, expected ${expected.items.size} (actual ${show(actual)})")
            } else {
                expected.items.forEachIndexed { i, item -> compareSubset(actual.items[i], item, "$where[$i]", failures) }
            }
        }
        is JsonObject -> {
            if (actual !is JsonObject) {
                failures.add("$where: expected an object, got ${show(actual)}")
            } else {
                for ((key, value) in expected.members) {
                    val got = actual.members[key]
                    if (key == "size" && value is JsonNumber && got is JsonNumber) {
                        if (abs(got.value - value.value) > 1) failures.add("$where.size: ${got.value} vs ${value.value}")
                        continue
                    }
                    compareSubset(got, value, "$where.$key", failures)
                }
            }
        }
        is JsonString -> if (actual !is JsonString || actual.value != expected.value) failures.add("$where: expected ${show(expected)}, got ${show(actual)}")
        is JsonNumber -> if (actual !is JsonNumber || actual.value != expected.value) failures.add("$where: expected ${show(expected)}, got ${show(actual)}")
        is JsonBool -> if (actual !is JsonBool || actual.value != expected.value) failures.add("$where: expected ${show(expected)}, got ${show(actual)}")
    }
}
