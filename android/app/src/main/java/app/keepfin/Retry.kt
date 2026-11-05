package app.keepfin

import app.keepfin.core.errors.Platform
import app.keepfin.core.errors.Retry

/**
 * Automatic retries: which failures deserve another attempt and how long to wait. The decision itself is the shared core's
 * (`app.keepfin.core.errors.Retry`, fixtures/engine/errors/retry.json); this object adapts it to the app's types and row text.
 */
object RetryPolicy {
    /** Automatic retries after the first attempt (so at most four tries in total). */
    const val MAX = Retry.MAX

    /** Wait before automatic retry number [retry] (1-based). */
    fun backoffMs(retry: Int): Long = Retry.backoffMs[(retry - 1).coerceIn(0, Retry.backoffMs.lastIndex)]

    /** The engine is refreshed before this retry number when the failure points at it. */
    const val ENGINE_UPDATE_BEFORE_RETRY = Retry.ENGINE_REFRESH_FROM
    const val ENGINE_STALE_MS = 60 * 60 * 1000L

    /** YouTube-style throttling: wait much longer between attempts so a burst of retries doesn't prolong the block. */
    fun rateLimitBackoffMs(retry: Int): Long = Retry.rateLimitBackoffMs[(retry - 1).coerceIn(0, Retry.rateLimitBackoffMs.lastIndex)]

    /** A bot check gets exactly one retry, after this wait, then fails with the sign-in message. */
    val BOT_CHECK_WAIT_MS: Long = Retry.botCheckBackoffMs.first()

    /** [RATE_LIMITED] and [BOT_CHECK] are throttling signals; the rest keep their original meaning. */
    enum class Kind { RETRYABLE, ENGINE, PERMANENT, RATE_LIMITED, BOT_CHECK }

    data class Decision(val retry: Int, val delayMs: Long, val engineRelated: Boolean, val rateLimited: Boolean = false, val refreshEngine: Boolean = false)

    /**
     * Decides what to do after a failed attempt. [retriesUsed] is how many automatic retries were already scheduled.
     * Returns null when the failure is permanent or the budget is spent.
     */
    fun next(retriesUsed: Int, raw: String?, crashed: Boolean = false): Decision? =
        Retry.next(retriesUsed, raw.orEmpty(), crashed, Platform.ANDROID)?.let {
            Decision(it.retry, it.delayMs, it.engineRelated, it.rateLimited, it.refreshEngine)
        }

    /** Row text while a retry waits. */
    fun waitingText(secondsLeft: Long, retry: Int, engineUpdating: Boolean): String =
        if (engineUpdating) "Updating engine, then retrying ($retry of $MAX)" else "Retrying in ${secondsLeft.coerceAtLeast(0)} s ($retry of $MAX)"
}
