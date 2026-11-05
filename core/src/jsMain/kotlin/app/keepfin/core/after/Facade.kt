@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.after

// The JavaScript surface of the after-download plan (see gui/shared/core/after.ts): plain values in, an array of plain steps out.

@JsExport
class JsAfterStep(val kind: String, val onFailure: String, val fallback: Boolean)

@JsExport
fun afterPlan(audio: Boolean, hasFile: Boolean, existing: Boolean, video: String, playlist: Boolean, lyrics: Boolean, library: String): Array<JsAfterStep> =
    AfterDownload.plan(Facts(audio, hasFile, existing, video, playlist, lyrics, library)).map { JsAfterStep(it.kind, it.onFailure, it.fallback) }.toTypedArray()
