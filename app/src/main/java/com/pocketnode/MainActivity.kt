package com.pocketnode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pocketnode.service.BitcoindService
import com.pocketnode.ui.PocketNodeApp

/**
 * Main entry point for Bitcoin Pocket Node.
 *
 * This activity hosts the Compose UI and handles notification permissions
 * required for the foreground service on Android 13+.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result — service works either way, just no visible notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ requires runtime permission for notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Defend against Seedvault/backup restoring stale SharedPreferences.
        // noBackupFilesDir is NEVER backed up. If our sentinel is missing but
        // prefs exist, a backup restore happened — wipe the prefs.
        val sentinel = java.io.File(noBackupFilesDir, ".setup_complete")
        if (!sentinel.exists()) {
            val prefsToCheck = listOf("pocketnode_sftp", "pocketnode_prefs", "node_setup", "sync_settings", "ssh_prefs")
            for (name in prefsToCheck) {
                val p = getSharedPreferences(name, MODE_PRIVATE)
                if (p.all.isNotEmpty()) {
                    android.util.Log.i("MainActivity", "Clearing stale prefs: $name (backup restore detected)")
                    p.edit().clear().apply()
                }
            }
        }

        // Auto-start node if it was running before app was killed
        val prefs = getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("node_was_running", false) && !BitcoindService.isRunningFlow.value) {
            val intent = Intent(this, BitcoindService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
            PocketNodeApp(
                networkMonitor = BitcoindService.activeNetworkMonitor,
                syncController = BitcoindService.activeSyncController
            )
        }
    }
}
