@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.errors

// The JavaScript surface of the errors core (see gui/shared/core/errors.ts): wire strings instead of enums, arrays, nullable values.

/** `errorClass`, `retry` and `action` are wire strings (fixtures/engine/errors/SCHEMA.md). */
@JsExport
class JsAnalysis(
    val errorClass: String,
    val retry: String,
    val action: String,
    val message: String,
    val recognized: Boolean,
    val detail: String?,
)

@JsExport
class JsRetryDecision(
    val retry: Int,
    val delayMs: Double,
    val engineRelated: Boolean,
    val rateLimited: Boolean,
    val refreshEngine: Boolean,
)

@JsExport
class JsErrorConstants(
    val maxRetries: Int,
    val backoffMs: Array<Double>,
    val rateLimitBackoffMs: Array<Double>,
    val botCheckBackoffMs: Array<Double>,
    val unavailableMessage: String,
    val restrictedMusicMessage: String,
    val restrictedMessage: String,
)

@JsExport
fun errorsAnalyze(text: String, platform: String, exhausted: Boolean): JsAnalysis {
    val analysis = Errors.analyze(text, Platform.fromWire(platform), exhausted)
    return JsAnalysis(
        analysis.errorClass.wire, analysis.retry.wire, analysis.action.wire, analysis.message, analysis.recognized, analysis.detail,
    )
}

@JsExport
fun errorsScrub(text: String, platform: String): String = Errors.scrub(text, Platform.fromWire(platform))

@JsExport
fun errorsDetail(text: String, limit: Int, platform: String): String = Errors.detail(text, limit, Platform.fromWire(platform))

@JsExport
fun errorsLastErrorLine(text: String): String? = Errors.lastErrorLine(text)

@JsExport
fun errorsSuggestsGallery(text: String): Boolean = Errors.suggestsGallery(text)

@JsExport
fun errorsAllMessages(platform: String): Array<String> = Errors.allMessages(Platform.fromWire(platform)).toTypedArray()

@JsExport
fun errorsRetryNext(retriesUsed: Int, text: String, crashed: Boolean, platform: String): JsRetryDecision? =
    Retry.next(retriesUsed, text, crashed, Platform.fromWire(platform))?.let {
        JsRetryDecision(it.retry, it.delayMs.toDouble(), it.engineRelated, it.rateLimited, it.refreshEngine)
    }

private fun List<Long>.toJs(): Array<Double> = map { it.toDouble() }.toTypedArray()

@JsExport
fun errorsConstants(): JsErrorConstants = JsErrorConstants(
    Retry.MAX,
    Retry.backoffMs.toJs(),
    Retry.rateLimitBackoffMs.toJs(),
    Retry.botCheckBackoffMs.toJs(),
    Messages.UNAVAILABLE,
    Messages.RESTRICTED_MUSIC,
    Messages.RESTRICTED_OTHER,
)
