package dev.xangma.hidratespark.healthconnect

/** Converts frequent queue-count heartbeats into at most one drain per increase. */
class PendingQueuePolicy {
    private var lastPending: Int? = null

    fun shouldDrain(pending: Int): Boolean {
        require(pending >= 0) { "Bottle queue count cannot be negative" }
        val previous = lastPending
        lastPending = pending
        return pending > 0 && (previous == null || pending > previous)
    }

    fun onDrainCompleted() {
        // Acknowledgements reduce the count; they must never start another drain.
        lastPending = 0
    }

    fun onSyncFailed() {
        // Retry the same non-zero heartbeat when the local/Health Connect work failed.
        lastPending = null
    }
}
