package com.pocketnode.ui.lightning

import androidx.compose.foundation.background
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
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet Seed") },
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
                            "Tap below to reveal your 24 seed words. Make sure no one is watching your screen.",
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
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Show Seed Words")
                        }
                    } else {
                        // Display seed words in numbered grid
                        seedWords?.let { words ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 4 columns x 6 rows
                                for (row in 0 until 6) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        for (col in 0 until 4) {
                                            val idx = row * 4 + col
                                            if (idx < words.size) {
                                                Text(
                                                    "${idx + 1}. ${words[idx]}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.weight(1f),
                                                    textAlign = TextAlign.Start
                                                )
                                            }
                                        }
                                    }
                                }
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
                        "Restore from Seed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Restore your Lightning wallet from a 24-word backup. " +
                                "This will replace the current wallet seed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (lightningService.isRunning()) {
                        Text(
                            "Stop Lightning before restoring.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            restoreInput = ""
                            restoreError = null
                            restoreSuccess = false
                            showRestoreDialog = true
                        },
                        enabled = !lightningService.isRunning()
                    ) {
                        Text("Restore from Mnemonic")
                    }

                    if (restoreSuccess) {
                        Text(
                            "Wallet restored successfully. Start Lightning to use it.",
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
