package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.progress.ProgressParser
import app.keepfin.core.progress.ProgressPlatform
import kotlin.math.abs

// fixtures/engine/progress: see SCHEMA.md there. A step is a raw log line or a neutral progress event, rendered in
// the platform's own wire format (the part of the platform that differs; the core parses both).

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun wireNumber(step: JsonObject, key: String): String = step.number(key)?.let { numberText(it) } ?: "NA"

private fun renderStep(platform: String, step: JsonObject): String {
    val id = step.string("formatId")!!
    val status = step.string("status")!!
    val numbers = listOf("downloaded", "total", "estimate", "speed", "eta").map { wireNumber(step, it) }
    return if (platform == "android") {
        "[keepfin] $status|${numbers.joinToString("|")}|$id"
    } else {
        "KEEPFIN-P|$id|$status|${numbers.joinToString("|")}|${wireNumber(step, "fragmentIndex")}|${wireNumber(step, "fragmentCount")}"
    }
}

internal fun progressHandler(platform: String): Handler = { input ->
    val sizes = input.members["streamSizes"] as? JsonObject
    val parser = if (platform == "android") {
        // Android knows the job's inspected size as one number.
        ProgressParser(ProgressPlatform.ANDROID, expectedSize = sizes?.members?.values?.sumOf { (it as JsonNumber).value })
    } else {
        ProgressParser(ProgressPlatform.DESKTOP, streamSizes = sizes?.members?.mapValues { (it.value as JsonNumber).value } ?: emptyMap())
    }
    val checkpoints = ArrayList<JsonValue>()
    for (step in (input.members.getValue("steps") as JsonArray).items) {
        step as JsonObject
        parser.accept(if (step.string("type") == "line") step.string("text")!! else renderStep(platform, step))
        val s = parser.snapshot
        checkpoints.add(
            obj(
                "percent" to jnum(s.percent), "downloadedBytes" to jnum(s.downloadedBytes), "totalBytes" to jnum(s.totalBytes),
                "speed" to jnum(s.speed), "eta" to jnum(s.eta), "phase" to jstr(s.phase), "processing" to JsonBool(s.processing),
            ),
        )
    }
    arr(checkpoints)
}

/** Expectations are `{after: <step>, ...}`; percent, speed and eta match within 1 because the platforms round differently. */
internal fun compareProgress(actual: JsonValue, expected: JsonValue, where: String, failures: MutableList<String>) {
    val steps = (actual as JsonArray).items
    for (want in (expected as JsonArray).items) {
        want as JsonObject
        val after = want.number("after")!!.toInt()
        val got = steps.getOrNull(after) as? JsonObject
        if (got == null) {
            failures.add("$where: no step $after")
            continue
        }
        val rest = LinkedHashMap(want.members)
        rest.remove("after")
        for (key in listOf("percent", "speed", "eta")) {
            val value = rest.remove(key) ?: continue
            val gotValue = got.members[key]
            if (value is JsonNull) {
                if (gotValue != null && gotValue !is JsonNull) failures.add("$where step $after $key: expected null, got ${show(gotValue)}")
            } else {
                val a = (gotValue as? JsonNumber)?.value
                val e = (value as JsonNumber).value
                if (a == null || abs(a - e) > 1) failures.add("$where step $after $key: ${show(gotValue)} vs ${show(value)}")
            }
        }
        compareSubset(got, JsonObject(rest), "$where step $after", failures)
    }
}
