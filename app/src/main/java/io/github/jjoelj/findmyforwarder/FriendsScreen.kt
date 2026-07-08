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
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val MAP_ANIMATION_DURATION_MS = 450L
private const val SHEET_FLING_VELOCITY_THRESHOLD = 900f
private const val ACTIVE_FRIEND_REFRESH_MIN_INTERVAL_MILLIS = 15_000L

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

fun relativeTime(epochSeconds: Long): String {
    val deltaMs = System.currentTimeMillis() - epochSeconds * 1000
    return if (deltaMs < 60_000) "Now"
    else DateUtils.getRelativeTimeSpanString(epochSeconds * 1000).toString()
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

// The phone live-refreshes from Find My on each call; spec allows up to ~20s.
private val friendsClient = OkHttpClient.Builder()
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private val friendsRefreshClient = OkHttpClient.Builder()
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(50, TimeUnit.SECONDS)
    .build()

const val FRIENDS_REFRESH_INTERVAL_MILLIS = 120_000L
private const val ALL_FRIENDS_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1_000L

fun shouldRefreshFriendsLocations(
    nowMillis: Long,
    lastRefreshTriggeredAtMillis: Long,
    intervalMillis: Long = FRIENDS_REFRESH_INTERVAL_MILLIS,
): Boolean =
    lastRefreshTriggeredAtMillis <= 0 ||
            nowMillis - lastRefreshTriggeredAtMillis >= intervalMillis

fun friendHandlesToRefresh(friends: List<Friend>): List<String> =
    friends.sortedWith(compareBy<Friend>({ it.timestamp }, { it.handle }))
        .map { it.handle }
        .distinct()

fun parseFriendRefreshTimes(body: String): Map<String, Long> {
    if (body.isBlank()) return emptyMap()
    return try {
        val root = JSONObject(body)
        root.keys().asSequence().associateWith { root.optLong(it) }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun encodeFriendRefreshTimes(times: Map<String, Long>): String {
    val root = JSONObject()
    times.forEach { (handle, time) -> root.put(handle, time) }
    return root.toString()
}

fun friendWasRefreshedRecently(
    handle: String,
    refreshTimes: Map<String, Long>,
    nowMillis: Long,
    intervalMillis: Long = FRIENDS_REFRESH_INTERVAL_MILLIS,
): Boolean =
    !shouldRefreshFriendsLocations(
        nowMillis = nowMillis,
        lastRefreshTriggeredAtMillis = refreshTimes[normalizeHandle(handle)] ?: 0L,
        intervalMillis = intervalMillis,
    )

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

fun mergeRefreshedFriends(current: List<Friend>, refreshed: List<Friend>): List<Friend> {
    if (refreshed.isEmpty()) return current
    val byHandle = refreshed.associateBy { normalizeHandle(it.handle) }
    val replaced = current.map { friend ->
        val updated = byHandle[normalizeHandle(friend.handle)] ?: return@map friend
        updated.copy(
            name = updated.name ?: friend.name,
            photoUri = updated.photoUri ?: friend.photoUri,
        )
    }
    val existingHandles = current.map { normalizeHandle(it.handle) }.toSet()
    return replaced + refreshed.filter { normalizeHandle(it.handle) !in existingHandles }
}

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

private fun triggerFriendsRefreshIfStale(
    prefs: SharedPreferencesProvider,
    handle: String?,
): List<Friend> {
    val url = friendsUrl(prefs, "/friends/refresh", handle)
        ?: throw IOException("Invalid base URL; check Settings")
    val request = Request.Builder()
        .url(url)
        .addHeader("skip_zrok_interstitial", "1")
        .get()
        .build()

    try {
        friendsRefreshClient.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = response.body.string()
                    val failure = refreshFailureMessage(body)
                    if (failure == null) {
                        val target = handle ?: "all friends"
                        FileLogger.i("Triggered friends location refresh for $target")
                        return parseFriends(body)
                    } else {
                        FileLogger.w("Friends location refresh trigger failed: $failure")
                    }
                }

                response.code == 409 -> {
                    FileLogger.i("Friends location refresh already in progress")
                }

                else -> {
                    FileLogger.w("Friends location refresh trigger failed: HTTP ${response.code}")
                }
            }
        }
    } catch (e: Exception) {
        FileLogger.w("Friends location refresh trigger did not complete: ${e.message}")
    }
    return emptyList()
}

