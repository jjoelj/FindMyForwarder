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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.ArrayList

class LocationUpdatesForegroundService : Service() {

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var notificationProvider: NotificationProvider
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: SharedPreferencesProvider

    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val START_FOREGROUND_ACTION = "START_FOREGROUND_ACTION"
        const val ACTIVITY_TRANSITION_ACTION = "ACTIVITY_TRANSITION_ACTION"
        const val RESET_LOCATION_ACTION = "RESET_LOCATION_ACTION"
        const val EXTRA_TRANSITION_EVENTS = "EXTRA_TRANSITION_EVENTS"

        fun isRunning() = isServiceInForeground

        @Volatile
        private var isServiceInForeground = false

        @Volatile
        private var isActivityRecognitionActive = false
    }

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
                return@withContext false
            }

            val httpUrl = "$baseUrl/set".toHttpUrlOrNull()
            if (httpUrl == null) {
                FileLogger.e("Invalid forwarding URL: $baseUrl")
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
                    FileLogger.i(response.body.string())
                    response.isSuccessful
                }
            } catch (e: Exception) {
                FileLogger.e("Error posting location: ${e.message}")
                false
            }
        }

    @OptIn(DelicateCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GlobalScope.launch {
            val notification = notificationProvider.createNotification(null)
            startForeground(
                NotificationProvider.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )

            if (!isServiceInForeground) {
                isServiceInForeground = true
                FileLogger.i("LocationUpdatesForegroundService started in foreground.")
            }

            if (!isActivityRecognitionActive) {
                isActivityRecognitionActive = true
                ActivityRecognitionProvider(applicationContext).apply {
                    startActivityTransitionRecognitionWithBroadcast()
                }
            }

            when (intent?.action) {
                START_FOREGROUND_ACTION -> {
                    FileLogger.i("Received START_FOREGROUND_ACTION")
                    stopForeground()
                }

                ACTIVITY_TRANSITION_ACTION -> {
                    @Suppress("UNCHECKED_CAST")
                    val transitionEvents = intent.getSerializableExtra(
                        EXTRA_TRANSITION_EVENTS,
                        ArrayList::class.java
                    ) as? ArrayList<ActivityRecognitionEvent>

                    val currentActivity = transitionEvents?.lastOrNull()
                    if (currentActivity == null) {
                        FileLogger.w("No activity recognition events found in intent.")
                        return@launch
                    }

                    val currentActivityName = currentActivity.activityType.getActivityName()
                    val currentTransitionName = currentActivity.transitionType.getTransitionName()
                    val event = "Activity: $currentActivityName, Transition: $currentTransitionName"
                    FileLogger.i("Received ACTIVITY_TRANSITION_ACTION - $event")

                    notificationProvider.updateNotification(currentActivityName)

                    if (currentActivity.activityType != DetectedActivity.STILL) {
                        if (currentActivity.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                            val locationRequest = buildLocationRequest(currentActivity)

                            fusedLocationProviderClient.requestLocationUpdates(
                                locationRequest,
                                locationCallback,
                                Looper.getMainLooper()
                            ).addOnFailureListener {
                                FileLogger.e("Failed to start location updates: ${it.message}")
                            }
                        } else if (currentActivity.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                            stopLocationUpdates()
                        }
                    } else if (currentActivity.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        stopForeground()
                    }
                }

                RESET_LOCATION_ACTION -> {
                    FileLogger.i("Received RESET_LOCATION_ACTION")
                    val currentLocationRequest = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .build()
                    fusedLocationProviderClient.getCurrentLocation(currentLocationRequest, null)
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                serviceScope.launch {
                                    postLocation(location.latitude, location.longitude)
                                }
                            } else {
                                FileLogger.w("Current location is null.")
                            }
                        }
                        .addOnFailureListener {
                            FileLogger.e("Failed to get current location: ${it.message}")
                        }
                    stopForeground()
                }
            }
        }
        return START_STICKY
    }

    private fun buildLocationRequest(detectedActivity: ActivityRecognitionEvent): LocationRequest {
        return when (detectedActivity.activityType) {
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

    private fun stopForeground() {
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isServiceInForeground = false
    }

    private fun stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback).addOnFailureListener {
            FileLogger.e("Failed to stop location updates: ${it.message}")
        }.addOnSuccessListener {
            FileLogger.i("Location updates stopped successfully.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationProvider.destroyNotification()
        client.dispatcher.executorService.shutdown();
    }
}