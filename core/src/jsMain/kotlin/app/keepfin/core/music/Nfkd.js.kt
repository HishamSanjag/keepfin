package app.keepfin.core.music

internal actual fun nfkd(value: String): String = value.asDynamic().normalize("NFKD") as String
