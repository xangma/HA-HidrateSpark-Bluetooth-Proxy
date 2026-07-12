package dev.xangma.hidratespark.healthconnect

import android.content.Context

/** Finds the current BLE address immediately before a GATT connection. */
class BottleLocator(context: Context) {
    private val scanner = BottleScanner(context.applicationContext)

    suspend fun resolve(settings: BottleSettings): DiscoveredBottle =
        scanner.findByName(settings.name)
}
