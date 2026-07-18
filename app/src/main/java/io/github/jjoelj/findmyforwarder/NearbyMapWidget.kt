package io.github.jjoelj.findmyforwarder

import android.content.Context
import org.osmdroid.util.GeoPoint

/**
 * "Nearby" home-screen widget: the same map snapshot, but framed on your last
 * sent location and fitted to just the closest friends. Friends beyond the
 * closest N still draw if they happen to fall inside the frame.
 */
class NearbyMapWidget : MapWidget() {

    // ponytail: zoom 10 ≈ a metro area; bump toward 12 if "nearby" should mean
    // your side of town, drop toward 8 for a wider region
    override val minZoom = 10.0

    override fun pointsToFit(
        context: Context,
        widgetId: Int,
        friends: List<Friend>,
        myPoint: GeoPoint?,
    ): List<GeoPoint> {
        // No location of our own yet? Fall back to the fit-everyone framing.
        if (myPoint == null) return super.pointsToFit(context, widgetId, friends, myPoint)
        // ponytail: fixed closest-5; add a widget config screen if N needs to vary
        val closest = friends
            .sortedBy { myPoint.distanceToAsDouble(GeoPoint(it.lat!!, it.lon!!)) }
            .take(5)
        // Each friend is paired with its mirror image across my location, so the
        // bounding box (and therefore the frame) is always centered exactly on me —
        // even when the zoom cap trims a distant friend out of view.
        return closest.flatMap {
            val p = GeoPoint(it.lat!!, it.lon!!)
            val mirror = GeoPoint(
                (2 * myPoint.latitude - p.latitude).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT),
                (2 * myPoint.longitude - p.longitude).coerceIn(-180.0, 180.0),
            )
            listOf(p, mirror)
        } + myPoint
    }

    private companion object {
        // Web-Mercator can't render latitudes past ~85°.
        const val MAX_MERCATOR_LAT = 85.0
    }
}
