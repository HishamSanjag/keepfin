package app.keepfin.core.sync

const val HLC_MAX_WALL = 9_999_999_999_999L
const val HLC_MAX_COUNTER = 999_999

/**
 * Thrown when a clock would pass its largest wall time. It is a dedicated exception type (never a silent wrap):
 * the writer must stop issuing ops and ask for attention.
 */
class HlcExhausted(message: String) : IllegalStateException(message)

/**
 * Hybrid logical clock: picks the winner among writes to one register. Never says what is missing.
 * Ranges: wall 0..9,999,999,999,999; counter 0..999,999.
 */
data class Hlc(val wall: Long, val counter: Int, val actor: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        if (wall != other.wall) return wall.compareTo(other.wall)
        if (counter != other.counter) return counter.compareTo(other.counter)
        return actor.compareTo(other.actor)
    }

    /** `<wall, 13 digits>.<counter, 6 digits>.<actor>` */
    fun wire(): String = wall.toString().padStart(13, '0') + "." + counter.toString().padStart(6, '0') + "." + actor

    companion object {
        private fun make(wall: Long, counter: Int, actor: String): Hlc {
            var w = wall
            var c = counter
            if (c > HLC_MAX_COUNTER) {
                w += 1
                c = 0
            }
            if (w > HLC_MAX_WALL) throw HlcExhausted("the clock passed its largest wall time")
            return Hlc(w, c, actor)
        }

        /** The next clock for a local write. A negative [nowMs] counts as 0. Throws [HlcExhausted] past the wall limit. */
        fun tick(last: Hlc?, nowMs: Long, actor: String): Hlc {
            val now = maxOf(nowMs, 0L)
            if (last == null) return make(now, 0, actor)
            val wall = maxOf(now, last.wall)
            val counter = if (wall == last.wall) last.counter + 1 else 0
            return make(wall, counter, actor)
        }

        /** The clock after admitting [remote]: strictly after both [last] and [remote]. */
        fun receive(last: Hlc?, remote: Hlc, nowMs: Long, actor: String): Hlc {
            val wall = maxOf(maxOf(nowMs, 0L), last?.wall ?: 0L, remote.wall)
            var counter = -1
            if (last != null && last.wall == wall) counter = maxOf(counter, last.counter)
            if (remote.wall == wall) counter = maxOf(counter, remote.counter)
            return make(wall, counter + 1, actor)
        }

        /** Reads the wire form; null unless [text] is exactly what [wire] would produce. */
        fun parse(text: String): Hlc? {
            if (text.length < 22 || text[13] != '.' || text[20] != '.') return null
            val w = text.substring(0, 13)
            val c = text.substring(14, 20)
            if (!w.all { it in '0'..'9' } || !c.all { it in '0'..'9' }) return null
            val hlc = Hlc(w.toLong(), c.toInt(), text.substring(21))
            return if (hlc.wire() == text) hlc else null
        }
    }
}
