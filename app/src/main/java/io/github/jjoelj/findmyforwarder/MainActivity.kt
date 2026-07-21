package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.jjoelj.findmyforwarder.ui.theme.FindMyForwarderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Idempotent re-registration; the safety net for lost Play Services registrations.
        startActivityRecognition(this)

        setContent {
            val themeMode by AppStatus.themeMode.collectAsState()
            FindMyForwarderTheme(darkTheme = themeMode.isDark()) {
                FindMyForwarderApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun FindMyForwarderApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FRIENDS) }
    var friendsResetRequest by rememberSaveable { mutableIntStateOf(0) }
    var dismissedLowBatteryWarning by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val status by AppStatus.state.collectAsState()
    val batteryPercent = status.batteryPercent
    SystemBarsForTheme()

    LaunchedEffect(Unit) {
        fetchBatteryStatus(context)
    }

    if (
        !dismissedLowBatteryWarning &&
        batteryPercent != null &&
        batteryPercent < 20 &&
        status.batteryCharging != true
    ) {
        AlertDialog(
            onDismissRequest = { dismissedLowBatteryWarning = true },
            title = { Text("iPhone battery low") },
            text = {
                Text("The forwarding iPhone is at $batteryPercent% and is not charging.")
            },
            confirmButton = {
                TextButton(onClick = { dismissedLowBatteryWarning = true }) {
                    Text("OK")
                }
            }
        )
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
                            contentDescription = it.label,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = {
                        if (it == AppDestinations.FRIENDS && currentDestination == AppDestinations.FRIENDS) {
                            friendsResetRequest++
                        }
                        currentDestination = it
                    }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
            // Map runs full-bleed under the status bar; it draws its own scrim.
            val layoutDirection = LocalLayoutDirection.current
            val mapModifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding(),
                )
            when (currentDestination) {
                AppDestinations.FRIENDS -> FriendsScreen(mapModifier, friendsResetRequest)
                AppDestinations.STATUS -> StatusScreen(contentModifier)
                AppDestinations.LOGS -> Logs(contentModifier)
                AppDestinations.SETTINGS -> SettingsScreen(contentModifier)
            }
        }
    }
}

@Composable
private fun SystemBarsForTheme() {
    val view = LocalView.current
    val useDarkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    FRIENDS("Friends", R.drawable.group_24px),
    STATUS("Status", R.drawable.monitoring_24px),
    LOGS("Logs", R.drawable.logs_24px),
    SETTINGS("Settings", R.drawable.settings_24px),
}

@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val currentLogs by FileLogger.logFlow.collectAsState(initial = FileLogger.getLog())
    val status by AppStatus.state.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val prefs = SharedPreferencesProvider(context)

    LaunchedEffect(currentLogs) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    LaunchedEffect(prefs.forwardUrl, prefs.forwardToken) {
        fetchBatteryStatus(context)
    }

    Column(
        modifier = modifier
            .scrollBar(scrollState, color = MaterialTheme.colorScheme.primary)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val now = rememberTimeTick()
        Text("Status", style = MaterialTheme.typography.titleLarge)
        StatusCard(
            "Forwarding",
            if (prefs.forwardUrl.isBlank()) "Not configured" else "Configured",
            prefs.forwardUrl.ifBlank { "Set this in Settings" },
            good = prefs.forwardUrl.isNotBlank() && prefs.forwardToken.isNotBlank()
        )
        StatusCard(
            "Service",
            if (status.serviceRunning) "Running" else "Idle",
            if (status.locationUpdatesActive) "Location updates active" else "No active location updates",
            state = if (status.serviceRunning) StatusTone.Good else StatusTone.Neutral
        )
        StatusCard(
            "Activity",
            listOfNotNull(status.currentActivity, status.currentTransition).joinToString(" · ").ifBlank { "No activity yet" },
            formatStatusTime(status.lastActivityAtMillis, now),
            state = if (status.currentActivity != null) StatusTone.Good else StatusTone.Neutral
        )
        StatusCard(
            "Last Location",
            if (status.lastLocationLat != null && status.lastLocationLon != null) {
                "%.5f, %.5f".format(status.lastLocationLat, status.lastLocationLon)
            } else "No location yet",
            formatStatusTime(status.lastLocationAtMillis, now),
            state = if (status.lastLocationLat != null && status.lastLocationLon != null) {
                StatusTone.Good
            } else {
                StatusTone.Neutral
            }
        )
        val statusBatteryPercent = status.batteryPercent
        StatusCard(
            "iPhone Battery",
            statusBatteryPercent?.let { "$it%" } ?: "Unknown",
            listOfNotNull(
                status.batteryCharging?.let { if (it) "Charging" else "Not charging" },
                status.batteryExternalPower?.let { if (it) "External power" else "Battery power" },
                status.batteryMessage,
                formatStatusTime(status.batteryAtMillis, now).takeIf { it != "Never" },
            ).joinToString(" · ").ifBlank { "Waiting for battery status" },
            state = when {
                statusBatteryPercent == null -> StatusTone.Neutral
                statusBatteryPercent < 20 && status.batteryCharging != true -> StatusTone.Bad
                else -> StatusTone.Good
            }
        )
    }
}

