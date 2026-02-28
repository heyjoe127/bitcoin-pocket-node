package com.pocketnode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import kotlinx.coroutines.launch

/**
 * Lightning wallet management screen.
 * Powered by LDK Node — shows status, channels, and basic operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSend: () -> Unit = {},
    onNavigateToReceive: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToOpenChannel: () -> Unit = {},
    onNavigateToSeedBackup: () -> Unit = {},
    onNavigateToWatchtower: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val lightningState by LightningService.stateFlow.collectAsState()

    val lightning = remember { LightningService.getInstance(context) }

    // Poll node state every 1s to catch background start/stop transitions.
    // StateFlow.collectAsState should handle this, but raw Thread starts
    // can race the Compose subscription. This reconciles actual node state.
    var isNodeRunning by remember { mutableStateOf(lightning.isRunning()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val running = lightning.isRunning()
            if (running != isNodeRunning) {
                isNodeRunning = running
            }
            if (running) {
                lightning.updateState()
            }
        }
    }

    // Derive effective status: if node is actually running but StateFlow
    // hasn't caught up yet, override to RUNNING and trigger an update
    val effectiveState = if (isNodeRunning && lightningState.status == LightningService.LightningState.Status.STOPPED) {
        // StateFlow is stale — node started on background thread
        LaunchedEffect(Unit) { lightning.updateState() }
        lightningState.copy(status = LightningService.LightningState.Status.STARTING)
    } else {
        lightningState
    }

    // RPC credentials from existing config
    val rpcPrefs = remember { context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE) }
    val rpcUser = remember { rpcPrefs.getString("rpc_user", "pocketnode") ?: "pocketnode" }
    val rpcPassword = remember { rpcPrefs.getString("rpc_password", "") ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lightning") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Node status banner — shown when not running
            if (effectiveState.status != LightningService.LightningState.Status.RUNNING) {
                val (bannerText, bannerDesc, bannerColor) = when (effectiveState.status) {
                    LightningService.LightningState.Status.STARTING -> Triple(
                        "⏳ Node Starting...",
                        "Connecting to bitcoind and syncing",
                        Color(0xFFFF9800)
                    )
                    LightningService.LightningState.Status.ERROR -> Triple(
                        "⚠️ Node Error",
                        effectiveState.error ?: "Lightning node encountered an error",
                        MaterialTheme.colorScheme.error
                    )
                    else -> Triple(
                        "Lightning Node Off",
                        "Start the node to access your wallet",
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = bannerColor.copy(alpha = 0.12f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (effectiveState.status == LightningService.LightningState.Status.STARTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = bannerColor
                            )
                        }
                        Column {
                            Text(bannerText, fontWeight = FontWeight.Bold, color = bannerColor)
                            Text(
                                bannerDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("LDK Node", fontWeight = FontWeight.Bold)
                        val (statusText, statusColor) = when (effectiveState.status) {
                            LightningService.LightningState.Status.RUNNING -> "Running" to Color(0xFF4CAF50)
                            LightningService.LightningState.Status.STARTING -> "Starting..." to Color(0xFFFF9800)
                            LightningService.LightningState.Status.ERROR -> "Error" to MaterialTheme.colorScheme.error
                            LightningService.LightningState.Status.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = statusColor)
                            Spacer(Modifier.width(4.dp))
                            Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (effectiveState.nodeId != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Node ID", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                effectiveState.nodeId!!.take(16) + "..." + effectiveState.nodeId!!.takeLast(8),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(effectiveState.nodeId!!)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    if (effectiveState.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(effectiveState.error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Powered by LDK (Lightning Dev Kit)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            // Start/Stop button
            if (effectiveState.status == LightningService.LightningState.Status.STOPPED ||
                effectiveState.status == LightningService.LightningState.Status.ERROR) {
                Button(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            lightning.start(rpcUser, rpcPassword)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("⚡ Start Lightning Node")
                }
                if (!lightning.hasSeed()) {
                    TextButton(
                        onClick = onNavigateToSeedBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore existing wallet from seed words")
                    }
                }
            }

            // Action buttons — shown when running
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNavigateToSend,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("⬆️ Send")
                    }
                    Button(
                        onClick = onNavigateToReceive,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("⬇️ Receive")
                    }
                }
                OutlinedButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Payment History")
                }
            }

            // Balances card — shown when running
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Balances", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("On-chain", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("${"%,d".format(effectiveState.onchainBalanceSats)} sats",
                                    fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Lightning", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("${"%,d".format(effectiveState.lightningBalanceSats)} sats",
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Channels card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Channels", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        if (effectiveState.channelCount == 0) {
                            Text(
                                "No channels yet. Open a channel to start using Lightning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onNavigateToOpenChannel) {
                                Text("Open Channel")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Active", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${effectiveState.channelCount}", fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Capacity", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(effectiveState.totalCapacitySats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Inbound", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(effectiveState.totalInboundSats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                            }

                            // Channel list — tap to close
                            Spacer(Modifier.height(12.dp))
                            val channels = remember(effectiveState) { lightning.listChannels() }
                            var selectedChannel by remember { mutableStateOf<org.lightningdevkit.ldknode.ChannelDetails?>(null) }
                            channels.forEach { ch ->
                                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedChannel = ch }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            ch.counterpartyNodeId.take(12) + "...",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        val status = when {
                                            ch.isUsable -> "Active"
                                            ch.isChannelReady -> "Ready"
                                            else -> "Pending"
                                        }
                                        val statusColor = when {
                                            ch.isUsable -> Color(0xFF4CAF50)
                                            ch.isChannelReady -> Color(0xFFFF9800)
                                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        }
                                        Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "${"%,d".format(ch.channelValueSats.toLong())} sats",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        val outbound = ch.outboundCapacityMsat.toLong() / 1000
                                        Text(
                                            "can send ${"%,d".format(outbound)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }

                            // Close channel dialog
                            selectedChannel?.let { ch ->
                                var closing by remember { mutableStateOf(false) }
                                var closeError by remember { mutableStateOf<String?>(null) }
                                AlertDialog(
                                    onDismissRequest = { if (!closing) selectedChannel = null },
                                    title = { Text("Channel Options") },
                                    text = {
                                        Column {
                                            Text(
                                                ch.counterpartyNodeId,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text("${"%,d".format(ch.channelValueSats.toLong())} sats capacity")
                                            if (closeError != null) {
                                                Spacer(Modifier.height(8.dp))
                                                Text(closeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (closing) {
                                                Spacer(Modifier.height(8.dp))
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        // Cooperative close
                                        TextButton(
                                            onClick = {
                                                closing = true
                                                closeError = null
                                                scope.launch {
                                                    lightning.closeChannel(ch.userChannelId, ch.counterpartyNodeId)
                                                        .onSuccess { selectedChannel = null }
                                                        .onFailure { closing = false; closeError = it.message }
                                                }
                                            },
                                            enabled = !closing
                                        ) { Text("Close") }
                                    },
                                    dismissButton = {
                                        Row {
                                            // Force close
                                            TextButton(
                                                onClick = {
                                                    closing = true
                                                    closeError = null
                                                    scope.launch {
                                                        lightning.forceCloseChannel(ch.userChannelId, ch.counterpartyNodeId)
                                                            .onSuccess { selectedChannel = null }
                                                            .onFailure { closing = false; closeError = it.message }
                                                    }
                                                },
                                                enabled = !closing,
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Force Close") }
                                            // Cancel
                                            TextButton(
                                                onClick = { selectedChannel = null },
                                                enabled = !closing
                                            ) { Text("Cancel") }
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = onNavigateToOpenChannel) {
                                Text("Open Another Channel")
                            }
                        }
                    }
                }

                // Fund wallet card
                var depositAddress by remember { mutableStateOf<String?>(null) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Fund Lightning Wallet", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Send bitcoin to this address to fund your Lightning wallet for opening channels.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))

                        if (depositAddress != null) {
                            Text(
                                depositAddress!!,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { clipboardManager.setText(AnnotatedString(depositAddress!!)) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy Address", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    lightning.getOnchainAddress().onSuccess { depositAddress = it }
                                }
                            ) {
                                Text("Generate Deposit Address")
                            }
                        }
                    }
                }

                // Watchtower status card
                val wtPrefs = remember { context.getSharedPreferences("watchtower_prefs", android.content.Context.MODE_PRIVATE) }
                val towerConfigured = remember { wtPrefs.getString("tower_pubkey", null) != null }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (towerConfigured)
                            Color(0xFF1B5E20).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (towerConfigured) {
                            Text("🛡️ Watchtower Active", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            Text(
                                "Your wallet is automatically protected. Your home node watches your channels when this phone is offline and justice data is pushed after each payment.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            val sshHost = wtPrefs.getString("ssh_host", "") ?: ""
                            if (sshHost.isNotEmpty()) {
                                Text(
                                    "Node: $sshHost",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            Text("🛡️ Watchtower", fontWeight = FontWeight.Bold)
                            Text(
                                "Protect your channels when your phone is offline. " +
                                    "Connect to your home node's watchtower to automatically back up channel states.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        OutlinedButton(onClick = onNavigateToWatchtower) {
                            Text(if (towerConfigured) "Watchtower Settings" else "Set Up Watchtower")
                        }
                    }
                }

                // Wallet backup
                OutlinedButton(
                    onClick = onNavigateToSeedBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("\uD83D\uDD11 Wallet Backup")
                }

                // Stop node
                OutlinedButton(
                    onClick = { lightning.stop() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Lightning Node")
                }
            }
        }
    }
}
