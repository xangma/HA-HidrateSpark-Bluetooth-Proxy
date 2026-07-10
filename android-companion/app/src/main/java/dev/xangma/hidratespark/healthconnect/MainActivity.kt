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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var baseUrl: EditText
    private lateinit var token: EditText
    private lateinit var saveAndSync: Button
    private lateinit var status: TextView

    private val permissionLauncher: ActivityResultLauncher<Set<String>> =
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
        baseUrl.setText(configStore.savedBaseUrl())
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

        addText(getString(R.string.home_assistant_url_label))
        baseUrl = EditText(this).apply {
            hint = getString(R.string.home_assistant_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO
        }
        form.addView(baseUrl, matchWrap())

        addText(getString(R.string.token_label))
        token = EditText(this).apply {
            hint = getString(R.string.token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO
        }
        form.addView(token, matchWrap())

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

    private fun saveThenAuthorizeAndSync() {
        try {
            configStore.saveConnection(baseUrl.text.toString(), token.text.toString())
            token.text.clear()
        } catch (error: Exception) {
            showStatus(error.message ?: getString(R.string.status_save_failed))
            return
        }

        when (HealthConnectWriter.sdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                try {
                    if (HealthConnectWriter.create(this@MainActivity).hasPermission()) {
                        HealthConnectSyncWorker.schedule(this@MainActivity)
                        runSync()
                    } else {
                        showStatus(getString(R.string.status_waiting_permission))
                        permissionLauncher.launch(HealthConnectWriter.REQUIRED_PERMISSIONS)
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
        showStatus(getString(R.string.status_syncing))
        lifecycleScope.launch {
            try {
                val summary = SyncEngine(this@MainActivity, configStore).sync()
                val message = if (summary.retentionGaps > 0) {
                    getString(
                        R.string.status_sync_complete_with_gap,
                        summary.sips,
                        summary.bottles,
                        summary.retentionGaps,
                    )
                } else {
                    getString(
                        R.string.status_sync_complete,
                        summary.sips,
                        summary.bottles,
                    )
                }
                showStatus(message)
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.status_sync_failed))
            } finally {
                saveAndSync.isEnabled = true
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
}
