package com.spydermusic.auth

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CompanionHttpServer — lightweight HTTP server for loopback credential delivery.
 *
 * Listens on 127.0.0.1:52080 and serves two endpoints:
 *
 *   GET /health  → {"status":"ok","last_captured":<epoch_float>,
 *                    "refresh_count":<int>,"server_version":1}
 *
 *   GET /headers → {"v":1,"ts":<epoch_float>,"headers":"<raw text>",
 *                    "format":"raw","refresh_count":<int>}
 *                  or HTTP 204 No Content when no headers captured yet.
 *
 * The Kodi addon's companion_bridge.py consumes these endpoints.
 * Binding to 127.0.0.1 (loopback only) ensures the server is never
 * reachable from external network interfaces.
 *
 * Implementation: pure Java ServerSocket — no NanoHTTPD or OkHttp dependency.
 * One accept-loop thread + a cached thread pool for per-connection handlers.
 * All state is held in AtomicReference/AtomicLong so connection threads never
 * need to synchronise on the server object itself.
 */
class CompanionHttpServer(private val port: Int = 52080) {

    companion object {
        const val SERVER_VERSION = 1
        const val DEFAULT_PORT     = 52080
        private const val BACKLOG = 8
        private const val SO_TIMEOUT_MS = 2000   // accept() timeout — lets the loop check isRunning
        private const val CONN_TIMEOUT_MS = 3000 // per-connection read timeout
    }

    // Atomic state — safe to read from any thread without locking
    private val _rawHeaders    = AtomicReference<String?>(null)
    private val _lastCaptured  = AtomicLong(0L)           // epoch seconds
    private val _refreshCount  = AtomicInteger(0)

    // Now Playing state — set by POST /nowplaying from the Kodi addon
    @Volatile var nowPlayingCallback: ((NowPlayingPayload) -> Unit)? = null

    data class NowPlayingPayload(
        val videoId:  String,
        val title:    String,
        val artist:   String,
        val album:    String,
        val duration: Long,        // seconds
        val thumbnail:String,
        val playing:  Boolean,
        val position: Double,      // seconds
        val clear:    Boolean      // true = stop / clear media session
    )

    // Cast-to-Kodi callback — invoked when POST /cast arrives from MainActivity
    @Volatile var castCallback: ((CastPayload) -> Unit)? = null

