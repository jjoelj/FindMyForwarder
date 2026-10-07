package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
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
    private lateinit var gpsCallback: LocationCallback
    private lateinit var passiveCallback: LocationCallback
    private lateinit var prefs: SharedPreferencesProvider

    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTIVITY_TRANSITION_ACTION = "ACTIVITY_TRANSITION_ACTION"
        const val RESET_LOCATION_ACTION = "RESET_LOCATION_ACTION"
        const val EXTRA_ACTIVITY_TYPE = "EXTRA_ACTIVITY_TYPE"
        const val EXTRA_TRANSITION_TYPE = "EXTRA_TRANSITION_TYPE"
        private const val GPS_INTERVAL_MILLIS = 5 * 60_000L

        @Volatile
        private var isServiceInForeground = false
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

        // The service outlives the activity, so this is the only re-registration path that
        // survives a process restart the user never sees. No-op once this process holds an
        // acked registration, so the sticky-restart case is covered without extra cost.
        startActivityRecognition(applicationContext)

        locationCallback = fixCallback(onlyPrecise = false)
        gpsCallback = fixCallback(onlyPrecise = false)
        passiveCallback = fixCallback(onlyPrecise = true)
    }

    // One fix can reach several callbacks (passive sees ours too); post it once.
    private var lastFixNanos = 0L

    /** onlyPrecise: drop the flat-100 m balanced fixes; passive only adds value with real GPS. */
    private fun fixCallback(onlyPrecise: Boolean) = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            if (location.elapsedRealtimeNanos <= lastFixNanos) return
            if (onlyPrecise && !(location.hasAccuracy() && location.accuracy < 100f)) return
            lastFixNanos = location.elapsedRealtimeNanos
            FileLogger.i("Location update received: ${location.latitude}, ${location.longitude}")
            rememberLastSentLocation(location.latitude, location.longitude)

            serviceScope.launch {
                postLocation(location)
            }
        }
    }

    suspend fun postLocation(location: Location): Boolean =
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

            val url = httpUrl.newBuilder().apply {
                addQueryParameter("lat", location.latitude.toString())
                addQueryParameter("lon", location.longitude.toString())
                // Optional fields are omitted when the fix doesn't carry them.
                fun opt(name: String, has: Boolean, value: Number) {
                    if (has) addQueryParameter(name, value.toString())
                }
                // A 0 m accuracy is a bogus reading, not a perfect one; omit it.
                // Balanced fused fixes report a flat 100 m placeholder, not a measurement; omit
                // it and let the server apply its own default for non-GPS fixes.
                opt(
                    "acc",
                    location.hasAccuracy() && location.accuracy > 0 && location.accuracy != 100f,
                    location.accuracy
                )
                if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
                    opt("alt", true, location.mslAltitudeMeters)
                    opt("vacc", location.hasMslAltitudeAccuracy() && location.mslAltitudeAccuracyMeters > 0, location.mslAltitudeAccuracyMeters)
                } else {
                    opt("alt", location.hasAltitude(), location.altitude)
                    opt("vacc", location.hasVerticalAccuracy() && location.verticalAccuracyMeters > 0, location.verticalAccuracyMeters)
                }
                opt("speed", location.hasSpeed(), location.speed)
                opt("sacc", location.hasSpeedAccuracy(), location.speedAccuracyMetersPerSecond)
                opt("course", location.hasBearing(), location.bearing)
                opt("cacc", location.hasBearingAccuracy(), location.bearingAccuracyDegrees)
                addQueryParameter("token", token)
            }.build()

            // Raw fix plus exactly what we send; token redacted since logs get shared.
            FileLogger.i("Posting $location")
            FileLogger.i("Sending ${url.newBuilder().setQueryParameter("token", "***").build().query}")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    FileLogger.i(body)
                    AppStatus.setPostResult(
                        response.isSuccessful,
                        // A regenerated token kills the old one instantly; retrying never helps.
                        if (response.code == 403) "Token rejected — scan the QR code again"
                        else "HTTP ${response.code}"
                    )
                    if (response.isSuccessful) {
                        prefs.lastPushedAtMillis = System.currentTimeMillis()
                        // Don't resurrect a dismissed notif: STILL enter stops the service
                        // while this upload is still in flight.
                        if (isServiceInForeground) {
                            notificationProvider.updateNotification(prefs.lastActivityName)
                        }
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
    // COARSE rather than FINE: that is what checkAllPermissions actually verifies before
    // anything starts us, and it satisfies the anyOf on the location APIs below.
    @RequiresPermission(
        allOf = [Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.ACCESS_COARSE_LOCATION]
    )
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationProvider.createNotification(prefs.lastActivityName)
        try {
            startForeground(
                NotificationProvider.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (t: Throwable) {
            // Android 15+ bars location-type foreground services started from
            // BOOT_COMPLETED, and rejects them here rather than at startForegroundService.
            // Bail quietly; the widget refresh or the first real transition starts us.
            FileLogger.e("startForeground rejected: ${t.message}", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isServiceInForeground) {
            isServiceInForeground = true
            AppStatus.setServiceRunning(true)
            FileLogger.i("LocationUpdatesForegroundService started in foreground.")
            // Free: never powers anything, just hears fixes other apps (Maps, etc.) pay for.
            fusedLocationProviderClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000L)
                    .setMinUpdateIntervalMillis(60_000L)
                    .setMinUpdateDistanceMeters(15f)
                    .build(),
                passiveCallback,
                Looper.getMainLooper()
            ).addOnFailureListener {
                FileLogger.e("Failed to start passive location updates: ${it.message}")
            }
        }

        when (intent?.action) {
            ACTIVITY_TRANSITION_ACTION -> handleActivityTransition(intent)

            RESET_LOCATION_ACTION -> {
                FileLogger.i("Received RESET_LOCATION_ACTION")
                sendCurrentLocation(highAccuracy = true)
            }
        }
        // STICKY now that idling in the foreground is the resting state: a null-intent
        // restart just re-parks us there, keeping the process out of the cached bucket.
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun handleActivityTransition(intent: Intent) {
        val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
        val transitionType = intent.getIntExtra(EXTRA_TRANSITION_TYPE, -1)

        val activityName = activityType.getActivityName()
        val transitionName = transitionType.getTransitionName()
        FileLogger.i("Received ACTIVITY_TRANSITION_ACTION - Activity: $activityName, Transition: $transitionName")
        AppStatus.setActivity(activityName, transitionName)

        // Single source of truth for the notification text: postLocation and
        // onStartCommand both rebuild it from prefs.lastActivityName, so anything not
        // written here gets overwritten. "Still" included — it's the resting state now
        // that the service no longer stops.
        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            prefs.lastActivityName = activityName
            notificationProvider.updateNotification(activityName)
        }

        if (activityType == DetectedActivity.STILL) {
            // Stay in the foreground with location updates off instead of stopping. A
            // process with no running FGS goes cached, and Android defers broadcasts to
            // cached processes — the next transition would sit in the queue until the
            // user opened the app, which is exactly the wake-up bug.
            if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                stopLocationUpdates()
                sendCurrentLocation()
            }
            return
        }

        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
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
            // Balanced fixes report a flat 100 m. One real GPS fix every 5 min, only when moving
            // fast enough for that to matter; on foot, balanced is close enough.
            val fast = activityType == DetectedActivity.IN_VEHICLE ||
                activityType == DetectedActivity.ON_BICYCLE
            // Vehicle -> walking can deliver ENTER before the vehicle EXIT; don't leave GPS on.
            if (!fast) fusedLocationProviderClient.removeLocationUpdates(gpsCallback)
            else fusedLocationProviderClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MILLIS)
                    .setMinUpdateIntervalMillis(GPS_INTERVAL_MILLIS)
                    .build(),
                gpsCallback,
                Looper.getMainLooper()
            ).addOnFailureListener {
                FileLogger.e("Failed to start GPS location updates: ${it.message}")
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

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun sendCurrentLocation(highAccuracy: Boolean = false) {
        // A user-requested send gets a fresh GPS fix; the automatic one on going still stays
        // cheap. A cached fix would just be the last balanced one.
        val currentLocationRequest = CurrentLocationRequest.Builder()
            .setPriority(
                if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
            .apply { if (highAccuracy) setMaxUpdateAgeMillis(0) }
            .build()
        fusedLocationProviderClient.getCurrentLocation(currentLocationRequest, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    rememberLastSentLocation(location.latitude, location.longitude)
                    serviceScope.launch {
                        postLocation(location)
                    }
                } else {
                    FileLogger.w("Current location is null.")
                }
            }
            .addOnFailureListener {
                FileLogger.e("Failed to get current location: ${it.message}")
            }
    }

    private fun stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(gpsCallback)
        fusedLocationProviderClient.removeLocationUpdates(locationCallback).addOnFailureListener {
            FileLogger.e("Failed to stop location updates: ${it.message}")
        }.addOnSuccessListener {
            FileLogger.i("Location updates stopped successfully.")
            AppStatus.setLocationUpdatesActive(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationProviderClient.removeLocationUpdates(passiveCallback)
        fusedLocationProviderClient.removeLocationUpdates(gpsCallback)
        client.dispatcher.executorService.shutdown()
    }
}
