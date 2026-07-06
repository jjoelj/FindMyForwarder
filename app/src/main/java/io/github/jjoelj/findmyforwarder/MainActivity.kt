package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.jjoelj.findmyforwarder.ui.theme.FindMyForwarderTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LocationUpdatesForegroundService.isRunning()) {
            startActivityRecognition(this)
        }

        setContent {
            FindMyForwarderTheme {
                FindMyForwarderApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun FindMyForwarderApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

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
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
            when (currentDestination) {
                AppDestinations.HOME -> Logs(contentModifier)
                AppDestinations.SETTINGS -> SettingsScreen(contentModifier)
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.home_24px),
    SETTINGS("Settings", R.drawable.settings_24px),
}

@Composable
fun Logs(modifier: Modifier = Modifier) {
    val currentLogs by FileLogger.logFlow.collectAsState(initial = FileLogger.getLog())
    val scrollState = rememberScrollState()

    LaunchedEffect(currentLogs) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .scrollBar(scrollState, color = MaterialTheme.colorScheme.primary)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = currentLogs ?: "No logs available.",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            lineHeight = 10.sp
        )
    }
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
        PermissionsStatus()
        Button(
            onClick = { resetLocation(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Reset to Current Location")
        }
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
                            savedToken = tokenInput.trim()
                            prefs.forwardUrl = savedUrl
                            prefs.forwardToken = savedToken
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
