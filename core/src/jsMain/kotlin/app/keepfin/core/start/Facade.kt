@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.start

import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.jObj

// The JavaScript surface of the start pages (see gui/shared/core/start.ts): page names as strings, the words as JSON.

private fun line(l: StartLine): Jv = jObj("title" to JStr(l.title), "body" to JStr(l.body))

@JsExport
fun startPages(phone: Boolean): Array<String> = Start.pages(phone).map { it.name }.toTypedArray()

@JsExport
fun startSkipTo(): String = Start.skipTo.name

@JsExport
fun startIntroduces(page: String): Boolean = Start.introduces(StartPage.valueOf(page))

@JsExport
fun startCanGoOn(page: String, folderReady: Boolean): Boolean = Start.canGoOn(StartPage.valueOf(page), folderReady)

@JsExport
fun startWordsJson(phone: Boolean): String {
    val w = Start.words(phone)
    val texts = mapOf(
        "welcomeEyebrow" to w.welcomeEyebrow, "welcomeTitle" to w.welcomeTitle, "welcomeBody" to w.welcomeBody,
        "sitesTitle" to w.sitesTitle, "sitesMore" to w.sitesMore, "musicLinks" to w.musicLinks,
        "howEyebrow" to w.howEyebrow, "howTitle" to w.howTitle,
        "modeEyebrow" to w.modeEyebrow, "modeTitle" to w.modeTitle, "recommended" to w.recommended, "modeLater" to w.modeLater,
        "folderEyebrow" to w.folderEyebrow, "folderTitle" to w.folderTitle, "folderHint" to w.folderHint,
        "haveFolder" to w.haveFolder, "advanced" to w.advanced, "advancedHint" to w.advancedHint, "folderLater" to w.folderLater,
        "permissionsEyebrow" to w.permissionsEyebrow, "permissionsTitle" to w.permissionsTitle,
        "grant" to w.grant, "granted" to w.granted, "permissionsLater" to w.permissionsLater,
        "next" to w.next, "back" to w.back, "skipIntro" to w.skipIntro, "start" to w.start,
    ).mapValues { JStr(it.value) as Jv }
    val lines = mapOf(
        "features" to JArr(w.features.map(::line)), "steps" to JArr(w.steps.map(::line)), "signIn" to line(w.signIn),
        "sites" to JArr(w.sites.map { JStr(it) }),
        "complete" to line(w.complete), "downloads" to line(w.downloads), "media" to line(w.media), "notifications" to line(w.notifications),
    )
    return encodeCanonical(app.keepfin.core.sync.JObj(texts + lines))
}
