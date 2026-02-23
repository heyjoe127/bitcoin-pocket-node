package com.pocketnode.electrum

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Tracks addresses/xpubs/descriptors via bitcoind's descriptor wallet.
 *
 * Uses importdescriptors to watch addresses, then queries the wallet
 * for history, balance, and UTXOs per scripthash.
 *
 * Scripthash = SHA256(scriptPubKey) reversed (Electrum convention).
 */
class AddressIndex(private val rpc: BitcoinRpcClient) {

    companion object {
        private const val TAG = "AddressIndex"
        const val WALLET_NAME = "pocketnode_electrum"
    }

    // Map scripthash -> list of known addresses for that scripthash
    // Populated when descriptors are imported
    private val scripthashToAddress = mutableMapOf<String, MutableSet<String>>()
    private val addressToScripthash = mutableMapOf<String, String>()

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

        // Build importdescriptors request
        val importArray = JSONArray()
        for (desc in allDescriptors) {
            // Add checksum if not present
            val descWithChecksum = if (desc.contains('#')) desc else addChecksum(desc)
            importArray.put(JSONObject().apply {
                put("desc", descWithChecksum ?: desc)
                put("timestamp", "now")  // don't rescan, start tracking from now
                put("watchonly", true)
                put("active", true)
                if (desc.contains("/0/*")) put("internal", false)
                if (desc.contains("/1/*")) put("internal", true)
            })
        }

        val result = walletRpc("importdescriptors", JSONArray().apply { put(importArray) })
        if (result != null) {
            Log.i(TAG, "Imported ${allDescriptors.size} descriptors")
        } else {
            Log.e(TAG, "Failed to import descriptors")
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
                put("timestamp", "now")
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

        // Get all addresses known to the wallet
        // Use listdescriptors to see what's tracked, then derive addresses
        val result = walletRpc("listreceivedbyaddress", JSONArray().apply {
            put(0)     // minconf
            put(true)  // include_empty
            put(true)  // include_watchonly
        })

        if (result != null) {
            val arr = result.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val address = entry.getString("address")
                    indexAddress(address)
                }
            }
        }

        // Also get addresses from getaddressesbylabel (catch-all)
        val labelResult = walletRpc("getaddressesbylabel", JSONArray().apply { put("") })
        if (labelResult != null) {
            val keys = labelResult.keys()
            while (keys.hasNext()) {
                val address = keys.next()
                if (address != "_rpc_error" && address != "value") {
                    indexAddress(address)
                }
            }
        }

        Log.i(TAG, "Index built: ${addressToScripthash.size} addresses, ${scripthashToAddress.size} scripthashes")
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

        for (addr in addresses) {
            val result = walletRpc("listunspent", JSONArray().apply {
                put(0)  // minconf
                put(9999999)  // maxconf
                put(JSONArray().apply { put(addr) })
            })

            val arr = result?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                val sats = btcToSats(utxo.getDouble("amount"))
                val confirmations = utxo.optInt("confirmations", 0)
                if (confirmations > 0) confirmed += sats else unconfirmed += sats
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
        val result = JSONArray()
        val seenTxids = mutableSetOf<String>()

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
                if (txid in seenTxids) continue

                // Check if this tx involves our address
                val txAddr = tx.optString("address", "")
                if (txAddr != addr) continue

                seenTxids.add(txid)
                val confirmations = tx.optInt("confirmations", 0)
                val height = if (confirmations > 0) {
                    // Get actual block height
                    val blockHash = tx.optString("blockhash", "")
                    if (blockHash.isNotEmpty()) {
                        val blockInfo = rpc.call("getblockheader", JSONArray().apply { put(blockHash) })
                        blockInfo?.optInt("height", 0) ?: 0
                    } else 0
                } else 0

                result.put(JSONObject().apply {
                    put("tx_hash", txid)
                    put("height", if (confirmations > 0) height else 0)
                    if (confirmations == 0) {
                        val fee = tx.optDouble("fee", 0.0)
                        if (fee != 0.0) put("fee", btcToSats(Math.abs(fee)))
                    }
                })
            }
        }

        return result
    }

    /**
     * Get unspent outputs for a scripthash.
     */
    suspend fun listUnspent(scripthash: String): JSONArray {
        val addresses = scripthashToAddress[scripthash] ?: return JSONArray()
        val result = JSONArray()

        for (addr in addresses) {
            val utxoResult = walletRpc("listunspent", JSONArray().apply {
                put(0)
                put(9999999)
                put(JSONArray().apply { put(addr) })
            })

            val arr = utxoResult?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                val confirmations = utxo.optInt("confirmations", 0)

                result.put(JSONObject().apply {
                    put("tx_hash", utxo.getString("txid"))
                    put("tx_pos", utxo.getInt("vout"))
                    put("height", if (confirmations > 0) {
                        // Calculate height from confirmations
                        val tipResult = rpc.call("getblockchaininfo")
                        val tipHeight = tipResult?.optInt("blocks", 0) ?: 0
                        tipHeight - confirmations + 1
                    } else 0)
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
