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

    /**
     * Convert 32-byte entropy to a 24-word BIP39 mnemonic.
     *
     * BIP39 for 256 bits:
     * - Compute SHA256 of entropy
     * - Append first 8 bits of hash as checksum
     * - Split 264 bits into 24 groups of 11 bits
     * - Each 11-bit value indexes the wordlist
     */
    fun entropyToMnemonic(entropy: ByteArray, context: Context): List<String> {
        require(entropy.size == 32) { "Entropy must be 32 bytes for 24-word mnemonic" }

        val words = loadWordList(context)

        // SHA256 checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumByte = hash[0] // First 8 bits for 256-bit entropy

        // Build bit string: 256 bits of entropy + 8 bits of checksum = 264 bits
        val bits = BooleanArray(264)
        for (i in 0 until 256) {
            bits[i] = (entropy[i / 8].toInt() and (1 shl (7 - i % 8))) != 0
        }
        for (i in 0 until 8) {
            bits[256 + i] = (checksumByte.toInt() and (1 shl (7 - i))) != 0
        }

        // Split into 24 groups of 11 bits
        val mnemonic = mutableListOf<String>()
        for (i in 0 until 24) {
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
     * Convert a 24-word BIP39 mnemonic back to 32-byte entropy.
     * Validates the checksum.
     *
     * @throws IllegalArgumentException if words are invalid or checksum fails
     */
    fun mnemonicToEntropy(mnemonic: List<String>, context: Context): ByteArray {
        require(mnemonic.size == 24) { "Mnemonic must be 24 words" }

        val words = loadWordList(context)
        val wordMap = words.withIndex().associate { (i, w) -> w.lowercase() to i }

        // Convert words to 11-bit indices
        val indices = mnemonic.map { word ->
            wordMap[word.lowercase().trim()]
                ?: throw IllegalArgumentException("Unknown BIP39 word: '$word'")
        }

        // Reconstruct 264 bits
        val bits = BooleanArray(264)
        for (i in indices.indices) {
            val idx = indices[i]
            for (j in 0 until 11) {
                bits[i * 11 + j] = (idx and (1 shl (10 - j))) != 0
            }
        }

        // Extract 256 bits of entropy
        val entropy = ByteArray(32)
        for (i in 0 until 256) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }

        // Extract 8-bit checksum
        var checksum = 0
        for (i in 0 until 8) {
            if (bits[256 + i]) {
                checksum = checksum or (1 shl (7 - i))
            }
        }

        // Verify checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = hash[0].toInt() and 0xFF
        require(checksum == expectedChecksum) {
            "Invalid checksum: mnemonic may be incorrect"
        }

        return entropy
    }

    /**
     * Validate a mnemonic without converting to entropy.
     * Returns null if valid, or an error message if invalid.
     */
    fun validate(mnemonic: List<String>, context: Context): String? {
        if (mnemonic.size != 24) return "Mnemonic must be 24 words (got ${mnemonic.size})"
        return try {
            mnemonicToEntropy(mnemonic, context)
            null // Valid
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }
}