    data class CastPayload(
        val videoId: String,
        val title:   String,
        val artist:  String
    )
    @Volatile private var isRunning = false

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "SpyderHttpConn").apply { isDaemon = true }
    }
    private var acceptThread: Thread? = null

    /**
     * Update the stored headers.  Called from MainActivity.writeHeadersFile()
     * on every successful capture.  Thread-safe.
     *
     * @param rawHeaders  Raw "name: value\n" header text — same format as the file.
     * @param epochSeconds Capture timestamp in seconds since Unix epoch.
     */
    fun updateHeaders(rawHeaders: String, epochSeconds: Long) {
        _rawHeaders.set(rawHeaders)
        _lastCaptured.set(epochSeconds)
        _refreshCount.incrementAndGet()
    }

    /** Clear stored headers (e.g. on sign-out). */
    fun clearHeaders() {
        _rawHeaders.set(null)
        _lastCaptured.set(0L)
    }

    /** Start the accept loop.  No-op if already running. */
    fun start() {
        if (isRunning) return
        isRunning = true
        acceptThread = Thread({
            try {
                // Bind to loopback only — never reachable from outside the device.
                val ss = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
                ss.soTimeout = SO_TIMEOUT_MS
                serverSocket = ss
                while (isRunning) {
                    val conn: Socket = try {
                        ss.accept()
                    } catch (_: SocketException) {
                        break  // server closed
                    } catch (_: java.net.SocketTimeoutException) {
                        continue  // timeout — loop to check isRunning
                    }
                    executor.submit { handleConnection(conn) }
                }
            } catch (e: Exception) {
                // Port already in use or other bind failure — log and exit silently.
                // The Kodi side will fall back to file-mtime polling.
                android.util.Log.w("SpyderHttpServer", "Failed to bind port $port: $e")
            }
        }, "SpyderHttpAccept").apply { isDaemon = true }
        acceptThread!!.start()
        android.util.Log.i("SpyderHttpServer", "HTTP server started on 127.0.0.1:$port")
    }

    /** Stop the accept loop and close the server socket. */
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        android.util.Log.i("SpyderHttpServer", "HTTP server stopped")
    }

    // ── Connection handler ────────────────────────────────────────────────────

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = CONN_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Parse request line (e.g. "GET /health HTTP/1.1")
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) { sendError(writer, 400, "Bad Request"); return }
            val method = parts[0]
            val path   = parts[1].substringBefore("?")  // strip query string

            // Consume remaining headers (we don't need them, but must drain)
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                line = reader.readLine()
            }

            if (method == "POST" && path == "/nowplaying") {
                serveNowPlayingPost(reader, writer)
                return
            }

            if (method == "POST" && path == "/cast") {
                serveCastPost(reader, writer)
                return
            }

            if (method != "GET") { sendError(writer, 405, "Method Not Allowed"); return }

            when (path) {
                "/health"  -> serveHealth(writer)
                "/headers" -> serveHeaders(writer)
                else       -> sendError(writer, 404, "Not Found")
            }
        } catch (_: Exception) {
            // Connection dropped or timeout — ignore
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveHealth(writer: PrintWriter) {
        val body = JSONObject().apply {
            put("status",         if (_rawHeaders.get() != null) "ok" else "empty")
            put("last_captured",  _lastCaptured.get().toDouble())
            put("refresh_count",  _refreshCount.get())
            put("server_version", SERVER_VERSION)
        }.toString()
        sendJson(writer, 200, body)
    }

    private fun serveHeaders(writer: PrintWriter) {
        val raw = _rawHeaders.get()
        if (raw == null) {
            // No credentials captured yet
            sendNoContent(writer)
            return
        }
        val body = JSONObject().apply {
            put("v",             1)
            put("ts",            _lastCaptured.get().toDouble())
            put("headers",       raw)
            put("format",        "raw")
            put("refresh_count", _refreshCount.get())
        }.toString()
        sendJson(writer, 200, body)
    }

    private fun serveCastPost(reader: BufferedReader, writer: PrintWriter) {
        val bodyChars = StringBuilder()
        try { while (reader.ready()) { val ch = reader.read(); if (ch == -1) break; bodyChars.append(ch.toChar()) } }
        catch (_: Exception) {}
        val body = bodyChars.toString().trim()
        if (body.isEmpty()) { sendError(writer, 400, "Empty body"); return }
        try {
            val j = JSONObject(body)
            val payload = CastPayload(
                videoId = j.optString("video_id", ""),
                title   = j.optString("title",    ""),
                artist  = j.optString("artist",   ""),
            )
            if (payload.videoId.isBlank()) { sendError(writer, 400, "Missing video_id"); return }
            castCallback?.invoke(payload)
            sendJson(writer, 200, """{"ok":true}""")
        } catch (e: Exception) {
            sendError(writer, 400, "Bad JSON: ${e.message}")
        }
    }

    private fun serveNowPlayingPost(reader: BufferedReader, writer: PrintWriter) {
        // Read Content-Length header (already consumed request line + headers above;
        // we need the body).  Re-read using a fixed-length approach: read chars until
        // no more data arrives within the connection timeout.
        val bodyChars = StringBuilder()
        try {
            while (reader.ready()) {
                val ch = reader.read()
                if (ch == -1) break
                bodyChars.append(ch.toChar())
            }
        } catch (_: Exception) {}

        val body = bodyChars.toString().trim()
        if (body.isEmpty()) { sendError(writer, 400, "Empty body"); return }

        try {
            val j       = JSONObject(body)
            val clear   = j.optBoolean("clear", false)
            val playing = j.optBoolean("playing", false)

            val payload = NowPlayingPayload(
                videoId   = j.optString("video_id",  ""),
                title     = j.optString("title",     ""),
                artist    = j.optString("artist",    ""),
                album     = j.optString("album",     ""),
                duration  = j.optLong("duration",    0L),
                thumbnail = j.optString("thumbnail", ""),
                playing   = playing,
                position  = j.optDouble("position",  0.0),
                clear     = clear,
            )

            nowPlayingCallback?.invoke(payload)
            sendJson(writer, 200, """{"ok":true}""")
        } catch (e: Exception) {
            android.util.Log.w("SpyderHttpServer", "POST /nowplaying parse error: $e")
            sendError(writer, 400, "Bad JSON: ${e.message}")
        }
    }

    // ── HTTP response helpers ─────────────────────────────────────────────────

    private fun sendJson(writer: PrintWriter, status: Int, body: String) {
        val statusText = if (status == 200) "OK" else "Error"
        writer.print("HTTP/1.1 $status $statusText\r\n")
        writer.print("Content-Type: application/json; charset=utf-8\r\n")
        writer.print("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private fun sendNoContent(writer: PrintWriter) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    private fun sendError(writer: PrintWriter, status: Int, message: String) {
        val body = """{"error":"$message"}"""
        sendJson(writer, status, body)
    }
}
