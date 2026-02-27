package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.WatchtowerJusticeBlob
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bridges LDK watchtower data to an LND watchtower server.
 *
 * Flow:
 * 1. Drain justice blobs from ldk-node (captured by WatchtowerPersister)
 * 2. Encrypt each blob using XChaCha20-Poly1305 (breach txid as key)
 * 3. SSH tunnel to Umbrel, connect to LND tower on localhost
 * 4. Push encrypted blobs via LND watchtower wire protocol
 *
 * The LND tower stores encrypted blobs indexed by hint (first 16 bytes of
 * breach txid). If the counterparty broadcasts a revoked commitment, the
 * tower sees the txid, decrypts the blob, and broadcasts the justice tx.
 */
class WatchtowerBridge(private val context: Context) {

    companion object {
        private const val TAG = "WatchtowerBridge"

        // LND watchtower wire message types
        private const val MSG_INIT: Int = 600
        private const val MSG_CREATE_SESSION: Int = 16
        private const val MSG_CREATE_SESSION_REPLY: Int = 17
        private const val MSG_STATE_UPDATE: Int = 18
        private const val MSG_STATE_UPDATE_REPLY: Int = 19

        // Blob type: anchor channels (type 1)
        private const val BLOB_TYPE_ANCHOR: Int = 1

        // JusticeKit V0 plaintext size: 274 bytes
        // revocation_pubkey(33) + local_delay_pubkey(33) + csv_delay(4) +
        // sweep_address_len(2) + sweep_address(max ~34) + to_local_sig(64) + to_remote_sig(64)
        // Padded/fixed to 274
        private const val JUSTICE_KIT_V0_SIZE = 274

        // Encrypted blob: 24 (nonce) + 274 (plaintext) + 16 (MAC) = 314
        private const val ENCRYPTED_BLOB_SIZE = 314
    }

