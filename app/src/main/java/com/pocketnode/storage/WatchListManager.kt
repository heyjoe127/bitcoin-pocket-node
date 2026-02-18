package com.pocketnode.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager for persisting transaction watch list using SharedPreferences
 */
class WatchListManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "mempool_watch_list"
        private const val KEY_WATCHED_TRANSACTIONS = "watched_txs"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addTransaction(txid: String) {
        val watchedTxs = getWatchedTransactions().toMutableList()
        if (watchedTxs.none { it.txid == txid }) {
            watchedTxs.add(WatchedTransaction(txid, System.currentTimeMillis()))
            saveWatchedTransactions(watchedTxs)
        }
    }

    fun removeTransaction(txid: String) {
        val watchedTxs = getWatchedTransactions().filterNot { it.txid == txid }
        saveWatchedTransactions(watchedTxs)
    }

    fun isWatched(txid: String): Boolean = getWatchedTransactions().any { it.txid == txid }

    fun getWatchedTransactions(): List<WatchedTransaction> {
        val jsonString = prefs.getString(KEY_WATCHED_TRANSACTIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonString)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                WatchedTransaction(obj.getString("txid"), obj.getLong("addedTimestamp"))
            }
        } catch (e: Exception) {
            prefs.edit().remove(KEY_WATCHED_TRANSACTIONS).apply()
            emptyList()
        }
    }

    fun getWatchedTransactionIds(): List<String> = getWatchedTransactions().map { it.txid }

    fun clearAll() { prefs.edit().clear().apply() }

    private fun saveWatchedTransactions(watchedTxs: List<WatchedTransaction>) {
        val array = JSONArray()
        watchedTxs.forEach { tx ->
            array.put(JSONObject().apply {
                put("txid", tx.txid)
                put("addedTimestamp", tx.addedTimestamp)
            })
        }
        prefs.edit().putString(KEY_WATCHED_TRANSACTIONS, array.toString()).apply()
    }
}

data class WatchedTransaction(val txid: String, val addedTimestamp: Long)
