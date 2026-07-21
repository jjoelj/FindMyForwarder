package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.SystemClock
import android.view.MotionEvent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAP_ANIMATION_DURATION_MS = 450L

// ponytail: span cap approximates "what fits on screen at a useful zoom"; derive from
// screen size + zoom math if this feels wrong on tablets
private const val MAX_FIT_SPAN_DEGREES = 60.0

private const val EDGE_AVATAR_MIN_SIZE_PX = 68

// Avatars scale with zoom on a ramp rather than stepping at a threshold. Bitmaps are
// always rendered at one resolution and scaled into place — letting the drawn size drive
// the render size instead would mint a new bitmap per zoom frame and thrash the cache.
private const val AVATAR_ICON_RENDER_PX = 192
private const val AVATAR_SIZE_MIN_PX = 144f
private const val AVATAR_SIZE_MAX_PX = 192f
private const val AVATAR_ZOOM_MIN = 12.0
private const val AVATAR_ZOOM_MAX = 16.0

// Time-based rather than a per-frame fraction: a fraction of the remaining gap is
// exponential decay, which eases out but never eases in, and its speed rides on the
// frame rate. These are real durations with a cubic in-out curve.
private const val SIZE_EASE_MS = 140L
private const val OFFSET_EASE_MS = 260L

/** Cubic ease-in-out: accelerates out of the old position, decelerates into the new. */
private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t
    else 1f - ((-2f * t + 2f).let { it * it * it }) / 2f

private fun avatarBaseSize(zoom: Double): Int {
    val t = ((zoom - AVATAR_ZOOM_MIN) / (AVATAR_ZOOM_MAX - AVATAR_ZOOM_MIN)).coerceIn(0.0, 1.0)
    return (AVATAR_SIZE_MIN_PX + (AVATAR_SIZE_MAX_PX - AVATAR_SIZE_MIN_PX) * t).roundToInt()
}

// Margin leaves room for the direction arrow drawn outside the avatar.
private const val EDGE_AVATAR_MARGIN_PX = 28
private const val EDGE_ARROW_LENGTH_PX = 22f

// Translucent so edge hints don't read as actual positions on the map, but only just —
// they already shrink with distance, and on a dark basemap the dimmed avatar plus a
// heavy fade left them too faint to pick out.
private const val EDGE_INDICATOR_MAX_ALPHA = 220

private const val MY_LOCATION_PULSE_MS = 2000L

// Edge puck is smaller than the onscreen dot so it reads as an indicator, not a fix. The
// dot is drawn as a fixed fraction of the ring so both shrink together on the way out.
private const val MY_LOCATION_RADIUS = 28f
private const val MY_LOCATION_EDGE_RADIUS = 22f
private const val MY_LOCATION_DOT_RATIO = 20f / 28f
private const val MY_LOCATION_ARROW_GAP = 6f
private const val SHEET_FLING_VELOCITY_THRESHOLD = 900f
private const val FRIENDS_LIST_POLL_INTERVAL_MILLIS = 60_000L
private const val FRIENDS_AUTO_REFRESH_DELAY_MILLIS = 10_000L
private const val ACTIVE_FRIEND_FETCH_INTERVAL_MILLIS = 15_000L

data class Friend(
    val handle: String,
    val lat: Double?,
    val lon: Double?,
    val address: String?,
    val fullAddress: String?,
    val timestamp: Long,
    val valid: Boolean,
    val name: String? = null,
    val photoUri: String? = null,
) {
    val hasLocation get() = valid && lat != null && lon != null
}

/** Phone handles keep a leading + and digits only; emails compare lowercased. */
fun normalizeHandle(handle: String): String {
    val t = handle.trim()
    return if ("@" in t) t.lowercase()
    else (if (t.startsWith("+")) "+" else "") + t.filter { it.isDigit() }
}

fun parseFriends(body: String): List<Friend> {
    val root = JSONObject(body)
    if (!root.optBoolean("ok")) {
        throw IOException(root.optString("message").ifBlank { "Server reported an error" })
    }
    val arr = root.optJSONArray("friends") ?: return emptyList()
    return List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        fun str(key: String) = if (o.isNull(key)) null else o.getString(key)
        // Coords but no address means the server geocoded nothing; log the raw entry so
        // it's clear this is their bug, not ours.
        if (!o.isNull("lat") && str("address").isNullOrBlank() && str("fullAddress").isNullOrBlank()) {
            FileLogger.w("Server sent coords with no address: $o")
        }
        Friend(
            handle = o.getString("handle"),
            lat = if (o.isNull("lat")) null else o.getDouble("lat"),
            lon = if (o.isNull("lon")) null else o.getDouble("lon"),
            address = str("address"),
            fullAddress = str("fullAddress"),
            timestamp = o.optLong("timestamp"),
            valid = o.optBoolean("valid"),
        )
    }
}

/** Same person can appear under several handles/devices; keep the best fix each. */
fun dedupeFriends(friends: List<Friend>): List<Friend> =
    friends.groupBy { it.name ?: normalizeHandle(it.handle) }
        .map { (_, entries) ->
            entries.sortedWith(compareBy({ it.valid }, { it.timestamp })).last()
        }
        .sortedBy { (it.name ?: it.handle).lowercase() }

private data class ContactInfo(val name: String?, val photoUri: String?)

private fun resolveContact(context: Context, handle: String): ContactInfo = try {
    val n = normalizeHandle(handle)
    val email = "@" in n
    val uri = if (email) {
        Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(n)
        )
    } else {
        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
    }
    val idColumn =
        if (email) ContactsContract.Data.CONTACT_ID else ContactsContract.PhoneLookup._ID
    context.contentResolver.query(
        uri,
        arrayOf(
            idColumn,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI
        ),
        null, null, null
    )?.use { c ->
        if (c.moveToFirst()) {
            val nickname = contactNickname(context, c.getLong(0))
            ContactInfo(nickname ?: c.getString(1), c.getString(2))
        } else ContactInfo(null, null)
    } ?: ContactInfo(null, null)
} catch (e: Exception) {
    FileLogger.w("Contact lookup failed for $handle: ${e.message}")
    ContactInfo(null, null)
}

private fun contactNickname(context: Context, contactId: Long): String? =
    context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        ),
        null
    )?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }

fun relativeTime(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val atMillis = epochSeconds * 1000
    return if (nowMillis - atMillis < 60_000) "Now"
    else DateUtils
        .getRelativeTimeSpanString(atMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS)
        .toString()
}

fun distanceMeters(from: Location, lat: Double, lon: Double): Float {
    val results = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, lat, lon, results)
    return results[0]
}

/** Crow-flies distance, formatted like iOS ("0.3 mi"). */
fun formatMiles(from: Location, lat: Double, lon: Double): String {
    val miles = distanceMeters(from, lat, lon) / 1609.344
    return when {
        miles < 0.1 -> "nearby"
        miles < 10 -> "%.1f mi".format(miles)
        else -> "%,d mi".format(miles.roundToInt())
    }
}

// The iPhone refreshes its cache independently; /friends returns the latest server cache.
private val friendsClient = OkHttpClient.Builder()
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private val friendsRefreshClient = OkHttpClient.Builder()
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(50, TimeUnit.SECONDS)
    .build()

private fun friendsUrl(
    prefs: SharedPreferencesProvider,
    path: String,
    handle: String? = null,
) = "${prefs.forwardUrl}$path".toHttpUrlOrNull()?.newBuilder()
    ?.addQueryParameter("token", prefs.forwardToken)
    ?.apply {
        if (!handle.isNullOrBlank()) addQueryParameter("handle", handle)
    }
    ?.build()

