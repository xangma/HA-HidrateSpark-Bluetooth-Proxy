package dev.xangma.hidratespark.healthconnect

data class Sip(
    val id: String,
    val bottleAddress: String,
    val bottleName: String,
    val timestampMillis: Long,
    val volumeMl: Int,
    val totalReportedMl: Int,
)

data class SyncSummary(
    val collectedSips: Int,
    val writtenSips: Int,
)

class HealthPermissionRequiredException : IllegalStateException(
    "Health Connect hydration write permission is required",
)

class BluetoothPermissionRequiredException : IllegalStateException(
    "Nearby devices permission is required",
)
