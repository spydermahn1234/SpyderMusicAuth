package com.spydermusic.auth

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import java.net.URL
import java.util.concurrent.Executors

/**
 * MediaSessionManager — Android MediaSession for SpyderMusic playback state.
 *
 * Maintains a [MediaSessionCompat] that surfaces the current track on:
 *   - Android lock screen (artwork, title, artist, transport controls)
 *   - Notification shade media card
 *   - Bluetooth remote controls (headphones, car stereo, smart speakers)
 *
 * Transport controls (play/pause/next/prev/stop) are forwarded to Kodi via
 * JSON-RPC on 127.0.0.1:8080 — the default Kodi web server port.
 *
 * Lifecycle: owned by [SpyderMusicApplication].  Call [update] when track
 * changes and [setPlaying]/[setPaused]/[stop] for transport state changes.
 * Call [release] when the Application is terminating.
 *
 * Threading: [update] and state methods are safe to call from any thread;
 * they post work to a single-threaded executor internally.
 */
class MediaSessionManager(private val context: Context) {

    companion object {
        private const val TAG          = "MediaSessionManager"
        private const val SESSION_TAG  = "SpyderMusicSession"
        private const val KODI_HOST    = "127.0.0.1"
        private const val KODI_PORT    = 8080
        private const val KODI_JSONRPC = "http://$KODI_HOST:$KODI_PORT/jsonrpc"
        private const val HTTP_TIMEOUT = 2000   // ms — loopback, so short is fine
    }

    private val session: MediaSessionCompat = MediaSessionCompat(context, SESSION_TAG)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpyderMediaSession").apply { isDaemon = true }
    }

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay()     = kodiAction("Input.ExecuteAction", "play")
            override fun onPause()    = kodiAction("Input.ExecuteAction", "pause")
            override fun onStop()     = kodiAction("Player.Stop", null)
            override fun onSkipToNext()     = kodiAction("Input.ExecuteAction", "skipnext")
            override fun onSkipToPrevious() = kodiAction("Input.ExecuteAction", "skipprevious")
        })

        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        // Set initial stopped state
        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        session.setPlaybackState(state)
        session.isActive = true

        Log.i(TAG, "MediaSession initialised")
    }

    /**
     * Update metadata and set playing state.
     *
     * @param videoId   YouTube Music video ID (used as media ID)
     * @param title     Track title
     * @param artist    Artist name
     * @param album     Album title (may be empty)
     * @param durationMs Track duration in milliseconds
     * @param thumbnailUrl HTTPS URL of album art (fetched on background thread)
     * @param positionMs  Current playback position in milliseconds
     */
    fun update(
        videoId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        thumbnailUrl: String,
        positionMs: Long = 0L,
        playing: Boolean = true
    ) {
        executor.submit {
            try {
                // Fetch album art synchronously on the executor thread
                val bitmap: Bitmap? = fetchBitmapOrNull(thumbnailUrl)

                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, videoId)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,    title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,   artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,    album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,   durationMs)
                    .apply { if (bitmap != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap) }
                    .build()

                session.setMetadata(metadata)

                val stateCode = if (playing) PlaybackStateCompat.STATE_PLAYING
                                else         PlaybackStateCompat.STATE_PAUSED
                val pbState = PlaybackStateCompat.Builder()
                    .setState(stateCode, positionMs, 1f)
                    .setActions(ALL_TRANSPORT_ACTIONS)
                    .build()
                session.setPlaybackState(pbState)
                session.isActive = true

                Log.d(TAG, "update: $artist — $title (playing=$playing)")
            } catch (e: Exception) {
                Log.w(TAG, "update failed: $e")
            }
        }
    }

    fun setPlaying(positionMs: Long = 0L) {
        executor.submit {
            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, positionMs, 1f)
                .setActions(ALL_TRANSPORT_ACTIONS)
                .build()
            session.setPlaybackState(state)
        }
    }

    fun setPaused(positionMs: Long = 0L) {
        executor.submit {
            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, positionMs, 1f)
                .setActions(ALL_TRANSPORT_ACTIONS)
                .build()
            session.setPlaybackState(state)
        }
    }

    fun stop() {
        executor.submit {
            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .build()
            session.setPlaybackState(state)
            session.isActive = false
            session.setMetadata(null)
            Log.d(TAG, "stop: MediaSession cleared")
        }
    }

    fun release() {
        executor.submit {
            try {
                session.isActive = false
                session.release()
                Log.i(TAG, "MediaSession released")
            } catch (_: Exception) {}
        }
        executor.shutdown()
    }

    // ── Kodi transport control forwarding ───────────────────────────────────

    private fun kodiAction(method: String, action: String?) {
        executor.submit {
            try {
                val params = if (action != null)
                    "\"params\":{\"action\":\"$action\"}"
                else
                    "\"params\":{\"playerid\":0}"
                val body = "{\"jsonrpc\":\"2.0\",\"method\":\"$method\",$params,\"id\":1}"

                val url  = java.net.URL(KODI_JSONRPC)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.apply {
                    requestMethod       = "POST"
                    doOutput            = true
                    connectTimeout      = HTTP_TIMEOUT
                    readTimeout         = HTTP_TIMEOUT
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val rc = conn.responseCode
                conn.disconnect()
                Log.d(TAG, "Kodi $method($action) → HTTP $rc")
            } catch (e: Exception) {
                Log.w(TAG, "Kodi transport action failed: $e")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fetchBitmapOrNull(url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val stream = URL(url).openStream()
            BitmapFactory.decodeStream(stream).also { stream.close() }
        } catch (e: Exception) {
            Log.d(TAG, "Thumbnail fetch failed ($url): $e")
            null
        }
    }

    private companion object {
        val ALL_TRANSPORT_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY         or
            PlaybackStateCompat.ACTION_PAUSE        or
            PlaybackStateCompat.ACTION_PLAY_PAUSE   or
            PlaybackStateCompat.ACTION_STOP         or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    }
}
