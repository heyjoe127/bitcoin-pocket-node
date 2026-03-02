package com.pocketnode.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version of the app.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/FreeOnlineUser/bitcoin-pocket-node/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val htmlUrl: String,
        val hasUpdate: Boolean,
        val releaseNotes: String = ""
    )

    /**
     * Check GitHub for the latest release. Returns null on network error.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: return@withContext null

            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tagName = json.getString("tag_name").removePrefix("v")
            val htmlUrl = json.getString("html_url")
            val releaseNotes = json.optString("body", "").take(500)

            val hasUpdate = isNewer(tagName, currentVersion)

            UpdateInfo(
                latestVersion = tagName,
                currentVersion = currentVersion,
                htmlUrl = htmlUrl,
                hasUpdate = hasUpdate,
                releaseNotes = releaseNotes
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compare version strings. Simple comparison: strip non-numeric suffixes,
     * compare major.minor numerically.
     */
    private fun isNewer(remote: String, local: String): Boolean {
        // Normalize: "0.11-alpha" -> [0, 11], "0.12" -> [0, 12]
        fun parse(v: String): List<Int> = v.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        val r = parse(remote)
        val l = parse(local)

        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    fun openReleasePage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
