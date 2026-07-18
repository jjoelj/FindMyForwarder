package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class LocationUpdatesForegroundService : Service() {

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var notificationProvider: NotificationProvider
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: SharedPreferencesProvider

    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTIVITY_TRANSITION_ACTION = "ACTIVITY_TRANSITION_ACTION"
        const val RESET_LOCATION_ACTION = "RESET_LOCATION_ACTION"
        const val EXTRA_ACTIVITY_TYPE = "EXTRA_ACTIVITY_TYPE"
        const val EXTRA_TRANSITION_TYPE = "EXTRA_TRANSITION_TYPE"

        fun isRunning() = isServiceInForeground

        @Volatile
        private var isServiceInForeground = false
    }

    private var locationUpdatesRequested = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i("LocationUpdatesForegroundService created.")
        prefs = SharedPreferencesProvider(applicationContext)
        notificationProvider = NotificationProvider(applicationContext)
        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(applicationContext)
        notificationProvider.createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    FileLogger.i("Location update received: ${location.latitude}, ${location.longitude}")
                    rememberLastSentLocation(location.latitude, location.longitude)

                    serviceScope.launch {
                        postLocation(location.latitude, location.longitude)
                    }
                }
            }
        }
    }

    suspend fun postLocation(latitude: Double, longitude: Double): Boolean =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.forwardUrl
            val token = prefs.forwardToken
            if (baseUrl.isBlank() || token.isBlank()) {
                FileLogger.w("Forwarding endpoint not configured; skipping location post.")
                AppStatus.setPostResult(false, "Endpoint not configured")
                return@withContext false
            }

            val httpUrl = "$baseUrl/set".toHttpUrlOrNull()
            if (httpUrl == null) {
                FileLogger.e("Invalid forwarding URL: $baseUrl")
                AppStatus.setPostResult(false, "Invalid forwarding URL")
                return@withContext false
            }

            val url = httpUrl.newBuilder()
                .addQueryParameter("lat", latitude.toString())
                .addQueryParameter("lon", longitude.toString())
                .addQueryParameter("token", token)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("skip_zrok_interstitial", "1")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    FileLogger.i(body)
                    AppStatus.setPostResult(response.isSuccessful, "HTTP ${response.code}")
                    if (response.isSuccessful) {
                        prefs.lastPushedAtMillis = System.currentTimeMillis()
                        notificationProvider.updateNotification(prefs.lastActivityName)
                    }
                    response.isSuccessful
                }
            } catch (e: Exception) {
                FileLogger.e("Error posting location: ${e.message}")
                AppStatus.setPostResult(false, e.message)
                false
            }
        }

    // Everything here is synchronous and ordered — the previous GlobalScope.launch per
    // intent let coroutines from back-to-back transitions interleave, which could
    // re-post the notification after a STILL stop.
    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationProvider.createNotification(prefs.lastActivityName)
        startForeground(
            NotificationProvider.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        if (!isServiceInForeground) {
            isServiceInForeground = true
            AppStatus.setServiceRunning(true)
            FileLogger.i("LocationUpdatesForegroundService started in foreground.")
        }

        when (intent?.action) {
            ACTIVITY_TRANSITION_ACTION -> handleActivityTransition(intent)

            RESET_LOCATION_ACTION -> {
                FileLogger.i("Received RESET_LOCATION_ACTION")
                sendCurrentLocation()
            }
        }
        // NOT_STICKY: a sticky restart redelivers a null intent, leaving a zombie
        // foreground service showing the last saved activity. Transitions restart us.
        return START_NOT_STICKY
    }

    private fun handleActivityTransition(intent: Intent) {
        val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
        val transitionType = intent.getIntExtra(EXTRA_TRANSITION_TYPE, -1)

        val activityName = activityType.getActivityName()
        val transitionName = transitionType.getTransitionName()
        FileLogger.i("Received ACTIVITY_TRANSITION_ACTION - Activity: $activityName, Transition: $transitionName")
        AppStatus.setActivity(activityName, transitionName)

        if (activityType == DetectedActivity.STILL) {
            // Don't persist or show "Still" — we're stopping; saving it would make the
            // next cold start's notification say "Still" before the real activity arrives.
            if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                // Post the final resting location; sendCurrentLocation's completion
                // then stops the service via stopIfIdle().
                stopLocationUpdates()
                sendCurrentLocation()
            }
            return
        }

        prefs.lastActivityName = activityName
        notificationProvider.updateNotification(activityName)

        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            locationUpdatesRequested = true
            fusedLocationProviderClient.requestLocationUpdates(
                buildLocationRequest(activityType),
                locationCallback,
                Looper.getMainLooper()
            ).addOnFailureListener {
                FileLogger.e("Failed to start location updates: ${it.message}")
                AppStatus.setLocationUpdatesActive(false)
            }.addOnSuccessListener {
                AppStatus.setLocationUpdatesActive(true)
            }
        } else if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
            stopLocationUpdates()
        }
    }

    private fun buildLocationRequest(activityType: Int): LocationRequest {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> LocationRequest.Builder(30_000L)
                .setMinUpdateIntervalMillis(15_000L)
                .setMaxUpdateDelayMillis(120_000L)
                .setMinUpdateDistanceMeters(50f)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            DetectedActivity.ON_BICYCLE -> LocationRequest.Builder(20_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .setMaxUpdateDelayMillis(90_000L)
                .setMinUpdateDistanceMeters(25f)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            DetectedActivity.RUNNING -> LocationRequest.Builder(15_000L)
                .setMinUpdateIntervalMillis(7_500L)
                .setMaxUpdateDelayMillis(60_000L)
                .setMinUpdateDistanceMeters(15f)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> LocationRequest.Builder(30_000L)
                .setMinUpdateIntervalMillis(15_000L)
                .setMaxUpdateDelayMillis(180_000L)
                .setMinUpdateDistanceMeters(25f)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            else -> {
                LocationRequest.Builder(120_000L)
                    .setMinUpdateIntervalMillis(60_000L)
                    .setMaxUpdateDelayMillis(600_000L)
                    .setMinUpdateDistanceMeters(100f)
                    .setPriority(Priority.PRIORITY_LOW_POWER)
                    .build()
            }
        }
    }

    private fun rememberLastSentLocation(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        prefs.lastSentLat = latitude.toString()
        prefs.lastSentLon = longitude.toString()
        prefs.lastSentAtMillis = now
        AppStatus.setLastLocation(latitude, longitude, now)
    }

    private fun sendCurrentLocation() {
        val currentLocationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()
        fusedLocationProviderClient.getCurrentLocation(currentLocationRequest, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    rememberLastSentLocation(location.latitude, location.longitude)
                    serviceScope.launch {
                        postLocation(location.latitude, location.longitude)
                    }
                } else {
                    FileLogger.w("Current location is null.")
                }
                stopIfIdle()
            }
            .addOnFailureListener {
                FileLogger.e("Failed to get current location: ${it.message}")
                stopIfIdle()
            }
    }

    // A one-shot RESET_LOCATION_ACTION must not leave the service parked in the
    // foreground with a stale "Detected Activity" notification.
    private fun stopIfIdle() {
        if (!locationUpdatesRequested) {
            stopForeground()
        }
    }

    private fun stopForeground() {
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isServiceInForeground = false
        AppStatus.setServiceRunning(false)
    }

    private fun stopLocationUpdates() {
        locationUpdatesRequested = false
        fusedLocationProviderClient.removeLocationUpdates(locationCallback).addOnFailureListener {
            FileLogger.e("Failed to stop location updates: ${it.message}")
        }.addOnSuccessListener {
            FileLogger.i("Location updates stopped successfully.")
            AppStatus.setLocationUpdatesActive(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.dispatcher.executorService.shutdown();
    }
}
