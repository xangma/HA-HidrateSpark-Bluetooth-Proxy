package dev.xangma.hidratespark.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SyncEngine(
    context: Context,
    private val configStore: ConfigStore = ConfigStore(context),
) {
    private val appContext = context.applicationContext

    suspend fun sync(): SyncSummary = withContext(Dispatchers.IO) {
        val settings = configStore.loadConnection()
            ?: throw IllegalStateException("Save the Home Assistant connection first")
        when (HealthConnectWriter.sdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                throw IllegalStateException("Install or update Health Connect")
            else -> throw IllegalStateException("Health Connect is unavailable on this device")
        }

        val writer = HealthConnectWriter.create(appContext)
        if (!writer.hasPermission()) throw HealthPermissionRequiredException()

        val homeAssistant = HomeAssistantClient(settings)
        val bottles = homeAssistant.fetchBottles()
        var synced = 0
        var retentionGaps = 0
        for (bottle in bottles) {
            var cursor = configStore.cursor(
                settings.baseUrl,
                bottle.entryId,
                bottle.journalId,
            )
            do {
                val page = homeAssistant.fetchSips(bottle.entryId, cursor)
                if (page.journalId != bottle.journalId) {
                    throw IOException("Home Assistant sip journal changed; retry synchronization")
                }
                if (page.nextAfter < cursor || (page.hasMore && page.nextAfter == cursor)) {
                    throw IOException("Home Assistant returned a non-advancing sync cursor")
                }
                if (page.truncated) retentionGaps += 1
                writer.write(bottle, page.sips)

                // Advance only after Health Connect accepted the full page.
                // Retrying a crash here is safe because clientRecordId upserts.
                if (page.nextAfter > cursor) {
                    configStore.saveCursor(
                        settings.baseUrl,
                        bottle.entryId,
                        bottle.journalId,
                        page.nextAfter,
                    )
                    cursor = page.nextAfter
                }
                synced += page.sips.size
            } while (page.hasMore)
        }
        SyncSummary(
            bottles = bottles.size,
            sips = synced,
            retentionGaps = retentionGaps,
        )
    }
}
