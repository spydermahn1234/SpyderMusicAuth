package com.spydermusic.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
 * and write them as headers_auth.json to a shared public directory that
 * both this app and Kodi can access on Android 11+.
 *
 * On Android 11+, /Android/data/<other-pkg>/ is excluded from
 * MANAGE_EXTERNAL_STORAGE scope, so we write exclusively to:
 *   /storage/emulated/0/SpyderMusic/headers_auth.json
 *
 * SpyderMusic's AuthReceiver polls and imports from that path.
 *
 * Requires: minSdk 30 (Android 11)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Public directory accessible to both this app and Kodi on Android 11+.
        // /Android/data/<other-pkg>/ is excluded from MANAGE_EXTERNAL_STORAGE,
        // so we never attempt to write directly into Kodi's data directory.
        const val PUBLIC_DIR     = "/storage/emulated/0/SpyderMusic"
        const val PUBLIC_HEADERS = "$PUBLIC_DIR/headers_auth.json"
        const val SENTINEL_FILE  = "$PUBLIC_DIR/.companion_installed"
        const val DEBUG_LOG      = "$PUBLIC_DIR/spyderauth_debug.log"

        // Broadcast the addon listens for
        const val ACTION_AUTH_UPDATED = "com.spydermusic.AUTH_UPDATED"

        // YTMusic API hostname we intercept headers from
        const val TARGET_HOST = "music.youtube.com"

        // Headers we require in requestHeaders to consider an API call capturable.
        // 'cookie' is intentionally absent — WebView strips cookies from
        // requestHeaders; we inject them from CookieManager instead.
        val REQUIRED_HEADERS = setOf("x-goog-authuser")

        // Full Chrome UA so YouTube Music serves the full web app
        const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
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

        // Android 11+ (API 30+): set status/nav bar appearance via
        // WindowInsetsController — the XML style approach still works but
        // this is the correct API from API 30 onward.
        window.insetsController?.let { controller ->
            controller.setSystemBarsAppearance(
                0,   // dark icons off (we have a dark background)
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        debugLog("=== SpyderMusic Auth started (v${BuildConfig.VERSION_NAME}) ===")
        debugLog("Android API: ${android.os.Build.VERSION.SDK_INT}")

        registerBackPressedCallback()
        ensureStoragePermission()
        setupWebView()

        webView.loadUrl("https://music.youtube.com")
    }

    /**
     * Use OnBackPressedDispatcher (replaces deprecated onBackPressed override).
     * Registered once in onCreate — the callback intercepts back presses and
     * navigates the WebView history before falling through to the system.
     */
    private fun registerBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Disable this callback so the system default (finish) fires
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // User may have returned from the MANAGE_EXTERNAL_STORAGE settings page
        if (Environment.isExternalStorageManager()) {
            debugLog("onResume: isExternalStorageManager=true")
            writeSentinel()
        } else {
            debugLog("onResume: isExternalStorageManager=false — storage not yet granted")
        }
    }

    /**
     * On Android 11+ the only way to write outside our own sandbox to a shared
     * public folder is MANAGE_EXTERNAL_STORAGE ("All files access").
     * Direct the user to the system settings page to grant it.
     */
    private fun ensureStoragePermission() {
        debugLog("ensureStoragePermission: isExternalStorageManager=${Environment.isExternalStorageManager()}")
        if (Environment.isExternalStorageManager()) {
            writeSentinel()
        } else {
            setStatus(
                "SpyderMusic Auth needs 'All files access' to share data with Kodi.\n" +
                "Tap OK on the next screen to grant it.",
                isError = false
            )
            debugLog("ensureStoragePermission: directing user to ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }, 2000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            allowContentAccess   = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            userAgentString      = CHROME_UA
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

                if (host.endsWith(TARGET_HOST)) {
                    val headers  = request.requestHeaders ?: emptyMap()
                    val lower    = headers.mapKeys { it.key.lowercase() }
                    val hasReqd  = REQUIRED_HEADERS.all { it in lower }
                    val isApi    = path.contains("/youtubei/")

                    debugLog("INTERCEPT host=$host path=$path isApi=$isApi hasReqd=$hasReqd headers=${lower.keys}")

                    if (isApi && hasReqd) {
                        debugLog("INTERCEPT match — capturing headers")
                        handleCapturedHeaders(headers)
                    } else if (isApi) {
                        val missing = REQUIRED_HEADERS.filter { it !in lower }
                        debugLog("INTERCEPT api call but missing headers: $missing")
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

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                // errorCode and description are safe — minSdk 30 >= required API 23
                debugLog("PAGE_ERROR url=${request.url} code=${error.errorCode} desc=${error.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    setStatus("Loading… $newProgress%", isError = false)
                } else {
                    setStatus("Sign in to YouTube Music, then play any song", isError = false)
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

        val merged = headers.toMutableMap()
        merged["cookie"] = cookieStr

        lastWriteTime   = now
        capturedHeaders = merged

        debugLog("handleCapturedHeaders: writing ${merged.size} headers (cookie injected, ${cookieStr.length} chars): ${merged.keys}")

        val sb = StringBuilder()
        for ((k, v) in merged) sb.append("$k: $v\n")
        writeHeadersFile(sb.toString(), merged)
    }

    private fun tryBuildFromWebViewCookies(cookieStr: String, url: String) {
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60) {
            debugLog("tryBuildFromWebViewCookies: skipped (dedup)")
            return
        }
        debugLog("tryBuildFromWebViewCookies: building minimal headers from cookies (${cookieStr.length} chars)")

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

    private fun writeHeadersFile(rawHeaders: String, headers: Map<String, String>) {
        if (!Environment.isExternalStorageManager()) {
            debugLog("writeHeadersFile: BLOCKED — MANAGE_EXTERNAL_STORAGE not granted")
            runOnUiThread {
                setStatus(
                    "Storage permission not granted.\n" +
                    "Reopen the app and allow 'All files access' to continue.",
                    isError = true
                )
            }
            return
        }

        try {
            // Write to the shared public directory. On Android 11+,
            // MANAGE_EXTERNAL_STORAGE grants access here but NOT to
            // /Android/data/<other-pkg>/ — so this is the only viable path.
            val publicFile = File(PUBLIC_HEADERS)
            publicFile.parentFile?.mkdirs()
            publicFile.writeText(rawHeaders, Charsets.UTF_8)
            debugLog("writeHeadersFile: wrote ${publicFile.absolutePath} (${rawHeaders.length} bytes)")

            // Metadata sidecar
            val meta = JSONObject().apply {
                put("written_at",   System.currentTimeMillis() / 1000)
                put("written_by",   "SpyderMusicAuth")
                put("app_version",  BuildConfig.VERSION_NAME)
                put("header_count", headers.size)
            }
            File(publicFile.parent, "auth_meta.json").writeText(meta.toString(2))
            debugLog("writeHeadersFile: meta written")

            sendAuthBroadcast(publicFile.absolutePath)
            debugLog("writeHeadersFile: broadcast sent")

            runOnUiThread { showSuccess() }

        } catch (e: Exception) {
            debugLog("writeHeadersFile: EXCEPTION — $e")
            runOnUiThread { setStatus("Write failed: ${e.message}", isError = true) }
        }
    }

    private fun sendAuthBroadcast(headersPath: String) {
        // Target Kodi explicitly — satisfies Android 14+ explicit broadcast rules
        // for cross-app custom-action intents. Kodi may not be running; the file
        // write is sufficient, and the addon will detect it on next launch.
        val intent = Intent(ACTION_AUTH_UPDATED).apply {
            putExtra("headers_path", headersPath)
            putExtra("timestamp",    System.currentTimeMillis() / 1000)
            setPackage("org.xbmc.kodi")
        }
        try {
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    /** Write a timestamped line to spyderauth_debug.log. Thread-safe, fails silently. */
    private fun debugLog(msg: String) {
        try {
            val ts   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$ts  $msg\n"
            val f    = File(DEBUG_LOG)
            f.parentFile?.mkdirs()
            FileWriter(f, true).use { it.write(line) }
        } catch (_: Exception) {}
    }

    /**
     * Write a presence sentinel so Kodi's companion_app_installed() can detect us.
     * /storage/emulated/0/SpyderMusic/ is visible to both apps on Android 11+
     * (unlike /Android/data/<pkg>/ which is scoped).
     */
    private fun writeSentinel() {
        try {
            val f = File(SENTINEL_FILE)
            f.parentFile?.mkdirs()
            if (!f.exists()) {
                f.createNewFile()
                debugLog("SENTINEL written: $SENTINEL_FILE")
            } else {
                debugLog("SENTINEL already exists: $SENTINEL_FILE")
            }
        } catch (e: Exception) {
            debugLog("SENTINEL write FAILED: $e")
        }
    }

    private fun showSuccess() {
        statusCard.visibility  = View.GONE
        successCard.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            successCard.visibility = View.GONE
            statusCard.visibility  = View.VISIBLE
            setStatus("Session saved ✓  You can close this app", isError = false)
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

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