fun refreshFailureMessage(body: String): String? {
    if (body.isBlank()) return null
    return try {
        val root = JSONObject(body)
        if (root.optBoolean("ok", true)) {
            null
        } else {
            val message = root.optString("message").ifBlank { "Server reported an error" }
            val handle = root.optString("handle").takeIf { it.isNotBlank() }
            if (handle == null) message else "$message: $handle"
        }
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchFriends(context: Context, handle: String? = null): List<Friend> = withContext(Dispatchers.IO) {
    val prefs = SharedPreferencesProvider(context)

    val url = friendsUrl(prefs, "/friends", handle)
        ?: throw IOException("Invalid base URL; check Settings")

    val request = Request.Builder()
        .url(url)
        .addHeader("skip_zrok_interstitial", "1")
        .get()
        .build()

    friendsClient.newCall(request).execute().use { response ->
        when {
            response.code == 403 -> throw IOException("Invalid token")
            !response.isSuccessful -> throw IOException("HTTP ${response.code}")
        }
        val body = response.body.string()
        val friends = parseFriends(body)
        if (handle == null) {
            prefs.friendsCache = body
            prefs.lastFriendsUpdatedAtMillis = System.currentTimeMillis()
            MapWidget.requestUpdate(context)
        }
        FileLogger.i(
            "Fetched ${friends.size} friends (${friends.count { it.hasLocation }} with location)"
        )
        resolveAndDedupe(context, friends)
    }
}

suspend fun refreshFriends(context: Context, handle: String? = null): List<Friend> = withContext(Dispatchers.IO) {
    val prefs = SharedPreferencesProvider(context)

    val url = friendsUrl(prefs, "/friends/refresh", handle)
        ?: throw IOException("Invalid base URL; check Settings")

    val request = Request.Builder()
        .url(url)
        .addHeader("skip_zrok_interstitial", "1")
        .get()
        .build()

    friendsRefreshClient.newCall(request).execute().use { response ->
        if (response.code == 403) throw IOException("Invalid token")
        if (response.code == 409) {
            FileLogger.i("Friends location refresh already in progress")
            return@withContext emptyList()
        }
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

        val body = response.body.string()
        refreshFailureMessage(body)?.let { throw IOException(it) }
        val friends = parseFriends(body)
        if (handle == null) {
            prefs.friendsCache = body
            prefs.lastFriendsUpdatedAtMillis = System.currentTimeMillis()
            MapWidget.requestUpdate(context)
        }
        val target = handle ?: "all friends"
        FileLogger.i(
            "Refreshed $target (${friends.size} friends, ${friends.count { it.hasLocation }} with location)"
        )
        resolveAndDedupe(context, friends)
    }
}

/** Last successful response, re-parsed; empty if none or unreadable. */
suspend fun loadCachedFriends(context: Context): List<Friend> = withContext(Dispatchers.IO) {
    val body = SharedPreferencesProvider(context).friendsCache
    if (body.isBlank()) return@withContext emptyList()
    try {
        resolveAndDedupe(context, parseFriends(body))
    } catch (e: Exception) {
        FileLogger.w("Discarding unreadable friends cache: ${e.message}")
        emptyList()
    }
}

private fun resolveAndDedupe(context: Context, friends: List<Friend>): List<Friend> {
    val canReadContacts = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
    val named = if (canReadContacts) {
        friends.map {
            val info = resolveContact(context, it.handle)
            it.copy(name = info.name, photoUri = info.photoUri)
        }
    } else friends
    return dedupeFriends(named)
}

@Composable
fun FriendsScreen(modifier: Modifier = Modifier, resetRequest: Int = 0) {
    val context = LocalContext.current
    val prefs = remember { SharedPreferencesProvider(context) }
    val configured = prefs.forwardUrl.isNotBlank() && prefs.forwardToken.isNotBlank()
    val scope = rememberCoroutineScope()

    var friends by remember { mutableStateOf(emptyList<Friend>()) }
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFriend by remember { mutableStateOf<Friend?>(null) }
    var mapSelectionRequest by remember { mutableStateOf(0) }
    var mapSheetSnapRequest by remember { mutableStateOf(0) }
    var mapMyLocationRequest by remember { mutableStateOf(0) }
    var mapFitAllRequest by remember { mutableStateOf(0) }
    var refreshedThisVisit by remember { mutableStateOf(false) }
    val refreshingHandles = remember { mutableStateMapOf<String, Boolean>() }
    val status by AppStatus.state.collectAsState()
    // Prefs hold the durable "last successful push" time; the status flow just
    // triggers a re-read whenever a new post completes.
    val lastPushedAtMillis = remember(status.lastPostAtMillis) { prefs.lastPushedAtMillis }
    val myLocation = remember(status.lastLocationLat, status.lastLocationLon) {
        val lat = status.lastLocationLat
        val lon = status.lastLocationLon
        if (lat == null || lon == null) {
            null
        } else {
            Location("last_sent").apply {
                latitude = lat
                longitude = lon
            }
        }
    }

    // No last known location: have the service fetch one now, which also stores
    // it (prefs + AppStatus) and forwards it.
    LaunchedEffect(Unit) {
        if (myLocation == null) resetLocation(context)
    }

    fun applyFetchedFriends(fetchedFriends: List<Friend>) {
        friends = fetchedFriends
        loadedOnce = true
        val selected = selectedFriend ?: return
        val normalizedHandle = normalizeHandle(selected.handle)
        fetchedFriends.firstOrNull { normalizeHandle(it.handle) == normalizedHandle }?.let { updated ->
            selectedFriend = updated.copy(
                name = updated.name ?: selected.name,
                photoUri = updated.photoUri ?: selected.photoUri,
            )
        }
    }

    fun loadFriends(forceRefresh: Boolean) {
        if (!configured || loading) return
        if (forceRefresh) refreshedThisVisit = true
        scope.launch {
            loading = true
            error = null
            try {
                val fetchedFriends = if (forceRefresh) {
                    refreshFriends(context).ifEmpty { fetchFriends(context) }
                } else {
                    fetchFriends(context)
                }
                applyFetchedFriends(fetchedFriends)
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                FileLogger.e("Failed to fetch friends: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        loadFriends(forceRefresh = true)
    }

    // Poll only while this screen is actually visible; leaving the tab cancels the
    // effect and backgrounding the app suspends it via repeatOnLifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(configured, lifecycleOwner) {
        if (!configured) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                delay(FRIENDS_AUTO_REFRESH_DELAY_MILLIS.milliseconds)
                if (!refreshedThisVisit) refresh()
            }
            while (isActive) {
                delay(FRIENDS_LIST_POLL_INTERVAL_MILLIS.milliseconds)
                if (loading) continue
                try {
                    applyFetchedFriends(fetchFriends(context))
                } catch (e: Exception) {
                    FileLogger.w("Friends list poll failed: ${e.message}")
                }
            }
        }
    }

    var contactsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val contactsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            contactsGranted = it
            if (it) loadFriends(forceRefresh = false)
        }

    LaunchedEffect(Unit) {
        val cached = loadCachedFriends(context)
        if (friends.isEmpty() && cached.isNotEmpty()) {
            friends = cached
            loadedOnce = true
        }
    }

    // New observers get brought up to the current state, so this fires on first
    // composition, on switching back to this tab, and on returning to the app.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadFriends(forceRefresh = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sortedFriends = remember(friends, myLocation) {
        val loc = myLocation ?: return@remember friends
        friends.sortedBy {
            if (it.hasLocation) distanceMeters(loc, it.lat!!, it.lon!!)
            else Float.MAX_VALUE
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sheetMaxHeightPx = with(density) { maxHeight.toPx() }
        val halfOffset = sheetMaxHeightPx / 2f
        val twoThirdOffset = sheetMaxHeightPx / 3f
        val fullOffset = 0f
        val collapsedOffset = (sheetMaxHeightPx - with(density) { 72.dp.toPx() }).coerceAtLeast(0f)
        val sheetOffset = remember { Animatable(halfOffset) }
        val mapCenterOffset = remember { Animatable(0f) }
        // Derived rather than snapshotted on settle: reading sheetOffset here recomposes
        // every drag frame, so the map's bottom inset — and the edge pins clamped against
        // it — track the sheet live instead of jumping when it stops.
        val mapSheetVisibleHeightPx = sheetMaxHeightPx - sheetOffset.value
        val sheetAnchors = remember(sheetMaxHeightPx, collapsedOffset) {
            listOf(fullOffset, twoThirdOffset, halfOffset, collapsedOffset)
        }
        // Visible from the half position all the way down to collapsed; only a
        // taller sheet (which covers the map) hides them.
        val showMyLocationButton = myLocation != null &&
            sheetOffset.value > halfOffset - with(density) { 3.dp.toPx() }
        // The height itself is now derived; this only asks the map to re-frame once the
        // sheet has settled on an anchor.
        fun updateMapSheetHeight(@Suppress("UNUSED_PARAMETER") offset: Float) {
            mapSheetSnapRequest++
        }
        fun targetMapCenterOffset(offset: Float): Float =
            -(((sheetMaxHeightPx - offset).coerceAtLeast(76f) + 24f) / 2f)

        fun animateSheetTo(offset: Float) {
            scope.launch {
                val targetMapOffset = targetMapCenterOffset(offset)
                launch { mapCenterOffset.animateTo(targetMapOffset, spring()) }
                sheetOffset.animateTo(offset, spring())
                updateMapSheetHeight(offset)
            }
        }
        fun snapSheetToNearest(velocity: Float = 0f) {
            val currentAnchor = sheetAnchors.minBy { (sheetOffset.value - it).absoluteValue }
            val currentIndex = sheetAnchors.indexOf(currentAnchor)
            val target = when {
                velocity < -SHEET_FLING_VELOCITY_THRESHOLD ->
                    sheetAnchors[(currentIndex - 1).coerceAtLeast(0)]
                velocity > SHEET_FLING_VELOCITY_THRESHOLD ->
                    sheetAnchors[(currentIndex + 1).coerceAtMost(sheetAnchors.lastIndex)]
                else -> sheetAnchors.minBy { (sheetOffset.value - it).absoluteValue }
            }
            animateSheetTo(target)
        }
        fun selectFriend(friend: Friend) {
            selectedFriend = friend
            mapSelectionRequest++
            mapSheetSnapRequest++
            animateSheetTo(halfOffset)
        }
        fun clearSelection(keepSheetPosition: Boolean = false, openDefault: Boolean = false) {
            selectedFriend = null
            mapSelectionRequest++
            if (!keepSheetPosition) {
                mapSheetSnapRequest++
                scope.launch { mapCenterOffset.animateTo(0f, spring()) }
            }
            if (!keepSheetPosition) {
                animateSheetTo(if (openDefault) halfOffset else fullOffset)
            }
        }

        LaunchedEffect(sheetMaxHeightPx) {
            sheetOffset.snapTo(halfOffset)
            mapCenterOffset.snapTo(targetMapCenterOffset(halfOffset))
            updateMapSheetHeight(halfOffset)
        }

        LaunchedEffect(resetRequest, halfOffset) {
            if (resetRequest > 0) {
                clearSelection(openDefault = true)
            }
        }

        LaunchedEffect(selectedFriend?.handle) {
            val selectedHandle = selectedFriend?.handle ?: return@LaunchedEffect
            val normalizedHandle = normalizeHandle(selectedHandle)
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    val fetchStartedAt = System.currentTimeMillis()
                    refreshingHandles[selectedHandle] = true
                    try {
                        val fetchedFriends = refreshFriends(context, handle = selectedHandle)
                            .ifEmpty { fetchFriends(context, handle = selectedHandle) }
                        fetchedFriends.firstOrNull { normalizeHandle(it.handle) == normalizedHandle }?.let { updated ->
                            val current = selectedFriend
                            if (current == null || normalizeHandle(current.handle) != normalizedHandle) {
                                return@let
                            }
                            val updatedWithMetadata = updated.copy(
                                name = updated.name ?: current.name,
                                photoUri = updated.photoUri ?: current.photoUri,
                            )
                            if (updatedWithMetadata.lat != current.lat || updatedWithMetadata.lon != current.lon) {
                                mapSelectionRequest++
                            }
                            selectedFriend = updatedWithMetadata
                            var replaced = false
                            friends = friends.map { friend ->
                                if (normalizeHandle(friend.handle) != normalizedHandle) {
                                    friend
                                } else {
                                    replaced = true
                                    updatedWithMetadata.copy(
                                        name = updatedWithMetadata.name ?: friend.name,
                                        photoUri = updatedWithMetadata.photoUri ?: friend.photoUri,
                                    )
                                }
                            }
                            if (!replaced) {
                                friends = friends + updatedWithMetadata
                            }
                        }
                    } catch (e: Exception) {
                        FileLogger.w("Failed to update selected friend from cache: ${e.message}")
                    } finally {
                        refreshingHandles.remove(selectedHandle)
                    }
                    val elapsedMillis = System.currentTimeMillis() - fetchStartedAt
                    val remainingDelay = ACTIVE_FRIEND_FETCH_INTERVAL_MILLIS - elapsedMillis
                    if (remainingDelay > 0) {
                        delay(remainingDelay.milliseconds)
                    }
                }
            }
        }

        FriendsMap(
            friends = sortedFriends,
            myLocation = myLocation,
            selectedFriend = selectedFriend,
            selectionRequest = mapSelectionRequest,
            sheetSnapRequest = mapSheetSnapRequest,
            myLocationRequest = mapMyLocationRequest,
            fitAllRequest = mapFitAllRequest,
            sheetVisibleHeightPx = mapSheetVisibleHeightPx,
            mapCenterOffsetY = mapCenterOffset.value.roundToInt(),
            controlsVisible = showMyLocationButton,
            onFriendSelected = { selectFriend(it) },
            modifier = Modifier.fillMaxSize()
        )

        // Soft scrim behind the status bar so its icons stay legible over the map.
        Spacer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        )
                    )
                )
        )

        AnimatedVisibility(
            visible = showMyLocationButton,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 12.dp,
                    // Ride just above the sheet's current top edge.
                    bottom = with(density) {
                        (sheetMaxHeightPx - sheetOffset.value).coerceAtLeast(0f).toDp()
                    } + 12.dp
                )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Clickable Surface rather than a nested IconButton: IconButton's ripple
                // is an unbounded 20dp circle, so it never filled these rounded squares.
                Surface(
                    onClick = {
                        clearSelection(keepSheetPosition = true)
                        mapFitAllRequest++
                    },
                    modifier = Modifier
                        .size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.zoom_out_map_24px),
                            contentDescription = "Show everyone"
                        )
                    }
                }
                Surface(
                    onClick = {
                        clearSelection(keepSheetPosition = true)
                        mapMyLocationRequest++
                    },
                    modifier = Modifier
                        .size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.navigation_24px),
                            contentDescription = "Show my location"
                        )
                    }
                }
            }
        }

        FriendsBottomSheet(
            configured = configured,
            loading = loading,
            contactsGranted = contactsGranted,
            loadedOnce = loadedOnce,
            error = error,
            friends = sortedFriends,
            selectedFriend = selectedFriend,
            myLocation = myLocation,
            lastPushedAtMillis = lastPushedAtMillis,
            refreshingHandles = refreshingHandles,
            hiddenBottomPaddingPx = sheetOffset.value,
            onRefresh = { refresh() },
            onRequestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
            onFriendSelected = { selectFriend(it) },
            onClearSelection = { clearSelection(keepSheetPosition = true) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, sheetOffset.value.roundToInt()) }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val next = (sheetOffset.value + delta).coerceIn(fullOffset, collapsedOffset)
                            sheetOffset.snapTo(next)
                            mapCenterOffset.snapTo(targetMapCenterOffset(next))
                        }
                    },
                    onDragStopped = { velocity -> snapSheetToNearest(velocity) }
                )
        )
    }
}