suspend fun refreshAllFriendsIfStale(context: Context): List<Friend> =
    withContext(Dispatchers.IO) {
        val prefs = SharedPreferencesProvider(context)
        val nowMillis = System.currentTimeMillis()
        if (!shouldRefreshFriendsLocations(
                nowMillis = nowMillis,
                lastRefreshTriggeredAtMillis = prefs.friendsRefreshTriggeredAtMillis,
                intervalMillis = ALL_FRIENDS_REFRESH_INTERVAL_MILLIS,
            )
        ) {
            return@withContext emptyList()
        }

        val refreshed = triggerFriendsRefreshIfStale(prefs, handle = null)
        prefs.friendsRefreshTriggeredAtMillis = nowMillis
        if (refreshed.isEmpty()) {
            emptyList()
        } else {
            val times = parseFriendRefreshTimes(prefs.friendRefreshTimes).toMutableMap()
            refreshed.forEach { times[normalizeHandle(it.handle)] = nowMillis }
            prefs.friendRefreshTimes = encodeFriendRefreshTimes(times)
            resolveAndDedupe(context, refreshed)
        }
    }

suspend fun refreshFriendLocationIfStale(
    context: Context,
    friend: Friend,
    force: Boolean = false,
    onHandleRefreshing: (String, Boolean) -> Unit = { _, _ -> },
): List<Friend> {
    val handle = friend.handle
    val prefs = SharedPreferencesProvider(context)
    val nowMillis = System.currentTimeMillis()
    val refreshTimes = parseFriendRefreshTimes(prefs.friendRefreshTimes)
    if (!force && friendWasRefreshedRecently(handle, refreshTimes, nowMillis)) {
        return emptyList()
    }

    onHandleRefreshing(handle, true)
    return try {
        withContext(Dispatchers.IO) {
            val refreshedFriends = triggerFriendsRefreshIfStale(prefs, handle)
            val updatedTimes = refreshTimes.toMutableMap()
            updatedTimes[normalizeHandle(handle)] = nowMillis
            prefs.friendRefreshTimes = encodeFriendRefreshTimes(updatedTimes)
            if (refreshedFriends.isEmpty()) {
                emptyList()
            } else {
                resolveAndDedupe(context, refreshedFriends)
            }
        }
    } finally {
        onHandleRefreshing(handle, false)
    }
}

