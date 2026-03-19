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
 * Hosts a WebView pointed at music.youtube.com.  On every YTMusic API
 * request we intercept headers (Cookie, x-goog-*) and write them as
 * headers_auth.json to a shared public directory both this app and Kodi
 * can access regardless of Android version.
 *
 * Storage strategy by API level:
 *   API 28-29 (Android 9-10)  — WRITE_EXTERNAL_STORAGE, runtime permission
 *                                requested via registerForActivityResult.
 *   API 30+   (Android 11+)   — MANAGE_EXTERNAL_STORAGE ("All files access"),
 *                                directed to system settings page.
 *
 * Both paths write to /storage/emulated/0/SpyderMusic/headers_auth.json —
 * the public directory readable by Kodi regardless of Android version.
 * Direct writes to /Android/data/org.xbmc.kodi/ are not attempted because
 * they fail on API 30+ even with MANAGE_EXTERNAL_STORAGE.
 *
 * minSdk 28 — supports Nvidia Shield Pro (2019, Android 9) and later.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PUBLIC_DIR     = "/storage/emulated/0/SpyderMusic"
        const val PUBLIC_HEADERS = "$PUBLIC_DIR/headers_auth.json"
        const val SENTINEL_FILE  = "$PUBLIC_DIR/.companion_installed"
        const val DEBUG_LOG      = "$PUBLIC_DIR/spyderauth_debug.log"

        const val ACTION_AUTH_UPDATED = "com.spydermusic.AUTH_UPDATED"
        const val TARGET_HOST         = "music.youtube.com"

        // Headers we need from the intercepted request.
        // 'cookie' is absent — WebView strips it from requestHeaders;
        // we inject it from CookieManager in handleCapturedHeaders().
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

    // Runtime permission launcher for API 28-29
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            debugLog("WRITE_EXTERNAL_STORAGE permission result: granted=$granted")
            if (granted) {
                writeSentinel()
            } else {
                setStatus(
                    "Storage permission denied.\n" +
                    "SpyderMusic Auth needs storage access to share cookies with Kodi.",
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

        // WindowInsetsController available from API 30+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        debugLog("=== SpyderMusic Auth started (v${BuildConfig.VERSION_NAME}) ===")
        debugLog("Android API: ${Build.VERSION.SDK_INT}")

        registerBackPressedCallback()
        ensureStoragePermission()
        setupWebView()

        webView.loadUrl("https://music.youtube.com")
    }

    private fun registerBackPressedCallback() {
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
        if (hasStorageAccess()) {
            debugLog("onResume: storage access confirmed")
            writeSentinel()
        } else {
            debugLog("onResume: storage access not yet granted")
        }
    }

    /**
     * Returns true if the app has the storage access it needs for the
     * current API level to write to the public SpyderMusic directory.
     */
    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureStoragePermission() {
        debugLog("ensureStoragePermission: API=${Build.VERSION.SDK_INT} hasAccess=${hasStorageAccess()}")

        if (hasStorageAccess()) {
            writeSentinel()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: direct to MANAGE_EXTERNAL_STORAGE system settings
            setStatus(
                "SpyderMusic Auth needs 'All files access' to share data with Kodi.\n" +
                "Tap OK on the next screen to grant it.",
                isError = false
            )
            debugLog("Directing to ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }, 2000)
        } else {
            // API 28-29: standard runtime permission request
            debugLog("Requesting WRITE_EXTERNAL_STORAGE via ActivityResult")
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

                    debugLog("INTERCEPT host=$host path=$path isApi=$isApi hasReqd=$hasReqd headers=${lower.keys}")

                    if (isApi && hasReqd) {
                        debugLog("INTERCEPT match — capturing")
                        handleCapturedHeaders(headers)
                    } else if (isApi) {
                        debugLog("INTERCEPT api but missing: ${REQUIRED_HEADERS.filter { it !in lower }}")
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                debugLog("PAGE_FINISHED url=$url")
                if (url.contains(TARGET_HOST)) {
                    val cookieStr = CookieManager.getInstance().getCookie(url)
                    debugLog("PAGE_FINISHED cookies=${if (cookieStr.isNullOrEmpty()) "NONE" else "${cookieStr.length} chars"}")
                    if (!cookieStr.isNullOrEmpty() && capturedHeaders == null) {
                        tryBuildFromWebViewCookies(cookieStr, url)
                    }
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                // API 28-compatible override (the RequestResource variant needs API 23,
                // which we meet, but the String variant is still needed to satisfy the
                // compiler when targeting API 28 without the newer override).
                debugLog("PAGE_ERROR code=$errorCode desc=$description url=$failingUrl")
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

        debugLog("handleCapturedHeaders: ${merged.size} headers (${cookieStr.length} char cookie): ${merged.keys}")

        val sb = StringBuilder()
        for ((k, v) in merged) sb.append("$k: $v\n")
        writeHeadersFile(sb.toString(), merged)
    }

    private fun tryBuildFromWebViewCookies(cookieStr: String, url: String) {
        val now = System.currentTimeMillis() / 1000
        if (now - lastWriteTime < 60) {
            debugLog("tryBuildFromWebViewCookies: dedup skip")
            return
        }
        debugLog("tryBuildFromWebViewCookies: ${cookieStr.length} chars")

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
        if (!hasStorageAccess()) {
            debugLog("writeHeadersFile: BLOCKED — no storage permission")
            runOnUiThread {
                setStatus(
                    "Storage permission not granted.\n" +
                    "Restart the app and grant storage access to continue.",
                    isError = true
                )
            }
            return
        }

        try {
            val publicFile = File(PUBLIC_HEADERS)
            publicFile.parentFile?.mkdirs()
            publicFile.writeText(rawHeaders, Charsets.UTF_8)
            debugLog("writeHeadersFile: wrote ${publicFile.absolutePath} (${rawHeaders.length} bytes)")

            val meta = JSONObject().apply {
                put("written_at",   System.currentTimeMillis() / 1000)
                put("written_by",   "SpyderMusicAuth")
                put("app_version",  BuildConfig.VERSION_NAME)
                put("header_count", headers.size)
                put("android_api",  Build.VERSION.SDK_INT)
            }
            File(publicFile.parent, "auth_meta.json").writeText(meta.toString(2))
            debugLog("writeHeadersFile: meta written")

            sendAuthBroadcast(publicFile.absolutePath)
            runOnUiThread { showSuccess() }

        } catch (e: Exception) {
            debugLog("writeHeadersFile: EXCEPTION — $e")
            runOnUiThread { setStatus("Write failed: ${e.message}", isError = true) }
        }
    }

    private fun sendAuthBroadcast(headersPath: String) {
        // Explicit package target satisfies Android 14+ cross-app broadcast rules.
        // Safe to call even if Kodi is not running — the file write is sufficient.
        val intent = Intent(ACTION_AUTH_UPDATED).apply {
            putExtra("headers_path", headersPath)
            putExtra("timestamp",    System.currentTimeMillis() / 1000)
            setPackage("org.xbmc.kodi")
        }
        try { sendBroadcast(intent) } catch (_: Exception) {}
    }

    private fun debugLog(msg: String) {
        try {
            val ts   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val f    = File(DEBUG_LOG)
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
                debugLog("SENTINEL written: $SENTINEL_FILE")
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
