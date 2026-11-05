package app.keepfin.core.sync

// Fractional positions: base-62 order keys (0-9A-Za-z, ordered by code unit). An integer part (its length is
// given by the first letter) plus a fraction. This follows the algorithm of the public-domain
// "fractional-indexing" library, written from its description.

object Fractional {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'
    private val SMALLEST_INTEGER = "A" + "0".repeat(26)

    private fun digitValue(c: Char): Int = DIGITS.indexOf(c)

    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw IllegalArgumentException("invalid order key head: $head")
    }

    private fun integerPart(key: String): String {
        val len = integerLength(key[0])
        if (len > key.length) throw IllegalArgumentException("invalid order key: $key")
        return key.substring(0, len)
    }

    /** True when [key] is a well-formed order key. */
    fun isValidKey(key: String): Boolean {
        if (key.isEmpty() || key == SMALLEST_INTEGER) return false
        if (!key.all { digitValue(it) >= 0 }) return false
        val head = key[0]
        if (head !in 'a'..'z' && head !in 'A'..'Z') return false
        val len = integerLength(head)
        if (len > key.length) return false
        return key.length == len || key[key.length - 1] != ZERO
    }

    private fun validate(key: String) {
        if (!isValidKey(key)) throw IllegalArgumentException("invalid order key: $key")
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null && a >= b) throw IllegalArgumentException("$a >= $b")
        if (a.isNotEmpty() && a.last() == ZERO || b != null && b.isNotEmpty() && b.last() == ZERO) {
            throw IllegalArgumentException("trailing zero")
        }
        if (b != null) {
            var n = 0
            while (n < b.length && (if (n < a.length) a[n] else ZERO) == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.substring(minOf(n, a.length)), b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) digitValue(a[0]) else 0
        val digitB = if (b != null) digitValue(b[0]) else DIGITS.length
        if (digitB - digitA > 1) {
            return DIGITS[(digitA + digitB + 1) / 2].toString()
        }
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[digitA].toString() + midpoint(if (a.isEmpty()) "" else a.substring(1), null)
    }

    private fun increment(x: String): String? {
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var carry = true
        var i = digs.size - 1
        while (carry && i >= 0) {
            val d = digitValue(digs[i]) + 1
            if (d == DIGITS.length) {
                digs[i] = DIGITS[0]
            } else {
                digs[i] = DIGITS[d]
                carry = false
            }
            i--
        }
        if (carry) {
            if (head == 'Z') return "a" + DIGITS[0]
            if (head == 'z') return null
            val h = head + 1
            val rest = if (h > 'a') digs.concatToString() + DIGITS[0] else digs.concatToString().dropLast(1)
            return h.toString() + rest
        }
        return head.toString() + digs.concatToString()
    }

    private fun decrement(x: String): String? {
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var borrow = true
        var i = digs.size - 1
        while (borrow && i >= 0) {
            val d = digitValue(digs[i]) - 1
            if (d == -1) {
                digs[i] = DIGITS[DIGITS.length - 1]
            } else {
                digs[i] = DIGITS[d]
                borrow = false
            }
            i--
        }
        if (borrow) {
            if (head == 'a') return "Z" + DIGITS[DIGITS.length - 1]
            if (head == 'A') return null
            val h = head - 1
            val rest = if (h < 'Z') digs.concatToString() + DIGITS[DIGITS.length - 1] else digs.concatToString().dropLast(1)
            return h.toString() + rest
        }
        return head.toString() + digs.concatToString()
    }

    /**
     * A key strictly between [a] and [b]; null means open on that side. Requires `a < b` when both are given.
     * Throws [IllegalArgumentException] for malformed or misordered input (a programming error).
     */
    fun keyBetween(a: String?, b: String?): String {
        if (a != null) validate(a)
        if (b != null) validate(b)
        if (a != null && b != null && a >= b) throw IllegalArgumentException("$a >= $b")
        if (a == null) {
            if (b == null) return "a" + DIGITS[0]
            val ib = integerPart(b)
            val fb = b.substring(ib.length)
            if (ib == SMALLEST_INTEGER) return ib + midpoint("", fb)
            if (ib < b) return ib
            return decrement(ib) ?: throw IllegalArgumentException("cannot decrement any more")
        }
        if (b == null) {
            val ia = integerPart(a)
            val fa = a.substring(ia.length)
            val i = increment(ia)
            return i ?: (ia + midpoint(fa, null))
        }
        val ia = integerPart(a)
        val fa = a.substring(ia.length)
        val ib = integerPart(b)
        val fb = b.substring(ib.length)
        if (ia == ib) return ia + midpoint(fa, fb)
        val i = increment(ia) ?: throw IllegalArgumentException("cannot increment any more")
        if (i < b) return i
        return ia + midpoint(fa, null)
    }

    /**
     * Places a new entry inside a tied group. [tieKey] is the key the group shares, [next] the next key strictly
     * greater than it (or null), and [followers] the entry ids of the tied entries that must come after the new
     * one, in their observed order. The new entry gets the first of a rising run of keys between [tieKey] and
     * [next]; each follower gets one of the following keys and needs an `entry.move`. Entries of the group that
     * stay before the new one keep [tieKey] (they sort first, by entry id).
     */
    fun placeInTie(tieKey: String, next: String?, followers: List<String>): TiePlacement {
        var previous = keyBetween(tieKey, next)
        val newKey = previous
        val moves = ArrayList<EntryMove>(followers.size)
        for (entry in followers) {
            previous = keyBetween(previous, next)
            moves.add(EntryMove(entry, previous))
        }
        return TiePlacement(newKey, moves)
    }
}

/** An `entry.move` to issue: [entry] gets [key]. */
data class EntryMove(val entry: String, val key: String)

/** The key for the new entry plus the moves for the tied entries that follow it. */
data class TiePlacement(val newKey: String, val moves: List<EntryMove>)
