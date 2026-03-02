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

        // Defend against Seedvault restoring stale data after reinstall.
        // Compare APK first-install time against a saved timestamp.
        // If the install time is newer, this is a reinstall — wipe everything.
        val installTimePrefs = getSharedPreferences("install_guard", MODE_PRIVATE)
        val savedInstallTime = installTimePrefs.getLong("first_install_time", 0)
        val actualInstallTime = try {
            packageManager.getPackageInfo(packageName, 0).firstInstallTime
        } catch (_: Exception) { 0L }
        if (savedInstallTime == 0L && actualInstallTime > 0) {
            // First launch ever (or after reinstall) — save install time and wipe stale data
            installTimePrefs.edit().putLong("first_install_time", actualInstallTime).apply()
            val prefsToCheck = listOf("pocketnode_sftp", "pocketnode_prefs", "node_setup", "sync_settings", "ssh_prefs")
            for (name in prefsToCheck) {
                val p = getSharedPreferences(name, MODE_PRIVATE)
                if (p.all.isNotEmpty()) {
                    android.util.Log.i("MainActivity", "Clearing stale prefs: $name (reinstall detected)")
                    p.edit().clear().apply()
                }
            }
            // Clear sentinel too
            java.io.File(noBackupFilesDir, ".setup_complete").delete()
            // Clear any restored bitcoin data
            val bitcoinDir = java.io.File(filesDir, "bitcoin")
            if (bitcoinDir.exists()) bitcoinDir.deleteRecursively()
        } else if (savedInstallTime != actualInstallTime && actualInstallTime > 0) {
            // Install time changed — reinstall happened
            installTimePrefs.edit().putLong("first_install_time", actualInstallTime).apply()
            val prefsToCheck = listOf("pocketnode_sftp", "pocketnode_prefs", "node_setup", "sync_settings", "ssh_prefs")
            for (name in prefsToCheck) {
                getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
            }
            java.io.File(noBackupFilesDir, ".setup_complete").delete()
            java.io.File(filesDir, "bitcoin").let { if (it.exists()) it.deleteRecursively() }
            android.util.Log.i("MainActivity", "Reinstall detected — cleared all stale data")
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
