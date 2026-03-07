package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.lightningdevkit.ldknode.*
import java.io.File

/**
 * Manages the ldk-node Lightning node lifecycle.
 * Connects to local bitcoind via RPC for chain data.
 * Powered by LDK (Lightning Dev Kit) from Spiral.
 */
class LightningService(private val context: Context) {

    companion object {
        private const val TAG = "LightningService"
        private const val STORAGE_DIR = "lightning"
        private const val RGS_URL = "https://rapidsync.lightningdevkit.org/snapshot"

        // Singleton state for UI observation
        private val _state = MutableStateFlow(LightningState())
        val stateFlow: StateFlow<LightningState> = _state.asStateFlow()

        private var instance: LightningService? = null

        fun getInstance(context: Context): LightningService {
            return instance ?: LightningService(context.applicationContext).also { instance = it }
        }
    }

    data class LightningState(
        val status: Status = Status.STOPPED,
        val nodeId: String? = null,
        val onchainBalanceSats: Long = 0,
        val lightningBalanceSats: Long = 0,
        val channelCount: Int = 0,
        val totalCapacitySats: Long = 0,
        val totalInboundSats: Long = 0,
        val error: String? = null,
        // Prune recovery progress
        val recoveryBlocksNeeded: Int = 0,
        val recoveryBlocksDone: Int = 0,
        val recoveryWaitingForWifi: Boolean = false,
        // Background UTXO scan
        val scanningForFunds: Boolean = false,
        val scanProgress: Int = 0,  // 0-100%
        // Channel error (set when a pending channel is rejected by peer)
        val lastChannelError: String? = null,
        // Pending channel confirmation tracking
        val pendingChannels: List<PendingChannel> = emptyList()
    ) {
        data class PendingChannel(
            val channelId: String,
            val peerAlias: String,
            val confirmations: Int,
            val confirmationsRequired: Int,
            val capacitySats: Long
        )

        enum class Status { STOPPED, STARTING, RUNNING, ERROR, RECOVERING }
    }

    private var node: Node? = null
    private var watchtowerBridge: WatchtowerBridge? = null
    private var lndHubServer: LndHubServer? = null
    private var stateRefreshJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Signaled by handleEvents() when a channel event (Pending/Ready/Closed) occurs
    @Volatile private var channelEventLatch: java.util.concurrent.CountDownLatch? = null

    @Volatile private var starting = false

    /**
     * Start the Lightning node on a bare Java Thread.
     *
     * CRITICAL: Must NOT use Dispatchers.IO, runBlocking, or any coroutine
     * dispatcher before node.start() is called.
     *
     * Root cause: UniFFI's JNI bridge attaches a tokio runtime to every
     * Dispatchers.IO thread. runBlocking() on a plain Thread installs a
     * Kotlin BlockingEventLoop, and any withContext(Dispatchers.IO) call
     * inside it briefly runs on a tokio-bearing thread. This causes
     * tokio::runtime::Handle::try_current() to succeed in LDK's Runtime::new(),
     * so LDK borrows UniFFI's runtime handle instead of creating its own.
     * The background sync task then runs on a JNI thread with no active
     * reactor, and header_cache.lock().await hangs forever.
     *
     * Fix: startInternal is a plain fun with zero coroutine machinery.
     * All RPC calls before node.start() use callSync() / getBlockchainInfoSync()
     * which are plain blocking HttpURLConnection — no coroutine context attached.
     */
    fun start(rpcUser: String, rpcPassword: String, rpcPort: Int = 8332) {
        // If a pending seed restore exists, stop the running node first so we restart fresh
        val pendingFile = File(context.filesDir, "pending_seed_restore")
        if (pendingFile.exists() && node != null) {
            Log.i(TAG, "Pending seed restore found while node running. Stopping for restart.")
            try { stop() } catch (_: Exception) {}
            Thread.sleep(500)
        }

        synchronized(this) {
            if (node != null || starting) {
                Log.w(TAG, "Lightning node already running or starting")
                return
            }
            starting = true
        }

        scope.launch {
            _state.value = _state.value.copy(status = LightningState.Status.STARTING, error = null)
            delay(100)
        }

        Thread({
            startInternal(rpcUser, rpcPassword, rpcPort)
        }, "ldk-start").start()
    }

