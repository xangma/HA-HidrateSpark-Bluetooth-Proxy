package dev.xangma.hidratespark.healthconnect

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Debug-only activity, launched through ADB for a read-only live-notification probe. */
class GattNotificationDebugActivity : ComponentActivity() {
    private lateinit var start: Button
    private lateinit var status: TextView
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            textSize = 20f
            text = "Unsolicited GATT notification probe"
        })
        content.addView(TextView(this).apply {
            text = "This connects, performs the normal handshake, and enables notifications. " +
                "It never sends 0x57, so it does not drain or acknowledge bottle records. " +
                "After the prompt, take one sip and leave the bottle nearby."
            setPadding(0, dp(12), 0, dp(12))
        })
        start = Button(this).apply {
            text = "Start 2-minute probe"
            setOnClickListener { startProbe() }
        }
        content.addView(start, matchWrap())
        status = TextView(this).apply {
            textSize = 16f
            text = "Ready. Keep the official HidrateSpark app closed."
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(status, matchWrap())
        report = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        content.addView(report, matchWrap())
        setContentView(content)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) startProbe()
    }

    private fun startProbe() {
        start.isEnabled = false
        report.text = ""
        status.text = "Connecting and enabling notifications. Do not take a sip yet."
        Log.i(TAG, "Probe started; no drain command will be sent")
        lifecycleScope.launch {
            try {
                val bottle = resolveBottle()
                SipStore(this@GattNotificationDebugActivity).use { store ->
                    val result = BottleGattClient(this@GattNotificationDebugActivity, bottle, store)
                        .observeUnsolicitedNotifications(OBSERVATION_DURATION_MS) {
                            status.text = "Connected. Notifications are enabled. Take one sip now; waiting 2 minutes."
                            Log.i(TAG, "Ready: notifications enabled; take one sip now")
                        }
                    report.text = result.report()
                    Log.i(TAG, result.report())
                    status.text = if (result.notifications.isEmpty()) {
                        "Complete: no unsolicited data notification was received."
                    } else {
                        "Complete: unsolicited data notification received."
                    }
                }
            } catch (error: Exception) {
                status.text = error.message ?: "Probe failed"
                Log.e(TAG, "Probe failed", error)
            } finally {
                start.isEnabled = true
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private suspend fun resolveBottle(): BottleSettings {
        ConfigStore(this).loadBottle()?.let { return it }
        val requestedName = intent.getStringExtra(EXTRA_BOTTLE_NAME)?.trim().orEmpty()
        require(requestedName.isNotBlank()) {
            "Select and save a bottle, or provide a bottle name to the debug activity"
        }
        status.text = "No saved bottle: scanning for $requestedName. Do not take a sip yet."
        Log.i(TAG, "Scanning for current address of $requestedName")
        val candidates = BottleScanner(this).scan()
        Log.i(TAG, "Scan candidates: ${candidates.joinToString { "${it.name}@${it.address} (${it.rssi} dBm)" }}")
        val discovered = candidates.firstOrNull {
            it.name.equals(requestedName, ignoreCase = true)
        } ?: throw IllegalStateException("Could not find $requestedName in a 10-second scan")
        Log.i(TAG, "Using discovered address ${discovered.address} (${discovered.rssi} dBm)")
        return BottleSettings(
            address = discovered.address,
            name = discovered.name,
            sizeMl = intent.getIntExtra(EXTRA_BOTTLE_SIZE_ML, ConfigStore.DEFAULT_SIZE_ML),
        )
    }

    private companion object {
        const val EXTRA_AUTO_START = "dev.xangma.hidratespark.healthconnect.AUTO_START_GATT_PROBE"
        const val EXTRA_BOTTLE_NAME = "dev.xangma.hidratespark.healthconnect.BOTTLE_NAME"
        const val EXTRA_BOTTLE_SIZE_ML = "dev.xangma.hidratespark.healthconnect.BOTTLE_SIZE_ML"
        const val OBSERVATION_DURATION_MS = 120_000L
        const val TAG = "GattNotificationProbe"
    }
}
