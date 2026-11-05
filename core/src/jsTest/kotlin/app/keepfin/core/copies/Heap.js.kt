package app.keepfin.core.copies

internal actual fun usedHeapBytes(): Long {
    val v: dynamic = js("(typeof globalThis.gc === 'function' ? (globalThis.gc(), 0) : 0, globalThis.process.memoryUsage().heapUsed)")
    return (v as Number).toLong()
}