    // Plain fun — no suspend, no coroutine context, no runBlocking.
    // Every call in this function that happens before node.start() must be
    // coroutine-free. After node.start() returns, tokio's runtime is fully
    // initialised and it's safe to touch coroutine machinery again.
    private fun startInternal(rpcUser: String, rpcPassword: String, rpcPort: Int) {
        try {
            // Apply pending seed restore before LDK touches any files
            applyPendingSeedRestore()

            val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)

            // --- Prune check (sync, no coroutines) ---
            val lastLdkHeight = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .getLong("last_ldk_sync_height", 0)
            if (lastLdkHeight > 0) {
                val chainInfo = rpc.getBlockchainInfoSync()
                if (chainInfo != null && !chainInfo.has("_rpc_error")) {
                    val pruneHeight = chainInfo.optLong("pruneheight", 0)
                    if (pruneHeight > lastLdkHeight) {
                        Log.w(TAG, "Pruned blocks detected: LDK last synced at $lastLdkHeight but prune height is $pruneHeight")
                        starting = false
                        // recoverPrunedBlocks is suspend — hand off to coroutine scope
                        scope.launch { recoverPrunedBlocks(rpcUser, rpcPassword, rpcPort) }
                        return
                    }
                }
            }

            // --- Wait for bitcoind RPC ready (sync, no coroutines) ---
            var rpcReady = false
            for (attempt in 1..30) {
                val info = rpc.getBlockchainInfoSync()
                if (info != null && !info.has("_rpc_error")) {
                    Log.i(TAG, "bitcoind RPC ready (attempt $attempt), height=${info.optLong("blocks")}")
                    rpcReady = true
                    break
                }
                Log.d(TAG, "Waiting for bitcoind RPC (attempt $attempt/30)...")
                Thread.sleep(2_000)
            }
            if (!rpcReady) throw Exception("bitcoind RPC not reachable after 60 seconds")

            // --- Stale chain state detection ---
            // If LDK's stored height is far behind bitcoind, synchronize_listeners
            // will hang trying to fetch pruned blocks. Proactively reset chain state
            // while preserving the seed and any channel data.
            val storageDir = File(context.filesDir, STORAGE_DIR)
            if (storageDir.exists()) {
                val chainInfo = rpc.getBlockchainInfoSync()
                val bitcoindHeight = chainInfo?.optLong("blocks", 0) ?: 0
                val lastLdkHeight = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                    .getLong("last_ldk_sync_height", 0)
                val staleThreshold = 500 // blocks behind before we reset
                if (lastLdkHeight > 0 && bitcoindHeight > 0 && (bitcoindHeight - lastLdkHeight) > staleThreshold) {
                    Log.w(TAG, "LDK chain state is stale: LDK at $lastLdkHeight, bitcoind at $bitcoindHeight (${bitcoindHeight - lastLdkHeight} blocks behind)")
                    Log.w(TAG, "Resetting chain state to avoid synchronize_listeners hang. Seed and channels preserved.")
                    resetChainState(storageDir)
                }
            }

            // --- Build LDK node ---
            if (!storageDir.exists()) storageDir.mkdirs()

            val builder = Builder()
            builder.setStorageDirPath(storageDir.absolutePath)

            // Route LDK internal Rust logs to Android logcat
            builder.setCustomLogger(object : LogWriter {
                override fun log(record: LogRecord) {
                    val tag = "LDK"
                    when (record.level) {
                        LogLevel.ERROR -> Log.e(tag, record.args)
                        LogLevel.WARN  -> Log.w(tag, record.args)
                        LogLevel.INFO  -> Log.i(tag, record.args)
                        LogLevel.DEBUG -> Log.d(tag, record.args)
                        LogLevel.TRACE -> Log.v(tag, record.args)
                        LogLevel.GOSSIP -> Log.v(tag, "[gossip] ${record.args}")
                    }
                }
            })

            builder.setNetwork(Network.BITCOIN)

            builder.setChainSourceBitcoindRpc(
                "127.0.0.1",
                rpcPort.toUShort(),
                rpcUser,
                rpcPassword
            )

            builder.setGossipSourceRgs(RGS_URL)

            // --- Wallet birthday for seed recovery ---
            val seedFile = File(storageDir, "keys_seed")
            val birthdayFile = File(storageDir, "wallet_birthday")
            val hasPersistedState = File(storageDir, "bdk_wallet").exists()
            if (seedFile.exists() && !hasPersistedState && birthdayFile.exists()) {
                try {
                    val birthdayHeight = birthdayFile.readText().trim().toUInt()
                    Log.i(TAG, "Wallet birthday found: $birthdayHeight. Setting for recovery sync.")
                    builder.setWalletBirthdayHeight(birthdayHeight)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid wallet_birthday file: ${e.message}")
                }
            }

            val seedPath = seedFile.absolutePath
            val entropy = NodeEntropy.fromSeedPath(seedPath)
            val ldkNode = builder.build(entropy)

            // --- Start LDK (sync, blocks until tokio runtime is running) ---
            // After this point, LDK owns its tokio runtime. Coroutine machinery
            // is safe to use again.
            var lastError: Exception? = null
            for (attempt in 1..10) {
                try {
                    ldkNode.start()
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (e.message?.contains("fee rate", ignoreCase = true) == true && attempt < 10) {
                        Log.w(TAG, "Fee estimates not ready, retry $attempt/10 in 60s...")
                        Thread.sleep(60_000)
                    } else {
                        throw e
                    }
                }
            }
            if (lastError != null) throw lastError

            node = ldkNode

            val nodeId = ldkNode.nodeId()
            val initBalances = ldkNode.listBalances()
            Log.i(TAG, "Lightning node started. Node ID: $nodeId")
            Log.i(TAG, "Initial balances: onchain=${initBalances.totalOnchainBalanceSats} spendable=${initBalances.spendableOnchainBalanceSats} lightning=${initBalances.totalLightningBalanceSats}")

            // Check if a seed restore just happened and needs a recovery scan.
            // Uses SharedPreferences flag (survives file-level resets) — set in applyPendingSeedRestore,
            // cleared here after reading. Only scans once per restore.
            val prefs = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
            val needsRecoveryScan = prefs.getBoolean("pending_recovery_scan", false)
                && !birthdayFile.exists() && initBalances.totalOnchainBalanceSats == 0UL
            prefs.edit().putBoolean("pending_recovery_scan", false).apply()
            // Clean up legacy file marker if present
            val restoredMarker = File(storageDir, "restored_wallet")
            if (restoredMarker.exists()) restoredMarker.delete()

            // Collect scan addresses FIRST — before verification/sweep newAddress() calls
            // advance the internal index past the deposit address
            val scanAddresses = if (needsRecoveryScan) {
                val addrs = mutableListOf<String>()
                for (i in 0 until 20) {
                    try { addrs.add(ldkNode.onchainPayment().newAddress()) } catch (_: Exception) { break }
                }
                Log.i(TAG, "Collected ${addrs.size} addresses for recovery scan")
                addrs
            } else { emptyList() }

            try {
                val bestBlock = ldkNode.status().currentBestBlock
                Log.i(TAG, "LDK best block: height=${bestBlock.height} hash=${bestBlock.blockHash}")
                val newAddr = ldkNode.onchainPayment().newAddress()
                Log.i(TAG, "LDK new deposit address (for verification): $newAddr")

                // Save wallet birthday on first creation (not on restore — scan handles that)
                if (!birthdayFile.exists() && !needsRecoveryScan) {
                    val height = bestBlock.height.toInt()
                    birthdayFile.writeText(height.toString())
                    Log.i(TAG, "Saved wallet birthday: $height")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get LDK status: ${e.message}")
            }

            // Watchtower sweep address
            watchtowerBridge = WatchtowerBridge(context)
            try {
                val prefs = context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
                val sweepKey = "sweep_address_${nodeId.take(16)}"
                var sweepAddr = prefs.getString(sweepKey, null)
                if (sweepAddr == null) {
                    sweepAddr = ldkNode.onchainPayment().newAddress()
                    prefs.edit().putString(sweepKey, sweepAddr).apply()
                    Log.i(TAG, "Generated watchtower sweep address: $sweepAddr")
                } else {
                    Log.i(TAG, "Reusing watchtower sweep address: $sweepAddr")
                }
                val scriptPubKey = bech32ToScriptPubKey(sweepAddr)
                if (scriptPubKey != null) ldkNode.watchtowerSetSweepAddress(scriptPubKey)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set watchtower sweep address: ${e.message}")
            }

            lndHubServer = LndHubServer(context).also { it.start() }
            Log.i(TAG, "LNDHub server started on localhost:${LndHubServer.PORT}")

            // --- Background recovery scan fallback ---
            if (needsRecoveryScan && scanAddresses.isNotEmpty()) {
                Thread({
                    backgroundRecoveryScan(ldkNode, rpc, storageDir, rpcUser, rpcPassword, rpcPort, scanAddresses)
                }, "recovery-scan").start()
            }

            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", true).apply()

            starting = false
            updateState()

            // Periodic state refresh — safe to use coroutines here, LDK is running
            stateRefreshJob = scope.launch {
                while (isActive) {
                    delay(10_000)
                    try { updateState() } catch (_: Exception) {}
                }
            }

            // Sync watchdog: if height doesn't advance within 120s after start,
            // the stored chain state is likely corrupted. Reset and restart.
            val startHeight = try { ldkNode.status().currentBestBlock.height.toLong() } catch (_: Exception) { 0L }
            Thread({
                Thread.sleep(120_000) // Wait 2 minutes
                try {
                    val currentHeight = ldkNode.status().currentBestBlock.height.toLong()
                    if (currentHeight <= startHeight && !_state.value.scanningForFunds) {
                        Log.e(TAG, "Sync watchdog: height stuck at $currentHeight after 120s (started at $startHeight). Resetting chain state.")
                        stop()
                        resetChainState(storageDir)
                        // Clear the stale sync height so prune check doesn't block restart
                        context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                            .edit().putLong("last_ldk_sync_height", 0).apply()
                        // Restart on a new thread
                        Thread({
                            Thread.sleep(2_000)
                            start(rpcUser, rpcPassword, rpcPort)
                        }, "ldk-restart").start()
                    } else {
                        Log.i(TAG, "Sync watchdog: height advanced from $startHeight to $currentHeight. Sync healthy.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync watchdog check failed: ${e.message}")
                }
            }, "ldk-sync-watchdog").start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lightning node", e)
            starting = false
            val errorMsg = e.message ?: "Unknown error"

            if (errorMsg.contains("WalletSetupFailed") || errorMsg.contains("wallet")) {
                val recovered = tryRestoreSeedBackup()
                if (recovered) {
                    _state.value = _state.value.copy(
                        status = LightningState.Status.ERROR,
                        error = "Wallet seed restored from backup. Please try starting again."
                    )
                    return
                }
            }

            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = errorMsg
            )
        }
    }

