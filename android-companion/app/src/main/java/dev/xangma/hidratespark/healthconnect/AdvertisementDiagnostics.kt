package dev.xangma.hidratespark.healthconnect

import java.util.Locale

data class AdvertisementObservation(
    val elapsedMillis: Long,
    val rssi: Int,
    val connectable: Boolean,
    val rawDataHex: String,
)

data class AdvertisementMarker(
    val label: String,
    val elapsedMillis: Long,
)

data class AdvertisementCapture(
    val requestedDurationMillis: Long,
    val observations: List<AdvertisementObservation>,
) {
    fun report(markers: List<AdvertisementMarker>): String {
        val output = StringBuilder()
        output.appendLine("HidrateSpark advertisement test")
        output.appendLine("Requested duration: ${formatSeconds(requestedDurationMillis)}")
        output.appendLine("Advertisements: ${observations.size}")
        if (observations.isEmpty()) {
            output.append("No matching advertisements were received.")
            return output.toString()
        }

        val intervals = observations.zipWithNext { first, second ->
            second.elapsedMillis - first.elapsedMillis
        }
        output.appendLine("First/last: ${formatSeconds(observations.first().elapsedMillis)} / " +
            formatSeconds(observations.last().elapsedMillis))
        if (intervals.isNotEmpty()) {
            output.appendLine(
                "Interval average/max: ${formatSeconds(intervals.average().toLong())} / " +
                    formatSeconds(intervals.max()),
            )
        }
        output.appendLine("RSSI min/max: ${observations.minOf { it.rssi }} / " +
            "${observations.maxOf { it.rssi }} dBm")

        val payloads = observations.groupBy { it.rawDataHex }
        output.appendLine("Unique raw payloads: ${payloads.size}")
        payloads.entries.sortedByDescending { it.value.size }.forEachIndexed { index, entry ->
            val sightings = entry.value
            output.appendLine(
                "Payload ${index + 1}: ${sightings.size} sightings, " +
                    "${formatSeconds(sightings.first().elapsedMillis)}–" +
                    formatSeconds(sightings.last().elapsedMillis),
            )
            output.appendLine(entry.key.ifBlank { "<no scan record bytes>" })
        }

        output.appendLine("Markers: ${markers.size}")
        markers.forEach { marker ->
            val before = observations.lastOrNull { it.elapsedMillis <= marker.elapsedMillis }
            val after = observations.firstOrNull { it.elapsedMillis >= marker.elapsedMillis }
            output.appendLine("${marker.label} at ${formatSeconds(marker.elapsedMillis)}")
            output.appendLine("  before: ${describeRelative(before, marker.elapsedMillis)}")
            output.appendLine("  after: ${describeRelative(after, marker.elapsedMillis)}")
            output.appendLine(
                "  previous 10s: ${describeWindow(marker.elapsedMillis - EVENT_WINDOW_MS, marker.elapsedMillis)}",
            )
            output.appendLine(
                "  next 10s: ${describeWindow(marker.elapsedMillis, marker.elapsedMillis + EVENT_WINDOW_MS)}",
            )
            if (before != null && after != null) {
                output.appendLine("  raw payload changed: ${before.rawDataHex != after.rawDataHex}")
            }
        }
        return output.toString().trimEnd()
    }

    private fun describeRelative(
        observation: AdvertisementObservation?,
        markerMillis: Long,
    ): String = observation?.let {
        val offsetMillis = it.elapsedMillis - markerMillis
        val sign = if (offsetMillis >= 0) "+" else ""
        "$sign${formatSeconds(offsetMillis)}, ${it.rssi} dBm, connectable=${it.connectable}"
    } ?: "none"

    private fun describeWindow(startMillis: Long, endMillis: Long): String {
        val window = observations.filter { it.elapsedMillis >= startMillis && it.elapsedMillis < endMillis }
        if (window.isEmpty()) return "0 packets"
        val intervals = window.zipWithNext { first, second ->
            second.elapsedMillis - first.elapsedMillis
        }
        val cadence = if (intervals.isEmpty()) {
            "interval unavailable"
        } else {
            "interval avg/max ${formatSeconds(intervals.average().toLong())}/${formatSeconds(intervals.max())}"
        }
        return "${window.size} packets, $cadence"
    }

    private fun formatSeconds(millis: Long): String =
        String.format(Locale.ROOT, "%.3fs", millis / 1_000.0)

    companion object {
        private const val EVENT_WINDOW_MS = 10_000L
    }
}

data class PresenceObservation(
    val elapsedMillis: Long,
    val event: String,
    val address: String,
)

data class PresenceCapture(
    val requestedDurationMillis: Long,
    val observations: List<PresenceObservation>,
) {
    fun report(): String = buildString {
        appendLine("HidrateSpark FIRST_MATCH / MATCH_LOST test")
        appendLine("Requested duration: ${formatSeconds(requestedDurationMillis)}")
        appendLine("Presence callbacks: ${observations.size}")
        appendLine("FIRST_MATCH: ${observations.count { it.event == EVENT_FIRST_MATCH }}")
        appendLine("MATCH_LOST: ${observations.count { it.event == EVENT_MATCH_LOST }}")
        observations.forEach { observation ->
            appendLine(
                "${formatSeconds(observation.elapsedMillis)} ${observation.event} ${observation.address}",
            )
        }
    }.trimEnd()

    private fun formatSeconds(millis: Long): String =
        String.format(Locale.ROOT, "%.3fs", millis / 1_000.0)

    companion object {
        const val EVENT_FIRST_MATCH = "FIRST_MATCH"
        const val EVENT_MATCH_LOST = "MATCH_LOST"
    }
}
