package io.github.jjoelj.findmyforwarder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.text.format.DateFormat
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jjoelj.findmyforwarder.ui.theme.FindMyForwarderTheme
import org.osmdroid.util.GeoPoint
import java.util.Date

/**
 * "Track a Friend" home-screen widget: the same map snapshot, framed on one
 * friend chosen when the widget is placed. Falls back to the fit-everyone
 * framing until a friend is picked or while they have no location.
 */
class SingleFriendWidget : MapWidget() {

    // ponytail: building-level; drop toward 16 if a single friend feels too tight
    override val singlePointZoom = 18.0

    // Keep headroom for the selected callout while its tip stays on the true location.
    override val focusedPointVerticalPosition = 0.65

    override fun pointsToFit(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<GeoPoint> {
        val friend = trackedFriend(context, widgetId, friends)
            ?: return emptyList()
        // A single point renders centered on the friend at street-level zoom.
        return listOf(GeoPoint(friend.lat!!, friend.lon!!))
    }

    override fun focusedFriend(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
    ): Friend? = trackedFriend(context, widgetId, friends)

    override fun refreshHandles(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<String> = trackedFriend(context, widgetId, friends)?.let { listOf(it.handle) }
        ?: emptyList()

    override fun widgetLabel(context: Context, widgetId: Int, friends: List<Friend>): String? =
        trackedFriend(context, widgetId, friends)?.let { it.name ?: it.handle }

    override fun widgetUpdatedLabel(context: Context): String? = null

    override fun widgetStatus(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): String? {
        val friend = trackedFriend(context, widgetId, friends) ?: return null
        val distance = myPoint?.takeIf { friend.hasLocation }?.let {
            val out = FloatArray(1)
            Location.distanceBetween(it.latitude, it.longitude, friend.lat!!, friend.lon!!, out)
            val miles = out[0] / 1609.344
            when {
                miles < 0.1 -> "nearby"
                miles < 10 -> "%.1f mi".format(miles)
                else -> "%,d mi".format(miles.roundToInt())
            }
        }
        // Widgets may keep this snapshot for a while, so use the actual local time
        // instead of a relative label that silently becomes stale.
        val time = friend.timestamp.takeIf { it > 0 }?.let { epochSeconds ->
            DateFormat.getTimeFormat(context).format(Date(epochSeconds * 1_000L))
        }
        return listOfNotNull(distance, friend.address, time)
            .ifEmpty { return null }
            .joinToString(" · ")
    }

    private fun trackedFriend(context: Context, widgetId: Int, friends: List<Friend>): Friend? {
        val handle = SharedPreferencesProvider(context).widgetFriendHandle(widgetId)
            ?.let(::normalizeHandle) ?: return null
        return friends.firstOrNull { normalizeHandle(it.handle) == handle }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = SharedPreferencesProvider(context)
        ids.forEach { prefs.setWidgetFriendHandle(it, null) }
    }
}

/** Widget-placement config screen: pick which friend this widget tracks. */
class SingleFriendWidgetConfigActivity : ComponentActivity() {
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
                val friends by produceState(emptyList<Friend>()) {
                    value = loadCachedFriends(this@SingleFriendWidgetConfigActivity)
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Track which friend?", style = MaterialTheme.typography.titleLarge)
                    if (friends.isEmpty()) {
                        Text(
                            "No friends loaded yet. Open the app first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(friends, key = { it.handle }) { friend ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickFriend(widgetId, friend) }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        friend.name ?: friend.handle,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (friend.name != null) {
                                        Text(
                                            friend.handle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

    private fun pickFriend(widgetId: Int, friend: Friend) {
        SharedPreferencesProvider(this).setWidgetFriendHandle(widgetId, friend.handle)
        MapWidget.showLoading(this, SingleFriendWidget::class.java, widgetId, "Loading friend…")
        // Re-render immediately with the new pick, and pull fresh locations so the
        // newly tracked friend isn't framed on a stale cached position.
        MapWidget.requestUpdate(this, SingleFriendWidget::class.java, intArrayOf(widgetId))
        MapWidget.refreshData(this, SingleFriendWidget::class.java, widgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
        moveTaskToBack(true)
    }
}
