package com.pocketnode.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages Bitcoin implementation binaries.
 *
 * Supports multiple Bitcoin Core/Knots versions bundled as native libraries.
 * User selects which version to run; the selected binary is used by BitcoindService.
 *
 * Binaries are packaged as lib*.so in jniLibs/arm64-v8a/. This naming is required
 * because Android only extracts files matching lib*.so from the APK to nativeLibraryDir.
 * That directory has the execute permission needed to run binaries -- other app directories
 * (filesDir, cacheDir) are mounted noexec on modern Android and GrapheneOS.
 */
object BinaryExtractor {

    private const val TAG = "BinaryExtractor"
    private const val PREFS_NAME = "pocketnode_prefs"
    private const val KEY_BITCOIN_VERSION = "bitcoin_version"
    private const val KEY_SIGNAL_BIP110 = "signal_bip110"

    /**
     * Available Bitcoin implementations.
     *
     * Both include BIP 110 consensus code (always compiled in, BIP9-gated).
     * BIP 110 signaling is controlled separately via the signalbip110 toggle
     * and works with either implementation.
     */
    enum class BitcoinVersion(
        val libraryName: String,
        val displayName: String,
        val versionString: String,
        val description: String,
        val policyStance: String,
        val supportsBip110: Boolean
    ) {
        CORE(
            "libbitcoind_core.so",
            "Bitcoin Core",
            "29.3",
            "Reference implementation with BIP 110 consensus code. Standard relay rules.",
            "Standard -- default relay policy",
            true
        ),
        CORE_30(
            "libbitcoind_v30.so",
            "Bitcoin Core",
            "30.0",
            "Latest release. Relaxed OP_RETURN data size limits. No BIP 110 support.",
            "Permissive -- larger OP_RETURN data allowed",
            false
        ),
        KNOTS(
            "libbitcoind_knots.so",
            "Bitcoin Knots",
            "29.3",
            "Alternative implementation by Luke Dashjr. Stricter transaction filtering and relay policy. Includes BIP 110 consensus code.",
            "Restrictive -- filters non-standard transactions",
            true
        );

        companion object {
            val DEFAULT = CORE

            fun fromName(name: String): BitcoinVersion {
                return try {
                    // Handle legacy selections
                    when (name) {
                        "KNOTS_BIP110" -> KNOTS
                        "CORE_28_1" -> CORE
                        else -> valueOf(name)
                    }
                } catch (_: IllegalArgumentException) {
                    DEFAULT
                }
            }
        }
    }

    /**
     * Check if a specific version's binary is bundled in this APK.
     */
    fun isAvailable(context: Context, version: BitcoinVersion): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, version.libraryName)
        return binary.exists() && binary.canExecute()
    }

    /**
     * Get all available (bundled) versions.
     */
    fun availableVersions(context: Context): List<BitcoinVersion> {
        return BitcoinVersion.entries.filter { isAvailable(context, it) }
    }

    /**
     * Get the user's selected Bitcoin version.
     */
    fun getSelectedVersion(context: Context): BitcoinVersion {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_BITCOIN_VERSION, null) ?: return BitcoinVersion.DEFAULT
        val version = BitcoinVersion.fromName(name)
        // Fall back to default if selected version isn't available
        return if (isAvailable(context, version)) version else BitcoinVersion.DEFAULT
    }

    /**
     * Set the user's selected Bitcoin version.
     */
    fun setSelectedVersion(context: Context, version: BitcoinVersion) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BITCOIN_VERSION, version.name)
            .apply()
        Log.i(TAG, "Selected Bitcoin version: ${version.displayName} ${version.versionString}")
    }

    /**
     * Get BIP 110 signaling preference.
     * Only effective on implementations that include BIP 110 consensus code (Core 29.3, Knots).
     * Returns false for implementations without BIP 110 support (Core 30).
     */
    fun isSignalBip110(context: Context): Boolean {
        val selected = getSelectedVersion(context)
        if (!selected.supportsBip110) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SIGNAL_BIP110, false)
    }

    /**
     * Get the raw BIP 110 toggle state (ignoring implementation support).
     * Used by UI to show the toggle state even when current implementation doesn't support it.
     */
    fun isSignalBip110Raw(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SIGNAL_BIP110, false)
    }

    /**
     * Set BIP 110 signaling preference.
     */
    fun setSignalBip110(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SIGNAL_BIP110, enabled)
            .apply()
        Log.i(TAG, "BIP 110 signaling: ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get the bitcoind binary for the currently selected version.
     * Falls back through: selected version -> default version -> legacy name.
     */
    fun extractIfNeeded(context: Context): File {
        val selected = getSelectedVersion(context)
        return getBinary(context, selected)
    }

    /**
     * Get the bitcoind binary for a specific version.
     */
    fun getBinary(context: Context, version: BitcoinVersion): File {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, version.libraryName)

        if (binary.exists() && binary.canExecute()) {
            Log.i(TAG, "bitcoind [${version.displayName} ${version.versionString}] at: ${binary.absolutePath} (${binary.length()} bytes)")
            return binary
        }

        // Fallback: legacy single-binary name (pre-version-selection)
        val legacy = File(nativeLibDir, "libbitcoind.so")
        if (legacy.exists() && legacy.canExecute()) {
            Log.w(TAG, "Using legacy binary at: ${legacy.absolutePath}")
            return legacy
        }

        throw RuntimeException("bitcoind binary not found for ${version.displayName} ${version.versionString}")
    }
}
