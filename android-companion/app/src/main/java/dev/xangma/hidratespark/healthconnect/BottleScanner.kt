package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredBottle(
    val address: String,
    val name: String,
    val rssi: Int,
)

class BottleScanner(context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun scan(durationMillis: Long = SCAN_DURATION_MS): List<DiscoveredBottle> {
        if (BluetoothPermissions.missingForSetup(appContext).isNotEmpty()) {
            throw BluetoothPermissionRequiredException()
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is not supported on this phone")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth, then scan again")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("Bluetooth scanning is unavailable")
        val results = ConcurrentHashMap<String, DiscoveredBottle>()
        val failure = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                candidate(result)?.let { candidate ->
                    results.compute(candidate.address) { _, current ->
                        if (current == null || candidate.rssi > current.rssi) candidate else current
                    }
                }
            }

            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failure.complete(errorCode)
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
            withTimeoutOrNull(durationMillis) { failure.await() }?.let { code ->
                throw IOException("Bluetooth scan failed (code $code)")
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return results.values.sortedWith(
            compareByDescending<DiscoveredBottle> { it.rssi }.thenBy { it.name },
        )
    }

    /** Resolves the bottle's current private address from its stable advertised name. */
    @SuppressLint("MissingPermission")
    suspend fun findByName(
        advertisedName: String,
        durationMillis: Long = SCAN_DURATION_MS,
    ): DiscoveredBottle {
        if (BluetoothPermissions.missingForSetup(appContext).isNotEmpty()) {
            throw BluetoothPermissionRequiredException()
        }
        require(advertisedName.isNotBlank()) { "Choose a bottle name before syncing" }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is not supported on this phone")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth, then sync again")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("Bluetooth scanning is unavailable")
        var best: DiscoveredBottle? = null
        val failure = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val candidate = candidate(result) ?: return
                if (candidate.name != advertisedName) return
                if (best == null || candidate.rssi > best!!.rssi) best = candidate
            }

            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failure.complete(errorCode)
            }
        }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceName(advertisedName).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
            withTimeoutOrNull(durationMillis) { failure.await() }?.let { code ->
                throw IOException("Bluetooth scan failed (code $code)")
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return best ?: throw IOException("Could not find $advertisedName nearby")
    }

    @SuppressLint("MissingPermission")
    suspend fun captureAdvertisements(
        address: String,
        durationMillis: Long,
    ): AdvertisementCapture {
        if (BluetoothPermissions.missingForSetup(appContext).isNotEmpty()) {
            throw BluetoothPermissionRequiredException()
        }
        val normalizedAddress = BottleSettings.normalizeAddress(address)
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is not supported on this phone")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth, then start the test again")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("Bluetooth scanning is unavailable")
        val observations = Collections.synchronizedList(mutableListOf<AdvertisementObservation>())
        val failure = CompletableDeferred<Int>()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(normalizedAddress, ignoreCase = true)) return
                observations += AdvertisementObservation(
                    elapsedMillis = android.os.SystemClock.elapsedRealtime() - startedAt,
                    rssi = result.rssi,
                    connectable = result.isConnectable,
                    rawDataHex = result.scanRecord?.bytes?.toHex().orEmpty(),
                )
            }

            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failure.complete(errorCode)
            }
        }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(normalizedAddress).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build(),
                callback,
            )
            withTimeoutOrNull(durationMillis) { failure.await() }?.let { code ->
                throw IOException("Bluetooth scan failed (code $code)")
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val captured = synchronized(observations) { observations.toList() }
        return AdvertisementCapture(durationMillis, captured)
    }

    @SuppressLint("MissingPermission")
    suspend fun capturePresenceEvents(
        advertisedName: String,
        durationMillis: Long,
    ): PresenceCapture {
        if (BluetoothPermissions.missingForSetup(appContext).isNotEmpty()) {
            throw BluetoothPermissionRequiredException()
        }
        require(advertisedName.isNotBlank()) { "Select a bottle before starting the presence test" }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is not supported on this phone")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth, then start the test again")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("Bluetooth scanning is unavailable")
        val observations = Collections.synchronizedList(mutableListOf<PresenceObservation>())
        val failure = CompletableDeferred<Int>()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val event = when (callbackType) {
                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> PresenceCapture.EVENT_FIRST_MATCH
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST -> PresenceCapture.EVENT_MATCH_LOST
                    else -> "CALLBACK_$callbackType"
                }
                observations += PresenceObservation(
                    elapsedMillis = android.os.SystemClock.elapsedRealtime() - startedAt,
                    event = event,
                    address = result.device.address.uppercase(Locale.ROOT),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                failure.complete(errorCode)
            }
        }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceName(advertisedName).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(
                        ScanSettings.CALLBACK_TYPE_FIRST_MATCH or
                            ScanSettings.CALLBACK_TYPE_MATCH_LOST,
                    )
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build(),
                callback,
            )
            withTimeoutOrNull(durationMillis) { failure.await() }?.let { code ->
                throw IOException("Bluetooth presence scan failed (code $code)")
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val captured = synchronized(observations) { observations.toList() }
        return PresenceCapture(durationMillis, captured)
    }

    @SuppressLint("MissingPermission")
    private fun candidate(result: ScanResult): DiscoveredBottle? {
        val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
        val advertisesReferenceService = result.scanRecord?.serviceUuids
            ?.contains(ParcelUuid(BleProtocol.REFERENCE_SERVICE)) == true
        if (!advertisesReferenceService && !name.startsWith("h2o", ignoreCase = true)) {
            return null
        }
        return DiscoveredBottle(
            address = result.device.address.uppercase(),
            name = name.ifBlank { "HidrateSpark" },
            rssi = result.rssi,
        )
    }

    companion object {
        private const val SCAN_DURATION_MS = 10_000L
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02X".format(Locale.ROOT, byte.toInt() and 0xff)
}
