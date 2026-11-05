package app.keepfin.core

/** 1,234 the way both apps print counts. */
internal fun groupDigits(n: Long): String {
    val digits = (if (n < 0) -n else n).toString()
    val out = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return (if (n < 0) "-" else "") + out
}
