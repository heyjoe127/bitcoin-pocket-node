package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus

/**
 * Shows Lightning payment history from ldk-node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentHistoryScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lightning = remember { LightningService.getInstance(context) }
    val payments = remember { lightning.listPayments().sortedByDescending { it.latestUpdateTimestamp } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (payments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No payments yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(payments) { payment ->
                    PaymentCard(payment)
                }
            }
        }
    }
}

@Composable
private fun PaymentCard(payment: PaymentDetails) {
    val isInbound = payment.direction == PaymentDirection.INBOUND
    val amountMsat = payment.amountMsat?.toLong() ?: 0L
    val amountSats = amountMsat / 1000

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isInbound) "⬇️" else "⬆️",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isInbound) "Received" else "Sent",
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    payment.id.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (isInbound) "+" else "-"}${"%,d".format(amountSats)} sats",
                    fontWeight = FontWeight.Bold,
                    color = if (isInbound) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                val (statusText, statusColor) = when (payment.status) {
                    PaymentStatus.PENDING -> "Pending" to Color(0xFFFF9800)
                    PaymentStatus.SUCCEEDED -> "Completed" to Color(0xFF4CAF50)
                    PaymentStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}
