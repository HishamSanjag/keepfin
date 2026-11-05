package app.keepfin.core.sync

// Small immutable collections. A library of 200,000 items holds a handful of tiny maps and sets per item (its
// fields, its tags), and a general hash map costs hundreds of bytes even when it holds one entry. These keep the
// entries in two arrays and look up by scanning, which is faster than hashing at this size. They are ordinary
// Map and Set values: equality and hash codes follow the collection contracts, so a state holding them is equal to
// the same state held in hash maps.

/** Above this many entries a plain hash map is used again. */
private const val SMALL_LIMIT = 12

internal class ArrMap<K, V>(private val keyArray: Array<Any?>, private val valueArray: Array<Any?>, override val size: Int) : AbstractMap<K, V>() {
    @Suppress("UNCHECKED_CAST")
    override fun get(key: K): V? {
        for (i in 0 until size) if (keyArray[i] == key) return valueArray[i] as V
        return null
    }

    override fun containsKey(key: K): Boolean {
        for (i in 0 until size) if (keyArray[i] == key) return true
        return false
    }

    @Suppress("UNCHECKED_CAST")
    override val entries: Set<Map.Entry<K, V>>
        get() = object : AbstractSet<Map.Entry<K, V>>() {
            override val size: Int get() = this@ArrMap.size

            override fun iterator(): Iterator<Map.Entry<K, V>> = object : Iterator<Map.Entry<K, V>> {
                private var i = 0
                override fun hasNext(): Boolean = i < this@ArrMap.size
                override fun next(): Map.Entry<K, V> {
                    val k = keyArray[i] as K
                    val v = valueArray[i] as V
                    i++
                    return Entry(k, v)
                }
            }
        }

    private class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }
}

internal class ArrSet<T>(private val items: Array<Any?>, override val size: Int) : AbstractSet<T>() {
    override fun contains(element: T): Boolean {
        for (i in 0 until size) if (items[i] == element) return true
        return false
    }

    @Suppress("UNCHECKED_CAST")
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var i = 0
        override fun hasNext(): Boolean = i < size
        override fun next(): T = items[i++] as T
    }
}

/** [m] as a compact map when it is small (the same entries, in the same order), else [m] itself. */
internal fun <K, V> compactMap(m: Map<K, V>): Map<K, V> {
    val n = m.size
    if (n == 0) return emptyMap()
    if (n > SMALL_LIMIT || m is ArrMap<*, *>) return m
    val ks = arrayOfNulls<Any?>(n)
    val vs = arrayOfNulls<Any?>(n)
    var i = 0
    for ((k, v) in m) {
        ks[i] = k
        vs[i] = v
        i++
    }
    return ArrMap(ks, vs, n)
}

/** [s] as a compact set when it is small (the same elements, in the same order), else [s] itself. */
internal fun <T> compactSet(s: Set<T>): Set<T> {
    val n = s.size
    if (n == 0) return emptySet()
    if (n > SMALL_LIMIT || s is ArrSet<*>) return s
    val items = arrayOfNulls<Any?>(n)
    var i = 0
    for (x in s) items[i++] = x
    return ArrSet(items, n)
}

/** The set holding only [x]. */
internal fun <T> singleSet(x: T): Set<T> = ArrSet(arrayOf<Any?>(x), 1)

/** The union of two sets, reusing an operand when the other adds nothing. */
internal fun <T> unionSets(a: Set<T>, b: Set<T>): Set<T> {
    if (b.isEmpty()) return a
    if (a.isEmpty()) return b
    var missing = false
    for (x in b) if (x !in a) { missing = true; break }
    if (!missing) return a
    val out = LinkedHashSet<T>(a)
    out.addAll(b)
    return compactSet(out)
}

/** The map holding only [pair]. */
internal fun <K, V> singleMap(pair: Pair<K, V>): Map<K, V> = ArrMap(arrayOf<Any?>(pair.first), arrayOf<Any?>(pair.second), 1)
