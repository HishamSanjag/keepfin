@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.versions

// The JavaScript surface of the versions core (see gui/shared/core/versions.ts): wire strings in, arrays and nullable strings out.

@JsExport
fun releasesIsValid(version: String): Boolean = Releases.isValid(version)

@JsExport
fun releasesIsPrerelease(version: String): Boolean = Releases.isPrerelease(version)

/** -1, 0 or 1. */
@JsExport
fun releasesCompare(a: String, b: String): Int = Releases.compare(a, b)

/** `channel` is "stable" or "beta"; the result is the offered entry of `versions`, or null. */
@JsExport
fun releasesPick(versions: Array<String>, channel: String, current: String): String? =
    Releases.pick(versions.toList(), Channel.of(channel), current)

/** `platform` is "windows" or "android". */
@JsExport
fun releasesHighlights(notes: String?, platform: String, limit: Int): Array<String> =
    Releases.highlights(notes, Platform.of(platform), limit).toTypedArray()
