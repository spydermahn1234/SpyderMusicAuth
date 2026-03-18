package com.spydermusic.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * SpyderMusic Auth — companion app for SpyderMusic Kodi addon.
 *
 * Hosts a WebView pointed at music.youtube.com. On every request to the
 * YTMusic API we intercept the headers (Cookie, Authorization, x-goog-*)
 * and write them as headers_auth.json to the Kodi addon data directory.
 * The addon is then notified via a broadcast intent to reload its session.
 *
 * File written: /storage/emulated/0/Android/data/org.xbmc.kodi/files/
 *               .kodi/userdata/addon_data/plugin.audio.ytmusic.exp/headers_auth.json
 *
 * Intent broadcast: com.spydermusic.AUTH_UPDATED
 *   extras: "headers_path" — absolute path to the written file
 *           "timestamp"    — epoch seconds of the write
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Agreed path — must match _headers_path() in cookie_wizard.py
        const val HEADERS_FILE = "/storage/emulated/0/Android/data/org.xbmc.kodi" +
                "/files/.kodi/userdata/addon_data/plugin.audio.ytmusic.exp/headers_auth.json"

        // Public directory readable by Kodi despite Android 11+ scoped storage.
        // Kodi cannot see /Android/data/<other-pkg>/, so we mirror files here.
        const val PUBLIC_DIR     = "/storage/emulated/0/SpyderMusic"
        const val PUBLIC_HEADERS = "$PUBLIC_DIR/headers_auth.json"
        const val SENTINEL_FILE  = "$PUBLIC_DIR/.companion_installed"

        // Broadcast the addon listens for
        const val ACTION_AUTH_UPDATED = "com.spydermusic.AUTH_UPDATED"

        // YTMusic API hostname we intercept headers from
        const val TARGET_HOST = "music.youtube.com"

        // Headers ytmusicapi needs to see to consider the file valid
        val REQUIRED_HEADERS = setOf("cookie", "x-goog-authuser")

        // Full Chrome UA so YouTube Music serves the full web app, not a mobile redirect
        const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var statusCard: View
    private lateinit var successCard: View

    private var capturedHeaders: Map<String, String>? = null
    private var lastWriteTime: Long = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText  = findViewById(R.id.status_text)
        statusCard  = findViewById(R.id.status_card)
        successCard = findViewById(R.id.success_card)

        writeSentinel()
        setupWebView()

        // Load YouTube Music
        webView.loadUrl("https://music.youtube.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            databaseEnabled        = true
            allowContentAccess     = true
            loadWithOverviewMode   = true
            useWideViewPort        = true
            userAgentString        = CHROME_UA
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null

                // Only intercept API calls to music.youtube.com
                if (host.endsWith(TARGET_HOST) && request.url.path?.contains("/youtubei/") == true) {
                    val headers = request.requestHeaders ?: emptyMap()
                    val lower   = headers.mapKeys { it.key.lowercase() }

                    // Check we have the headers we need
                    if (REQUIRED_HEADERS.all { it in lower }) {
                        handleCapturedHeaders(headers)
                    }
                }
                return null  // Let the request proceed normally
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Inject cookies from CookieManager into captured headers
                // for any page on music.youtube.com
                if (url.contains(TARGET_HOST)) {
                    val cookieStr = CookieManager.getInstance().getCookie(url)
                    if (!cookieStr.isNullOrEmpty() && capturedHeaders == null) {
                        // Build minimal header set from WebView cookies
                        tryBuildFromWebViewCookies(cookieStr, url)
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    setStatus("Loading… $newProgress%", false)
                } else {
                    setStatus("Sign in to YouTube Music, then play any song", false)
                }
            }
        }
    }

    private fun handleCapturedHeaders(headers: Map<String, String>) {
        // Deduplicate — don't write more than once per 60 seconds
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60 && capturedHeaders != null) return
        lastWriteTime = now

        capturedHeaders = headers

        // Build the raw headers string in the format ytmusicapi expects:
        // "Header-Name: value\nHeader-Name: value\n..."
        val sb = StringBuilder()
        for ((k, v) in headers) {
            sb.append("$k: $v\n")
        }

        writeHeadersFile(sb.toString(), headers)
    }

    private fun tryBuildFromWebViewCookies(cookieStr: String, url: String) {
        // Fallback: build a minimal headers string from WebView CookieManager
        // This covers the case where the API intercept hasn't fired yet
        val uri    = Uri.parse(url)
        val origin = "${uri.scheme}://${uri.host}"

        val sb = StringBuilder()
        sb.append("cookie: $cookieStr\n")
        sb.append("x-goog-authuser: 0\n")
        sb.append("origin: $origin\n")
        sb.append("x-origin: $origin\n")

        val pseudoHeaders = mapOf(
            "cookie"          to cookieStr,
            "x-goog-authuser" to "0",
            "origin"          to origin,
        )

        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60) return
        lastWriteTime = now

        writeHeadersFile(sb.toString(), pseudoHeaders)
    }

    /**
     * Write a presence sentinel to the public SpyderMusic directory so that
     * Kodi's companion_app_installed() check can find it.  Android 11+ scoped
     * storage blocks Kodi from reading /Android/data/<pkg>/, but the public
     * /storage/emulated/0/SpyderMusic/ directory is accessible to both apps.
     */
    private fun writeSentinel() {
        try {
            val sentinel = File(SENTINEL_FILE)
            if (!sentinel.exists()) {
                sentinel.parentFile?.mkdirs()
                sentinel.createNewFile()
            }
        } catch (_: Exception) {
            // Non-fatal — public headers fallback in writeHeadersFile() covers this
        }
    }

    private fun writeHeadersFile(rawHeaders: String, headers: Map<String, String>) {
        try {
            val destFile = File(HEADERS_FILE)
            destFile.parentFile?.mkdirs()

            // Write raw headers format — ytmusicapi's setup_browser() reads this
            destFile.writeText(rawHeaders, Charsets.UTF_8)

            // Mirror to the public SpyderMusic directory so Kodi can detect
            // the companion app even when Kodi's own data dir doesn't exist yet
            // (Android 11+ scoped storage blocks cross-app /Android/data/ reads)
            try {
                val publicFile = File(PUBLIC_HEADERS)
                publicFile.parentFile?.mkdirs()
                publicFile.writeText(rawHeaders, Charsets.UTF_8)
            } catch (_: Exception) {
                // Non-fatal — Kodi path write above is the primary target
            }

            // Also write a metadata sidecar so the addon can check freshness
            val meta = JSONObject().apply {
                put("written_at",    System.currentTimeMillis() / 1000)
                put("written_by",    "SpyderMusicAuth")
                put("app_version",   BuildConfig.VERSION_NAME)
                put("header_count",  headers.size)
            }
            File(destFile.parent, "auth_meta.json").writeText(meta.toString(2))

            // Notify Kodi addon via broadcast
            sendAuthBroadcast(destFile.absolutePath)

            runOnUiThread { showSuccess() }

        } catch (e: Exception) {
            runOnUiThread {
                setStatus("Write failed: ${e.message}\n\nCheck Kodi is installed and has storage permission.", true)
            }
        }
    }

    private fun sendAuthBroadcast(headersPath: String) {
        val intent = Intent(ACTION_AUTH_UPDATED).apply {
            putExtra("headers_path", headersPath)
            putExtra("timestamp",    System.currentTimeMillis() / 1000)
            // Target Kodi explicitly so Android 14+ explicit broadcast rules are satisfied
            setPackage("org.xbmc.kodi")
        }
        try {
            sendBroadcast(intent)
        } catch (e: Exception) {
            // Kodi may not be running — file write is sufficient, addon will
            // detect the updated file on next launch
        }
    }

    private fun showSuccess() {
        statusCard.visibility  = View.GONE
        successCard.visibility = View.VISIBLE

        // Auto-dismiss after 3 seconds, return to webview in case user wants
        // to trigger another capture
        Handler(Looper.getMainLooper()).postDelayed({
            successCard.visibility = View.GONE
            statusCard.visibility  = View.VISIBLE
            setStatus("Session saved ✓  You can close this app", false)
        }, 3000)
    }

    private fun setStatus(msg: String, isError: Boolean) {
        runOnUiThread {
            statusText.text = msg
            statusCard.setBackgroundResource(
                if (isError) R.drawable.card_error else R.drawable.card_status
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
