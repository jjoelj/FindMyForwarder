package io.github.jjoelj.findmyforwarder

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Ink for a marker's initial. Always drawn on a light avatar disc, so unlike the pin
 * body it must stay dark whatever the basemap is doing.
 */
val MARKER_INK: Int = Color.rgb(20, 30, 44)

/** Pin body; has to contrast with the basemap, so it flips with the theme. */
fun mapMarkerBodyColor(dark: Boolean): Int =
    if (dark) Color.rgb(178, 173, 199) else Color.rgb(20, 30, 44)

/** Ring around the avatar, and the callout stem. */
fun mapMarkerRingColor(dark: Boolean): Int =
    if (dark) Color.rgb(124, 107, 240) else Color.rgb(134, 166, 190)

/**
 * Dark basemap filter, equivalent to CSS `invert(1) hue-rotate(180deg)`.
 *
 * osmdroid's TilesOverlay.INVERT_COLORS inverts RGB outright, which flips hue as well as
 * lightness: zoomed out, ocean lands on muddy orange and parks on pink. Rotating hue a
 * half turn afterwards puts those back — water reads blue, green space reads green —
 * while land still ends up near black.
 *
 * Shared by the in-app map and the widget snapshots so the two cannot drift apart.
 */
val DARK_MAP_COLOR_FILTER: ColorMatrixColorFilter by lazy {
    val filter = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    // Luminance-preserving hue rotation at 180 degrees (rows sum to 1, so greys stay grey
    // and only the colour axes move). Applied after the inversion.
    filter.postConcat(
        ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    // Inverting a light basemap leaves it reading brighter than a designed dark one, so
    // pull it down afterwards. Tune by eye — these are the knobs, not the matrix above.
    filter.postConcat(ColorMatrix().apply { setSaturation(DARK_MAP_SATURATION) })
    filter.postConcat(
        ColorMatrix(
            floatArrayOf(
                DARK_MAP_BRIGHTNESS, 0f, 0f, 0f, 0f,
                0f, DARK_MAP_BRIGHTNESS, 0f, 0f, 0f,
                0f, 0f, DARK_MAP_BRIGHTNESS, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    ColorMatrixColorFilter(filter)
}

/** Overall lightness of the dark basemap. Lower is dimmer. */
private const val DARK_MAP_BRIGHTNESS = 0.52f

/** How much colour survives the dim. 1.0 keeps it fully saturated. */
private const val DARK_MAP_SATURATION = 0.80f

/**
 * Contact photos are arbitrary bright images, so on a dark basemap they glare next to
 * the dimmed tiles. Knocks them back without touching hue.
 */
val DIM_PHOTO_COLOR_FILTER: ColorMatrixColorFilter by lazy {
    val m = ColorMatrix().apply { setSaturation(0.90f) }
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                PHOTO_BRIGHTNESS, 0f, 0f, 0f, 0f,
                0f, PHOTO_BRIGHTNESS, 0f, 0f, 0f,
                0f, 0f, PHOTO_BRIGHTNESS, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    ColorMatrixColorFilter(m)
}

private const val PHOTO_BRIGHTNESS = 0.74f