@Composable
private fun FriendsMap(
    friends: List<Friend>,
    myLocation: Location?,
    selectedFriend: Friend?,
    selectionRequest: Int,
    sheetSnapRequest: Int,
    myLocationRequest: Int,
    fitAllRequest: Int,
    sheetVisibleHeightPx: Float,
    mapCenterOffsetY: Int,
    controlsVisible: Boolean,
    onFriendSelected: (Friend) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current)

    // OSM only ships light tiles, so the dark basemap is a colour matrix rather than a
    // second tile server. Applied in update so flipping the setting re-themes the live
    // map without rebuilding the view.
    val darkMap = AppStatus.themeMode.collectAsState().value.isDark()

    // Keyed on the theme: marker bitmaps are cached inside the controller, so a flip has
    // to rebuild it or every pin keeps the old palette.
    val mapController = remember(darkMap) {
        FriendsMapController(
            context.applicationContext,
            mapMarkerBodyColor(darkMap),
            mapMarkerRingColor(darkMap),
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MapView(viewContext).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // osmdroid's built-in +/- buttons fade in on every gesture; pinch and the
                // in-app controls already cover zooming.
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 4.0
                maxZoomLevel = 20.0
                if (myLocation != null) {
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                } else {
                    controller.setZoom(5.0)
                }
            }
        },
        update = { mapView ->
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (darkMap) DARK_MAP_COLOR_FILTER else null
            )
            mapController.sync(
                mapView = mapView,
                friends = friends,
                myLocation = myLocation,
                selectedFriend = selectedFriend,
                selectionRequest = selectionRequest,
                sheetSnapRequest = sheetSnapRequest,
                myLocationRequest = myLocationRequest,
                fitAllRequest = fitAllRequest,
                sheetVisibleHeightPx = sheetVisibleHeightPx,
                mapCenterOffsetY = mapCenterOffsetY,
                topInsetPx = topInsetPx,
                controlsVisible = controlsVisible,
                onFriendSelected = onFriendSelected,
            )
        }
    )
}

