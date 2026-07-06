package io.github.jjoelj.findmyforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FileLogger.i("Boot completed received in BootCompletedReceiver")
            startActivityRecognition(context);
        }
    }
}