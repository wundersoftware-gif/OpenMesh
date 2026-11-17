package com.openmesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmesh.app.ui.OpenMeshPalette
import com.openmesh.app.ui.OpenMeshTheme
import com.openmesh.core.model.InternetState
import com.openmesh.core.model.LinkState
import com.openmesh.core.model.RouteState
import com.openmesh.core.model.VpnPhase
import com.openmesh.core.model.VpnStatus
import com.openmesh.vpn.android.VpnControl
import com.openmesh.vpn.android.VpnStartOptions
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private val notice = MutableStateFlow<String?>(null)
    private var pendingLabDirect = BuildConfig.DEBUG

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            launchVpn(pendingLabDirect)
        } else {
            notice.value = "VPN permission was not granted. No tunnel was created."
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The foreground service still runs if this is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OpenMeshTheme {
                OpenMeshScreen(
                    targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                    targetInstalled = isTargetInstalled(),
                    labAvailable = BuildConfig.DEBUG,
                    notices = notice,
                    onStart = ::requestStart,
                    onStop = { VpnControl.stop(this) },
                    onClearNotice = { notice.value = null },
                )
            }
        }
    }

    private fun requestStart(labDirect: Boolean) {
        if (!isTargetInstalled()) {
            notice.value =
                "Target app ${BuildConfig.OPENMESH_TARGET_PACKAGE} is not installed. " +
                    "Install it before starting the VPN."
        }
        pendingLabDirect = labDirect
        val prepare = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            notice.value = "VpnService.prepare failed: ${e.message}"
            null
        }
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else if (isTargetInstalled()) {
            launchVpn(labDirect)
        }
    }

    private fun launchVpn(labDirect: Boolean) {
        val options = VpnStartOptions(
            targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
            labDirectSocks = labDirect,
            labAllowlist = if (labDirect) setOf(LAB_ALLOWLIST_ENTRY) else emptySet(),
        )
        try {
            VpnControl.start(this, options)
        } catch (e: Exception) {
            notice.value = "Could not start the VPN service: ${e.message}"
        }
    }

    private fun isTargetInstalled(): Boolean = try {
        packageManager.getPackageInfo(BuildConfig.OPENMESH_TARGET_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val LAB_ALLOWLIST_ENTRY = "10.0.2.2:8080"
    }
}

