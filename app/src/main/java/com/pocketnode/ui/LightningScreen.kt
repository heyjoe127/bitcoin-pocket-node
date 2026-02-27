package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val lightningState by LightningService.stateFlow.collectAsState()

    val lightning = remember { LightningService.getInstance(context) }

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
                        val (statusText, statusColor) = when (lightningState.status) {
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

                    if (lightningState.nodeId != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Node ID", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lightningState.nodeId!!.take(16) + "..." + lightningState.nodeId!!.takeLast(8),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(lightningState.nodeId!!)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    if (lightningState.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(lightningState.error!!, color = MaterialTheme.colorScheme.error,
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
            if (lightningState.status == LightningService.LightningState.Status.STOPPED ||
                lightningState.status == LightningService.LightningState.Status.ERROR) {
                Button(
                    onClick = {
                        scope.launch {
                            lightning.start(rpcUser, rpcPassword)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("⚡ Start Lightning Node")
                }
            } else if (lightningState.status == LightningService.LightningState.Status.RUNNING) {
                OutlinedButton(
                    onClick = { lightning.stop() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Lightning Node")
                }
            }

            // Balances card — shown when running
            if (lightningState.status == LightningService.LightningState.Status.RUNNING) {
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
                                Text("${"%,d".format(lightningState.onchainBalanceSats)} sats",
                                    fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Lightning", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("${"%,d".format(lightningState.lightningBalanceSats)} sats",
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

                        if (lightningState.channelCount == 0) {
                            Text(
                                "No channels yet. Open a channel to start using Lightning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Active", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${lightningState.channelCount}", fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Capacity", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(lightningState.totalCapacitySats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Inbound", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(lightningState.totalInboundSats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                            }

                            // Channel list
                            Spacer(Modifier.height(12.dp))
                            val channels = remember(lightningState) { lightning.listChannels() }
                            channels.forEach { ch ->
                                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        ch.counterpartyNodeId.take(12) + "...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        "${"%,d".format(ch.channelValueSats.toLong())} sats",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
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
            }
        }
    }
}
