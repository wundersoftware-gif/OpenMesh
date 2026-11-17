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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.openmesh.transport.Fingerprint
import com.openmesh.vpn.android.VpnControl
import com.openmesh.vpn.android.VpnStartOptions
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private enum class ClientMode {
    REMOTE_GATEWAY,
    LAB_DIRECT,
    CAPTURE_ONLY,
}

private data class StartRequest(
    val mode: ClientMode,
    val gateway: GatewayClientConfig?,
)

class MainActivity : ComponentActivity() {

    private val notice = MutableStateFlow<String?>(null)
    private var pendingStart: StartRequest? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingStart?.let(::launchVpn)
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
        val clientConfig = GatewayClientPreferences.load(this)
        val gatewayPort = GatewayPreferences.port(this)
        val gatewayToken = GatewayPreferences.token(this)
        setContent {
            OpenMeshTheme {
                OpenMeshScreen(
                    targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                    targetInstalled = isTargetInstalled(),
                    labAvailable = BuildConfig.DEBUG,
                    initialClientConfig = clientConfig,
                    initialGatewayPort = gatewayPort,
                    initialGatewayToken = gatewayToken,
                    notices = notice,
                    onStart = ::requestStart,
                    onStop = { VpnControl.stop(this) },
                    onStartGateway = { portText ->
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            notice.value = "Gateway port must be a number between 1 and 65535."
                        } else {
                            GatewayPreferences.savePort(this, port)
                            GatewayService.start(this)
                        }
                    },
                    onStopGateway = { GatewayService.stop(this) },
                    onRegenerateGatewayToken = {
                        GatewayService.stop(this)
                        GatewayPreferences.regenerateToken(this)
                    },
                    onClearNotice = { notice.value = null },
                )
            }
        }
    }

    private fun requestStart(request: StartRequest) {
        if (!isTargetInstalled()) {
            notice.value =
                "Target app ${BuildConfig.OPENMESH_TARGET_PACKAGE} is not installed. " +
                    "Install it before starting the VPN."
            return
        }
        if (request.mode == ClientMode.REMOTE_GATEWAY) {
            val config = request.gateway ?: return
            val error = validateGatewayClientConfig(config)
            if (error != null) {
                notice.value = error
                return
            }
            GatewayClientPreferences.save(this, config)
        }
        pendingStart = request
        val prepare = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            notice.value = "VpnService.prepare failed: ${e.message}"
            null
        }
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            launchVpn(request)
        }
    }

    private fun launchVpn(request: StartRequest) {
        val options = when (request.mode) {
            ClientMode.REMOTE_GATEWAY -> {
                val config = request.gateway ?: return
                VpnStartOptions(
                    targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                    gatewayHost = config.host.trim(),
                    gatewayPort = config.port,
                    gatewayFingerprint = Fingerprint.normalize(config.fingerprint),
                    gatewayToken = config.token.trim(),
                )
            }
            ClientMode.LAB_DIRECT -> VpnStartOptions(
                targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                labDirectSocks = true,
                labAllowlist = setOf(LAB_ALLOWLIST_ENTRY),
            )
            ClientMode.CAPTURE_ONLY -> VpnStartOptions(
                targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
            )
        }
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
    initialClientConfig: GatewayClientConfig,
    initialGatewayPort: Int,
    initialGatewayToken: String,
    notices: StateFlow<String?>,
    onStart: (StartRequest) -> Unit,
    onStop: () -> Unit,
    onStartGateway: (String) -> Unit,
    onStopGateway: () -> Unit,
    onRegenerateGatewayToken: () -> String,
    onClearNotice: () -> Unit,
) {
    val status by remember { VpnControl.observeStatus() }.collectAsStateWithLifecycle()
    val gatewayStatus by remember { GatewayStateStore.status }.collectAsStateWithLifecycle()
    val notice by notices.collectAsStateWithLifecycle()
    var gatewayPortText by rememberSaveable { mutableStateOf(initialGatewayPort.toString()) }
    var gatewayToken by rememberSaveable { mutableStateOf(initialGatewayToken) }
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

            TargetCard(targetPackage, targetInstalled, labAvailable)

            ClientSection(
                status = status,
                labAvailable = labAvailable,
                targetInstalled = targetInstalled,
                initialConfig = initialClientConfig,
                onStart = onStart,
                onStop = onStop,
            )

            GatewayCard(
                status = gatewayStatus,
                portText = gatewayPortText,
                token = gatewayToken,
                onPortChange = { gatewayPortText = it },
                onStart = { onStartGateway(gatewayPortText) },
                onStop = onStopGateway,
                onRegenerate = { gatewayToken = onRegenerateGatewayToken() },
            )

            StatsCard(status)

            ProbeCard(status)

            DetailsCard(status)

            status.lastError?.let { ErrorCard(it) }
            gatewayStatus.error?.let { ErrorCard(it) }

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
private fun ModeWarning(mode: ClientMode) {
    val text = when (mode) {
        ClientMode.REMOTE_GATEWAY ->
            "P2 DIRECT GATEWAY · TUN → tun2socks → authenticated TLS 1.3 gateway → " +
                "Internet. One gateway, no relay and no multi-hop. Route and Internet become " +
                "VERIFIED only after a real HTTPS probe through the gateway."
        ClientMode.LAB_DIRECT ->
            "LAB MODE · direct SOCKS. Traffic bypasses any gateway and goes straight to the " +
                "destination. This is a P1 plumbing test, not a mesh and not an Internet " +
                "verification."
        ClientMode.CAPTURE_ONLY ->
            "P0 CAPTURE ONLY · packets from the target app are drained and discarded. " +
                "Nothing pretends to reach the Internet. This exists to prove fail-closed."
    }
    Surface(
        color = OpenMeshPalette.AmberDeep,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OpenMeshPalette.Amber.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
    ) {
        Text(
            text = text,
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
                    "not WhatsApp. It validates capture, the gateway path and fail-closed " +
                    "without touching WhatsApp.",
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
private fun ClientSection(
    status: VpnStatus,
    labAvailable: Boolean,
    targetInstalled: Boolean,
    initialConfig: GatewayClientConfig,
    onStart: (StartRequest) -> Unit,
    onStop: () -> Unit,
) {
    val initialMode = if (!labAvailable ||
        initialConfig.fingerprint.isBlank() ||
        initialConfig.token.isBlank()
    ) {
        ClientMode.REMOTE_GATEWAY
    } else {
        ClientMode.LAB_DIRECT
    }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var host by rememberSaveable { mutableStateOf(initialConfig.host) }
    var portText by rememberSaveable { mutableStateOf(initialConfig.port.toString()) }
    var fingerprint by rememberSaveable { mutableStateOf(initialConfig.fingerprint) }
    var token by rememberSaveable { mutableStateOf(initialConfig.token) }
    val changeable = !status.isActive

    OpenMeshCard {
        Text(
            text = "CLIENT MODE",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        ModeRow(
            title = "Direct gateway (P2)",
            subtitle = "TLS 1.3 to another OpenMesh phone",
            selected = mode == ClientMode.REMOTE_GATEWAY,
            enabled = changeable,
            onSelect = { mode = ClientMode.REMOTE_GATEWAY },
        )
        if (labAvailable) {
            Spacer(Modifier.height(8.dp))
            ModeRow(
                title = "Lab direct (P1)",
                subtitle = "Loopback SOCKS5, no gateway",
                selected = mode == ClientMode.LAB_DIRECT,
                enabled = changeable,
                onSelect = { mode = ClientMode.LAB_DIRECT },
            )
        }
        Spacer(Modifier.height(8.dp))
        ModeRow(
            title = "Capture only (P0)",
            subtitle = "Drain captured packets, fail closed",
            selected = mode == ClientMode.CAPTURE_ONLY,
            enabled = changeable,
            onSelect = { mode = ClientMode.CAPTURE_ONLY },
        )
        Spacer(Modifier.height(12.dp))
        ModeWarning(mode)

        if (mode == ClientMode.REMOTE_GATEWAY) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Gateway host") },
                placeholder = { Text("192.168.1.20 or 10.0.2.2") },
                singleLine = true,
                enabled = changeable,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { value ->
                    if (value.length <= 5 && value.all { it.isDigit() }) portText = value
                },
                label = { Text("Gateway port") },
                singleLine = true,
                enabled = changeable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = fingerprint,
                onValueChange = { fingerprint = it },
                label = { Text("SHA-256 fingerprint") },
                placeholder = { Text("64 hex characters; colons optional") },
                minLines = 2,
                maxLines = 3,
                enabled = changeable,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Gateway token") },
                singleLine = true,
                enabled = changeable,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Copy the fingerprint and token from the gateway phone. The " +
                    "certificate is pinned; a different certificate aborts the connection.",
                color = OpenMeshPalette.Faint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        if (!changeable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Stop the VPN to change the client mode or gateway configuration.",
                color = OpenMeshPalette.Amber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (!status.isActive) {
            Button(
                onClick = {
                    val config = GatewayClientConfig(
                        host = host,
                        port = portText.toIntOrNull() ?: 0,
                        fingerprint = fingerprint,
                        token = token,
                    )
                    onStart(StartRequest(mode, if (mode == ClientMode.REMOTE_GATEWAY) config else null))
                },
                enabled = targetInstalled,
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
                    text = when (mode) {
                        ClientMode.REMOTE_GATEWAY -> "Start gateway tunnel (P2)"
                        ClientMode.LAB_DIRECT -> "Start lab tunnel (P1)"
                        ClientMode.CAPTURE_ONLY -> "Start capture-only VPN (P0)"
                    },
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
private fun ModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        color = if (selected) {
            OpenMeshPalette.Mint.copy(alpha = 0.08f)
        } else {
            OpenMeshPalette.CardElevated
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) OpenMeshPalette.Mint.copy(alpha = 0.45f) else OpenMeshPalette.Line,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = OpenMeshPalette.Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun GatewayCard(
    status: GatewayStatus,
    portText: String,
    token: String,
    onPortChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val running = status.running || status.starting
    OpenMeshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "GATEWAY (THIS PHONE)",
                    color = OpenMeshPalette.Faint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        status.running -> "Listening"
                        status.starting -> "Starting"
                        else -> "Stopped"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            StatusPill(
                label = when {
                    status.running -> "RUNNING"
                    status.starting -> "STARTING"
                    else -> "STOPPED"
                },
                color = when {
                    status.running -> OpenMeshPalette.Mint
                    status.starting -> OpenMeshPalette.Amber
                    else -> OpenMeshPalette.Faint
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A client phone can use this device as its direct exit to the Internet. " +
                "There is no relay, no multi-hop and no traffic forwarding between clients.",
            color = OpenMeshPalette.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (running) {
            DetailRow("Port", status.port.toString())
        } else {
            OutlinedTextField(
                value = portText,
                onValueChange = { value ->
                    if (value.length <= 5 && value.all { it.isDigit() }) onPortChange(value)
                },
                label = { Text("Listen port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "SHA-256 FINGERPRINT",
            color = OpenMeshPalette.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        val fingerprint = status.fingerprint
        if (fingerprint != null) {
            SelectionContainer {
                Text(
                    text = Fingerprint.format(fingerprint),
                    color = OpenMeshPalette.Mint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                )
            }
        } else {
            Text(
                text = "Start the gateway to create and display its certificate identity.",
                color = OpenMeshPalette.Faint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "SESSION TOKEN",
            color = OpenMeshPalette.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = token,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The client needs the fingerprint and this token. Long-press to copy.",
            color = OpenMeshPalette.Faint,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (running) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = OpenMeshPalette.Coral,
                ),
            ) {
                Text("Stop gateway", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenMeshPalette.Mint,
                    contentColor = OpenMeshPalette.Ink,
                ),
            ) {
                Text("Start gateway", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Regenerate token (stops the gateway)",
                color = OpenMeshPalette.Faint,
                fontSize = 11.sp,
            )
        }
        if (status.running) {
            Spacer(Modifier.height(8.dp))
            DetailRow("Accepted", status.accepted.toString())
            DetailRow("Denied", status.denied.toString())
            DetailRow("Active", status.active.toString())
            DetailRow("Bytes out", formatBytes(status.bytesToRemote))
            DetailRow("Bytes in", formatBytes(status.bytesFromRemote))
        }
    }
}

@Composable
private fun ProbeCard(status: VpnStatus) {
    val detail = status.probeDetail ?: return
    OpenMeshCard {
        Text(
            text = "GATEWAY PROBE",
            color = OpenMeshPalette.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            color = OpenMeshPalette.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (status.internet) {
                InternetState.VERIFIED -> "Internet verified through the gateway."
                InternetState.FAILED -> "Internet NOT verified."
                InternetState.UNKNOWN -> "Internet probe pending."
            },
            color = when (status.internet) {
                InternetState.VERIFIED -> OpenMeshPalette.Mint
                InternetState.FAILED -> OpenMeshPalette.Coral
                InternetState.UNKNOWN -> OpenMeshPalette.Amber
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        status.probeAtMillis?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Last probe at ${formatClock(it)}",
                color = OpenMeshPalette.Faint,
                fontSize = 10.sp,
            )
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
        DetailRow(
            "Mode",
            when {
                status.labDirect -> "lab direct SOCKS (P1)"
                status.remoteGateway -> "direct gateway (P2)"
                else -> "capture only (P0)"
            },
        )
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

private fun formatClock(epochMillis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(epochMillis))
