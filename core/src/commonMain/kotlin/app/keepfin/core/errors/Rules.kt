package app.keepfin.core.errors

import app.keepfin.core.music.NOT_EOL

/** Wording, per platform, of the messages the core can produce. None of them names an external tool. */
object Messages {
    const val RATE_LIMIT_ANDROID = "YouTube is limiting downloads from your network right now. Keepfin will retry automatically."
    const val RATE_LIMIT_FINAL_ANDROID = "YouTube is limiting downloads from your network right now. Wait a few minutes, then retry."
    const val BOT_ANDROID = "YouTube asked to confirm you're not a bot. Sign in to YouTube in Settings → Downloads → Website sign-in, then retry."
    const val UNAVAILABLE = "YouTube didn't return this video right now. Retrying may help."
    const val RESTRICTED_MUSIC = "YouTube doesn't play this song in your region without an account, and Keepfin couldn't find another copy. Sign in to YouTube in Settings, then retry."
    const val RESTRICTED_OTHER = "YouTube doesn't make this video available in your region."

    /** Markers put into raw failure text by the app after it confirmed a regional restriction. */
    const val MARKER_MUSIC = "keepfin-restricted:music"
    const val MARKER_OTHER = "keepfin-restricted:other"

    const val BLANK_ANDROID = "Download failed. Retry, or update the engine in Settings."
    const val STOPPED_DESKTOP = "The download engine stopped. Try again."
    const val STOPPED_PREFIX_DESKTOP = "The download engine stopped: "
}

internal class Rule(
    val errorClass: ErrorClass,
    pattern: String,
    val message: String,
    val action: Action,
    val retry: RetryKind,
    /** Wording on Android when it differs. */
    val androidMessage: String? = null,
    /** Android wording once the automatic retries are used up. */
    val androidFinal: String? = null,
    /** Retry kind on Android when it differs (ERR-3: a 403 refreshes the bundled engine there). */
    val androidRetry: RetryKind? = null,
    /** Platforms the rule applies to; null means both. */
    val platforms: Set<Platform>? = null,
    /** Extra patterns only Android recognises (they were in its own table; desktop behaviour stays as it was). */
    androidExtra: String? = null,
) {
    private val regex = Regex(pattern)
    private val androidRegex = if (androidExtra == null) regex else Regex("(?:$pattern)|(?:$androidExtra)")

    fun matches(form: String, platform: Platform): Boolean = (if (platform == Platform.ANDROID) androidRegex else regex).containsMatchIn(form)

    fun appliesTo(platform: Platform) = platforms == null || platform in platforms

    fun messageFor(platform: Platform, exhausted: Boolean): String =
        if (platform == Platform.ANDROID) (if (exhausted && androidFinal != null) androidFinal else androidMessage ?: message) else message

    fun retryFor(platform: Platform): RetryKind = if (platform == Platform.ANDROID) androidRetry ?: retry else retry
}

private const val SIGN_IN_ANDROID = "Sign in required: this content is private or members-only. Sign in from Settings → Downloads → Website sign-in, then retry."
private const val WORD_END = "(?![a-z0-9_])"

/**
 * First match wins: specific causes before generic HTTP and network failures. Patterns are written against the matching form
 * (ASCII lower case, straight apostrophes), so none needs a case-insensitive flag. The order and wording are the desktop's;
 * patterns the Android build recognised in addition were folded into the matching rule.
 */
