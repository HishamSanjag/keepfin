package app.keepfin.core.sync

internal actual fun scaleEnv(): String? {
    val value: dynamic = js("globalThis.process.env['KEEPFIN_SCALE']")
    return if (value == undefined || value == null) null else value as String
}