private class FriendsMapController(
    private val context: Context,
    private val markerColor: Int,
    private val markerTextColor: Int,
) {
    private val friendsByHandle = mutableMapOf<String, Friend>()
    private val friendIconCache = mutableMapOf<String, BitmapDrawable>()
    private var myLocation: Location? = null
    private var lastFriendSignature = ""
    private var lastFriendsRef: List<Friend>? = null

    // Cluster membership is binary — a pin joining a pair drops to 0.57x its size in one
    // frame, and every change in member count steps again. No sizing formula can smooth
    // that, so the drawn size chases the target instead of snapping to it.
    private val faceSizeAnim = mutableMapOf<String, EaseState>()
    private var sizeAnimating = false

    private class EaseState(var cur: Float) {
        var start = cur
        var target = cur
        var startAt = 0L
    }

    /**
     * Retargets mid-flight from wherever the value currently is, so a merge interrupted
     * by another merge glides on rather than snapping back to a new start.
     */
    private fun ease(state: EaseState, target: Float, durationMs: Long, epsilon: Float = 0.5f): Float {
        val now = SystemClock.uptimeMillis()
        if (kotlin.math.abs(target - state.target) > epsilon) {
            state.start = state.cur
            state.target = target
            state.startAt = now
        }
        val elapsed = now - state.startAt
        if (elapsed >= durationMs) {
            state.cur = state.target
            return state.cur
        }
        val t = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        state.cur = state.start + (state.target - state.start) * easeInOutCubic(t)
        sizeAnimating = true
        return state.cur
    }

    /**
     * Eases the pin's displacement from where it would sit un-clustered, never its
     * absolute position. Panning moves the pin and its cluster centroid together, so the
     * displacement holds steady and nothing lags; only a merge or split changes it.
     */
    private val faceOffsetAnim = mutableMapOf<String, Array<EaseState>>()

    private fun easedOffset(handle: String, targetDx: Float, targetDy: Float): FloatArray {
        val s = faceOffsetAnim.getOrPut(handle) {
            arrayOf(EaseState(targetDx), EaseState(targetDy))
        }
        return floatArrayOf(
            ease(s[0], targetDx, OFFSET_EASE_MS),
            ease(s[1], targetDy, OFFSET_EASE_MS),
        )
    }

    /**
     * Eases the cluster's share of the pin size (1.0 alone, less as a face in a group),
     * never the absolute size. The zoom ramp and the offscreen shrink are already smooth
     * functions of the viewport, so they multiply through untouched and stay instant —
     * only a change in cluster membership actually animates.
     */
    /**
     * Slides a marker clear of the control column instead of teleporting it. The push
     * ramps with vertical proximity to the buttons, so a pin travelling down the left
     * edge eases out and back rather than popping sideways when it crosses their top.
     */
    private fun clearOfControls(x: Int, y: Int, halfSize: Int, rect: Rect?): Int {
        if (rect == null) return x
        val target = rect.right + halfSize + EDGE_AVATAR_MARGIN_PX
        if (x >= target) return x
        val dy = when {
            y < rect.top -> (rect.top - y).toFloat()
            y > rect.bottom -> (y - rect.bottom).toFloat()
            else -> 0f
        }
        val reach = (halfSize + EDGE_AVATAR_MARGIN_PX).toFloat()
        val nearness = (1f - dy / reach).coerceIn(0f, 1f)
        if (nearness <= 0f) return x
        return (x + (target - x) * nearness).roundToInt()
    }

    private fun easedClusterScale(handle: String, target: Float): Float {
        val s = faceSizeAnim.getOrPut(handle) { EaseState(target) }
        return ease(s, target, SIZE_EASE_MS, epsilon = 0.002f)
    }
    private var lastSelectedHandle: String? = null
    private var lastSelectionRequest = -1
    private var lastSheetSnapRequest = -1
    private var lastMyLocationRequest = -1
    private var lastFitAllRequest = -1
    private var lastBottomInset = -1
    private var lastTopInset = -1
    private var controlsVisible = false
    private var lastMapCenterOffsetY = Int.MIN_VALUE
    private var followMyLocation = false
    private var avatarOverlayInstalled = false
    private var onFriendSelected: (Friend) -> Unit = {}
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor }
    private val arrowPath = Path()
    private val myLocationDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt() }
    private val myLocationRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val myLocationPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt() }

    // Hit areas from the last draw: rect -> cluster members, in draw order.
    private val drawnClusters = mutableListOf<Pair<Rect, List<Friend>>>()

    fun sync(
        mapView: MapView,
        friends: List<Friend>,
        myLocation: Location?,
        selectedFriend: Friend?,
        selectionRequest: Int,
        sheetSnapRequest: Int,
        myLocationRequest: Int,
        fitAllRequest: Int,
        sheetVisibleHeightPx: Float,
        mapCenterOffsetY: Int,
        topInsetPx: Int,
        controlsVisible: Boolean,
        onFriendSelected: (Friend) -> Unit,
    ) {
        installAvatarOverlay(mapView)
        this.onFriendSelected = onFriendSelected
        this.controlsVisible = controlsVisible
        val bottomInset = sheetVisibleHeightPx.coerceAtLeast(76f).roundToInt() + 24
        // Padding is what osmdroid frames bounding boxes inside, so reserving the status
        // bar here keeps clusters and fit-all from packing pins up under it.
        val topInset = topInsetPx + 24
        val mappedFriends = friends.filter { it.hasLocation }
        // sync now runs every frame of a sheet drag, so skip rebuilding the signature
        // string when the caller handed us the same list instance it did last time.
        val friendSignature = if (friends === lastFriendsRef) lastFriendSignature else {
            mappedFriends.joinToString("|") {
                "${it.handle}:${it.lat}:${it.lon}:${it.name}:${it.photoUri}:${it.address}:${it.fullAddress}"
            }
        }
        lastFriendsRef = friends
        val friendsChanged = friendSignature != lastFriendSignature
        val selectionChanged = selectedFriend?.handle != lastSelectedHandle
        val selectionRequested = selectionRequest != lastSelectionRequest
        val sheetSnapRequested = sheetSnapRequest != lastSheetSnapRequest
        val myLocationRequested = myLocationRequest != lastMyLocationRequest
        val fitAllRequested = fitAllRequest != lastFitAllRequest
        val bottomInsetChanged = bottomInset != lastBottomInset || topInset != lastTopInset
        if (bottomInsetChanged) {
            mapView.setPadding(0, topInset, 0, bottomInset)
            lastBottomInset = bottomInset
            lastTopInset = topInset
        }
        if (mapCenterOffsetY != lastMapCenterOffsetY) {
            mapView.setMapCenterOffset(0, mapCenterOffsetY)
            lastMapCenterOffsetY = mapCenterOffsetY
        }
        if (friendsChanged) {
            friendsByHandle.clear()
            mappedFriends.forEach { friendsByHandle[it.handle] = it }
            friendIconCache.keys.removeAll { key -> key.substringBefore('|') !in friendsByHandle.keys }
            lastFriendSignature = friendSignature
        }
        val myLocationChanged = myLocation?.latitude != this.myLocation?.latitude ||
            myLocation?.longitude != this.myLocation?.longitude
        this.myLocation = myLocation

        if (selectedFriend?.hasLocation == true &&
            (selectionRequested || selectionChanged || sheetSnapRequested)
        ) {
            followMyLocation = false
            zoomToSelected(mapView, selectedFriend)
        } else if (fitAllRequested && lastFitAllRequest != -1) {
            followMyLocation = false
            zoomToFitAll(mapView, mappedFriends, myLocation)
        } else if (myLocationRequested && myLocation != null) {
            followMyLocation = true
            zoomToLocation(mapView, myLocation)
        } else if (followMyLocation && myLocation != null && myLocationChanged) {
            // Following: track new fixes at the user's current zoom.
            val point = GeoPoint(myLocation.latitude, myLocation.longitude)
            mapView.post { mapView.controller.animateTo(point) }
        }
        lastSelectedHandle = selectedFriend?.handle
        lastSelectionRequest = selectionRequest
        lastSheetSnapRequest = sheetSnapRequest
        lastMyLocationRequest = myLocationRequest
        lastFitAllRequest = fitAllRequest
        mapView.invalidate()
    }

    // All friend avatars are drawn by one overlay: at their true map position while
    // on screen, clamped to the screen edge (shrinking, translucent, with an arrow)
    // once their position moves off — one continuous pin, never two copies.
    // Avatars that would overlap merge into a row showing every face.
    private fun installAvatarOverlay(mapView: MapView) {
        if (avatarOverlayInstalled) return
        avatarOverlayInstalled = true
        mapView.overlays.add(0, object : Overlay() {
            override fun draw(canvas: AndroidCanvas, mapView: MapView, shadow: Boolean) {
                if (!shadow) drawFriendAvatars(canvas, mapView)
            }

            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                // Any manual map interaction ends follow mode; the button re-arms it.
                if (event.action == MotionEvent.ACTION_DOWN) followMyLocation = false
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
                val x = e.x.roundToInt()
                val y = e.y.roundToInt()
                val members = drawnClusters.lastOrNull { it.first.contains(x, y) }?.second
                    ?: return false
                if (members.size == 1) {
                    onFriendSelected(members[0])
                } else {
                    // Zoom in far enough to pull the merged group apart.
                    val box = BoundingBox.fromGeoPointsSafe(
                        members.map { GeoPoint(it.lat!!, it.lon!!) }
                    ).increaseByScale(1.6f)
                    mapView.zoomToBoundingBox(box, true, 64)
                }
                return true
            }
        })
    }

    private fun drawFriendAvatars(canvas: AndroidCanvas, mapView: MapView) {
        drawnClusters.clear()
        sizeAnimating = false
        val width = mapView.width
        // Treat the area under the bottom sheet and the status bar as offscreen too, so
        // edge-clamped pins cluster inside the visible strip rather than under them.
        val height = mapView.height - lastBottomInset.coerceAtLeast(0)
        val topBound = lastTopInset.coerceAtLeast(0)
        val baseSize = avatarBaseSize(mapView.zoomLevelDouble)
        if (width <= 0 || height <= topBound) return

        // Bottom-left control column, in the same coordinates as the pins. Mirrors the
        // Compose layout: 12dp inset, 48dp square buttons, 8dp apart, riding the sheet.
        val controlsRect = if (controlsVisible) {
            val d = mapView.resources.displayMetrics.density
            fun dp(v: Float) = (v * d).roundToInt()
            val left = dp(12f)
            val bottom = height - dp(12f)
            Rect(left, bottom - dp(48f * 2 + 8f), left + dp(48f), bottom)
        } else null
        val projection = mapView.projection ?: return

        // coerceIn that survives an inverted range (sheet dragged up can shrink the
        // visible strip below the margins) instead of throwing or hiding everything.
        fun clamp(v: Int, lo: Int, hi: Int): Int = if (lo <= hi) v.coerceIn(lo, hi) else (lo + hi) / 2

        class Item(
            val friend: Friend,
            val x: Int,
            val y: Int,
            val rawX: Int,
            val rawY: Int,
            val size: Int,
            val alpha: Int,
            /** Drawn position differs from the true one, so it needs a direction arrow. */
            val clamped: Boolean,
        )

        val point = Point()

        // Standard blue "you are here" dot with a pulsing halo, under the friend
        // pins. The timed invalidate keeps the pulse animating (~30fps) only while
        // the map is visible and a location exists.
        myLocation?.let { loc ->
            projection.toPixels(GeoPoint(loc.latitude, loc.longitude), point)
            val phase = (SystemClock.uptimeMillis() % MY_LOCATION_PULSE_MS).toFloat() /
                MY_LOCATION_PULSE_MS
            val offX = max(0, max(-point.x, point.x - width))
            val offY = max(0, max(topBound - point.y, point.y - height))

            // One drawing path for both states, with every quantity a continuous function
            // of how far off-view the fix is: no branch means nothing can pop at the
            // boundary. tEdge reaches the full edge look within a quarter viewport.
            val screensOut = hypot(offX.toDouble(), offY.toDouble()) /
                min(width, (height - topBound).coerceAtLeast(1))
            val tEdge = min(1.0, screensOut * 4).toFloat()
            val r = MY_LOCATION_RADIUS + (MY_LOCATION_EDGE_RADIUS - MY_LOCATION_RADIUS) * tEdge
            val margin = r.roundToInt() + EDGE_AVATAR_MARGIN_PX
            val ey = clamp(point.y, topBound + margin, height - margin)
            val ex = clearOfControls(
                clamp(point.x, margin, width - margin), ey, r.roundToInt(), controlsRect
            )

            myLocationPulsePaint.alpha = ((90 - 20 * tEdge) * (1f - phase)).roundToInt()
            val pulseReach = 70f - 36f * tEdge
            canvas.drawCircle(ex.toFloat(), ey.toFloat(), r + phase * pulseReach, myLocationPulsePaint)
            canvas.drawCircle(ex.toFloat(), ey.toFloat(), r, myLocationRingPaint)
            canvas.drawCircle(ex.toFloat(), ey.toFloat(), r * MY_LOCATION_DOT_RATIO, myLocationDotPaint)

            // Keyed on displacement, not on leaving the viewport: the arrow's whole job is
            // to say "the real fix is that way", which becomes true the instant clamping
            // moves the dot off its true point. Growing with the displacement means it
            // starts at zero size, so it appears without popping.
            val towardX = point.x - ex.toFloat()
            val towardY = point.y - ey.toFloat()
            val displaced = hypot(towardX.toDouble(), towardY.toDouble()).toFloat()
            if (displaced > 0.5f) {
                val arrowT = min(1f, displaced / (EDGE_ARROW_LENGTH_PX * 2f))
                val angleDeg =
                    Math.toDegrees(atan2(towardY.toDouble(), towardX.toDouble())).toFloat()
                // Base sits clear of the ring instead of overlapping it, so the arrow
                // reads as a separate marker. Tip stays inside the clamp margin.
                val base = r + MY_LOCATION_ARROW_GAP
                arrowPath.reset()
                arrowPath.moveTo(base + EDGE_ARROW_LENGTH_PX * arrowT, 0f)
                arrowPath.lineTo(base, -EDGE_ARROW_LENGTH_PX * 0.6f * arrowT)
                arrowPath.lineTo(base, EDGE_ARROW_LENGTH_PX * 0.6f * arrowT)
                arrowPath.close()
                canvas.withTranslation(ex.toFloat(), ey.toFloat()) {
                    rotate(angleDeg)
                    drawPath(arrowPath, myLocationDotPaint)
                }
            }
            mapView.postInvalidateDelayed(33L)
        }

        val items = mutableListOf<Item>()
        var selectedOnscreen: Item? = null
        friendsByHandle.values.forEach { friend ->
            projection.toPixels(GeoPoint(friend.lat!!, friend.lon!!), point)
            val offX = max(0, max(-point.x, point.x - width))
            val offY = max(0, max(topBound - point.y, point.y - height))
            val offscreen = offX > 0 || offY > 0

            // Shrink and fade with distance in viewports: still full size and opacity
            // at the moment the pin sticks to the edge, so the transition is seamless.
            val screensAway = hypot(offX.toDouble(), offY.toDouble()) /
                min(width, (height - topBound).coerceAtLeast(1))
            val size = (baseSize / (1.0 + screensAway)).roundToInt()
                .coerceAtLeast(EDGE_AVATAR_MIN_SIZE_PX)
            val alpha = 255 -
                ((255 - EDGE_INDICATOR_MAX_ALPHA) * min(1.0, screensAway * 2)).roundToInt()

            // Clamped into the band at all times rather than only once offscreen. A hard
            // clamp is a continuous function, so a pin drifting out of view slides to a
            // stop against the edge instead of sliding off and being pulled back — and it
            // can never be drawn half outside the canvas. The cost is that a pin within
            // one margin of the edge is nudged inward from its true point.
            val margin = size / 2 + EDGE_AVATAR_MARGIN_PX
            val y = clamp(point.y, topBound + margin, height - margin)
            // The map control column sits in this same band at the bottom-left, so
            // slide an edge hint clear of it rather than letting it hide underneath.
            val x = clearOfControls(
                clamp(point.x, margin, width - margin), y, size / 2, controlsRect
            )
            val item = Item(
                friend,
                x,
                y,
                point.x,
                point.y,
                size,
                alpha,
                clamped = x != point.x || y != point.y,
            )
            if (friend.handle == lastSelectedHandle && !offscreen) {
                selectedOnscreen = item // drawn last, on top, as the callout
            } else {
                items.add(item)
            }
        }

        val clusters = clusterByProximity(items, { it.x }, { it.y }, { it.size })

        // Every cluster — on-screen, offscreen, or mixed — is the same circular
        // group pin: one disc of normal pin size with all the faces inside (grid in
        // the inscribed square). On-screen it sits on the members' true centroid and
        // splits apart as zooming in stops them overlapping; offscreen it shrinks,
        // fades, sticks to the edge band, and gains a direction arrow — so a group
        // sliding off screen keeps its avatar, and nearby leavers just join it.
        clusters.forEach { cluster ->
            val size = cluster.maxOf { it.size }
            val alpha = cluster.maxOf { it.alpha }
            // Arrow follows displacement, not "left the viewport": a group parked against
            // the edge band is already lying about where its members are.
            val offscreenGroup = cluster.any { it.clamped }

            // Clamped unconditionally, same as the members, so the group disc can never be
            // drawn partly outside the canvas either.
            val margin = size / 2 + EDGE_AVATAR_MARGIN_PX
            val cx = clamp(cluster.sumOf { it.x } / cluster.size, margin, width - margin)
            val cy = clamp(
                cluster.sumOf { it.y } / cluster.size, topBound + margin, height - margin
            )
            val left = cx - size / 2
            val top = cy - size / 2

            // Fade the pin as one composite: disc, faces, and arrow render opaque
            // into a layer that carries the whole group's alpha, so their overlaps
            // don't stack into darker seams.
            val faded = alpha < 255
            if (faded) {
                val pad = EDGE_ARROW_LENGTH_PX + 4f
                canvas.saveLayerAlpha(
                    left - pad, top - pad, left + size + pad, top + size + pad, alpha
                )
            }
            avatarPaint.alpha = 255
            arrowPaint.alpha = 255

            if (cluster.size == 1) {
                val only = cluster[0]
                // Alone: full share of the pin size, so this eases back up after a split.
                val drawn = (size * easedClusterScale(only.friend.handle, 1f)).roundToInt()
                // Zero displacement for a lone pin, so a member leaving a cluster eases
                // back to its own position rather than snapping there.
                val off = easedOffset(
                    only.friend.handle, (cx - only.x).toFloat(), (cy - only.y).toFloat()
                )
                val dl = (only.x + off[0] - drawn / 2f).roundToInt()
                val dt = (only.y + off[1] - drawn / 2f).roundToInt()
                canvas.drawBitmap(
                    avatarIconFor(only.friend).bitmap,
                    null,
                    Rect(dl, dt, dl + drawn, dt + drawn),
                    avatarPaint
                )
            } else {
                val n = cluster.size
                layoutClusterFaces(
                    pinSize = size,
                    memberSizes = IntArray(n) { cluster[it].size },
                    rawX = IntArray(n) { cluster[it].rawX },
                    rawY = IntArray(n) { cluster[it].rawY },
                ).forEach { face ->
                    val member = cluster[face.member]
                    val drawn = (size * easedClusterScale(
                        member.friend.handle, face.size.toFloat() / size
                    )).roundToInt()
                    val off = easedOffset(
                        member.friend.handle,
                        (cx + face.x - member.x).toFloat(),
                        (cy + face.y - member.y).toFloat(),
                    )
                    val l = (member.x + off[0] - drawn / 2f).roundToInt()
                    val t = (member.y + off[1] - drawn / 2f).roundToInt()
                    canvas.drawBitmap(
                        avatarIconFor(member.friend).bitmap,
                        null,
                        Rect(l, t, l + drawn, t + drawn),
                        avatarPaint
                    )
                }
            }
            drawnClusters.add(
                Rect(left, top, left + size, top + size) to cluster.map { it.friend }
            )

            // Arrow on the outer rim pointing at where the friend(s) actually are.
            if (offscreenGroup) {
                val towardX = cluster.sumOf { it.rawX } / cluster.size - cx.toFloat()
                val towardY = cluster.sumOf { it.rawY } / cluster.size - cy.toFloat()
                if (towardX != 0f || towardY != 0f) {
                    val angleDeg =
                        Math.toDegrees(atan2(towardY.toDouble(), towardX.toDouble())).toFloat()
                    val half = size / 2f
                    arrowPath.reset()
                    arrowPath.moveTo(half + EDGE_ARROW_LENGTH_PX, 0f)
                    arrowPath.lineTo(half - 2f, -EDGE_ARROW_LENGTH_PX * 0.6f)
                    arrowPath.lineTo(half - 2f, EDGE_ARROW_LENGTH_PX * 0.6f)
                    arrowPath.close()
                    canvas.withTranslation(cx.toFloat(), cy.toFloat()) {
                        rotate(angleDeg)
                        drawPath(arrowPath, arrowPaint)
                    }
                }
            }
            if (faded) canvas.restore()
        }

        // Selected friend keeps the callout look, anchored on its true position.
        selectedOnscreen?.let { item ->
            val friend = item.friend
            val key = "${friend.handle}|$AVATAR_ICON_RENDER_PX|true|${friend.photoUri}|${friend.name}"
            val icon = friendIconCache.getOrPut(key) {
                selectedFriendMarkerIcon(context, friend, markerColor, AVATAR_ICON_RENDER_PX)
            }
            // Drawn size follows the zoom ramp, with the callout's aspect taken from the
            // bitmap so the stem and dot stay in proportion at every size.
            val calloutWidth = baseSize
            val calloutHeight =
                (calloutWidth * icon.bitmap.height.toFloat() / icon.bitmap.width).roundToInt()
            val dst = Rect(
                item.rawX - calloutWidth / 2,
                item.rawY - calloutHeight,
                item.rawX + calloutWidth / 2,
                item.rawY,
            )
            avatarPaint.alpha = 255
            canvas.drawBitmap(icon.bitmap, null, dst, avatarPaint)
            drawnClusters.add(dst to listOf(friend))
        }

        // Only while a size is still settling, so a static map stays at zero frames.
        if (sizeAnimating) mapView.postInvalidateDelayed(16L)
    }

    // One resolution for every avatar; callers scale the bitmap into place, so the size
    // is no longer a parameter and the cache key no longer varies by it.
    private fun avatarIconFor(friend: Friend): BitmapDrawable {
        val key = "${friend.handle}|false|${friend.photoUri}|${friend.name}"
        return friendIconCache.getOrPut(key) {
            friendPhotoMarkerIcon(
                context, friend, markerColor, markerTextColor, AVATAR_ICON_RENDER_PX
            )
        }
    }

    private fun zoomToSelected(
        mapView: MapView,
        selectedFriend: Friend,
    ) {
        val point = GeoPoint(selectedFriend.lat!!, selectedFriend.lon!!)
        val zoom = (mapView.maxZoomLevel - 2.0).coerceAtLeast(mapView.minZoomLevel)
        mapView.post {
            mapView.controller.animateTo(point, zoom, MAP_ANIMATION_DURATION_MS)
        }
    }

    private fun zoomToLocation(mapView: MapView, location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        val zoom = (mapView.maxZoomLevel - 2.0).coerceAtLeast(mapView.minZoomLevel)
        mapView.post {
            mapView.controller.animateTo(point, zoom, MAP_ANIMATION_DURATION_MS)
        }
    }

    private fun zoomToFitAll(mapView: MapView, friends: List<Friend>, myLocation: Location?) {
        val points = friends.map { GeoPoint(it.lat!!, it.lon!!) }.toMutableList()
        myLocation?.let { points.add(GeoPoint(it.latitude, it.longitude)) }
        if (points.isEmpty()) return
        if (points.size == 1) {
            zoomToLocation(mapView, Location("").apply {
                latitude = points[0].latitude
                longitude = points[0].longitude
            })
            return
        }
        val group = densestFitGroup(points)
        if (group.size == 1) {
            zoomToLocation(mapView, Location("").apply {
                latitude = group[0].latitude
                longitude = group[0].longitude
            })
            return
        }
        val box = BoundingBox.fromGeoPointsSafe(group).increaseByScale(1.4f)
        mapView.post {
            mapView.zoomToBoundingBox(box, true, 64)
        }
    }

}

