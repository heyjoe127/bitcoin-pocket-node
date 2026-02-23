package com.pocketnode.service

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Session
import com.pocketnode.ssh.SshUtils

/**
 * Manages watchtower configuration: detect on home node via SSH,
 * store URI, provide status for dashboard.
 *
 * We never modify the remote node's LND config. The user enables
 * watchtower via their node's UI (Umbrel Advanced Settings, etc).
 * We detect if it's active, read the .onion URI, and store it locally.
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
     * Auto-detect watchtower during SSH connection.
     * Called after chainstate/filter copy completes.
     * Does NOT modify the remote node. Only reads status.
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

        // Step 2: Check if watchtower is active
        if (!SshUtils.isWatchtowerActive(session, sshPassword, lndInfo)) {
            Log.i(TAG, "Watchtower not active on remote node")
            return SetupResult.NOT_ENABLED
        }

        // Step 3: Get watchtower URI
        val uri = SshUtils.getWatchtowerUri(session, sshPassword, lndInfo)
        if (uri == null) {
            Log.w(TAG, "Watchtower active but could not read URI")
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
     * Remove watchtower configuration (local only, does not touch remote node).
     */
    fun remove() {
        prefs.edit()
            .remove(KEY_TOWER_URI)
            .remove(KEY_NODE_OS)
            .putBoolean(KEY_ENABLED, false)
            .apply()
        Log.i(TAG, "Watchtower configuration removed")
    }

    fun isConfigured(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false) &&
                prefs.getString(KEY_TOWER_URI, null) != null
    }

    fun getTowerUri(): String? {
        return if (isConfigured()) prefs.getString(KEY_TOWER_URI, null) else null
    }

    fun getNodeOs(): String? {
        return prefs.getString(KEY_NODE_OS, null)
    }

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
        NOT_ENABLED,
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
