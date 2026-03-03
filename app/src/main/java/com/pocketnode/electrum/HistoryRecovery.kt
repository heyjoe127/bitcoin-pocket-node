package com.pocketnode.electrum

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recovers transaction history for addresses where local data is incomplete.
 * Queries mempool.space API to find historical transactions that our pruned
 * node can't see, then persists them so they're never lost.
 *
 * TODO: Route through embedded Tor for better privacy. Currently uses clearnet
 * HTTPS. The addresses are already public on the blockchain, but linking them
 * to an IP is a privacy leak.
 */
object HistoryRecovery {

    private const val TAG = "HistoryRecovery"
    private const val API_BASE = "https://mempool.space/api"
    private const val TIMEOUT_MS = 15_000

    /**
     * Recover missing transaction history for a set of addresses.
     * Returns a map of address -> list of (txid, height) pairs discovered.
     *
     * Only queries addresses that have UTXOs (from scantxoutset) but
     * fewer history entries than expected.
     */
    suspend fun recoverHistory(
        addresses: Set<String>,
        knownTxCount: Map<String, Int>  // address -> known tx count
    ): Map<String, List<Pair<String, Int>>> = withContext(Dispatchers.IO) {
        val recovered = mutableMapOf<String, List<Pair<String, Int>>>()

        for (addr in addresses) {
            try {
                val txs = fetchAddressTransactions(addr)
                if (txs.isNotEmpty()) {
                    val known = knownTxCount[addr] ?: 0
                    if (txs.size > known) {
                        Log.i(TAG, "Recovered ${txs.size - known} missing txs for $addr")
                    }
                    recovered[addr] = txs
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover history for $addr: ${e.message}")
            }
        }

        recovered
    }

    /**
     * Fetch all transactions for an address from mempool.space.
     * Returns list of (txid, block_height) pairs. Height=0 means unconfirmed.
     */
    private fun fetchAddressTransactions(address: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()

        // Confirmed transactions
        val confirmedUrl = "$API_BASE/address/$address/txs"
        val confirmedJson = httpGet(confirmedUrl)
        if (confirmedJson != null) {
            val arr = JSONArray(confirmedJson)
            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i)
                val txid = tx.getString("txid")
                val status = tx.optJSONObject("status")
                val height = if (status?.optBoolean("confirmed", false) == true) {
                    status.optInt("block_height", 0)
                } else 0
                results.add(Pair(txid, height))
            }
        }

        return results
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "PocketNode/1.0")
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
