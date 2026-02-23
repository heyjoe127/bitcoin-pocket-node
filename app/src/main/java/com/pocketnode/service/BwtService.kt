package com.pocketnode.service

import android.content.Context
import android.util.Log
import com.pocketnode.util.ConfigGenerator
import dev.bwt.libbwt.daemon.BwtConfig
import dev.bwt.libbwt.daemon.BwtDaemon
import dev.bwt.libbwt.daemon.ProgressNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

/**
 * Manages the BWT (Bitcoin Wallet Tracker) Electrum server.
 * Uses libbwt-jni to run BWT in-process via JNI, avoiding
 * Android PhantomProcess restrictions on child processes.
 *
 * BWT tracks xpubs/descriptors against the local bitcoind and
 * exposes an Electrum RPC interface for wallets like BlueWallet.
 */
class BwtService(private val context: Context) {

    companion object {
        private const val TAG = "BwtService"
        const val ELECTRUM_HOST = "127.0.0.1"
        const val ELECTRUM_PORT = 50001

        private val _state = MutableStateFlow(BwtState())
        val stateFlow: StateFlow<BwtState> = _state

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning

        // Shared across all instances so stop/start from different screens works
        private var daemon: BwtDaemon? = null
        private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    data class BwtState(
        val status: Status = Status.STOPPED,
        val electrumAddress: String = "$ELECTRUM_HOST:$ELECTRUM_PORT",
        val error: String? = null,
        val trackedAddresses: Int = 0,
        val syncProgress: Float = 0f
    ) {
        enum class Status { STOPPED, STARTING, SYNCING, RUNNING, ERROR }
    }

    /**
     * Start BWT in-process via JNI, connecting to the local bitcoind.
     * Descriptors and xpubs are tracked directly by BWT without
     * needing a bitcoind wallet (no importmulti or createwallet).
     */
    fun start(saveState: Boolean = true) {
        // Shut down any existing instance
        daemon?.let { d ->
            try { d.shutdown() } catch (_: Exception) {}
        }
        daemon = null
        _isRunning.value = false

        if (saveState) {
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("bwt_was_running", true).apply()
        }
        _state.value = BwtState(status = BwtState.Status.STARTING)

        scope.launch {
            try {
                // Get RPC credentials
                val creds = ConfigGenerator.readCredentials(context)
                if (creds == null) {
                    _state.value = BwtState(status = BwtState.Status.ERROR, error = "No RPC credentials")
                    return@launch
                }

                // Get saved xpubs/descriptors/addresses from preferences
                val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                val xpubs = prefs.getStringSet("xpubs", emptySet()) ?: emptySet()
                val addresses = prefs.getStringSet("addresses", emptySet()) ?: emptySet()
                val descriptors = prefs.getStringSet("descriptors", emptySet()) ?: emptySet()

                if (xpubs.isEmpty() && addresses.isEmpty() && descriptors.isEmpty()) {
                    _state.value = BwtState(status = BwtState.Status.ERROR,
                        error = "No xpubs, descriptors, or addresses configured. Add them in Connect Wallet settings.")
                    return@launch
                }

                // Build descriptor list: explicit descriptors + addresses wrapped as addr() descriptors
                val allDescriptors = mutableListOf<String>()
                allDescriptors.addAll(descriptors)
                for (addr in addresses) {
                    allDescriptors.add("addr($addr)")
                }

                Log.i(TAG, "Starting BWT JNI with ${xpubs.size} xpubs, ${descriptors.size} descriptors, ${addresses.size} addresses")

                val config = BwtConfig().apply {
                    bitcoindUrl = "http://127.0.0.1:8332"
                    bitcoindAuth = "${creds.first}:${creds.second}"
                    electrumAddr = "$ELECTRUM_HOST:$ELECTRUM_PORT"
                    rescanSince = 0  // "now" equivalent: don't rescan history
                    verbose = 1
                    setupLogger = false  // we handle logging ourselves
                    requireAddresses = false
                    electrumSkipMerkle = true  // faster, BlueWallet doesn't verify merkle proofs
                }

                if (xpubs.isNotEmpty()) {
                    config.xpubs = xpubs.toTypedArray()
                }
                if (allDescriptors.isNotEmpty()) {
                    config.descriptors = allDescriptors.toTypedArray()
                }

                val bwt = BwtDaemon(config)
                daemon = bwt

                val trackedCount = xpubs.size + descriptors.size + addresses.size

                // start() blocks until shutdown, so it runs on this IO coroutine
                bwt.start(object : ProgressNotifier {
                    override fun onBooting() {
                        Log.i(TAG, "BWT booting...")
                        _state.value = BwtState(
                            status = BwtState.Status.STARTING,
                            trackedAddresses = trackedCount
                        )
                    }

                    override fun onSyncProgress(progress: Float, tip: Date) {
                        Log.d(TAG, "BWT sync progress: ${(progress * 100).toInt()}%")
                        _state.value = BwtState(
                            status = BwtState.Status.SYNCING,
                            trackedAddresses = trackedCount,
                            syncProgress = progress
                        )
                    }

                    override fun onScanProgress(progress: Float, eta: Int) {
                        Log.d(TAG, "BWT scan progress: ${(progress * 100).toInt()}%, ETA: ${eta}s")
                        _state.value = BwtState(
                            status = BwtState.Status.SYNCING,
                            trackedAddresses = trackedCount,
                            syncProgress = progress
                        )
                    }

                    override fun onReady() {
                        Log.i(TAG, "BWT ready, Electrum on $ELECTRUM_HOST:$ELECTRUM_PORT")
                        _isRunning.value = true
                        _state.value = BwtState(
                            status = BwtState.Status.RUNNING,
                            trackedAddresses = trackedCount,
                            syncProgress = 1f
                        )
                    }
                })

                // start() returned, meaning BWT shut down
                _isRunning.value = false
                Log.i(TAG, "BWT daemon stopped")
                _state.value = BwtState(status = BwtState.Status.STOPPED)

            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "BWT coroutine cancelled (stop)")
            } catch (e: Exception) {
                _isRunning.value = false
                Log.e(TAG, "BWT failed: ${e.message}", e)
                _state.value = BwtState(status = BwtState.Status.ERROR, error = e.message)
            }
        }
    }