/**
 * Everyone fits at a useful zoom? Show everyone. Otherwise find the fixed-size
 * window holding the MOST points — exact, since some optimal window has a point
 * on its south edge and one on its west edge. O(n³) but n is your friends list.
 * ponytail: ignores the antimeridian; split lon windows if your friends straddle it
 */
fun densestFitGroup(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 2) return points
    var group: List<GeoPoint> = points
    val fullBox = BoundingBox.fromGeoPointsSafe(points)
    if (fullBox.latitudeSpan > MAX_FIT_SPAN_DEGREES ||
        fullBox.longitudeSpanWithDateLine > MAX_FIT_SPAN_DEGREES
    ) {
        for (south in points) for (west in points) {
            val candidate = points.filter {
                it.latitude >= south.latitude &&
                    it.latitude <= south.latitude + MAX_FIT_SPAN_DEGREES &&
                    it.longitude >= west.longitude &&
                    it.longitude <= west.longitude + MAX_FIT_SPAN_DEGREES
            }
            if (candidate.size > group.size || group === points) group = candidate
        }
    }
    return group
}

/**
 * Greedy proximity clustering: avatars merge only once they substantially
 * overlap (centers within ~half a pin), so the compression into one
 * footprint at the merge moment is small — pins near their true spots
 * barely move. ponytail: order-dependent chaining is possible but harmless here.
 */
