package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Send a Lightning payment by pasting or scanning a BOLT11 invoice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendPaymentScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val lightning = remember { LightningService.getInstance(context) }

    var invoiceInput by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Payment") },
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
            // Invoice input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Lightning Invoice", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Paste a BOLT11 invoice to pay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = invoiceInput,
                        onValueChange = {
                            invoiceInput = it.trim()
                            error = null
                            result = null
                        },
                        label = { Text("BOLT11 Invoice") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        singleLine = false
                    )

                    Spacer(Modifier.height(8.dp))

                    // Paste from clipboard button
                    OutlinedButton(
                        onClick = {
                            clipboardManager.getText()?.text?.let {
                                invoiceInput = it.trim()
                                error = null
                                result = null
                            }
                        }
                    ) {
                        Text("Paste from clipboard")
                    }
                }
            }

            // Invoice details (decoded preview)
            if (invoiceInput.startsWith("lnbc", ignoreCase = true) ||
                invoiceInput.startsWith("lightning:", ignoreCase = true)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Invoice Preview", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            invoiceInput.take(40) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Pay button
            Button(
                onClick = {
                    if (invoiceInput.isBlank()) {
                        error = "Paste an invoice first"
                        return@Button
                    }
                    sending = true
                    error = null
                    result = null
                    scope.launch {
                        val cleanInvoice = invoiceInput
                            .removePrefix("lightning:")
                            .removePrefix("LIGHTNING:")
                            .trim()
                        withContext(Dispatchers.IO) {
                            lightning.payInvoice(cleanInvoice)
                        }.onSuccess {
                            result = "Payment sent! ID: ${it.take(16)}..."
                            sending = false
                        }.onFailure {
                            error = it.message ?: "Payment failed"
                            sending = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !sending && invoiceInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Text("⚡ Pay Invoice")
                }
            }

            // Result / Error
            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                ) {
                    Text(
                        result!!,
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        error!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
