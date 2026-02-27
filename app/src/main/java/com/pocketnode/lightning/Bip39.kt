package com.pocketnode.lightning

import android.content.Context
import com.pocketnode.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * BIP39 mnemonic encoding/decoding for Lightning wallet seed backup.
 *
 * Supports 256-bit entropy (32 bytes) -> 24-word mnemonic.
 * Uses the standard English wordlist from the BIP39 specification.
 */
object Bip39 {

    private var wordList: List<String>? = null

    /**
     * Load the BIP39 English wordlist from resources.
     * Must be called with a Context before encode/decode.
     */
    fun loadWordList(context: Context): List<String> {
        wordList?.let { return it }
        val words = mutableListOf<String>()
        val input = context.resources.openRawResource(R.raw.bip39_english)
        BufferedReader(InputStreamReader(input)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) words.add(trimmed)
                line = reader.readLine()
            }
        }
        require(words.size == 2048) { "BIP39 wordlist must have 2048 words, got ${words.size}" }
        wordList = words
        return words
    }

    // Supported mnemonic sizes: word count -> (entropy bytes, checksum bits)
    private val VALID_SIZES = mapOf(
        12 to Pair(16, 4),    // 128-bit entropy
        15 to Pair(20, 5),    // 160-bit
        18 to Pair(24, 6),    // 192-bit
        21 to Pair(28, 7),    // 224-bit
        24 to Pair(32, 8)     // 256-bit
    )

    /**
     * Convert entropy bytes to a BIP39 mnemonic.
     * Supports 16 bytes (12 words) through 32 bytes (24 words).
     *
     * BIP39: entropy + SHA256 checksum bits → split into 11-bit groups → word indices.
     */
    fun entropyToMnemonic(entropy: ByteArray, context: Context): List<String> {
        val wordCount = when (entropy.size) {
            16 -> 12; 20 -> 15; 24 -> 18; 28 -> 21; 32 -> 24
            else -> throw IllegalArgumentException(
                "Entropy must be 16-32 bytes (got ${entropy.size})")
        }
        val checksumBits = entropy.size / 4 // CS = ENT / 32, in bits = ENT_bytes / 4

        val words = loadWordList(context)

        // SHA256 checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)

        // Build bit string: entropy bits + checksum bits
        val totalBits = entropy.size * 8 + checksumBits
        val bits = BooleanArray(totalBits)
        for (i in 0 until entropy.size * 8) {
            bits[i] = (entropy[i / 8].toInt() and (1 shl (7 - i % 8))) != 0
        }
        for (i in 0 until checksumBits) {
            bits[entropy.size * 8 + i] = (hash[i / 8].toInt() and (1 shl (7 - i % 8))) != 0
        }

        // Split into 11-bit groups
        val mnemonic = mutableListOf<String>()
        for (i in 0 until wordCount) {
            var index = 0
            for (j in 0 until 11) {
                if (bits[i * 11 + j]) {
                    index = index or (1 shl (10 - j))
                }
            }
            mnemonic.add(words[index])
        }

        return mnemonic
    }

    /**
     * Convert a BIP39 mnemonic back to entropy bytes.
     * Supports 12, 15, 18, 21, or 24 words.
     * Validates the checksum.
     *
     * For 12-word mnemonics (128-bit), the returned 16 bytes are expanded
     * to 32 bytes via SHA256 for ldk-node compatibility (which expects 32-byte keys_seed).
     *
     * @throws IllegalArgumentException if words are invalid or checksum fails
     */
    fun mnemonicToEntropy(mnemonic: List<String>, context: Context): ByteArray {
        val size = VALID_SIZES[mnemonic.size]
            ?: throw IllegalArgumentException(
                "Mnemonic must be 12, 15, 18, 21, or 24 words (got ${mnemonic.size})")

        val (entropyBytes, checksumBits) = size

        val words = loadWordList(context)
        val wordMap = words.withIndex().associate { (i, w) -> w.lowercase() to i }

        // Convert words to 11-bit indices
        val indices = mnemonic.map { word ->
            wordMap[word.lowercase().trim()]
                ?: throw IllegalArgumentException("Unknown BIP39 word: '${word.trim()}'")
        }

        // Reconstruct bits
        val totalBits = mnemonic.size * 11
        val bits = BooleanArray(totalBits)
        for (i in indices.indices) {
            val idx = indices[i]
            for (j in 0 until 11) {
                bits[i * 11 + j] = (idx and (1 shl (10 - j))) != 0
            }
        }

        // Extract entropy
        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBytes * 8) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }

        // Extract and verify checksum
        var checksum = 0
        for (i in 0 until checksumBits) {
            if (bits[entropyBytes * 8 + i]) {
                checksum = checksum or (1 shl (checksumBits - 1 - i))
            }
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = (hash[0].toInt() and 0xFF) shr (8 - checksumBits)
        require(checksum == expectedChecksum) {
            "Invalid checksum: mnemonic may be incorrect"
        }

        // If entropy is shorter than 32 bytes, expand via SHA256 for ldk-node compatibility
        return if (entropyBytes < 32) {
            MessageDigest.getInstance("SHA-256").digest(entropy)
        } else {
            entropy
        }
    }

    /**
     * Validate a mnemonic without converting to entropy.
     * Returns null if valid, or an error message if invalid.
     */
    fun validate(mnemonic: List<String>, context: Context): String? {
        if (mnemonic.size !in VALID_SIZES) {
            return "Mnemonic must be 12, 15, 18, 21, or 24 words (got ${mnemonic.size})"
        }
        return try {
            mnemonicToEntropy(mnemonic, context)
            null // Valid
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }
}
