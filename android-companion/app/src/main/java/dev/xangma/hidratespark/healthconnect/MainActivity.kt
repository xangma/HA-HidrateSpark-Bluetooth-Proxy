package dev.xangma.hidratespark.healthconnect

import android.os.Bundle
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
    private lateinit var saveAndSync: Button
    private lateinit var status: TextView
    private var pendingBluetoothAction: BluetoothAction? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            when (action) {
                BluetoothAction.SCAN -> performScan()
                BluetoothAction.SYNC -> authorizeHealthAndSync()
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
                runSync()
            } else {
                showStatus(getString(R.string.status_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        setContentView(buildContent())
        configStore.loadBottle()?.let { saved ->
            address.setText(saved.address)
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

        saveAndSync = Button(this).apply {
            text = getString(R.string.save_and_sync)
            setOnClickListener { saveThenAuthorizeAndSync() }
        }
        form.addView(saveAndSync, matchWrap())

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class BluetoothAction { SCAN, SYNC }
}
