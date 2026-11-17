package com.openmesh.vpn.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openmesh.core.model.InternetState
import com.openmesh.core.model.LinkState
import com.openmesh.core.model.OpenMeshException
import com.openmesh.core.model.RouteState
import com.openmesh.core.model.VpnPhase
import com.openmesh.gateway.NoopGatewayAudit
import com.openmesh.gateway.client.GatewayEndpoint
import com.openmesh.gateway.client.RemoteGatewayConnector
import com.openmesh.gateway.socks5.SocketProtector
import com.openmesh.vpn.tun2socks.Tun2SocksConfig
import com.openmesh.vpn.tun2socks.Tun2SocksEngine
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground VpnService for OpenMesh.
 *
 * Responsibilities are deliberately narrow: own the TUN, enforce the
 * per-application allowlist, start the local forwarding path and publish
 * honest state. It does not perform any mesh routing yet. Until a gateway is
 * implemented, release builds run in capture-only mode: packets are drained
 * and dropped instead of leaking through the underlying network.
 */
class OpenMeshVpnService : VpnService(), SocketProtector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var tun: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksEngine? = null
    private var capture: CaptureOnlyEngine? = null
    private var frontend: SocksFrontend? = null
    private var monitor: Job? = null
    private var gatewayMonitor: Job? = null
    private var foregroundStarted = false

    @Volatile
    private var active = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel("Stopped by user", VpnPhase.DISCONNECTED)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundNow(getString(R.string.openmesh_notification_preparing))
        if (active) return START_NOT_STICKY

        val options = VpnPreferences.load(this)
        if (options == null) {
            fail(OpenMeshException.TargetAppNotInstalled("(no saved target)").message ?: "no target")
            return START_NOT_STICKY
        }

        synchronized(lock) {
            try {
                startTunnel(options)
            } catch (e: Throwable) {
                fail(describe(e))
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(options: VpnStartOptions) {
        val target = options.targetPackage
        VpnStateStore.update {
            it.copy(
                phase = VpnPhase.CONNECTING,
                targetPackage = target,
                labDirect = options.labDirectSocks,
                remoteGateway = options.remoteGateway,
                lastError = null,
                link = LinkState.DOWN,
                route = RouteState.NONE,
                gatewayId = null,
                hops = null,
                startedAtMillis = null,
                rxBytes = 0,
                txBytes = 0,
                rxPackets = 0,
                txPackets = 0,
                engineRunning = false,
                internet = InternetState.UNKNOWN,
                probeDetail = null,
                probeAtMillis = null,
            )
        }

        if (!isPackageInstalled(target)) {
            throw OpenMeshException.TargetAppNotInstalled(target)
        }
        if (VpnService.prepare(this) != null) {
            VpnStateStore.update { it.copy(phase = VpnPhase.WAITING_PERMISSION) }
            throw OpenMeshException.VpnPermissionMissing()
        }

        val gatewayEndpoint = options.gatewayEndpoint()
        val gatewayConfigured = !options.gatewayHost.isNullOrBlank() ||
            !options.gatewayFingerprint.isNullOrBlank() ||
            !options.gatewayToken.isNullOrBlank()
        if (gatewayConfigured && gatewayEndpoint == null) {
            throw OpenMeshException.TransportFailure(
                "incomplete gateway configuration: host, fingerprint and token are all required",
            )
        }
        if (options.labDirectSocks && gatewayEndpoint != null) {
            throw OpenMeshException.TransportFailure(
                "lab direct SOCKS and a remote gateway cannot be enabled together",
            )
        }

        var activeFrontend: SocksFrontend? = null
        var established: ParcelFileDescriptor? = null
        var engine: Tun2SocksEngine? = null
        var captureEngine: CaptureOnlyEngine? = null
        try {
            if (options.labDirectSocks) {
                activeFrontend = try {
                    LocalSocksFrontend(
                        protector = this,
                        labAllowlist = options.labAllowlist,
                        audit = NoopGatewayAudit,
                    ).start()
                } catch (e: Exception) {
                    throw OpenMeshException.LocalProxyStartFailed(describe(e))
                }
            } else if (gatewayEndpoint != null) {
                activeFrontend = try {
                    RemoteSocksFrontend(
                        protector = this,
                        endpoint = gatewayEndpoint,
                        audit = NoopGatewayAudit,
                    ).start()
                } catch (e: Exception) {
                    throw OpenMeshException.LocalProxyStartFailed(describe(e))
                }
            }

            val builder = Builder()
                .setSession(SESSION_NAME)
                .setMtu(MTU)
                .addAddress(TUN_IPV4, 32)
                .addAddress(TUN_IPV6, 128)
                // Default routes make the tunnel fail-closed. OpenMesh itself
                // is not in the allowlist, so its own sockets stay outside.
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("9.9.9.9")
                .addAllowedApplication(target)

            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                builder.setConfigureIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            established = try {
                builder.establish()
            } catch (e: Exception) {
                throw OpenMeshException.VpnEstablishFailed(describe(e))
            } ?: throw OpenMeshException.AnotherVpnActive()

            if (activeFrontend != null) {
                val config = Tun2SocksConfig(
                    socksPort = activeFrontend.port,
                    socksUsername = activeFrontend.socksCredentials.username,
                    socksPassword = activeFrontend.socksCredentials.password,
                    mtu = MTU,
                    ipv4 = TUN_IPV4,
                    ipv6 = TUN_IPV6,
                    logFile = File(filesDir, "tun2socks.log"),
                )
                engine = Tun2SocksEngine(
                    configFile = File(filesDir, "tun2socks.yaml"),
                    logFile = config.logFile,
                )
                val started = try {
                    engine.start(established.fd, config)
                } catch (e: Throwable) {
                    throw OpenMeshException.Tun2SocksStartFailed(describe(e))
                }
                if (!started) {
                    throw OpenMeshException.Tun2SocksStartFailed(
                        "native engine did not report running (see tun2socks.log)",
                    )
                }
            } else {
                captureEngine = CaptureOnlyEngine(
                    pfd = established,
                    onCounters = { packets, bytes ->
                        VpnStateStore.update {
                            it.copy(txPackets = packets, txBytes = bytes)
                        }
                    },
                    onStopped = { reason ->
                        if (reason != null) {
                            fail("Capture stopped: $reason")
                        }
                    },
                )
                captureEngine.start()
            }

            synchronized(lock) {
                tun = established
                tun2socks = engine
                capture = captureEngine
                frontend = activeFrontend
                active = true
            }

            val now = System.currentTimeMillis()
            if (engine != null) {
                VpnStateStore.update {
                    it.copy(
                        // A remote tunnel is not "ready" in the routing sense
                        // until the gateway probe succeeds. NO_ROUTE still
                        // means the link is up and fails closed.
                        phase = if (gatewayEndpoint != null) {
                            VpnPhase.NO_ROUTE
                        } else {
                            VpnPhase.TUNNEL_READY
                        },
                        link = LinkState.UP,
                        engineRunning = true,
                        startedAtMillis = now,
                    )
                }
                startStatsMonitor(engine)
                if (gatewayEndpoint != null) {
                    startGatewayMonitor(gatewayEndpoint)
                    updateNotification(getString(R.string.openmesh_notification_probing))
                } else {
                    updateNotification(getString(R.string.openmesh_notification_tunnel_ready))
                }
            } else {
                VpnStateStore.update {
                    it.copy(
                        phase = VpnPhase.CAPTURING,
                        link = LinkState.UP,
                        engineRunning = false,
                        startedAtMillis = now,
                    )
                }
                updateNotification(getString(R.string.openmesh_notification_capture))
            }
        } catch (e: Throwable) {
            captureEngine?.stop()
            engine?.stop()
            activeFrontend?.close()
            try {
                established?.close()
            } catch (_: Exception) {
                // best effort
            }
            throw e
        }
    }

    private fun startStatsMonitor(engine: Tun2SocksEngine) {
        monitor = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MILLIS)
                if (!active) break
                if (!engine.isRunning()) {
                    fail("tun2socks stopped unexpectedly")
                    break
                }
                val stats = engine.stats()
                VpnStateStore.update {
                    it.copy(
                        rxPackets = stats.rxPackets,
                        rxBytes = stats.rxBytes,
                        txPackets = stats.txPackets,
                        txBytes = stats.txBytes,
                    )
                }
            }
        }
    }

    /**
     * Re-probes the gateway instead of trusting a configuration value. Route
     * and Internet in the UI are only ever updated from a real connection
     * through the gateway.
     */
    private fun startGatewayMonitor(endpoint: GatewayEndpoint) {
        gatewayMonitor = scope.launch {
            val probe = RemoteGatewayProbe(
                RemoteGatewayConnector(endpoint = endpoint, protector = this@OpenMeshVpnService),
            )
            var lastNotification: String? = null
            while (isActive && active) {
                val outcome = probe.run()
                if (!active) break
                VpnStateStore.update {
                    it.copy(
                        phase = if (outcome.route == RouteState.SELECTED) {
                            VpnPhase.TUNNEL_READY
                        } else {
                            VpnPhase.NO_ROUTE
                        },
                        route = outcome.route,
                        internet = outcome.internet,
                        gatewayId = if (outcome.route == RouteState.SELECTED) {
                            "${endpoint.host}:${endpoint.port}"
                        } else {
                            null
                        },
                        hops = if (outcome.route == RouteState.SELECTED) 1 else null,
                        probeDetail = outcome.detail,
                        probeAtMillis = System.currentTimeMillis(),
                    )
                }
                val text = when {
                    outcome.internet == InternetState.VERIFIED ->
                        getString(R.string.openmesh_notification_gateway_ok)
                    outcome.route == RouteState.SELECTED ->
                        getString(R.string.openmesh_notification_gateway_no_internet)
                    else -> getString(R.string.openmesh_notification_no_route)
                }
                if (text != lastNotification) {
                    updateNotification(text)
                    lastNotification = text
                }
                delay(GATEWAY_PROBE_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopTunnel(reason: String?, phase: VpnPhase) {
        synchronized(lock) {
            val wasActive = active
            active = false
            monitor?.cancel()
            monitor = null
            gatewayMonitor?.cancel()
            gatewayMonitor = null
            capture?.stop()
            capture = null
            tun2socks?.stop()
            tun2socks = null
            frontend?.close()
            frontend = null
            try {
                tun?.close()
            } catch (_: Exception) {
                // best effort
            }
            tun = null

            VpnStateStore.update {
                it.copy(
                    phase = phase,
                    link = LinkState.DOWN,
                    route = RouteState.NONE,
                    internet = InternetState.UNKNOWN,
                    engineRunning = false,
                    rxBytes = 0,
                    txBytes = 0,
                    rxPackets = 0,
                    txPackets = 0,
                    startedAtMillis = null,
                    lastError = reason,
                    gatewayId = null,
                    hops = null,
                    probeDetail = null,
                    probeAtMillis = null,
                )
            }
            if (wasActive) {
                emitForegroundStop()
            }
        }
    }

    private fun fail(message: String) {
        stopTunnel(message, VpnPhase.ERROR)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel("VPN permission revoked by the system", VpnPhase.REVOKED)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(null, VpnPhase.DISCONNECTED)
        scope.cancel()
        super.onDestroy()
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.openmesh_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.openmesh_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNow(text: String) {
        if (foregroundStarted) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        foregroundStarted = true
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun emitForegroundStop() {
        if (!foregroundStarted) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenMeshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_openmesh_notification)
            .setContentTitle(getString(R.string.openmesh_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.openmesh_notification_stop), stopIntent)
            .build()
    }

    // SocketProtector: OpenMesh itself is not in the VPN allowlist, so its
    // sockets already stay outside the tunnel. Protect anyway for defence in
    // depth in case the allowlist changes.
    override fun protect(socket: Socket): Boolean = super<VpnService>.protect(socket)

    override fun protect(socket: DatagramSocket): Boolean = super<VpnService>.protect(socket)

    // VpnService has no ServerSocket overload. The only ServerSocket OpenMesh
    // opens listens on loopback, which is never routed into the TUN.
    override fun protect(socket: ServerSocket): Boolean = true

    companion object {
        const val ACTION_START = "com.openmesh.vpn.action.START"
        const val ACTION_STOP = "com.openmesh.vpn.action.STOP"

        private const val SESSION_NAME = "OpenMesh VPN"
        private const val MTU = 1500
        private const val TUN_IPV4 = "10.111.0.2"
        private const val TUN_IPV6 = "fd00:111::2"
        private const val NOTIFICATION_CHANNEL_ID = "openmesh_vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val STATS_INTERVAL_MILLIS = 1_000L
        private const val GATEWAY_PROBE_INTERVAL_MILLIS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, OpenMeshVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenMeshVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, OpenMeshVpnService::class.java))
            }
        }
    }
}
