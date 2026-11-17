package com.openmesh.vpn.android

import com.openmesh.gateway.socks5.Socks5Credentials

/**
 * The loopback SOCKS5 server that tun2socks talks to. Implementations differ
 * only in what is behind it: a direct socket (P1 lab) or an authenticated TLS
 * gateway (P2).
 */
interface SocksFrontend : AutoCloseable {
    val port: Int
    val socksCredentials: Socks5Credentials
}
