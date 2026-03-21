package com.spydermusic.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

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

        // UDP port on which credentials are broadcast to all Kodi instances
        // on the local network (e.g. Shield TV on a different device).
        const val UDP_BROADCAST_PORT  = 8765

        // 'cookie' excluded — WebView strips it from requestHeaders;
        // injected from CookieManager in handleCapturedHeaders().
        val REQUIRED_HEADERS = setOf("x-goog-authuser")

        // Mobile UA — used for accounts.google.com sign-in.
        // Google blocks sign-in when it detects a non-standard desktop UA in a WebView.
        const val CHROME_UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 9; Pixel 3) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // Desktop UA — used for music.youtube.com to get the full web layout
        // which uses ytmusic-responsive-list-item-renderer (required for Cast injection).
        const val CHROME_UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Default UA (mobile) — CookieRefreshService uses this via MainActivity.CHROME_UA
        const val CHROME_UA = CHROME_UA_MOBILE
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var statusCard: View
    private lateinit var successCard: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var grantAccessButton: Button

    private var capturedHeaders: Map<String, String>? = null
    private var lastWriteTime: Long = 0

    // FIX #5: single Handler instance so postDelayed callbacks can be cancelled in onDestroy.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideSuccessRunnable = Runnable {
        successCard.visibility = View.GONE
        statusCard.visibility  = View.VISIBLE
        setStatus("Session saved ✓  You can close this app", isError = false)
    }

    // FIX #10: single-threaded executor for all disk log writes — serialises
    // concurrent calls from shouldInterceptRequest (WebView IO thread) and main thread.
    private val logExecutor = Executors.newSingleThreadExecutor()

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

        statusText        = findViewById(R.id.status_text)
        statusCard        = findViewById(R.id.status_card)
        successCard       = findViewById(R.id.success_card)
        loadingBar        = findViewById(R.id.loading_bar)
        grantAccessButton = findViewById(R.id.grant_access_button)

        // FIX #1: wire version label to BuildConfig — never stale.
        findViewById<TextView>(R.id.version_text).text = "v${BuildConfig.VERSION_NAME}"

        // Status/nav bar appearance — API 30+ only, guarded at call site.
        applyWindowInsets()

        debugLog("=== SpyderMusic Auth started (v${BuildConfig.VERSION_NAME}) ===")
        debugLog("Android API: ${Build.VERSION.SDK_INT}")

        registerBackPressedCallback()
        ensureStoragePermission()
        setupWebView()

        // Start the local HTTP server so Kodi can poll credentials without
        // filesystem races.  The server is owned by SpyderMusicApplication and
        // survives Activity recreation — start() is idempotent.
        (application as? SpyderMusicApplication)?.httpServer?.start()

        // Schedule proactive 6-hourly cookie refresh via WorkManager.
        // Idempotent — KEEP policy means a second call here has no effect.
        CookieRefreshWorker.schedule(this)

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
        // FIX #8: resume WebView internal timers and compositing threads.
        webView.onResume()
        // User may have returned from the MANAGE_EXTERNAL_STORAGE settings screen.
        if (hasStorageAccess()) {
            debugLog("onResume: storage access OK")
            grantAccessButton.visibility = View.GONE
            writeSentinel()
        } else {
            debugLog("onResume: storage access not yet granted (API ${Build.VERSION.SDK_INT})")
        }
    }

    override fun onPause() {
        // FIX #8: pause WebView to stop JS timers and compositing while backgrounded.
        webView.onPause()
        super.onPause()
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
                // FIX #4: show a "Grant Access" button instead of auto-redirecting
                // after an arbitrary delay. User controls when they leave the screen.
                setStatus(
                    "SpyderMusic Auth needs 'All files access' to share data with Kodi.",
                    isError = false
                )
                debugLog("Showing Grant Access button for ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                grantAccessButton.visibility = View.VISIBLE
                grantAccessButton.setOnClickListener {
                    grantAccessButton.visibility = View.GONE
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
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

        // FIX #9: content description for TalkBack.
        webView.contentDescription = "YouTube Music"

        // Inject Cast-to-Kodi JavaScript interface.
        // The interface is added before loadUrl() so it is available as soon as
        // the first page's JS executes.  The cast button injection script itself
        // waits for the DOM to settle (rAF + MutationObserver) so there is no
        // timing issue with early injection.
        val castInterface = CastJavascriptInterface { videoId, title, artist ->
            // Called on a WebView thread — POST to HTTP server which invokes
            // SpyderMusicApplication.castCallback → CastToKodi.play() on the
            // same thread (all loopback, synchronous, <3 s timeout).
            (application as? SpyderMusicApplication)?.httpServer?.castCallback?.invoke(
                CompanionHttpServer.CastPayload(videoId, title, artist)
            )
            debugLog("Cast requested: videoId=$videoId title=$title artist=$artist")
        }
        webView.addJavascriptInterface(castInterface, CastJavascriptInterface.INTERFACE_NAME)

        webView.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            databaseEnabled    = true
            allowContentAccess = true
            // FIX #15: removed loadWithOverviewMode + useWideViewPort — the mobile
            // UA already causes YTMusic to serve a mobile layout sized for the viewport.
            // These settings caused a slightly zoomed-out initial render on some devices.
            userAgentString    = CHROME_UA_MOBILE  // onPageStarted switches to desktop for music.youtube.com
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Switch UA based on domain so Google sign-in works (requires mobile UA)
                // while music.youtube.com gets the desktop layout for Cast button injection.
                val targetUA = if (url.contains("accounts.google.com") ||
                                   url.contains("accounts.youtube.com"))
                    CHROME_UA_MOBILE else CHROME_UA_DESKTOP
                if (view.settings.userAgentString != targetUA) {
                    view.settings.userAgentString = targetUA
                    // Guard: only reload if we haven't just reloaded for this URL,
                    // preventing the reload from triggering another reload loop.
                    val lastReloadKey = "_spyderLastReload"
                    val tag = view.getTag(R.id.webview) as? String
                    if (tag != url) {
                        view.setTag(R.id.webview, url)
                        view.reload()
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                val path = request.url.path ?: ""

                if (host.endsWith(TARGET_HOST)) {
                    val isApi = path.contains("/youtubei/")
                    // FIX #11: only log API-path requests — non-API requests (images,
                    // fonts, static assets) fire dozens of times per second and would
                    // flood the log and thrash the disk.
                    if (isApi) {
                        val headers = request.requestHeaders ?: emptyMap()
                        val lower   = headers.mapKeys { it.key.lowercase() }
                        val hasReqd = REQUIRED_HEADERS.all { it in lower }
                        debugLog("INTERCEPT isApi=true hasReqd=$hasReqd headers=${lower.keys}")
                        if (hasReqd) {
                            handleCapturedHeaders(headers)
                        } else {
                            debugLog("INTERCEPT missing: ${REQUIRED_HEADERS.filter { it !in lower }}")
                        }
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
                    // Re-inject Cast buttons on every page load (YTMusic SPA
                    // re-renders the DOM on navigation without firing a new load).
                    view.evaluateJavascript(CastJavascriptInterface.CAST_JS, null)
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
                // FIX #3: drive the ProgressBar; guard status text against
                // overwriting an error or success message mid-load.
                runOnUiThread {
                    if (newProgress < 100) {
                        loadingBar.visibility = View.VISIBLE
                        loadingBar.progress   = newProgress
                    } else {
                        loadingBar.visibility = View.GONE
                        val current = statusText.text.toString()
                        if (current.startsWith("Loading") || current.isEmpty()) {
                            setStatus("Sign in to YouTube Music, then play any song", isError = false)
                        }
                    }
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
        // FIX #16: warn that the fallback path can only synthesise authuser=0.
        // Users signed in as a secondary Google account will get a broken auth file
        // silently until a full API intercept fires and overwrites this one.
        debugLog("tryBuildFromWebViewCookies: ${cookieStr.length} chars [WARNING: x-goog-authuser hardcoded to 0 — secondary account users may see auth failures until a full API intercept fires]")
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
            sendNetworkBroadcast(rawHeaders)

            // Update the local HTTP server so Kodi can fetch fresh credentials
            // without waiting for a file-mtime change (eliminates filesystem race).
            (application as? SpyderMusicApplication)?.httpServer?.updateHeaders(
                rawHeaders,
                System.currentTimeMillis() / 1000
            )

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

    /**
     * UDP network broadcast — sends raw header text to all devices on the local
     * network listening on UDP_BROADCAST_PORT (8765).
     *
     * This allows a Kodi instance on a *different device* (e.g. Shield TV) to
     * receive credentials captured on this phone.  The Kodi service's
     * AuthBroadcastMonitor listens on the same port and writes the payload to
     * its local headers_auth.json, triggering the normal file-based auth flow.
     *
     * Payload format — a single JSON object:
     *   { "v": 1, "ts": <epoch_seconds>, "headers": "<raw header text>" }
     *
     * The raw header text is the same "name: value\n" format written to disk.
     * Runs on the log executor (background thread) so it never blocks the UI.
     */
    private fun sendNetworkBroadcast(rawHeaders: String) {
        logExecutor.execute {
            try {
                val payload = JSONObject().apply {
                    put("v",       1)
                    put("ts",      System.currentTimeMillis() / 1000)
                    put("headers", rawHeaders)
                }.toString()

                val bytes = payload.toByteArray(Charsets.UTF_8)

                // Cap at 60 KB — UDP practical limit; headers are typically ~4 KB
                if (bytes.size > 61440) {
                    debugLog("sendNetworkBroadcast: payload too large (${bytes.size} bytes) — skipping")
                    return@execute
                }

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val addr   = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(bytes, bytes.size, addr, UDP_BROADCAST_PORT)
                    socket.send(packet)
                }
                debugLog("sendNetworkBroadcast: sent ${bytes.size} bytes to 255.255.255.255:$UDP_BROADCAST_PORT")
            } catch (e: Exception) {
                debugLog("sendNetworkBroadcast: EXCEPTION — $e")
            }
        }
    }

    /**
     * FIX #10: all log writes are submitted to a single-threaded executor.
     * This serialises concurrent calls from shouldInterceptRequest (WebView IO thread)
     * and the main thread, preventing interleaved FileWriter writes.
     * SimpleDateFormat is constructed per-call inside the executor task — it is not
     * thread-safe so must not be shared, but construction is cheap given that logging
     * is now gated to API-path requests only (fix #11).
     */
    private fun debugLog(msg: String) {
        logExecutor.execute {
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val f  = File(DEBUG_LOG)
                f.parentFile?.mkdirs()
                FileWriter(f, true).use { it.write("$ts  $msg\n") }
            } catch (_: Exception) {}
        }
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
        // FIX #5: cancel any pending hide-runnable before scheduling a new one,
        // so rapid successive captures don't stack callbacks on a dead activity.
        mainHandler.removeCallbacks(hideSuccessRunnable)
        statusCard.visibility  = View.GONE
        successCard.visibility = View.VISIBLE
        mainHandler.postDelayed(hideSuccessRunnable, 3000)
    }

    private fun setStatus(msg: String, isError: Boolean) {
        runOnUiThread {
            statusText.text = msg
            // FIX #7: error state uses full-white text for sharper contrast on #3A1A1A.
            statusText.setTextColor(
                if (isError) Color.WHITE else Color.parseColor("#CCCCCC")
            )
            statusCard.setBackgroundResource(
                if (isError) R.drawable.card_error else R.drawable.card_status
            )
        }
    }

    override fun onDestroy() {
        // FIX #5: cancel pending success-hide timer so it cannot fire on a destroyed activity.
        mainHandler.removeCallbacks(hideSuccessRunnable)
        logExecutor.shutdown()
        // NOTE: do NOT stop httpServer here — the server is owned by SpyderMusicApplication
        // and must survive Activity recreation (rotation, backgrounding).  The OS will
        // terminate it when the process dies.  onDestroy() fires on rotation; stopping
        // the server here would make Kodi's poll fail every time the screen rotates.
        webView.destroy()
        super.onDestroy()
    }
}
