package io.github.jjoelj.findmyforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import kotlinx.coroutines.DelicateCoroutinesApi
import java.io.Serializable

class ActivityRecognitionBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TRANSITION_DELAY = 5 * 60 * 1000L // 5 minutes
    }

    private var lastEvent: ActivityTransitionEvent? = null
    private var lastEventTimestamp: Long = 0L

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (!checkAllPermissions(context)) {
            FileLogger.w("Missing required permissions in ActivityRecognitionBroadcastReceiver")
            return
        }
        if (ActivityTransitionResult.hasResult(intent)) {
            handleActivityTransition(context, intent)
        } else {
            FileLogger.i("Received unknown intent in ActivityRecognitionBroadcastReceiver")
        }
    }

    private fun handleActivityTransition(context: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent)
        if (result == null) {
            FileLogger.w("No ActivityTransitionResult found in intent")
            return
        }

        val events = result.transitionEvents
        if (events.isEmpty()) {
            FileLogger.w("No transition events found in ActivityTransitionResult")
            return
        }

        if (events.size == 1) {
            if (lastEvent != null) {
                val isActivityTypeMatch = lastEvent?.activityType == events[0].activityType
                val isTransitionTypeMatch = lastEvent?.transitionType == events[0].transitionType
                val isDelayExceeded =
                    (events[0].elapsedRealTimeNanos / 1_000_000L - lastEventTimestamp) > TRANSITION_DELAY
                if (isActivityTypeMatch && isTransitionTypeMatch && !isDelayExceeded) {
                    FileLogger.i("Ignoring duplicate transition event: $events[0]")
                    return
                }
            }
            lastEvent = events[0]
            lastEventTimestamp = events[0].elapsedRealTimeNanos / 1_000_000L
        } else if (events.size > 2) {
            FileLogger.w("More than 2 transition events received.")
            return
        }

        val serviceIntent = Intent(context, LocationUpdatesForegroundService::class.java).apply {
            action = LocationUpdatesForegroundService.ACTIVITY_TRANSITION_ACTION
            putExtra(
                LocationUpdatesForegroundService.EXTRA_TRANSITION_EVENTS,
                events.map { event ->
                    ActivityRecognitionEvent(
                        event.activityType,
                        event.transitionType
                    )
                } as Serializable)
        }

        try {
            context.startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            FileLogger.e("Error starting LocationUpdatesForegroundService from ActivityRecognitionBroadcastReceiver: ${t.message}", t)
        }
    }
}