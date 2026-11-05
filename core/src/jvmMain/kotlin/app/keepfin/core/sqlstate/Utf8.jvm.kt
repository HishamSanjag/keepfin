package app.keepfin.core.sqlstate

internal actual fun utf8Bytes(text: String): ByteArray = text.encodeToByteArray()