fun <T> clusterByProximity(
    items: List<T>,
    x: (T) -> Int,
    y: (T) -> Int,
    size: (T) -> Int,
): List<List<T>> {
    val clusters = mutableListOf<MutableList<T>>()
    items.forEach { item ->
        val near = clusters.firstOrNull { cluster ->
            cluster.any {
                hypot((x(it) - x(item)).toDouble(), (y(it) - y(item)).toDouble()) <
                    (size(it) + size(item)) * 0.25
            }
        }
        if (near != null) near.add(item) else clusters.add(mutableListOf(item))
    }
    return clusters
}

/** One face of a group pin: member index, offset from pin center, and draw size. */
class ClusterFace(val member: Int, val x: Double, val y: Double, val size: Int)

/**
 * Faces keep the members' real relative arrangement: their true offsets from
 * the group centroid, scaled to fit the pin footprint, with a light push-apart
 * pass so nobody fully hides behind a neighbor. Returned in draw order —
 * smaller (farther) faces first, so closer members draw larger and on top.
 */
fun layoutClusterFaces(
    pinSize: Int,
    memberSizes: IntArray,
    rawX: IntArray,
    rawY: IntArray,
): List<ClusterFace> {
    val n = memberSizes.size
    val sinHalf = sin(PI / n)
    val d = (pinSize * sinHalf / (0.75 + sinHalf)).roundToInt()
    val faceSizes = IntArray(n) { i ->
        (d * (memberSizes[i].toFloat() / pinSize)).roundToInt().coerceAtLeast(d / 2)
    }
    val centroidX = rawX.sum().toDouble() / n
    val centroidY = rawY.sum().toDouble() / n
    var scale = Double.MAX_VALUE
    for (i in 0 until n) {
        val r = hypot(rawX[i] - centroidX, rawY[i] - centroidY)
        if (r > 1.0) scale = min(scale, (pinSize - faceSizes[i]) / 2.0 / r)
    }
    if (scale == Double.MAX_VALUE) scale = 0.0
    val px = DoubleArray(n) { i -> (rawX[i] - centroidX) * scale }
    val py = DoubleArray(n) { i -> (rawY[i] - centroidY) * scale }
    repeat(3) {
        for (i in 0 until n) for (j in i + 1 until n) {
            var dx = px[j] - px[i]
            var dy = py[j] - py[i]
            var dist = hypot(dx, dy)
            val sep = (faceSizes[i] + faceSizes[j]) / 2.0 * 0.7
            if (dist < sep) {
                if (dist < 1.0) {
                    dx = 1.0
                    dy = 0.0
                    dist = 1.0
                }
                val push = (sep - dist) / 2
                px[i] -= dx / dist * push
                py[i] -= dy / dist * push
                px[j] += dx / dist * push
                py[j] += dy / dist * push
            }
        }
    }
    return (0 until n).sortedBy { faceSizes[it] }.map { i ->
        // Keep each face inside the pin circle after the push-apart.
        val maxR = (pinSize - faceSizes[i]) / 2.0
        val r = hypot(px[i], py[i])
        if (r > maxR && r > 0.0) {
            px[i] *= maxR / r
            py[i] *= maxR / r
        }
        ClusterFace(i, px[i], py[i], faceSizes[i])
    }
}

