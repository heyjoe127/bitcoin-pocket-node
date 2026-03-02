package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import com.pocketnode.power.PowerModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Open a Lightning channel to a peer node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenChannelScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPeerBrowser: () -> Unit = {},
    prefillNodeId: String = "",
    prefillAddress: String = "",
    prefillAlias: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lightning = remember { LightningService.getInstance(context) }
    val lightningState by LightningService.stateFlow.collectAsState()

    var nodeId by remember { mutableStateOf(prefillNodeId) }
    var address by remember { mutableStateOf(prefillAddress) }
    var amountSats by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val powerMode by PowerModeManager.modeFlow.collectAsState()
    val isAwayMode = powerMode == PowerModeManager.Mode.AWAY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open Channel") },
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
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Open a Lightning Channel", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect to a Lightning peer and open a payment channel. You need on-chain funds in your Lightning wallet to open a channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Available: ${"%,d".format(lightningState.onchainBalanceSats)} sats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF9800)
                    )
                }
            }

            // Peer details
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nodeId,
                        onValueChange = { nodeId = it.trim(); error = null; result = null },
                        label = { Text("Peer Node ID") },
                        placeholder = { Text("02abc...def") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim(); error = null; result = null },
                        label = { Text("Peer Address") },
                        placeholder = { Text("ip:port or .onion:port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = amountSats,
                        onValueChange = { amountSats = it.filter { c -> c.isDigit() }; error = null; result = null },
                        label = { Text("Channel Amount (sats)") },
                        placeholder = { Text("100000") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        supportingText = {
                            Text("Minimum ~20,000 sats recommended")
                        }
                    )
                }
            }

            // Open button
            Button(
                onClick = {
                    when {
                        nodeId.length < 60 -> { error = "Invalid node ID (should be 66 hex characters)"; return@Button }
                        address.isBlank() -> { error = "Enter peer address (ip:port)"; return@Button }
                        (amountSats.toLongOrNull() ?: 0) < 1000 -> { error = "Amount too small"; return@Button }
                    }
                    opening = true
                    error = null
                    result = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            lightning.openChannel(nodeId, address, amountSats.toLong())
                        }.onSuccess {
                            result = "Channel opening initiated!"
                            opening = false
                        }.onFailure {
                            error = it.message ?: "Failed to open channel"
                            opening = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !isAwayMode && !opening && nodeId.isNotBlank() && address.isNotBlank() && amountSats.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800),
                    disabledContainerColor = if (isAwayMode) Color(0xFF607D8B).copy(alpha = 0.3f)
                        else ButtonDefaults.buttonColors().disabledContainerColor
                )
            ) {
                if (opening) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Opening...")
                } else {
                    Text("⚡ Open Channel")
                }
            }

            // Away mode warning
            if (isAwayMode) {
                Text(
                    "🚶 Channel opens are disabled in Away Mode. Switch to Low or Max to open channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF607D8B)
                )
            }

            // Result / Error
            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                ) {
                    Text(result!!, modifier = Modifier.padding(16.dp), color = Color(0xFF4CAF50))
                }
            }
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(error!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
            }

            // Peer browser
            if (prefillAlias.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                ) {
                    Text(
                        "Selected: $prefillAlias",
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedButton(
                onClick = onNavigateToPeerBrowser,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔍 Browse Peers")
            }
        }
    }
}
