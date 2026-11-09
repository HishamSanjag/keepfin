package app.keepfin.core.copies

internal actual fun nfc(value: String): String = value.asDynamic().normalize("NFC") as String
