package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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
