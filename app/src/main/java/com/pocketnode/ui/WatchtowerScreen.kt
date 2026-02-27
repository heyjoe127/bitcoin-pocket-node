package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.pocketnode.service.WatchtowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated screen for watchtower setup and status.
 * Detects watchtower on home node via SSH (read-only, never modifies remote config).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchtowerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val wtManager = remember { WatchtowerManager(context) }
    var isConfigured by remember { mutableStateOf(wtManager.isConfigured()) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watchtower") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            // Explanation card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🛡️ Channel Protection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A watchtower monitors your Lightning channels when your phone is offline. " +
                        "If someone tries to broadcast an old channel state, your home node detects " +
                        "the breach and claims your funds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            if (isConfigured) {
                // Status card
                val status = wtManager.getStatus()
                if (status is WatchtowerManager.WatchtowerStatus.Configured) {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "✅ Watchtower Active",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4CAF50)
                            )
                            Text(
                                "Home node is protecting your channels",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Node: ${status.nodeOs}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            // Parse URI into pubkey and host
                            val atIndex = status.uri.indexOf('@')
                            val pubkey = if (atIndex > 0) status.uri.substring(0, atIndex) else status.uri
                            val host = if (atIndex > 0) status.uri.substring(atIndex + 1) else ""

                            // Server Pubkey
                            Text(
                                "Server Pubkey",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Text(
                                pubkey,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            var copiedPubkey by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(pubkey))
                                    copiedPubkey = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(if (copiedPubkey) "Copied ✓" else "Copy Pubkey", style = MaterialTheme.typography.labelSmall)
                            }

                            // Host
                            if (host.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Host",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    host,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                var copiedHost by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(host))
                                        copiedHost = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(if (copiedHost) "Copied ✓" else "Copy Host", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // Lightning wallet setup instructions
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Connect Lightning Wallet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "1. Open your Lightning wallet → Watchtower settings",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "2. Add a new watchtower",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "3. Paste the Server Pubkey and Host separately using the copy buttons above",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Your Lightning wallet connects to the watchtower via Tor automatically. Your channels will be monitored even when your phone is offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Test connection button
                    var testResult by remember { mutableStateOf<String?>(null) }
                    var testing by remember { mutableStateOf(false) }
                    val testScope = rememberCoroutineScope()

                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResult = null
                            testScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val result = testTowerConnection(context)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    testResult = result
                                    testing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Testing connection...")
                        } else {
                            Text("🔌 Test Connection")
                        }
                    }
                    if (testResult != null) {
                        Text(
                            testResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testResult!!.startsWith("✅")) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // Remove button
                    var showRemoveConfirm by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showRemoveConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Remove Watchtower")
                    }
                    if (showRemoveConfirm) {
                        AlertDialog(
                            onDismissRequest = { showRemoveConfirm = false },
                            title = { Text("Remove Watchtower?") },
                            text = {
                                Text("Your Lightning channels will not be protected when the phone is offline. " +
                                     "The watchtower server on your home node will keep running.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    wtManager.remove()
                                    isConfigured = false
                                    showRemoveConfirm = false
                                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            } else {
                // Setup instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Setup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "1. Open your home node's web interface",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "2. Go to Lightning → Advanced Settings → Watchtower",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "3. Enable Watchtower Service and save",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "4. Enter your node's admin credentials below and tap Check",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Credential fields
                val sshPrefs = remember {
                    context.getSharedPreferences("ssh_prefs", android.content.Context.MODE_PRIVATE)
                }
                var host by remember { mutableStateOf(sshPrefs.getString("ssh_host", "") ?: "") }
                var adminUser by remember { mutableStateOf(sshPrefs.getString("ssh_admin_user", "") ?: "") }
                var adminPassword by remember { mutableStateOf("") }
                var showPassword by remember { mutableStateOf(false) }
                val savedPort = remember { sshPrefs.getInt("ssh_port", 22) }
                var checking by remember { mutableStateOf(false) }
                var checkResult by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("e.g. umbrel.local") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = adminUser,
                    onValueChange = { adminUser = it },
                    label = { Text("Admin username") },
                    placeholder = { Text("e.g. umbrel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = adminPassword,
                    onValueChange = { adminPassword = it },
                    label = { Text("Admin password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide" else "Show"
                            )
                        }
                    }
                )

                Button(
                    onClick = {
                        checking = true
                        checkResult = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                wtManager.manualSetup(
                                    host = host,
                                    port = savedPort,
                                    user = adminUser,
                                    password = adminPassword
                                )
                            }
                            checking = false
                            adminPassword = "" // Clear password from memory
                            when (result) {
                                WatchtowerManager.SetupResult.SUCCESS -> {
                                    isConfigured = true
                                    checkResult = ""
                                    // Save host and username for next time
                                    sshPrefs.edit()
                                        .putString("ssh_host", host)
                                        .putString("ssh_admin_user", adminUser)
                                        .putInt("ssh_port", savedPort)
                                        .apply()
                                }
                                WatchtowerManager.SetupResult.NOT_ENABLED ->
                                    checkResult = "Watchtower not enabled. Enable it in Lightning → Advanced → Watchtower on your node, then retry."
                                WatchtowerManager.SetupResult.NO_LND ->
                                    checkResult = "LND not found on this node. Make sure Lightning is installed."
                                WatchtowerManager.SetupResult.NO_URI ->
                                    checkResult = "Watchtower is active but the URI is not available yet. Wait a moment and retry."
                                WatchtowerManager.SetupResult.CONNECTION_FAILED ->
                                    checkResult = "Could not connect. Check the host, username, and password."
                            }
                        }
                    },
                    enabled = !checking && host.isNotEmpty() && adminUser.isNotEmpty() && adminPassword.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Checking...")
                    } else {
                        Text("Check")
                    }
                }

                if (checkResult.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            checkResult,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Keyboard spacer
            Spacer(Modifier.height(200.dp))
        }
    }
}

