package com.openmesh.gateway

import java.util.concurrent.atomic.AtomicLong

/**
 * Metadata-only audit sink. Implementations must never log payloads, DNS
 * question names, message contents or credentials.
 */
interface GatewayAudit {
    fun onConnectionAccepted(sessionId: Long, host: String, port: Int) = Unit
    fun onConnectionDenied(host: String, port: Int, reason: String) = Unit
    fun onConnectionClosed(
        sessionId: Long,
        host: String,
        port: Int,
        bytesToRemote: Long,
        bytesFromRemote: Long,
    ) = Unit
    fun onUdpDnsForwarded(requestBytes: Int, responseBytes: Int) = Unit
}

object NoopGatewayAudit : GatewayAudit

/** Counters exposed to diagnostics without retaining destination history. */
class GatewayMetrics {
    private val accepted = AtomicLong()
    private val denied = AtomicLong()
    private val closed = AtomicLong()
    private val active = AtomicLong()
    private val bytesToRemote = AtomicLong()
    private val bytesFromRemote = AtomicLong()
    private val udpDnsQueries = AtomicLong()

    fun accepted() {
        accepted.incrementAndGet()
        active.incrementAndGet()
    }

    fun denied() {
        denied.incrementAndGet()
    }

    fun closed(toRemote: Long, fromRemote: Long) {
        closed.incrementAndGet()
        active.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        bytesToRemote.addAndGet(toRemote)
        bytesFromRemote.addAndGet(fromRemote)
    }

    fun udpDns() {
        udpDnsQueries.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        accepted = accepted.get(),
        denied = denied.get(),
        closed = closed.get(),
        active = active.get().coerceAtLeast(0),
        bytesToRemote = bytesToRemote.get(),
        bytesFromRemote = bytesFromRemote.get(),
        udpDnsQueries = udpDnsQueries.get(),
    )

    data class Snapshot(
        val accepted: Long,
        val denied: Long,
        val closed: Long,
        val active: Long,
        val bytesToRemote: Long,
        val bytesFromRemote: Long,
        val udpDnsQueries: Long,
    )
}
