package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * LNDHub-compatible REST API server on localhost:3000.
 * Translates LNDHub protocol calls into ldk-node operations.
 * BlueWallet and Zeus (in LNDHub mode) can connect to this.
 *
 * Protocol reference: https://github.com/BlueWallet/LndHub
 */
class LndHubServer(private val context: Context) {

    companion object {
        private const val TAG = "LndHubServer"
        const val PORT = 3000
        private const val ACCESS_TOKEN = "pocketnode_access"
        private const val REFRESH_TOKEN = "pocketnode_refresh"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (isRunning) return

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
                isRunning = true
                Log.i(TAG, "LNDHub server started on localhost:$PORT")

                while (isActive) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (isActive) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "LNDHub server stopped")
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            // Parse HTTP request
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1].split("?")[0] // strip query params

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(":")
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            // Read body
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            Log.d(TAG, "$method $path")

            // Route
            val response = route(method, path, body)
            sendResponse(writer, response)

        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun route(method: String, path: String, body: String): ApiResponse {
        val lightning = LightningService.getInstance(context)

        return when {
            // Auth — BlueWallet sends login/password, we accept anything
            method == "POST" && path == "/auth" -> {
                ApiResponse(200, JSONObject().apply {
                    put("access_token", ACCESS_TOKEN)
                    put("refresh_token", REFRESH_TOKEN)
                })
            }

            // Create account — auto-provision
            method == "POST" && path == "/create" -> {
                ApiResponse(200, JSONObject().apply {
                    put("login", "pocketnode")
                    put("password", "pocketnode")
                })
            }

            // Get node info
            method == "GET" && path == "/getinfo" -> {
                val state = LightningService.stateFlow.value
                ApiResponse(200, JSONObject().apply {
                    put("identity_pubkey", state.nodeId ?: "")
                    put("alias", "Bitcoin Pocket Node")
                    put("num_active_channels", state.channelCount)
                    put("block_height", 0) // TODO: get from bitcoind
                })
            }

            // Get balance
            method == "GET" && path == "/balance" -> {
                val state = LightningService.stateFlow.value
                ApiResponse(200, JSONObject().apply {
                    put("BTC", JSONObject().apply {
                        put("AvailableBalance", state.lightningBalanceSats)
                    })
                })
            }

            // Get on-chain balance
            method == "GET" && path == "/getbtc" -> {
                val state = LightningService.stateFlow.value
                val addrResult = lightning.getOnchainAddress()
                val addr = addrResult.getOrNull() ?: ""
                ApiResponse(200, JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", addr)
                        put("amt", state.onchainBalanceSats)
                    })
                })
            }

            // Create invoice
            method == "POST" && path == "/addinvoice" -> {
                try {
                    val json = JSONObject(body)
                    val amountSats = json.optLong("amt", 0)
                    val memo = json.optString("memo", "")
                    if (amountSats <= 0) {
                        ApiResponse(400, JSONObject().apply {
                            put("error", true)
                            put("message", "Invalid amount")
                        })
                    } else {
                        val result = lightning.createInvoice(
                            amountMsat = amountSats * 1000,
                            description = memo.ifBlank { "Bitcoin Pocket Node" }
                        )
                        result.fold(
                            onSuccess = { invoice ->
                                ApiResponse(200, JSONObject().apply {
                                    put("payment_request", invoice)
                                    // BlueWallet expects r_hash
                                    put("r_hash", "")
                                    put("add_index", System.currentTimeMillis() / 1000)
                                })
                            },
                            onFailure = { e ->
                                ApiResponse(500, JSONObject().apply {
                                    put("error", true)
                                    put("message", e.message ?: "Invoice creation failed")
                                })
                            }
                        )
                    }
                } catch (e: Exception) {
                    ApiResponse(400, JSONObject().apply {
                        put("error", true)
                        put("message", "Invalid request: ${e.message}")
                    })
                }
            }

            // Pay invoice
            method == "POST" && path == "/payinvoice" -> {
                try {
                    val json = JSONObject(body)
                    val invoice = json.optString("invoice", "")
                    if (invoice.isBlank()) {
                        ApiResponse(400, JSONObject().apply {
                            put("error", true)
                            put("message", "Invoice required")
                        })
                    } else {
                        val result = lightning.payInvoice(invoice)
                        result.fold(
                            onSuccess = { paymentId ->
                                ApiResponse(200, JSONObject().apply {
                                    put("payment_preimage", paymentId)
                                    put("payment_hash", "")
                                    put("decoded", JSONObject())
                                })
                            },
                            onFailure = { e ->
                                ApiResponse(500, JSONObject().apply {
                                    put("error", true)
                                    put("message", e.message ?: "Payment failed")
                                })
                            }
                        )
                    }
                } catch (e: Exception) {
                    ApiResponse(400, JSONObject().apply {
                        put("error", true)
                        put("message", "Invalid request: ${e.message}")
                    })
                }
            }

            // Get user invoices (received payments)
            method == "GET" && path == "/getuserinvoices" -> {
                val payments = lightning.listPayments()
                    .filter { it.direction == org.lightningdevkit.ldknode.PaymentDirection.INBOUND }
                val arr = JSONArray()
                payments.forEach { p ->
                    arr.put(JSONObject().apply {
                        put("payment_hash", p.id)
                        put("amt", (p.amountMsat?.toLong() ?: 0L) / 1000)
                        put("ispaid", p.status == org.lightningdevkit.ldknode.PaymentStatus.SUCCEEDED)
                        put("type", "user_invoice")
                        put("timestamp", p.latestUpdateTimestamp.toLong())
                    })
                }
                ApiResponse(200, arr)
            }

            // Get transactions (sent payments)
            method == "GET" && path == "/gettxs" -> {
                val payments = lightning.listPayments()
                    .filter { it.direction == org.lightningdevkit.ldknode.PaymentDirection.OUTBOUND }
                val arr = JSONArray()
                payments.forEach { p ->
                    arr.put(JSONObject().apply {
                        put("payment_hash", p.id)
                        put("value", (p.amountMsat?.toLong() ?: 0L) / 1000)
                        put("type", "paid_invoice")
                        put("timestamp", p.latestUpdateTimestamp.toLong())
                    })
                }
                ApiResponse(200, arr)
            }

            // Get pending transactions
            method == "GET" && path == "/getpending" -> {
                val payments = lightning.listPayments()
                    .filter { it.status == org.lightningdevkit.ldknode.PaymentStatus.PENDING }
                val arr = JSONArray()
                payments.forEach { p ->
                    arr.put(JSONObject().apply {
                        put("payment_hash", p.id)
                        put("value", (p.amountMsat?.toLong() ?: 0L) / 1000)
                        put("type", if (p.direction == org.lightningdevkit.ldknode.PaymentDirection.INBOUND) "user_invoice" else "paid_invoice")
                        put("timestamp", p.latestUpdateTimestamp.toLong())
                    })
                }
                ApiResponse(200, arr)
            }

            // Decode invoice — optional but BlueWallet uses it
            method == "GET" && path.startsWith("/decodeinvoice") -> {
                // BlueWallet sends ?invoice=lnbc...
                val queryStr = if ("?" in path) path.substringAfter("?") else ""
                val invoiceStr = queryStr.split("&")
                    .firstOrNull { it.startsWith("invoice=") }
                    ?.removePrefix("invoice=")
                    ?: ""
                try {
                    val invoice = org.lightningdevkit.ldknode.Bolt11Invoice.fromStr(invoiceStr)
                    ApiResponse(200, JSONObject().apply {
                        put("destination", "")
                        put("num_satoshis", (invoice.amountMilliSatoshis()?.toLong() ?: 0L) / 1000)
                        put("description", "")
                        put("timestamp", "")
                        put("expiry", invoice.expiryTimeSeconds().toLong())
                    })
                } catch (e: Exception) {
                    ApiResponse(400, JSONObject().apply {
                        put("error", true)
                        put("message", "Failed to decode: ${e.message}")
                    })
                }
            }

            // Fallback
            else -> {
                Log.w(TAG, "Unknown route: $method $path")
                ApiResponse(404, JSONObject().apply {
                    put("error", true)
                    put("message", "Not found: $path")
                })
            }
        }
    }

    private fun sendResponse(writer: PrintWriter, response: ApiResponse) {
        val body = response.body.toString()
        writer.print("HTTP/1.1 ${response.status} OK\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Content-Length: ${body.toByteArray().size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private data class ApiResponse(val status: Int, val body: Any)
}
