package com.pocketnode.ui.lightning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.lightning.Bip39
import com.pocketnode.lightning.LightningService

/**
 * Seed backup and restore screen.
 * Allows users to view their 24-word mnemonic and restore from an existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedBackupScreen(
    lightningService: LightningService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showSeed by remember { mutableStateOf(false) }
    var seedWords by remember { mutableStateOf<List<String>?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreInput by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoreSuccess by remember { mutableStateOf(false) }
    var createSuccess by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet Seed and Backup") },
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
            // Warning card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Your seed words are the ONLY way to recover your Lightning wallet. " +
                                "Write them down and store them safely. Never share them with anyone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // View seed section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "View Seed Words",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (!lightningService.hasSeed()) {
                        Text(
                            "No wallet seed found. Start Lightning to generate one, or restore from backup below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (!showSeed) {
                        Text(
                            "Tap below to reveal your seed words. Make sure no one is watching your screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                seedWords = lightningService.getSeedWords()
                                showSeed = true
                                copied = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F)
                            )
                        ) {
                            Text("Show Seed Words", color = Color.White)
                        }
                    } else if (seedWords == null) {
                        Text(
                            "Could not read seed file. The wallet seed may not exist yet, or the file is corrupted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = { showSeed = false }) {
                            Text("Back")
                        }
                    } else {
                        // Display seed words as plain text
                        seedWords?.let { words ->
                            // Check if this seed was restored from mnemonic
                            val lightningDir = java.io.File(context.filesDir, "lightning")
                            val wasRestored = lightningDir.listFiles()?.any {
                                it.name.startsWith("keys_seed.bak.")
                            } == true
                            if (!wasRestored) {
                                Text(
                                    "Generated by LDK using your device's secure random number generator.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            SelectionContainer {
                                Text(
                                    words.joinToString(" "),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(16.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.sp,
                                    lineHeight = 28.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    clipboardManager.setText(
                                        AnnotatedString(words.joinToString(" "))
                                    )
                                    copied = true
                                }) {
                                    Text(if (copied) "Copied!" else "Copy to Clipboard")
                                }
                                OutlinedButton(onClick = {
                                    showSeed = false
                                    seedWords = null
                                    copied = false
                                }) {
                                    Text("Hide")
                                }
                            }
                        }
                    }
                }
            }

            // Restore section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Wallet Setup",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Restore an existing wallet from a 24-word backup, or create a new one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var showCreateConfirm by remember { mutableStateOf(false) }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                if (lightningService.isRunning()) {
                                    lightningService.stop()
                                }
                                restoreInput = ""
                                restoreError = null
                                restoreSuccess = false
                                showRestoreDialog = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Restore", maxLines = 1)
                        }

                        Button(
                            onClick = { showCreateConfirm = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F)
                            )
                        ) {
                            Text("Create New", maxLines = 1, color = Color.White)
                        }
                    }

                    if (showCreateConfirm) {
                        // Dark scrim overlay behind dialog
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { showCreateConfirm = false }
                        )
                        AlertDialog(
                            onDismissRequest = { showCreateConfirm = false },
                            title = {
                                Text("⚠️ Create New Wallet?", color = Color.White)
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFD32F2F), RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            "This will permanently delete your current wallet. You will lose all channels and any value stored. Make sure you have backed up your seed words first.",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Text(
                                        "LDK will generate a new seed using your device's secure random number generator (256-bit entropy). " +
                                        "Lightning will restart with the new wallet.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            confirmButton = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { showCreateConfirm = false },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Text("Cancel", maxLines = 1)
                                    }
                                    Button(
                                        onClick = {
                                            if (lightningService.isRunning()) {
                                                lightningService.stop()
                                            }
                                            val lightningDir = java.io.File(context.filesDir, "lightning")
                                            lightningDir.listFiles()?.forEach { file ->
                                                if (!file.name.startsWith("keys_seed.bak.")) {
                                                    file.deleteRecursively()
                                                }
                                            }
                                            showCreateConfirm = false
                                            restoreSuccess = false
                                            createSuccess = true
                                            val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            val rpcUser = prefs.getString("rpc_user", "pocketnode") ?: "pocketnode"
                                            val rpcPass = prefs.getString("rpc_password", "") ?: ""
                                            if (rpcPass.isNotEmpty()) {
                                                Thread {
                                                    try {
                                                        lightningService.start(rpcUser, rpcPass)
                                                    } catch (_: Exception) {}
                                                }.start()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                    ) {
                                        Text("Delete", maxLines = 1, color = Color.White)
                                    }
                                }
                            }
                        )
                    }

                    if (restoreSuccess) {
                        Text(
                            "Wallet restored successfully. Start Lightning to use it.",
                            color = Color(0xFF4CAF50),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (createSuccess) {
                        Text(
                            "Wallet data cleared. Start Lightning to generate a new seed, then back up your 24 words.",
                            color = Color(0xFF4CAF50),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Powered by LDK attribution
            Text(
                "Powered by LDK (Lightning Dev Kit)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // Restore dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Enter Seed Words") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter your 24 seed words separated by spaces.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = restoreInput,
                        onValueChange = {
                            restoreInput = it
                            restoreError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        placeholder = { Text("abandon ability able about...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {}),
                        maxLines = 8
                    )
                    val wordCount = restoreInput.trim().split("\\s+".toRegex())
                        .filter { it.isNotEmpty() }.size
                    Text(
                        "$wordCount / 24 words",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wordCount == 24) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    restoreError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val words = restoreInput.trim().split("\\s+".toRegex())
                            .filter { it.isNotEmpty() }
                        // Validate first
                        val error = Bip39.validate(words, context)
                        if (error != null) {
                            restoreError = error
                        } else {
                            try {
                                lightningService.restoreFromMnemonic(words)
                                showRestoreDialog = false
                                restoreSuccess = true
                            } catch (e: Exception) {
                                restoreError = e.message ?: "Restore failed"
                            }
                        }
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
