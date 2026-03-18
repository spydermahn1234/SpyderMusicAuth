package com.spydermusic.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
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
        const val DEBUG_LOG      = "$PUBLIC_DIR/spyderauth_debug.log"

        // Broadcast the addon listens for
        const val ACTION_AUTH_UPDATED = "com.spydermusic.AUTH_UPDATED"

        // YTMusic API hostname we intercept headers from
        const val TARGET_HOST = "music.youtube.com"

        // Headers we require in requestHeaders to consider an API call capturable.
        // NOTE: 'cookie' is intentionally absent — Android WebView strips cookies from
        // requestHeaders. We inject them from CookieManager in handleCapturedHeaders().
        val REQUIRED_HEADERS = setOf("x-goog-authuser")

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

        debugLog("=== SpyderMusic Auth started (v${BuildConfig.VERSION_NAME}) ===")
        debugLog("Android API: ${Build.VERSION.SDK_INT}")
        ensureStoragePermission()
        setupWebView()

        // Load YouTube Music
        webView.loadUrl("https://music.youtube.com")
    }

    override fun onResume() {
        super.onResume()
        // User may have just returned from the MANAGE_EXTERNAL_STORAGE settings page
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasPermission = Environment.isExternalStorageManager()
            debugLog("onResume: isExternalStorageManager=$hasPermission")
            if (hasPermission) {
                writeSentinel()
            }
        }
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasPermission = Environment.isExternalStorageManager()
            debugLog("ensureStoragePermission: API=${Build.VERSION.SDK_INT} isExternalStorageManager=$hasPermission")
            if (hasPermission) {
                writeSentinel()
            } else {
                debugLog("ensureStoragePermission: launching ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                setStatus("SpyderMusic Auth needs 'All files access' to share data with Kodi.\nTap OK in the next screen to grant it.", false)
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }, 2000)
            }
        } else {
            debugLog("ensureStoragePermission: API<30, using WRITE_EXTERNAL_STORAGE")
            writeSentinel()
        }
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
                val path = request.url.path ?: ""
                val url  = request.url.toString()

                // Log every music.youtube.com request so we can see what's happening
                if (host.endsWith(TARGET_HOST)) {
                    val headers = request.requestHeaders ?: emptyMap()
                    val lower   = headers.mapKeys { it.key.lowercase() }
                    val hasRequired = REQUIRED_HEADERS.all { it in lower }
                    val isApiCall   = path.contains("/youtubei/")

                    debugLog("INTERCEPT host=$host path=$path isApiCall=$isApiCall hasRequired=$hasRequired headers=${lower.keys}")

                    if (isApiCall && hasRequired) {
                        debugLog("INTERCEPT match — capturing headers")
                        handleCapturedHeaders(headers)
                    } else if (isApiCall && !hasRequired) {
                        val missing = REQUIRED_HEADERS.filter { it !in lower }
                        debugLog("INTERCEPT api call but missing headers: $missing (present: ${lower.keys})")
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                debugLog("PAGE_FINISHED url=$url")
                if (url.contains(TARGET_HOST)) {
                    val cookieStr = CookieManager.getInstance().getCookie(url)
                    debugLog("PAGE_FINISHED cookies=${if (cookieStr.isNullOrEmpty()) "NONE" else "present (${cookieStr.length} chars)"}")
                    if (!cookieStr.isNullOrEmpty() && capturedHeaders == null) {
                        tryBuildFromWebViewCookies(cookieStr, url)
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                debugLog("PAGE_ERROR url=${request.url} code=${error.errorCode} desc=${error.description}")
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
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60 && capturedHeaders != null) {
            debugLog("handleCapturedHeaders: skipped (dedup, last write ${now - lastWriteTime}s ago)")
            return
        }

        // Inject cookie from CookieManager — WebView never exposes it in requestHeaders
        val cookieStr = CookieManager.getInstance().getCookie("https://music.youtube.com")
        if (cookieStr.isNullOrEmpty()) {
            debugLog("handleCapturedHeaders: no cookie from CookieManager — skipping")
            return
        }

        val mergedHeaders = headers.toMutableMap()
        mergedHeaders["cookie"] = cookieStr

        lastWriteTime = now
        capturedHeaders = mergedHeaders
        debugLog("handleCapturedHeaders: writing ${mergedHeaders.size} headers (cookie injected, ${cookieStr.length} chars): ${mergedHeaders.keys}")

        val sb = StringBuilder()
        for ((k, v) in mergedHeaders) {
            sb.append("$k: $v\n")
        }
        writeHeadersFile(sb.toString(), mergedHeaders)
    }

    private fun tryBuildFromWebViewCookies(cookieStr: String, url: String) {
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60) {
            debugLog("tryBuildFromWebViewCookies: skipped (dedup)")
            return
        }
        debugLog("tryBuildFromWebViewCookies: building from cookies (${cookieStr.length} chars)")

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

        lastWriteTime = now
        writeHeadersFile(sb.toString(), pseudoHeaders)
    }

    /**
     * Append a timestamped line to spyderauth_debug.log in the public SpyderMusic dir.
     * Safe to call from any thread. Fails silently if storage isn't ready yet.
     */
    private fun debugLog(msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$ts  $msg\n"
            val logFile = File(DEBUG_LOG)
            logFile.parentFile?.mkdirs()
            FileWriter(logFile, true).use { it.write(line) }
        } catch (_: Exception) {}
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
                debugLog("SENTINEL written: $SENTINEL_FILE")
            } else {
                debugLog("SENTINEL already exists: $SENTINEL_FILE")
            }
        } catch (e: Exception) {
            debugLog("SENTINEL write FAILED: $e")
        }
    }

    private fun writeHeadersFile(rawHeaders: String, headers: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            debugLog("writeHeadersFile: BLOCKED — no MANAGE_EXTERNAL_STORAGE permission")
            runOnUiThread {
                setStatus("Storage permission not granted. Please reopen the app and allow 'All files access'.", true)
            }
            return
        }
        try {
            // PRIMARY: write to public SpyderMusic directory — always accessible
            // regardless of Android version. The addon checks this path first.
            val publicFile = File(PUBLIC_HEADERS)
            publicFile.parentFile?.mkdirs()
            publicFile.writeText(rawHeaders, Charsets.UTF_8)
            debugLog("writeHeadersFile: wrote public path OK — ${publicFile.absolutePath} (${rawHeaders.length} bytes)")

            // SECONDARY: attempt write to Kodi's addon data directory.
            // This will fail on Android 11+ (EACCES — /Android/data/ is excluded
            // from MANAGE_EXTERNAL_STORAGE) but succeeds on older devices.
            try {
                val destFile = File(HEADERS_FILE)
                destFile.parentFile?.mkdirs()
                destFile.writeText(rawHeaders, Charsets.UTF_8)
                debugLog("writeHeadersFile: wrote Kodi path OK — ${destFile.absolutePath}")
            } catch (e: Exception) {
                debugLog("writeHeadersFile: Kodi path write skipped (expected on Android 11+) — $e")
            }

            // Metadata sidecar alongside the public file
            val meta = JSONObject().apply {
                put("written_at",    System.currentTimeMillis() / 1000)
                put("written_by",    "SpyderMusicAuth")
                put("app_version",   BuildConfig.VERSION_NAME)
                put("header_count",  headers.size)
            }
            File(publicFile.parent, "auth_meta.json").writeText(meta.toString(2))
            debugLog("writeHeadersFile: meta written OK")

            sendAuthBroadcast(publicFile.absolutePath)
            debugLog("writeHeadersFile: broadcast sent")

            runOnUiThread { showSuccess() }

        } catch (e: Exception) {
            debugLog("writeHeadersFile: EXCEPTION — $e")
            runOnUiThread {
                setStatus("Write failed: ${e.message}", true)
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
