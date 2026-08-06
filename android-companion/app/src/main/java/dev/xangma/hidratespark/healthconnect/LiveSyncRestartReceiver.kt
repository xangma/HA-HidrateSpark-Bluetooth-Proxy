package dev.xangma.hidratespark.healthconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

internal object LiveSyncRestartPolicy {
    private val restartActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
    )

    fun shouldRestart(action: String?, enabled: Boolean): Boolean =
        enabled && action in restartActions
}

class LiveSyncRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = ConfigStore(context.applicationContext).isLiveSyncEnabled()
        if (!LiveSyncRestartPolicy.shouldRestart(intent.action, enabled)) return

        try {
            Log.i(TAG, "Restoring live sync after ${intent.action}")
            LiveBottleSyncService.start(context)
        } catch (error: RuntimeException) {
            // Keep the enabled flag set so a later lifecycle event or manual
            // app launch can restore the service.
            Log.e(TAG, "Could not restore live sync after ${intent.action}", error)
        }
    }

    private companion object {
        const val TAG = "HidrateSparkLive"
    }
}
