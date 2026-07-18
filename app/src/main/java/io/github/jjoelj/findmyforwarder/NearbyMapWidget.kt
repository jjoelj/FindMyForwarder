package io.github.jjoelj.findmyforwarder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jjoelj.findmyforwarder.ui.theme.FindMyForwarderTheme
import org.osmdroid.util.GeoPoint

/** A map widget focused on the closest friends within its configured radius. */
class NearbyMapWidget : MapWidget() {

    // The mirrored frame must fit on the widget's limiting (shorter) axis. A
    // fixed zoom-out floor could otherwise crop a friend exactly at the radius.
    override val minZoom = 3.0

    override fun widgetLabel(context: Context, widgetId: Int, friends: List<Friend>): String {
        val myPoint = myPoint(context) ?: return "Nearby"
        return "Nearby (${nearbyFriends(context, widgetId, friends, myPoint).size})"
    }

    override fun widgetStatus(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): String = "Within ${SharedPreferencesProvider(context).nearbyWidgetRadiusMiles(widgetId)} mi"

    override fun pointsToFit(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<GeoPoint> {
        // No location of our own yet? Fall back to the fit-everyone framing.
        if (myPoint == null) return super.pointsToFit(context, widgetId, friends, myPoint)
        val closest = nearbyFriends(context, widgetId, friends, myPoint)
        // Each friend is paired with its mirror image across my location, so the
        // bounding box (and therefore the frame) is always centered exactly on me.
        return closest.flatMap {
            val p = GeoPoint(it.lat!!, it.lon!!)
            val mirror = GeoPoint(
                (2 * myPoint.latitude - p.latitude).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT),
                (2 * myPoint.longitude - p.longitude).coerceIn(-180.0, 180.0),
            )
            listOf(p, mirror)
        } + myPoint
    }

    override fun refreshHandles(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<String>? {
        // Without our location this widget cannot identify a nearby frame. Do not
        // turn its refresh button into an unexpected all-friends refresh.
        if (myPoint == null) return emptyList()
        return nearbyFriends(context, widgetId, friends, myPoint).map { it.handle }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = SharedPreferencesProvider(context)
        ids.forEach { prefs.setNearbyWidgetRadiusMiles(it, null) }
    }

    private fun nearbyFriends(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint,
    ): List<Friend> {
        val maxDistanceMeters = SharedPreferencesProvider(context)
            .nearbyWidgetRadiusMiles(widgetId) * METERS_PER_MILE
        return friends.filter { it.hasLocation }
            .filter { myPoint.distanceToAsDouble(GeoPoint(it.lat!!, it.lon!!)) <= maxDistanceMeters }
            .sortedBy { myPoint.distanceToAsDouble(GeoPoint(it.lat!!, it.lon!!)) }
            .take(MAX_NEARBY_FRIENDS)
    }

    private fun myPoint(context: Context): GeoPoint? {
        val prefs = SharedPreferencesProvider(context)
        val lat = prefs.lastSentLat.toDoubleOrNull() ?: return null
        val lon = prefs.lastSentLon.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    private companion object {
        const val MAX_MERCATOR_LAT = 85.0
        const val METERS_PER_MILE = 1_609.344
        const val MAX_NEARBY_FRIENDS = 5
    }
}

/** Widget-placement and reconfiguration screen for a Nearby widget's radius. */
class NearbyMapWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            FindMyForwarderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("How close is nearby?", style = MaterialTheme.typography.titleLarge)
                        RADIUS_OPTIONS_MILES.forEach { miles ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { saveRadius(widgetId, miles) }
                            ) {
                                Text(
                                    "$miles miles",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveRadius(widgetId: Int, miles: Int) {
        SharedPreferencesProvider(this).setNearbyWidgetRadiusMiles(widgetId, miles)
        MapWidget.showLoading(this, NearbyMapWidget::class.java, widgetId, "Loading nearby friends…")
        MapWidget.requestUpdate(this, NearbyMapWidget::class.java, intArrayOf(widgetId))
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
        moveTaskToBack(true)
    }

    private companion object {
        val RADIUS_OPTIONS_MILES = listOf(5, 10, 25, 50, 100)
    }
}
