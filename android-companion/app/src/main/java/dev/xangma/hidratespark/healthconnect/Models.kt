package dev.xangma.hidratespark.healthconnect

data class Bottle(
    val entryId: String,
    val journalId: String,
    val name: String,
    val address: String,
)

data class Sip(
    val id: String,
    val sequence: Long,
    val timestampSeconds: Double,
    val volumeMl: Int,
)

data class SipPage(
    val journalId: String,
    val sips: List<Sip>,
    val nextAfter: Long,
    val hasMore: Boolean,
    val truncated: Boolean,
)

data class SyncSummary(
    val bottles: Int,
    val sips: Int,
    val retentionGaps: Int,
)

class HealthPermissionRequiredException : IllegalStateException(
    "Health Connect hydration write permission is required",
)
