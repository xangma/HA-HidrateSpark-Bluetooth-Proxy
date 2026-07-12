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
            val writer = validatedWriter()
            val discovered = BottleLocator(appContext).resolve(bottle)
            configStore.updateLastKnownAddress(discovered.address)

            SipStore(appContext).use { store ->
                BottleGattClient(appContext, bottle, store).open(discovered.address).use { connection ->
                    syncConnectedLocked(bottle, connection, store, writer)
                }
            }
        }
    }

    /** Drains an already-subscribed GATT session after a queue-count notification. */
    suspend fun syncConnected(
        bottle: BottleSettings,
        connection: BottleGattClient.Connection,
    ): SyncSummary = withContext(Dispatchers.IO) {
        SYNC_MUTEX.withLock {
            val writer = validatedWriter()
            SipStore(appContext).use { store ->
                syncConnectedLocked(bottle, connection, store, writer)
            }
        }
    }

    private suspend fun syncConnectedLocked(
        bottle: BottleSettings,
        connection: BottleGattClient.Connection,
        store: SipStore,
        writer: HealthConnectWriter,
    ): SyncSummary {
        // Finish a transaction interrupted after local persistence but before
        // the previous Health Connect write, then drain the bottle queue.
        var written = writePending(store, writer, bottle)
        val collected = connection.drain()
        written += writePending(store, writer, bottle)
        return SyncSummary(collectedSips = collected, writtenSips = written)
    }

    private suspend fun validatedWriter(): HealthConnectWriter {
        when (HealthConnectWriter.sdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                throw IllegalStateException("Install or update Health Connect")
            else -> throw IllegalStateException("Health Connect is unavailable on this device")
        }
        val writer = HealthConnectWriter.create(appContext)
        if (!writer.hasPermission()) throw HealthPermissionRequiredException()
        return writer
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
