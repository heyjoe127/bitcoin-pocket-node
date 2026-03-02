package com.pocketnode.power

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketnode.network.NetworkState
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.service.BatteryMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray

/**
 * Manages node power modes: Max, Low, Away.
 *
 * Max: full peers, full dbcache, continuous sync
 * Low: reduced peers, normal sync, default for daily carry
 * Away: periodic burst sync with long idle periods, minimal resource use
 */
class PowerModeManager(private val context: Context) {

    companion object {
        private const val TAG = "PowerModeManager"
        private const val PREFS_NAME = "pocketnode_prefs"
        private const val PREF_KEY_POWER_MODE = "power_mode"

        // Burst sync intervals
        private const val LOW_BURST_INTERVAL_MS = 15 * 60 * 1000L   // 15 min
        private const val AWAY_BURST_INTERVAL_MS = 60 * 60 * 1000L  // 60 min
        private const val BURST_SYNC_TIMEOUT_MS = 120 * 1000L       // 2 min max per burst

        // Peer counts during burst
        private const val MAX_PEERS = 8
        private const val LOW_PEERS = 4
        private const val AWAY_PEERS = 2

        private const val PREF_KEY_AUTO_POWER = "power_mode_auto"
        private const val PREF_KEY_MANUAL_MODE = "power_mode_manual"

        private val _modeFlow = MutableStateFlow(Mode.LOW)
        val modeFlow: StateFlow<Mode> = _modeFlow

        /** True when auto-detection is active (user toggle) */
        private val _autoEnabled = MutableStateFlow(false)
        val autoEnabledFlow: StateFlow<Boolean> = _autoEnabled

        private val _burstStateFlow = MutableStateFlow(BurstState.IDLE)
        val burstStateFlow: StateFlow<BurstState> = _burstStateFlow

        private val _nextBurstFlow = MutableStateFlow(0L)
        /** Epoch millis of next scheduled burst (0 = not scheduled) */
        val nextBurstFlow: StateFlow<Long> = _nextBurstFlow

        /** Callbacks for wallet session tracking (Electrum client connect/disconnect) */
        var onWalletSessionStart: (() -> Unit)? = null
        var onWalletSessionEnd: (() -> Unit)? = null

        /** True while a wallet is connected (persists 10s after disconnect) */
        private val _walletConnectedFlow = MutableStateFlow(false)
        val walletConnectedFlow: StateFlow<Boolean> = _walletConnectedFlow
    }

    enum class Mode(val label: String, val emoji: String, val notificationLabel: String) {
        MAX("Max", "⚡", "Max Data Mode"),
        LOW("Low", "🔋", "Low Data Mode"),
        AWAY("Away", "🚶", "Away Mode");

        companion object {
            fun fromString(s: String): Mode = try { valueOf(s) } catch (_: Exception) { LOW }
        }
    }

    enum class BurstState {
        IDLE,       // Between bursts (network may be off)
        SYNCING,    // Burst active, syncing blocks
        WAITING     // Waiting for next burst
    }

