package com.pocketnode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.oracle.OracleResult

/**
 * Fair Trade — sovereign fiat-to-BTC converter using UTXOracle price.
 * No external APIs. Your node, your price, your conversion.
 */
@Composable
fun FairTradeCard(
    oraclePrice: Int? // USD price from UTXOracle, null if not available
) {
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var expanded by remember { mutableStateOf(false) }
    var fiatInput by remember { mutableStateOf("") }
    var copiedField by remember { mutableStateOf<String?>(null) }

    // Reset copied indicator after delay
    LaunchedEffect(copiedField) {
        if (copiedField != null) {
            kotlinx.coroutines.delay(1500)
            copiedField = null
        }
    }

    val fiatAmount = fiatInput.toDoubleOrNull()
    val btcAmount = if (fiatAmount != null && oraclePrice != null && oraclePrice > 0) {
        fiatAmount / oraclePrice
    } else null
    val satsAmount = if (btcAmount != null) (btcAmount * 100_000_000).toLong() else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Collapsed view ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚖️", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (oraclePrice != null) "Sovereign Converter" else "Sovereign Converter — waiting for price…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

            }

            // ── Expanded view ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(12.dp))

                    // Fiat input
                    OutlinedTextField(
                        value = fiatInput,
                        onValueChange = { fiatInput = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount (USD)") },
                        placeholder = { Text("e.g. 40") },
                        prefix = { Text("$ ", fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF9800),
                            cursorColor = Color(0xFFFF9800)
                        )
                    )

                    if (btcAmount != null && satsAmount != null) {
                        Spacer(Modifier.height(16.dp))

                        // BTC result
                        ResultRow(
                            label = "BTC",
                            value = "%.8f".format(btcAmount),
                            isCopied = copiedField == "btc",
                            onCopy = {
                                clipboardManager.setText(AnnotatedString("%.8f".format(btcAmount)))
                                copiedField = "btc"
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        // Sats result
                        ResultRow(
                            label = "Sats",
                            value = "%,d".format(satsAmount),
                            isCopied = copiedField == "sats",
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(satsAmount.toString()))
                                copiedField = "sats"
                            }
                        )

                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (oraclePrice != null) "Using UTXOracle price: $${"%,d".format(oraclePrice)} USD/BTC"
                        else "Price not yet available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    isCopied: Boolean,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCopied) Color(0xFF4CAF50).copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isCopied) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isCopied) "✓" else "📋",
                    fontSize = 16.sp
                )
            }
        }
    }
}
