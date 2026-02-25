package com.pocketnode.electrum

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Implements Electrum protocol methods backed by bitcoind RPC.
 *
 * Each method corresponds to an Electrum JSON-RPC call.
 * Methods are called synchronously from the connection handler thread;
 * internally they use runBlocking to bridge to the suspend RPC client.
 *
 * Reference: bwt-dev/bwt server.rs (MIT license)
 */
class ElectrumMethods(
    private val rpc: BitcoinRpcClient,
    private val addressIndex: AddressIndex
) {
    companion object {
        private const val TAG = "ElectrumMethods"
        private const val MAX_HEADERS = 2016
    }

    // -- Server methods --

    fun serverVersion(): JSONArray {
        return JSONArray().apply {
            put(ElectrumServer.SERVER_VERSION)
            put(ElectrumServer.PROTOCOL_VERSION)
        }
    }

    fun serverBanner(): String {
        return "Bitcoin Pocket Node - Personal Electrum Server\n" +
            "Connected to your own full node. Self-validated, no third parties.\n" +
            "Add your wallet's xpub in the app to track balances and transactions."
    }

    fun serverDonationAddress(): String {
        return ""  // No donation address
    }

    // -- Header methods --

    fun headersSubscribe(): JSONObject = runBlocking {
        val info = rpc.call("getblockchaininfo")
        val height = info?.optInt("blocks", 0) ?: 0
        val bestHash = info?.optString("bestblockhash", "") ?: ""

        val headerHex = if (bestHash.isNotEmpty()) {
            getHeaderHex(bestHash)
        } else ""

        JSONObject().apply {
            put("height", height)
            put("hex", headerHex)
        }
    }

    fun blockHeader(height: Int, cpHeight: Int?): Any = runBlocking {
        val hash = getBlockHash(height)
        val headerHex = getHeaderHex(hash)

        if (cpHeight != null && cpHeight > 0) {
            val (branch, root) = headerMerkleProof(height, cpHeight)
            JSONObject().apply {
                put("header", headerHex)
                put("root", root)
                put("branch", JSONArray(branch))
            }
        } else {
            headerHex  // Return as plain string when no checkpoint
        }
    }

    fun blockHeaders(startHeight: Int, count: Int, cpHeight: Int?): JSONObject = runBlocking {
        val tipHeight = getTipHeight()
        val actualCount = minOf(count, MAX_HEADERS)
        val maxHeight = minOf(startHeight + actualCount - 1, tipHeight)

        val headers = StringBuilder()
        var fetched = 0
        for (h in startHeight..maxHeight) {
            val hash = getBlockHash(h)
            headers.append(getHeaderHex(hash))
            fetched++
        }

        val result = JSONObject().apply {
            put("count", fetched)
            put("hex", headers.toString())
            put("max", MAX_HEADERS)
        }

        if (cpHeight != null && cpHeight > 0 && fetched > 0) {
            val (branch, root) = headerMerkleProof(startHeight + fetched - 1, cpHeight)
            result.put("root", root)
            result.put("branch", JSONArray(branch))
        }

        result
    }

    // -- Scripthash methods --

    fun scripthashSubscribe(scripthash: String): Any = runBlocking {
        val statusHash = addressIndex.getStatusHash(scripthash)
        statusHash ?: JSONObject.NULL
    }

    fun scripthashGetBalance(scripthash: String): JSONObject = runBlocking {
        val (confirmed, unconfirmed) = addressIndex.getBalance(scripthash)
        JSONObject().apply {
            put("confirmed", confirmed)
            put("unconfirmed", unconfirmed)
        }
    }

    fun scripthashGetHistory(scripthash: String): JSONArray = runBlocking {
        addressIndex.getHistory(scripthash)
    }

    fun scripthashListUnspent(scripthash: String): JSONArray = runBlocking {
        addressIndex.listUnspent(scripthash)
    }

    fun scripthashGetMempool(scripthash: String): JSONArray {
        // Mempool entries for this scripthash — covered by getHistory with height=0
        return JSONArray()
    }

    // -- Transaction methods --

    fun transactionGet(txid: String, verbose: Boolean): Any = runBlocking {
        val params = JSONArray().apply {
            put(txid)
            put(if (verbose) 1 else 0)
        }
        val result = rpc.call("getrawtransaction", params)

        if (verbose) {
            // Return the full JSON object for verbose mode
            result ?: JSONObject()
        } else {
            // Return raw hex string
            result?.optString("value", "") ?: ""
        }
    }

    fun transactionBroadcast(txHex: String): String = runBlocking {
        val result = rpc.call("sendrawtransaction", JSONArray().apply { put(txHex) })
        result?.optString("value", "") ?: throw Exception("Broadcast failed")
    }

    fun transactionGetMerkle(txid: String, height: Int): JSONObject = runBlocking {
        // Get block txids and compute merkle proof
        val hash = getBlockHash(height)
        val block = rpc.call("getblock", JSONArray().apply { put(hash); put(1) })
        val txArray = block?.optJSONArray("tx") ?: JSONArray()

        val txids = mutableListOf<String>()
        for (i in 0 until txArray.length()) {
            txids.add(txArray.getString(i))
        }

        val pos = txids.indexOf(txid)
        val (branch, _) = createMerkleBranchAndRoot(
            txids.map { hexToBytes(it).reversedArray() },
            if (pos >= 0) pos else 0
        )

        JSONObject().apply {
            put("block_height", height)
            put("merkle", JSONArray(branch.map { bytesToHex(it.reversedArray()) }))
            put("pos", if (pos >= 0) pos else 0)
        }
    }

    fun transactionIdFromPos(height: Int, txPos: Int, wantMerkle: Boolean): Any = runBlocking {
        val hash = getBlockHash(height)
        val block = rpc.call("getblock", JSONArray().apply { put(hash); put(1) })
        val txArray = block?.optJSONArray("tx") ?: JSONArray()

        val txid = if (txPos < txArray.length()) txArray.getString(txPos) else ""

        if (wantMerkle) {
            val txids = mutableListOf<String>()
            for (i in 0 until txArray.length()) txids.add(txArray.getString(i))

            val (branch, _) = createMerkleBranchAndRoot(
                txids.map { hexToBytes(it).reversedArray() },
                txPos
            )

            JSONObject().apply {
                put("tx_hash", txid)
                put("merkle", JSONArray(branch.map { bytesToHex(it.reversedArray()) }))
            }
        } else {
            txid
        }
    }

    // -- Fee methods --

    fun estimateFee(blocks: Int): Double = runBlocking {
        val result = rpc.call("estimatesmartfee", JSONArray().apply { put(blocks) })
        val feeRate = result?.optDouble("feerate", -1.0) ?: -1.0
        feeRate  // Already in BTC/kB, which is what Electrum expects
    }

    fun relayFee(): Double = runBlocking {
        val result = rpc.call("getnetworkinfo")
        result?.optDouble("relayfee", 0.00001) ?: 0.00001
    }

    fun mempoolFeeHistogram(): JSONArray = runBlocking {
        // Build fee histogram from mempool
        // Format: [[fee_rate, vsize], ...] — fee_rate in sat/vbyte, descending
        try {
            val result = rpc.call("getrawmempool", JSONArray().apply { put(true) })
            if (result == null) return@runBlocking JSONArray()

            // Collect fee rates and sizes
            data class MempoolTx(val feeRate: Double, val vsize: Int)
            val txs = mutableListOf<MempoolTx>()

            val keys = result.keys()
            while (keys.hasNext()) {
                val txid = keys.next()
                if (txid == "_rpc_error" || txid == "value") continue
                val tx = result.optJSONObject(txid) ?: continue
                val fees = tx.optJSONObject("fees")
                val baseFee = fees?.optDouble("base", 0.0) ?: tx.optDouble("fee", 0.0)
                val vsize = tx.optInt("vsize", tx.optInt("size", 1))
                if (vsize > 0) {
                    val feeRate = (baseFee * 1e8) / vsize  // sat/vbyte
                    txs.add(MempoolTx(feeRate, vsize))
                }
            }

            // Sort by fee rate descending and bucket
            txs.sortByDescending { it.feeRate }

            // Create histogram buckets
            val buckets = listOf(1000.0, 500.0, 200.0, 100.0, 50.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.0)
            val histogram = JSONArray()
            for (bucket in buckets) {
                val totalVsize = txs.filter { it.feeRate >= bucket }.sumOf { it.vsize }
                if (totalVsize > 0) {
                    histogram.put(JSONArray().apply {
                        put(bucket)
                        put(totalVsize)
                    })
                }
            }
            histogram
        } catch (e: Exception) {
            Log.w(TAG, "Fee histogram failed: ${e.message}")
            JSONArray()
        }
    }

    // -- Internal helpers --

    private suspend fun getBlockHash(height: Int): String {
        val result = rpc.call("getblockhash", JSONArray().apply { put(height) })
        return result?.optString("value", "") ?: ""
    }

    private suspend fun getHeaderHex(blockHash: String): String {
        val result = rpc.call("getblockheader", JSONArray().apply {
            put(blockHash)
            put(false)  // Return hex, not JSON
        })
        return result?.optString("value", "") ?: ""
    }

    private suspend fun getTipHeight(): Int {
        val info = rpc.call("getblockchaininfo")
        return info?.optInt("blocks", 0) ?: 0
    }

    /**
     * Compute merkle branch and root from a list of hashes.
     * Used for block header checkpoint proofs and tx merkle proofs.
     */
    private fun createMerkleBranchAndRoot(
        hashes: List<ByteArray>,
        index: Int
    ): Pair<List<ByteArray>, ByteArray> {
        var workingHashes = hashes.toMutableList()
        var workingIndex = index
        val branch = mutableListOf<ByteArray>()

        while (workingHashes.size > 1) {
            if (workingHashes.size % 2 != 0) {
                workingHashes.add(workingHashes.last().copyOf())
            }
            val pairIndex = if (workingIndex % 2 == 0) workingIndex + 1 else workingIndex - 1
            if (pairIndex < workingHashes.size) {
                branch.add(workingHashes[pairIndex])
            }
            workingIndex /= 2
            workingHashes = workingHashes.chunked(2).map { pair ->
                doubleSha256(pair[0] + pair[1])
            }.toMutableList()
        }

        return Pair(branch, if (workingHashes.isNotEmpty()) workingHashes[0] else ByteArray(32))
    }

    /**
     * Header merkle proof for checkpoint validation.
     */
    private suspend fun headerMerkleProof(height: Int, cpHeight: Int): Pair<List<String>, String> {
        val headerHashes = mutableListOf<ByteArray>()
        for (h in 0..cpHeight) {
            val hash = getBlockHash(h)
            headerHashes.add(hexToBytes(hash).reversedArray())
        }

        val (branch, root) = createMerkleBranchAndRoot(headerHashes, height)
        return Pair(
            branch.map { bytesToHex(it.reversedArray()) },
            bytesToHex(root.reversedArray())
        )
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
