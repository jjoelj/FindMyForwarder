package io.github.jjoelj.findmyforwarder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class StatusState(
    val serviceRunning: Boolean = false,
    val activityRecognitionActive: Boolean = false,
    val currentActivity: String? = null,
    val currentTransition: String? = null,
    val lastActivityAtMillis: Long = 0L,
    val locationUpdatesActive: Boolean = false,
    val lastLocationLat: Double? = null,
    val lastLocationLon: Double? = null,
    val lastLocationAtMillis: Long = 0L,
    val lastPostSucceeded: Boolean? = null,
    val lastPostMessage: String? = null,
    val lastPostAtMillis: Long = 0L,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val batteryExternalPower: Boolean? = null,
    val batteryMessage: String? = null,
    val batteryAtMillis: Long = 0L,
)

object AppStatus {
    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state

    // Separate from StatusState: this is a user setting, not observed device status, and
    // it is seeded from prefs at startup so the first frame already has the right theme.
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    fun setServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(serviceRunning = running)
    }

    fun setActivityRecognitionActive(active: Boolean) {
        _state.value = _state.value.copy(activityRecognitionActive = active)
    }

    fun setActivity(activity: String, transition: String) {
        _state.value = _state.value.copy(
            currentActivity = activity,
            currentTransition = transition,
            lastActivityAtMillis = System.currentTimeMillis(),
        )
    }

    fun setLocationUpdatesActive(active: Boolean) {
        _state.value = _state.value.copy(locationUpdatesActive = active)
    }

    fun setLastLocation(lat: Double, lon: Double, atMillis: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(
            lastLocationLat = lat,
            lastLocationLon = lon,
            lastLocationAtMillis = atMillis,
        )
    }

    fun setPostResult(succeeded: Boolean, message: String? = null) {
        _state.value = _state.value.copy(
            lastPostSucceeded = succeeded,
            lastPostMessage = message,
            lastPostAtMillis = System.currentTimeMillis(),
        )
    }

    fun setBattery(
        percent: Int?,
        charging: Boolean?,
        externalPower: Boolean?,
        message: String? = null,
    ) {
        _state.value = _state.value.copy(
            batteryPercent = percent,
            batteryCharging = charging,
            batteryExternalPower = externalPower,
            batteryMessage = message,
            batteryAtMillis = System.currentTimeMillis(),
        )
    }
}
