package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

fun Int?.getActivityName(): String {
    return when (this) {
        DetectedActivity.STILL -> "Still"
        DetectedActivity.WALKING -> "Walking"
        DetectedActivity.RUNNING -> "Running"
        DetectedActivity.ON_BICYCLE -> "Cycling"
        DetectedActivity.IN_VEHICLE -> "In Vehicle"
        DetectedActivity.UNKNOWN -> "Unknown"
        DetectedActivity.TILTING -> "Tilting"
        else -> "Unknown Activity Type: $this"
    }
}

fun Int.getTransitionName(): String {
    return when (this) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "Enter"
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "Exit"
        else -> "Unknown Transition Type: $this"
    }
}

@Composable
fun Modifier.scrollBar(
    state: ScrollState,
    scrollbarWidth: Dp = 6.dp,
    color: Color = Color.LightGray
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 1f else 0f,
        animationSpec = tween(400, delayMillis = if (state.isScrollInProgress) 0 else 700)
    )

    return this then Modifier.drawWithContent {
        drawContent()


        val viewHeight = state.viewportSize.toFloat()
        val contentHeight = state.maxValue + viewHeight

        val scrollbarHeight =
            (viewHeight * (viewHeight / contentHeight)).coerceIn(10.dp.toPx()..viewHeight)
        val variableZone = viewHeight - scrollbarHeight
        val scrollbarYoffset = (state.value.toFloat() / state.maxValue) * variableZone

        drawRoundRect(
            cornerRadius = CornerRadius(scrollbarWidth.toPx() / 2, scrollbarWidth.toPx() / 2),
            color = color,
            topLeft = Offset(this.size.width - scrollbarWidth.toPx(), scrollbarYoffset),
            size = Size(scrollbarWidth.toPx(), scrollbarHeight),
            alpha = alpha
        )
    }
}

fun checkAllPermissions(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return true
}

// Registers directly with Play Services — no foreground service involved, so this is
// legal from BOOT_COMPLETED on Android 15+ and doesn't flash a notification on app open.
fun startActivityRecognition(context: Context) {
    if (!checkAllPermissions(context)) {
        FileLogger.w("Missing required permissions")
        return
    }
    // Play Services echoes the current activity back the instant you register, so
    // re-registering on every app open costs a GPS fix and a POST for nothing. A running
    // service already implies a live registration; boot and package-replace both reach
    // here with it stopped, so they still register.
    if (LocationUpdatesForegroundService.isRunning()) return
    ActivityRecognitionProvider(context.applicationContext)
        .startActivityTransitionRecognitionWithBroadcast()
}

/** "Location sent 12 min. ago" for the last successful push, or null if never pushed. */
fun lastPushedLabel(atMillis: Long): String? {
    if (atMillis <= 0) return null
    return if (System.currentTimeMillis() - atMillis < 60_000) "Location sent just now"
    else "Location sent ${DateUtils.getRelativeTimeSpanString(atMillis)}"
}

fun resetLocation(context: Context) {
    val serviceIntent = Intent(context, LocationUpdatesForegroundService::class.java).apply {
        action = LocationUpdatesForegroundService.RESET_LOCATION_ACTION
    }
    if (!checkAllPermissions(context)) {
        FileLogger.w("Missing required permissions")
        return
    }
    ContextCompat.startForegroundService(context, serviceIntent)
}
