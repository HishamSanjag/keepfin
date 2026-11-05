package app.keepfin.core.sync

/**
 * The string of `bytes[from, to)`, which must all be ASCII (below 0x80). On the JVM it is the usual decoder; on
 * JavaScript it is one native call instead of a loop that builds the string character by character.
 */
internal expect fun asciiString(bytes: ByteArray, from: Int, to: Int): String
