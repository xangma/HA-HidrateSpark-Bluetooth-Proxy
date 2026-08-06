package dev.xangma.hidratespark.healthconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSyncRestartPolicyTest {
    @Test
    fun `enabled live sync restarts after boot or package replacement`() {
        assertTrue(
            LiveSyncRestartPolicy.shouldRestart(
                "android.intent.action.BOOT_COMPLETED",
                enabled = true,
            ),
        )
        assertTrue(
            LiveSyncRestartPolicy.shouldRestart(
                "android.intent.action.MY_PACKAGE_REPLACED",
                enabled = true,
            ),
        )
    }

    @Test
    fun `explicitly disabled live sync never restarts`() {
        assertFalse(
            LiveSyncRestartPolicy.shouldRestart(
                "android.intent.action.BOOT_COMPLETED",
                enabled = false,
            ),
        )
        assertFalse(
            LiveSyncRestartPolicy.shouldRestart(
                "android.intent.action.MY_PACKAGE_REPLACED",
                enabled = false,
            ),
        )
    }

    @Test
    fun `unrelated or missing broadcasts do not restart live sync`() {
        assertFalse(
            LiveSyncRestartPolicy.shouldRestart(
                "android.intent.action.TIME_SET",
                enabled = true,
            ),
        )
        assertFalse(LiveSyncRestartPolicy.shouldRestart(null, enabled = true))
    }
}
