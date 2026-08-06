package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.content.Context
import java.util.Locale

data class BottleSettings(
    /** A short-lived address cache. The advertised name is the bottle identity. */
    val address: String?,
    val name: String,
    val sizeMl: Int,
) {
    val identity: String
        get() = name.trim().lowercase(Locale.ROOT)

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

        fun validateName(raw: String): String {
            val name = raw.trim()
            require(name.isNotBlank()) { "Choose the bottle name shown by Bluetooth scanning" }
            return name
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
        val savedName = preferences.getString(KEY_NAME, null)?.trim().orEmpty()
        val cachedAddress = preferences.getString(KEY_ADDRESS, null)
            ?.let { raw -> runCatching { BottleSettings.normalizeAddress(raw) }.getOrNull() }
        // Configurations from older versions always have an address. Preserve
        // them, but use their saved name for all new discovery attempts.
        if (savedName.isBlank() && cachedAddress == null) return null
        return BottleSettings(
            address = cachedAddress,
            name = savedName.ifBlank { DEFAULT_NAME },
            sizeMl = preferences.getInt(KEY_SIZE_ML, DEFAULT_SIZE_ML),
        )
    }

    fun saveBottle(rawAddress: String, rawName: String, sizeMl: Int): BottleSettings {
        val settings = BottleSettings(
            address = rawAddress.trim().takeIf { it.isNotEmpty() }
                ?.let(BottleSettings::normalizeAddress),
            name = BottleSettings.validateName(rawName),
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

    fun updateLastKnownAddress(address: String) {
        check(
            preferences.edit()
                .putString(KEY_ADDRESS, BottleSettings.normalizeAddress(address))
                .commit(),
        ) { "Could not save the bottle's current Bluetooth address" }
    }

    fun isLiveSyncEnabled(): Boolean = preferences.getBoolean(
        KEY_LIVE_SYNC_ENABLED,
        // Existing installations predate this flag. A configured bottle means
        // live sync was previously enabled unless the user stops it now.
        loadBottle() != null,
    )

    fun setLiveSyncEnabled(enabled: Boolean) {
        check(
            preferences.edit()
                .putBoolean(KEY_LIVE_SYNC_ENABLED, enabled)
                .commit(),
        ) { "Could not save the live sync setting" }
    }

    companion object {
        const val DEFAULT_SIZE_ML = 591
        private const val DEFAULT_NAME = "HidrateSpark"
        private const val PREFERENCES_NAME = "direct_bottle_sync"
        private const val KEY_ADDRESS = "bottle_address"
        private const val KEY_NAME = "bottle_name"
        private const val KEY_SIZE_ML = "bottle_size_ml"
        private const val KEY_LIVE_SYNC_ENABLED = "live_sync_enabled"
    }
}
