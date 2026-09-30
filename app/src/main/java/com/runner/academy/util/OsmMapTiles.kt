package com.runner.academy.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView

/**
 * Picks OSM basemap styling for the current UI theme.
 *
 * Both themes use standard Mapnik tiles (no API key). Dark theme renders them through
 * [darkTilesFilter]: invert + 180° hue rotation, so the background becomes dark while
 * water stays blue and parks stay green.
 */
object OsmMapTiles {

    private val DARK_LOADING_BACKGROUND = Color.parseColor("#1C1C1E")
    private val DARK_LOADING_LINES = Color.parseColor("#2C2C2E")

    /** Invert → rotate hue 180° → dim slightly. */
    val darkTilesFilter: ColorMatrixColorFilter by lazy {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Hue rotation by 180° (luminance weights 0.213 / 0.715 / 0.072).
        val hue180 = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) }
        val result = ColorMatrix(invert)
        result.postConcat(hue180)
        result.postConcat(dim)
        ColorMatrixColorFilter(result)
    }

    fun isNightMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun applyForTheme(context: Context, mapView: MapView) {
        val desired = TileSourceFactory.MAPNIK
        if (mapView.tileProvider.tileSource.name() != desired.name()) {
            mapView.setTileSource(desired)
        }
        val tilesOverlay = mapView.overlayManager.tilesOverlay
        if (isNightMode(context)) {
            tilesOverlay.setColorFilter(darkTilesFilter)
            tilesOverlay.loadingBackgroundColor = DARK_LOADING_BACKGROUND
            tilesOverlay.loadingLineColor = DARK_LOADING_LINES
        } else {
            tilesOverlay.setColorFilter(null)
            tilesOverlay.loadingBackgroundColor = Color.rgb(216, 208, 208)
            tilesOverlay.loadingLineColor = Color.rgb(200, 192, 192)
        }
        mapView.invalidate()
    }
}
