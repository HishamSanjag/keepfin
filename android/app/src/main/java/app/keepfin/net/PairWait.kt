package app.keepfin.net

/**
 * Waiting for the other side (network spec, pairing): the person pressed Pair on a device whose "Add a device" is not open yet. It
 * answers `not-pairing`; instead of failing, the start is retried every [retryMs] until it answers or [waitMs] have passed. Time and
 * sleeping are passed in so tests do not wait two minutes. Free of Android and of the network.
 */
class PairWait(
    private val retryMs: Long = 2500, // under the 30 requests a minute a stranger may send
    private val waitMs: Long = PAIRING_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Waits up to the given milliseconds; returns true when the wait was cut short because the person cancelled. */
    private val sleep: (Long) -> Boolean,
) {
    /** Runs [attempt] until it does not answer `not-pairing`. [waiting] is called once, when the first such answer arrives. */
    fun <T> run(peerName: String, waiting: () -> Unit, cancelled: () -> Boolean, attempt: () -> T): T {
        val deadline = now() + waitMs
        var announced = false
        while (true) {
            if (cancelled()) throw AskError("Pairing was cancelled.", CANCELLED)
            try {
                return attempt()
            } catch (e: AskError) {
                if (e.code != "not-pairing") throw e
            }
            if (!announced) {
                announced = true
                waiting()
            }
            if (now() >= deadline) throw AskError("Add a device was not opened on $peerName in time. Try again.", "peer-timeout")
            if (sleep(retryMs)) throw AskError("Pairing was cancelled.", CANCELLED)
        }
    }

    companion object {
        const val CANCELLED = "cancelled"
        const val FALLBACK_NAME = "the other device"
    }
}