internal val rules: List<Rule> = listOf(
    Rule(
        ErrorClass.BOT_CHECK,
        "sign in to confirm (you're|you are) not a bot|confirm you're not a bot",
        "YouTube asked to confirm you're not a bot. Sign in to YouTube in Settings → Downloads → Website sign-in (or use browser cookies), then retry.",
        Action.SIGN_IN,
        RetryKind.BOT_CHECK,
        androidMessage = Messages.BOT_ANDROID,
        androidExtra = "bot check",
    ),
    Rule(
        ErrorClass.AGE_RESTRICTED,
        "sign in to confirm your age|age[- ]restricted|inappropriate for some users|confirm your age",
        "This video is age-restricted. Add cookies from a signed-in browser to download it.",
        Action.SIGN_IN,
        RetryKind.PERMANENT,
        androidMessage = "Age-restricted: sign in with an adult account from Settings → Downloads → Website sign-in, then retry.",
        androidExtra = "age[- ]?restrict",
    ),
    Rule(
        ErrorClass.SIGN_IN_REQUIRED,
        "members[- ]only|join this channel|available to this channel's members|requires (a )?(channel )?membership",
        "This is members-only content. Add cookies from an account with access.",
        Action.SIGN_IN,
        RetryKind.PERMANENT,
        androidMessage = SIGN_IN_ANDROID,
    ),
    Rule(
        ErrorClass.SIGN_IN_REQUIRED,
        "private video|this video is private|is private$WORD_END|private (post|account|playlist)|you need to log in|login required|" +
            "sign[- ]?in required|log in to|requires? (authentication|login|logging in)|" +
            "cookies? (are|is) (needed|required)|use --cookies|account credentials|redirect(ed)? to (the )?login page",
        "Sign-in required: this content is private or limited. Add cookies and try again.",
        Action.SIGN_IN,
        RetryKind.PERMANENT,
        androidMessage = SIGN_IN_ANDROID,
        androidExtra = "requires? (a )?(login|authentication|subscription)|logged[- ]?in|account${NOT_EOL}*required|only available for registered users|premium|sign in|log ?in|authentication",
    ),
    Rule(
        ErrorClass.RATE_LIMITED,
        "content isn't available,? try again later|rate[- ]?limit|http error 429|too many requests",
        "YouTube is limiting downloads from your network right now. Keepfin will retry automatically.",
        Action.RETRY,
        RetryKind.RATE_LIMITED,
        androidMessage = Messages.RATE_LIMIT_ANDROID,
        androidFinal = Messages.RATE_LIMIT_FINAL_ANDROID,
    ),
    Rule(
        ErrorClass.REGION_BLOCKED,
        "(not|n't)( made this video)? available in your (country|region)|geo[- ]?restrict|blocked in your (country|region)|" +
            "not available from your location|this content isn't available in your (country|region)",
        "This content is blocked in your region. A proxy in an allowed region may work.",
        Action.TRY_ANOTHER,
        RetryKind.PERMANENT,
        androidMessage = "This media is blocked in your region.",
        androidExtra = "blocked it in your country|available in your country",
    ),
    Rule(
        ErrorClass.NOT_STARTED,
        "premieres? in|premiere will begin|this live event will begin|live event will begin in|is (an )?upcoming|scheduled to start",
        "This premiere or live stream has not started yet. Try again when it begins.",
        Action.RETRY,
        RetryKind.PERMANENT,
        androidMessage = "This live stream hasn't started yet. Try again once it's live.",
        androidExtra = "is_upcoming|live event has not started|premiere",
    ),
    Rule(
        ErrorClass.LIVE_ENDED,
        "this live stream recording is not available|live stream (has )?ended|stream is offline|is not currently live",
        "This live stream is not available right now.",
        Action.RETRY,
        RetryKind.PERMANENT,
    ),
    Rule(
        ErrorClass.REMOVED,
        "(has been|was|been) (removed|deleted|terminated)|removed by|copyright (claim|grounds)|no longer available|" +
            "account (has been|was) (suspended|terminated)|this video does not exist|" +
            "(^|[^a-z0-9_])404([^a-z0-9_])${NOT_EOL}*not found|http error 404",
        "This media was removed or is unavailable.",
        Action.TRY_ANOTHER,
        RetryKind.PERMANENT,
        androidExtra = "does not exist|account (has been )?terminated|has been terminated|http error 410|page not found|been taken down|violating|copyright",
    ),
    Rule(
        ErrorClass.UNAVAILABLE,
        "video unavailable|this video is unavailable|content (is )?not available",
        Messages.UNAVAILABLE,
        Action.RETRY,
        RetryKind.RETRYABLE,
    ),
    Rule(
        ErrorClass.UNKNOWN,
        "http error 5[0-9][0-9]|service unavailable|bad gateway|gateway time-?out",
        "The site had a temporary problem. Keepfin will retry.",
        Action.RETRY,
        RetryKind.RETRYABLE,
    ),
    Rule(
        ErrorClass.NETWORK,
        "page needs to be reloaded|record layer failure|\\[ssl\\]|ssl(error)?:",
        "Network problem while contacting the site. Check your connection and retry.",
        Action.RETRY,
        RetryKind.RETRYABLE,
        androidMessage = "The connection to the site was interrupted. Check your network and retry in a moment.",
        platforms = setOf(Platform.ANDROID),
    ),
    Rule(
        ErrorClass.UNSUPPORTED,
        "unsupported url|no suitable extractor|is not a valid url",
        "This site or link type is not supported.",
        Action.TRY_ANOTHER,
        RetryKind.PERMANENT,
        androidMessage = "This site or link type isn't supported by the download engine.",
    ),
    Rule(
        ErrorClass.DRM,
        "drm",
        "This media is DRM-protected and cannot be downloaded.",
        Action.TRY_ANOTHER,
        RetryKind.PERMANENT,
        androidMessage = "This media is DRM-protected and can't be downloaded.",
    ),
    Rule(
        ErrorClass.FORMAT_UNAVAILABLE,
        "requested format is not available",
        "The chosen quality is not available for this link. Pick another quality.",
        Action.TRY_ANOTHER,
        RetryKind.PERMANENT,
        androidMessage = "That quality isn't available for this media. Inspect again and pick another quality.",
        androidExtra = "requested formats are incompatible|requested format|choose a supported",
    ),
    Rule(
        ErrorClass.FORMAT_UNAVAILABLE,
        "no video formats found|no (media|video) (could be )?found|there's no video in this|no formats found|no video could be found",
        "No downloadable formats were found for this link. Try another quality.",
        Action.UPDATE_ENGINE,
        RetryKind.ENGINE,
        androidMessage = "That quality isn't available for this media. Inspect again and pick another quality.",
    ),
    Rule(
        ErrorClass.EXTRACTOR_BROKEN,
        "nsig extraction failed|signature extraction failed|unable to extract|precondition check failed|player response|failed to decrypt|" +
            "n challenge|js challenge",
        "The site changed how it serves this media. Updating the engine usually fixes this.",
        Action.UPDATE_ENGINE,
        RetryKind.ENGINE,
        androidMessage = "The site rejected the download engine (it may be outdated). Update the engine in Settings, then retry.",
        androidExtra = "failed to extract",
    ),
    Rule(
        ErrorClass.FORBIDDEN,
        "http error 403|403: forbidden|forbidden",
        "The site refused the download right now. Keepfin will retry; adding cookies can help.",
        Action.RETRY,
        RetryKind.RETRYABLE,
        androidMessage = "The site refused this download (HTTP 403), often a temporary limit on your network. Retry in a few minutes; " +
            "if it keeps happening, update the engine in Settings.",
        androidRetry = RetryKind.ENGINE,
        androidExtra = "unable to download api page",
    ),
    Rule(
        ErrorClass.DISK_FULL,
        "no space left|disk (is )?full|enospc|errno 28|not enough space",
        "The disk is full. Free some space or choose another download folder.",
        Action.NONE,
        RetryKind.PERMANENT,
        androidMessage = "Your phone is out of storage. Free up space and retry.",
    ),
    Rule(
        ErrorClass.PERMISSION,
        "permission denied|access is denied|eacces|eperm|errno 13|read-only file system",
        "Keepfin cannot write to the download folder. Choose another folder in Settings.",
        Action.NONE,
        RetryKind.PERMANENT,
        androidMessage = "Keepfin can't write to the download folder. Check the app's storage access, then retry.",
        androidExtra = "operation not permitted",
    ),
    Rule(
        ErrorClass.PROCESSOR_MISSING,
        "ffmpeg (is )?not (found|installed)|ffprobe (is )?not found|ffmpeg could not be found",
        "The media processor is missing, so streams cannot be merged or converted.",
        Action.UPDATE_ENGINE,
        RetryKind.ENGINE,
        androidMessage = "The media processor is missing. Update the engine in Settings, then retry.",
        androidExtra = "(ffmpeg|ffprobe)${NOT_EOL}{0,40}(not installed|not found|is missing)|requested merging of multiple formats but",
    ),
    Rule(
        ErrorClass.PROCESSING,
        "postprocessing:|conversion failed|ffmpeg${NOT_EOL}*(error|failed)|error (while )?(merging|muxing)|invalid data found when processing input",
        "Processing the media failed. Try another format or container.",
        Action.TRY_ANOTHER,
        RetryKind.RETRYABLE,
        androidMessage = "Processing the downloaded media failed. Retry, or choose a different format.",
        androidExtra = "postprocessing|error opening output|invalid data found",
    ),
    Rule(
        ErrorClass.COOKIES_UNREADABLE,
        "could not copy ${NOT_EOL}* cookie database|failed to decrypt with dpapi|cookies? database is locked|could not find ${NOT_EOL}* cookies database",
        "Could not read browser cookies. Close the browser or use Firefox / a cookies.txt file.",
        Action.SIGN_IN,
        RetryKind.PERMANENT,
        platforms = setOf(Platform.DESKTOP),
    ),
    Rule(
        ErrorClass.NETWORK,
        "timed out|timeout|etimedout|econnreset|econnrefused|enotfound|eai_again|getaddrinfo|connection (reset|refused|aborted)|" +
            "network is unreachable|temporary failure in name resolution|unable to download webpage|failed to resolve|remote end closed|incompleteread|" +
            "ssl: |curl: \\([0-9]+\\)",
        "Network problem while contacting the site. Check your connection and retry.",
        Action.RETRY,
        RetryKind.RETRYABLE,
        androidMessage = "Network problem. Check your connection and retry.",
        androidExtra = "connection (closed|interrupted)|temporary failure|name resolution|reset by peer|broken pipe|unable to connect|" +
            "no address associated|eof occurred|incomplete (read|download)|temporarily unavailable|download interrupted|" +
            "could not locate the downloaded file",
    ),
    Rule(
        ErrorClass.UNKNOWN,
        "aria2",
        "The download engine stopped. Try again.",
        Action.RETRY,
        RetryKind.ENGINE,
        androidMessage = "Multi-connection transfer failed. Retry; Keepfin falls back to standard transfers.",
        platforms = setOf(Platform.ANDROID),
    ),
)
