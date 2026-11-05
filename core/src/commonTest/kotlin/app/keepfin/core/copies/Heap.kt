package app.keepfin.core.copies

/** Heap in use after a collection attempt, in bytes (a measurement aid for the scale test). */
internal expect fun usedHeapBytes(): Long
