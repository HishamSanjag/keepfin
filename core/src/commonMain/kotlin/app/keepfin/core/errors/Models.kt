package app.keepfin.core.errors

/** Which app is asking. The rules are shared; only wording and a few deliberate differences depend on it (fixtures `platforms`). */
enum class Platform(val wire: String) {
    DESKTOP("desktop"),
    ANDROID("android"),
    ;

    companion object {
        fun fromWire(value: String): Platform = entries.firstOrNull { it.wire == value } ?: error("unknown platform $value")
    }
}

/** What went wrong, as the fixtures name it (fixtures/engine/errors/SCHEMA.md). */
enum class ErrorClass(val wire: String) {
    BOT_CHECK("botCheck"),
    AGE_RESTRICTED("ageRestricted"),
    SIGN_IN_REQUIRED("signInRequired"),
    RATE_LIMITED("rateLimited"),
    REGION_BLOCKED("regionBlocked"),
    NOT_STARTED("notStarted"),
    LIVE_ENDED("liveEnded"),
    REMOVED("removed"),
    UNAVAILABLE("unavailable"),
    UNSUPPORTED("unsupported"),
    DRM("drm"),
    FORMAT_UNAVAILABLE("formatUnavailable"),
    EXTRACTOR_BROKEN("extractorBroken"),
    FORBIDDEN("forbidden"),
    DISK_FULL("diskFull"),
    PERMISSION("permission"),
    PROCESSOR_MISSING("processorMissing"),
    PROCESSING("processing"),
    COOKIES_UNREADABLE("cookiesUnreadable"),
    NETWORK("network"),
    UNKNOWN("unknown"),
}

/** Whether and how an automatic retry can help. */
enum class RetryKind(val wire: String) {
    /** No automatic retry. */
    PERMANENT("permanent"),

    /** Plain backoff (5, 15, 30 s). */
    RETRYABLE("retryable"),

    /** Refresh the download engine, then retry. */
    ENGINE("engine"),

    /** Wait much longer between attempts (60, 180, 300 s). */
    RATE_LIMITED("rateLimited"),

    /** One retry after 120 s. */
    BOT_CHECK("botCheck"),
}

/** The next step the app suggests next to the message. */
enum class Action(val wire: String) {
    SIGN_IN("signIn"),
    UPDATE_ENGINE("updateEngine"),
    RETRY("retry"),
    TRY_ANOTHER("tryAnother"),
    NONE("none"),
}

/**
 * The result of reading one failure text. [recognized] is false when no rule matched (the class is then UNKNOWN and the
 * retry kind ENGINE: an outdated engine words new failures unpredictably). [message] never names an external tool.
 */
class Analysis(
    val errorClass: ErrorClass,
    val retry: RetryKind,
    val action: Action,
    val message: String,
    val recognized: Boolean,
    val detail: String?,
)
