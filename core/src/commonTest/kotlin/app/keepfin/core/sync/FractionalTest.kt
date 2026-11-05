package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class FractionalTest {
    private val z27 = "z".repeat(27)

    @Test
    fun fixedVectorsFromTheReferenceImplementation() {
        val cases = listOf(
            Triple(null, null, "a0"),
            Triple("a0", null, "a1"),
            Triple(null, "a0", "Zz"),
            Triple("a0", "a1", "a0V"),
            Triple("a0", "a0V", "a0G"),
            Triple(null, "a0V", "a0"),
            Triple("a0V", null, "a1"),
            Triple("a1", "a2", "a1V"),
            Triple("Zz", "a0", "ZzV"),
            Triple("a0", "a0G", "a08"),
            Triple("a0G", "a0V", "a0O"),
            Triple("az", null, "b00"),
            Triple("b00", null, "b01"),
            Triple("Zy", "Zz", "ZyV"),
            Triple(null, "Zz", "Zy"),
            Triple("a0V", "a1", "a0l"),
            Triple(z27, null, z27 + "V"),
        )
        for ((a, b, expected) in cases) assertEquals(expected, Fractional.keyBetween(a, b), "keyBetween($a, $b)")
    }

    @Test
    fun invalidAndMisorderedInputFails() {
        assertFailsWith<IllegalArgumentException> { Fractional.keyBetween("a0", "a0") }
        assertFailsWith<IllegalArgumentException> { Fractional.keyBetween("a1", "a0") }
        assertFailsWith<IllegalArgumentException> { Fractional.keyBetween("a00", null) } // fraction ends in 0
        assertFailsWith<IllegalArgumentException> { Fractional.keyBetween("a0", "a10") }
        for (bad in listOf("", "a00", "a10", "a0 ", "A" + "0".repeat(26), "!", "a", "Z", "a-")) {
            assertTrue(!Fractional.isValidKey(bad), "'$bad' must be invalid")
        }
        for (good in listOf("a0", "a1", "Zz", "a0V", "b00", "b01", z27, z27 + "V", "A" + "0".repeat(25) + "1")) {
            assertTrue(Fractional.isValidKey(good), "'$good' must be valid")
        }
    }

    @Test
    fun randomPairsGetAKeyStrictlyBetween() {
        val rnd = Random(77)
        val keys = ArrayList<String>()
        keys.add(Fractional.keyBetween(null, null))
        repeat(400) {
            val sorted = keys.sorted().distinct()
            val pos = rnd.nextInt(sorted.size + 1)
            val a = if (pos == 0) null else sorted[pos - 1]
            val b = if (pos == sorted.size) null else sorted[pos]
            val k = Fractional.keyBetween(a, b)
            assertTrue(Fractional.isValidKey(k), "valid $k")
            if (a != null) assertTrue(a < k, "$a < $k")
            if (b != null) assertTrue(k < b, "$k < $b")
            keys.add(k)
        }
    }

    @Test
    fun tenThousandRandomInsertsKeepTheOrder() {
        val rnd = Random(2024)
        val order = ArrayList<String>()
        repeat(10_000) {
            val pos = rnd.nextInt(order.size + 1)
            val a = if (pos == 0) null else order[pos - 1]
            val b = if (pos == order.size) null else order[pos]
            order.add(pos, Fractional.keyBetween(a, b))
        }
        for (i in 1 until order.size) {
            if (!(order[i - 1] < order[i])) fail("order broken at $i: ${order[i - 1]} !< ${order[i]}")
        }
        assertEquals(order.size, order.toSet().size, "keys are distinct")
    }

    @Test
    fun repeatedAppendsStayShort() {
        var key: String? = null
        var longest = 0
        repeat(10_000) {
            val next = Fractional.keyBetween(key, null)
            val previous = key
            if (previous != null) assertTrue(previous < next)
            key = next
            longest = maxOf(longest, next.length)
        }
        assertTrue(longest < 64, "longest append key was $longest")
        var first: String? = null
        repeat(2000) { first = Fractional.keyBetween(null, first) }
        assertTrue(first!!.length < 64)
    }

    @Test
    fun tiedKeysAreOrderedByEntryId() {
        val ties = listOf("e3" to "a1", "e1" to "a1", "e2" to "a1").sortedWith(compareBy({ it.second }, { it.first }))
        assertEquals(listOf("e1", "e2", "e3"), ties.map { it.first })
        // appending a digit to a tied key is wrong: it can sort after a later key
        assertTrue("a1V" < "a1" + "z")
        val after = Fractional.keyBetween("a1", "a2")
        assertTrue("a1" < after && after < "a2")
    }

    @Test
    fun placingInsideATiedGroupMovesTheFollowers() {
        // entries t1 < t2 < t3 (by id) share the key a1; the next distinct key is a2. Insert N between t1 and t2.
        val placement = Fractional.placeInTie("a1", "a2", listOf("t2", "t3"))
        val keys = listOf(placement.newKey) + placement.moves.map { it.key }
        assertEquals(listOf("t2", "t3"), placement.moves.map { it.entry })
        for (k in keys) assertTrue("a1" < k && k < "a2", "$k is inside the gap")
        for (i in 1 until keys.size) assertTrue(keys[i - 1] < keys[i], "keys rise")
        // apply it and sort by (key, entry id), as readers do
        val applied = mutableMapOf("t1" to "a1", "t2" to "a1", "t3" to "a1", "N" to placement.newKey)
        for (m in placement.moves) applied[m.entry] = m.key
        val order = applied.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
        assertEquals(listOf("t1", "N", "t2", "t3"), order)
        // with no followers it is just "after the whole group"; an open upper bound works too
        assertEquals(Fractional.keyBetween("a1", "a2"), Fractional.placeInTie("a1", "a2", emptyList()).newKey)
        val open = Fractional.placeInTie("a1", null, listOf("t2"))
        assertTrue("a1" < open.newKey && open.newKey < open.moves[0].key)
    }
}
