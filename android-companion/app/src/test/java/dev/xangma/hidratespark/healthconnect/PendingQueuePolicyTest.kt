package dev.xangma.hidratespark.healthconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueuePolicyTest {
    @Test
    fun drainsInitialNonZeroAndSubsequentIncreasesOnly() {
        val policy = PendingQueuePolicy()

        assertTrue(policy.shouldDrain(6))
        assertFalse(policy.shouldDrain(6))
        assertFalse(policy.shouldDrain(5))
        assertTrue(policy.shouldDrain(7))
    }

    @Test
    fun acknowledgementsDoNotRecursivelyDrain() {
        val policy = PendingQueuePolicy()

        assertTrue(policy.shouldDrain(3))
        policy.onDrainCompleted()
        assertFalse(policy.shouldDrain(0))
        assertFalse(policy.shouldDrain(0))
        assertTrue(policy.shouldDrain(1))
    }

    @Test
    fun failedSyncRetriesTheSameHeartbeat() {
        val policy = PendingQueuePolicy()

        assertTrue(policy.shouldDrain(2))
        policy.onSyncFailed()
        assertTrue(policy.shouldDrain(2))
    }
}
