package dev.xangma.hidratespark.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId

class HealthConnectWriter(private val client: HealthConnectClient) {
    suspend fun hasPermission(): Boolean = client.permissionController
        .getGrantedPermissions()
        .containsAll(REQUIRED_PERMISSIONS)

    suspend fun write(bottle: BottleSettings, sips: List<Sip>) {
        if (sips.isEmpty()) return
        val device = Device(
            type = Device.TYPE_UNKNOWN,
            manufacturer = "HidrateSpark",
            model = bottle.name,
        )
        val records = sips.map { sip ->
            val start = Instant.ofEpochMilli(sip.timestampMillis)
            val end = start.plusSeconds(1)
            HydrationRecord(
                startTime = start,
                startZoneOffset = ZoneId.systemDefault().rules.getOffset(start),
                endTime = end,
                endZoneOffset = ZoneId.systemDefault().rules.getOffset(end),
                volume = Volume.milliliters(sip.volumeMl.toDouble()),
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = RecordIds.forSip(sip.id),
                    clientRecordVersion = 1L,
                ),
            )
        }
        client.insertRecords(records)
    }

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(HydrationRecord::class),
        )

        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

        fun create(context: Context): HealthConnectWriter =
            HealthConnectWriter(HealthConnectClient.getOrCreate(context))
    }
}
