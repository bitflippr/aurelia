package com.aurelia.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.syncLibrarySmart
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic library sync.
 * Uses WorkManager for reliable scheduling and runs on WiFi-only by default.
 */
class SyncWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {
      Log.d(TAG, "Starting background library sync...")

      try {
        val sessionStore = SessionStore(applicationContext)

        val serverUrl = sessionStore.getServerUrl()
        val userId = sessionStore.getUserId()
        val token = sessionStore.getToken()
        val appDataDir = sessionStore.getAppDataDir()

        if (serverUrl.isNullOrBlank() || userId.isNullOrBlank() || token.isNullOrBlank()) {
          Log.w(TAG, "Missing session data, skipping sync")
          return@withContext Result.success()
        }

        // Perform smart sync (paginated + incremental)
        val report = syncLibrarySmart(serverUrl, token, userId, appDataDir ?: "")

        Log.d(
          TAG,
          "Background sync completed: full=${report.fullSync}, songs=${report.songsUpdated}, artists=${report.artistsUpdated}, albums=${report.albumsUpdated}, duration=${report.durationMs}ms",
        )
        Result.success()
      } catch (e: Exception) {
        // Check if database is already in use (app is in foreground)
        val message = e.message ?: ""
        if (message.contains("Database already initialized") || message.contains("DatabaseAlreadyOpen")) {
          Log.d(TAG, "Database in use by app, skipping background sync (app will sync itself)")
          return@withContext Result.success()
        }

        Log.e(TAG, "Background sync failed", e)
        if (runAttemptCount < 3) {
          Result.retry()
        } else {
          Result.failure()
        }
      }
    }

  companion object {
    private const val TAG = "SyncWorker"
    private const val WORK_NAME = "library_sync"

    /**
     * Schedule periodic sync with WiFi-only constraint.
     * @param context Application context
     * @param intervalHours Hours between syncs (default 24 = daily)
     */
    fun schedule(
      context: Context,
      intervalHours: Long = 24,
    ) {
      val constraints =
        Constraints
          .Builder()
          .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
          .setRequiresBatteryNotLow(true)
          .build()

      val workRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(
          intervalHours,
          TimeUnit.HOURS,
        ).setConstraints(constraints)
          .build()

      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest,
      )

      Log.d(TAG, "Scheduled periodic sync every $intervalHours hours (WiFi only)")
    }

    /**
     * Cancel any scheduled sync work.
     */
    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
      Log.d(TAG, "Cancelled periodic sync")
    }
  }
}
