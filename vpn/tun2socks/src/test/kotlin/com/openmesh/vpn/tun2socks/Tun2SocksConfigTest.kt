package com.openmesh.vpn.tun2socks

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Tun2SocksConfigTest {

    private fun config(
        port: Int = 1080,
        username: String? = null,
        password: String? = null,
    ) = Tun2SocksConfig(
        socksPort = port,
        socksUsername = username,
        socksPassword = password,
        logFile = File("openmesh-hev.log"),
    )

    @Test
    fun yamlContainsRequiredSectionsAndValues() {
        val yaml = config().toYaml()
        assertTrue(yaml.contains("tunnel:"))
        assertTrue(yaml.contains("  mtu: 1500"))
        assertTrue(yaml.contains("  ipv4: 10.111.0.2"))
        assertTrue(yaml.contains("  ipv6: 'fd00:111::2'"))
        assertTrue(yaml.contains("socks5:"))
        assertTrue(yaml.contains("  port: 1080"))
        assertTrue(yaml.contains("  address: 127.0.0.1"))
        assertTrue(yaml.contains("misc:"))
        assertTrue(yaml.contains("  log-level: info"))
        val escapedPath = File("openmesh-hev.log").absolutePath
            .replace("\\", "\\\\")
        assertTrue(yaml.contains("  log-file: '$escapedPath'"))
    }

    @Test
    fun credentialsAreIncludedOnlyWhenComplete() {
        val withCredentials = config(username = "node", password = "se'cret").toYaml()
        assertTrue(withCredentials.contains("  username: 'node'"))
        // A single quote is escaped YAML-style by doubling it.
        assertTrue(withCredentials.contains("  password: 'se''cret'"))

        val withoutCredentials = config().toYaml()
        assertTrue(!withoutCredentials.contains("username:"))
        assertTrue(!withoutCredentials.contains("password:"))
    }

    @Test
    fun rejectsInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) { config(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(port = 70000) }
    }

    @Test
    fun rejectsInvalidMtu() {
        assertThrows(IllegalArgumentException::class.java) {
            Tun2SocksConfig(socksPort = 1080, mtu = 100, logFile = File("x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tun2SocksConfig(socksPort = 1080, mtu = 20_000, logFile = File("x"))
        }
    }

    @Test
    fun rejectsHalfCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            config(username = "node", password = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(username = null, password = "secret")
        }
    }
}
