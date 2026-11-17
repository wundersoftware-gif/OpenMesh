package com.openmesh.vpn.tun2socks

import hev.htproxy.TProxyService
import java.io.File

/**
 * Thin lifecycle wrapper around hev-socks5-tunnel 2.20.0 (MIT).
 *
 * The native engine owns the TUN file descriptor while it runs. The caller
 * must keep the ParcelFileDescriptor open until [stop] returns and must not
 * read from the descriptor concurrently.
 */
class Tun2SocksEngine(
    private val configFile: File,
    private val logFile: File,
) {
    private var running = false

    @Synchronized
    fun start(tunFd: Int, config: Tun2SocksConfig): Boolean {
        if (running) return true
        configFile.parentFile?.mkdirs()
        configFile.writeText(config.toYaml())
        // Reset the engine log for this session so diagnostics refer to the
        // current run only.
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.exists()) logFile.delete()
        } catch (_: Exception) {
            // A stale log is not fatal.
        }
        val started = try {
            TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is possible on an unsupported ABI.
            throw IllegalStateException("native tun2socks failed to load: ${e.message}", e)
        }
        if (!started) return false
        // The native call returns after spawning a worker. Give it a moment to
        // fail fast on an invalid configuration.
        repeat(20) {
            if (TProxyService.TProxyIsRunning()) {
                running = true
                return true
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        running = false
        return false
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            TProxyService.TProxyStopService()
        } finally {
            running = false
        }
    }

    fun isRunning(): Boolean = running && TProxyService.TProxyIsRunning()

    fun stats(): Tun2SocksStats {
        if (!running) return Tun2SocksStats.ZERO
        return try {
            val values = TProxyService.TProxyGetStats()
            if (values.size < 4) {
                Tun2SocksStats.ZERO
            } else {
                Tun2SocksStats(
                    txPackets = values[0],
                    txBytes = values[1],
                    rxPackets = values[2],
                    rxBytes = values[3],
                )
            }
        } catch (_: Throwable) {
            Tun2SocksStats.ZERO
        }
    }

    fun logTail(maxBytes: Int = 4096): String {
        if (!logFile.exists()) return ""
        return try {
            val length = logFile.length()
            val skip = (length - maxBytes).coerceAtLeast(0)
            logFile.inputStream().use { input ->
                input.skip(skip)
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
