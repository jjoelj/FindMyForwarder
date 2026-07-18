package io.github.jjoelj.findmyforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FileLogger.i("Boot completed received in BootCompletedReceiver")
        if (!checkAllPermissions(context)) {
            FileLogger.w("Missing required permissions in BootCompletedReceiver")
            return
        }
        // goAsync keeps the process alive until Play Services acks the registration.
        val pendingResult = goAsync()
        ActivityRecognitionProvider(context.applicationContext)
            .startActivityTransitionRecognitionWithBroadcast()
            .addOnCompleteListener { pendingResult.finish() }
    }
}