package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.content.Context

data class BottleSettings(
    val address: String,
    val name: String,
    val sizeMl: Int,
) {
    companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun normalizeAddress(raw: String): String {
            val address = raw.trim().uppercase()
            require(MAC_ADDRESS.matches(address)) {
                "Enter a Bluetooth address such as AA:BB:CC:DD:EE:FF"
            }
            return address
        }

        fun validateSize(sizeMl: Int): Int {
            require(sizeMl in 100..2_000) {
                "Bottle capacity must be between 100 and 2000 mL"
            }
            return sizeMl
        }
    }
}

@SuppressLint("UseKtx")
class ConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadBottle(): BottleSettings? {
        val address = preferences.getString(KEY_ADDRESS, null) ?: return null
        return BottleSettings(
            address = address,
            name = preferences.getString(KEY_NAME, DEFAULT_NAME).orEmpty().ifBlank { DEFAULT_NAME },
            sizeMl = preferences.getInt(KEY_SIZE_ML, DEFAULT_SIZE_ML),
        )
    }

    fun saveBottle(rawAddress: String, rawName: String, sizeMl: Int): BottleSettings {
        val settings = BottleSettings(
            address = BottleSettings.normalizeAddress(rawAddress),
            name = rawName.trim().ifBlank { DEFAULT_NAME },
            sizeMl = BottleSettings.validateSize(sizeMl),
        )
        check(
            preferences.edit()
                .putString(KEY_ADDRESS, settings.address)
                .putString(KEY_NAME, settings.name)
                .putInt(KEY_SIZE_ML, settings.sizeMl)
                .commit(),
        ) { "Could not save bottle settings" }
        return settings
    }

    companion object {
        const val DEFAULT_SIZE_ML = 591
        private const val DEFAULT_NAME = "HidrateSpark"
        private const val PREFERENCES_NAME = "direct_bottle_sync"
        private const val KEY_ADDRESS = "bottle_address"
        private const val KEY_NAME = "bottle_name"
        private const val KEY_SIZE_ML = "bottle_size_ml"
    }
}
