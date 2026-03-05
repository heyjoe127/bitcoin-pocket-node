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
        val recoveryWaitingForWifi: Boolean = false
    ) {
        enum class Status { STOPPED, STARTING, RUNNING, ERROR, RECOVERING }
    }

    private var node: Node? = null
    private var watchtowerBridge: WatchtowerBridge? = null
    private var lndHubServer: LndHubServer? = null
    private var stateRefreshJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

            val seedPath = File(storageDir, "keys_seed").absolutePath
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
            try {
                val bestBlock = ldkNode.status().currentBestBlock
                Log.i(TAG, "LDK best block: height=${bestBlock.height} hash=${bestBlock.blockHash}")
                val newAddr = ldkNode.onchainPayment().newAddress()
                Log.i(TAG, "LDK new deposit address (for verification): $newAddr")
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
                    if (currentHeight <= startHeight) {
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
        val preserveNames = setOf("keys_seed", "keys_seed.bak", "channel_manager", "monitors")
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
                error = null
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
                is Event.ChannelReady      -> Log.i(TAG, "Channel ready: ${event.channelId}")
                is Event.ChannelClosed     -> Log.i(TAG, "Channel closed: ${event.channelId}")
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
            n.connect(nodeId, address, true)
            val userChannelId = n.openChannel(nodeId, address, amountSats.toULong(), null, null)
            updateState()
            Result.success(userChannelId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open channel", e)
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
        if (node != null) throw IllegalStateException("Cannot restore while Lightning node is running. Stop it first.")

        val entropy32 = Bip39.mnemonicToEntropy(words, context)
        val storageDir = File(context.filesDir, STORAGE_DIR)
        if (!storageDir.exists()) storageDir.mkdirs()
        val seedFile = File(storageDir, "keys_seed")

        if (seedFile.exists()) {
            val existing = seedFile.readBytes()
            if (existing.size >= 32 && existing.sliceArray(0 until 32).contentEquals(entropy32)) {
                Log.i(TAG, "Seed matches current wallet, no change needed")
                return
            }
        }

        val matchingBackup = storageDir.listFiles()?.filter {
            it.name.startsWith("keys_seed.bak.")
        }?.find { bak ->
            val bytes = bak.readBytes()
            bytes.size >= 32 && bytes.sliceArray(0 until 32).contentEquals(entropy32)
        }

        val seed64: ByteArray
        if (matchingBackup != null) {
            seed64 = matchingBackup.readBytes()
            Log.i(TAG, "Found matching backup: ${matchingBackup.name}")
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            seed64 = entropy32 + digest.digest(entropy32)
            Log.i(TAG, "Creating new wallet from seed (no matching backup found)")
            val lightningDir = File(context.filesDir, STORAGE_DIR)
            lightningDir.listFiles()?.filter {
                it.name != "keys_seed" && !it.name.startsWith("keys_seed.bak.")
            }?.forEach {
                it.deleteRecursively()
                Log.d(TAG, "Removed stale state: ${it.name}")
            }
        }

        if (seedFile.exists()) {
            val backup = File(storageDir, "keys_seed.bak.${System.currentTimeMillis()}")
            seedFile.copyTo(backup)
            Log.i(TAG, "Backed up existing seed to ${backup.name}")
        }

        seedFile.writeBytes(seed64)
        Log.i(TAG, "Wallet seed restored from mnemonic (${words.size} words)")
        context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE).edit().clear().apply()
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
