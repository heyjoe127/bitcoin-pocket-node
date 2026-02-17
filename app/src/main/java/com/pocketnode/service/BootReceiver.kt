package com.pocketnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts BitcoindService automatically after device reboot,
 * if the user has enabled auto-start in app preferences.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        const val PREF_AUTO_START = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)

        if (!autoStart) {
            Log.d(TAG, "Auto-start disabled, skipping")
            return
        }

        // Only auto-start if the node was previously running (has config)
        val configFile = java.io.File(context.filesDir, "bitcoin/bitcoin.conf")
        if (!configFile.exists()) {
            Log.d(TAG, "No bitcoin.conf found, skipping auto-start")
            return
        }

        Log.i(TAG, "Device rebooted, auto-starting bitcoind")
        val serviceIntent = Intent(context, BitcoindService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
