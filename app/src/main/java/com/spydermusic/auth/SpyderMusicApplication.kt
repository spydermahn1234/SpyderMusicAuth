package com.spydermusic.auth

import android.app.Application
import android.util.Log

/**
 * SpyderMusicApplication — Application subclass.
 *
 * Owns three singletons that must survive Activity recreation:
 *   1. [CompanionHttpServer]  — HTTP server on 127.0.0.1:52080
 *   2. [MediaSessionManager] — Android MediaSession (lock screen / BT controls)
 *
 * The HTTP server's /nowplaying callback is wired to the MediaSession here
 * so both objects are configured in one place and neither depends on the
 * Activity lifecycle.
 *
 * Declared in AndroidManifest.xml via android:name=".SpyderMusicApplication".
 */
class SpyderMusicApplication : Application() {

    /** HTTP server singleton — started by MainActivity.onCreate() (idempotent). */
    val httpServer = CompanionHttpServer(port = CompanionHttpServer.DEFAULT_PORT)

    /** MediaSession singleton — surfaces Now Playing on lock screen / BT remotes. */
    lateinit var mediaSession: MediaSessionManager

    override fun onCreate() {
        super.onCreate()

        // Notification channel must exist before any startForegroundService() call.
        CookieRefreshService.createNotificationChannel(this)

        // Create the MediaSession
        mediaSession = MediaSessionManager(applicationContext)

        // Wire HTTP server /nowplaying POST → MediaSession
        // Kodi's now_playing_broadcaster.py posts here on every track change,
        // pause, and stop. The callback runs on the HTTP server's connection
        // thread pool, so MediaSessionManager.update() must be thread-safe
        // (it is — all work is dispatched to its internal single-threaded executor).
        httpServer.nowPlayingCallback = { payload ->
            when {
                payload.clear || (!payload.playing && payload.videoId.isEmpty()) ->
                    mediaSession.stop()

                !payload.playing ->
                    mediaSession.setPaused((payload.position * 1000.0).toLong())

                else ->
                    mediaSession.update(
                        videoId      = payload.videoId,
                        title        = payload.title,
                        artist       = payload.artist,
                        album        = payload.album,
                        durationMs   = payload.duration * 1000L,
                        thumbnailUrl = payload.thumbnail,
                        positionMs   = (payload.position * 1000.0).toLong(),
                        playing      = true,
                    )
            }
        }

        // Wire /cast POST → Kodi JSON-RPC Player.Open
        // Payload arrives from CastJavascriptInterface via MainActivity's
        // onCastRequested, which POSTs to /cast on the HTTP server.
        // The server invokes this callback on its connection thread.
        httpServer.castCallback = { payload ->
            CastToKodi.play(payload.videoId, payload.title, payload.artist)
        }

        Log.i("SpyderMusicApp", "Application initialised (HTTP port ${CompanionHttpServer.DEFAULT_PORT})")
    }

    override fun onTerminate() {
        // Called by emulator/test environments only — real devices kill the process.
        // Still useful for clean teardown in Robolectric / instrumented tests.
        httpServer.stop()
        mediaSession.release()
        Log.i("SpyderMusicApp", "Application terminating")
        super.onTerminate()
    }
}
