package com.openmesh.vpn.android

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.openmesh.core.model.VpnStatus
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * P0 capture engine: it drains the TUN and drops every packet.
 *
 * This is deliberately not a data path. It lets the app prove that Android
 * routes only the allowlisted application into the TUN, while remaining
 * fail-closed: when there is no gateway, captured packets are discarded rather
 * than leaking through the underlying network.
 */
class CaptureOnlyEngine(
    private val pfd: ParcelFileDescriptor,
    private val onCounters: (txPackets: Long, txBytes: Long) -> Unit,
    private val onStopped: (reason: String?) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val packets = AtomicLong()
    private val bytes = AtomicLong()
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            // Poll with a timeout so shutdown does not depend on the kernel
            // waking a blocked read.
            Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        } catch (_: Exception) {
            // Fall back to a blocking read; closing the descriptor still
            // releases the thread on the tested Android kernels.
        }
        worker = thread(name = "openmesh-p0-capture", isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            val input = FileInputStream(pfd.fileDescriptor)
            var lastReport = 0L
            var reason: String? = null
            try {
                while (running.get()) {
                    val poll = StructPollfd().apply {
                        fd = pfd.fileDescriptor
                        events = OsConstants.POLLIN.toShort()
                    }
                    val ready = try {
                        Os.poll(arrayOf(poll), 500)
                    } catch (e: Exception) {
                        -1
                    }
                    if (ready <= 0) {
                        if (ready < 0) {
                            reason = "TUN poll failed"
                            break
                        }
                        continue
                    }
                    val n = try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        if (running.get()) reason = "TUN read failed: ${e.message}"
                        break
                    }
                    if (n < 0) {
                        reason = "TUN closed"
                        break
                    }
                    if (n > 0) {
                        packets.incrementAndGet()
                        bytes.addAndGet(n.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 500) {
                            lastReport = now
                            onCounters(packets.get(), bytes.get())
                        }
                    }
                }
            } finally {
                try {
                    input.close()
                } catch (_: Exception) {
                    // ignore
                }
                onCounters(packets.get(), bytes.get())
                onStopped(if (running.get()) reason else null)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            pfd.close()
        } catch (_: Exception) {
            // ignore
        }
        worker?.interrupt()
        worker = null
    }

    fun counters(): Pair<Long, Long> = packets.get() to bytes.get()
}
