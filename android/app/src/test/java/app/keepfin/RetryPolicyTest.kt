package app.keepfin

import app.keepfin.RetryPolicy.Kind
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {
    @Test fun networkAndServerFailuresAreRetryable() {
        listOf(
            "ERROR: unable to download video data: HTTP Error 503: Service Unavailable",
            "ERROR: HTTP Error 429: Too Many Requests",
            "ERROR: Read timed out.",
            "ERROR: [Errno 104] Connection reset by peer",
            "WARNING: fragment 12 not found, unable to continue",
            "ERROR: Postprocessing: Conversion failed!",
            "ERROR: [generic] Unable to download webpage: <urlopen error [Errno 111] Connection refused>",
            "ERROR: unable to download video data: HTTP Error 403: Forbidden (fragment 3)",
            "ERROR: Could not locate the downloaded file.",
        ).forEach { assertNotEquals(it, Kind.PERMANENT, ErrorMessages.classify(it)) }
        assertEquals(Kind.RETRYABLE, ErrorMessages.classify("ERROR: Read timed out."))
        assertEquals(Kind.RETRYABLE, ErrorMessages.classify("ERROR: unable to download video data: HTTP Error 503: Service Unavailable"))
    }

    @Test fun extractorAndEngineFailuresAskForAnEngineRefresh() {
        assertEquals(Kind.ENGINE, ErrorMessages.classify("ERROR: [youtube] abc: Unable to extract initial data; please report this issue"))
        assertEquals(Kind.ENGINE, ErrorMessages.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(Kind.ENGINE, ErrorMessages.classify("ERROR: nsig extraction failed: Some formats may be missing"))
    }

    @Test fun permanentFailuresNeverRetry() {
        listOf(
            "ERROR: Sign in to confirm your age. This video may be inappropriate for some users.",
            "ERROR: Private video. Sign in if you've been granted access",
            "ERROR: Join this channel to get access to members-only content",
            "ERROR: Video unavailable. This video has been removed by the uploader",
            "ERROR: The uploader has not made this video available in your country",
            "ERROR: Video unavailable. This video contains content from X, who has blocked it on copyright grounds",
            "ERROR: Unsupported URL: https://example.com/x",
            "ERROR: [Errno 28] No space left on device",
            "OSError: [Errno 13] Permission denied: '/storage/x'",
            "ERROR: Requested format is not available. Use --list-formats",
            "Choose a supported resolution.",
            "ERROR: This video is DRM protected",
            "ERROR: HTTP Error 404: Not Found",
        ).forEach { assertEquals(it, Kind.PERMANENT, ErrorMessages.classify(it)) }
    }

    @Test fun unrecognisedTextGetsOneEngineRefreshRetryThenFails() {
        // Empty output and text no rule knows: the engine may simply be outdated, so one refresh and one retry, then fail.
        listOf("", "ERROR: something exploded unexpectedly", "ERROR: [Instagram] xyz: No video formats found!").forEach { text ->
            assertEquals(text, Kind.ENGINE, ErrorMessages.classify(text))
            assertTrue(text, ErrorMessages.engineOnce(text))
            assertTrue(text, RetryPolicy.next(0, text)?.engineRelated == true)
            assertNull(text, RetryPolicy.next(1, text))
        }
        // A known engine failure keeps the full budget.
        assertNotNull(RetryPolicy.next(1, "ERROR: [youtube] abc: nsig extraction failed"))
    }

    @Test fun backoffIsFiveFifteenThirtySeconds() {
        assertEquals(listOf(5_000L, 15_000L, 30_000L), (1..3).map(RetryPolicy::backoffMs))
        assertEquals(30_000L, RetryPolicy.backoffMs(9))
    }

    @Test fun attemptBudgetIsThreeAutomaticRetries() {
        val raw = "ERROR: Read timed out."
        assertEquals(RetryPolicy.Decision(1, 5_000, false), RetryPolicy.next(0, raw))
        assertEquals(RetryPolicy.Decision(2, 15_000, false), RetryPolicy.next(1, raw))
        assertEquals(RetryPolicy.Decision(3, 30_000, false), RetryPolicy.next(2, raw))
        assertNull("The fourth failure is final", RetryPolicy.next(3, raw))
        assertNull(RetryPolicy.next(0, "ERROR: Private video"))
    }

    @Test fun engineFailuresAreMarkedEngineRelatedAndCrashesRetryUnlessPermanent() {
        assertTrue(RetryPolicy.next(1, "Unable to extract initial data")!!.engineRelated)
        assertTrue(RetryPolicy.next(0, "The download engine stopped unexpectedly.", crashed = true)!!.engineRelated)
        assertNull(RetryPolicy.next(0, "ERROR: No space left on device", crashed = true))
        assertEquals(2, RetryPolicy.ENGINE_UPDATE_BEFORE_RETRY)
    }

    @Test fun waitingTextIsTruthful() {
        assertEquals("Retrying in 12 s (2 of 3)", RetryPolicy.waitingText(12, 2, false))
        assertEquals("Retrying in 0 s (1 of 3)", RetryPolicy.waitingText(-3, 1, false))
        assertEquals("Updating engine, then retrying (2 of 3)", RetryPolicy.waitingText(12, 2, true))
    }

    @Test fun groupSummaryCountsAndText() {
        fun job(id: String, status: JobStatus, percent: Float = 0f, saveError: String? = null) = Job(
            id = id, url = "https://a.b/$id", title = id, thumbnail = null, uploader = null, duration = null, provider = null, label = "", kind = MediaKind.VIDEO,
            status = status, percent = percent, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = null,
            error = null, file = null, publicUri = null, publicPath = null, saveError = saveError, createdAt = 0, completedAt = null, attempt = 1,
            group = JobGroup("g", "Mix", "https://youtube.com/playlist?list=x"))
        val summary = GroupSummary.of(listOf(
            job("1", JobStatus.COMPLETED, 100f), job("2", JobStatus.COMPLETED, 100f), job("3", JobStatus.DOWNLOADING, 50f),
            job("4", JobStatus.FAILED), job("5", JobStatus.QUEUED), job("6", JobStatus.PAUSED), job("7", JobStatus.CANCELLED),
            job("8", JobStatus.COMPLETED, 100f, saveError = "x"),
        ))
        assertEquals(8, summary.total); assertEquals(2, summary.saved); assertEquals(2, summary.failed); assertEquals(1, summary.cancelled)
        assertEquals(2, summary.active); assertEquals(1, summary.paused); assertFalse(summary.done)
        assertEquals("2 of 8 done · 2 failed · 1 paused", summary.text())
        assertEquals("2 of 8 saved, 2 failed, 1 cancelled", summary.finalText())
        assertTrue(summary.percent in 1..99)
    }

    private val rateLimited = "ERROR: [youtube] fcXJMqDW8aI: Video unavailable. This content isn't available, try again later."
    private val botCheck = "ERROR: [youtube] abc: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or --cookies for the authentication."

    @Test fun youtubeThrottlingIsClassifiedAndNeverCalledRemovedOrOutdated() {
        listOf(
            rateLimited,
            "ERROR: [youtube:tab] The current session has been rate-limited by YouTube for up to an hour. It is recommended to use `-t sleep` between requests",
            "ERROR: unable to download video data: HTTP Error 429: Too Many Requests",
        ).forEach {
            assertEquals(it, Kind.RATE_LIMITED, ErrorMessages.classify(it))
            assertEquals(ErrorMessages.RATE_LIMIT_MESSAGE, ErrorMessages.friendly(it))
            assertTrue(ErrorMessages.throttled(it))
            assertFalse("no quick in-place retry", ErrorMessages.transient(it))
        }
        assertEquals(Kind.BOT_CHECK, ErrorMessages.classify(botCheck))
        assertEquals(ErrorMessages.BOT_MESSAGE, ErrorMessages.friendly(botCheck))
        assertTrue(ErrorMessages.BOT_MESSAGE.contains("Settings \u2192 Downloads \u2192 Website sign-in"))
        assertTrue(ErrorMessages.friendly(rateLimited, exhausted = true).contains("Wait a few minutes"))
    }

    @Test fun rateLimitBacksOffSixtyOneEightyThreeHundredSeconds() {
        assertEquals(listOf(60_000L, 180_000L, 300_000L), (1..3).map(RetryPolicy::rateLimitBackoffMs))
        assertEquals(RetryPolicy.Decision(1, 60_000, false, rateLimited = true), RetryPolicy.next(0, rateLimited))
        assertEquals(RetryPolicy.Decision(2, 180_000, false, rateLimited = true), RetryPolicy.next(1, rateLimited))
        assertEquals(RetryPolicy.Decision(3, 300_000, false, rateLimited = true), RetryPolicy.next(2, rateLimited))
        assertNull(RetryPolicy.next(3, rateLimited))
    }

    @Test fun botCheckRetriesOnceAfterTwoMinutes() {
        assertEquals(RetryPolicy.Decision(1, 120_000, false, rateLimited = true), RetryPolicy.next(0, botCheck))
        assertNull(RetryPolicy.next(1, botCheck))
    }

    @Test fun genericUnavailableIsRetryableButNamedRemovalsArePermanent() {
        val generic = "ERROR: [youtube] abc: Video unavailable"
        assertEquals(Kind.RETRYABLE, ErrorMessages.classify(generic))
        assertEquals("YouTube didn't return this video right now. Retrying may help.", ErrorMessages.friendly(generic))
        assertNotNull(RetryPolicy.next(0, generic))
        listOf(
            "ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader",
            "ERROR: [youtube] abc: Video unavailable. This video is no longer available because the YouTube account associated with this video has been terminated.",
            "ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video",
            "ERROR: [youtube] abc: This video has been removed for violating YouTube's Terms of Service",
        ).forEach {
            assertEquals(it, Kind.PERMANENT, ErrorMessages.classify(it))
            assertNull(RetryPolicy.next(0, it))
        }
        assertEquals("This media was removed or is unavailable.", ErrorMessages.friendly("ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader"))
    }

    @Test fun outdatedEngineIsOnlyBlamedForExtractorFailures() {
        val outdated = "The site rejected the download engine (it may be outdated). Update the engine in Settings, then retry."
        listOf("ERROR: [youtube] abc: Unable to extract initial data", "WARNING: nsig extraction failed: Some formats may be missing", "ERROR: [youtube] Precondition check failed: HTTP Error 400")
            .forEach { assertEquals(it, outdated, ErrorMessages.friendly(it)) }
        listOf(rateLimited, botCheck, "ERROR: [youtube] abc: Video unavailable", "ERROR: unable to download video data: HTTP Error 403: Forbidden")
            .forEach { assertNotEquals(it, outdated, ErrorMessages.friendly(it)) }
    }

    @Test fun errorDetailKeepsLinesButScrubsToolNames() {
        val detail = ErrorMessages.detail("ERROR: [youtube] x: failed\n\nyt-dlp exited; ffmpeg crashed\nline3")
        // The core scrubs each line like the desktop: log prefixes dropped, tool names replaced by their plain names.
        assertEquals("x: failed\nThe download engine exited; the media processor crashed\nline3", detail)
        assertTrue(ErrorMessages.detail("a".repeat(9000)).length <= 4000)
    }
}