fun friendPhotoMarkerIcon(
    context: Context,
    friend: Friend,
    markerColor: Int,
    markerTextColor: Int,
    size: Int,
): BitmapDrawable {
    val photo = friend.photoUri?.let { uri ->
        try {
            context.contentResolver.openInputStream(uri.toUri())?.use(BitmapFactory::decodeStream)
        } catch (_: Exception) {
            null
        }
    }
    val label = (friend.name ?: friend.handle).trim().firstOrNull()?.uppercase() ?: "?"
    // Resolved here rather than passed in, so every caller (in-app map and widgets) gets
    // the same treatment without another colour argument threaded through.
    return contactCircleBitmap(photo, label, markerColor, markerTextColor, size, isDarkTheme(context))
        .toDrawable(context.resources)
}

fun selectedFriendMarkerIcon(
    context: Context,
    friend: Friend,
    markerColor: Int,
    avatarSize: Int,
): BitmapDrawable {
    val photo = friend.photoUri?.let { uri ->
        try {
            context.contentResolver.openInputStream(uri.toUri())?.use(BitmapFactory::decodeStream)
        } catch (_: Exception) {
            null
        }
    }
    val label = (friend.name ?: friend.handle).trim().firstOrNull()?.uppercase() ?: "?"
    return selectedContactCalloutBitmap(photo, label, markerColor, avatarSize, isDarkTheme(context))
        .toDrawable(context.resources)
}

