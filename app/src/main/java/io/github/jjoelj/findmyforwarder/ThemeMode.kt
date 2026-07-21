package io.github.jjoelj.findmyforwarder

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Compose-side resolution; SYSTEM follows the device so it tracks live config changes. */
@Composable
@ReadOnlyComposable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Resolves the theme outside Compose — widgets render from a BroadcastReceiver where
 * isSystemInDarkTheme() is unavailable, and they must agree with the app.
 */
fun isDarkTheme(context: Context): Boolean = when (SharedPreferencesProvider(context).themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM ->
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
}
