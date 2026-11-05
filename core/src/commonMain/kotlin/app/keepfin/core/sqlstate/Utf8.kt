package app.keepfin.core.sqlstate

/**
 * The UTF-8 bytes of [text]. A part body is tens of megabytes of text; Kotlin/JS's own encoder walks it one character at
 * a time (seconds), while the JavaScript runtime encodes it natively. Malformed surrogates become U+FFFD on both.
 */
internal expect fun utf8Bytes(text: String): ByteArray
