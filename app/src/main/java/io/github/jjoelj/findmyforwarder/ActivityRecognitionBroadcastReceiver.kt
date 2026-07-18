package io.github.jjoelj.findmyforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityRecognitionBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!checkAllPermissions(context)) {
            FileLogger.w("Missing required permissions in ActivityRecognitionBroadcastReceiver")
            return
        }
        if (!ActivityTransitionResult.hasResult(intent)) {
            FileLogger.i("Received unknown intent in ActivityRecognitionBroadcastReceiver")
            return
        }

        // Play Services may batch several events (e.g. after doze); only the latest matters.
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull()
        if (event == null) {
            FileLogger.w("No transition events found in ActivityTransitionResult")
            return
        }

        val isStillEnter = event.activityType == DetectedActivity.STILL &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        if (isStillEnter && !LocationUpdatesForegroundService.isRunning()) {
            FileLogger.i("STILL enter while service not running; nothing to do.")
            return
        }

        val serviceIntent = Intent(context, LocationUpdatesForegroundService::class.java).apply {
            action = LocationUpdatesForegroundService.ACTIVITY_TRANSITION_ACTION
            putExtra(LocationUpdatesForegroundService.EXTRA_ACTIVITY_TYPE, event.activityType)
            putExtra(LocationUpdatesForegroundService.EXTRA_TRANSITION_TYPE, event.transitionType)
        }

        try {
            context.startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            FileLogger.e("Error starting LocationUpdatesForegroundService from ActivityRecognitionBroadcastReceiver: ${t.message}", t)
        }
    }
}