    /**
     * Try to restore the most recent seed backup that differs from the current seed.
     * Returns true if a backup was restored.
     */
    /**
     * Reset LDK chain state while preserving seed and channel data.
     * Deletes files that synchronize_listeners uses to determine its starting point.
     * On next start, LDK will sync from the current chain tip instead of the stale height.
     */
    private fun resetChainState(storageDir: File) {
        val preserveNames = setOf("keys_seed", "keys_seed.bak", "channel_manager", "monitors", "wallet_birthday")
        storageDir.listFiles()?.forEach { file ->
            if (file.name !in preserveNames) {
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                Log.d(TAG, "resetChainState: ${if (deleted) "deleted" else "FAILED to delete"} ${file.name}")
            } else {
                Log.d(TAG, "resetChainState: preserved ${file.name}")
            }
        }
    }

    private fun tryRestoreSeedBackup(): Boolean {
        val storageDir = File(context.filesDir, STORAGE_DIR)
        val seedFile = File(storageDir, "keys_seed")
        val currentSeed = if (seedFile.exists()) seedFile.readBytes() else return false

        val backups = storageDir.listFiles()?.filter {
            it.name.startsWith("keys_seed.bak.")
        }?.sortedByDescending { it.lastModified() } ?: return false

        for (backup in backups) {
            val backupSeed = backup.readBytes()
            if (backupSeed.size == 64 && !backupSeed.contentEquals(currentSeed)) {
                seedFile.writeBytes(backupSeed)
                Log.i(TAG, "Auto-restored seed from ${backup.name}")
                return true
            }
        }
        return false
    }

