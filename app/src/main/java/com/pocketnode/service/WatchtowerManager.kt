package com.pocketnode.service

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Session
import com.pocketnode.ssh.SshUtils

/**
 * Manages watchtower configuration: auto-detect during SSH setup,
 * store URI, provide status for dashboard.
 *
 * Watchtower is automatically configured when:
 * 1. User connects to home node via SSH (chainstate/filter copy)
 * 2. LND is detected on the home node
 * 3. Watchtower server is enabled (or already was)
 * 4. .onion URI is read and stored locally
 *
 * The phone's Zeus/LND connects to the watchtower via embedded Tor.
 */
class WatchtowerManager(private val context: Context) {

    companion object {
        private const val TAG = "WatchtowerManager"
        private const val PREFS = "watchtower_prefs"
        private const val KEY_TOWER_URI = "tower_uri"
        private const val KEY_NODE_OS = "tower_node_os"
        private const val KEY_ENABLED = "tower_enabled"
        private const val KEY_SETUP_TIME = "tower_setup_time"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Auto-setup watchtower during SSH connection.
     * Called after chainstate/filter copy completes.
     * Returns a result describing what happened.
     */
    fun autoSetup(session: Session, sshPassword: String): SetupResult {
        Log.i(TAG, "Starting watchtower auto-detection...")

        // Step 1: Detect LND
        val lndInfo = SshUtils.detectLnd(session, sshPassword)
        if (lndInfo == null) {
            Log.i(TAG, "No LND detected on remote node")
            return SetupResult.NO_LND
        }
        Log.i(TAG, "LND detected: ${lndInfo.nodeOs} (docker=${lndInfo.isDocker})")

        // Step 2: Enable watchtower server if not already
        val configChanged = SshUtils.enableWatchtower(session, sshPassword, lndInfo)
        if (configChanged) {
            Log.i(TAG, "Watchtower config added to lnd.conf, restarting LND...")
            val restarted = SshUtils.restartLnd(session, sshPassword, lndInfo)
            if (!restarted) {
                Log.w(TAG, "LND restart failed after config change")
                return SetupResult.RESTART_FAILED
            }
            Log.i(TAG, "LND restarted successfully")
        } else {
            Log.i(TAG, "Watchtower already enabled in config")
        }

        // Step 3: Get watchtower URI
        val uri = SshUtils.getWatchtowerUri(session, sshPassword, lndInfo)
        if (uri == null) {
            Log.w(TAG, "Could not read watchtower URI")
            return SetupResult.NO_URI
        }
        Log.i(TAG, "Watchtower URI obtained: ${uri.take(20)}...")

        // Step 4: Store locally
        prefs.edit()
            .putString(KEY_TOWER_URI, uri)
            .putString(KEY_NODE_OS, lndInfo.nodeOs)
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_SETUP_TIME, System.currentTimeMillis())
            .apply()

        Log.i(TAG, "Watchtower configured successfully (${lndInfo.nodeOs})")
        return SetupResult.SUCCESS
    }

    /**
     * Manual setup trigger (same as auto, but called from UI).
     */
    fun manualSetup(host: String, port: Int, user: String, password: String): SetupResult {
        return try {
            val session = SshUtils.connectSsh(host, port, user, password)
            try {
                autoSetup(session, password)
            } finally {
                session.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual watchtower setup failed", e)
            SetupResult.CONNECTION_FAILED
        }
    }

    /**
     * Remove watchtower configuration.
     */
    fun remove() {
        prefs.edit()
            .remove(KEY_TOWER_URI)
            .remove(KEY_NODE_OS)
            .putBoolean(KEY_ENABLED, false)
            .apply()
        Log.i(TAG, "Watchtower configuration removed")
    }

    /**
     * Check if watchtower is configured and enabled.
     */
    fun isConfigured(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false) &&
                prefs.getString(KEY_TOWER_URI, null) != null
    }

    /**
     * Get the stored watchtower URI.
     */
    fun getTowerUri(): String? {
        return if (isConfigured()) prefs.getString(KEY_TOWER_URI, null) else null
    }

    /**
     * Get the node OS type of the watchtower.
     */
    fun getNodeOs(): String? {
        return prefs.getString(KEY_NODE_OS, null)
    }

    /**
     * Get status info for dashboard display.
     */
    fun getStatus(): WatchtowerStatus {
        if (!isConfigured()) return WatchtowerStatus.NOT_CONFIGURED
        val uri = prefs.getString(KEY_TOWER_URI, null) ?: return WatchtowerStatus.NOT_CONFIGURED
        val nodeOs = prefs.getString(KEY_NODE_OS, "unknown") ?: "unknown"
        val setupTime = prefs.getLong(KEY_SETUP_TIME, 0)
        return WatchtowerStatus.Configured(uri, nodeOs, setupTime)
    }

    enum class SetupResult {
        SUCCESS,
        NO_LND,
        RESTART_FAILED,
        NO_URI,
        CONNECTION_FAILED
    }

    sealed class WatchtowerStatus {
        object NOT_CONFIGURED : WatchtowerStatus()
        data class Configured(
            val uri: String,
            val nodeOs: String,
            val setupTimeMs: Long
        ) : WatchtowerStatus()
    }
}
