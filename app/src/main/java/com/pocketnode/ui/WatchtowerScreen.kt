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
import androidx.compose.ui.unit.dp
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
                            Text(
                                "URI: ${status.uri.take(20)}...${status.uri.takeLast(15)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
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
                    visualTransformation = PasswordVisualTransformation()
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
        }
    }
}
