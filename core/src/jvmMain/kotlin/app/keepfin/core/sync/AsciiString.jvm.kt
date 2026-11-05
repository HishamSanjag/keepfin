package app.keepfin.core.sync

internal actual fun asciiString(bytes: ByteArray, from: Int, to: Int): String = String(bytes, from, to - from, Charsets.ISO_8859_1)
