package app.keepfin.core.sync

internal actual fun asciiString(bytes: ByteArray, from: Int, to: Int): String {
    if (to - from <= 4096) {
        val view = bytes.asDynamic().subarray(from, to)
        return js("String.fromCharCode.apply(null, view)") as String
    }
    // long text goes in pieces (a call takes a limited number of arguments)
    var out = ""
    var i = from
    while (i < to) {
        val end = if (i + 4096 < to) i + 4096 else to
        val view = bytes.asDynamic().subarray(i, end)
        out += js("String.fromCharCode.apply(null, view)") as String
        i = end
    }
    return out
}
