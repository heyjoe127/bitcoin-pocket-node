package com.pocketnode.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketnode.MainActivity

class TransactionNotificationManager(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "mempool_confirmations"
        private const val CHANNEL_NAME = "Transaction Confirmations"
        private const val CHANNEL_DESCRIPTION = "Notifications when watched transactions are confirmed"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init { createNotificationChannel() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#FF9500")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun notifyTransactionConfirmed(txid: String, blockNumber: Int, confirmations: Int) {
        if (!hasNotificationPermission()) return

        val truncatedTxid = if (txid.length > 12) "${txid.take(6)}...${txid.takeLast(6)}" else txid
        val title = "⛏️ Transaction Confirmed"
        val message = "$truncatedTxid confirmed in block #$blockNumber ($confirmations confirmations)"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("txid", txid)
        }
        val pendingIntent = PendingIntent.getActivity(context, txid.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(context.getColor(android.R.color.holo_orange_light))
            .setVibrate(longArrayOf(0, 150, 100, 150))
            .build()

        notificationManager.notify(NOTIFICATION_ID_BASE + txid.hashCode() % 1000, notification)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}