    private var burstJob: Job? = null
    private var autoDetectJob: Job? = null
    private var rpc: BitcoinRpcClient? = null
    private var activeScope: CoroutineScope? = null
    private var walletHoldingNetwork = false
    private var walletIndicatorJob: Job? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        _modeFlow.value = Mode.fromString(prefs.getString(PREF_KEY_POWER_MODE, "LOW") ?: "LOW")
        _autoEnabled.value = prefs.getBoolean(PREF_KEY_AUTO_POWER, false)
    }

    /** Set the RPC client once bitcoind is running */
    fun setRpc(client: BitcoinRpcClient) {
        rpc = client
    }

    /** Switch power mode. Applies immediately. */
    fun setMode(mode: Mode, scope: CoroutineScope, isAuto: Boolean = false) {
        val previous = _modeFlow.value
        _modeFlow.value = mode
        prefs.edit().putString(PREF_KEY_POWER_MODE, mode.name).apply()
        // Save as last manual choice when user picks it directly
        if (!isAuto) {
            prefs.edit().putString(PREF_KEY_MANUAL_MODE, mode.name).apply()
        }
        Log.i(TAG, "Power mode: $previous -> $mode${if (isAuto) " (auto)" else ""}")

        // Cancel existing burst cycle and wallet hold
        burstJob?.cancel()
        burstJob = null
        walletHoldingNetwork = false
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L

        scope.launch(Dispatchers.IO) {
            applyMode(mode)
        }
    }

    /** Enable or disable auto power mode detection */
    fun setAutoEnabled(enabled: Boolean, networkStateFlow: StateFlow<NetworkState>,
                       batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                       scope: CoroutineScope) {
        _autoEnabled.value = enabled
        prefs.edit().putBoolean(PREF_KEY_AUTO_POWER, enabled).apply()
        Log.i(TAG, "Auto power mode: $enabled")

        autoDetectJob?.cancel()
        if (enabled) {
            startAutoDetection(networkStateFlow, batteryStateFlow, scope)
        } else {
            // Revert to last manually-set mode
            val lastManual = Mode.fromString(prefs.getString(PREF_KEY_MANUAL_MODE, "LOW") ?: "LOW")
            Log.i(TAG, "Auto disabled, reverting to manual: $lastManual")
            setMode(lastManual, scope)
        }
    }

    /**
     * Monitors network and battery state, automatically switching power mode:
     * - WiFi + Charging -> Max
     * - WiFi + Battery -> Low
     * - Cellular + Charging -> Low
     * - Cellular + Battery -> Away
     * - Battery below 20% -> Away
     */
    private fun startAutoDetection(networkStateFlow: StateFlow<NetworkState>,
                                   batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                                   scope: CoroutineScope) {
        autoDetectJob = scope.launch {
            combine(networkStateFlow, batteryStateFlow) { network, battery ->
                suggestMode(network, battery)
            }.collect { suggested ->
                val current = _modeFlow.value
                if (suggested != current) {
                    Log.i(TAG, "Auto-switching: $current -> $suggested")
                    setMode(suggested, scope, isAuto = true)
                }
            }
        }
    }

    private fun suggestMode(network: NetworkState, battery: BatteryMonitor.BatteryState): Mode {
        // Low battery always goes to Away
        if (battery.level < 20 && !battery.isCharging) return Mode.AWAY

        return when (network) {
            NetworkState.WIFI -> if (battery.isCharging) Mode.MAX else Mode.LOW
            NetworkState.CELLULAR -> if (battery.isCharging) Mode.LOW else Mode.AWAY
            NetworkState.OFFLINE -> Mode.AWAY
        }
    }

    /** Start auto-detection if enabled (call after setRpc with available flows) */
    fun startAutoIfEnabled(networkStateFlow: StateFlow<NetworkState>,
                           batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                           scope: CoroutineScope) {
        activeScope = scope
        // Wire up wallet session callbacks
        onWalletSessionStart = { onWalletSessionStarted() }
        onWalletSessionEnd = { onWalletSessionEnded() }
        if (_autoEnabled.value) {
            startAutoDetection(networkStateFlow, batteryStateFlow, scope)
        }
    }

    /** Apply mode settings to bitcoind via RPC */
    private suspend fun applyMode(mode: Mode) {
        val client = rpc ?: return

        when (mode) {
            Mode.MAX -> {
                // Full power: network on, no burst cycling
                setNetworkActive(client, true)
                _burstStateFlow.value = BurstState.IDLE
                _nextBurstFlow.value = 0L
            }
            Mode.LOW -> {
                // Burst sync every 15 minutes: sync to tip, then sleep
                startBurstCycle(LOW_BURST_INTERVAL_MS)
            }
            Mode.AWAY -> {
                // Start burst sync cycle: sync now, then sleep
                startBurstCycle(AWAY_BURST_INTERVAL_MS)
            }
        }
    }

    /** Start the burst sync cycle for Away mode */
    private fun startBurstCycle(intervalMs: Long) {
        burstJob?.cancel()
        burstJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Burst: turn network on, sync, turn off
                doBurst()

                // Check if cancelled during burst (mode changed)
                if (!isActive) break

                // Schedule next burst
                val nextBurst = System.currentTimeMillis() + intervalMs
                _nextBurstFlow.value = nextBurst
                _burstStateFlow.value = BurstState.WAITING

                delay(intervalMs)
            }
        }
    }

    /** Execute one sync burst: enable network, wait for sync, disable network */
    private suspend fun doBurst() {
        val client = rpc ?: return
        _burstStateFlow.value = BurstState.SYNCING
        Log.i(TAG, "Burst sync starting")

        try {
            // Enable network
            setNetworkActive(client, true)

            // Wait for sync or timeout
            val startTime = System.currentTimeMillis()
            var synced = false
            while (System.currentTimeMillis() - startTime < BURST_SYNC_TIMEOUT_MS) {
                try {
                    val info = client.getBlockchainInfo()
                    val progress = info?.optDouble("verificationprogress", 0.0) ?: 0.0
                    val headers = info?.optInt("headers", 0) ?: 0
                    val blocks = info?.optInt("blocks", 0) ?: 0
                    if (progress > 0.9999 && headers > 0 && blocks >= headers) {
                        synced = true
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                delay(5_000)
            }

            Log.i(TAG, "Burst sync ${if (synced) "complete" else "timed out"}")

            // Disable network until next burst
            setNetworkActive(client, false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Mode changed — don't touch network state, let the new mode handle it
            Log.d(TAG, "Burst sync cancelled (mode change)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Burst sync error: ${e.message}")
            // Leave network off on error, next burst will retry
            try { setNetworkActive(client, false) } catch (_: Exception) {}
        }

        _burstStateFlow.value = BurstState.IDLE
    }

    /** Trigger an immediate burst sync (e.g. when user opens the app or wallet connects) */
    fun triggerBurst(scope: CoroutineScope) {
        val mode = _modeFlow.value
        if (mode == Mode.MAX) return
        if (_burstStateFlow.value == BurstState.SYNCING) return

        val intervalMs = if (mode == Mode.LOW) LOW_BURST_INTERVAL_MS else AWAY_BURST_INTERVAL_MS

        burstJob?.cancel()
        burstJob = null

        scope.launch(Dispatchers.IO) {
            doBurst()
            // Resume normal cycle after triggered burst
            startBurstCycle(intervalMs)
        }
    }

    /**
     * Called when an external wallet (BlueWallet etc.) connects to the Electrum server.
     * Holds the network active so the wallet has peers for fresh data and tx broadcast.
     * Burst cycling is paused until the last wallet disconnects.
     */
    private fun onWalletSessionStarted() {
        val mode = _modeFlow.value
        if (mode == Mode.MAX) return  // Already fully connected
        if (walletHoldingNetwork) return  // Already holding

        walletHoldingNetwork = true
        walletIndicatorJob?.cancel()
        _walletConnectedFlow.value = true
        Log.i(TAG, "Wallet connected — holding network active")

        // Cancel burst cycling, just keep network on
        burstJob?.cancel()
        burstJob = null
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L

        val scope = activeScope ?: return
        scope.launch(Dispatchers.IO) {
            setNetworkActive(rpc ?: return@launch, true)
        }
    }

    /**
     * Called when the last wallet disconnects from the Electrum server.
     * Resumes normal burst cycling for the current power mode.
     */
    private fun onWalletSessionEnded() {
        if (!walletHoldingNetwork) return
        walletHoldingNetwork = false

        val mode = _modeFlow.value
        if (mode == Mode.MAX) return  // Max stays connected anyway

        Log.i(TAG, "All wallets disconnected — resuming burst cycle")

        // Keep indicator visible for 10s after disconnect
        val scope = activeScope ?: return
        walletIndicatorJob?.cancel()
        walletIndicatorJob = scope.launch {
            delay(10_000)
            _walletConnectedFlow.value = false
        }

        scope.launch(Dispatchers.IO) {
            applyMode(mode)
        }
    }

    /** Clean up when service stops */
    fun stop() {
        burstJob?.cancel()
        burstJob = null
        autoDetectJob?.cancel()
        autoDetectJob = null
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L
    }

    private suspend fun setNetworkActive(client: BitcoinRpcClient, active: Boolean) {
        try {
            val params = JSONArray().apply { put(active) }
            client.call("setnetworkactive", params)
            Log.d(TAG, "setnetworkactive($active)")
        } catch (e: Exception) {
            Log.e(TAG, "setnetworkactive failed: ${e.message}")
        }
    }
}
