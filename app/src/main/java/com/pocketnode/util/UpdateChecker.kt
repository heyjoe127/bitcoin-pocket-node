package com.pocketnode.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version and handles APK download + install.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/FreeOnlineUser/bitcoin-pocket-node/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val hasUpdate: Boolean
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

            // Find APK asset
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            val hasUpdate = isNewer(tagName, currentVersion)

            UpdateInfo(
                latestVersion = tagName,
                currentVersion = currentVersion,
                htmlUrl = htmlUrl,
                apkUrl = apkUrl,
                hasUpdate = hasUpdate
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download APK and trigger Android's package installer.
     * Returns progress via callback (0-100), or -1 on error.
     */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "update.apk")

            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(((downloaded * 100) / totalBytes).toInt())
                        }
                    }
                }
            }

            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")

            // Trigger install via FileProvider
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}", e)
            false
        }
    }

    /**
     * Compare version strings. Strip suffixes, compare major.minor numerically.
     */
    private fun isNewer(remote: String, local: String): Boolean {
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
}