@Composable
private fun OpenMeshScreen(
    targetPackage: String,
    targetInstalled: Boolean,
    labAvailable: Boolean,
    notices: StateFlow<String?>,
    onStart: (Boolean) -> Unit,
    onStop: () -> Unit,
    onClearNotice: () -> Unit,
) {
    val status by remember { VpnControl.observeStatus() }.collectAsStateWithLifecycle()
    val notice by notices.collectAsStateWithLifecycle()
    var labDirect by rememberSaveable { mutableStateOf(labAvailable) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            onClearNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            HeroCard(status)

            if (labDirect) {
                LabWarning()
            }

            TargetCard(targetPackage, targetInstalled, labAvailable)

            if (labAvailable) {
                LabModeCard(
                    enabled = labDirect,
                    changeable = !status.isActive,
                    onToggle = { labDirect = it },
                )
            } else {
                ReleaseLimitationCard()
            }

            ActionButtons(
                status = status,
                labDirect = labDirect,
                enabled = targetInstalled,
                onStart = { onStart(labDirect) },
                onStop = onStop,
            )

            StatsCard(status)

            DetailsCard(status)

            status.lastError?.let { ErrorCard(it) }

            Footer()
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "OPENMESH",
            color = OpenMeshPalette.Mint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Mesh egress VPN",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Android · no root · no central relay",
            color = OpenMeshPalette.Muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun HeroCard(status: VpnStatus) {
    OpenMeshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "TUNNEL STATUS",
                    color = OpenMeshPalette.Faint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = phaseLabel(status.phase),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            StatusPill(
                label = if (status.isActive) "ACTIVE" else "IDLE",
                color = if (status.isActive) OpenMeshPalette.Mint else OpenMeshPalette.Faint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = phaseExplanation(status.phase),
            color = OpenMeshPalette.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = OpenMeshPalette.Line)
        Spacer(Modifier.height(12.dp))
        StateRow(
            label = "Link",
            value = if (status.link == LinkState.UP) "UP" else "DOWN",
            color = if (status.link == LinkState.UP) OpenMeshPalette.Mint else OpenMeshPalette.Faint,
        )
        StateRow(
            label = "Route",
            value = if (status.route == RouteState.SELECTED) "SELECTED" else "NONE",
            color = if (status.route == RouteState.SELECTED) OpenMeshPalette.Mint
            else OpenMeshPalette.Amber,
        )
        StateRow(
            label = "Internet",
            value = when (status.internet) {
                InternetState.VERIFIED -> "VERIFIED"
                InternetState.FAILED -> "FAILED"
                InternetState.UNKNOWN -> "UNKNOWN"
            },
            color = when (status.internet) {
                InternetState.VERIFIED -> OpenMeshPalette.Mint
                InternetState.FAILED -> OpenMeshPalette.Coral
                InternetState.UNKNOWN -> OpenMeshPalette.Amber
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "These three states are independent. A link or a running engine does not " +
                "prove that a gateway or the Internet is reachable.",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun StateRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = OpenMeshPalette.Muted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun LabWarning() {
    Surface(
        color = OpenMeshPalette.AmberDeep,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OpenMeshPalette.Amber.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
    ) {
        Text(
            text = "LAB MODE · direct SOCKS. Traffic bypasses any mesh and goes straight to " +
                "the destination. This is a P1 plumbing test, not a gateway and not an " +
                "Internet verification.",
            color = OpenMeshPalette.Amber,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun TargetCard(targetPackage: String, installed: Boolean, labAvailable: Boolean) {
    OpenMeshCard {
        Text(
            text = "CAPTURE TARGET",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = targetPackage,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Only this application is routed into the VPN. Every other app keeps " +
                "normal connectivity. No other package is captured.",
            color = OpenMeshPalette.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        if (targetPackage == "com.openmesh.testclient") {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Debug substitution: this build captures the bundled lab traffic app, " +
                    "not WhatsApp. It validates capture mechanics only.",
                color = OpenMeshPalette.Amber,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        if (!installed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Target not installed. Install the target APK before starting.",
                color = OpenMeshPalette.Coral,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!labAvailable) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Release build: capture-only. No mesh gateway exists yet, so captured " +
                    "packets are dropped instead of leaking.",
                color = OpenMeshPalette.Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LabModeCard(enabled: Boolean, changeable: Boolean, onToggle: (Boolean) -> Unit) {
    OpenMeshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Lab direct SOCKS (P1)",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "tun2socks → loopback SOCKS5 → direct socket",
                    color = OpenMeshPalette.Muted,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = changeable,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OpenMeshPalette.Ink,
                    checkedTrackColor = OpenMeshPalette.Mint,
                    uncheckedThumbColor = OpenMeshPalette.Muted,
                    uncheckedTrackColor = OpenMeshPalette.CardElevated,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Loopback SOCKS5 uses a random per-session username and password. " +
                "The only private destination allowed is 10.0.2.2:8080 (host test server). " +
                "Public destinations are permitted by policy, but OpenMesh does not claim " +
                "Internet verification for this path.",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        if (!changeable) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Stop the VPN to change this mode.",
                color = OpenMeshPalette.Amber,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReleaseLimitationCard() {
    OpenMeshCard {
        Text(
            text = "P0 / CAPTURE ONLY",
            color = OpenMeshPalette.Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The mesh transport, TLS gateway and multi-hop routing are not implemented " +
                "in this build. Captured packets are drained and discarded. Nothing pretends " +
                "to reach the Internet.",
            color = OpenMeshPalette.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun ActionButtons(
    status: VpnStatus,
    labDirect: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!status.isActive) {
            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenMeshPalette.Mint,
                    contentColor = OpenMeshPalette.Ink,
                    disabledContainerColor = OpenMeshPalette.Line,
                    disabledContentColor = OpenMeshPalette.Faint,
                ),
            ) {
                Text(
                    text = if (labDirect) "Start lab tunnel (P1)" else "Start capture-only VPN (P0)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        } else {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = OpenMeshPalette.Coral,
                ),
            ) {
                Text("Stop VPN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Emergency stop — restores normal connectivity for the target app",
                    color = OpenMeshPalette.Faint,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(status: VpnStatus) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val startedAt = status.startedAtMillis
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    OpenMeshCard {
        Text(
            text = "SESSION COUNTERS",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("TX", formatBytes(status.txBytes), Modifier.weight(1f))
            StatTile("RX", formatBytes(status.rxBytes), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("PACKETS OUT", status.txPackets.toString(), Modifier.weight(1f))
            StatTile("PACKETS IN", status.rxPackets.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("DURATION", formatDuration(startedAt, now), Modifier.weight(1f))
            StatTile("ENGINE", if (status.engineRunning) "tun2socks" else "capture", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = OpenMeshPalette.CardElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                color = OpenMeshPalette.Faint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DetailsCard(status: VpnStatus) {
    OpenMeshCard {
        Text(
            text = "DETAILS",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        DetailRow("Gateway", status.gatewayId ?: "—")
        DetailRow("Hops", status.hops?.toString() ?: "—")
        DetailRow("Peers", status.peerCount.toString())
        DetailRow("Mode", if (status.labDirect) "lab direct SOCKS" else "capture only")
        DetailRow("Build", "${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "debug" else "release"}")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = OpenMeshPalette.Muted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        color = OpenMeshPalette.CoralDeep,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OpenMeshPalette.Coral.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "LAST ERROR",
                color = OpenMeshPalette.Coral,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun Footer() {
    Text(
        text = "OpenMesh does not modify, automate or reverse-engineer WhatsApp, and it never " +
            "claims success without reproducible evidence. See README and docs/TEST_PLAN.md.",
        color = OpenMeshPalette.Faint,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(bottom = 24.dp),
    )
}

@Composable
private fun OpenMeshCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = OpenMeshPalette.Card,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OpenMeshPalette.Line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun phaseLabel(phase: VpnPhase): String = when (phase) {
    VpnPhase.DISCONNECTED -> "Disconnected"
    VpnPhase.WAITING_PERMISSION -> "Waiting for permission"
    VpnPhase.CONNECTING -> "Connecting"
    VpnPhase.CAPTURING -> "Capturing · fail-closed"
    VpnPhase.TUNNEL_READY -> "Tunnel ready"
    VpnPhase.NO_ROUTE -> "No route"
    VpnPhase.ERROR -> "Error"
    VpnPhase.REVOKED -> "Permission revoked"
}

private fun phaseExplanation(phase: VpnPhase): String = when (phase) {
    VpnPhase.DISCONNECTED ->
        "No VPN interface. The target app uses the normal network."
    VpnPhase.WAITING_PERMISSION ->
        "Android has not granted the VPN consent yet."
    VpnPhase.CONNECTING ->
        "Requesting the TUN interface and starting the local engine."
    VpnPhase.CAPTURING ->
        "Only the target app enters the TUN. Packets are read and dropped: no leak path."
    VpnPhase.TUNNEL_READY ->
        "The target app is routed into tun2socks. Route and Internet are still unproven."
    VpnPhase.NO_ROUTE ->
        "A link exists, but no gateway route has been selected."
    VpnPhase.ERROR ->
        "The tunnel failed or stopped unexpectedly. The target app may use normal networking again."
    VpnPhase.REVOKED ->
        "Android revoked the VPN. The tunnel is closed."
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatDuration(startedAt: Long?, now: Long): String {
    if (startedAt == null) return "—"
    val total = ((now - startedAt) / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
