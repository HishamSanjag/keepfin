package app.keepfin.core

import app.keepfin.core.errors.Errors
import app.keepfin.core.errors.Platform
import app.keepfin.core.errors.Retry
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.music.asciiLower
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// fixtures/engine/errors: see SCHEMA.md there.

private val toolNames = listOf("yt-dlp", "ytdlp", "yt_dlp", "youtube-dl", "youtubedl", "ffmpeg", "ffprobe", "gallery-dl", "aria2")

internal fun namesATool(text: String): Boolean = asciiLower(text).let { lower -> toolNames.any { lower.contains(it) } }

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.int(key: String): Int? = (members[key] as? JsonNumber)?.value?.toInt()

internal fun errorsHandler(input: JsonObject, platform: Platform): JsonValue {
    val text = input.string("text")!!
    return when (val op = input.string("op")) {
        "scrub" -> {
            val scrubbed = Errors.scrub(text, platform)
            val detail = Errors.detail(text, platform = platform)
            obj("noToolNames" to JsonBool(!namesATool(scrubbed) && !namesATool(detail)))
        }
        "classify" -> {
            val analysis = Errors.analyze(text, platform)
            val clean = !namesATool(analysis.message) && !namesATool(analysis.detail.orEmpty()) && !namesATool(Errors.detail(text, platform = platform))
            check(clean) { "a user-facing message names an external tool: ${analysis.message}" }
            obj(
                "class" to jstr(analysis.errorClass.wire),
                "retry" to jstr(analysis.retry.wire),
                "noToolNames" to JsonBool(clean),
            )
        }
        "retry" -> {
            val decision = Retry.next(input.int("retriesUsed") ?: 0, text, (input.members["crashed"] as? JsonBool)?.value ?: false, platform)
            if (decision == null) {
                JsonNull
            } else {
                obj(
                    "retry" to jnum(decision.retry),
                    "delayMs" to jnum(decision.delayMs.toDouble()),
                    "engineRelated" to JsonBool(decision.engineRelated),
                    "rateLimited" to JsonBool(decision.rateLimited),
                    "refreshEngine" to JsonBool(decision.refreshEngine),
                )
            }
        }
        else -> error("unknown errors op $op")
    }
}

/** The "no tool names" proof: every message the core can produce, for every platform, exhausted or not. */
class ErrorsNoToolNamesTest {
    @Test
    fun everyFixedMessageIsFree() {
        val problems = ArrayList<String>()
        for (platform in Platform.entries) {
            val messages = Errors.allMessages(platform)
            assertTrue(messages.size > 20, "expected the full message table, got ${messages.size}")
            for (message in messages) if (namesATool(message)) problems.add("${platform.wire}: $message")
        }
        if (problems.isNotEmpty()) fail("messages naming a tool:\n" + problems.joinToString("\n"))
    }

    @Test
    fun everyMessageForEveryFixtureTextIsFree() {
        val problems = ArrayList<String>()
        val noise = " yt-dlp.exe ffmpeg gallery-dl aria2c youtube-dl yt_dlp.utils.DownloadError python3.11 mutagen"
        val classes = HashSet<String>()
        for (case in loadCases("errors")) {
            val text = case.input.string("text") ?: continue
            for (platform in Platform.entries) for (exhausted in listOf(false, true)) for (variant in listOf(text, "$text$noise", "ERROR: $noise\n$text")) {
                val analysis = Errors.analyze(variant, platform, exhausted)
                classes.add(analysis.errorClass.wire)
                for (shown in listOf(analysis.message, analysis.detail.orEmpty(), Errors.friendly(variant, platform, exhausted), Errors.scrub(variant, platform), Errors.detail(variant, platform = platform))) {
                    if (namesATool(shown)) problems.add("${platform.wire}/exhausted=$exhausted: ${case.name}: $shown")
                }
            }
        }
        assertTrue(classes.size >= 18, "the texts should reach most classes, reached $classes")
        if (problems.isNotEmpty()) fail("output naming a tool:\n" + problems.take(20).joinToString("\n"))
    }
}