    fun stop(saveState: Boolean = true) {
        if (saveState) {
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("bwt_was_running", false).apply()
        }
        daemon?.let { d ->
            try {
                d.shutdown()
                Log.i(TAG, "BWT shutdown requested")
            } catch (e: Exception) {
                Log.w(TAG, "BWT shutdown error: ${e.message}")
            }
        }
        daemon = null
        _isRunning.value = false
        _state.value = BwtState(status = BwtState.Status.STOPPED)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    // -- Saved config helpers --

    object SavedConfig {
        fun getXpubs(context: Context): Set<String> {
            return context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                .getStringSet("xpubs", emptySet()) ?: emptySet()
        }

        fun getAddresses(context: Context): Set<String> {
            return context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                .getStringSet("addresses", emptySet()) ?: emptySet()
        }

        fun getDescriptors(context: Context): Set<String> {
            return context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                .getStringSet("descriptors", emptySet()) ?: emptySet()
        }

        fun saveXpub(context: Context, xpub: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("xpubs", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(xpub)
            prefs.edit().putStringSet("xpubs", existing).apply()
        }

        fun saveAddress(context: Context, address: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(address)
            prefs.edit().putStringSet("addresses", existing).apply()
        }

        fun saveDescriptor(context: Context, descriptor: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("descriptors", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(descriptor)
            prefs.edit().putStringSet("descriptors", existing).apply()
        }

        fun removeXpub(context: Context, xpub: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("xpubs", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.remove(xpub)
            prefs.edit().putStringSet("xpubs", existing).apply()
        }

        fun removeAddress(context: Context, address: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.remove(address)
            prefs.edit().putStringSet("addresses", existing).apply()
        }

        fun removeDescriptor(context: Context, descriptor: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("descriptors", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.remove(descriptor)
            prefs.edit().putStringSet("descriptors", existing).apply()
        }
    }
}
