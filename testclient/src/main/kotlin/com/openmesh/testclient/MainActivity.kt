package com.openmesh.testclient

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Lab traffic generator.
 *
 * This app exists only for debug builds of OpenMesh. It is the allowlisted
 * package that produces reproducible traffic so the TUN -> tun2socks -> SOCKS
 * path can be tested without touching WhatsApp.
 */
class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val log = StringBuilder()
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        logLine("OpenMesh lab client ${BuildConfig.VERSION_NAME}")
        logLine("This app is captured only by OpenMesh debug builds.")
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            logView.post { runChecks() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            runChecks()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 14))
            setPadding(32, 40, 32, 24)
        }
        val title = TextView(this).apply {
            text = "OpenMesh Lab Traffic"
            setTextColor(Color.rgb(232, 242, 237))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Reproducible fetches used by docs/TEST_PLAN.md. Run this while the " +
                "debug VPN is active and compare the OpenMesh counters."
            setTextColor(Color.rgb(145, 167, 158))
            textSize = 12f
            setPadding(0, 8, 0, 20)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val run = Button(this).apply {
            text = "Run checks"
            setOnClickListener { runChecks() }
        }
        val clear = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                log.setLength(0)
                logView.text = ""
                statusView.text = "Idle"
            }
        }
        buttons.addView(run)
        buttons.addView(clear)
        statusView = TextView(this).apply {
            text = "Idle"
            setTextColor(Color.rgb(87, 227, 166))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 12)
        }
        logView = TextView(this).apply {
            setTextColor(Color.rgb(205, 220, 213))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(buttons)
        root.addView(statusView)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun runChecks() {
        if (executor.isShutdown) return
        setStatus("Running…")
        logLine("----- run started -----")
        executor.execute {
            try {
                checkDns()
                checkLabServer()
                checkPublicHttps()
                checkBlockedPrivate()
            } catch (e: Throwable) {
                logLine("fatal: ${describe(e)}")
            } finally {
                logLine("----- run finished -----")
                setStatus("Finished")
            }
        }
    }

    private fun checkDns() {
        val started = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName("example.com")
            val took = System.currentTimeMillis() - started
            logLine("DNS example.com -> ${addresses.joinToString { it.hostAddress ?: "?" }} (${took} ms)")
        } catch (e: Exception) {
            logLine("DNS FAILED after ${System.currentTimeMillis() - started} ms: ${describe(e)}")
        }
    }

    private fun checkLabServer() {
        val url = "http://10.0.2.2:8080/health"
        logLine("HTTP GET $url")
        fetch(url, readBody = true)
    }

    private fun checkPublicHttps() {
        val url = "https://example.com/"
        logLine("HTTPS GET $url")
        fetch(url, readBody = true)
    }

    /**
     * 10.0.2.2:8081 is private and is not in the lab allowlist, so the gateway
     * policy must refuse it. Success here would be a policy bug.
     */
    private fun checkBlockedPrivate() {
        val url = "http://10.0.2.2:8081/health"
        logLine("HTTP GET $url (expected deny)")
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            logLine(
                "POLICY FAILURE: blocked private destination answered HTTP $code " +
                    "after ${System.currentTimeMillis() - started} ms",
            )
        } catch (e: Exception) {
            logLine(
                "expected deny after ${System.currentTimeMillis() - started} ms: ${describe(e)}",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetch(url: String, readBody: Boolean) {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "OpenMeshLab/0.1")
            connection.setRequestProperty(
                "X-OpenMesh-Test",
                timeFormat.format(Date()),
            )
            val code = connection.responseCode
            val length = connection.contentLengthLong
            val stream: InputStream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = if (readBody) readLimited(BufferedInputStream(stream), 256 * 1024) else ByteArray(0)
            val took = System.currentTimeMillis() - started
            val tls = (connection as? HttpsURLConnection)?.let {
                " cipher=${it.cipherSuite}"
            } ?: ""
            logLine("HTTP $code in $took ms, declared=$length, read=${body.size} bytes$tls")
            if (body.isNotEmpty()) {
                val text = String(body, Charsets.UTF_8)
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .take(180)
                logLine("body: $text")
            }
        } catch (e: Exception) {
            logLine("FAILED after ${System.currentTimeMillis() - started} ms: ${describe(e)}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < limit) {
                val n = it.read(buffer)
                if (n < 0) break
                val keep = minOf(n, limit - total)
                out.write(buffer, 0, keep)
                total += keep
            }
            return out.toByteArray()
        }
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause
        return buildString {
            append(error.javaClass.simpleName)
            append(": ")
            append(error.message ?: "(no message)")
            if (cause != null && cause !== error) {
                append(" <- ")
                append(cause.javaClass.simpleName)
                append(": ")
                append(cause.message ?: "")
            }
        }
    }

    private fun logLine(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line\n"
        synchronized(log) {
            log.append(stamped)
        }
        main.post {
            logView.append(stamped)
            (logView.parent as? ScrollView)?.post {
                (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun setStatus(text: String) {
        main.post { statusView.text = text }
    }

    companion object {
        const val EXTRA_AUTO_START = "autoStart"
    }
}
