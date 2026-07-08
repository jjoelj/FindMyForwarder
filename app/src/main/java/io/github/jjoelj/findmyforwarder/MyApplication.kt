package io.github.jjoelj.findmyforwarder

import android.app.Application
import org.osmdroid.config.Configuration

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        Configuration.getInstance().userAgentValue = packageName
        val prefs = SharedPreferencesProvider(this)
        val lat = prefs.lastSentLat.toDoubleOrNull()
        val lon = prefs.lastSentLon.toDoubleOrNull()
        if (lat != null && lon != null) {
            AppStatus.setLastLocation(lat, lon, prefs.lastSentAtMillis)
        }
    }
}
