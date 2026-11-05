package app.keepfin

import app.keepfin.core.errors.Errors
import app.keepfin.core.errors.Messages
import app.keepfin.core.errors.Platform
import app.keepfin.core.errors.Retry
import app.keepfin.core.errors.RetryKind

/**
 * Pure: raw engine output → one clear sentence with the next step. Tool names never reach the UI.
 * What a failure text means (class, retry kind, wording, scrubbing, details) is decided by the shared core
 * (`app.keepfin.core.errors`, fixtures/engine/errors); only the in-place retry heuristics below are Android's own.
 */
object ErrorMessages {
    const val RATE_LIMIT_MESSAGE = Messages.RATE_LIMIT_ANDROID
    const val RATE_LIMIT_FINAL_MESSAGE = Messages.RATE_LIMIT_FINAL_ANDROID
    const val BOT_MESSAGE = Messages.BOT_ANDROID

    /** Maps the failure to an actionable message; [exhausted] words a rate limit for a download that gave up. */
    fun friendly(raw: String?, exhausted: Boolean = false): String = Errors.friendly(raw, Platform.ANDROID, exhausted)

    /** Removes engine/tool names and "ERROR:" / "[extractor]" prefixes from any raw text that might reach the UI. */
    fun scrub(raw: String?): String = Errors.scrub(raw.orEmpty(), Platform.ANDROID)

    /** Rate limits and bot checks must never be hammered by quick in-place retries. */
    fun throttled(raw: String?): Boolean = Errors.throttled(raw, Platform.ANDROID)

    /** Pure classification of a raw failure for automatic retries (see [RetryPolicy.next]). */
    fun classify(raw: String?): RetryPolicy.Kind = when (Errors.analyze(raw.orEmpty(), Platform.ANDROID).retry) {
        RetryKind.PERMANENT -> RetryPolicy.Kind.PERMANENT
        RetryKind.RETRYABLE -> RetryPolicy.Kind.RETRYABLE
        RetryKind.ENGINE -> RetryPolicy.Kind.ENGINE
        RetryKind.RATE_LIMITED -> RetryPolicy.Kind.RATE_LIMITED
        RetryKind.BOT_CHECK -> RetryPolicy.Kind.BOT_CHECK
    }

    /** Failures that get exactly one retry after an engine refresh: unrecognised text and "no video formats found". */
    fun engineOnce(raw: String?): Boolean = Retry.engineOnce(raw.orEmpty(), Platform.ANDROID)

    /** True only when the text names a known permanent problem (used for crashes, whose text is otherwise unclassifiable). */
    fun explicitlyPermanent(raw: String?): Boolean = Errors.explicitlyPermanent(raw, Platform.ANDROID)

    /** Full failure text for the "Error details" sheet: tool names scrubbed, bounded in size. */
    fun detail(raw: String?, limit: Int = 4000): String = Errors.detail(raw.orEmpty(), limit, Platform.ANDROID, lines = 40)

    // ---- Android's own in-place retry heuristics (no desktop counterpart) -----------------------------------------------

    /** Provider hiccups that usually succeed when simply tried again. */
    fun transient(raw: String?): Boolean = !throttled(raw) && Regex("needs to be reloaded|http error 5[0-9][0-9]|temporarily unavailable|service unavailable|try again", RegexOption.IGNORE_CASE).containsMatchIn(raw.orEmpty())

    /** Failures where a second attempt with the plain downloader or fresh extraction can help. */
    fun retryable(raw: String?): Boolean {
        val text = raw.orEmpty()
        return !Regex("private video|members.only|sign in|age.?restrict|unsupported url|geo.?restrict|not available in your country|drm|no space left|has been removed", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
