package com.pocketnode.ui.mempool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSearchScreen(
    onNavigateBack: () -> Unit,
    viewModel: TransactionSearchViewModel = viewModel()
) {
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val searchResult by viewModel.searchResult.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Search") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color(0xFFFF9500)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = viewModel::updateSearchText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enter Transaction ID (TXID)") },
                placeholder = { Text("64-character hex string...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide(); viewModel.searchTransaction() }),
                singleLine = true
            )

            Button(
                onClick = { keyboardController?.hide(); viewModel.searchTransaction() },
                modifier = Modifier.fillMaxWidth(),
                enabled = searchText.length == 64 && !isLoading
            ) { Text("Search Transaction") }

            when (val result = searchResult) {
                is TransactionSearchUiResult.Found -> TransactionDetailsCard(result.transaction) { viewModel.watchTransaction() }
                is TransactionSearchUiResult.NotFound -> {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Transaction Not Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("This transaction is not currently in the mempool.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                is TransactionSearchUiResult.Error -> {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Search Error", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(result.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                null -> {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Search for a Transaction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Enter a 64-character transaction ID to see its details.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionDetailsCard(transaction: TransactionDetails, onWatchClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transaction Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFF9500))
            DetailRow("Fee Rate", "${transaction.feeRate} sat/vB")
            DetailRow("Virtual Size", "${transaction.vsize} vB")
            DetailRow("Fee", String.format("%.8f BTC", transaction.fee))
            DetailRow("Time in Mempool", transaction.timeInMempool)

            if (transaction.confirmations > 0) {
                ConfirmationProgressSection(transaction.confirmations, transaction.blockHeight)
            } else if (transaction.projectedBlockPosition != null) {
                DetailRow("Projected Block", "#${transaction.projectedBlockPosition + 1}")
            } else {
                DetailRow("Projected Block", "Not projected")
            }

            Button(onClick = onWatchClick, modifier = Modifier.fillMaxWidth()) {
                Text(if (transaction.isWatched) "Stop Watching" else "Watch for Confirmation")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConfirmationProgressSection(confirmations: Int, blockHeight: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Confirmations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (confirmations >= 6) Icon(Icons.Default.Check, "Confirmed", tint = Color(0xFF00FF00), modifier = Modifier.size(16.dp))
                Text("$confirmations/6", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = if (confirmations >= 6) Color(0xFF00FF00) else MaterialTheme.colorScheme.onSurface)
            }
        }
        LinearProgressIndicator(
            progress = { (confirmations.coerceAtMost(6) / 6.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (confirmations >= 6) Color(0xFF00FF00) else Color(0xFFFF9500),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        blockHeight?.let { height ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Block Height", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("#$height", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}
