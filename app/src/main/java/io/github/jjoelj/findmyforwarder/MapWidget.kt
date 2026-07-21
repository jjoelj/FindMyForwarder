package io.github.jjoelj.findmyforwarder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.drawing.MapSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Date
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.net.toUri

/**
 * "Large Map" home-screen widget: a static snapshot of the map zoomed out to
 * fit as many friends as possible (same densest-group + clustering logic as
 * the in-app map), rendered with osmdroid's MapSnapshot from the cached
 * friends list. Tapping the map opens the app; the corner button refetches
 * friends. Subclasses override [pointsToFit] to frame the map differently.
 */
open class MapWidget : AppWidgetProvider() {

    /** The locations the snapshot should be framed around. */
    protected open fun pointsToFit(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<GeoPoint> =
        friends.map { GeoPoint(it.lat!!, it.lon!!) } + listOfNotNull(myPoint)

    /** Friend rendered with the selected callout marker, if this widget tracks one. */
    protected open fun focusedFriend(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
    ): Friend? = null

    /** Non-null enables a dedicated loading screen while this widget snapshot renders. */
    protected open fun loadingMessage(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
    ): String? = null

    /** Vertical viewport position for a focused marker (0.5 is exact center). */
    protected open val focusedPointVerticalPosition = 0.5

    /**
     * Handles whose locations this widget refreshes. Null means refresh everyone;
     * an empty list means there is nothing currently visible to refresh.
     */
    protected open fun refreshHandles(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<String>? = null

    /** Zoom-out cap: framing never zooms out further than this level. */
    protected open val minZoom = 3.0

    /** Zoom used when the frame collapses to a single point. */
    protected open val singlePointZoom = 15.0

    /** Corner label so placed widgets are distinguishable; null hides it. */
    protected open fun widgetLabel(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
    ): String? = null

    /** Bottom-corner status line; subclasses may supply friend-specific details. */
    protected open fun widgetStatus(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): String? = null

    /** Bottom-right data-freshness label; single-friend widgets opt out. */
    protected open fun widgetUpdatedLabel(context: Context): String? {
        val updatedAt = SharedPreferencesProvider(context).lastFriendsUpdatedAtMillis
        if (updatedAt <= 0L) return null
        return "Updated ${DateFormat.getTimeFormat(context).format(Date(updatedAt))}"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appContext = context.applicationContext
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(widgetId)
            else manager.getAppWidgetIds(ComponentName(appContext, javaClass))
            FileLogger.i("Widget refresh requested: ${javaClass.simpleName}, id=$widgetId")
            setLoading(appContext, javaClass, ids, loading = true)
            // Never leave a launcher displaying an indefinite loading state if the
            // server refresh outlives the broadcast's background execution window.
            Handler(Looper.getMainLooper()).postDelayed({
                setLoading(appContext, javaClass, ids, loading = false)
            }, WIDGET_LOADING_MAX_MS)
            val result = goAsync()
            thread {
                try {
                    val friends = runBlocking { loadCachedFriends(appContext) }
                    val prefs = SharedPreferencesProvider(appContext)
                    val myPoint = prefs.lastSentLat.toDoubleOrNull()?.let { lat ->
                        prefs.lastSentLon.toDoubleOrNull()?.let { lon -> GeoPoint(lat, lon) }
                    }
                    val handles = refreshHandles(appContext, widgetId, friends, myPoint)
                    if (handles == null) {
                        // Full-map refresh: ask the server to refresh everybody.
                        runBlocking { refreshFriends(appContext) }
                    } else if (handles.isNotEmpty()) {
                        // Targeted refresh responses do not replace the shared cache,
                        // so reload it once after every requested person has updated.
                        runBlocking {
                            handles.distinct().forEach { refreshFriends(appContext, it) }
                            fetchFriends(appContext)
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.w("Widget friends refresh failed: ${e.message}")
                    requestUpdate(appContext, javaClass, ids)
                } finally {
                    result.finish()
                }
            }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The receiver's own context can't register receivers, which the osmdroid
        // tile provider does internally.
        val appContext = context.applicationContext
        val result = goAsync()
        thread {
            try {
                ids.forEach { renderWidget(appContext, manager, it) }
            } catch (e: Exception) {
                FileLogger.w("Map widget render failed: ${e.message}")
            } finally {
                result.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle,
    ) = onUpdate(context, manager, intArrayOf(widgetId))

    private fun renderWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val friends = runBlocking { loadCachedFriends(context) }.filter { it.hasLocation }
        val prefs = SharedPreferencesProvider(context)
        val myPoint = prefs.lastSentLat.toDoubleOrNull()?.let { lat ->
            prefs.lastSentLon.toDoubleOrNull()?.let { lon -> GeoPoint(lat, lon) }
        }
        val points = pointsToFit(context, widgetId, friends, myPoint)
        val focusedFriend = focusedFriend(context, widgetId, friends)
        val loadingMessage = loadingMessage(context, widgetId, friends)
        if (loadingMessage != null) {
            manager.updateAppWidget(
                widgetId,
                loadingViews(
                    context, javaClass, widgetId, loading = true,
                    clearMap = true, message = loadingMessage,
                )
            )
        }
        if (points.isEmpty()) {
            manager.partiallyUpdateAppWidget(
                widgetId,
                loadingViews(
                    context, javaClass, widgetId, loading = false,
                    clearMap = loadingMessage != null, message = loadingMessage,
                )
            )
            return
        }
        // No forced spinner here: an auto-render (placement / 30-min tick) renders
        // silently over the last image. Only the user-triggered refresh shows the
        // spinner (via setLoading), and this render clears it below on completion —
        // so a render that's torn down early never leaves a spinner stuck on.

        val density = context.resources.displayMetrics.density
        val options = manager.getAppWidgetOptions(widgetId)
        // ponytail: portrait cell size only; landscape home screens get a crop
        val width = ((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            .takeIf { it > 0 } ?: 250) * density).roundToInt()
        val height = ((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            .takeIf { it > 0 } ?: 140) * density).roundToInt()

        val group = densestFitGroup(points)
        val center: GeoPoint
        val zoom: Double
        if (group.size == 1) {
            center = group[0]
            zoom = singlePointZoom
        } else {
            val box = BoundingBox.fromGeoPointsSafe(group).increaseByScale(1.3f)
            center = GeoPoint(box.centerLatitude, box.centerLongitude)
            zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width, height)
                .coerceIn(minZoom, 17.0)
        }
        val focusedCenterOffsetY = if (focusedFriend != null) {
            ((focusedPointVerticalPosition - 0.5) * height).roundToInt()
        } else 0
        val focusedPinSize = if (focusedFriend != null) {
            // A selected callout is 1.55× its avatar height. Scale down only on
            // short widgets so it always has room above its coordinate.
            min(
                FOCUSED_PIN_SIZE_PX.toDouble(),
                (height * focusedPointVerticalPosition - FOCUSED_PIN_TOP_MARGIN_PX) /
                    SELECTED_CALLOUT_HEIGHT_SCALE,
            ).roundToInt().coerceAtLeast(48)
        } else FOCUSED_PIN_SIZE_PX
        val projection = Projection(
            zoom, width, height, center, 0f, true, false, 0, focusedCenterOffsetY
        )

        // MapSnapshot's internal Handler must be built on a Looper thread, and its
        // tile-complete callbacks are delivered on that same looper — so the whole
        // snapshot lives on the main looper while this worker just waits on the latch.
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var tileProvider: MapTileProviderBasic? = null
        var strict: MapSnapshot? = null
        var fallback: MapSnapshot? = null
        val callback = MapSnapshot.MapSnapshotable { s ->
            try {
                if (s.status == MapSnapshot.Status.CANVAS_OK) {
                    val bitmap = s.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    // Same matrix the in-app map uses, applied to the tiles only —
                    // drawPins runs afterwards so markers keep their real colours.
                    if (isDarkTheme(context)) darkenMapTiles(bitmap)
                    drawPins(
                        context, bitmap, projection, friends, myPoint, focusedFriend, focusedPinSize
                    )
                    manager.updateAppWidget(
                        widgetId,
                        mapViews(
                            context, widgetId, bitmap,
                            widgetLabel(context, widgetId, friends),
                            widgetStatus(context, widgetId, friends, myPoint),
                            widgetUpdatedLabel(context),
                        )
                    )
                }
            } finally {
                latch.countDown()
            }
        }
        mainHandler.post {
            val provider = MapTileProviderBasic(context, TileSourceFactory.MAPNIK)
            tileProvider = provider
            // Wait for every tile to be a real one (fresh or expired-from-cache);
            // scaled placeholders and failed downloads keep it waiting.
            strict = MapSnapshot(
                callback,
                MapSnapshot.INCLUDE_FLAG_UPTODATE + MapSnapshot.INCLUDE_FLAG_EXPIRED,
                provider, emptyList(), projection
            ).also { it.run() }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            // Some tiles are slow or failed: take a best-effort snapshot NOW from
            // the same (warm) provider so the widget shows what did arrive. If the
            // strict snapshot completes later, its full image replaces this one.
            mainHandler.post {
                fallback = MapSnapshot(
                    callback, MapSnapshot.INCLUDE_FLAGS_ALL,
                    tileProvider, emptyList(), projection
                ).also { it.run() }
            }
            latch.await(5, TimeUnit.SECONDS)
        }
        manager.partiallyUpdateAppWidget(
            widgetId, loadingViews(context, javaClass, widgetId, loading = false)
        )
        // ponytail: fixed grace period for the late strict render, then free the
        // tile provider; a render still pending at +60s just keeps the fallback image
        mainHandler.postDelayed({
            fallback?.onDetach()
            strict?.onDetach() // also detaches the shared tile provider
        }, 60_000)
    }

    private fun mapViews(
        context: Context,
        widgetId: Int,
        bitmap: Bitmap,
        label: String?,
        status: String?,
        updatedLabel: String?,
    ) =
        RemoteViews(context.packageName, R.layout.widget_map).apply {
            setImageViewBitmap(R.id.widget_map_image, bitmap)
            setViewVisibility(R.id.widget_map_loading_text, View.GONE)
            setViewVisibility(R.id.widget_map_progress, View.GONE)
            setViewVisibility(R.id.widget_map_refresh, View.VISIBLE)
            if (label != null) {
                setTextViewText(R.id.widget_map_title, label)
                setViewVisibility(R.id.widget_map_title, View.VISIBLE)
            } else {
                setViewVisibility(R.id.widget_map_title, View.GONE)
            }
            // Snapshot-time relative text; refreshed by the 30-min widget update cycle
            // and by every friends fetch.
            if (status != null) {
                setTextViewText(R.id.widget_map_status, status)
                setViewVisibility(R.id.widget_map_status, View.VISIBLE)
            } else {
                setViewVisibility(R.id.widget_map_status, View.GONE)
            }
            if (updatedLabel != null) {
                setTextViewText(R.id.widget_map_updated, updatedLabel)
                setViewVisibility(R.id.widget_map_updated, View.VISIBLE)
            } else {
                setViewVisibility(R.id.widget_map_updated, View.GONE)
            }
            attachClicks(this, context, javaClass, widgetId)
        }

    /**
     * Repaints the snapshot in place through the shared dark-map matrix. Draws from a
     * throwaway copy because a Canvas reading and writing the same bitmap is undefined.
     */
    private fun darkenMapTiles(bitmap: Bitmap) {
        val source = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Canvas(bitmap).drawBitmap(
            source, 0f, 0f, Paint().apply { colorFilter = DARK_MAP_COLOR_FILTER }
        )
        source.recycle()
    }

    private fun drawPins(
        context: Context,
        bitmap: Bitmap,
        projection: Projection,
        friends: List<Friend>,
        myPoint: GeoPoint?,
        focusedFriend: Friend?,
        focusedPinSize: Int,
    ) {
        val canvas = Canvas(bitmap)
        val point = Point()
        val size = PIN_SIZE_PX
        class Pin(val friend: Friend, val x: Int, val y: Int)
        // ponytail: pins outside the snapshot are simply skipped — no edge
        // indicators in the widget
        val pins = friends.filterNot { friend ->
            focusedFriend?.let { normalizeHandle(it.handle) == normalizeHandle(friend.handle) } == true
        }.mapNotNull { friend ->
            projection.toPixels(GeoPoint(friend.lat!!, friend.lon!!), point)
            if (point.x in -size..bitmap.width + size && point.y in -size..bitmap.height + size)
                Pin(friend, point.x, point.y) else null
        }
        val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Matches the in-app map: a near-black pin body disappears against dark tiles.
        val dark = isDarkTheme(context)
        val pinColor = mapMarkerBodyColor(dark)
        val pinRingColor = mapMarkerRingColor(dark)
        fun icon(friend: Friend) =
            friendPhotoMarkerIcon(context, friend, pinColor, pinRingColor, size).bitmap

        clusterByProximity(pins, { it.x }, { it.y }, { size }).forEach { cluster ->
            val cx = cluster.sumOf { it.x } / cluster.size
            val cy = cluster.sumOf { it.y } / cluster.size
            if (cluster.size == 1) {
                canvas.drawBitmap(
                    icon(cluster[0].friend), null,
                    Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
                    avatarPaint
                )
            } else {
                val n = cluster.size
                layoutClusterFaces(
                    pinSize = size,
                    memberSizes = IntArray(n) { size },
                    rawX = IntArray(n) { cluster[it].x },
                    rawY = IntArray(n) { cluster[it].y },
                ).forEach { face ->
                    val l = (cx + face.x - face.size / 2.0).roundToInt()
                    val t = (cy + face.y - face.size / 2.0).roundToInt()
                    canvas.drawBitmap(
                        icon(cluster[face.member].friend), null,
                        Rect(l, t, l + face.size, t + face.size),
                        avatarPaint
                    )
                }
            }
        }

        // Drawn last so my dot always sits on top of friend pins.
        myPoint?.let {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            projection.toPixels(it, point)
            paint.color = Color.WHITE
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 18f, paint)
            paint.color = 0xFF4285F4.toInt()
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 13f, paint)
        }

        // Match the in-app selected-friend state: a larger callout whose tip is
        // anchored at the exact reported coordinate, rather than a generic avatar
        // centered over it.
        focusedFriend?.let { friend ->
            projection.toPixels(GeoPoint(friend.lat!!, friend.lon!!), point)
            val icon = selectedFriendMarkerIcon(
                context, friend, pinColor, focusedPinSize
            ).bitmap
            canvas.drawBitmap(
                icon, null,
                Rect(
                    point.x - icon.width / 2,
                    point.y - icon.height,
                    point.x + icon.width / 2,
                    point.y,
                ),
                avatarPaint,
            )
        }
    }

    companion object {
        private const val ACTION_REFRESH = "io.github.jjoelj.findmyforwarder.WIDGET_REFRESH"
        private const val WIDGET_LOADING_MAX_MS = 12_000L
        private const val PIN_SIZE_PX = 96
        // Same selected-marker size used by the in-app map at the focused zoom.
        private const val FOCUSED_PIN_SIZE_PX = 192
        private const val SELECTED_CALLOUT_HEIGHT_SCALE = 1.55
        private const val FOCUSED_PIN_TOP_MARGIN_PX = 12

        private fun loadingViews(
            context: Context,
            providerClass: Class<*>,
            widgetId: Int,
            loading: Boolean,
            clearMap: Boolean = false,
            message: String? = null,
        ) =
            RemoteViews(context.packageName, R.layout.widget_map).apply {
                if (clearMap) {
                    setImageViewResource(R.id.widget_map_image, R.drawable.widget_map_loading)
                    setViewVisibility(R.id.widget_map_title, View.GONE)
                    setViewVisibility(R.id.widget_map_status, View.GONE)
                    setViewVisibility(R.id.widget_map_updated, View.GONE)
                    setTextViewText(R.id.widget_map_loading_text, message ?: "Loading…")
                    setViewVisibility(R.id.widget_map_loading_text, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.widget_map_loading_text, View.GONE)
                }
                setViewVisibility(
                    R.id.widget_map_progress, if (loading) View.VISIBLE else View.GONE
                )
                setViewVisibility(
                    R.id.widget_map_refresh, if (loading) View.GONE else View.VISIBLE
                )
                // Clicks must be (re)attached on every pushed RemoteViews — a partial
                // update that omits them on a launcher that dropped the earlier full
                // update would leave the button dead.
                attachClicks(this, context, providerClass, widgetId)
            }

        /** Open-app tap on the map, refresh tap on the button — set on every push. */
        private fun attachClicks(
            views: RemoteViews,
            context: Context,
            providerClass: Class<*>,
            widgetId: Int,
        ) {
            views.setOnClickPendingIntent(
                R.id.widget_map_image,
                PendingIntent.getActivity(
                    context, widgetId,
                    Intent(context, MainActivity::class.java).setData(
                        widgetIntentUri(providerClass, widgetId, "open")
                    ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_map_refresh,
                PendingIntent.getBroadcast(
                    context, widgetId,
                    Intent(context, providerClass).apply {
                        action = ACTION_REFRESH
                        data = widgetIntentUri(providerClass, widgetId, "refresh")
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        private val PROVIDERS = listOf(
            MapWidget::class.java, NearbyMapWidget::class.java, SingleFriendWidget::class.java
        )

        /** Extras are not part of PendingIntent identity; this URI is. */
        private fun widgetIntentUri(providerClass: Class<*>, widgetId: Int, action: String): Uri =
            "findmyforwarder://widget/${providerClass.name}/$widgetId/$action".toUri()

        private fun setLoading(
            context: Context,
            providerClass: Class<*>,
            ids: IntArray,
            loading: Boolean,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { widgetId ->
                manager.partiallyUpdateAppWidget(
                    widgetId, loadingViews(context, providerClass, widgetId, loading)
                )
            }
        }

        /** Trigger the refresh scope for one specific widget instance. */
        fun refreshData(context: Context, providerClass: Class<*>, widgetId: Int) {
            context.sendBroadcast(Intent(context, providerClass).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            })
        }

        /** Immediately replace an existing snapshot while a reconfiguration renders. */
        fun showLoading(context: Context, providerClass: Class<*>, widgetId: Int, message: String) {
            AppWidgetManager.getInstance(context).updateAppWidget(
                widgetId,
                loadingViews(
                    context, providerClass, widgetId, loading = true,
                    clearMap = true, message = message,
                )
            )
        }

        /** Re-render all placed widgets of every map-widget flavor; no-op when none exist. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            PROVIDERS.forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(Intent(context, cls).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    })
                }
            }
        }

        /** Re-render only the specified widget instances. */
        fun requestUpdate(context: Context, providerClass: Class<*>, ids: IntArray) {
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, providerClass).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
