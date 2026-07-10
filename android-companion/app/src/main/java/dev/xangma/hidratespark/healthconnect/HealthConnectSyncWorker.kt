package dev.xangma.hidratespark.healthconnect

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
                "bottles" to summary.bottles,
                "sips" to summary.sips,
                "retention_gaps" to summary.retentionGaps,
            ),
        )
    } catch (error: HealthPermissionRequiredException) {
        Log.w(TAG, error.message.orEmpty())
        Result.failure(workDataOf("error" to error.message))
    } catch (error: IOException) {
        Log.w(TAG, "Temporary synchronization failure", error)
        Result.retry()
    } catch (error: Exception) {
        Log.e(TAG, "Synchronization failed", error)
        Result.failure(workDataOf("error" to (error.message ?: "Synchronization failed")))
    }

    companion object {
        private const val TAG = "HidrateSparkSync"
        private const val PERIODIC_WORK_NAME = "hidratespark-health-connect-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