suspend fun fetchFriends(context: Context): List<Friend> = withContext(Dispatchers.IO) {
    val prefs = SharedPreferencesProvider(context)

    val url = friendsUrl(prefs, "/friends")
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
        prefs.friendsCache = body
        FileLogger.i(
            "Fetched ${friends.size} friends (${friends.count { it.hasLocation }} with location)"
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
    val refreshingHandles = remember { mutableStateMapOf<String, Boolean>() }
    val status by AppStatus.state.collectAsState()
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

    fun refresh() {
        if (!configured || loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val refreshedAll = refreshAllFriendsIfStale(context)
                if (refreshedAll.isNotEmpty()) {
                    friends = mergeRefreshedFriends(friends, refreshedAll)
                }
                val fetchedFriends = fetchFriends(context)
                friends = fetchedFriends
                loadedOnce = true
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                FileLogger.e("Failed to fetch friends: ${e.message}")
            } finally {
                loading = false
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
            if (it) refresh()
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
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
        var mapSheetVisibleHeightPx by remember {
            mutableFloatStateOf(sheetMaxHeightPx / 2f)
        }
        val sheetAnchors = remember(sheetMaxHeightPx, collapsedOffset) {
            listOf(fullOffset, twoThirdOffset, halfOffset, collapsedOffset)
        }
        val showMyLocationButton = myLocation != null &&
            (sheetOffset.value - halfOffset).absoluteValue < with(density) { 3.dp.toPx() }
        fun updateMapSheetHeight(offset: Float) {
            mapSheetVisibleHeightPx = sheetMaxHeightPx - offset
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
        fun applyRefreshedFriends(refreshed: List<Friend>) {
            if (refreshed.isEmpty()) return
            friends = mergeRefreshedFriends(friends, refreshed)
            selectedFriend?.let { selected ->
                val updatedSelected = mergeRefreshedFriends(listOf(selected), refreshed).first()
                if (updatedSelected.lat != selected.lat || updatedSelected.lon != selected.lon) {
                    mapSelectionRequest++
                }
                selectedFriend = updatedSelected
            }
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

        val currentSelectedFriend by rememberUpdatedState(selectedFriend)
        LaunchedEffect(selectedFriend?.handle) {
            val selectedHandle = selectedFriend?.handle ?: return@LaunchedEffect
            val normalizedHandle = normalizeHandle(selectedHandle)
            while (isActive) {
                val activeFriend = currentSelectedFriend
                if (activeFriend == null || normalizeHandle(activeFriend.handle) != normalizedHandle) {
                    break
                }
                val refreshStartedAt = System.currentTimeMillis()
                val refreshed = refreshFriendLocationIfStale(
                    context = context,
                    friend = activeFriend,
                    force = true,
                    onHandleRefreshing = { handle, refreshing ->
                        if (refreshing) {
                            refreshingHandles[handle] = true
                        } else {
                            refreshingHandles.remove(handle)
                        }
                    }
                )
                applyRefreshedFriends(refreshed)
                val elapsedMillis = System.currentTimeMillis() - refreshStartedAt
                val remainingDelay = ACTIVE_FRIEND_REFRESH_MIN_INTERVAL_MILLIS - elapsedMillis
                if (remainingDelay > 0) {
                    delay(remainingDelay)
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
            sheetVisibleHeightPx = mapSheetVisibleHeightPx,
            mapCenterOffsetY = mapCenterOffset.value.roundToInt(),
            onFriendSelected = { selectFriend(it) },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showMyLocationButton,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 12.dp,
                    bottom = with(density) { (sheetMaxHeightPx - halfOffset).toDp() } + 12.dp
                )
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
            ) {
                IconButton(
                    onClick = {
                        clearSelection(keepSheetPosition = true)
                        mapMyLocationRequest++
                    }
                ) {
                    Icon(
                        painterResource(R.drawable.navigation_24px),
                        contentDescription = "Show my location"
                    )
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
    sheetVisibleHeightPx: Float,
    mapCenterOffsetY: Int,
    onFriendSelected: (Friend) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markerColor = Color.rgb(20, 30, 44)
    val markerTextColor = Color.rgb(134, 166, 190)
    val meMarkerColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val meMarkerInnerColor = MaterialTheme.colorScheme.onTertiary.toArgb()
    val mapController = remember {
        FriendsMapController(
            context.applicationContext,
            markerColor,
            markerTextColor,
            meMarkerColor,
            meMarkerInnerColor,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MapView(viewContext).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
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
            mapController.sync(
                mapView = mapView,
                friends = friends,
                myLocation = myLocation,
                selectedFriend = selectedFriend,
                selectionRequest = selectionRequest,
                sheetSnapRequest = sheetSnapRequest,
                myLocationRequest = myLocationRequest,
                sheetVisibleHeightPx = sheetVisibleHeightPx,
                mapCenterOffsetY = mapCenterOffsetY,
                onFriendSelected = onFriendSelected,
            )
        }
    )
}

private class FriendsMapController(
    private val context: Context,
    private val markerColor: Int,
    private val markerTextColor: Int,
    private val meMarkerColor: Int,
    private val meMarkerInnerColor: Int,
) {
    private val friendMarkers = mutableMapOf<String, Marker>()
    private val friendsByHandle = mutableMapOf<String, Friend>()
    private val friendIconCache = mutableMapOf<String, BitmapDrawable>()
    private var myLocationMarker: Marker? = null
    private var zoomedIcons = false
    private var lastFriendSignature = ""
    private var lastSelectedHandle: String? = null
    private var lastSelectionRequest = -1
    private var lastSheetSnapRequest = -1
    private var lastMyLocationRequest = -1
    private var lastBottomInset = -1
    private var lastMapCenterOffsetY = Int.MIN_VALUE
    private var listenerInstalled = false

    fun sync(
        mapView: MapView,
        friends: List<Friend>,
        myLocation: Location?,
        selectedFriend: Friend?,
        selectionRequest: Int,
        sheetSnapRequest: Int,
        myLocationRequest: Int,
        sheetVisibleHeightPx: Float,
        mapCenterOffsetY: Int,
        onFriendSelected: (Friend) -> Unit,
    ) {
        installZoomListener(mapView)
        val bottomInset = sheetVisibleHeightPx.coerceAtLeast(76f).roundToInt() + 24
        val mappedFriends = friends.filter { it.hasLocation }
        val friendSignature = mappedFriends.joinToString("|") {
            "${it.handle}:${it.lat}:${it.lon}:${it.name}:${it.photoUri}:${it.address}:${it.fullAddress}"
        }
        val friendsChanged = friendSignature != lastFriendSignature
        val selectionChanged = selectedFriend?.handle != lastSelectedHandle
        val selectionRequested = selectionRequest != lastSelectionRequest
        val sheetSnapRequested = sheetSnapRequest != lastSheetSnapRequest
        val myLocationRequested = myLocationRequest != lastMyLocationRequest
        val bottomInsetChanged = bottomInset != lastBottomInset
        if (bottomInsetChanged) {
            mapView.setPadding(0, 0, 0, bottomInset)
            lastBottomInset = bottomInset
        }
        if (mapCenterOffsetY != lastMapCenterOffsetY) {
            mapView.setMapCenterOffset(0, mapCenterOffsetY)
            lastMapCenterOffsetY = mapCenterOffsetY
        }
        if (friendsChanged) {
            syncFriendMarkers(mapView, mappedFriends, onFriendSelected)
            lastFriendSignature = friendSignature
        }
        syncMyLocationMarker(mapView, myLocation)
        updateFriendMarkerIcons(mapView, selectedFriend?.handle, force = friendsChanged || selectionChanged)

        if (selectedFriend?.hasLocation == true &&
            (selectionRequested || selectionChanged || sheetSnapRequested)
        ) {
            zoomToSelected(mapView, selectedFriend)
        } else if (myLocationRequested && myLocation != null) {
            zoomToLocation(mapView, myLocation)
        }
        lastSelectedHandle = selectedFriend?.handle
        lastSelectionRequest = selectionRequest
        lastSheetSnapRequest = sheetSnapRequest
        lastMyLocationRequest = myLocationRequest
        mapView.invalidate()
    }

    private fun installZoomListener(mapView: MapView) {
        if (listenerInstalled) return
        listenerInstalled = true
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                updateFriendMarkerIcons(mapView, lastSelectedHandle)
                return false
            }
        })
    }

    private fun syncFriendMarkers(
        mapView: MapView,
        friends: List<Friend>,
        onFriendSelected: (Friend) -> Unit,
    ) {
        val handles = friends.map { it.handle }.toSet()
        val removed = friendMarkers.keys - handles
        removed.forEach { handle ->
            friendMarkers.remove(handle)?.let { mapView.overlays.remove(it) }
            friendsByHandle.remove(handle)
        }

        friends.forEach { friend ->
            friendsByHandle[friend.handle] = friend
            val point = GeoPoint(friend.lat!!, friend.lon!!)
            val marker = friendMarkers.getOrPut(friend.handle) {
                Marker(mapView).also {
                    it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    mapView.overlays.add(it)
                }
            }
            marker.position = point
            marker.title = friend.name ?: friend.handle
            marker.subDescription = friend.address ?: friend.fullAddress
            marker.setOnMarkerClickListener { _, _ ->
                onFriendSelected(friend)
                true
            }
        }
        friendIconCache.keys.removeAll { key -> key.substringBefore('|') !in handles }
    }

    private fun syncMyLocationMarker(mapView: MapView, myLocation: Location?) {
        if (myLocation == null) {
            myLocationMarker?.let { mapView.overlays.remove(it) }
            myLocationMarker = null
            return
        }
        val point = GeoPoint(myLocation.latitude, myLocation.longitude)
        val marker = myLocationMarker ?: Marker(mapView).also {
            it.title = "You"
            it.icon = currentLocationMarkerIcon(context, meMarkerColor, meMarkerInnerColor)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mapView.overlays.add(it)
            myLocationMarker = it
        }
        marker.position = point
    }

    private fun updateFriendMarkerIcons(
        mapView: MapView,
        selectedHandle: String?,
        force: Boolean = false,
    ) {
        val zoomed = mapView.zoomLevelDouble >= 14.0
        if (!force && zoomed == zoomedIcons && friendMarkers.values.all { it.icon != null }) return
        zoomedIcons = zoomed
        friendMarkers.forEach { (handle, marker) ->
            val friend = friendsByHandle[handle] ?: return@forEach
            val selected = handle == selectedHandle
            val size = when {
                selected -> 192
                zoomed -> 192
                else -> 144
            }
            val key = "$handle|$size|$selected|${friend.photoUri}|${friend.name}"
            marker.icon = friendIconCache.getOrPut(key) {
                if (selected) {
                    selectedFriendMarkerIcon(context, friend, markerColor, markerTextColor, size)
                } else {
                    friendPhotoMarkerIcon(context, friend, markerColor, markerTextColor, size)
                }
            }
            if (selected) {
                marker.setAnchor(Marker.ANCHOR_CENTER, 1.0f)
            } else {
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
        }
        mapView.invalidate()
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

}

private fun friendPhotoMarkerIcon(
    context: Context,
    friend: Friend,
    markerColor: Int,
    markerTextColor: Int,
    size: Int,
): BitmapDrawable {
    val photo = friend.photoUri?.let { uri ->
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        } catch (e: Exception) {
            null
        }
    }
    val label = (friend.name ?: friend.handle).trim().firstOrNull()?.uppercase() ?: "?"
    return BitmapDrawable(
        context.resources,
        contactCircleBitmap(photo, label, markerColor, markerTextColor, size)
    )
}

private fun selectedFriendMarkerIcon(
    context: Context,
    friend: Friend,
    markerColor: Int,
    markerTextColor: Int,
    avatarSize: Int,
): BitmapDrawable {
    val photo = friend.photoUri?.let { uri ->
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        } catch (e: Exception) {
            null
        }
    }
    val label = (friend.name ?: friend.handle).trim().firstOrNull()?.uppercase() ?: "?"
    val bitmap = selectedContactCalloutBitmap(photo, label, markerColor, markerTextColor, avatarSize)
    return BitmapDrawable(context.resources, bitmap)
}

private fun currentLocationMarkerIcon(
    context: Context,
    markerColor: Int,
    innerColor: Int,
): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = markerColor
    canvas.drawCircle(18f, 18f, 18f, paint)
    paint.color = innerColor
    canvas.drawCircle(18f, 18f, 8f, paint)
    return BitmapDrawable(context.resources, bitmap)
}

private fun selectedContactCalloutBitmap(
    photo: Bitmap?,
    label: String,
    markerColor: Int,
    markerTextColor: Int,
    avatarSize: Int,
): Bitmap {
    val contrastColor = Color.rgb(116, 148, 174)
    val dotFillColor = markerColor
    val width = avatarSize
    val height = (avatarSize * 1.55f).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        val scaled = Bitmap.createScaledBitmap(it, avatarPixels, avatarPixels, true)
        val clip = Path().apply { addOval(avatarRect, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(scaled, avatarRect.left, avatarRect.top, null)
        canvas.restore()
    } ?: run {
        paint.color = Color.rgb(235, 241, 247)
        canvas.drawOval(avatarRect, paint)
        paint.color = markerColor
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

private fun contactCircleBitmap(
    photo: Bitmap?,
    label: String,
    markerColor: Int,
    markerTextColor: Int,
    size: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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
        val scaled = Bitmap.createScaledBitmap(it, avatarSize, avatarSize, true)
        val clip = Path().apply { addOval(avatarRect, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(scaled, inset, inset, null)
        canvas.restore()
    } ?: run {
        paint.color = Color.WHITE
        canvas.drawOval(avatarRect, paint)
        paint.color = markerColor
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
                                    FriendsSheetHeader(loading, configured, onRefresh)
                                    Text(
                                        "Set the forwarding base URL and token in Settings first.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                else -> {
                                    FriendsSheetHeader(loading, configured, onRefresh)
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
                                                onClick = { onFriendSelected(friend) }
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
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Friends", style = MaterialTheme.typography.titleLarge)
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
    val place = friend.address
    val longAddress = friend.fullAddress?.replace("\n", ", ")
    val time = if (friend.timestamp > 0) relativeTime(friend.timestamp) else null

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
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
                    } catch (e: Exception) {
                        val fallback = "geo:${friend.lat},${friend.lon}?q=${friend.lat},${friend.lon}($label)"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
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
) {
    val place = friend.address ?: friend.fullAddress?.replace("\n", ", ")
    val time = if (friend.timestamp > 0) relativeTime(friend.timestamp) else null
    val subtitle = if (friend.hasLocation) {
        listOfNotNull(place, time).joinToString(" · ")
    } else {
        listOfNotNull(place?.let { "Near $it" } ?: "Location unavailable", time)
            .joinToString(" · ")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
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
            when {
                refreshing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                friend.hasLocation -> {
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
}

@Composable
private fun ContactAvatar(friend: Friend, modifier: Modifier = Modifier.size(40.dp)) {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(initialValue = null, friend.photoUri) {
        value = friend.photoUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Box(
        modifier = modifier
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
