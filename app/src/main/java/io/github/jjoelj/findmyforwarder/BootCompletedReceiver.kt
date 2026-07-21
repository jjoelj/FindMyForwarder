package io.github.jjoelj.findmyforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A package replace kills the process and the service with it, leaving the app
        // dark until the user next moves. Play Services keeps our registration across an
        // update, so this only has to restart the service — same work as a boot.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        FileLogger.i("${intent.action} received in BootCompletedReceiver")
        if (!checkAllPermissions(context)) {
            FileLogger.w("Missing required permissions in BootCompletedReceiver")
            return
        }
        val appContext = context.applicationContext
        // goAsync keeps the process alive until Play Services acks the registration.
        val pendingResult = goAsync()
        ActivityRecognitionProvider(appContext)
            .startActivityTransitionRecognitionWithBroadcast()
            .addOnCompleteListener { pendingResult.finish() }

        // Play Services has no prior activity state after a reboot, so it reports nothing
        // until the user actually moves. Hand the service a synthetic STILL enter: it
        // seeds the status, posts one fix, and parks in the foreground — which is also
        // what stops later transitions being deferred to a cached process.
        val serviceIntent = Intent(appContext, LocationUpdatesForegroundService::class.java)
            .apply {
                action = LocationUpdatesForegroundService.ACTIVITY_TRANSITION_ACTION
                putExtra(
                    LocationUpdatesForegroundService.EXTRA_ACTIVITY_TYPE,
                    DetectedActivity.STILL
                )
                putExtra(
                    LocationUpdatesForegroundService.EXTRA_TRANSITION_TYPE,
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER
                )
            }
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        } catch (t: Throwable) {
            FileLogger.e("Could not start location service at boot: ${t.message}", t)
        }
    }
}
