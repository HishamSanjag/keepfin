package app.keepfin

/**
 * Download records removed from the history, held for a short while so the Undo of "Removed from history" can bring them back as they
 * were. While a record is held nothing that belongs to it is deleted (its artwork, a paused download's partial files): the engine does
 * that when [expire] hands the record back and it has not been taken back. Saved media is never part of this.
 */
internal class RemovedHistory<T>(private val keepMs: Long = KEEP_MS, private val limit: Int = LIMIT) {
    private class Held<T>(val record: T, val at: Long)

    private val held = LinkedHashMap<String, Held<T>>()

    /** Holds [record] (removed at [now]); the oldest ones go first when more than the limit are held, and are returned to be cleaned up. */
    @Synchronized fun hold(id: String, record: T, now: Long): List<T> {
        held.remove(id)
        held[id] = Held(record, now)
        val over = ArrayList<T>()
        while (held.size > limit) {
            val eldest = held.entries.first()
            over += eldest.value.record
            held.remove(eldest.key)
        }
        return over
    }

    /** Takes the records of [ids] that are still held and not past their time, in the order of [ids]; the others are not returned. */
    @Synchronized fun take(ids: List<String>, now: Long): List<T> =
        ids.distinct().mapNotNull { id -> held[id]?.takeIf { now - it.at <= keepMs }?.also { held.remove(id) }?.record }

    /** The records held longer than the time allows; they are no longer held. */
    @Synchronized fun expire(now: Long): List<T> {
        val gone = held.filterValues { now - it.at > keepMs }
        gone.keys.forEach { held.remove(it) }
        return gone.values.map { it.record }
    }

    @Synchronized fun isHeld(id: String): Boolean = id in held

    companion object {
        /** How long Undo is offered: longer than a notice stays on screen. */
        const val KEEP_MS = 90_000L
        const val LIMIT = 1_000
    }
}
