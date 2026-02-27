package com.pocketnode.lightning

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightning node directory using mempool.space API.
 * Browse and search for Lightning peers to open channels with.
 * No API key required.
 */
object NodeDirectory {

    private const val TAG = "NodeDirectory"
    private const val BASE_URL = "https://mempool.space/api/v1/lightning"

    data class LightningNode(
        val publicKey: String,
        val alias: String,
        val channels: Int,
        val capacity: Long, // sats
        val sockets: String, // comma-separated ip:port addresses
        val country: String
    ) {
        /** First clearnet address, or first .onion, or empty */
        val address: String get() {
            val addrs = sockets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // Prefer clearnet
            return addrs.firstOrNull { !it.contains(".onion") }
                ?: addrs.firstOrNull()
                ?: ""
        }

        /** Capacity formatted as BTC */
        val capacityBtc: String get() = "%.4f BTC".format(capacity / 100_000_000.0)
    }

    /** Get top nodes by connectivity (most channels) */
    fun getTopNodes(limit: Int = 20): List<LightningNode> {
        return try {
            val json = fetch("$BASE_URL/nodes/rankings/connectivity")
            val arr = JSONArray(json)
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val obj = arr.getJSONObject(i)
                nodes.add(parseRankingNode(obj))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top nodes", e)
            emptyList()
        }
    }

    /** Get top nodes by capacity (largest) */
    fun getTopByCapacity(limit: Int = 20): List<LightningNode> {
        return try {
            val json = fetch("$BASE_URL/nodes/rankings/liquidity")
            val arr = JSONArray(json)
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val obj = arr.getJSONObject(i)
                nodes.add(parseRankingNode(obj))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top by capacity", e)
            emptyList()
        }
    }

    /** Search for a node by alias or public key */
    fun search(query: String): List<LightningNode> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = fetch("$BASE_URL/search?searchText=$encoded")
            val result = JSONObject(json)
            val nodesArr = result.optJSONArray("nodes") ?: return emptyList()
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until nodesArr.length()) {
                val obj = nodesArr.getJSONObject(i)
                // Search results don't have sockets, need to fetch details
                val pk = obj.getString("public_key")
                val alias = obj.optString("alias", "")
                val channels = obj.optInt("channels", 0)
                val capacity = obj.optLong("capacity", 0)
                nodes.add(LightningNode(pk, alias, channels, capacity, "", ""))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search nodes", e)
            emptyList()
        }
    }

    /** Get full node details including socket addresses */
    fun getNodeDetails(publicKey: String): LightningNode? {
        return try {
            val json = fetch("$BASE_URL/nodes/$publicKey")
            val obj = JSONObject(json)
            LightningNode(
                publicKey = obj.getString("public_key"),
                alias = obj.optString("alias", ""),
                channels = obj.optInt("active_channel_count", 0),
                capacity = obj.optLong("capacity", 0),
                sockets = obj.optString("sockets", ""),
                country = obj.optJSONObject("country")?.optString("en", "") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch node details", e)
            null
        }
    }

    private fun parseRankingNode(obj: JSONObject): LightningNode {
        return LightningNode(
            publicKey = obj.optString("publicKey", ""),
            alias = obj.optString("alias", ""),
            channels = obj.optInt("channels", 0),
            capacity = obj.optLong("capacity", 0),
            sockets = "", // Rankings don't include sockets
            country = obj.optJSONObject("country")?.optString("en", "") ?: ""
        )
    }

    private fun fetch(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "BitcoinPocketNode/0.7")
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