private val statusClient = OkHttpClient()

data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
    val externalPower: Boolean,
)

suspend fun fetchBatteryStatus(context: android.content.Context) {
    withContext(Dispatchers.IO) {
        val prefs = SharedPreferencesProvider(context)
        if (prefs.forwardUrl.isBlank() || prefs.forwardToken.isBlank()) {
            AppStatus.setBattery(null, null, null, "Endpoint not configured")
            return@withContext
        }
        val url = "${prefs.forwardUrl}/battery".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("token", prefs.forwardToken)
            ?.build()
        if (url == null) {
            AppStatus.setBattery(null, null, null, "Invalid endpoint URL")
            return@withContext
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("skip_zrok_interstitial", "1")
            .get()
            .build()
        try {
            statusClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppStatus.setBattery(null, null, null, "HTTP ${response.code}")
                    return@withContext
                }
                val battery = parseBatteryStatus(response.body.string())
                AppStatus.setBattery(battery.percent, battery.charging, battery.externalPower)
            }
        } catch (e: Exception) {
            AppStatus.setBattery(null, null, null, e.message ?: "Battery request failed")
        }
    }
}

fun parseBatteryStatus(body: String): BatteryStatus {
    val root = JSONObject(body)
    if (!root.optBoolean("ok")) {
        throw IOException(root.optString("message").ifBlank { "Battery endpoint failed" })
    }
    return BatteryStatus(
        percent = root.getInt("batteryPercent"),
        charging = root.optBoolean("charging"),
        externalPower = root.optBoolean("externalPower"),
    )
}

@Composable
fun Logs(modifier: Modifier = Modifier) {
    val currentLogs by FileLogger.logFlow.collectAsState(initial = FileLogger.getLog())
    val scrollState = rememberScrollState()
    val logs = remember(currentLogs) { parseLogLines(currentLogs.orEmpty()) }

    LaunchedEffect(currentLogs) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .scrollBar(scrollState, color = MaterialTheme.colorScheme.primary)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Logs", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No logs available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.forEach {
                        Text(
                            text = "${it.timestamp}  ${it.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = logColor(it.message)
                        )
                    }
                }
            }
        }
    }
}

data class ParsedLogLine(val timestamp: String, val message: String)

fun parseLogLines(log: String): List<ParsedLogLine> =
    log.lineSequence()
        .mapNotNull { line ->
            val parts = line.split(" — ", limit = 2)
            if (parts.size == 2) ParsedLogLine(parts[0], parts[1]) else null
        }
        .toList()

