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
        const val PREFS_KEY_LAST_PUSHED_AT = "last_pushed_at"
        const val PREFS_KEY_LAST_FRIENDS_UPDATED_AT = "last_friends_updated_at"
        const val PREFS_KEY_LAST_SENT_LAT = "last_sent_lat"
        const val PREFS_KEY_LAST_SENT_LON = "last_sent_lon"
        const val PREFS_KEY_LAST_SENT_AT = "last_sent_at"
        const val PREFS_KEY_LAST_ACTIVITY_NAME = "last_activity_name"
        const val PREFS_KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_NEARBY_RADIUS_MILES = 25
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var forwardUrl: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_URL, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_URL, value) }

    var forwardToken: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_TOKEN, value) }

    var friendsCache: String
        get() = sharedPreferences.getString(PREFS_KEY_FRIENDS_CACHE, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FRIENDS_CACHE, value) }

    var lastSentLat: String
        get() = sharedPreferences.getString(PREFS_KEY_LAST_SENT_LAT, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_LAST_SENT_LAT, value) }

    var lastSentLon: String
        get() = sharedPreferences.getString(PREFS_KEY_LAST_SENT_LON, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_LAST_SENT_LON, value) }

    var lastSentAtMillis: Long
        get() = sharedPreferences.getLong(PREFS_KEY_LAST_SENT_AT, 0L)
        set(value) = sharedPreferences.edit { putLong(PREFS_KEY_LAST_SENT_AT, value) }

    /** When a location was last POSTed to the server successfully (lastSentAt is just "obtained"). */
    var lastPushedAtMillis: Long
        get() = sharedPreferences.getLong(PREFS_KEY_LAST_PUSHED_AT, 0L)
        set(value) = sharedPreferences.edit { putLong(PREFS_KEY_LAST_PUSHED_AT, value) }

    /** When the cached friend locations were last successfully fetched from the server. */
    var lastFriendsUpdatedAtMillis: Long
        get() = sharedPreferences.getLong(PREFS_KEY_LAST_FRIENDS_UPDATED_AT, 0L)
        set(value) = sharedPreferences.edit { putLong(PREFS_KEY_LAST_FRIENDS_UPDATED_AT, value) }

    var lastActivityName: String?
        get() = sharedPreferences.getString(PREFS_KEY_LAST_ACTIVITY_NAME, null)
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_LAST_ACTIVITY_NAME, value) }

    /** Shared by the app and every widget; widgets re-render on change. */
    var themeMode: ThemeMode
        get() = ThemeMode.from(sharedPreferences.getString(PREFS_KEY_THEME_MODE, null))
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_THEME_MODE, value.name) }

    /** Which friend a SingleFriendWidget instance tracks, keyed by app-widget id. */
    fun widgetFriendHandle(widgetId: Int): String? =
        sharedPreferences.getString("widget_friend_$widgetId", null)

    fun setWidgetFriendHandle(widgetId: Int, handle: String?) = sharedPreferences.edit {
        if (handle == null) remove("widget_friend_$widgetId")
        else putString("widget_friend_$widgetId", handle)
    }

    /** Nearby radius, in miles, selected for a particular Nearby widget instance. */
    fun nearbyWidgetRadiusMiles(widgetId: Int): Int =
        sharedPreferences.getInt("nearby_radius_miles_$widgetId", DEFAULT_NEARBY_RADIUS_MILES)

    fun setNearbyWidgetRadiusMiles(widgetId: Int, miles: Int?) = sharedPreferences.edit {
        if (miles == null) remove("nearby_radius_miles_$widgetId")
        else putInt("nearby_radius_miles_$widgetId", miles)
    }
}
