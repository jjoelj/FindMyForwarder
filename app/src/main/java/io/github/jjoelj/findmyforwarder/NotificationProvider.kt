package io.github.jjoelj.findmyforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class NotificationProvider(private val context: Context) {

    companion object {
        private const val SERVICE_REQUEST_CODE = 1990
        private const val NOTIFICATION_CHANNEL_ID = "sending_updates_channel"
        const val NOTIFICATION_ID = 1
    }

    fun createNotification(activityName: String?): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(
            context,
            SERVICE_REQUEST_CODE,
            notificationIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Find My Forwarder")
            .setContentText("Detected Activity: ${activityName ?: "Unknown"}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        return notification
    }

    fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID,
            "Location Update Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        channel.enableVibration(false)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun cancelNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    fun destroyNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
    }

    fun updateNotification(activityName: String?) {
        cancelNotification()
        val notification = createNotification(activityName)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}