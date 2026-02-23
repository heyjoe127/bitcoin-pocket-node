package com.pocketnode.service

import android.content.Context
import android.util.Log
import com.pocketnode.electrum.AddressIndex
import com.pocketnode.electrum.ElectrumMethods
import com.pocketnode.electrum.ElectrumServer
import com.pocketnode.electrum.SubscriptionManager
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the Electrum server for wallet connectivity (BlueWallet etc.).
 *
 * Uses a pure Kotlin Electrum protocol implementation (com.pocketnode.electrum)
 * backed by the local bitcoind via JSON-RPC. Replaces the previous BWT JNI approach.
 *
 * Tracks xpubs/descriptors/addresses via a bitcoind descriptor wallet and
 * exposes an Electrum RPC interface on localhost:50001.
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
        private var server: ElectrumServer? = null
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
     * Start the Electrum server, connecting to the local bitcoind.
     * Imports descriptors/xpubs into a tracking wallet, then starts
     * the TCP server on localhost:50001.
     */
    fun start(saveState: Boolean = true) {
        // Shut down any existing instance
        server?.stop()
        server = null
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
                val xpubSet = prefs.getStringSet("xpubs", emptySet()) ?: emptySet()
                val addresses = prefs.getStringSet("addresses", emptySet()) ?: emptySet()
                val descriptors = prefs.getStringSet("descriptors", emptySet()) ?: emptySet()

                if (xpubSet.isEmpty() && addresses.isEmpty() && descriptors.isEmpty()) {
                    _state.value = BwtState(status = BwtState.Status.ERROR,
                        error = "No xpubs, descriptors, or addresses configured. Add them in Connect Wallet settings.")
                    return@launch
                }

                val trackedCount = xpubSet.size + descriptors.size + addresses.size
                Log.i(TAG, "Starting Electrum server with ${xpubSet.size} xpubs, ${descriptors.size} descriptors, ${addresses.size} addresses")

                _state.value = BwtState(
                    status = BwtState.Status.SYNCING,
                    trackedAddresses = trackedCount,
                    syncProgress = 0.2f
                )

                // Build components
                val rpc = BitcoinRpcClient(creds.first, creds.second)
                val addressIndex = AddressIndex(rpc)
                val methods = ElectrumMethods(rpc, addressIndex)
                val subscriptions = SubscriptionManager(rpc, addressIndex)

                // Ensure tracking wallet exists
                _state.value = BwtState(
                    status = BwtState.Status.SYNCING,
                    trackedAddresses = trackedCount,
                    syncProgress = 0.4f
                )

                if (!addressIndex.ensureWallet()) {
                    _state.value = BwtState(status = BwtState.Status.ERROR,
                        error = "Failed to create tracking wallet")
                    return@launch
                }

                // Import descriptors and addresses
                _state.value = BwtState(
                    status = BwtState.Status.SYNCING,
                    trackedAddresses = trackedCount,
                    syncProgress = 0.6f
                )

                addressIndex.importDescriptors(
                    descriptors = descriptors.toList(),
                    xpubs = xpubSet.toList()
                )

                if (addresses.isNotEmpty()) {
                    addressIndex.importAddresses(addresses.toList())
                }

                _state.value = BwtState(
                    status = BwtState.Status.SYNCING,
                    trackedAddresses = trackedCount,
                    syncProgress = 0.8f
                )

                // Start the TCP server
                val electrumServer = ElectrumServer(methods, subscriptions, ELECTRUM_HOST, ELECTRUM_PORT)
                electrumServer.start()
                server = electrumServer

                Log.i(TAG, "Electrum server ready on $ELECTRUM_HOST:$ELECTRUM_PORT")
                _isRunning.value = true
                _state.value = BwtState(
                    status = BwtState.Status.RUNNING,
                    trackedAddresses = trackedCount,
                    syncProgress = 1f
                )

            } catch (e: CancellationException) {
                Log.d(TAG, "Electrum server start cancelled")
            } catch (e: Exception) {
                _isRunning.value = false
                Log.e(TAG, "Electrum server failed: ${e.message}", e)
                _state.value = BwtState(status = BwtState.Status.ERROR, error = e.message)
            }
        }
    }

    fun stop(saveState: Boolean = true) {
        if (saveState) {
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("bwt_was_running", false).apply()
        }
        server?.let { s ->
            try {
                s.stop()
                Log.i(TAG, "Electrum server stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Electrum server stop error: ${e.message}")
            }
        }
        server = null
        _isRunning.value = false
        _state.value = BwtState(status = BwtState.Status.STOPPED)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    // -- Saved config helpers (unchanged, used by ConnectWalletScreen) --

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
