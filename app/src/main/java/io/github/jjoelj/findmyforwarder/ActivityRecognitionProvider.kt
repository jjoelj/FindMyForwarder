package io.github.jjoelj.findmyforwarder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

class ActivityRecognitionProvider(private val context: Context) {

    companion object {
        private const val ACTIVITY_TRANSITION_INTENT_REQUEST = 1991
    }

    private val activityRecognitionClient = ActivityRecognition.getClient(context)

    // Registration is idempotent (FLAG_UPDATE_CURRENT) and needs no service; it goes
    // straight to Play Services so it works from BOOT_COMPLETED, where Android 15+
    // forbids launching a location foreground service.
    fun startActivityTransitionRecognitionWithBroadcast(): Task<Void> {
        val intent = Intent(context, ActivityRecognitionBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ACTIVITY_TRANSITION_INTENT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val request = ActivityTransitionRequest(
            getActivityTransitions(DetectedActivity.IN_VEHICLE)
                    + getActivityTransitions(DetectedActivity.ON_BICYCLE)
                    + getActivityTransitions(DetectedActivity.ON_FOOT)
                    + getActivityTransitions(DetectedActivity.STILL)
                    + getActivityTransitions(DetectedActivity.WALKING)
                    + getActivityTransitions(DetectedActivity.RUNNING)
        )
        return activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
            .addOnFailureListener { e ->
                FileLogger.e("Failed to start activity transition updates: ${e.message}")
                AppStatus.setActivityRecognitionActive(false)
            }
            .addOnSuccessListener {
                FileLogger.i("Successfully started activity transition updates.")
                AppStatus.setActivityRecognitionActive(true)
            }
    }

    private fun getActivityTransitions(activity: Int): List<ActivityTransition> {
        return listOf(
            ActivityTransition.Builder()
                .setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
    }
}