    // === Prune Recovery ===

    private suspend fun recoverPrunedBlocks(
        rpcUser: String, rpcPassword: String, rpcPort: Int
    ) = withContext(Dispatchers.IO) {
        val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)

        val chainInfo = rpc.getBlockchainInfo() ?: run {
            Log.e(TAG, "Prune recovery: can't reach bitcoind")
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Cannot reach bitcoind for block recovery"
            )
            return@withContext
        }

        val pruneHeight = chainInfo.optLong("pruneheight", 0)
        val currentHeight = chainInfo.optLong("blocks", 0)
        if (pruneHeight <= 0 || currentHeight <= 0) {
            Log.e(TAG, "Prune recovery: invalid chain info (prune=$pruneHeight, height=$currentHeight)")
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Could not determine pruned block range"
            )
            return@withContext
        }

        val blocksNeeded = (currentHeight - pruneHeight).toInt().coerceAtLeast(1)
        Log.i(TAG, "Prune recovery: need to re-download ~$blocksNeeded blocks (prune height: $pruneHeight, tip: $currentHeight)")

        val networkMonitor = NetworkMonitor(context)
        if (networkMonitor.networkState.value != NetworkState.WIFI) {
            Log.i(TAG, "Prune recovery: waiting for WiFi...")
            _state.value = _state.value.copy(
                status = LightningState.Status.RECOVERING,
                recoveryBlocksNeeded = blocksNeeded,
                recoveryBlocksDone = 0,
                recoveryWaitingForWifi = true,
                error = null
            )
            while (networkMonitor.networkState.value != NetworkState.WIFI) {
                delay(5000)
                if (!starting) {
                    Log.i(TAG, "Prune recovery: cancelled while waiting for WiFi")
                    return@withContext
                }
            }
            Log.i(TAG, "Prune recovery: WiFi connected, starting recovery")
        }

        _state.value = _state.value.copy(
            status = LightningState.Status.RECOVERING,
            recoveryBlocksNeeded = blocksNeeded,
            recoveryBlocksDone = 0,
            recoveryWaitingForWifi = false,
            error = null
        )

        try {
            val hashResult = rpc.call("getblockhash", JSONArray().apply { put(pruneHeight) })
            val pruneHash = hashResult?.optString("value") ?: run {
                Log.e(TAG, "Prune recovery: can't get block hash at height $pruneHeight")
                _state.value = _state.value.copy(
                    status = LightningState.Status.ERROR,
                    error = "Could not get block hash for recovery"
                )
                return@withContext
            }

            Log.i(TAG, "Prune recovery: invalidating block $pruneHash at height $pruneHeight")
            rpc.call("invalidateblock", JSONArray().apply { put(pruneHash) })

            Log.i(TAG, "Prune recovery: reconsidering block to trigger re-download")
            rpc.call("reconsiderblock", JSONArray().apply { put(pruneHash) })

            var lastHeight = 0L
            var stallCount = 0
            while (true) {
                delay(2000)
                if (!starting) {
                    Log.i(TAG, "Prune recovery: cancelled during re-download")
                    return@withContext
                }
                val info = rpc.getBlockchainInfo() ?: continue
                val height = info.optLong("blocks", 0)

                if (height >= currentHeight) {
                    val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                    _state.value = _state.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))
                    Log.i(TAG, "Prune recovery: complete! Chain at $height")
                    break
                }

                val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))

                if (height == lastHeight) {
                    stallCount++
                    if (stallCount > 30) {
                        Log.w(TAG, "Prune recovery: stalled at $height for 60s")
                        _state.value = _state.value.copy(
                            status = LightningState.Status.ERROR,
                            error = "Block recovery stalled at $height. Try again on a faster connection."
                        )
                        return@withContext
                    }
                } else {
                    stallCount = 0
                }
                lastHeight = height
            }

            Log.i(TAG, "Prune recovery: retrying Lightning start...")
            _state.value = _state.value.copy(
                status = LightningState.Status.STARTING,
                recoveryBlocksNeeded = 0,
                recoveryBlocksDone = 0,
                error = null
            )
            delay(100)
            // Hand back to a plain thread for the retry — same rule applies
            Thread({ startInternal(rpcUser, rpcPassword, rpcPort) }, "ldk-start").start()

        } catch (e: Exception) {
            Log.e(TAG, "Prune recovery failed", e)
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Block recovery failed: ${e.message}"
            )
        }
    }

    fun stop() {
        try {
            stateRefreshJob?.cancel()
            stateRefreshJob = null
            lndHubServer?.stop()
            lndHubServer = null
            node?.stop()
            context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", false).apply()
            Log.i(TAG, "Lightning node stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Lightning node", e)
        } finally {
            node = null
            _state.value = LightningState()
        }
    }

    fun updateState() {
        val n = node ?: return
        try {
            val channels = n.listChannels()
            val balances = n.listBalances()

            try {
                val bestBlock = n.status().currentBestBlock
                val height = bestBlock.height.toLong()
                if (height > 0) {
                    context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                        .edit().putLong("last_ldk_sync_height", height).apply()
                }
            } catch (_: Exception) {}

            val bestBlock = n.status().currentBestBlock
            Log.d(TAG, "updateState: onchain=${balances.totalOnchainBalanceSats} lightning=${balances.totalLightningBalanceSats} spendable=${balances.spendableOnchainBalanceSats} channels=${channels.size} ldkHeight=${bestBlock.height}")

            val pending = channels.filter { it.isChannelReady == false }.map { ch ->
                LightningState.PendingChannel(
                    channelId = ch.channelId.toString().take(16),
                    peerAlias = ch.counterpartyNodeId.toString().take(16),
                    confirmations = ch.confirmations?.toInt() ?: 0,
                    confirmationsRequired = ch.confirmationsRequired?.toInt() ?: 3,
                    capacitySats = ch.channelValueSats.toLong()
                )
            }

            _state.value = LightningState(
                status = LightningState.Status.RUNNING,
                nodeId = n.nodeId(),
                onchainBalanceSats = balances.totalOnchainBalanceSats.toLong(),
                lightningBalanceSats = balances.totalLightningBalanceSats.toLong(),
                channelCount = channels.size,
                totalCapacitySats = channels.sumOf { it.channelValueSats.toLong() },
                totalInboundSats = channels.sumOf {
                    (it.channelValueSats.toLong() - (it.outboundCapacityMsat.toLong() / 1000))
                },
                error = null,
                scanningForFunds = _state.value.scanningForFunds,
                scanProgress = _state.value.scanProgress,
                lastChannelError = _state.value.lastChannelError,
                pendingChannels = pending
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update state", e)
        }
    }

    fun handleEvents() {
        val n = node ?: return
        try {
            val event = n.nextEvent() ?: return
            when (event) {
                is Event.PaymentSuccessful -> Log.i(TAG, "Payment successful: ${event.paymentId}")
                is Event.PaymentFailed     -> Log.w(TAG, "Payment failed: ${event.paymentId}")
                is Event.PaymentReceived   -> Log.i(TAG, "Payment received: ${event.amountMsat} msat")
                is Event.ChannelPending    -> {
                    Log.i(TAG, "Channel pending: ${event.channelId} (funding txo: ${event.fundingTxo})")
                    channelEventLatch?.countDown()
                }
                is Event.ChannelReady      -> {
                    Log.i(TAG, "Channel ready: ${event.channelId}")
                    channelEventLatch?.countDown()
                }
                is Event.ChannelClosed     -> {
                    val reason = event.reason?.toString() ?: "unknown"
                    Log.w(TAG, "Channel closed: ${event.channelId} reason: $reason")
                    _state.value = _state.value.copy(lastChannelError = reason)
                    // Cache peer's min channel size from rejection message
                    val peerId = event.counterpartyNodeId
                    if (peerId != null) {
                        val minBtc = Regex("""min chan size of (\d+\.\d+) BTC""").find(reason)?.groupValues?.get(1)
                        if (minBtc != null) {
                            val minSats = (minBtc.toDouble() * 100_000_000).toLong()
                            savePeerMinChannel(peerId, minSats)
                        }
                    }
                    channelEventLatch?.countDown()
                }
                else -> Log.d(TAG, "Event: $event")
            }
            n.eventHandled()
            updateState()
            if (event is Event.ChannelReady || event is Event.ChannelClosed
                || event is Event.PaymentSuccessful || event is Event.PaymentReceived) {
                drainWatchtowerBlobs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling events", e)
        }
    }

    private fun savePeerMinChannel(peerId: PublicKey, minSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        prefs.edit()
            .putLong(peerId.toString(), minSats)
            .putBoolean("${peerId}_floor", false)  // exact value, not a floor
            .apply()
        Log.i(TAG, "Cached peer min channel: ${peerId.toString().take(16)}... = $minSats sats")
    }

    fun getPeerMinChannel(peerId: String): Long {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return prefs.getLong(peerId, -1L)
    }

    /** true if the stored min is an exact value from a rejection message, false if it's a floor (amount+) */
    fun isPeerMinExact(peerId: String): Boolean {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return !prefs.getBoolean("${peerId}_floor", false)
    }

    private fun savePeerMinCeiling(peerId: String, acceptedSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        val existing = prefs.getLong(peerId, -1L)
        val isExistingExact = !prefs.getBoolean("${peerId}_floor", false) && !prefs.getBoolean("${peerId}_ceiling", false)
        // Don't overwrite an exact min. Only update ceiling if lower.
        if (isExistingExact && existing > 0) return
        if (existing < 0 || acceptedSats < existing) {
            prefs.edit()
                .putLong(peerId, acceptedSats)
                .putBoolean("${peerId}_floor", false)
                .putBoolean("${peerId}_ceiling", true)
                .apply()
            Log.i(TAG, "Cached peer min ceiling: ${peerId.take(16)}... <= $acceptedSats sats")
        }
    }

    fun isPeerMinCeiling(peerId: String): Boolean {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return prefs.getBoolean("${peerId}_ceiling", false)
    }

    private fun savePeerMinFloor(peerId: String, attemptedSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        val existing = prefs.getLong(peerId, -1L)
        val isExistingExact = !prefs.getBoolean("${peerId}_floor", false)
        // Don't overwrite an exact min with a floor. Only update floor if higher.
        if (isExistingExact && existing > 0) return
        if (attemptedSats > existing) {
            prefs.edit()
                .putLong(peerId, attemptedSats)
                .putBoolean("${peerId}_floor", true)
                .apply()
            Log.i(TAG, "Cached peer min floor: ${peerId.take(16)}... > $attemptedSats sats")
        }
    }

    private fun drainWatchtowerBlobs() {
        val n = node ?: return
        val bridge = watchtowerBridge ?: return
        Thread {
            try {
                val count = bridge.drainAndPush(n)
                if (count > 0) Log.i(TAG, "Watchtower: pushed $count justice blob(s) to tower")
            } catch (e: Exception) {
                Log.e(TAG, "Watchtower drain failed: ${e.message}")
            }
        }.start()
    }

    // === Channel operations ===

    fun openChannel(nodeId: String, address: String, amountSats: Long): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            Log.i(TAG, "Connecting to peer $nodeId at $address")
            n.connect(nodeId, address, true)
            Log.i(TAG, "Connected. Opening channel for $amountSats sats")
            val userChannelId = n.openChannel(nodeId, address, amountSats.toULong(), null, null)
            Log.i(TAG, "Channel open initiated: $userChannelId")
            // Clear any previous channel error
            _state.value = _state.value.copy(lastChannelError = null)
            // Poll for peer response. LDK creates the channel locally before peer responds,
            // so we must wait long enough for rejection to arrive (~1-2s typically).
            // Drain events during the wait to catch ChannelClosed reason.
            for (i in 1..6) { // 6 x 500ms = 3s
                Thread.sleep(500)
                handleEvents()
            }
            val channels = n.listChannels()
            val hasNewChannel = channels.isNotEmpty()
            updateState()
            val reason = _state.value.lastChannelError
            Log.i(TAG, "Post-open: channels=${channels.size} hasNew=$hasNewChannel reason=$reason")
            if (!hasNewChannel) {
                // If no explicit min from rejection, save attempted amount as floor
                if (reason == null || !reason.contains("min chan size")) {
                    savePeerMinFloor(nodeId, amountSats)
                }
                val msg = if (reason != null) reason else "Peer rejected channel open"
                Result.failure(Exception(msg))
            } else {
                // Peer accepted: their minimum is at most this amount
                savePeerMinCeiling(nodeId, amountSats)
                Result.success(userChannelId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open channel: ${e.message}", e)
            // Save floor for connection failures (peer blocked us)
            savePeerMinFloor(nodeId, amountSats)
            Result.failure(e)
        }
    }

    fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            n.closeChannel(userChannelId, counterpartyNodeId)
            updateState()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close channel", e)
            Result.failure(e)
        }
    }

    fun forceCloseChannel(userChannelId: String, counterpartyNodeId: String, reason: String = "User requested"): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            n.forceCloseChannel(userChannelId, counterpartyNodeId, reason)
            updateState()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force-close channel", e)
            Result.failure(e)
        }
    }

    fun listChannels(): List<ChannelDetails> = node?.listChannels() ?: emptyList()

    // === Payment operations ===

    fun payInvoice(invoiceStr: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val invoice = Bolt11Invoice.fromStr(invoiceStr)
            val paymentId = n.bolt11Payment().send(invoice, null)
            Result.success(paymentId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pay invoice", e)
            Result.failure(e)
        }
    }

    fun createInvoice(amountMsat: Long, description: String, expirySecs: Int = 3600): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val desc = Bolt11InvoiceDescription.Direct(description)
            val invoice = n.bolt11Payment().receive(amountMsat.toULong(), desc, expirySecs.toUInt())
            Result.success(invoice.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create invoice", e)
            Result.failure(e)
        }
    }

    fun createOffer(amountMsat: Long, description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receive(amountMsat.toULong(), description, null, null)
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed"))
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    fun createVariableOffer(description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receiveVariableAmount(description, null)
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create variable offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed"))
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    fun payOffer(offerStr: String, amountMsat: Long? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = Offer.fromStr(offerStr)
            val paymentId = if (amountMsat != null)
                n.bolt12Payment().sendUsingAmount(offer, amountMsat.toULong(), null, null, null)
            else
                n.bolt12Payment().send(offer, null, null, null)
            Result.success(paymentId.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pay offer", e)
            Result.failure(e)
        }
    }

    fun listPayments(): List<PaymentDetails> = node?.listPayments() ?: emptyList()

    fun removePayment(id: String): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            n.removePayment(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove payment $id", e)
            Result.failure(e)
        }
    }

    // === On-chain wallet ===

    fun getOnchainAddress(): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            Result.success(n.onchainPayment().newAddress())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address", e)
            Result.failure(e)
        }
    }

    fun sendOnchain(address: String, amountSats: Long, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            Result.success(n.onchainPayment().sendToAddress(address, amountSats.toULong(), rate))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send on-chain", e)
            Result.failure(e)
        }
    }

    fun sendAllOnchain(address: String, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            Result.success(n.onchainPayment().sendAllToAddress(address, false, rate))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send all on-chain", e)
            Result.failure(e)
        }
    }

    fun isRunning(): Boolean = node != null

    // === Seed Backup & Restore ===

    fun getSeedWords(): List<String>? {
        val seedFile = File(context.filesDir, "$STORAGE_DIR/keys_seed")
        Log.d(TAG, "getSeedWords: checking ${seedFile.absolutePath}, exists=${seedFile.exists()}")
        if (!seedFile.exists()) return null
        val rawBytes = seedFile.readBytes()
        Log.d(TAG, "getSeedWords: read ${rawBytes.size} bytes")
        val entropy = when (rawBytes.size) {
            32 -> rawBytes
            64 -> rawBytes.sliceArray(0 until 32)
            else -> { Log.e(TAG, "Unexpected seed size: ${rawBytes.size}"); return null }
        }
        return try {
            Bip39.entropyToMnemonic(entropy, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert seed to mnemonic: ${e.message}")
            null
        }
    }

    fun hasSeed(): Boolean = File(context.filesDir, "$STORAGE_DIR/keys_seed").exists()

    fun restoreFromMnemonic(words: List<String>) {
        val entropy32 = Bip39.mnemonicToEntropy(words, context)

        // Check if lightning_backup has the original 64-byte seed matching this entropy
        val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
        val backupSeed = File(backupDir, "keys_seed")
        val seed64: ByteArray
        if (backupSeed.exists()) {
            val backupBytes = backupSeed.readBytes()
            if (backupBytes.size == 64 && backupBytes.copyOfRange(0, 32).contentEquals(entropy32)) {
                seed64 = backupBytes
                Log.i(TAG, "Matched backup seed — using original 64-byte keys_seed")
            } else {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                seed64 = entropy32 + digest.digest(entropy32)
                Log.i(TAG, "Backup seed doesn't match — generating new 64-byte seed")
            }
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            seed64 = entropy32 + digest.digest(entropy32)
            Log.i(TAG, "No backup seed found — generating new 64-byte seed")
        }

        // Write pending restore file OUTSIDE the lightning dir (no file lock issues)
        // On next start(), we'll apply it before LDK touches anything
        val pendingFile = File(context.filesDir, "pending_seed_restore")
        pendingFile.writeBytes(seed64)
        Log.i(TAG, "Pending seed restore written (${words.size} words). Will apply on next start.")

        // Stop node if running — must fully stop so start() can run fresh
        if (node != null) {
            try { stop() } catch (_: Exception) {}
            // Reset the starting flag so start() won't return early
            synchronized(this) { starting = false }
        }

        context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Apply a pending seed restore before LDK starts.
     * Called at the top of start() before any file handles are opened.
     */
    private fun applyPendingSeedRestore() {
        val pendingFile = File(context.filesDir, "pending_seed_restore")
        if (!pendingFile.exists()) return

        val seed64 = pendingFile.readBytes()
        if (seed64.size != 64) {
            Log.e(TAG, "Invalid pending seed restore file (${seed64.size} bytes). Deleting.")
            pendingFile.delete()
            return
        }

        val storageDir = File(context.filesDir, STORAGE_DIR)
        if (!storageDir.exists()) storageDir.mkdirs()

        // Clear ALL state (including wallet_birthday from previous wallet)
        storageDir.listFiles()?.forEach { file ->
            file.deleteRecursively()
            Log.d(TAG, "Cleared for restore: ${file.name}")
        }

        // Write the new seed
        File(storageDir, "keys_seed").writeBytes(seed64)

        // Copy wallet_birthday from backup if the seed matches
        val backupBirthday = File(context.filesDir, "${STORAGE_DIR}_backup/wallet_birthday")
        val backupSeedFile = File(context.filesDir, "${STORAGE_DIR}_backup/keys_seed")
        if (backupBirthday.exists() && backupSeedFile.exists()) {
            val backupBytes = backupSeedFile.readBytes()
            if (backupBytes.contentEquals(seed64)) {
                backupBirthday.copyTo(File(storageDir, "wallet_birthday"), overwrite = true)
                Log.i(TAG, "Restored wallet birthday: ${backupBirthday.readText().trim()}")
            } else {
                Log.i(TAG, "Backup birthday skipped (different seed). Background scan will run.")
            }
        }

        pendingFile.delete()
        // Mark as restored wallet so we trigger recovery scan (not birthday save) on first start
        context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
            .edit().putBoolean("pending_recovery_scan", true).apply()
        Log.i(TAG, "Seed restore applied. Fresh wallet state ready.")
    }

    /**
     * Background UTXO scan fallback for wallets without a saved birthday.
     * Gets LDK's actual addresses, scans with scantxoutset, and if UTXOs
     * are found at an older height, saves the birthday and restarts LDK.
     */
    private fun backgroundRecoveryScan(
        @Suppress("UNUSED_PARAMETER") ldkNode: org.lightningdevkit.ldknode.Node,
        rpc: com.pocketnode.rpc.BitcoinRpcClient,
        storageDir: File,
        rpcUser: String,
        rpcPassword: String,
        rpcPort: Int,
        addresses: List<String>
    ) {
        try {
            _state.value = _state.value.copy(scanningForFunds = true)

            if (addresses.isEmpty()) {
                Log.w(TAG, "Background recovery scan: no addresses provided")
                return
            }

            // Build addr() descriptors
            val scanObjects = org.json.JSONArray()
            for (addr in addresses) {
                val obj = org.json.JSONObject()
                obj.put("desc", "addr($addr)")
                scanObjects.put(obj)
            }

            // Scan UTXO set for our addresses (~4 min on phone hardware)
            val birthdayHeight = tryScanTxOutSet(rpc, scanObjects)

            if (birthdayHeight == null) {
                Log.i(TAG, "Background recovery scan: no funds found in ${addresses.size} addresses.")
                _state.value = _state.value.copy(scanningForFunds = false)
                File(storageDir, "restored_wallet").delete()
                return
            }

            // Save the birthday and clear restored marker
            File(storageDir, "wallet_birthday").writeText(birthdayHeight.toString())
            File(storageDir, "restored_wallet").delete()
            Log.i(TAG, "Background recovery scan: saved birthday $birthdayHeight. Restarting LDK...")

            // Restart LDK with the birthday — must clear bdk_wallet so birthday takes effect
            stop()
            Thread.sleep(500)
            resetChainState(storageDir)
            synchronized(this) { starting = false }
            start(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Background recovery scan failed: ${e.message}", e)
            _state.value = _state.value.copy(scanningForFunds = false)
        }
    }

    /**
     * Fast scan using block filter index (BIP 157). Returns in seconds.
     * Returns birthday height or null if no matches / index not available.
     */
    /**
     * Scan entire UTXO set for matching addresses (~4 min on phone hardware).
     * Returns birthday height or null if no UTXOs found.
     */
    private fun tryScanTxOutSet(
        rpc: com.pocketnode.rpc.BitcoinRpcClient,
        scanObjects: org.json.JSONArray
    ): Int? {
        try {
            Log.i(TAG, "Recovery scan: falling back to scantxoutset (UTXO set scan)...")

            // Abort any previous scan
            try {
                val abortParams = org.json.JSONArray()
                abortParams.put("abort")
                rpc.callSync("scantxoutset", abortParams, readTimeoutMs = 10_000)
            } catch (_: Exception) {}

            val params = org.json.JSONArray()
            params.put("start")
            params.put(scanObjects)

            // Poll progress while scan runs
            val progressPoller = Thread({
                try {
                    while (!Thread.interrupted()) {
                        Thread.sleep(2_000)
                        val statusParams = org.json.JSONArray()
                        statusParams.put("status")
                        val status = rpc.callSync("scantxoutset", statusParams, readTimeoutMs = 5_000)
                        if (status != null && status.has("progress")) {
                            val pct = status.getInt("progress")
                            _state.value = _state.value.copy(scanProgress = pct)
                        }
                    }
                } catch (_: InterruptedException) {}
                catch (_: Exception) {}
            }, "scan-progress")
            progressPoller.start()

            val result = rpc.callSync("scantxoutset", params, readTimeoutMs = 300_000)
            progressPoller.interrupt()

            if (result == null || result.has("_rpc_error")) {
                val errMsg = result?.optString("_rpc_error", "null response") ?: "null response"
                Log.e(TAG, "scantxoutset failed: $errMsg")
                return null
            }

            val totalAmount = result.optDouble("total_amount", 0.0)
            val totalSats = (totalAmount * 100_000_000).toLong()
            val unspents = result.optJSONArray("unspents") ?: org.json.JSONArray()

            if (totalSats == 0L || unspents.length() == 0) {
                return null
            }

            var minHeight = Int.MAX_VALUE
            for (i in 0 until unspents.length()) {
                val h = unspents.getJSONObject(i).getInt("height")
                if (h < minHeight) minHeight = h
            }

            val birthday = maxOf(minHeight - 10, 0)
            Log.i(TAG, "scantxoutset: found $totalSats sats in ${unspents.length()} UTXOs. " +
                    "Min height: $minHeight, birthday: $birthday")
            return birthday

        } catch (e: Exception) {
            Log.e(TAG, "scantxoutset failed: ${e.message}", e)
            return null
        }
    }

    private fun bech32ToScriptPubKey(address: String): ByteArray? {
        return try {
            val lower = address.lowercase()
            val hrpEnd = lower.lastIndexOf('1')
            if (hrpEnd < 1) return null
            val data = lower.substring(hrpEnd + 1)
            val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
            val values = data.map { charset.indexOf(it) }.filter { it >= 0 }
            if (values.size < 8) return null
            val payload = values.dropLast(6)
            if (payload.isEmpty()) return null
            val witnessVersion = payload[0]
            val program = convertBits(payload.drop(1), 5, 8, false) ?: return null
            val script = ByteArray(2 + program.size)
            script[0] = if (witnessVersion == 0) 0x00 else (0x50 + witnessVersion).toByte()
            script[1] = program.size.toByte()
            program.forEachIndexed { i, b -> script[2 + i] = b }
            script
        } catch (e: Exception) {
            Log.e(TAG, "bech32 decode failed: ${e.message}")
            null
        }
    }

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0; var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value; bits += fromBits
            while (bits >= toBits) { bits -= toBits; result.add(((acc shr bits) and maxv).toByte()) }
        }
        if (pad && bits > 0) result.add(((acc shl (toBits - bits)) and maxv).toByte())
        else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) return null
        return result.toByteArray()
    }
}