private fun selectedContactCalloutBitmap(
    photo: Bitmap?,
    label: String,
    markerColor: Int,
    avatarSize: Int,
    dimPhoto: Boolean = false,
): Bitmap {
    val contrastColor = Color.rgb(116, 148, 174)
    val dotFillColor = markerColor
    val width = avatarSize
    val height = (avatarSize * 1.55f).roundToInt()
    val bitmap = createBitmap(width, height)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = width / 2f
    val avatarRadius = avatarSize * 0.44f
    val avatarCenterY = avatarSize * 0.46f

    paint.color = markerColor
    canvas.drawCircle(center, avatarCenterY, avatarRadius, paint)

    val avatarInset = avatarSize * 0.1f
    val avatarDiameter = avatarRadius * 2f - avatarInset * 2f
    val avatarRect = RectF(0f, 0f, avatarDiameter, avatarDiameter).apply {
        offset(center - avatarDiameter / 2f, avatarCenterY - avatarDiameter / 2f)
    }
    photo?.let {
        val avatarPixels = (avatarRect.width()).roundToInt()
        val scaled = it.scale(avatarPixels, avatarPixels)
        val clip = Path().apply { addOval(avatarRect, Path.Direction.CW) }
        canvas.withClip(clip) {
            drawBitmap(scaled, avatarRect.left, avatarRect.top, dimPhotoPaint(dimPhoto))
        }
    } ?: run {
        paint.color = Color.rgb(235, 241, 247)
        canvas.drawOval(avatarRect, paint)
        paint.color = MARKER_INK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = avatarSize * 0.42f
        paint.isFakeBoldText = true
        canvas.drawText(label, center, avatarCenterY + avatarSize * 0.14f, paint)
    }

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = avatarSize * 0.035f
    paint.color = contrastColor
    canvas.drawOval(avatarRect, paint)
    paint.style = Paint.Style.FILL

    paint.strokeWidth = avatarSize * 0.024f
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = contrastColor
    canvas.drawLine(
        center,
        avatarCenterY + avatarRadius + avatarSize * 0.03f,
        center,
        height - avatarSize * 0.14f,
        paint
    )

    paint.style = Paint.Style.FILL
    paint.color = dotFillColor
    canvas.drawCircle(center, height - avatarSize * 0.08f, avatarSize * 0.06f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = avatarSize * 0.024f
    paint.color = contrastColor
    canvas.drawCircle(center, height - avatarSize * 0.08f, avatarSize * 0.06f, paint)
    paint.style = Paint.Style.FILL
    return bitmap
}

/** Reused for every avatar draw; null keeps the original fast path untouched. */
private val dimPhotoPaintInstance = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = DIM_PHOTO_COLOR_FILTER
}

private fun dimPhotoPaint(dim: Boolean): Paint? = if (dim) dimPhotoPaintInstance else null

private fun contactCircleBitmap(
    photo: Bitmap?,
    label: String,
    markerColor: Int,
    markerTextColor: Int,
    size: Int,
    dimPhoto: Boolean = false,
): Bitmap {
    val bitmap = createBitmap(size, size)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    val outerRadius = size * 0.46f
    val inset = size * 0.1f
    paint.color = markerColor
    canvas.drawCircle(center, center, outerRadius, paint)

    val avatarRect = RectF(inset, inset, size - inset, size - inset)
    photo?.let {
        val avatarSize = (size - inset * 2).roundToInt()
        val scaled = it.scale(avatarSize, avatarSize)
        val clip = Path().apply { addOval(avatarRect, Path.Direction.CW) }
        canvas.withClip(clip) {
            drawBitmap(scaled, inset, inset, dimPhotoPaint(dimPhoto))
        }
    } ?: run {
        paint.color = Color.WHITE
        canvas.drawOval(avatarRect, paint)
        paint.color = MARKER_INK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.42f
        paint.isFakeBoldText = true
        canvas.drawText(label, center, center + size * 0.16f, paint)
    }

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = size * 0.035f
    paint.color = markerTextColor
    canvas.drawOval(avatarRect, paint)
    paint.style = Paint.Style.FILL
    return bitmap
}

@Composable
private fun FriendsBottomSheet(
    configured: Boolean,
    loading: Boolean,
    contactsGranted: Boolean,
    loadedOnce: Boolean,
    error: String?,
    friends: List<Friend>,
    selectedFriend: Friend?,
    myLocation: Location?,
    lastPushedAtMillis: Long,
    refreshingHandles: Map<String, Boolean>,
    hiddenBottomPaddingPx: Float,
    onRefresh: () -> Unit,
    onRequestContacts: () -> Unit,
    onFriendSelected: (Friend) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val listBottomPadding = with(density) { hiddenBottomPaddingPx.toDp() + 32.dp }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(20.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .align(Alignment.CenterHorizontally)
                )
                AnimatedContent(
                    targetState = selectedFriend,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "friends_sheet_content"
                ) { detailFriend ->
                    if (detailFriend != null) {
                        FriendDetailSheet(
                            friend = detailFriend,
                            myLocation = myLocation,
                            refreshing = refreshingHandles[detailFriend.handle] == true,
                            onClose = onClearSelection,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                !configured -> {
                                    FriendsSheetHeader(loading, configured, lastPushedAtMillis, onRefresh)
                                    Text(
                                        "Set the forwarding base URL and token in Settings first.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                else -> {
                                    FriendsSheetHeader(loading, configured, lastPushedAtMillis, onRefresh)
                                    if (!contactsGranted) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Allow contacts access to show names instead of handles.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = onRequestContacts) {
                                                Text("Allow")
                                            }
                                        }
                                    }

                                    error?.let {
                                        Text("Couldn't load friends: $it", color = MaterialTheme.colorScheme.error)
                                    }

                                    if (loadedOnce && friends.isEmpty() && error == null) {
                                        Text(
                                            "Nobody is sharing their location right now.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = listBottomPadding)
                                    ) {
                                        items(friends, key = { it.handle }) { friend ->
                                            FriendRow(
                                                friend = friend,
                                                myLocation = myLocation,
                                                refreshing = refreshingHandles[friend.handle] == true,
                                                onClick = { onFriendSelected(friend) },
                                                modifier = Modifier.animateItem()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendsSheetHeader(
    loading: Boolean,
    configured: Boolean,
    lastPushedAtMillis: Long,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Friends", style = MaterialTheme.typography.titleLarge)
            lastPushedLabel(lastPushedAtMillis, rememberTimeTick())?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onRefresh, enabled = configured && !loading) {
                Icon(painterResource(R.drawable.refresh_24px), contentDescription = "Refresh")
            }
        }
    }
}

@Composable
private fun FriendDetailSheet(
    friend: Friend,
    myLocation: Location?,
    refreshing: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Distinguishes "no fix at all" from "we have a fix, the server just didn't name it".
    val place = friend.address?.takeIf { it.isNotBlank() }
        ?: if (friend.hasLocation)"No address from server (%.5f, %.5f)".format(friend.lat, friend.lon)
        else null
    val longAddress = friend.fullAddress?.replace("\n", ", ")
    val time = if (friend.timestamp > 0) relativeTime(friend.timestamp, rememberTimeTick()) else null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                ContactAvatar(friend, Modifier.size(56.dp))
                Column {
                    Text(
                        text = friend.name ?: friend.handle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = friend.handle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close_24px), contentDescription = "Close")
            }
        }

        if (refreshing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Refreshing location", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            text = listOfNotNull(place, time).ifEmpty { listOf("Location unavailable") }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        longAddress?.takeIf { it.isNotBlank() && it != place }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        myLocation?.takeIf { friend.hasLocation }?.let {
            Text(
                text = formatMiles(it, friend.lat!!, friend.lon!!),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        TextButton(
            onClick = {
                if (friend.hasLocation) {
                    val label = Uri.encode(friend.name ?: friend.handle)
                    val geo = "google.navigation:q=${friend.lat},${friend.lon}($label)"
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, geo.toUri()))
                    } catch (_: Exception) {
                        val fallback = "geo:${friend.lat},${friend.lon}?q=${friend.lat},${friend.lon}($label)"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, fallback.toUri()))
                        } catch (inner: Exception) {
                            FileLogger.w("No maps app available: ${inner.message}")
                        }
                    }
                }
            },
            enabled = friend.hasLocation,
        ) {
            Text("Navigate")
        }
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    myLocation: Location?,
    refreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val place = friend.address ?: friend.fullAddress?.replace("\n", ", ")
    val time = if (friend.timestamp > 0) relativeTime(friend.timestamp, rememberTimeTick()) else null
    val subtitle = if (friend.hasLocation) {
        listOfNotNull(place, time).joinToString(" · ")
    } else {
        listOfNotNull(place?.let { "Near $it" } ?: "Location unavailable", time)
            .joinToString(" · ")
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(friend, Modifier.size(40.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name ?: friend.handle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (friend.hasLocation) {
                myLocation?.let {
                    Text(
                        text = formatMiles(it, friend.lat!!, friend.lon!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactAvatar(friend: Friend, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(initialValue = null, friend.photoUri) {
        value = friend.photoUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri.toUri())?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    Box(
        // 40.dp stays the fallback, but comes first so a caller-supplied size wins.
        modifier = Modifier
            .size(40.dp)
            .then(modifier)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        photo?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: Text(
            text = (friend.name ?: friend.handle).trim()
                .firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
