package com.spydermusic.auth

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CookieRefreshWorker — WorkManager periodic worker for proactive cookie refresh.
 *
 * Scheduled every [REFRESH_INTERVAL_HOURS] hours when WiFi is connected.
 * On each run:
 *   1. Reads auth_meta.json to check when headers were last captured.
 *   2. If headers are older than [STALE_THRESHOLD_HOURS] hours, starts
 *      CookieRefreshService to load a headless WebView and capture fresh headers.
 *   3. If headers are still fresh, skips silently — no WebView spin-up needed.
 *
 * The WorkManager constraint (NETWORK_TYPE_UNMETERED) ensures refresh only
 * runs on WiFi, avoiding mobile data usage.  The 6-hour interval gives ~4
 * refresh opportunities per day, well within the 6–24h YouTube session cookie
 * lifetime.
 *
 * Scheduling is idempotent — call schedule() from any entry point;
 * ExistingPeriodicWorkPolicy.KEEP means a second call has no effect if a job
 * with the same name already exists in the queue.
 */
class CookieRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "CookieRefreshWorker"

        const val WORK_NAME               = "spydermusic_cookie_refresh"
        const val REFRESH_INTERVAL_HOURS  = 6L
        const val STALE_THRESHOLD_HOURS   = 5L   // refresh if last capture older than 5 h

        /**
         * Enqueue the periodic work request.  Safe to call multiple times —
         * KEEP policy means a running job is never replaced.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
                .build()

            val request = PeriodicWorkRequestBuilder<CookieRefreshWorker>(
                REFRESH_INTERVAL_HOURS, TimeUnit.HOURS,
                // Flex window: allow WorkManager to fire anywhere in the last
                // 30 min of the interval to batch with other work.
                30L, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Periodic work scheduled: every ${REFRESH_INTERVAL_HOURS}h on WiFi")
        }

        /**
         * Cancel the periodic work.  Call on sign-out or when the user disables
         * background refresh in settings.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Periodic work cancelled")
        }
    }

    override fun doWork(): Result {
        Log.i(TAG, "doWork() — checking header age")

        if (!needsRefresh()) {
            Log.i(TAG, "Headers are fresh — skipping refresh")
            return Result.success()
        }

        Log.i(TAG, "Headers are stale — starting CookieRefreshService")

        return try {
            val intent = Intent(context, CookieRefreshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "CookieRefreshService started")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CookieRefreshService: $e")
            // Retry — don't return failure, which would mark the job as permanently failed
            Result.retry()
        }
    }

    /**
     * Return true if auth_meta.json indicates the last capture was more than
     * [STALE_THRESHOLD_HOURS] hours ago, or if the file doesn't exist yet.
     */
    private fun needsRefresh(): Boolean {
        return try {
            val metaFile = File(MainActivity.PUBLIC_DIR, "auth_meta.json")
            if (!metaFile.exists()) {
                Log.d(TAG, "auth_meta.json not found — treating as stale")
                return true
            }
            val meta      = JSONObject(metaFile.readText())
            val writtenAt = meta.optLong("written_at", 0L)
            if (writtenAt == 0L) return true

            val ageHours = (System.currentTimeMillis() / 1000 - writtenAt) / 3600.0
            Log.d(TAG, "Header age: %.1f h (threshold: ${STALE_THRESHOLD_HOURS} h)".format(ageHours))
            ageHours >= STALE_THRESHOLD_HOURS
        } catch (e: Exception) {
            Log.w(TAG, "Could not read auth_meta.json: $e — treating as stale")
            true
        }
    }
}
