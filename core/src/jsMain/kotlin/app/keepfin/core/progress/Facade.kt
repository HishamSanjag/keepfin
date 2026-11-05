@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.progress

// The JavaScript surface of the progress core (see gui/shared/core/progress.ts). The parser is stateful and
// runs for every output line, so `accept` returns a small number and the caller reads the snapshot only when
// something changed.

@JsExport
class JsProgressSnapshot(
    val percent: Double,
    val downloadedBytes: Double?,
    val totalBytes: Double?,
    val speed: Double?,
    val eta: Double?,
    val phase: String?,
    val phaseDetail: String?,
    val processing: Boolean,
    val sawTransfer: Boolean,
    val usedAria2c: Boolean,
)

private fun platformOf(name: String) = if (name == "android") ProgressPlatform.ANDROID else ProgressPlatform.DESKTOP

/**
 * @param platform "desktop" or "android".
 * @param expectedSize the inspected size of the whole job, or null.
 * @param streamIds format ids with inspected sizes, parallel to [streamBytes].
 */
@JsExport
class JsProgressParser(
    platform: String,
    expectedSize: Double?,
    streamIds: Array<String>,
    streamBytes: DoubleArray,
) {
    private val parser = ProgressParser(
        platformOf(platform),
        expectedSize,
        HashMap<String, Double>().also { sizes -> streamIds.forEachIndexed { i, id -> sizes[id] = streamBytes[i] } },
    )

    /** 0 = nothing changed, 1 = transfer numbers changed, 2 = the processing phase changed. */
    fun accept(line: String): Int = parser.accept(line).ordinal

    fun snapshot(): JsProgressSnapshot {
        val s = parser.snapshot
        return JsProgressSnapshot(
            s.percent, s.downloadedBytes, s.totalBytes, s.speed, s.eta, s.phase, s.phaseDetail, s.processing, s.sawTransfer, s.usedAria2c,
        )
    }
}

/** The progress line prefix of a platform's engine template. */
@JsExport
fun progressPrefix(platform: String): String = ProgressWire.of(platformOf(platform)).prefix
