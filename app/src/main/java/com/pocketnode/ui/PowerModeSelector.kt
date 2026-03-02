package com.pocketnode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.power.PowerModeManager

/**
 * Three-segment power mode toggle with info button.
 * [ ⚡ Max ] [ 🔋 Low ] [ 🚶 Away ]  (i)
 */
@Composable
fun PowerModeSelector(
    currentMode: PowerModeManager.Mode,
    onModeSelected: (PowerModeManager.Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = PowerModeManager.Mode.values()
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode toggle (compressed to left)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            modes.forEach { mode ->
                val isSelected = mode == currentMode
                val bgColor = if (isSelected) {
                    when (mode) {
                        PowerModeManager.Mode.MAX -> Color(0xFFFF9800)
                        PowerModeManager.Mode.LOW -> Color(0xFF4CAF50)
                        PowerModeManager.Mode.AWAY -> Color(0xFF607D8B)
                    }
                } else {
                    Color.Transparent
                }
                val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .clickable { onModeSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${mode.emoji} ${mode.label}",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Info button
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "Power mode info",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Info dialog
    if (showInfo) {
        val (title, description) = when (currentMode) {
            PowerModeManager.Mode.MAX -> "⚡ Max Data Mode" to
                "Full power mode for home use.\n\n" +
                "• Continuous sync, 8 peers always connected\n" +
                "• Full mempool relay and oracle updates\n" +
                "• Electrum server and Lightning fully active\n" +
                "• Maximum throughput, fastest block propagation\n\n" +
                "Estimated data: ~500 MB/day\n\n" +
                "Best when: on WiFi and plugged in at home."

            PowerModeManager.Mode.LOW -> "🔋 Low Data Mode" to
                "Balanced mode for daily carry.\n\n" +
                "• Burst sync every 15 minutes\n" +
                "• Connects to 8 peers, syncs to chain tip, then disconnects\n" +
                "• Network radio sleeps between bursts (saves battery)\n" +
                "• All services sync during each burst\n" +
                "• Force-close detection within 15 minutes\n" +
                "• Opening your wallet triggers an immediate sync\n\n" +
                "Estimated data: ~100-200 MB/day\n\n" +
                "Best when: on WiFi or cellular, phone in pocket."

            PowerModeManager.Mode.AWAY -> "🚶 Away Mode" to
                "Minimal mode for conserving battery and data.\n\n" +
                "• Burst sync every 60 minutes\n" +
                "• Connects to 8 peers, syncs briefly, then disconnects\n" +
                "• Network off between bursts (minimal battery drain)\n" +
                "• Lightning safety maintained (watchtower covers gaps)\n" +
                "• Opening your wallet triggers an immediate sync\n\n" +
                "Estimated data: ~25-50 MB/day\n\n" +
                "Best when: out for the day on cellular, conserving battery."
        }

        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(description, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

/**
 * Burst sync status banner for Low Data and Away modes.
 * Shows next sync time or current sync progress.
 */
@Composable
fun BurstSyncBanner(
    burstState: PowerModeManager.BurstState,
    nextBurstMs: Long,
    modifier: Modifier = Modifier
) {
    if (burstState == PowerModeManager.BurstState.IDLE && nextBurstMs == 0L) return

    val text = when (burstState) {
        PowerModeManager.BurstState.SYNCING -> "⏳ Burst sync in progress..."
        PowerModeManager.BurstState.WAITING -> {
            val remaining = nextBurstMs - System.currentTimeMillis()
            if (remaining > 0) {
                val minutes = (remaining / 60_000).toInt()
                "💤 Network paused — next sync in ${minutes}min"
            } else {
                "💤 Network paused — syncing soon..."
            }
        }
        PowerModeManager.BurstState.IDLE -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF607D8B))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}
