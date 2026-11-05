package app.keepfin.core.sync

internal actual fun scaleEnv(): String? = System.getenv("KEEPFIN_SCALE")
