package io.github.jjoelj.findmyforwarder

import android.app.Application
import org.osmdroid.config.Configuration

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        // load() resolves the tile cache path; without it the disk cache is dead
        // and every map render re-downloads every tile.
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        val prefs = SharedPreferencesProvider(this)
        AppStatus.setThemeMode(prefs.themeMode)
        val lat = prefs.lastSentLat.toDoubleOrNull()
        val lon = prefs.lastSentLon.toDoubleOrNull()
        if (lat != null && lon != null) {
            AppStatus.setLastLocation(lat, lon, prefs.lastSentAtMillis)
        }
    }
}
