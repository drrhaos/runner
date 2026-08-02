package com.runner.academy.ui.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.OsmMapConfig
import com.runner.academy.util.OsmMapTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Builds small map previews (OSM tiles + track) off the main thread.
 * Safe for RecyclerView — no [org.osmdroid.views.MapView] instances.
 */
object RouteMapBitmapRenderer {

    private const val TILE_SIZE = 256
    private const val MAX_DRAW_POINTS = 160
    private const val MIN_ZOOM = 11
    private const val MAX_ZOOM = 16
    private const val MAX_TILES_PER_PREVIEW = 25

    private val downloadSemaphore = Semaphore(4)
    private val cacheMutex = Mutex()
    private val memoryCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun getOrRender(
        context: Context,
        cacheKey: String,
        trackData: TrackData,
        widthPx: Int,
        heightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (widthPx <= 0 || heightPx <= 0 || trackData.points.size < 2) return@withContext null

        cacheMutex.withLock {
            memoryCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return@withContext it }
        }

        val rendered = render(context.applicationContext, trackData, widthPx, heightPx)
            ?: return@withContext null

        cacheMutex.withLock {
            memoryCache.put(cacheKey, rendered)
        }
        rendered
    }

    fun peek(cacheKey: String): Bitmap? =
        memoryCache.get(cacheKey)?.takeIf { !it.isRecycled }

    private suspend fun render(
        context: Context,
        trackData: TrackData,
        widthPx: Int,
        heightPx: Int
    ): Bitmap? {
        OsmMapConfig.apply(context)
        val points = downsample(trackData.points, MAX_DRAW_POINTS)
        if (points.size < 2) return null

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in points) {
            minLat = min(minLat, p.latitude)
            maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude)
            maxLon = max(maxLon, p.longitude)
        }
        val latPad = max((maxLat - minLat) * 0.18, 0.0008)
        val lonPad = max((maxLon - minLon) * 0.18, 0.0008)
        minLat -= latPad
        maxLat += latPad
        minLon -= lonPad
        maxLon += lonPad

        val zoom = chooseZoom(minLat, maxLat, minLon, maxLon, widthPx, heightPx)
        val n = 1 shl zoom

        val x0 = lonToPixelX(minLon, zoom)
        val x1 = lonToPixelX(maxLon, zoom)
        val y0 = latToPixelY(maxLat, zoom)
        val y1 = latToPixelY(minLat, zoom)

        val worldW = max(x1 - x0, 1.0)
        val worldH = max(y1 - y0, 1.0)
        val scale = min(widthPx / worldW, heightPx / worldH)
        val drawW = worldW * scale
        val drawH = worldH * scale
        val offsetX = (widthPx - drawW) / 2.0
        val offsetY = (heightPx - drawH) / 2.0

        fun mapX(lon: Double): Float =
            (offsetX + (lonToPixelX(lon, zoom) - x0) * scale).toFloat()

        fun mapY(lat: Double): Float =
            (offsetY + (latToPixelY(lat, zoom) - y0) * scale).toFloat()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(
            if (OsmMapTiles.isNightMode(context)) Color.parseColor("#1C1C1E")
            else Color.parseColor("#E8EEF4")
        )

        val tileMinX = floor(x0 / TILE_SIZE).toInt().coerceIn(0, n - 1)
        val tileMaxX = floor((x1 - 1e-6) / TILE_SIZE).toInt().coerceIn(0, n - 1)
        val tileMinY = floor(y0 / TILE_SIZE).toInt().coerceIn(0, n - 1)
        val tileMaxY = floor((y1 - 1e-6) / TILE_SIZE).toInt().coerceIn(0, n - 1)

        val tileCount = (tileMaxX - tileMinX + 1) * (tileMaxY - tileMinY + 1)
        if (tileCount in 1..MAX_TILES_PER_PREVIEW) {
            val night = OsmMapTiles.isNightMode(context)
            coroutineScope {
                val deferred = mutableListOf<Pair<Pair<Int, Int>, kotlinx.coroutines.Deferred<Bitmap?>>>()
                for (ty in tileMinY..tileMaxY) {
                    for (tx in tileMinX..tileMaxX) {
                        deferred.add((tx to ty) to async { loadTile(context, zoom, tx, ty, night) })
                    }
                }
                deferred.forEach { (xy, job) ->
                    val tile = job.await() ?: return@forEach
                    val (tx, ty) = xy
                    val tileLeft = offsetX + (tx * TILE_SIZE - x0) * scale
                    val tileTop = offsetY + (ty * TILE_SIZE - y0) * scale
                    val tileRight = tileLeft + TILE_SIZE * scale
                    val tileBottom = tileTop + TILE_SIZE * scale
                    canvas.drawBitmap(
                        tile,
                        null,
                        android.graphics.RectF(
                            tileLeft.toFloat(),
                            tileTop.toFloat(),
                            tileRight.toFloat(),
                            tileBottom.toFloat()
                        ),
                        null
                    )
                }
            }
        }

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(3f, widthPx / 32f)
            color = Color.RED
        }
        val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#34C759")
        }
        val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#007AFF")
        }

        val path = Path()
        var started = false
        var startXf = 0f
        var startYf = 0f
        var endXf = 0f
        var endYf = 0f
        for (point in points) {
            val x = mapX(point.longitude)
            val y = mapY(point.latitude)
            if (!started) {
                path.moveTo(x, y)
                startXf = x
                startYf = y
                started = true
            } else if (point.afterGap) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            endXf = x
            endYf = y
        }
        if (started) {
            canvas.drawPath(path, trackPaint)
            val r = max(3f, widthPx / 28f)
            canvas.drawCircle(startXf, startYf, r, startPaint)
            canvas.drawCircle(endXf, endYf, r, endPaint)
        }
        return bitmap
    }

    private fun chooseZoom(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        widthPx: Int,
        heightPx: Int
    ): Int {
        for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
            val w = lonToPixelX(maxLon, zoom) - lonToPixelX(minLon, zoom)
            val h = latToPixelY(minLat, zoom) - latToPixelY(maxLat, zoom)
            val tilesX = (w / TILE_SIZE) + 1
            val tilesY = (h / TILE_SIZE) + 1
            if (tilesX * tilesY <= MAX_TILES_PER_PREVIEW &&
                w >= widthPx * 0.35 &&
                h >= heightPx * 0.35
            ) {
                return zoom
            }
        }
        return MIN_ZOOM
    }

    private suspend fun loadTile(
        context: Context,
        zoom: Int,
        x: Int,
        y: Int,
        night: Boolean
    ): Bitmap? {
        val cacheFile = tileCacheFile(context, zoom, x, y, night)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        }
        val urls = tileUrls(zoom, x, y, night)
        for (url in urls) {
            val bytes = downloadBytes(url) ?: continue
            cacheFile.parentFile?.mkdirs()
            try {
                cacheFile.writeBytes(bytes)
            } catch (_: Exception) {
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return null
    }

    private fun tileCacheFile(
        context: Context,
        zoom: Int,
        x: Int,
        y: Int,
        night: Boolean
    ): File {
        val root = File(context.cacheDir, "osmdroid/preview_tiles")
        val theme = if (night) "dark" else "light"
        return File(root, "$theme/$zoom/$x/$y.png")
    }

    private fun tileUrls(zoom: Int, x: Int, y: Int, night: Boolean): List<String> {
        return if (night) {
            listOf(
                "https://a.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png",
                "https://b.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png"
            )
        } else {
            val s = arrayOf("a", "b", "c")[(x + y) % 3]
            listOf("https://$s.tile.openstreetmap.org/$zoom/$x/$y.png")
        }
    }

    private suspend fun downloadBytes(urlSpec: String): ByteArray? {
        return try {
            downloadSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    val connection = (URL(urlSpec).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", OsmMapConfig.USER_AGENT)
                        instanceFollowRedirects = true
                    }
                    try {
                        if (connection.responseCode !in 200..299) return@withContext null
                        connection.inputStream.use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun lonToPixelX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (lon + 180.0) / 360.0 * n * TILE_SIZE
    }

    private fun latToPixelY(lat: Double, zoom: Int): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = clamped * PI / 180.0
        val n = 1 shl zoom
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE_SIZE
    }

    private fun downsample(source: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (source.size <= maxPoints) return source
        val result = ArrayList<TrackPoint>(maxPoints)
        val step = (source.size - 1).toFloat() / (maxPoints - 1)
        var i = 0
        while (i < maxPoints) {
            val index = (i * step).toInt().coerceIn(0, source.lastIndex)
            result.add(source[index])
            i++
        }
        if (result.last() != source.last()) {
            result[result.lastIndex] = source.last()
        }
        return result
    }
}
