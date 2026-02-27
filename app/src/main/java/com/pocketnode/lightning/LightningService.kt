package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
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
    private var lndHubServer: LndHubServer? = null

    /**
     * Start the Lightning node, connecting to local bitcoind via RPC.
     */
    fun start(rpcUser: String, rpcPassword: String, rpcPort: Int = 8332) {
        if (node != null) {
            Log.w(TAG, "Lightning node already running")
            return
        }

        _state.value = _state.value.copy(status = LightningState.Status.STARTING, error = null)

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

            // Build and start
            val ldkNode = builder.build()
            ldkNode.start()

            node = ldkNode

            val nodeId = ldkNode.nodeId()
            Log.i(TAG, "Lightning node started. Node ID: $nodeId")

            // Start LNDHub API server for external wallet apps
            lndHubServer = LndHubServer(context).also { it.start() }
            Log.i(TAG, "LNDHub server started on localhost:${LndHubServer.PORT}")

            // Save running state for auto-start on next boot
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", true).apply()

            updateState()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lightning node", e)
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = e.message ?: "Unknown error"
            )
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error handling events", e)
        }
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
}
