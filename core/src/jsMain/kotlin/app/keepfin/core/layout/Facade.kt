@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.layout

// The JavaScript surface of card sizes and page layouts (see gui/shared/core/layout.ts): saved JSON text in, JSON text out.

private fun page(wire: String) = Page.of(wire) ?: Page.Home

@JsExport
fun layoutCardSize(kind: String, pageWidth: Double, scale: Double): Double = CardSizes.size(CardKind.of(kind) ?: CardKind.Square, pageWidth, scale)

/** The saved layout of [pageWire] made whole; empty or unreadable text gives the defaults. */
@JsExport
fun layoutReadJson(pageWire: String, savedJson: String): String = LayoutJson.writeLayout(LayoutJson.readLayout(page(pageWire), savedJson))

@JsExport
fun layoutToggleJson(pageWire: String, savedJson: String, id: String): String =
    LayoutJson.writeLayout(PageLayouts.toggle(LayoutJson.readLayout(page(pageWire), savedJson), id))

@JsExport
fun layoutStyleJson(pageWire: String, savedJson: String, id: String, style: String): String {
    val layout = LayoutJson.readLayout(page(pageWire), savedJson)
    return LayoutJson.writeLayout(SectionStyle.of(style)?.let { PageLayouts.setStyle(layout, id, it) } ?: layout)
}

@JsExport
fun layoutMoveJson(pageWire: String, savedJson: String, id: String, target: String, after: Boolean): String =
    LayoutJson.writeLayout(PageLayouts.move(LayoutJson.readLayout(page(pageWire), savedJson), id, target, after))

@JsExport
fun layoutStepJson(pageWire: String, savedJson: String, id: String, delta: Int): String =
    LayoutJson.writeLayout(PageLayouts.step(LayoutJson.readLayout(page(pageWire), savedJson), id, delta))

@JsExport
fun layoutDefaultsJson(pageWire: String): String = LayoutJson.writeLayout(PageLayouts.defaults(page(pageWire)))

/** {scale, text}, clamped and defaulted. */
@JsExport
fun cardPrefsReadJson(savedJson: String): String = LayoutJson.writeCardPrefs(LayoutJson.readCardPrefs(savedJson))
