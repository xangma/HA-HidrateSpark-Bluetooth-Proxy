package dev.xangma.hidratespark.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SyncEngine(
    context: Context,
    private val configStore: ConfigStore = ConfigStore(context),
) {
    private val appContext = context.applicationContext

    suspend fun sync(): SyncSummary = withContext(Dispatchers.IO) {
        SYNC_MUTEX.withLock {
            val bottle = configStore.loadBottle()
                ?: throw IllegalStateException("Choose a HidrateSpark bottle first")
            when (HealthConnectWriter.sdkStatus(appContext)) {
                HealthConnectClient.SDK_AVAILABLE -> Unit
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    throw IllegalStateException("Install or update Health Connect")
                else -> throw IllegalStateException("Health Connect is unavailable on this device")
            }
            val writer = HealthConnectWriter.create(appContext)
            if (!writer.hasPermission()) throw HealthPermissionRequiredException()

            SipStore(appContext).use { store ->
                // Finish a transaction interrupted after local persistence but
                // before the previous Health Connect write, then collect more.
                var written = writePending(store, writer, bottle)
                val collected = BottleGattClient(appContext, bottle, store).collectBufferedSips()
                written += writePending(store, writer, bottle)
                SyncSummary(collectedSips = collected, writtenSips = written)
            }
        }
    }

    private suspend fun writePending(
        store: SipStore,
        writer: HealthConnectWriter,
        bottle: BottleSettings,
    ): Int {
        var written = 0
        while (true) {
            val sips = store.pending()
            if (sips.isEmpty()) return written
            writer.write(bottle, sips)
            // Mark only after Health Connect accepts the full batch. A crash
            // here is safe because clientRecordId updates the same records.
            store.markSynced(sips)
            written += sips.size
        }
    }

    companion object {
        private val SYNC_MUTEX = Mutex()
    }
}
