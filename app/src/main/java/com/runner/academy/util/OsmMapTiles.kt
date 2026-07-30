package com.runner.academy.util

import android.content.Context
import android.content.res.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView

/**
 * Picks OSM basemap for the current UI theme.
 * Light: standard Mapnik. Dark: CARTO Dark Matter.
 */
object OsmMapTiles {

    private val cartoDark: ITileSource = object : XYTileSource(
        "CartoDarkMatter",
        1,
        20,
        256,
        ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    ) {}

    fun isNightMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun tileSourceFor(context: Context): ITileSource =
        if (isNightMode(context)) cartoDark else TileSourceFactory.MAPNIK

    fun applyForTheme(context: Context, mapView: MapView) {
        val desired = tileSourceFor(context)
        if (mapView.tileProvider.tileSource.name() != desired.name()) {
            mapView.setTileSource(desired)
            mapView.invalidate()
        }
    }
}
