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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.concurrent.thread
import kotlin.math.roundToInt

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

    /** Zoom-out cap: framing never zooms out further than this level. */
    protected open val minZoom = 3.0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appContext = context.applicationContext
            setLoading(appContext, loading = true)
            val result = goAsync()
            thread {
                try {
                    // Writes the cache and triggers requestUpdate → re-render.
                    runBlocking { fetchFriends(appContext) }
                } catch (e: Exception) {
                    FileLogger.w("Widget friends refresh failed: ${e.message}")
                    requestUpdate(appContext) // re-render from cache; clears the spinner
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
        if (points.isEmpty()) {
            manager.partiallyUpdateAppWidget(widgetId, loadingViews(context, loading = false))
            return
        }
        manager.partiallyUpdateAppWidget(widgetId, loadingViews(context, loading = true))

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
            zoom = 15.0
        } else {
            val box = BoundingBox.fromGeoPointsSafe(group).increaseByScale(1.3f)
            center = GeoPoint(box.centerLatitude, box.centerLongitude)
            zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width, height)
                .coerceIn(minZoom, 17.0)
        }
        val projection = Projection(zoom, width, height, center, 0f, true, false, 0, 0)

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
                    drawPins(context, bitmap, projection, friends, myPoint)
                    manager.updateAppWidget(widgetId, mapViews(context, bitmap))
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
        manager.partiallyUpdateAppWidget(widgetId, loadingViews(context, loading = false))
        // ponytail: fixed grace period for the late strict render, then free the
        // tile provider; a render still pending at +60s just keeps the fallback image
        mainHandler.postDelayed({
            fallback?.onDetach()
            strict?.onDetach() // also detaches the shared tile provider
        }, 60_000)
    }

    private fun mapViews(context: Context, bitmap: Bitmap) =
        RemoteViews(context.packageName, R.layout.widget_map).apply {
            setImageViewBitmap(R.id.widget_map_image, bitmap)
            setViewVisibility(R.id.widget_map_progress, View.GONE)
            setViewVisibility(R.id.widget_map_refresh, View.VISIBLE)
            // Snapshot-time relative text; refreshed by the 30-min widget update cycle
            // and by every friends fetch.
            lastPushedLabel(SharedPreferencesProvider(context).lastPushedAtMillis)?.let {
                setTextViewText(R.id.widget_map_status, it)
                setViewVisibility(R.id.widget_map_status, View.VISIBLE)
            }
            setOnClickPendingIntent(
                R.id.widget_map_image,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            setOnClickPendingIntent(
                R.id.widget_map_refresh,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, javaClass).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

    private fun drawPins(
        context: Context,
        bitmap: Bitmap,
        projection: Projection,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ) {
        val canvas = Canvas(bitmap)
        val point = Point()
        val size = PIN_SIZE_PX
        class Pin(val friend: Friend, val x: Int, val y: Int)
        // ponytail: pins outside the snapshot are simply skipped — no edge
        // indicators in the widget
        val pins = friends.mapNotNull { friend ->
            projection.toPixels(GeoPoint(friend.lat!!, friend.lon!!), point)
            if (point.x in -size..bitmap.width + size && point.y in -size..bitmap.height + size)
                Pin(friend, point.x, point.y) else null
        }
        val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun icon(friend: Friend) =
            friendPhotoMarkerIcon(context, friend, PIN_COLOR, PIN_TEXT_COLOR, size).bitmap

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
    }

    companion object {
        private const val ACTION_REFRESH = "io.github.jjoelj.findmyforwarder.WIDGET_REFRESH"
        private const val PIN_SIZE_PX = 96
        private val PIN_COLOR = Color.rgb(20, 30, 44)
        private val PIN_TEXT_COLOR = Color.rgb(134, 166, 190)

        private fun loadingViews(context: Context, loading: Boolean) =
            RemoteViews(context.packageName, R.layout.widget_map).apply {
                setViewVisibility(
                    R.id.widget_map_progress, if (loading) View.VISIBLE else View.GONE
                )
                setViewVisibility(
                    R.id.widget_map_refresh, if (loading) View.GONE else View.VISIBLE
                )
            }

        private val PROVIDERS = listOf(
            MapWidget::class.java, NearbyMapWidget::class.java, SingleFriendWidget::class.java
        )

        private fun setLoading(context: Context, loading: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            PROVIDERS.forEach { cls ->
                manager.getAppWidgetIds(ComponentName(context, cls)).forEach {
                    manager.partiallyUpdateAppWidget(it, loadingViews(context, loading))
                }
            }
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
    }
}
