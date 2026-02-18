package com.pocketnode.ui.mempool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FeeEstimatePanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Recommended Fees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FeeEstimateItem("Next Block", "45 sat/vB", FeePriority.High, Modifier.weight(1f))
                FeeEstimateItem("30 minutes", "25 sat/vB", FeePriority.Medium, Modifier.weight(1f))
                FeeEstimateItem("1 hour", "15 sat/vB", FeePriority.Low, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeeEstimateItem(label: String, feeRate: String, priority: FeePriority, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(feeRate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = priority.color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class FeePriority(val color: Color) {
    High(Color(0xFFFF0000)),
    Medium(Color(0xFFFF8000)),
    Low(Color(0xFF00FF00))
}
