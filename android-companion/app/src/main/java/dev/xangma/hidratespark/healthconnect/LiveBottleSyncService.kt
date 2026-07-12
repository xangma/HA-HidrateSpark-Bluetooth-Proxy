package dev.xangma.hidratespark.healthconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Keeps one GATT notification subscription alive while the user explicitly
 * enables live sync. A periodic worker remains the recovery path.
 */
class LiveBottleSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startAsForeground(getString(R.string.live_sync_starting))
        if (listenerJob?.isActive != true) {
            listenerJob = serviceScope.launch { listenUntilStopped() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        listenerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun listenUntilStopped() {
        val configStore = ConfigStore(applicationContext)
        val bottle = configStore.loadBottle()
        if (bottle == null) {
            updateNotification(getString(R.string.live_sync_needs_bottle))
            stopSelf()
            return
        }
        val syncEngine = SyncEngine(applicationContext, configStore)
        while (serviceScope.isActive) {
            try {
                updateNotification(getString(R.string.live_sync_discovering, bottle.name))
                val discovered = BottleLocator(applicationContext).resolve(bottle)
                configStore.updateLastKnownAddress(discovered.address)
                SipStore(applicationContext).use { store ->
                    BottleGattClient(applicationContext, bottle, store).open(discovered.address).use {
                        connection ->
                        Log.i(TAG, "Connected to ${bottle.name}; waiting for GATT notifications")
                        updateNotification(getString(R.string.live_sync_listening, bottle.name))
                        val queuePolicy = PendingQueuePolicy()
                        while (serviceScope.isActive) {
                            val pending = connection.awaitPendingRecords()
                            if (!queuePolicy.shouldDrain(pending)) continue
                            updateNotification(getString(R.string.live_sync_draining, pending))
                            try {
                                val summary = syncEngine.syncConnected(bottle, connection)
                                queuePolicy.onDrainCompleted()
                                updateNotification(
                                    getString(
                                        R.string.live_sync_complete,
                                        summary.collectedSips,
                                        summary.writtenSips,
                                    ),
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                queuePolicy.onSyncFailed()
                                updateNotification(getString(R.string.live_sync_retrying))
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                Log.w(TAG, "Live connection unavailable; retrying", error)
                updateNotification(getString(R.string.live_sync_reconnecting))
            } catch (error: Exception) {
                Log.w(TAG, "Live connection failed; retrying", error)
                updateNotification(getString(R.string.live_sync_reconnecting))
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun startAsForeground(content: String) {
        createChannel()
        val notification = notification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(content))
    }

    private fun notification(content: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.live_sync_notification_title))
        .setContentText(content)
        .setOngoing(true)
        .addAction(
            0,
            getString(R.string.stop_live_sync),
            PendingIntent.getService(
                this,
                0,
                Intent(this, LiveBottleSyncService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.live_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "live_bottle_sync"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "dev.xangma.hidratespark.healthconnect.STOP_LIVE_SYNC"
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val TAG = "HidrateSparkLive"

        @Volatile
        private var running = false

        internal fun isRunning(): Boolean = running

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveBottleSyncService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveBottleSyncService::class.java))
        }
    }
}