/**
 * Test watchtower connection: Brontide handshake + Init exchange only.
 * Tries Tor first (direct .onion), falls back to SSH tunnel.
 */
private fun testTowerConnection(context: android.content.Context): String {
    val prefs = context.getSharedPreferences("watchtower_prefs", android.content.Context.MODE_PRIVATE)
    val towerPubKey = prefs.getString("tower_pubkey", null) ?: return "❌ No tower pubkey configured"
    val towerOnion = prefs.getString("tower_onion", null)
    val towerPort = prefs.getInt("tower_port", 9911)

    val native = com.pocketnode.lightning.WatchtowerNative.INSTANCE

    // Get or create client key
    val keyFile = java.io.File(context.filesDir, "watchtower_client_key")
    val clientKey = if (keyFile.exists()) {
        keyFile.readBytes()
    } else {
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        keyFile.writeBytes(key)
        key
    }

    val towerPubKeyBytes = hexToBytes(towerPubKey)

    // Try Tor first if we have an onion address
    if (towerOnion != null && towerOnion.endsWith(".onion")) {
        val torStateDir = java.io.File(context.filesDir, "tor_state").apply { mkdirs() }.absolutePath
        val torCacheDir = java.io.File(context.cacheDir, "tor_cache").apply { mkdirs() }.absolutePath

        val result = native.wtclient_test_connection_tor(
            "$towerOnion:$towerPort",
            towerPubKeyBytes,
            clientKey,
            torStateDir,
            torCacheDir
        )
        if (result == 0) {
            return "✅ Connected via Tor! Brontide handshake successful."
        }
        // Tor failed, try SSH
    }

    // Try SSH tunnel
    val sshHost = prefs.getString("ssh_host", null)
    val sshUser = prefs.getString("ssh_user", null)
    val sshPort = prefs.getInt("ssh_port", 22)
    val sshPassword = prefs.getString("ssh_password", null)

    if (sshHost == null || sshUser == null) {
        return if (towerOnion != null) "❌ Tor connection failed, no SSH fallback configured"
               else "❌ No connection method configured"
    }

    try {
        val jsch = com.jcraft.jsch.JSch()
        val keyFileSSH = java.io.File(context.filesDir, "ssh_key")
        if (keyFileSSH.exists()) jsch.addIdentity(keyFileSSH.absolutePath)

        val session = jsch.getSession(sshUser, sshHost, sshPort)
        session.setConfig("StrictHostKeyChecking", "no")
        if (sshPassword != null) session.setPassword(sshPassword)
        session.connect(15000)

        val localPort = session.setPortForwardingL(0, towerOnion ?: "127.0.0.1", towerPort)

        val result = native.wtclient_test_connection(
            "127.0.0.1:$localPort",
            towerPubKeyBytes,
            clientKey
        )

        session.delPortForwardingL(localPort)
        session.disconnect()

        return if (result == 0) "✅ Connected via SSH tunnel! Brontide handshake successful."
               else "❌ SSH tunnel opened but Brontide handshake failed"
    } catch (e: Exception) {
        return "❌ SSH failed: ${e.message}"
    }
}

private fun hexToBytes(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    for (i in 0 until len step 2) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
    }
    return data
}
