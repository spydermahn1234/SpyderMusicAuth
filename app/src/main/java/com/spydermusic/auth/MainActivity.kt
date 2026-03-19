package com.spydermusic.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * SpyderMusic Auth — companion app for SpyderMusic Kodi addon.
 *
 * Hosts a WebView pointed at music.youtube.com.  On every YTMusic API request
 * we intercept headers (Cookie, x-goog-*) and write them as headers_auth.json
 * to /storage/emulated/0/SpyderMusic/ — a public directory both this app and
 * Kodi can access on all Android versions.
 *
 * Storage strategy by API level:
 *   API 21-28  WRITE_EXTERNAL_STORAGE.  Runtime request on API 23+.
 *   API 29     WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage="true".
 *   API 30+    MANAGE_EXTERNAL_STORAGE ("All files access") via system settings.
 *
 * minSdk 21 — covers all Shield TV firmware versions (2015 model to present).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PUBLIC_DIR     = "/storage/emulated/0/SpyderMusic"
        const val PUBLIC_HEADERS = "$PUBLIC_DIR/headers_auth.json"
        const val SENTINEL_FILE  = "$PUBLIC_DIR/.companion_installed"
        const val DEBUG_LOG      = "$PUBLIC_DIR/spyderauth_debug.log"

        const val ACTION_AUTH_UPDATED = "com.spydermusic.AUTH_UPDATED"
        const val TARGET_HOST         = "music.youtube.com"

        // 'cookie' excluded — WebView strips it from requestHeaders;
        // injected from CookieManager in handleCapturedHeaders().
        val REQUIRED_HEADERS = setOf("x-goog-authuser")

        const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 9; Pixel 3) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var statusCard: View
    private lateinit var successCard: View

    private var capturedHeaders: Map<String, String>? = null
    private var lastWriteTime: Long = 0

    // Runtime permission launcher (API 23+, used for API 23-29)
    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            debugLog("WRITE_EXTERNAL_STORAGE granted=$granted")
            if (granted) {
                writeSentinel()
            } else {
                setStatus(
                    "Storage permission denied.\n" +
                    "Grant storage access so SpyderMusic Auth can share cookies with Kodi.",
                    isError = true
                )
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText  = findViewById(R.id.status_text)
        statusCard  = findViewById(R.id.status_card)
        successCard = findViewById(R.id.success_card)

        // Status/nav bar appearance — API 30+ only, guarded at call site
        applyWindowInsets()

        debugLog("=== SpyderMusic Auth started (v${BuildConfig.VERSION_NAME}) ===")
        debugLog("Android API: ${Build.VERSION.SDK_INT}")

        registerBackPressedCallback()
        ensureStoragePermission()
        setupWebView()

        webView.loadUrl("https://music.youtube.com")
    }

    /**
     * Apply dark status/navigation bar appearance.
     * Guarded to API 30+ to avoid importing WindowInsetsController on older devices.
     */
    private fun applyWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    private fun registerBackPressedCallback() {
        // OnBackPressedCallback replaces deprecated onBackPressed() override.
        // Works from API 21 via the androidx.activity backport.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // User may have returned from the MANAGE_EXTERNAL_STORAGE settings screen
        if (hasStorageAccess()) {
            debugLog("onResume: storage access OK")
            writeSentinel()
        } else {
            debugLog("onResume: storage access not yet granted (API ${Build.VERSION.SDK_INT})")
        }
    }

    /**
     * Returns true if the app currently has the storage access it needs
     * to write to the public SpyderMusic directory for this API level.
     */
    private fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            // API 30+: requires MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            // API 23-29: requires WRITE_EXTERNAL_STORAGE runtime grant
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        else ->
            // API 21-22: WRITE_EXTERNAL_STORAGE granted at install time, always available
            true
    }

    private fun ensureStoragePermission() {
        debugLog("ensureStoragePermission: API=${Build.VERSION.SDK_INT} hasAccess=${hasStorageAccess()}")
        if (hasStorageAccess()) {
            writeSentinel()
            return
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // API 30+: send to system "All files access" settings
                setStatus(
                    "SpyderMusic Auth needs 'All files access' to share data with Kodi.\n" +
                    "Tap OK on the next screen to grant it.",
                    isError = false
                )
                debugLog("Directing to ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }, 2000)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // API 23-29: standard runtime permission request
                debugLog("Requesting WRITE_EXTERNAL_STORAGE")
                requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> {
                // API 21-22: permission was granted at install, no action needed
                debugLog("API 21-22: WRITE_EXTERNAL_STORAGE granted at install")
                writeSentinel()
            }
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
                    val headers = request.requestHeaders ?: emptyMap()
                    val lower   = headers.mapKeys { it.key.lowercase() }
                    val hasReqd = REQUIRED_HEADERS.all { it in lower }
                    val isApi   = path.contains("/youtubei/")

                    debugLog("INTERCEPT host=$host isApi=$isApi hasReqd=$hasReqd headers=${lower.keys}")

                    if (isApi && hasReqd) {
                        handleCapturedHeaders(headers)
                    } else if (isApi) {
                        debugLog("INTERCEPT missing: ${REQUIRED_HEADERS.filter { it !in lower }}")
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains(TARGET_HOST)) {
                    val cookieStr = CookieManager.getInstance().getCookie(url)
                    debugLog("PAGE_FINISHED cookies=${if (cookieStr.isNullOrEmpty()) "NONE" else "${cookieStr.length} chars"}")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    debugLog("PAGE_ERROR code=${error.errorCode} desc=${error.description}")
                }
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
            debugLog("handleCapturedHeaders: dedup skip (${now - lastWriteTime}s ago)")
            return
        }
        val cookieStr = CookieManager.getInstance().getCookie("https://music.youtube.com")
        if (cookieStr.isNullOrEmpty()) {
            debugLog("handleCapturedHeaders: no cookie — skipping")
            return
        }
        val merged = headers.toMutableMap()
        merged["cookie"] = cookieStr
        lastWriteTime   = now
        capturedHeaders = merged
        debugLog("handleCapturedHeaders: ${merged.size} headers, cookie ${cookieStr.length} chars")
        val sb = StringBuilder()
        for ((k, v) in merged) sb.append("$k: $v\n")
        writeHeadersFile(sb.toString(), merged)
    }

    private fun tryBuildFromWebViewCookies(cookieStr: String, url: String) {
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60) return
        debugLog("tryBuildFromWebViewCookies: ${cookieStr.length} chars")
        val uri    = Uri.parse(url)
        val origin = "${uri.scheme}://${uri.host}"
        val sb = StringBuilder()
        sb.append("cookie: $cookieStr\n")
        sb.append("x-goog-authuser: 0\n")
        sb.append("origin: $origin\n")
        sb.append("x-origin: $origin\n")
        lastWriteTime = now
        writeHeadersFile(sb.toString(), mapOf(
            "cookie"          to cookieStr,
            "x-goog-authuser" to "0",
            "origin"          to origin,
        ))
    }

    private fun writeHeadersFile(rawHeaders: String, headers: Map<String, String>) {
        if (!hasStorageAccess()) {
            debugLog("writeHeadersFile: BLOCKED — no storage permission")
            runOnUiThread {
                setStatus("Storage permission not granted. Restart the app to grant it.", isError = true)
            }
            return
        }
        try {
            val f = File(PUBLIC_HEADERS)
            f.parentFile?.mkdirs()
            f.writeText(rawHeaders, Charsets.UTF_8)
            debugLog("writeHeadersFile: wrote ${f.absolutePath} (${rawHeaders.length} bytes)")

            val meta = JSONObject().apply {
                put("written_at",   System.currentTimeMillis() / 1000)
                put("written_by",   "SpyderMusicAuth")
                put("app_version",  BuildConfig.VERSION_NAME)
                put("header_count", headers.size)
                put("android_api",  Build.VERSION.SDK_INT)
            }
            File(f.parent, "auth_meta.json").writeText(meta.toString(2))

            sendAuthBroadcast(f.absolutePath)
            runOnUiThread { showSuccess() }
        } catch (e: Exception) {
            debugLog("writeHeadersFile: EXCEPTION — $e")
            runOnUiThread { setStatus("Write failed: ${e.message}", isError = true) }
        }
    }

    private fun sendAuthBroadcast(headersPath: String) {
        val intent = Intent(ACTION_AUTH_UPDATED).apply {
            putExtra("headers_path", headersPath)
            putExtra("timestamp",    System.currentTimeMillis() / 1000)
            setPackage("org.xbmc.kodi")
        }
        try { sendBroadcast(intent) } catch (_: Exception) {}
    }

    private fun debugLog(msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val f  = File(DEBUG_LOG)
            f.parentFile?.mkdirs()
            FileWriter(f, true).use { it.write("$ts  $msg\n") }
        } catch (_: Exception) {}
    }

    private fun writeSentinel() {
        try {
            val f = File(SENTINEL_FILE)
            f.parentFile?.mkdirs()
            if (!f.exists()) {
                f.createNewFile()
                debugLog("SENTINEL written")
            }
        } catch (e: Exception) {
            debugLog("SENTINEL FAILED: $e")
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
