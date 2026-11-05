package app.keepfin.core.errors

/** Which failures get another automatic attempt, and how long to wait. */
object Retry {
    /** Automatic retries after the first attempt (so at most four tries in total). */
    const val MAX = 3

    /** Plain backoff before retries 1 to 3. */
    val backoffMs: List<Long> = listOf(5_000L, 15_000L, 30_000L)

    /** Rate limits clear slowly: wait 1, 3, then 5 minutes. */
    val rateLimitBackoffMs: List<Long> = listOf(60_000L, 180_000L, 300_000L)

    /** A bot check sometimes clears by itself: one retry after two minutes. */
    val botCheckBackoffMs: List<Long> = listOf(120_000L)

    /** The engine is refreshed before this retry number (or earlier, for a failure that gets only one retry). */
    const val ENGINE_REFRESH_FROM = 2

    /**
     * What to do next. [delayMs] is the wait before retry number [retry] (1-based). [engineRelated] says the failure points at
     * the engine; [refreshEngine] that it should be refreshed before this retry. [rateLimited] marks throttling waits.
     */
    class Decision(
        val retry: Int,
        val delayMs: Long,
        val engineRelated: Boolean,
        val rateLimited: Boolean,
        val refreshEngine: Boolean,
    )

    /** The waits for [kind], one per allowed retry; its size is the retry limit. */
    fun backoffFor(kind: RetryKind): List<Long> = when (kind) {
        RetryKind.RATE_LIMITED -> rateLimitBackoffMs
        RetryKind.BOT_CHECK -> botCheckBackoffMs
        else -> backoffMs
    }

    /** Failures that get exactly one retry after an engine refresh on [platform] (see [next]). */
    fun engineOnce(text: String, platform: Platform = Platform.DESKTOP): Boolean =
        Errors.analyze(text, platform).let { it.retry == RetryKind.ENGINE && engineOnce(it, platform) }

    private fun engineOnce(analysis: Analysis, platform: Platform): Boolean =
        platform == Platform.ANDROID && (!analysis.recognized || analysis.errorClass == ErrorClass.FORMAT_UNAVAILABLE)

    /**
     * Decides what to do after a failed attempt. [retriesUsed] is how many automatic retries were already scheduled.
     * Returns null when the failure is permanent or the budget is spent. A [crashed] attempt has no engine output, so
     * only text that names a known permanent problem stops it; otherwise it counts as an engine failure.
     *
     * On Android an unrecognised failure and "no formats found" get one engine refresh and one retry, not the full budget
     * (a stale bundled engine words new failures unpredictably); the desktop keeps its daily-updated engine and the full budget.
     */
    fun next(retriesUsed: Int, text: String, crashed: Boolean = false, platform: Platform = Platform.DESKTOP): Decision? {
        if (retriesUsed >= MAX) return null
        val analysis = Errors.analyze(text, platform)
        val kind = if (crashed) (if (analysis.recognized && analysis.retry == RetryKind.PERMANENT) RetryKind.PERMANENT else RetryKind.ENGINE) else analysis.retry
        if (kind == RetryKind.PERMANENT) return null
        val once = !crashed && kind == RetryKind.ENGINE && engineOnce(analysis, platform)
        if (once && retriesUsed >= 1) return null
        val retry = retriesUsed + 1
        val limit = backoffFor(kind).size
        if (retry > limit) return null
        val delays = backoffFor(kind)
        val engine = kind == RetryKind.ENGINE
        return Decision(
            retry = retry,
            delayMs = delays[(retry - 1).coerceIn(0, delays.lastIndex)],
            engineRelated = engine,
            rateLimited = kind == RetryKind.RATE_LIMITED || kind == RetryKind.BOT_CHECK,
            refreshEngine = engine && (retry >= ENGINE_REFRESH_FROM || once),
        )
    }
}
