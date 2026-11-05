package app.keepfin.core.copies

internal actual fun usedHeapBytes(): Long {
    val rt = Runtime.getRuntime()
    repeat(3) { System.gc() }
    return rt.totalMemory() - rt.freeMemory()
}
