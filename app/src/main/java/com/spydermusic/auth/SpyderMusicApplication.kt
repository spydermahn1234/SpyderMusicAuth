package com.spydermusic.auth

import android.app.Application
import android.util.Log

/**
 * SpyderMusicApplication — Application subclass.
 *
 * Responsibilities:
 *   1. Owns the [CompanionHttpServer] singleton so it persists across Activity
 *      recreation (e.g. screen rotation, backgrounding).
 *   2. Creates the notification channel for [CookieRefreshService] before any
 *      component calls startForegroundService() — the channel must exist at the
 *      point the notification is posted, not just when the service starts.
 *
 * Declared in AndroidManifest.xml via android:name=".SpyderMusicApplication".
 */
class SpyderMusicApplication : Application() {

    /** HTTP server singleton.  Started by MainActivity.onCreate(). */
    val httpServer = CompanionHttpServer(port = CompanionHttpServer.DEFAULT_PORT)

    override fun onCreate() {
        super.onCreate()
        // Create notification channel early — required before any foreground
        // service on API 26+.  Safe to call multiple times (idempotent).
        CookieRefreshService.createNotificationChannel(this)
        Log.i("SpyderMusicApp", "Application initialised")
    }

    override fun onTerminate() {
        // onTerminate() is only called in emulator/test environments — real
        // Android devices kill the process without calling this.  Still useful
        // for clean test teardown and emulator runs.
        httpServer.stop()
        Log.i("SpyderMusicApp", "Application terminating — HTTP server stopped")
        super.onTerminate()
    }
}
