package dev.xangma.hidratespark.healthconnect

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

class HealthConnectSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = try {
        val summary = SyncEngine(applicationContext).sync()
        Result.success(
            workDataOf(
                "collected_sips" to summary.collectedSips,
                "written_sips" to summary.writtenSips,
            ),
        )
    } catch (error: HealthPermissionRequiredException) {
        Log.w(TAG, error.message.orEmpty())
        Result.failure(workDataOf("error" to error.message))
    } catch (error: BluetoothPermissionRequiredException) {
        Log.w(TAG, error.message.orEmpty())
        Result.failure(workDataOf("error" to error.message))
    } catch (error: IOException) {
        Log.w(TAG, "Bottle is temporarily unavailable", error)
        Result.retry()
    } catch (error: Exception) {
        Log.e(TAG, "Synchronization failed", error)
        Result.failure(workDataOf("error" to (error.message ?: "Synchronization failed")))
    }

    companion object {
        private const val TAG = "HidrateSparkSync"
        private const val PERIODIC_WORK_NAME = "hidratespark-direct-health-connect-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
