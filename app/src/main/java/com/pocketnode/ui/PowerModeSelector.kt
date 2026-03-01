package com.pocketnode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Three-segment power mode toggle for the dashboard.
 * [ ⚡ Max ] [ 🔋 Low ] [ 🚶 Away ]
 */
@Composable
fun PowerModeSelector(
    currentMode: PowerModeManager.Mode,
    onModeSelected: (PowerModeManager.Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = PowerModeManager.Mode.values()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        modes.forEach { mode ->
            val isSelected = mode == currentMode
            val bgColor = if (isSelected) {
                when (mode) {
                    PowerModeManager.Mode.MAX -> Color(0xFFFF9800)  // Orange
                    PowerModeManager.Mode.LOW -> Color(0xFF4CAF50)  // Green
                    PowerModeManager.Mode.AWAY -> Color(0xFF607D8B) // Blue-gray
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
}

/**
 * Burst sync status banner for Away mode.
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
