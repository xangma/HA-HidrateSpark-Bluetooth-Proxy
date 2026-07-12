package dev.xangma.hidratespark.healthconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var address: EditText
    private lateinit var bottleName: EditText
    private lateinit var bottleSize: EditText
    private lateinit var scan: Button
    private lateinit var scanResults: LinearLayout
    private lateinit var testAdvertisements: Button
    private lateinit var testPresence: Button
    private lateinit var markSip: Button
    private lateinit var markMovement: Button
    private lateinit var copyAdvertisementReport: Button
    private lateinit var advertisementReport: TextView
    private lateinit var saveAndSync: Button
    private lateinit var startLiveSync: Button
    private lateinit var stopLiveSync: Button
    private lateinit var status: TextView
    private var pendingBluetoothAction: BluetoothAction? = null
    private var startLiveAfterHealthPermission = false
    private var advertisementTestStartedAt: Long? = null
    private val advertisementMarkers = mutableListOf<AdvertisementMarker>()

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            when (action) {
                BluetoothAction.SCAN -> performScan()
                BluetoothAction.SYNC -> authorizeHealthAndSync()
                BluetoothAction.LIVE_SYNC -> authorizeHealthAndStartLive()
                BluetoothAction.TEST_ADVERTISEMENTS -> performAdvertisementTest()
                BluetoothAction.TEST_PRESENCE -> performPresenceTest()
                null -> Unit
            }
        } else {
            pendingBluetoothAction = null
            showStatus(getString(R.string.status_bluetooth_permission_denied))
        }
    }

    private val healthPermissionLauncher: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(HealthConnectWriter.REQUIRED_PERMISSIONS)) {
                HealthConnectSyncWorker.schedule(this)
                if (startLiveAfterHealthPermission) {
                    startLiveAfterHealthPermission = false
                    startLiveSync()
                } else {
                    runSync()
                }
            } else {
                startLiveAfterHealthPermission = false
                showStatus(getString(R.string.status_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        setContentView(buildContent())
        configStore.loadBottle()?.let { saved ->
            address.setText(saved.address.orEmpty())
            bottleName.setText(saved.name)
            bottleSize.setText(String.format(Locale.ROOT, "%d", saved.sizeMl))
        } ?: bottleSize.setText(
            String.format(Locale.ROOT, "%d", ConfigStore.DEFAULT_SIZE_ML),
        )
        showStatus(getString(R.string.status_initial))
    }

    private fun buildContent(): ScrollView {
        val padding = dp(20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun addText(text: String, size: Float = 16f) {
            form.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = size
                },
                matchWrap(),
            )
        }

        addText(getString(R.string.screen_title), 24f)
        addText(getString(R.string.screen_description))

        scan = Button(this).apply {
            text = getString(R.string.scan_for_bottle)
            setOnClickListener { requestBluetoothThen(BluetoothAction.SCAN) }
        }
        form.addView(scan, matchWrap())

        scanResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(scanResults, matchWrap())

        addText(getString(R.string.bottle_address_label))
        address = EditText(this).apply {
            hint = getString(R.string.bottle_address_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO
        }
        form.addView(address, matchWrap())

        addText(getString(R.string.bottle_name_label))
        bottleName = EditText(this).apply {
            hint = getString(R.string.bottle_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        form.addView(bottleName, matchWrap())

        addText(getString(R.string.bottle_size_label))
        bottleSize = EditText(this).apply {
            hint = ConfigStore.DEFAULT_SIZE_ML.toString()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        form.addView(bottleSize, matchWrap())

        addText(getString(R.string.advertisement_test_title), 20f)
        addText(getString(R.string.advertisement_test_description))
        testAdvertisements = Button(this).apply {
            text = getString(R.string.test_advertisements)
            setOnClickListener {
                requestBluetoothThen(BluetoothAction.TEST_ADVERTISEMENTS)
            }
        }
        form.addView(testAdvertisements, matchWrap())
        testPresence = Button(this).apply {
            text = getString(R.string.test_presence)
            setOnClickListener {
                requestBluetoothThen(BluetoothAction.TEST_PRESENCE)
            }
        }
        form.addView(testPresence, matchWrap())

        val markerButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        markSip = markerButton(getString(R.string.mark_sip)) {
            addAdvertisementMarker(getString(R.string.marker_sip))
        }
        markMovement = markerButton(getString(R.string.mark_movement)) {
            addAdvertisementMarker(getString(R.string.marker_movement))
        }
        markerButtons.addView(markSip, weightedWrap())
        markerButtons.addView(markMovement, weightedWrap())
        form.addView(markerButtons, matchWrap())

        copyAdvertisementReport = Button(this).apply {
            text = getString(R.string.copy_advertisement_report)
            isEnabled = false
            setOnClickListener { copyAdvertisementReport() }
        }
        form.addView(copyAdvertisementReport, matchWrap())
        advertisementReport = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        form.addView(advertisementReport, matchWrap())

        saveAndSync = Button(this).apply {
            text = getString(R.string.save_and_sync)
            setOnClickListener { saveThenAuthorizeAndSync() }
        }
        form.addView(saveAndSync, matchWrap())

        startLiveSync = Button(this).apply {
            text = getString(R.string.start_live_sync)
            setOnClickListener { saveThenAuthorizeAndStartLive() }
        }
        form.addView(startLiveSync, matchWrap())

        stopLiveSync = Button(this).apply {
            text = getString(R.string.stop_live_sync)
            setOnClickListener {
                LiveBottleSyncService.stop(this@MainActivity)
                showStatus(getString(R.string.status_live_sync_stopped))
            }
        }
        form.addView(stopLiveSync, matchWrap())

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, 0)
        }
        form.addView(status, matchWrap())

        return ScrollView(this).apply { addView(form) }
    }

    private fun requestBluetoothThen(action: BluetoothAction) {
        val missing = BluetoothPermissions.missingForSetup(this)
        if (missing.isEmpty()) {
            when (action) {
                BluetoothAction.SCAN -> performScan()
                BluetoothAction.SYNC -> authorizeHealthAndSync()
                BluetoothAction.LIVE_SYNC -> authorizeHealthAndStartLive()
                BluetoothAction.TEST_ADVERTISEMENTS -> performAdvertisementTest()
                BluetoothAction.TEST_PRESENCE -> performPresenceTest()
            }
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing)
        }
    }

    private fun performScan() {
        scan.isEnabled = false
        scanResults.removeAllViews()
        showStatus(getString(R.string.status_scanning))
        lifecycleScope.launch {
            try {
                val bottles = BottleScanner(this@MainActivity).scan()
                if (bottles.isEmpty()) {
                    showStatus(getString(R.string.status_no_bottles))
                } else {
                    bottles.forEach { bottle -> addScanResult(bottle) }
                    showStatus(
                        resources.getQuantityString(
                            R.plurals.status_choose_bottle,
                            bottles.size,
                            bottles.size,
                        ),
                    )
                }
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.status_scan_failed))
            } finally {
                scan.isEnabled = true
            }
        }
    }

    private fun addScanResult(bottle: DiscoveredBottle) {
        scanResults.addView(
            Button(this).apply {
                text = getString(
                    R.string.scan_result,
                    bottle.name,
                    bottle.address,
                    bottle.rssi,
                )
                setOnClickListener {
                    address.setText(bottle.address)
                    bottleName.setText(bottle.name)
                    showStatus(getString(R.string.status_bottle_selected, bottle.name))
                }
            },
            matchWrap(),
        )
    }

    private fun performAdvertisementTest() {
        val normalizedAddress = try {
            BottleSettings.normalizeAddress(address.text.toString())
        } catch (error: Exception) {
            showStatus(getString(R.string.status_select_bottle_for_test))
            return
        }
        advertisementMarkers.clear()
        advertisementReport.text = ""
        copyAdvertisementReport.isEnabled = false
        advertisementTestStartedAt = SystemClock.elapsedRealtime()
        setAdvertisementTestRunning(true)
        showStatus(getString(R.string.status_advertisement_test_running))
        lifecycleScope.launch {
            try {
                val capture = BottleScanner(this@MainActivity).captureAdvertisements(
                    normalizedAddress,
                    ADVERTISEMENT_TEST_DURATION_MS,
                )
                advertisementReport.text = capture.report(advertisementMarkers.toList())
                copyAdvertisementReport.isEnabled = true
                showStatus(
                    getString(
                        R.string.status_advertisement_test_complete,
                        capture.observations.size,
                        capture.observations.map { it.rawDataHex }.distinct().size,
                    ),
                )
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.status_advertisement_test_failed))
            } finally {
                advertisementTestStartedAt = null
                setAdvertisementTestRunning(false)
            }
        }
    }

    private fun addAdvertisementMarker(label: String) {
        val startedAt = advertisementTestStartedAt ?: return
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
        advertisementMarkers += AdvertisementMarker(label, elapsedMillis)
        showStatus(getString(R.string.status_marker_recorded, label, elapsedMillis / 1_000.0))
    }

    private fun performPresenceTest() {
        val selectedName = bottleName.text.toString().trim()
        if (selectedName.isBlank() || selectedName == getString(R.string.bottle_name_hint)) {
            showStatus(getString(R.string.status_select_bottle_for_test))
            return
        }
        advertisementMarkers.clear()
        advertisementReport.text = ""
        copyAdvertisementReport.isEnabled = false
        setAdvertisementTestRunning(true)
        showStatus(getString(R.string.status_presence_test_running))
        lifecycleScope.launch {
            try {
                val capture = BottleScanner(this@MainActivity).capturePresenceEvents(
                    selectedName,
                    PRESENCE_TEST_DURATION_MS,
                )
                advertisementReport.text = capture.report()
                copyAdvertisementReport.isEnabled = true
                showStatus(
                    getString(
                        R.string.status_presence_test_complete,
                        capture.observations.count {
                            it.event == PresenceCapture.EVENT_FIRST_MATCH
                        },
                        capture.observations.count {
                            it.event == PresenceCapture.EVENT_MATCH_LOST
                        },
                    ),
                )
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.status_presence_test_failed))
            } finally {
                setAdvertisementTestRunning(false)
            }
        }
    }

    private fun copyAdvertisementReport() {
        val report = advertisementReport.text.toString()
        if (report.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.advertisement_report_label), report),
        )
        showStatus(getString(R.string.status_advertisement_report_copied))
    }

    private fun setAdvertisementTestRunning(running: Boolean) {
        testAdvertisements.isEnabled = !running
        testPresence.isEnabled = !running
        scan.isEnabled = !running
        saveAndSync.isEnabled = !running
        startLiveSync.isEnabled = !running
        stopLiveSync.isEnabled = !running
        markSip.isEnabled = running
        markMovement.isEnabled = running
    }

    private fun saveThenAuthorizeAndSync() {
        try {
            val size = bottleSize.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("Enter the bottle capacity in millilitres")
            configStore.saveBottle(
                address.text.toString(),
                bottleName.text.toString(),
                size,
            )
        } catch (error: Exception) {
            showStatus(error.message ?: getString(R.string.status_save_failed))
            return
        }
        requestBluetoothThen(BluetoothAction.SYNC)
    }

    private fun saveThenAuthorizeAndStartLive() {
        try {
            val size = bottleSize.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("Enter the bottle capacity in millilitres")
            configStore.saveBottle(
                address.text.toString(),
                bottleName.text.toString(),
                size,
            )
        } catch (error: Exception) {
            showStatus(error.message ?: getString(R.string.status_save_failed))
            return
        }
        requestBluetoothThen(BluetoothAction.LIVE_SYNC)
    }

    private fun authorizeHealthAndSync() {
        when (HealthConnectWriter.sdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                try {
                    if (HealthConnectWriter.create(this@MainActivity).hasPermission()) {
                        HealthConnectSyncWorker.schedule(this@MainActivity)
                        runSync()
                    } else {
                        showStatus(getString(R.string.status_waiting_permission))
                        healthPermissionLauncher.launch(HealthConnectWriter.REQUIRED_PERMISSIONS)
                    }
                } catch (error: Exception) {
                    showStatus(error.message ?: getString(R.string.status_open_failed))
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                showStatus(getString(R.string.status_provider_update))
            else -> showStatus(getString(R.string.status_unavailable))
        }
    }

    private fun authorizeHealthAndStartLive() {
        when (HealthConnectWriter.sdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                try {
                    if (HealthConnectWriter.create(this@MainActivity).hasPermission()) {
                        startLiveSync()
                        HealthConnectSyncWorker.schedule(this@MainActivity)
                    } else {
                        startLiveAfterHealthPermission = true
                        showStatus(getString(R.string.status_waiting_permission))
                        healthPermissionLauncher.launch(HealthConnectWriter.REQUIRED_PERMISSIONS)
                    }
                } catch (error: Exception) {
                    showStatus(error.message ?: getString(R.string.status_open_failed))
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                showStatus(getString(R.string.status_provider_update))
            else -> showStatus(getString(R.string.status_unavailable))
        }
    }

    private fun startLiveSync() {
        LiveBottleSyncService.start(this)
        showStatus(getString(R.string.status_live_sync_started))
    }

    private fun runSync() {
        saveAndSync.isEnabled = false
        scan.isEnabled = false
        showStatus(getString(R.string.status_syncing))
        lifecycleScope.launch {
            try {
                val summary = SyncEngine(this@MainActivity, configStore).sync()
                showStatus(
                    getString(
                        R.string.status_sync_complete,
                        summary.collectedSips,
                        summary.writtenSips,
                    ),
                )
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.status_sync_failed))
            } finally {
                saveAndSync.isEnabled = true
                scan.isEnabled = true
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedWrap() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun markerButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isEnabled = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class BluetoothAction { SCAN, SYNC, LIVE_SYNC, TEST_ADVERTISEMENTS, TEST_PRESENCE }

    companion object {
        private const val ADVERTISEMENT_TEST_DURATION_MS = 90_000L
        private const val PRESENCE_TEST_DURATION_MS = 300_000L
    }
}
