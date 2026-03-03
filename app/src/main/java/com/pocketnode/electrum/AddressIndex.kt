package com.pocketnode.electrum

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Tracks addresses/xpubs/descriptors via bitcoind's descriptor wallet.
 *
 * Uses scantxoutset for balance/UTXOs (reads chainstate directly, works on pruned nodes)
 * and descriptor wallet for unconfirmed transactions and history.
 * Discovered transactions are persisted to survive block pruning.
 *
 * Scripthash = SHA256(scriptPubKey) reversed (Electrum convention).
 */
class AddressIndex(private val rpc: BitcoinRpcClient, private val context: Context) {

    companion object {
        private const val TAG = "AddressIndex"
        const val WALLET_NAME = "pocketnode_electrum"
    }

    // Map scripthash -> list of known addresses for that scripthash
    // Populated when descriptors are imported
    private val scripthashToAddress = mutableMapOf<String, MutableSet<String>>()
    private val addressToScripthash = mutableMapOf<String, String>()
    private val rawDescriptors = mutableListOf<String>()  // for scantxoutset

    // Persistent transaction history: survives block pruning
    private val txHistoryFile = File(context.filesDir, "electrum_tx_history.json")
    // scripthash -> set of "txid:height"
    private val persistedHistory = mutableMapOf<String, MutableSet<String>>()

    init {
        loadPersistedHistory()
    }

