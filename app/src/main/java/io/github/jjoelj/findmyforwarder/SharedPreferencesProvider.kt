package io.github.jjoelj.findmyforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferencesProvider(context: Context) {
    companion object {
        const val PREFS_NAME = "FindMyForwarderPrefs"
        const val PREFS_KEY_FORWARD_URL = "forward_url"
        const val PREFS_KEY_FORWARD_TOKEN = "forward_token"
        const val PREFS_KEY_FRIENDS_CACHE = "friends_cache"
        const val PREFS_KEY_FRIENDS_REFRESH_TRIGGERED_AT = "friends_refresh_triggered_at"
        const val PREFS_KEY_FRIEND_REFRESH_TIMES = "friend_refresh_times"
        const val PREFS_KEY_LAST_SENT_LAT = "last_sent_lat"
        const val PREFS_KEY_LAST_SENT_LON = "last_sent_lon"
        const val PREFS_KEY_LAST_SENT_AT = "last_sent_at"
        const val PREFS_KEY_IS_FORWARDING_ENABLED = "is_forwarding_enabled"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

    var forwardUrl: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_URL, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_URL, value) }

    var forwardToken: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_TOKEN, value) }

    var friendsCache: String
        get() = sharedPreferences.getString(PREFS_KEY_FRIENDS_CACHE, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FRIENDS_CACHE, value) }

    var friendsRefreshTriggeredAtMillis: Long
        get() = sharedPreferences.getLong(PREFS_KEY_FRIENDS_REFRESH_TRIGGERED_AT, 0L)
        set(value) = sharedPreferences.edit { putLong(PREFS_KEY_FRIENDS_REFRESH_TRIGGERED_AT, value) }

    var friendRefreshTimes: String
        get() = sharedPreferences.getString(PREFS_KEY_FRIEND_REFRESH_TIMES, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FRIEND_REFRESH_TIMES, value) }

    var lastSentLat: String
        get() = sharedPreferences.getString(PREFS_KEY_LAST_SENT_LAT, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_LAST_SENT_LAT, value) }

    var lastSentLon: String
        get() = sharedPreferences.getString(PREFS_KEY_LAST_SENT_LON, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_LAST_SENT_LON, value) }

    var lastSentAtMillis: Long
        get() = sharedPreferences.getLong(PREFS_KEY_LAST_SENT_AT, 0L)
        set(value) = sharedPreferences.edit { putLong(PREFS_KEY_LAST_SENT_AT, value) }

    var isForwardingEnabled: Boolean
        get() = sharedPreferences.getBoolean(PREFS_KEY_IS_FORWARDING_ENABLED, false)
        set(value) = sharedPreferences.edit { putBoolean(PREFS_KEY_IS_FORWARDING_ENABLED, value) }
}
