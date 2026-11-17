package com.openmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openmesh.gateway.GatewayServer
import com.openmesh.gateway.socks5.DestinationPolicy
import com.openmesh.gateway.socks5.Socks5Credentials
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground listener for the P2 direct gateway.
 *
 * It listens on all interfaces because the client is usually another phone on
 * the same Wi-Fi network or hotspot. It is not a relay: every accepted
 * connection exits to the Internet from this device.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var server: GatewayServer? = null
    private var startJob: Job? = null
    private var statsJob: Job? = null
    private var foregroundStarted = false

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * The gateway is started and stopped through explicit intents; there is no
     * bound interface to expose.
     */
    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> Unit
            else -> return START_NOT_STICKY
        }

        startForegroundNow(getString(R.string.gateway_notification_starting))
        synchronized(lock) {
            if (server != null || startJob?.isActive == true) {
                return START_NOT_STICKY
            }
            stopping = false
            val port = GatewayPreferences.port(this)
            GatewayStateStore.update {
                it.copy(starting = true, port = port, error = null)
            }
            startJob = scope.launch { startGateway(port) }
        }
        return START_NOT_STICKY
    }

    private suspend fun startGateway(port: Int) {
        var started: GatewayServer? = null
        try {
            GatewayStateStore.update { it.copy(starting = true, error = null) }
            val identity = GatewayIdentityStore.loadOrCreate(this)
            val token = GatewayPreferences.token(this)
            val gateway = GatewayServer(
                identity = identity,
                credentials = Socks5Credentials(GATEWAY_USERNAME, token),
                listenAddress = InetAddress.getByName("0.0.0.0"),
                port = port,
                // The exact host test-server pair is reachable only in debug
                // builds. Release builds never contain this allowlist.
                policy = DestinationPolicy(
                    allowPrivateDestinations = false,
                    labAllowlist = if (BuildConfig.DEBUG) LAB_ALLOWLIST else emptySet(),
                ),
            ).start()
            started = gateway
            synchronized(lock) {
                if (stopping) {
                    gateway.close()
                    return
                }
                server = gateway
                startJob = null
            }
            GatewayStateStore.set(
                GatewayStatus(
                    running = true,
                    port = gateway.boundPort,
                    fingerprint = identity.fingerprint,
                    startedAtMillis = System.currentTimeMillis(),
                ),
            )
            Log.i(
                TAG,
                "gateway listening on 0.0.0.0:${gateway.boundPort} " +
                    "fingerprint=${identity.fingerprint}",
            )
            updateNotification(
                getString(R.string.gateway_notification_listening, gateway.boundPort),
            )
            startStats(gateway)
        } catch (e: Exception) {
            try {
                started?.close()
            } catch (_: Exception) {
                // best effort
            }
            synchronized(lock) {
                server = null
                startJob = null
            }
            GatewayStateStore.update {
                it.copy(
                    running = false,
                    starting = false,
                    error = "Gateway failed to start: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            Log.w(TAG, "gateway failed to start", e)
            emitForegroundStop()
            stopSelf()
        }
    }

    private fun startStats(gateway: GatewayServer) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MILLIS)
                val snapshot = gateway.metrics.snapshot()
                GatewayStateStore.update {
                    it.copy(
                        accepted = snapshot.accepted,
                        denied = snapshot.denied,
                        active = snapshot.active,
                        bytesToRemote = snapshot.bytesToRemote,
                        bytesFromRemote = snapshot.bytesFromRemote,
                    )
                }
            }
        }
    }

    private fun stopGateway() {
        synchronized(lock) {
            stopping = true
            startJob?.cancel()
            startJob = null
            statsJob?.cancel()
            statsJob = null
            val current = server
            server = null
            try {
                current?.close()
            } catch (_: Exception) {
                // best effort
            }
        }
        GatewayStateStore.update {
            it.copy(
                running = false,
                starting = false,
                accepted = 0,
                denied = 0,
                active = 0,
                bytesToRemote = 0,
                bytesFromRemote = 0,
                startedAtMillis = null,
            )
        }
        emitForegroundStop()
    }

    override fun onDestroy() {
        stopGateway()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.gateway_notification_channel_description)
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
            2,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_openmesh_notification)
            .setContentTitle(getString(R.string.gateway_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.gateway_notification_stop_action), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.openmesh.gateway.action.START"
        const val ACTION_STOP = "com.openmesh.gateway.action.STOP"

        private const val TAG = "OpenMeshGateway"
        private const val CHANNEL_ID = "openmesh_gateway_status"
        private const val NOTIFICATION_ID = 2001
        private const val STATS_INTERVAL_MILLIS = 1_000L
        private const val GATEWAY_USERNAME = "openmesh"

        // Debug-only exact destination used by the host test server.
        private val LAB_ALLOWLIST = setOf("10.0.2.2:8080")

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, GatewayService::class.java))
            }
        }
    }
}