    private fun loadPersistedHistory() {
        try {
            if (txHistoryFile.exists()) {
                val json = JSONObject(txHistoryFile.readText())
                for (key in json.keys()) {
                    val arr = json.getJSONArray(key)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    persistedHistory[key] = set
                }
                Log.i(TAG, "Loaded persisted tx history: ${persistedHistory.size} scripthashes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tx history: ${e.message}")
        }
    }

    private fun savePersistedHistory() {
        try {
            val json = JSONObject()
            for ((key, set) in persistedHistory) {
                json.put(key, JSONArray(set.toList()))
            }
            txHistoryFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tx history: ${e.message}")
        }
    }

    private fun persistTx(scripthash: String, txid: String, height: Int) {
        val set = persistedHistory.getOrPut(scripthash) { mutableSetOf() }
        val entry = "$txid:$height"
        if (set.add(entry)) {
            // Also update height if we had it at 0 (unconfirmed -> confirmed)
            val unconfirmed = "$txid:0"
            if (height > 0 && set.contains(unconfirmed)) {
                set.remove(unconfirmed)
            }
            savePersistedHistory()
        }
    }

    /**
     * Ensure the tracking wallet exists and load it.
     */
    suspend fun ensureWallet(): Boolean {
        // Try to load existing wallet first
        val loadResult = rpc.call("loadwallet", JSONArray().apply { put(WALLET_NAME) })
        if (loadResult != null && loadResult.optBoolean("_rpc_error", false).not()) {
            Log.i(TAG, "Loaded existing wallet: $WALLET_NAME")
            return true
        }

        // Create a new descriptor wallet (watch-only, no private keys)
        val createResult = rpc.call("createwallet", JSONArray().apply {
            put(WALLET_NAME)      // wallet_name
            put(true)             // disable_private_keys
            put(true)             // blank
            put("")               // passphrase (empty)
            put(false)            // avoid_reuse
            put(true)             // descriptors (use descriptor wallet)
            put(true)             // load_on_startup
        })

        return if (createResult != null && !createResult.optBoolean("_rpc_error", false)) {
            Log.i(TAG, "Created descriptor wallet: $WALLET_NAME")
            true
        } else {
            val errMsg = createResult?.optString("message", "unknown") ?: "null response"
            // "already loaded" is fine
            if (errMsg.contains("already loaded") || errMsg.contains("already exists")) {
                Log.d(TAG, "Wallet already exists/loaded")
                true
            } else {
                Log.e(TAG, "Failed to create wallet: $errMsg")
                false
            }
        }
    }

    /**
     * Import descriptors for tracking. Derives addresses and builds scripthash index.
     * @param descriptors List of output descriptors (e.g., "wpkh(xpub.../0/star)")
     * @param xpubs List of bare xpubs (auto-wrapped in wpkh, sh(wpkh), pkh descriptors)
     */
    suspend fun importDescriptors(descriptors: List<String>, xpubs: List<String>) {
        val allDescriptors = mutableListOf<String>()
        allDescriptors.addAll(descriptors)

        // Auto-wrap xpubs in standard descriptor types
        for (xpub in xpubs) {
            val prefix = xpub.take(4)
            when {
                // zpub / xpub starting with certain prefixes -> native segwit
                prefix.startsWith("zpub") || prefix.startsWith("xpub") -> {
                    allDescriptors.add("wpkh($xpub/0/*)")  // receive
                    allDescriptors.add("wpkh($xpub/1/*)")  // change
                }
                // ypub -> wrapped segwit
                prefix.startsWith("ypub") -> {
                    allDescriptors.add("sh(wpkh($xpub/0/*))")
                    allDescriptors.add("sh(wpkh($xpub/1/*))")
                }
                else -> {
                    // Default to wpkh for unknown prefixes
                    allDescriptors.add("wpkh($xpub/0/*)")
                    allDescriptors.add("wpkh($xpub/1/*)")
                }
            }
        }

        if (allDescriptors.isEmpty()) {
            Log.w(TAG, "No descriptors to import")
            return
        }

        // Store raw descriptors for scantxoutset queries
        rawDescriptors.clear()
        rawDescriptors.addAll(allDescriptors)

        // Check which descriptors are already imported
        val existingDescs = mutableSetOf<String>()
        val listResult = walletRpc("listdescriptors", JSONArray())
        if (listResult != null) {
            val descsArray = listResult.optJSONArray("descriptors")
                ?: listResult.optJSONObject("value")?.optJSONArray("descriptors")
            if (descsArray != null) {
                for (i in 0 until descsArray.length()) {
                    val d = descsArray.getJSONObject(i).optString("desc", "")
                    // Strip checksum for comparison
                    existingDescs.add(d.substringBefore('#'))
                }
            }
        }

        // Only import new descriptors (with rescan), skip already-tracked ones
        val importArray = JSONArray()
        for (desc in allDescriptors) {
            val descWithChecksum = if (desc.contains('#')) desc else addChecksum(desc)
            val bare = desc.substringBefore('#')
            if (bare in existingDescs) {
                Log.d(TAG, "Descriptor already imported, skipping: $bare")
                continue
            }
            importArray.put(JSONObject().apply {
                put("desc", descWithChecksum ?: desc)
                put("timestamp", 0)  // rescan available blocks for existing transactions
                put("watchonly", true)
                put("active", true)
                put("range", JSONArray().apply { put(0); put(100) })
                if (desc.contains("/0/*")) put("internal", false)
                if (desc.contains("/1/*")) put("internal", true)
            })
        }

        if (importArray.length() > 0) {
            val result = walletRpc("importdescriptors", JSONArray().apply { put(importArray) })
            if (result != null) {
                Log.i(TAG, "Imported ${importArray.length()} new descriptors (${existingDescs.size} already tracked)")
            } else {
                Log.e(TAG, "Failed to import descriptors")
            }
        } else {
            Log.i(TAG, "All ${allDescriptors.size} descriptors already imported, skipping rescan")
        }

        // Build scripthash index from derived addresses
        rebuildIndex()
    }

    /**
     * Import individual addresses for tracking.
     */
    suspend fun importAddresses(addresses: List<String>) {
        if (addresses.isEmpty()) return

        val importArray = JSONArray()
        for (addr in addresses) {
            importArray.put(JSONObject().apply {
                put("desc", "addr($addr)")
                put("timestamp", 0)
                put("watchonly", true)
            })
        }

        walletRpc("importdescriptors", JSONArray().apply { put(importArray) })
        rebuildIndex()
    }

    /**
     * Rebuild the scripthash -> address mapping by querying the wallet.
     */
    suspend fun rebuildIndex() {
        scripthashToAddress.clear()
        addressToScripthash.clear()

        // Derive addresses from descriptors using deriveaddresses RPC
        // This generates the actual addresses BlueWallet will query
        val listResult = walletRpc("listdescriptors", JSONArray())
        if (listResult != null) {
            val descsArray = listResult.optJSONObject("value")?.optJSONArray("descriptors")
                ?: listResult.optJSONArray("descriptors")
            if (descsArray != null) {
                for (i in 0 until descsArray.length()) {
                    val descObj = descsArray.getJSONObject(i)
                    val desc = descObj.optString("desc", "")
                    if (desc.isEmpty()) continue

                    // Derive first 100 addresses from each descriptor (receive + change)
                    val deriveResult = rpc.call("deriveaddresses", JSONArray().apply {
                        put(desc)
                        put(JSONArray().apply { put(0); put(99) })  // range [0, 99]
                    })
                    if (deriveResult != null && !deriveResult.has("_rpc_error")) {
                        // Result could be in "value" or directly an array
                        val addrs = deriveResult.optJSONArray("value") ?: deriveResult.optJSONArray("result")
                        if (addrs != null) {
                            for (j in 0 until addrs.length()) {
                                val addr = addrs.optString(j, "")
                                if (addr.isNotEmpty()) indexAddress(addr)
                            }
                        }
                    }
                }
            }
        }

        // Also index any addresses from listreceivedbyaddress (catch-all)
        val result = walletRpc("listreceivedbyaddress", JSONArray().apply {
            put(0); put(true); put(true)
        })
        if (result != null) {
            val arr = result.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    indexAddress(entry.getString("address"))
                }
            }
        }

