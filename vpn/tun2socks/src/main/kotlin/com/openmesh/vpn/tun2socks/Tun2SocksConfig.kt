package com.openmesh.vpn.tun2socks

import java.io.File

data class Tun2SocksConfig(
    val socksPort: Int,
    val socksAddress: String = "127.0.0.1",
    val socksUsername: String? = null,
    val socksPassword: String? = null,
    val mtu: Int = 1500,
    val ipv4: String = "10.111.0.2",
    val ipv6: String = "fd00:111::2",
    val logFile: File,
    val logLevel: String = "info",
    val connectTimeoutMillis: Int = 10_000,
    val readWriteTimeoutMillis: Int = 300_000,
) {
    init {
        require(socksPort in 1..65535)
        require(mtu in 1280..9000)
        require((socksUsername == null) == (socksPassword == null)) {
            "username and password must be provided together"
        }
    }

    fun toYaml(): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $mtu")
        appendLine("  ipv4: $ipv4")
        appendLine("  ipv6: '${yamlEscape(ipv6)}'")
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: $socksAddress")
        appendLine("  udp: 'udp'")
        if (socksUsername != null && socksPassword != null) {
            appendLine("  username: '${yamlEscape(socksUsername)}'")
            appendLine("  password: '${yamlEscape(socksPassword)}'")
        }
        appendLine("misc:")
        appendLine("  connect-timeout: $connectTimeoutMillis")
        appendLine("  tcp-read-write-timeout: $readWriteTimeoutMillis")
        appendLine("  log-file: '${yamlEscape(logFile.absolutePath)}'")
        appendLine("  log-level: $logLevel")
        appendLine("  limit-nofile: 4096")
    }

    private fun yamlEscape(value: String): String =
        value.replace("\\", "\\\\").replace("'", "''")
}

data class Tun2SocksStats(
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
) {
    companion object {
        val ZERO = Tun2SocksStats(0, 0, 0, 0)
    }
}
