package io.github.jjoelj.findmyforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferencesProvider(context: Context) {
    companion object {
        const val PREFS_NAME = "FindMyForwarderPrefs"
        const val PREFS_KEY_FORWARD_URL = "forward_url"
        const val PREFS_KEY_FORWARD_TOKEN = "forward_token"
        const val PREFS_KEY_IS_FORWARDING_ENABLED = "is_forwarding_enabled"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

    var forwardUrl: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_URL, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_URL, value) }

    var forwardToken: String
        get() = sharedPreferences.getString(PREFS_KEY_FORWARD_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit { putString(PREFS_KEY_FORWARD_TOKEN, value) }

    var isForwardingEnabled: Boolean
        get() = sharedPreferences.getBoolean(PREFS_KEY_IS_FORWARDING_ENABLED, false)
        set(value) = sharedPreferences.edit { putBoolean(PREFS_KEY_IS_FORWARDING_ENABLED, value) }
}