        Log.i(TAG, "Index built: ${addressToScripthash.size} addresses, ${scripthashToAddress.size} scripthashes")

        // Check initial recovery status, then recover missing history
        checkRecoveryStatus()
        recoverMissingHistory()
    }

    /**
     * Find addresses with UTXOs but incomplete history, and recover from mempool.space.
     * Only runs once per address (results are persisted).
     */
    data class RecoveryStatus(
        val totalAddresses: Int = 0,
        val recoveredAddresses: Int = 0,
        val totalTxFound: Int = 0,
        val isRecovering: Boolean = false,
        val isComplete: Boolean = false
    )

    private val _recoveryStatus = MutableStateFlow(RecoveryStatus())
    val recoveryStatus: StateFlow<RecoveryStatus> = _recoveryStatus

    /**
     * Check if all tracked addresses have been recovered.
     */
    fun checkRecoveryStatus() {
        val prefs = context.getSharedPreferences("electrum_recovery", Context.MODE_PRIVATE)
        val recovered = prefs.getStringSet("recovered", emptySet()) ?: emptySet()
        val total = addressToScripthash.keys.size
        val done = addressToScripthash.keys.count { it in recovered }
        val txCount = persistedHistory.values.sumOf { it.size }
        _recoveryStatus.value = RecoveryStatus(
            totalAddresses = total,
            recoveredAddresses = done,
            totalTxFound = txCount,
            isComplete = total > 0 && done >= total
        )
    }

    /**
     * Recover missing history from mempool.space.
     * Called automatically on startup for new addresses, or manually via UI.
     * @param force If true, re-query all addresses (not just new ones)
     */
    suspend fun recoverMissingHistory(force: Boolean = false) {
        val prefs = context.getSharedPreferences("electrum_recovery", Context.MODE_PRIVATE)
        val recoveredAddresses = if (force) {
            mutableSetOf()  // re-query everything
        } else {
            prefs.getStringSet("recovered", emptySet())?.toMutableSet() ?: mutableSetOf()
        }

        // Find addresses that need recovery
        val needsRecovery = mutableSetOf<String>()
        for (addr in addressToScripthash.keys) {
            if (addr in recoveredAddresses) continue
            if (force) {
                needsRecovery.add(addr)
                continue
            }
            // Only auto-recover addresses with UTXOs
            val scanResult = rpc.call("scantxoutset", JSONArray().apply {
                put("start")
                put(JSONArray().apply { put("addr($addr)") })
            })
            if (scanResult != null && !scanResult.has("_rpc_error")) {
                val total = scanResult.optDouble("total_amount", 0.0)
                if (total > 0) {
                    needsRecovery.add(addr)
                }
            }
        }

        if (needsRecovery.isEmpty()) {
            checkRecoveryStatus()
            return
        }

        Log.i(TAG, "Recovering history for ${needsRecovery.size} addresses")
        _recoveryStatus.value = _recoveryStatus.value.copy(
            isRecovering = true,
            totalAddresses = addressToScripthash.keys.size
        )

        val knownCounts = mutableMapOf<String, Int>()
        for (addr in needsRecovery) {
            val sh = addressToScripthash[addr] ?: continue
            knownCounts[addr] = persistedHistory[sh]?.size ?: 0
        }

        val recovered = HistoryRecovery.recoverHistory(needsRecovery, knownCounts)

        var newTxCount = 0
        for ((addr, txs) in recovered) {
            val sh = addressToScripthash[addr] ?: continue
            for ((txid, height) in txs) {
                persistTx(sh, txid, height)
                newTxCount++
            }
            recoveredAddresses.add(addr)
        }

        // Also mark addresses that had no results as recovered (they're empty)
        for (addr in needsRecovery) {
            recoveredAddresses.add(addr)
        }

        prefs.edit().putStringSet("recovered", recoveredAddresses).apply()
        if (newTxCount > 0) {
            Log.i(TAG, "Recovered $newTxCount transactions from mempool.space")
        }

        _recoveryStatus.value = RecoveryStatus(
            totalAddresses = addressToScripthash.keys.size,
            recoveredAddresses = recoveredAddresses.size,
            totalTxFound = persistedHistory.values.sumOf { it.size },
            isRecovering = false,
            isComplete = true
        )
    }

    /**
     * Add an address to the scripthash index.
     */
    private suspend fun indexAddress(address: String) {
        if (addressToScripthash.containsKey(address)) return

        // Get the scriptPubKey for this address
        val validateResult = rpc.call("validateaddress", JSONArray().apply { put(address) })
        val scriptPubKey = validateResult?.optString("scriptPubKey") ?: return

        val scripthash = computeScripthash(scriptPubKey)
        addressToScripthash[address] = scripthash
        scripthashToAddress.getOrPut(scripthash) { mutableSetOf() }.add(address)
    }

    // -- Query methods (called by ElectrumMethods) --

    /**
     * Get confirmed and unconfirmed balance for a scripthash.
     */
    suspend fun getBalance(scripthash: String): Pair<Long, Long> {
        val addresses = scripthashToAddress[scripthash] ?: return Pair(0L, 0L)

        var confirmed = 0L
        var unconfirmed = 0L

        // Use scantxoutset for confirmed balance (reads chainstate directly, works on pruned nodes)
        for (addr in addresses) {
            val scanResult = rpc.call("scantxoutset", JSONArray().apply {
                put("start")
                put(JSONArray().apply { put("addr($addr)") })
            })
            if (scanResult != null && !scanResult.has("_rpc_error")) {
                val totalAmount = scanResult.optDouble("total_amount", 0.0)
                confirmed += btcToSats(totalAmount)
            }
        }

        // Check mempool for unconfirmed (descriptor wallet still needed for this)
        for (addr in addresses) {
            val result = walletRpc("listunspent", JSONArray().apply {
                put(0)  // minconf
                put(0)  // maxconf (unconfirmed only)
                put(JSONArray().apply { put(addr) })
            })
            val arr = result?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                unconfirmed += btcToSats(utxo.getDouble("amount"))
            }
        }

        return Pair(confirmed, unconfirmed)
    }

    /**
     * Get transaction history for a scripthash.
     * Returns list of (txid, height, fee?) entries.
     */
    suspend fun getHistory(scripthash: String): JSONArray {
        val addresses = scripthashToAddress[scripthash] ?: return JSONArray()
        val seenTxids = mutableMapOf<String, Int>()  // txid -> height

        // Source 1: Persisted history (survives pruning)
        val persisted = persistedHistory[scripthash]
        if (persisted != null) {
            for (entry in persisted) {
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val height = parts[1].toIntOrNull() ?: 0
                    val existing = seenTxids[txid]
                    // Prefer confirmed height over unconfirmed
                    if (existing == null || (height > 0 && existing == 0)) {
                        seenTxids[txid] = height
                    }
                }
            }
        }

        // Source 2: scantxoutset (current UTXOs from chainstate)
        for (addr in addresses) {
            val scanResult = rpc.call("scantxoutset", JSONArray().apply {
                put("start")
                put(JSONArray().apply { put("addr($addr)") })
            })
            if (scanResult != null && !scanResult.has("_rpc_error")) {
                val unspents = scanResult.optJSONArray("unspents")
                if (unspents != null) {
                    for (i in 0 until unspents.length()) {
                        val utxo = unspents.getJSONObject(i)
                        val txid = utxo.getString("txid")
                        val height = utxo.optInt("height", 0)
                        val existing = seenTxids[txid]
                        if (existing == null || (height > 0 && existing == 0)) {
                            seenTxids[txid] = height
                        }
                        // Persist this discovery
                        persistTx(scripthash, txid, height)
                    }
                }
            }
        }

        // Source 3: Descriptor wallet (catches mempool + recently confirmed)
        for (addr in addresses) {
            val txResult = walletRpc("listtransactions", JSONArray().apply {
                put("*")   // label (all)
                put(100)   // count
                put(0)     // skip
                put(true)  // include_watchonly
            })

            val arr = txResult?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i)
                val txid = tx.getString("txid")
                val txAddr = tx.optString("address", "")
                if (txAddr != addr) continue

                val confirmations = tx.optInt("confirmations", 0)
                val height = if (confirmations > 0) {
                    val blockHash = tx.optString("blockhash", "")
                    if (blockHash.isNotEmpty()) {
                        val blockInfo = rpc.call("getblockheader", JSONArray().apply { put(blockHash) })
                        blockInfo?.optInt("height", 0) ?: 0
                    } else 0
                } else 0

                val existing = seenTxids[txid]
                if (existing == null || (height > 0 && existing == 0)) {
                    seenTxids[txid] = height
                }
                // Persist confirmed transactions
                if (height > 0) persistTx(scripthash, txid, height)
            }
        }

        // Build result sorted by height
        val result = JSONArray()
        for ((txid, height) in seenTxids.entries.sortedBy { it.value }) {
            result.put(JSONObject().apply {
                put("tx_hash", txid)
                put("height", height)
            })
        }
        return result
    }

    /**
     * Get unspent outputs for a scripthash.
     */
    suspend fun listUnspent(scripthash: String): JSONArray {
        val addresses = scripthashToAddress[scripthash] ?: return JSONArray()
        val result = JSONArray()
        val seen = mutableSetOf<String>()  // "txid:vout" dedup

        for (addr in addresses) {
            // Use scantxoutset for confirmed UTXOs (reads chainstate directly)
            val scanResult = rpc.call("scantxoutset", JSONArray().apply {
                put("start")
                put(JSONArray().apply { put("addr($addr)") })
            })
            if (scanResult != null && !scanResult.has("_rpc_error")) {
                val unspents = scanResult.optJSONArray("unspents")
                if (unspents != null) {
                    for (i in 0 until unspents.length()) {
                        val utxo = unspents.getJSONObject(i)
                        val key = "${utxo.getString("txid")}:${utxo.getInt("vout")}"
                        if (key in seen) continue
                        seen.add(key)
                        result.put(JSONObject().apply {
                            put("tx_hash", utxo.getString("txid"))
                            put("tx_pos", utxo.getInt("vout"))
                            put("height", utxo.optInt("height", 0))
                            put("value", btcToSats(utxo.getDouble("amount")))
                        })
                    }
                }
            }

            // Also include unconfirmed from mempool via descriptor wallet
            val mempoolResult = walletRpc("listunspent", JSONArray().apply {
                put(0)  // minconf
                put(0)  // maxconf (unconfirmed only)
                put(JSONArray().apply { put(addr) })
            })
            val arr = mempoolResult?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                val key = "${utxo.getString("txid")}:${utxo.getInt("vout")}"
                if (key in seen) continue
                seen.add(key)
                result.put(JSONObject().apply {
                    put("tx_hash", utxo.getString("txid"))
                    put("tx_pos", utxo.getInt("vout"))
                    put("height", 0)
                    put("value", btcToSats(utxo.getDouble("amount")))
                })
            }
        }

        return result
    }

    /**
     * Get the status hash for a scripthash (SHA256 of "txid:height:" for each tx).
     * Returns null if no history exists.
     */
    suspend fun getStatusHash(scripthash: String): String? {
        val history = getHistory(scripthash)
        if (history.length() == 0) return null

        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            val status = "${entry.getString("tx_hash")}:${entry.getInt("height")}:"
            md.update(status.toByteArray())
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Get all tracked scripthashes and their current status hashes.
     * Used by SubscriptionManager to detect changes.
     */
    suspend fun getAllStatusHashes(): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        for (scripthash in scripthashToAddress.keys) {
            result[scripthash] = getStatusHash(scripthash)
        }
        return result
    }

    // -- Helper methods --

    /**
     * Make an RPC call to the tracking wallet specifically.
     */
    private suspend fun walletRpc(method: String, params: Any = JSONArray()): JSONObject? {
        // Use the /wallet/<name> endpoint
        // For now, use the default wallet (we load it on startup)
        // TODO: Use wallet-specific endpoint when BitcoinRpcClient supports it
        return rpc.call(method, params)
    }

    /**
     * Compute Electrum-style scripthash: SHA256(scriptPubKey bytes), reversed.
     */
    private fun computeScripthash(scriptPubKeyHex: String): String {
        val scriptBytes = scriptPubKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(scriptBytes)
        return hash.reversed().toByteArray().joinToString("") { "%02x".format(it) }
    }

    /**
     * Add checksum to a descriptor using getdescriptorinfo RPC.
     */
    private suspend fun addChecksum(descriptor: String): String? {
        val result = rpc.call("getdescriptorinfo", JSONArray().apply { put(descriptor) })
        return result?.optString("descriptor")
    }

    private fun btcToSats(btc: Double): Long = (btc * 100_000_000L).toLong()
}