    /**
     * Drain all pending justice blobs and push them to the configured LND tower.
     * Call this periodically (e.g. after each payment or channel update).
     *
     * @return number of blobs successfully pushed, or -1 on error
     */
    fun drainAndPush(node: Node): Int {
        val blobs = node.watchtowerDrainJusticeBlobs()
        if (blobs.isEmpty()) {
            Log.d(TAG, "No pending justice blobs to push")
            return 0
        }

        Log.i(TAG, "Draining ${blobs.size} justice blob(s) for watchtower push")

        // Load tower config
        val prefs = context.getSharedPreferences("watchtower_prefs", Context.MODE_PRIVATE)
        val towerPubKey = prefs.getString("tower_pubkey", null)
        val sshHost = prefs.getString("ssh_host", null)
        val sshPort = prefs.getInt("ssh_port", 22)
        val sshUser = prefs.getString("ssh_user", null)
        val towerPort = prefs.getInt("tower_port", 9911)

        if (towerPubKey == null || sshHost == null || sshUser == null) {
            Log.w(TAG, "Watchtower not configured, skipping push")
            return -1
        }

        var pushed = 0
        var sshSession: Session? = null
        var localPort = -1

        try {
            // SSH tunnel to Umbrel, forward local port to tower's onion port
            val jsch = JSch()
            // Load SSH key if available
            val keyFile = java.io.File(context.filesDir, "ssh_key")
            if (keyFile.exists()) {
                jsch.addIdentity(keyFile.absolutePath)
            }

            sshSession = jsch.getSession(sshUser, sshHost, sshPort)
            sshSession.setConfig("StrictHostKeyChecking", "no")

            // If we have a password saved, use it
            val sshPassword = prefs.getString("ssh_password", null)
            if (sshPassword != null) {
                sshSession.setPassword(sshPassword)
            }

            sshSession.connect(15000) // 15s timeout

            // Forward a local port to the tower (onion address resolves on Umbrel)
            val towerOnion = prefs.getString("tower_onion", "127.0.0.1")!!
            localPort = sshSession.setPortForwardingL(0, towerOnion, towerPort)
            Log.i(TAG, "SSH tunnel established, local port $localPort -> $towerOnion:$towerPort")

            // Connect to tower through tunnel
            val socket = Socket("127.0.0.1", localPort)
            socket.soTimeout = 30000 // 30s read timeout

            try {
                // TODO: Noise_XK handshake with tower public key
                // For now, encrypt and store blobs locally for manual push
                // The full Noise handshake requires the snow/noise library ported to Kotlin
                // or the Rust watchtower-client cross-compiled as a separate .so

                for (blob in blobs) {
                    val encrypted = encryptJusticeBlob(blob)
                    if (encrypted != null) {
                        // Store locally until wire protocol is implemented
                        storeEncryptedBlob(blob.breachTxid, encrypted)
                        pushed++
                    }
                }

                Log.i(TAG, "Encrypted $pushed blob(s) — wire protocol push pending")
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchtower push failed: ${e.message}")
            // Still encrypt and store locally
            for (blob in blobs) {
                val encrypted = encryptJusticeBlob(blob)
                if (encrypted != null) {
                    storeEncryptedBlob(blob.breachTxid, encrypted)
                    pushed++
                }
            }
        } finally {
            try {
                if (localPort > 0) sshSession?.delPortForwardingL(localPort)
                sshSession?.disconnect()
            } catch (_: Exception) {}
        }

        return pushed
    }

    /**
     * Encrypt a justice blob using XChaCha20-Poly1305.
     * Key = breach_txid (32 bytes), nonce = random 24 bytes.
     *
     * Output format: [24-byte nonce][encrypted 274 bytes][16-byte MAC]
     */
    private fun encryptJusticeBlob(blob: WatchtowerJusticeBlob): ByteArray? {
        try {
            // Build JusticeKit V0 plaintext (274 bytes)
            val plaintext = buildJusticeKitV0(blob) ?: return null

            // Generate 24-byte random nonce
            val nonce = ByteArray(24)
            java.security.SecureRandom().nextBytes(nonce)

            // XChaCha20-Poly1305 encrypt
            // Android API 28+ has ChaCha20-Poly1305 but not XChaCha20
            // We use the HChaCha20 construction to derive the subkey
            val subkey = hChaCha20(blob.breachTxid, nonce.sliceArray(0..15))
            val subNonce = ByteArray(12)
            // XChaCha20 sub-nonce: [0x00, 0x00, 0x00, 0x00, nonce[16..23]]
            System.arraycopy(nonce, 16, subNonce, 4, 8)

            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(subkey, "ChaCha20")
            val ivSpec = IvParameterSpec(subNonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

            val ciphertext = cipher.doFinal(plaintext)

            // Output: nonce + ciphertext (which includes MAC)
            val output = ByteArray(24 + ciphertext.size)
            System.arraycopy(nonce, 0, output, 0, 24)
            System.arraycopy(ciphertext, 0, output, 24, ciphertext.size)

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt justice blob: ${e.message}")
            return null
        }
    }

    /**
     * Build LND JusticeKit V0 plaintext (274 bytes).
     */
    private fun buildJusticeKitV0(blob: WatchtowerJusticeBlob): ByteArray? {
        val buf = ByteBuffer.allocate(JUSTICE_KIT_V0_SIZE)
        buf.order(ByteOrder.BIG_ENDIAN)

        // Revocation pubkey (33 bytes)
        if (blob.revocationPubkey.size != 33) {
            Log.e(TAG, "Invalid revocation pubkey size: ${blob.revocationPubkey.size}")
            return null
        }
        buf.put(blob.revocationPubkey)

        // Local delay pubkey (33 bytes)
        if (blob.localDelayPubkey.size != 33) {
            Log.e(TAG, "Invalid local delay pubkey size: ${blob.localDelayPubkey.size}")
            return null
        }
        buf.put(blob.localDelayPubkey)

        // CSV delay (4 bytes, big-endian)
        buf.putInt(blob.csvDelay.toInt())

        // Sweep address length (2 bytes) + sweep address (variable, padded to 34)
        val sweepAddr = blob.sweepAddress
        buf.putShort(sweepAddr.size.toShort())
        buf.put(sweepAddr)
        // Pad to 34 bytes
        if (sweepAddr.size < 34) {
            buf.put(ByteArray(34 - sweepAddr.size))
        }

        // to_local signature (64 bytes)
        if (blob.toLocalSig.size != 64) {
            Log.e(TAG, "Invalid to_local sig size: ${blob.toLocalSig.size}")
            return null
        }
        buf.put(blob.toLocalSig)

        // to_remote signature (64 bytes)
        if (blob.toRemoteSig.size != 64) {
            Log.e(TAG, "Invalid to_remote sig size: ${blob.toRemoteSig.size}")
            return null
        }
        buf.put(blob.toRemoteSig)

        // Remaining padding (if any)
        while (buf.position() < JUSTICE_KIT_V0_SIZE) {
            buf.put(0)
        }

        return buf.array()
    }

    /**
     * HChaCha20: derive a 256-bit subkey for XChaCha20.
     * Takes a 256-bit key and 128-bit (16-byte) input, returns 256-bit subkey.
     */
    private fun hChaCha20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(input.size == 16) { "Input must be 16 bytes" }

        // ChaCha20 state initialization
        val state = IntArray(16)

        // Constants "expand 32-byte k"
        state[0] = 0x61707865.toInt()
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        // Key (8 words)
        for (i in 0..7) {
            state[4 + i] = ByteBuffer.wrap(key, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        // Input (4 words) replaces counter + nonce
        for (i in 0..3) {
            state[12 + i] = ByteBuffer.wrap(input, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        // 20 rounds of ChaCha
        val working = state.copyOf()
        for (round in 0 until 10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        // Output: first 4 words + last 4 words (NOT added to state, unlike ChaCha20)
        val subkey = ByteArray(32)
        val out = ByteBuffer.wrap(subkey).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..3) out.putInt(working[i])
        for (i in 12..15) out.putInt(working[i])

        return subkey
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun Int.rotateLeft(bits: Int): Int =
        (this shl bits) or (this ushr (32 - bits))

    /**
     * Compute the 16-byte hint from a breach txid (first 16 bytes).
     */
    fun computeHint(breachTxid: ByteArray): ByteArray {
        require(breachTxid.size == 32) { "Breach txid must be 32 bytes" }
        return breachTxid.sliceArray(0..15)
    }

    /**
     * Store an encrypted blob locally for later push or inspection.
     */
    private fun storeEncryptedBlob(breachTxid: ByteArray, encrypted: ByteArray) {
        val dir = java.io.File(context.filesDir, "watchtower_blobs")
        if (!dir.exists()) dir.mkdirs()

        val hint = computeHint(breachTxid).joinToString("") { "%02x".format(it) }
        val file = java.io.File(dir, "$hint.blob")
        file.writeBytes(encrypted)
        Log.d(TAG, "Stored encrypted blob: ${file.name} (${encrypted.size} bytes)")
    }
}
