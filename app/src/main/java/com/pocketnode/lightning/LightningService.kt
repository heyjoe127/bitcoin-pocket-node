package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val error: String? = null
    ) {
        enum class Status { STOPPED, STARTING, RUNNING, ERROR }
    }

    private var node: Node? = null
    private var watchtowerBridge: WatchtowerBridge? = null
    private var lndHubServer: LndHubServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Start the Lightning node, connecting to local bitcoind via RPC.
     * Sets STARTING on Main thread and yields a frame so Compose renders it,
     * then performs heavy init on IO dispatcher.
     */
    @Volatile private var starting = false

    fun start(rpcUser: String, rpcPassword: String, rpcPort: Int = 8332) {
        synchronized(this) {
            if (node != null || starting) {
                Log.w(TAG, "Lightning node already running or starting")
                return
            }
            starting = true
        }

        scope.launch {
            // Set STARTING on Main thread where Compose collects
            _state.value = _state.value.copy(status = LightningState.Status.STARTING, error = null)
            // Yield to let Compose render the Starting state
            delay(100)

            // Heavy init on IO thread
            startInternal(rpcUser, rpcPassword, rpcPort)
        }
    }

    private suspend fun startInternal(rpcUser: String, rpcPassword: String, rpcPort: Int) = withContext(Dispatchers.IO) {

        try {
            val storageDir = File(context.filesDir, STORAGE_DIR)
            if (!storageDir.exists()) storageDir.mkdirs()

            val builder = Builder()

            // Storage
            builder.setStorageDirPath(storageDir.absolutePath)

            // Network
            builder.setNetwork(Network.BITCOIN)

            // Chain source: local bitcoind RPC
            builder.setChainSourceBitcoindRpc(
                "127.0.0.1",
                rpcPort.toUShort(),
                rpcUser,
                rpcPassword
            )

            // Gossip: Rapid Gossip Sync for mobile efficiency
            builder.setGossipSourceRgs(RGS_URL)

            // Entropy: read or generate seed in storage dir
            val seedPath = File(storageDir, "keys_seed").absolutePath
            val entropy = NodeEntropy.fromSeedPath(seedPath)

            // Build and start
            val ldkNode = builder.build(entropy)
            ldkNode.start()

            node = ldkNode

            val nodeId = ldkNode.nodeId()
            Log.i(TAG, "Lightning node started. Node ID: $nodeId")

            // Initialize watchtower bridge and set sweep address
            watchtowerBridge = WatchtowerBridge(context)
            try {
                val prefs = context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
                // Key sweep address to this node's identity so a seed change
                // invalidates the old address automatically
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
                if (scriptPubKey != null) {
                    ldkNode.watchtowerSetSweepAddress(scriptPubKey)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set watchtower sweep address: ${e.message}")
            }

            // Start LNDHub API server for external wallet apps
            lndHubServer = LndHubServer(context).also { it.start() }
            Log.i(TAG, "LNDHub server started on localhost:${LndHubServer.PORT}")

            // Save running state for auto-start on next boot
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", true).apply()

            starting = false
            updateState()

        } catch (e: Exception) {
            starting = false
            Log.e(TAG, "Failed to start Lightning node", e)

            // Auto-recover from bad seed: restore most recent backup
            if (e.message?.contains("WalletSetupFailed") == true ||
                e.message?.contains("wallet") == true) {
                val recovered = tryRestoreSeedBackup()
                if (recovered) {
                    _state.value = _state.value.copy(
                        status = LightningState.Status.ERROR,
                        error = "Wallet seed restored from backup. Please try starting again."
                    )
                    return@withContext
                }
            }

            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Try to restore the most recent seed backup that differs from the current seed.
     * Returns true if a backup was restored.
     */
    private fun tryRestoreSeedBackup(): Boolean {
        val storageDir = File(context.filesDir, STORAGE_DIR)
        val seedFile = File(storageDir, "keys_seed")
        val currentSeed = if (seedFile.exists()) seedFile.readBytes() else return false

        // Find backups, sorted newest first
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

    /**
     * Stop the Lightning node gracefully.
     */
    fun stop() {
        try {
            lndHubServer?.stop()
            lndHubServer = null
            node?.stop()
            // Clear auto-start flag
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

    /**
     * Update the observable state from ldk-node.
     */
    fun updateState() {
        val n = node ?: return
        try {
            val channels = n.listChannels()
            val balances = n.listBalances()

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

    /**
     * Process pending LDK events. Call this periodically.
     */
    fun handleEvents() {
        val n = node ?: return
        try {
            val event = n.nextEvent() ?: return
            when (event) {
                is Event.PaymentSuccessful -> {
                    Log.i(TAG, "Payment successful: ${event.paymentId}")
                }
                is Event.PaymentFailed -> {
                    Log.w(TAG, "Payment failed: ${event.paymentId}")
                }
                is Event.PaymentReceived -> {
                    Log.i(TAG, "Payment received: ${event.amountMsat} msat")
                }
                is Event.ChannelReady -> {
                    Log.i(TAG, "Channel ready: ${event.channelId}")
                }
                is Event.ChannelClosed -> {
                    Log.i(TAG, "Channel closed: ${event.channelId}")
                }
                else -> {
                    Log.d(TAG, "Event: $event")
                }
            }
            n.eventHandled()
            updateState()

            // Drain watchtower blobs after any channel state change.
            // Every payment creates a new commitment tx that needs tower backup.
            if (event is Event.ChannelReady || event is Event.ChannelClosed
                || event is Event.PaymentSuccessful || event is Event.PaymentReceived) {
                drainWatchtowerBlobs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling events", e)
        }
    }

    // === Watchtower ===

    /**
     * Drain pending justice blobs from ldk-node and encrypt for tower push.
     * Called automatically after channel state changes.
     */
    private fun drainWatchtowerBlobs() {
        val n = node ?: return
        val bridge = watchtowerBridge ?: return
        // Run on background thread -- involves SSH tunnel + network I/O
        Thread {
            try {
                val count = bridge.drainAndPush(n)
                if (count > 0) {
                    Log.i(TAG, "Watchtower: pushed $count justice blob(s) to tower")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchtower drain failed: ${e.message}")
            }
        }.start()
    }

    // === Channel operations ===

    fun openChannel(nodeId: String, address: String, amountSats: Long): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            // Connect to peer first, then open channel
            n.connect(nodeId, address, true)
            val userChannelId = n.openChannel(
                nodeId,
                address,
                amountSats.toULong(),
                null, // push amount
                null  // channel config
            )
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

    fun listChannels(): List<ChannelDetails> {
        return node?.listChannels() ?: emptyList()
    }

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
            val invoice = n.bolt11Payment().receive(
                amountMsat.toULong(),
                desc,
                expirySecs.toUInt()
            )
            Result.success(invoice.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create invoice", e)
            Result.failure(e)
        }
    }

    /**
     * Create a reusable BOLT 12 offer with a fixed amount.
     */
    fun createOffer(amountMsat: Long, description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receive(
                amountMsat.toULong(),
                description,
                null, // no expiry
                null  // no quantity
            )
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed") == true)
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    /**
     * Create a reusable BOLT 12 offer with variable amount (payer chooses).
     */
    fun createVariableOffer(description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receiveVariableAmount(
                description,
                null // no expiry
            )
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create variable offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed") == true)
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    /**
     * Pay a BOLT 12 offer.
     */
    fun payOffer(offerStr: String, amountMsat: Long? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = Offer.fromStr(offerStr)
            val paymentId = if (amountMsat != null) {
                n.bolt12Payment().sendUsingAmount(offer, amountMsat.toULong(), null, null, null)
            } else {
                n.bolt12Payment().send(offer, null, null, null)
            }
            Result.success(paymentId.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pay offer", e)
            Result.failure(e)
        }
    }

    fun listPayments(): List<PaymentDetails> {
        return node?.listPayments() ?: emptyList()
    }

    // === On-chain wallet ===

    fun getOnchainAddress(): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val address = n.onchainPayment().newAddress()
            Result.success(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address", e)
            Result.failure(e)
        }
    }

    fun sendOnchain(address: String, amountSats: Long, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            val txid = n.onchainPayment().sendToAddress(address, amountSats.toULong(), rate)
            Result.success(txid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send on-chain", e)
            Result.failure(e)
        }
    }

    fun sendAllOnchain(address: String, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            val txid = n.onchainPayment().sendAllToAddress(address, false, rate)
            Result.success(txid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send all on-chain", e)
            Result.failure(e)
        }
    }

    fun isRunning(): Boolean = node != null

    // === Seed Backup & Restore ===

    /**
     * Get the 24-word BIP39 mnemonic for the current Lightning wallet seed.
     * Returns null if no seed file exists.
     */
    fun getSeedWords(): List<String>? {
        val seedFile = File(context.filesDir, "$STORAGE_DIR/keys_seed")
        Log.d(TAG, "getSeedWords: checking ${seedFile.absolutePath}, exists=${seedFile.exists()}")
        if (!seedFile.exists()) return null
        val rawBytes = seedFile.readBytes()
        Log.d(TAG, "getSeedWords: read ${rawBytes.size} bytes")
        // ldk-node stores 64-byte seed; use first 32 bytes as entropy for BIP39
        val entropy = when (rawBytes.size) {
            32 -> rawBytes
            64 -> rawBytes.sliceArray(0 until 32)
            else -> {
                Log.e(TAG, "Unexpected seed size: ${rawBytes.size}")
                return null
            }
        }
        return try {
            Bip39.entropyToMnemonic(entropy, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert seed to mnemonic: ${e.message}")
            null
        }
    }

    /**
     * Check if a Lightning wallet seed already exists.
     */
    fun hasSeed(): Boolean {
        return File(context.filesDir, "$STORAGE_DIR/keys_seed").exists()
    }

    /**
     * Restore wallet from a 24-word BIP39 mnemonic.
     * Must be called BEFORE start() -- overwrites the existing seed file.
     *
     * @throws IllegalArgumentException if the mnemonic is invalid
     * @throws IllegalStateException if the node is currently running
     */
    fun restoreFromMnemonic(words: List<String>) {
        if (node != null) {
            throw IllegalStateException("Cannot restore while Lightning node is running. Stop it first.")
        }

        // Validate and convert (returns 32 bytes)
        val entropy32 = Bip39.mnemonicToEntropy(words, context)

        val storageDir = File(context.filesDir, STORAGE_DIR)
        if (!storageDir.exists()) storageDir.mkdirs()
        val seedFile = File(storageDir, "keys_seed")

        // Check if the current seed matches (same first 32 bytes).
        // If so, no change needed.
        if (seedFile.exists()) {
            val existing = seedFile.readBytes()
            if (existing.size >= 32 && existing.sliceArray(0 until 32).contentEquals(entropy32)) {
                Log.i(TAG, "Seed matches current wallet, no change needed")
                return
            }
        }

        // Check backups for matching seed (preserves original ldk-node second 32 bytes)
        val matchingBackup = storageDir.listFiles()?.filter {
            it.name.startsWith("keys_seed.bak.")
        }?.find { bak ->
            val bytes = bak.readBytes()
            bytes.size >= 32 && bytes.sliceArray(0 until 32).contentEquals(entropy32)
        }

        val seed64: ByteArray
        if (matchingBackup != null) {
            // Restore exact original file (preserves ldk-node's second 32 bytes)
            seed64 = matchingBackup.readBytes()
            Log.i(TAG, "Found matching backup: ${matchingBackup.name}")
        } else {
            // New seed: first 32 from mnemonic, second 32 via SHA256
            // Note: this creates a NEW wallet, not compatible with existing ldk-node state
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            seed64 = entropy32 + digest.digest(entropy32)
            Log.i(TAG, "Creating new wallet from seed (no matching backup found)")

            // Wipe existing ldk-node state since it won't match the new seed
            val lightningDir = File(context.filesDir, STORAGE_DIR)
            lightningDir.listFiles()?.filter { it.name != "keys_seed" && !it.name.startsWith("keys_seed.bak.") }?.forEach {
                it.deleteRecursively()
                Log.d(TAG, "Removed stale state: ${it.name}")
            }
        }

        // Back up existing seed
        if (seedFile.exists()) {
            val backup = File(storageDir, "keys_seed.bak.${System.currentTimeMillis()}")
            seedFile.copyTo(backup)
            Log.i(TAG, "Backed up existing seed to ${backup.name}")
        }

        seedFile.writeBytes(seed64)
        Log.i(TAG, "Wallet seed restored from mnemonic (${words.size} words)")

        // Clear cached sweep address since node identity will change
        context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Convert a bech32/bech32m address to its witness scriptPubKey.
     * Supports p2wpkh (bc1q..., 20 bytes) and p2tr (bc1p..., 32 bytes).
     */
    private fun bech32ToScriptPubKey(address: String): ByteArray? {
        return try {
            val lower = address.lowercase()
            val hrpEnd = lower.lastIndexOf('1')
            if (hrpEnd < 1) return null

            val data = lower.substring(hrpEnd + 1)
            // Decode bech32 data part to 5-bit values
            val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
            val values = data.map { charset.indexOf(it) }.filter { it >= 0 }
            if (values.size < 8) return null // too short (need checksum + data)

            // Strip the 6-char checksum
            val payload = values.dropLast(6)
            if (payload.isEmpty()) return null

            val witnessVersion = payload[0]
            // Convert remaining 5-bit values to 8-bit
            val program = convertBits(payload.drop(1), 5, 8, false) ?: return null

            // Build scriptPubKey: [witness_version] [push_length] [program]
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
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return result.toByteArray()
    }
}
