package app.keepfin.core.providers

internal actual fun nfkc(value: String): String = value.asDynamic().normalize("NFKC") as String
