package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.service.BitcoindService
import com.pocketnode.service.ElectrumService

/**
 * Screen for connecting external wallet apps to the local Electrum server (BWT).
 * Shows BWT status, connection details, and lets users add xpubs/addresses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val electrumState by ElectrumService.stateFlow.collectAsState()
    val electrumRunning by ElectrumService.isRunningFlow.collectAsState()
    val nodeRunning by BitcoindService.isRunningFlow.collectAsState()

    var showAddXpub by remember { mutableStateOf(false) }
    var showAddAddress by remember { mutableStateOf(false) }
    var xpubInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }

    val xpubs = remember { mutableStateListOf<String>() }
    val addresses = remember { mutableStateListOf<String>() }

    // Load saved config
    LaunchedEffect(Unit) {
        xpubs.clear()
        xpubs.addAll(ElectrumService.SavedConfig.getXpubs(context))
        addresses.clear()
        addresses.addAll(ElectrumService.SavedConfig.getAddresses(context))
    }

    val electrumService = remember { ElectrumService(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Bitcoin RPC card
            val connClip = LocalClipboardManager.current
            val rpcCreds = remember { com.pocketnode.util.ConfigGenerator.readCredentials(context) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bitcoin RPC", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    CopyableValue("Host", "127.0.0.1", connClip)
                    CopyableValue("Port", "8332", connClip)
                    CopyableValue("User", rpcCreds?.first ?: "—", connClip)
                    CopyableValue("Password", rpcCreds?.second ?: "—", connClip)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "For any app with direct RPC support.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Electrum Server card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (electrumState.status) {
                        ElectrumService.ElectrumState.Status.RUNNING -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                        ElectrumService.ElectrumState.Status.ERROR -> if (nodeRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Electrum Server", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (statusText, statusColor) = when (electrumState.status) {
                            ElectrumService.ElectrumState.Status.RUNNING -> "Running" to Color(0xFF4CAF50)
                            ElectrumService.ElectrumState.Status.STARTING -> "Starting..." to Color(0xFFFF9800)
                            ElectrumService.ElectrumState.Status.SYNCING -> "Syncing ${(electrumState.syncProgress * 100).toInt()}%" to Color(0xFFFF9800)
                            ElectrumService.ElectrumState.Status.ERROR -> if (!nodeRunning) "Waiting for node..." to Color(0xFFFF9800) else "Error" to MaterialTheme.colorScheme.error
                            ElectrumService.ElectrumState.Status.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        }
                        Text("●", color = statusColor)
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }

                    if (electrumState.status == ElectrumService.ElectrumState.Status.RUNNING) {
                        Spacer(Modifier.height(8.dp))
                        CopyableValue("Host", ElectrumService.ELECTRUM_HOST, connClip)
                        CopyableValue("Port", ElectrumService.ELECTRUM_PORT.toString(), connClip)
                        Text("TCP (no SSL)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect a wallet app like BlueWallet to this address. Make sure to add your wallet's xpub below so the server knows which addresses to track.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    if (electrumState.status == ElectrumService.ElectrumState.Status.RUNNING && xpubs.isEmpty() && addresses.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ No wallets tracked. Add your xpub below to see balances and transactions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }

                    if (electrumState.error != null) {
                        Text(electrumState.error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Auto-start Electrum server when wallets are configured
            val hasConfig = xpubs.isNotEmpty() || addresses.isNotEmpty()
            LaunchedEffect(hasConfig, electrumRunning) {
                if (hasConfig && !electrumRunning) {
                    electrumService.start()
                }
            }

            // Tracked xpubs/addresses
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Electrum Tracked Wallets", fontWeight = FontWeight.Bold)
                    Text(
                        "This is a lightweight Electrum server for mobile. Add the wallets you want to track here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    if (xpubs.isEmpty() && addresses.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No wallets configured yet.\nAdd your wallet's xpub to track all addresses,\nor add individual addresses.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (xpubs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("WALLETS", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        xpubs.forEach { xpub ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFFF7931A))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${xpub.take(8)}...${xpub.takeLast(8)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        when {
                                            xpub.startsWith("zpub") -> "Native SegWit (zpub)"
                                            xpub.startsWith("ypub") -> "SegWit (ypub)"
                                            xpub.startsWith("xpub") -> "Legacy (xpub)"
                                            else -> "Extended public key"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                                IconButton(onClick = {
                                    ElectrumService.SavedConfig.removeXpub(context, xpub)
                                    xpubs.remove(xpub)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    if (addresses.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("ADDRESSES", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        addresses.forEach { addr ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Tag, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${addr.take(8)}...${addr.takeLast(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    ElectrumService.SavedConfig.removeAddress(context, addr)
                                    addresses.remove(addr)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    // Add buttons
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddXpub = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFF7931A)
                            )
                        ) {
                            Text("+ Wallet", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showAddAddress = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("+ Address", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // LNDHub card — connect external Lightning wallets
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚡ Lightning Wallet (LNDHub)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect BlueWallet or Zeus (LNDHub mode) to your Lightning node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(12.dp))

                    val lndhubUrl = "lndhub://pocketnode:pocketnode@http://127.0.0.1:${com.pocketnode.lightning.LndHubServer.PORT}"
                    Text("LNDHub URL", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text(
                        lndhubUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { connClip.setText(AnnotatedString(lndhubUrl)) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy LNDHub URL", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "BlueWallet: Add Wallet \u2192 Import Wallet \u2192 paste URL.\nZeus: Settings \u2192 Add node \u2192 LNDHub \u2192 paste URL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Lightning / Neutrino card (only when block filters installed)
            val filterDir = context.filesDir.resolve("bitcoin/indexes/blockfilter/basic")
            val hasFilters = filterDir.exists() && (filterDir.listFiles()?.any { it.name.startsWith("fltr") } == true)
            if (hasFilters) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFB300).copy(alpha = 0.15f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚡ Lightning (BIP 157/158)", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        CopyableValue("Host", "127.0.0.1", connClip)
                        CopyableValue("Port", "8333", connClip)
                        Text("Compact block filters enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect any Neutrino-compatible Lightning wallet. " +
                            "Note: pruned nodes advertise NODE_NETWORK_LIMITED, so Neutrino clients " +
                            "may sync via internet peers instead of localhost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    // Add xpub dialog
    if (showAddXpub) {
        val clipboardManager = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showAddXpub = false },
            title = { Text("Add Extended Public Key") },
            text = {
                Column {
                    Text(
                        "Paste your wallet's extended public key. BlueWallet uses zpub — find it in wallet settings. xpub and ypub are also supported.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = xpubInput,
                        onValueChange = { xpubInput = it },
                        label = { Text("zpub / xpub / ypub") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { xpubInput = it }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = xpubInput.trim()
                        if (trimmed.isNotEmpty()) {
                            ElectrumService.SavedConfig.saveXpub(context, trimmed)
                            xpubs.add(trimmed)
                            xpubInput = ""
                        }
                        showAddXpub = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddXpub = false }) { Text("Cancel") }
            }
        )
    }

    // Add address dialog  
    if (showAddAddress) {
        val clipboardManager = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showAddAddress = false },
            title = { Text("Add Address") },
            text = {
                Column {
                    Text(
                        "Paste a Bitcoin address to watch. Use this for individual addresses you want to monitor.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        label = { Text("Bitcoin address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { addressInput = it }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = addressInput.trim()
                        if (trimmed.isNotEmpty()) {
                            ElectrumService.SavedConfig.saveAddress(context, trimmed)
                            addresses.add(trimmed)
                            addressInput = ""
                        }
                        showAddAddress = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddAddress = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CopyableValue(
    label: String,
    value: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(value)) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
