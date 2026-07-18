package io.github.jjoelj.findmyforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.util.Date

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

        val lastUploaded = SharedPreferencesProvider(context).lastPushedAtMillis.takeIf { it > 0L }
            ?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
            ?: "Never"
        val activityText = "Detected Activity: ${activityName ?: "Unknown"}"
        val uploadText = "Last uploaded: $lastUploaded"
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Find My Forwarder")
            .setContentText(activityText)
            .setSubText(uploadText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$activityText\n$uploadText"))
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

    // Never delete the notification channel: posting on a just-deleted channel crashes
    // with CannotPostForegroundServiceNotificationException.
    fun updateNotification(activityName: String?) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(activityName))
    }
}
