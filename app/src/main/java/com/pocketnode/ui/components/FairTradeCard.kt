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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sovereign Converter - bidirectional fiat/BTC/sats converter using UTXOracle price.
 * Any field can be edited; the others update automatically.
 * No external APIs. Your node, your price, your conversion.
 */
@Composable
fun FairTradeCard(
    oraclePrice: Int?
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("fair_trade", android.content.Context.MODE_PRIVATE) }

    var expanded by remember { mutableStateOf(false) }
    var copiedField by remember { mutableStateOf<String?>(null) }

    // Track which field the user is editing
    var editingField by remember { mutableStateOf<String?>(null) }

    // Raw input strings
    var usdInput by remember { mutableStateOf(prefs.getString("usd_input", "") ?: "") }
    var btcInput by remember { mutableStateOf(prefs.getString("btc_input", "") ?: "") }
    var satsInput by remember { mutableStateOf(prefs.getString("sats_input", "") ?: "") }

    // Reset copied indicator
    LaunchedEffect(copiedField) {
        if (copiedField != null) {
            kotlinx.coroutines.delay(1500)
            copiedField = null
        }
    }

    // Bidirectional conversion
    fun updateFromUsd(usd: String) {
        usdInput = usd
        val amount = usd.toDoubleOrNull()
        if (amount != null && oraclePrice != null && oraclePrice > 0) {
            val btc = amount / oraclePrice
            val sats = (btc * 100_000_000).toLong()
            btcInput = "%.8f".format(btc)
            satsInput = sats.toString()
        } else {
            btcInput = ""
            satsInput = ""
        }
        prefs.edit().putString("usd_input", usd).putString("btc_input", btcInput).putString("sats_input", satsInput).apply()
    }

    fun updateFromBtc(btc: String) {
        btcInput = btc
        val amount = btc.toDoubleOrNull()
        if (amount != null && oraclePrice != null && oraclePrice > 0) {
            val usd = amount * oraclePrice
            val sats = (amount * 100_000_000).toLong()
            usdInput = "%.2f".format(usd)
            satsInput = sats.toString()
        } else {
            usdInput = ""
            satsInput = ""
        }
        prefs.edit().putString("usd_input", usdInput).putString("btc_input", btc).putString("sats_input", satsInput).apply()
    }

    fun updateFromSats(sats: String) {
        satsInput = sats
        val amount = sats.toLongOrNull()
        if (amount != null && oraclePrice != null && oraclePrice > 0) {
            val btc = amount / 100_000_000.0
            val usd = btc * oraclePrice
            usdInput = "%.2f".format(usd)
            btcInput = "%.8f".format(btc)
        } else {
            usdInput = ""
            btcInput = ""
        }
        prefs.edit().putString("usd_input", usdInput).putString("btc_input", btcInput).putString("sats_input", sats).apply()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Collapsed view
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚖️", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (oraclePrice != null) "Sovereign Converter" else "Sovereign Converter - waiting for price…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Expanded view
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

                    // USD field
                    ConverterField(
                        label = "USD",
                        prefix = "$",
                        value = usdInput,
                        onValueChange = { value ->
                            val filtered = value.filter { c -> c.isDigit() || c == '.' }
                            editingField = "usd"
                            updateFromUsd(filtered)
                        },
                        isCopied = copiedField == "usd",
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(usdInput))
                            copiedField = "usd"
                        },
                        keyboardType = KeyboardType.Decimal,
                        focusManager = focusManager
                    )

                    Spacer(Modifier.height(8.dp))

                    // BTC field
                    ConverterField(
                        label = "BTC",
                        prefix = "₿",
                        value = btcInput,
                        onValueChange = { value ->
                            val filtered = value.filter { c -> c.isDigit() || c == '.' }
                            editingField = "btc"
                            updateFromBtc(filtered)
                        },
                        isCopied = copiedField == "btc",
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(btcInput))
                            copiedField = "btc"
                        },
                        keyboardType = KeyboardType.Decimal,
                        focusManager = focusManager
                    )

                    Spacer(Modifier.height(8.dp))

                    // Sats field
                    ConverterField(
                        label = "Sats",
                        prefix = "丰",
                        value = satsInput,
                        onValueChange = { value ->
                            val filtered = value.filter { c -> c.isDigit() }
                            editingField = "sats"
                            updateFromSats(filtered)
                        },
                        isCopied = copiedField == "sats",
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(satsInput))
                            copiedField = "sats"
                        },
                        keyboardType = KeyboardType.Number,
                        focusManager = focusManager
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (oraclePrice != null) "UTXOracle: $${"%,d".format(oraclePrice)} USD/BTC"
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
private fun ConverterField(
    label: String,
    prefix: String,
    value: String,
    onValueChange: (String) -> Unit,
    isCopied: Boolean,
    onCopy: () -> Unit,
    keyboardType: KeyboardType,
    focusManager: androidx.compose.ui.focus.FocusManager
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = {
            Text(
                "$prefix ",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9800)
            )
        },
        trailingIcon = {
            IconButton(onClick = onCopy) {
                Text(
                    if (isCopied) "✓" else "📋",
                    fontSize = 16.sp,
                    color = if (isCopied) Color(0xFF4CAF50) else Color.Unspecified
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
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
}
