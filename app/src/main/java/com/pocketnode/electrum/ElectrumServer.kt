package com.pocketnode.electrum

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight Electrum protocol server for personal wallet tracking.
 *
 * Speaks the Electrum JSON-RPC protocol (v1.4) over TCP on localhost,
 * backed by the local bitcoind via JSON-RPC. Replaces BWT with a pure
 * Kotlin implementation that runs in-process.
 *
 * Only tracks addresses/xpubs/descriptors explicitly configured by the user.
 * Not a full chain indexer — this is a personal server like EPS/BWT.
 *
 * Protocol reference: https://electrumx.readthedocs.io/en/latest/protocol-methods.html
 * Implementation reference: bwt-dev/bwt (Rust, MIT license)
 */
class ElectrumServer(
    private val methods: ElectrumMethods,
    private val subscriptions: SubscriptionManager,
    private val host: String = "127.0.0.1",
    private val port: Int = 50001
) {
    companion object {
        private const val TAG = "ElectrumServer"
        const val PROTOCOL_VERSION = "1.4"
        const val SERVER_VERSION = "PocketNode Electrum 0.1"
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val connections = mutableListOf<ConnectionHandler>()

    fun start() {
        if (running.get()) return
        running.set(true)

        acceptThread = Thread({
            try {
                val ss = ServerSocket(port, 5, InetAddress.getByName(host))
                serverSocket = ss
                Log.i(TAG, "Electrum server listening on $host:$port (protocol $PROTOCOL_VERSION)")

                while (running.get()) {
                    try {
                        val client = ss.accept()
                        val handler = ConnectionHandler(client)
                        synchronized(connections) { connections.add(handler) }
                        Thread(handler, "electrum-client-${client.port}").start()
                    } catch (e: java.net.SocketException) {
                        if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed: ${e.message}", e)
            }
        }, "electrum-accept")
        acceptThread?.isDaemon = true
        acceptThread?.start()

        // Start the subscription poller (checks for new blocks/tx changes)
        subscriptions.startPolling { notifications ->
            synchronized(connections) {
                connections.forEach { conn ->
                    notifications.forEach { notification ->
                        conn.sendNotification(notification)
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        subscriptions.stopPolling()
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(connections) {
            connections.forEach { it.close() }
            connections.clear()
        }
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "Electrum server stopped")
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Handles a single Electrum client connection.
     * Protocol: newline-delimited JSON-RPC over TCP.
     */
    inner class ConnectionHandler(private val socket: Socket) : Runnable {
        private var writer: PrintWriter? = null
        private val subscribedScripthashes = mutableSetOf<String>()
        private var subscribedHeaders = false

        override fun run() {
            Log.d(TAG, "Client connected: ${socket.remoteSocketAddress}")
            try {
                socket.soTimeout = 600_000 // 10 min idle timeout
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer = PrintWriter(socket.getOutputStream(), true)

                var line: String?
                while (socket.isConnected && running.get()) {
                    line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    try {
                        val request = JSONObject(line)
                        val response = handleRequest(request)
                        sendJson(response)
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad request: ${e.message}")
                        // Send error response
                        val errorResp = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            put("id", JSONObject.NULL)
                            put("error", "Parse error: ${e.message}")
                        }
                        sendJson(errorResp)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "Client disconnected: ${e.message}")
            } finally {
                close()
                synchronized(connections) { connections.remove(this) }
                Log.d(TAG, "Client handler finished")
            }
        }

        private fun handleRequest(request: JSONObject): JSONObject {
            val method = request.optString("method", "")
            val params = request.optJSONArray("params") ?: JSONArray()
            val id = request.opt("id")

            val result = try {
                dispatch(method, params)
            } catch (e: Exception) {
                Log.w(TAG, "Method $method failed: ${e.message}")
                return JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JSONObject.NULL)
                    put("error", JSONObject().apply {
                        put("code", -32000)
                        put("message", e.message ?: "Internal error")
                    })
                }
            }

            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id ?: JSONObject.NULL)
                put("result", result ?: JSONObject.NULL)
            }
        }

        private fun dispatch(method: String, params: JSONArray): Any? {
            return when (method) {
                "server.version" -> methods.serverVersion()
                "server.banner" -> methods.serverBanner()
                "server.donation_address" -> methods.serverDonationAddress()
                "server.peers.subscribe" -> JSONArray()
                "server.ping" -> JSONObject.NULL

                "blockchain.headers.subscribe" -> {
                    subscribedHeaders = true
                    methods.headersSubscribe()
                }

                "blockchain.scripthash.subscribe" -> {
                    val scripthash = params.getString(0)
                    subscribedScripthashes.add(scripthash)
                    subscriptions.subscribeScripthash(scripthash)
                    methods.scripthashSubscribe(scripthash)
                }
                "blockchain.scripthash.get_balance" ->
                    methods.scripthashGetBalance(params.getString(0))
                "blockchain.scripthash.get_history" ->
                    methods.scripthashGetHistory(params.getString(0))
                "blockchain.scripthash.listunspent" ->
                    methods.scripthashListUnspent(params.getString(0))
                "blockchain.scripthash.get_mempool" ->
                    methods.scripthashGetMempool(params.getString(0))

                "blockchain.transaction.get" -> {
                    val txid = params.getString(0)
                    val verbose = if (params.length() > 1) params.optBoolean(1, false) else false
                    methods.transactionGet(txid, verbose)
                }
                "blockchain.transaction.broadcast" ->
                    methods.transactionBroadcast(params.getString(0))
                "blockchain.transaction.get_merkle" ->
                    methods.transactionGetMerkle(params.getString(0), params.getInt(1))

                "blockchain.block.header" -> {
                    val height = params.getInt(0)
                    val cpHeight = if (params.length() > 1 && !params.isNull(1)) params.getInt(1) else null
                    methods.blockHeader(height, cpHeight)
                }
                "blockchain.block.headers" -> {
                    val startHeight = params.getInt(0)
                    val count = params.getInt(1)
                    val cpHeight = if (params.length() > 2 && !params.isNull(2)) params.getInt(2) else null
                    methods.blockHeaders(startHeight, count, cpHeight)
                }

                "blockchain.estimatefee" ->
                    methods.estimateFee(params.getInt(0))
                "blockchain.relayfee" ->
                    methods.relayFee()

                "mempool.get_fee_histogram" ->
                    methods.mempoolFeeHistogram()

                "blockchain.transaction.id_from_pos" -> {
                    val height = params.getInt(0)
                    val txPos = params.getInt(1)
                    val wantMerkle = if (params.length() > 2) params.optBoolean(2, false) else false
                    methods.transactionIdFromPos(height, txPos, wantMerkle)
                }

                else -> throw IllegalArgumentException("Unknown method: $method")
            }
        }

        fun sendNotification(notification: SubscriptionManager.Notification) {
            when (notification) {
                is SubscriptionManager.Notification.NewBlock -> {
                    if (!subscribedHeaders) return
                    val msg = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "blockchain.headers.subscribe")
                        put("params", JSONArray().apply {
                            put(JSONObject().apply {
                                put("height", notification.height)
                                put("hex", notification.headerHex)
                            })
                        })
                    }
                    sendJson(msg)
                }
                is SubscriptionManager.Notification.ScripthashChanged -> {
                    if (notification.scripthash !in subscribedScripthashes) return
                    val msg = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "blockchain.scripthash.subscribe")
                        put("params", JSONArray().apply {
                            put(notification.scripthash)
                            put(notification.statusHash ?: JSONObject.NULL)
                        })
                    }
                    sendJson(msg)
                }
            }
        }

        @Synchronized
        private fun sendJson(json: JSONObject) {
            try {
                writer?.println(json.toString())
            } catch (e: Exception) {
                Log.d(TAG, "Send failed: ${e.message}")
            }
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
