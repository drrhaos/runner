package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import kotlin.math.roundToLong

/**
 * What the route previews draw (the list bitmap, the route picker), kept in
 * `Workout.routePreview` so a page of the list never loads the track JSON.
 *
 * @property pointCount points of the whole track, before thinning (the picker shows it)
 * @property runs the thinned runs, drawn as they are ([TrackRunPaths])
 */
data class RoutePreview(val pointCount: Int, val runs: List<TrackRun>)

object RoutePreviews {

    /** Points the previews draw; each solid also keeps both of its ends. */
    const val MAX_POINTS = 160

    /** Null for fewer than two points: nothing to draw. */
    fun of(points: List<TrackPoint>): RoutePreview? {
        if (points.size < 2) return null
        return RoutePreview(points.size, TrackRuns.downsample(TrackRuns.split(points), MAX_POINTS))
    }
}

/**
 * Text form of a [RoutePreview]: `1|<pointCount>|S:<lat>,<lon>;…|G|S:…|B|S:…|P|S:…`.
 * - `1` is the format version.
 * - Coordinates are in 1e-6 degrees (about 0.1 m), each a delta from the previous point
 *   (the first one from 0,0), so a 160-point route takes a couple of kilobytes.
 * - Between solids: `G` a gap, `B` a bridge, `P` a bridge counted by steps.
 *
 * Decoded points carry what [TrackRuns.split] reads ([TrackPoint.afterGap], a stub
 * [TrackPoint.bridgeMeters], [LocationSource.PEDOMETER]), so the renderers draw them unchanged.
 */
object RoutePreviewCodec {

    private const val FORMAT_VERSION = "1"
    private const val SEPARATOR = '|'
    private const val SOLID_PREFIX = "S:"
    private const val POINT_SEPARATOR = ';'
    private const val COORD_SEPARATOR = ','
    private const val GAP = "G"
    private const val BRIDGE = "B"
    private const val STEP_BRIDGE = "P"
    private const val SCALE = 1_000_000.0
    private const val MAX_LAT = 90_000_000L
    private const val MAX_LON = 180_000_000L

    /** Only "it is a bridge" matters to the renderers; the distance is not kept. */
    private const val BRIDGE_STUB_METERS = 1f

    fun encode(preview: RoutePreview): String {
        val out = StringBuilder()
        out.append(FORMAT_VERSION).append(SEPARATOR).append(preview.pointCount)
        var lastLat = 0L
        var lastLon = 0L
        for (run in preview.runs) {
            out.append(SEPARATOR)
            when (run) {
                is TrackRun.Gap -> out.append(GAP)
                is TrackRun.Bridge -> out.append(if (run.fromPedometer) STEP_BRIDGE else BRIDGE)
                is TrackRun.Solid -> {
                    out.append(SOLID_PREFIX)
                    run.points.forEachIndexed { index, point ->
                        val lat = (point.latitude * SCALE).roundToLong()
                        val lon = (point.longitude * SCALE).roundToLong()
                        if (index > 0) out.append(POINT_SEPARATOR)
                        out.append(lat - lastLat).append(COORD_SEPARATOR).append(lon - lastLon)
                        lastLat = lat
                        lastLon = lon
                    }
                }
            }
        }
        return out.toString()
    }

    /** Null for null, blank or malformed text (never throws): the row then shows no route. */
    fun decode(text: String?): RoutePreview? {
        if (text.isNullOrBlank()) return null
        return try {
            parse(text)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun parse(text: String): RoutePreview? {
        val parts = text.split(SEPARATOR)
        if (parts.size < 3 || parts[0] != FORMAT_VERSION) return null
        val pointCount = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null

        val points = mutableListOf<TrackPoint>()
        var lat = 0L
        var lon = 0L
        // Marks the first point of the next solid with the separator before it
        var pending: TrackPoint.() -> TrackPoint = { this }
        var expectSolid = true
        for (part in parts.drop(2)) {
            if (expectSolid) {
                if (!part.startsWith(SOLID_PREFIX)) return null
                val pairs = part.substring(SOLID_PREFIX.length).split(POINT_SEPARATOR)
                pairs.forEachIndexed { index, pair ->
                    val coords = pair.split(COORD_SEPARATOR)
                    if (coords.size != 2) return null
                    // addExact throws (caught by decode) where += would wrap around;
                    // ranges, not abs(): abs(Long.MIN_VALUE) is negative
                    lat = Math.addExact(lat, coords[0].toLongOrNull() ?: return null)
                    lon = Math.addExact(lon, coords[1].toLongOrNull() ?: return null)
                    if (lat !in -MAX_LAT..MAX_LAT || lon !in -MAX_LON..MAX_LON) return null
                    val point = TrackPoint(lat / SCALE, lon / SCALE, 0L, null, null, null)
                    points.add(if (index == 0) point.pending() else point)
                }
            } else {
                pending = when (part) {
                    GAP -> { { copy(afterGap = true) } }
                    BRIDGE -> { { copy(afterGap = true, bridgeMeters = BRIDGE_STUB_METERS) } }
                    STEP_BRIDGE -> {
                        {
                            copy(
                                afterGap = true,
                                bridgeMeters = BRIDGE_STUB_METERS,
                                source = LocationSource.PEDOMETER.name
                            )
                        }
                    }
                    else -> return null
                }
            }
            expectSolid = !expectSolid
        }
        // Must end with a solid, and draw at least a line
        if (expectSolid || points.size < 2) return null
        return RoutePreview(pointCount, TrackRuns.split(points))
    }
}