fun formatStatusTime(millis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    if (millis <= 0L) "Never" else relativeTime(millis / 1000, nowMillis)

enum class StatusTone { Good, Bad, Neutral }

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    good: Boolean? = null,
    state: StatusTone = good?.let { if (it) StatusTone.Good else StatusTone.Bad } ?: StatusTone.Neutral,
) {
    val color = when (state) {
        StatusTone.Good -> Color(0xFF2E7D32)
        StatusTone.Bad -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, color = color)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun logColor(message: String): Color = when {
    message.contains("[ERROR]") -> MaterialTheme.colorScheme.error
    message.contains("[WARNING]") -> Color(0xFFF57C00)
    message.contains("[INFO]") -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
    }

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ForwardingSettings()
        AppearanceSettings()
        PermissionsStatus()
        Button(
            onClick = { resetLocation(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Send Current Location")
        }
    }
}

@Composable
private fun AppearanceSettings() {
    val context = LocalContext.current
    val selected by AppStatus.themeMode.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = {
                        SharedPreferencesProvider(context).themeMode = mode
                        AppStatus.setThemeMode(mode)
                        // Widgets read the pref at render time, so they need a nudge.
                        MapWidget.requestUpdate(context)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                ) {
                    Text(mode.label)
                }
            }
        }
        Text(
            text = "Applies to the app, the map tiles and every widget.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Normalizes user input into a canonical base URL, or returns null if invalid.
 * Adds https:// when the scheme is missing and strips trailing slashes and a
 * trailing /set (the path is appended automatically when forwarding).
 */
fun normalizeBaseUrl(input: String): String? {
    var s = input.trim()
    if (s.isBlank()) return null
    if (!s.contains("://")) s = "https://$s"
    s = s.trimEnd('/').removeSuffix("/set").trimEnd('/')
    return s.toHttpUrlOrNull()?.toString()?.trimEnd('/')
}

fun maskToken(token: String): String =
    if (token.length > 8) "••••••••" + token.takeLast(4) else "••••••••"

@Composable
fun ForwardingSettings() {
    val context = LocalContext.current
    val prefs = remember { SharedPreferencesProvider(context) }

    var savedUrl by remember { mutableStateOf(prefs.forwardUrl) }
    var savedToken by remember { mutableStateOf(prefs.forwardToken) }
    var editing by remember { mutableStateOf(savedUrl.isBlank()) }
    var urlInput by remember { mutableStateOf(savedUrl) }
    var tokenInput by remember { mutableStateOf(savedToken) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Forwarding Endpoint", style = MaterialTheme.typography.titleMedium)

            if (editing) {
                val normalizedUrl = normalizeBaseUrl(urlInput)
                val urlInvalid = urlInput.isNotBlank() && normalizedUrl == null

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("https://example.com") },
                    supportingText = {
                        Text(
                            if (urlInvalid) "Enter a valid http(s) URL"
                            else "Locations are sent to <base URL>/set"
                        )
                    },
                    isError = urlInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token") },
                    supportingText = { Text("Sent as the \"token\" query parameter") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = {
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            GmsBarcodeScanning.getClient(context, options).startScan()
                                .addOnSuccessListener { barcode ->
                                    barcode.rawValue?.trim()?.takeIf { it.isNotBlank() }
                                        ?.let { tokenInput = it }
                                }
                                .addOnFailureListener {
                                    FileLogger.e("QR scan failed: ${it.message}")
                                }
                        }) {
                            Text("Scan")
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            savedUrl = normalizedUrl ?: return@Button
                            val previousUrl = prefs.forwardUrl
                            val previousToken = prefs.forwardToken
                            savedToken = tokenInput.trim()
                            prefs.forwardUrl = savedUrl
                            prefs.forwardToken = savedToken
                            if (savedUrl != previousUrl || savedToken != previousToken) {
                                prefs.friendsCache = ""
                            }
                            urlInput = savedUrl
                            tokenInput = savedToken
                            editing = false
                        },
                        enabled = normalizedUrl != null && tokenInput.isNotBlank()
                    ) {
                        Text("Save")
                    }
                    if (savedUrl.isNotBlank()) {
                        TextButton(onClick = {
                            urlInput = savedUrl
                            tokenInput = savedToken
                            editing = false
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            } else {
                SettingValue("Base URL", savedUrl.ifBlank { "Not set" })
                SettingValue("Token", if (savedToken.isBlank()) "Not set" else maskToken(savedToken))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }) {
                        Text("Edit")
                    }
                    OutlinedButton(onClick = {
                        prefs.forwardUrl = ""
                        prefs.forwardToken = ""
                        prefs.friendsCache = ""
                        savedUrl = ""
                        savedToken = ""
                        urlInput = ""
                        tokenInput = ""
                        editing = true
                    }) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun PermissionsStatus() {
    val permissions = listOf(
        Pair("Notifications", Manifest.permission.POST_NOTIFICATIONS),
        Pair(
            "Activity Recognition",
            Manifest.permission.ACTIVITY_RECOGNITION
        ),
        Pair("Location", Manifest.permission.ACCESS_FINE_LOCATION),
        Pair("Contacts (friend names)", Manifest.permission.READ_CONTACTS),
        Pair(
            "Background Location",
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            permissions.forEach { (label, permission) ->
                PermissionItem(label = label, permission = permission)
            }
        }
    }
}

@Composable
fun PermissionItem(label: String, permission: String?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            permission != null && ContextCompat.checkSelfPermission(
                context, permission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = label)
            Text(
                text = when {
                    permission == null -> "Not required"
                    granted -> "Granted"
                    else -> "Denied"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    permission == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    granted -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }

        if (permission != null && !granted) {
            val activity = context as? Activity
            // Once denied with "Don't ask again", the request dialog no longer
            // shows; offer the app settings page instead.
            val permanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { launcher.launch(permission) }) {
                    Text("Request")
                }
                if (permanentlyDenied) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }) {
                        Text("Settings")
                    }
                }
            }
        }
    }
}
