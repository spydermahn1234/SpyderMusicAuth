package com.spydermusic.auth

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CastToKodi — sends a play request to Kodi via JSON-RPC.
 *
 * Called when the user taps a Cast button injected into the YouTube Music
 * WebView by [CastJavascriptInterface].
 *
 * Kodi must have the web server enabled (Settings → Services → Control →
 * Allow remote control via HTTP) on port 8080 (default).  The SpyderMusic
 * addon is invoked via its plugin:// URL, which triggers the normal play
 * path including stream resolution, rate limiting, and scrobbling.
 *
 * plugin URL format (from utils.py song_url):
 *   plugin://plugin.audio.ytmusic.exp/?action=play_song
 *     &videoId=<id>&title=<enc>&artist=<enc>&albumart=&album=&duration=0&isVideo=False
 *
 * JSON-RPC call:
 *   Player.Open { item: { file: "<plugin_url>" } }
 *
 * The call is made on a background thread (connection from the HTTP server's
 * connection pool, invoked via [SpyderMusicApplication.castCallback]).
 * All errors are swallowed — if Kodi's web server is unreachable the cast
 * silently fails; the user sees nothing.  A toast on failure would require
 * a Context reference which is awkward here; revisit if UX feedback is needed.
 */
object CastToKodi {

    private const val TAG          = "CastToKodi"
    private const val KODI_JSONRPC = "http://127.0.0.1:8080/jsonrpc"
    private const val TIMEOUT_MS   = 3000
    private const val PLUGIN_BASE  = "plugin://plugin.audio.ytmusic.exp/"

    fun play(videoId: String, title: String, artist: String) {
        if (videoId.isBlank()) {
            Log.w(TAG, "play() called with blank videoId — ignoring")
            return
        }
        val pluginUrl = buildPluginUrl(videoId, title, artist)
        val body = """
            {
              "jsonrpc": "2.0",
              "method":  "Player.Open",
              "params":  { "item": { "file": "$pluginUrl" } },
              "id":      1
            }
        """.trimIndent()

        try {
            val conn = URL(KODI_JSONRPC).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod  = "POST"
                doOutput       = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val rc = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "Player.Open($videoId) → HTTP $rc")
        } catch (e: Exception) {
            // Kodi web server not reachable — expected when Kodi is not running
            Log.d(TAG, "Player.Open failed (Kodi unreachable?): $e")
        }
    }

    private fun buildPluginUrl(videoId: String, title: String, artist: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$PLUGIN_BASE?action=play_song" +
               "&videoId=${enc(videoId)}" +
               "&title=${enc(title)}" +
               "&artist=${enc(artist)}" +
               "&albumart=&album=&duration=0&isVideo=False"
    }
}
