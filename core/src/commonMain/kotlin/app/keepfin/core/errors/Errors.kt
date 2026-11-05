package app.keepfin.core.errors

import app.keepfin.core.music.WS
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim

/**
 * Failure text from the download engine -> a class, a retry kind and a short message that names no tool.
 * The rules and wording are the desktop's; [Platform.ANDROID] differs only where a fixture records it
 * (a 403 refreshes the bundled engine) and in the wording of a few messages.
 */
object Errors {
    private val androidLeadingTag = Regex("^$WS*\\[[^\\]]+\\]$WS*")
    private val androidLeadingId = Regex("^[A-Za-z0-9_-]{6,}:$WS*")

    private fun isErrorLine(line: String): Boolean = asciiLower(jsTrim(line)).let { it.startsWith("error") || it.startsWith("[error]") }

    /** Reads one failure text. [exhausted] picks the wording used once the automatic retries are used up. */
    fun analyze(text: String, platform: Platform = Platform.DESKTOP, exhausted: Boolean = false): Analysis {
        val detail = detailLine(text, platform)
        if (text.contains(Messages.MARKER_MUSIC)) {
            return Analysis(ErrorClass.REGION_BLOCKED, RetryKind.PERMANENT, Action.SIGN_IN, Messages.RESTRICTED_MUSIC, true, detail)
        }
        if (text.contains(Messages.MARKER_OTHER)) {
            return Analysis(ErrorClass.REGION_BLOCKED, RetryKind.PERMANENT, Action.NONE, Messages.RESTRICTED_OTHER, true, detail)
        }
        // Match against ERROR lines first so incidental WARNING lines do not misclassify.
        val errorLines = splitLines(text).filter { isErrorLine(it) }.joinToString("\n")
        for (source in listOf(errorLines, text)) {
            if (source.isEmpty()) continue
            val form = matchingForm(source)
            val rule = rules.firstOrNull { it.appliesTo(platform) && it.matches(form, platform) } ?: continue
            return Analysis(rule.errorClass, rule.retryFor(platform), rule.action, rule.messageFor(platform, exhausted), true, detail)
        }
        // Unclassified engine stop or crash: worth one retry after an engine refresh.
        return Analysis(ErrorClass.UNKNOWN, RetryKind.ENGINE, Action.RETRY, unrecognizedMessage(text, platform, detail), false, detail)
    }

    private fun unrecognizedMessage(text: String, platform: Platform, detail: String?): String {
        if (platform == Platform.DESKTOP) {
            return if (!detail.isNullOrEmpty()) Messages.STOPPED_PREFIX_DESKTOP + detail.take(200) else Messages.STOPPED_DESKTOP
        }
        if (jsTrim(text).isEmpty()) return Messages.BLANK_ANDROID
        val lines = splitLines(text).map { jsTrim(it) }
        val line = lines.lastOrNull { it.startsWith("ERROR:") }
            ?: lines.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING:") && !it.startsWith("[") }
            ?: jsTrim(text)
        val cleaned = androidLeadingId.replace(androidLeadingTag.replace(line.removePrefix("ERROR:"), ""), "")
        return Scrub.scrub(jsTrim(cleaned).take(300), platform).ifEmpty { Messages.BLANK_ANDROID }
    }

    fun classify(text: String, platform: Platform = Platform.DESKTOP): ErrorClass = analyze(text, platform).errorClass

    /** One clear sentence with the next step. */
    fun friendly(text: String?, platform: Platform = Platform.DESKTOP, exhausted: Boolean = false): String =
        analyze(text.orEmpty(), platform, exhausted).message

    /** Removes tool names and raw log prefixes from text that may reach the UI. */
    fun scrub(text: String, platform: Platform = Platform.DESKTOP): String = Scrub.scrub(text, platform)

    /** The last meaningful line of the engine's output (at most 500 characters). */
    fun lastErrorLine(text: String): String? = app.keepfin.core.errors.lastErrorLine(text)

    /** The engine's last lines for a "Details" view, tool names scrubbed, at most [limit] characters from the end. */
    fun detail(text: String, limit: Int = 3000, platform: Platform = Platform.DESKTOP, lines: Int = 20): String =
        splitLines(text).map { jsTrim(it) }.filter { !it.startsWith("keepfin-restricted:") }
            .map { Scrub.scrub(it, platform) }.filter { it.isNotEmpty() }
            .takeLast(lines).joinToString("\n").takeLast(limit)

    /** Rate limits and bot checks must never be answered with quick in-place retries. */
    fun throttled(text: String?, platform: Platform = Platform.DESKTOP): Boolean =
        analyze(text.orEmpty(), platform).retry.let { it == RetryKind.RATE_LIMITED || it == RetryKind.BOT_CHECK }

    /** True when the text names a known permanent problem (used for crashes, whose text is otherwise unclassifiable). */
    fun explicitlyPermanent(text: String?, platform: Platform = Platform.DESKTOP): Boolean =
        analyze(text.orEmpty(), platform).let { it.recognized && it.retry == RetryKind.PERMANENT }

    /** Every fixed message the core can produce for [platform] (both wordings), for the "no tool names" proof. */
    fun allMessages(platform: Platform): List<String> {
        val out = LinkedHashSet<String>()
        for (rule in rules) {
            if (!rule.appliesTo(platform)) continue
            out.add(rule.messageFor(platform, false))
            out.add(rule.messageFor(platform, true))
        }
        out.add(Messages.RESTRICTED_MUSIC)
        out.add(Messages.RESTRICTED_OTHER)
        out.add(if (platform == Platform.DESKTOP) Messages.STOPPED_DESKTOP else Messages.BLANK_ANDROID)
        return out.toList()
    }
}
