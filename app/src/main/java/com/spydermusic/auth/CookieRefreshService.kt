package com.spydermusic.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * CookieRefreshService — proactive background cookie refresh.
 *
 * Started by CookieRefreshWorker (WorkManager, every 6 h).  Loads
 * https://music.youtube.com in a headless WebView on the main thread,
 * intercepts the first YTMusic API request, captures fresh cookies, and
 * writes updated headers to the public SpyderMusic directory.
 *
 * Must be a foreground service on API 26+ (background execution limits).
 * Stops itself after a successful capture or after TIMEOUT_MS (60 s).
 *
 * Why a Service and not a Worker?
 *   WorkManager runs on a background thread; WebView.loadUrl() must be called
 *   on the main thread.  onStartCommand() and onCreate() are already on the
 *   main thread, making a Service the natural host.
 */
class CookieRefreshService : Service() {

    companion object {
        private const val TAG            = "CookieRefreshService"
        private const val NOTIF_CHANNEL  = "spydermusic_refresh"
        private const val NOTIF_ID       = 1001
        const val  TIMEOUT_MS            = 60_000L
        private const val TARGET_HOST    = "music.youtube.com"
        private val REQUIRED_HEADERS     = setOf("x-goog-authuser")

        /** Call from Application.onCreate() and any path before startForegroundService(). */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(NOTIF_CHANNEL) == null) {
                    val ch = NotificationChannel(
                        NOTIF_CHANNEL,
                        "SpyderMusic Auth refresh",
                        NotificationManager.IMPORTANCE_MIN   // silent, no badge
                    ).apply {
                        description = "Background credential refresh for SpyderMusic Kodi addon"
                        setShowBadge(false)
                    }
                    mgr.createNotificationChannel(ch)
                }
            }
        }

        fun buildSilentNotification(context: Context): Notification {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, NOTIF_CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("SpyderMusic Auth")
                .setContentText("Refreshing session in background…")
                .setOngoing(true)
                .build()
        }
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startId: Int = 0
    private var captured = false

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Timed out after ${TIMEOUT_MS}ms without capturing headers")
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        startForeground(NOTIF_ID, buildSilentNotification(this))
        Log.i(TAG, "CookieRefreshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        Log.i(TAG, "onStartCommand startId=$startId")

        // Arm watchdog
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        setupHeadlessWebView()
        return START_NOT_STICKY
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupHeadlessWebView() {
        // WebView must be created and used on the main thread.
        // onStartCommand() is already on the main thread, so no Handler needed.
        val wv = WebView(applicationContext)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            databaseEnabled    = true
            userAgentString    = MainActivity.CHROME_UA
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                val path = request.url.path ?: ""
                if (!host.endsWith(TARGET_HOST)) return null
                if (!path.contains("/youtubei/")) return null
                if (captured) return null

                val headers = request.requestHeaders ?: emptyMap()
                val lower   = headers.mapKeys { it.key.lowercase() }
                if (REQUIRED_HEADERS.all { it in lower }) {
                    val cookieStr = CookieManager.getInstance()
                        .getCookie("https://music.youtube.com") ?: ""
                    if (cookieStr.isNotEmpty()) {
                        captured = true
                        val merged = headers.toMutableMap()
                        merged["cookie"] = cookieStr
                        val sb = StringBuilder()
                        for ((k, v) in merged) sb.append("$k: $v\n")
                        onHeadersCaptured(sb.toString(), merged)
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (captured || !url.contains(TARGET_HOST)) return
                // Fallback: build from cookies only (same as MainActivity.tryBuildFromWebViewCookies)
                val cookieStr = CookieManager.getInstance().getCookie(url)
                if (!cookieStr.isNullOrEmpty()) {
                    captured = true
                    val uri    = Uri.parse(url)
                    val origin = "${uri.scheme}://${uri.host}"
                    val raw = buildString {
                        append("cookie: $cookieStr\n")
                        append("x-goog-authuser: 0\n")
                        append("origin: $origin\n")
                        append("x-origin: $origin\n")
                    }
                    onHeadersCaptured(raw, mapOf(
                        "cookie"          to cookieStr,
                        "x-goog-authuser" to "0",
                        "origin"          to origin,
                    ))
                }
            }
        }

        wv.loadUrl("https://music.youtube.com")
        Log.i(TAG, "Headless WebView loading $TARGET_HOST")
    }

    private fun onHeadersCaptured(rawHeaders: String, headers: Map<String, String>) {
        mainHandler.removeCallbacks(timeoutRunnable)
        Log.i(TAG, "Headers captured (${rawHeaders.length} bytes) — writing to disk")

        // Write to public directory (same path as MainActivity)
        try {
            val dir = File(MainActivity.PUBLIC_DIR)
            dir.mkdirs()
            File(MainActivity.PUBLIC_HEADERS).writeText(rawHeaders, Charsets.UTF_8)

            // Update HTTP server if it's running (singleton reference from Application)
            (application as? SpyderMusicApplication)?.httpServer?.updateHeaders(
                rawHeaders,
                System.currentTimeMillis() / 1000
            )

            // Write auth_meta.json so WorkManager can check header age
            val meta = JSONObject().apply {
                put("written_at",   System.currentTimeMillis() / 1000)
                put("written_by",   "CookieRefreshService")
                put("app_version",  BuildConfig.VERSION_NAME)
                put("header_count", headers.size)
                put("android_api",  Build.VERSION.SDK_INT)
            }
            File(dir, "auth_meta.json").writeText(meta.toString(2))

            debugLog("CookieRefreshService: write OK")
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: $e")
            debugLog("CookieRefreshService: write FAILED — $e")
        }

        stopSelf(startId)
    }

    private fun debugLog(msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val f  = File(MainActivity.DEBUG_LOG)
            f.parentFile?.mkdirs()
            FileWriter(f, true).use { it.write("$ts  $msg\n") }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        webView?.destroy()
        webView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "CookieRefreshService destroyed")
        super.onDestroy()
    }
}
