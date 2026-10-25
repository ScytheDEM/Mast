package au.signalatlas.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.max

/**
 * Draws the map into the car's surface. MapLibre 13.7.1 has no API to render into an external Surface (its renderers
 * are bound to a MapView's own SurfaceView or TextureView), so the map is a [MapSnapshotter] loop at most every
 * [FRAME_MS] (2 fps); the towers, the tower lines and the signal panel are painted over each snapshot on the canvas.
 */
internal class MapSurfaceRenderer(
    private val ctx: CarContext,
    private val source: CarDataSource,
) : SurfaceCallback {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var stable: Rect? = null
    private var job: Job? = null

    val camera = CarCamera(DEFAULT_LAT, DEFAULT_LON, CarCamera.DEFAULT_ZOOM)
    private var following = true
    val coverageOn = MutableStateFlow(false)

    private var snapper: MapSnapshotter? = null
    private var snapperKey: String? = null
    private val styleCache = HashMap<Boolean, String>()
    private var sites: List<CarSite> = emptyList()
    private var sitesAt: Triple<Double, Double, Long>? = null
    private var last: Bitmap? = null
    private var lastCamera: CarCamera? = null
    private var painter: SignalPanelPainter? = null
    private var painterUnit = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun recenter() { following = true }
    fun zoomBy(d: Double) = camera.zoomBy(d)
    fun toggleCoverage() { coverageOn.value = !coverageOn.value }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        MapLibre.getInstance(ctx.applicationContext)
        job = scope.launch {
            while (true) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    renderOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    // A failed frame keeps the previous image; the next tick tries again.
                }
                delay(max(50L, FRAME_MS - (SystemClock.elapsedRealtime() - t0)))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        snapper?.cancel()
        snapper = null
        snapperKey = null
    }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
        snapper?.cancel()
        snapper = null
        snapperKey = null
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        stable = stableArea
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        camera.pan(distanceX / PIXEL_RATIO, distanceY / PIXEL_RATIO)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor > 0f) camera.zoomBy(ln(scaleFactor.toDouble()) / ln(2.0))
    }

    // --- frames ---

    private suspend fun renderOnce() {
        val surf = surface?.takeIf { it.isValid } ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val fix = source.fix.value
        if (following && fix != null) {
            camera.lat = fix.lat
            camera.lon = fix.lon
        }
        val dark = ctx.isDarkMode
        val template = if (coverageOn.value) source.coverageTemplate.first() else null
        val snapW = (w / PIXEL_RATIO).toInt().coerceAtLeast(64)
        val snapH = (h / PIXEL_RATIO).toInt().coerceAtLeast(64)
        val key = "$dark|$template|$snapW|$snapH"
        if (key != snapperKey) {
            snapper?.cancel()
            val base = styleCache.getOrPut(dark) { source.styleJson(dark) ?: MapStyle.blank(dark) }
            val json = if (template != null) MapStyle.withCoverage(base, template) else base
            snapper = MapSnapshotter(
                ctx.applicationContext,
                MapSnapshotter.Options(snapW, snapH).withPixelRatio(PIXEL_RATIO).withLogo(false).withAttribution(false)
                    .withStyleBuilder(Style.Builder().fromJson(json)),
            )
            snapperKey = key
        }
        refreshSites(fix?.lat ?: camera.lat, fix?.lon ?: camera.lon)
        val shot = snapshot(snapper ?: return, camera)
        if (shot != null) {
            last = shot
            lastCamera = CarCamera(camera.lat, camera.lon, camera.zoom)
        }
        draw(surf, w, h, dark, snapW, snapH, fix)
    }

    private suspend fun snapshot(s: MapSnapshotter, cam: CarCamera): Bitmap? = suspendCancellableCoroutine { cont ->
        s.setCameraPosition(CameraPosition.Builder().target(LatLng(cam.lat, cam.lon)).zoom(cam.zoom).build())
        cont.invokeOnCancellation { s.cancel() }
        s.start({ snap -> if (cont.isActive) cont.resume(snap.bitmap) }, { if (cont.isActive) cont.resume(null) })
    }

    private suspend fun refreshSites(lat: Double, lon: Double) {
        val at = sitesAt
        val now = SystemClock.elapsedRealtime()
        if (at != null && now - at.third < SITES_MS && Distance.m(at.first, at.second, lat, lon) < SITES_MOVE_M) return
        sitesAt = Triple(lat, lon, now)
        sites = withContext(Dispatchers.IO) { source.sitesNear(lat, lon) }
    }

    private fun draw(surf: Surface, w: Int, h: Int, dark: Boolean, snapW: Int, snapH: Int, fix: CarFix?) {
        val canvas: Canvas = (try { surf.lockCanvas(null) } catch (_: Exception) { null }) ?: return
        try {
            val pal = CarPalette(dark)
            canvas.drawColor(if (dark) 0xFF1B2733.toInt() else 0xFFE4EEF2.toInt())
            last?.let { canvas.drawBitmap(it, null, Rect(0, 0, w, h), null) }
            // Overlays use the camera the bitmap was rendered with, so they never drift from the map.
            val cam = lastCamera ?: camera
            val k = w.toFloat() / snapW
            fun pt(lat: Double, lon: Double): Pair<Float, Float> =
                cam.project(lat, lon, snapW.toFloat(), snapH.toFloat()).let { (x, y) -> x * k to y * k }
            val unit = h / 720f
            val sims = source.sims.value

            dot.style = Paint.Style.FILL
            for (s in sites) {
                val (x, y) = pt(s.lat, s.lon)
                if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue
                dot.color = pal.halo; canvas.drawCircle(x, y, unit * 8f, dot)
                dot.color = pal.tower; canvas.drawCircle(x, y, unit * 5.5f, dot)
            }
            if (fix != null) {
                val (ux, uy) = pt(fix.lat, fix.lon)
                // SIM 2 is drawn first so SIM 1's solid line stays on top where they share a tower.
                for (sim in sims.sortedByDescending { it.slot }) {
                    val lat = sim.towerLat ?: continue
                    val lon = sim.towerLon ?: continue
                    drawTowerLine(canvas, sim, ux, uy, pt(lat, lon), pal, unit)
                }
                drawUser(canvas, ux, uy, fix.headingDeg, pal, unit)
            }
            val area = stable?.let { RectF(it) } ?: RectF(0f, 0f, w.toFloat(), h.toFloat())
            val p = painter?.takeIf { painterUnit == unit } ?: SignalPanelPainter(unit).also { painter = it; painterUnit = unit }
            p.draw(canvas, sims, area, pal)
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawTowerLine(canvas: Canvas, sim: CarSim, ux: Float, uy: Float, tower: Pair<Float, Float>, pal: CarPalette, unit: Float) {
        val path = Path().apply { moveTo(ux, uy); lineTo(tower.first, tower.second) }
        linePaint.pathEffect = if (sim.slot >= 2) DashPathEffect(floatArrayOf(unit * 22f, unit * 14f), 0f) else null
        linePaint.color = pal.halo; linePaint.strokeWidth = unit * 9f; canvas.drawPath(path, linePaint)
        linePaint.color = pal.simColor(sim); linePaint.strokeWidth = unit * 5f; canvas.drawPath(path, linePaint)
        linePaint.pathEffect = null
        dot.style = Paint.Style.FILL
        dot.color = pal.halo; canvas.drawCircle(tower.first, tower.second, unit * 15f, dot)
        dot.color = pal.simColor(sim); canvas.drawCircle(tower.first, tower.second, unit * 11f, dot)
        dot.color = pal.halo; canvas.drawCircle(tower.first, tower.second, unit * 4.5f, dot)
    }

    private fun drawUser(canvas: Canvas, x: Float, y: Float, heading: Double?, pal: CarPalette, unit: Float) {
        canvas.save()
        canvas.translate(x, y)
        dot.style = Paint.Style.FILL
        if (heading == null) {
            dot.color = pal.halo; canvas.drawCircle(0f, 0f, unit * 16f, dot)
            dot.color = USER_BLUE; canvas.drawCircle(0f, 0f, unit * 11f, dot)
        } else {
            canvas.rotate(heading.toFloat())
            val arrow = Path().apply {
                moveTo(0f, -unit * 26f); lineTo(unit * 18f, unit * 18f); lineTo(0f, unit * 8f); lineTo(-unit * 18f, unit * 18f); close()
            }
            linePaint.color = pal.halo; linePaint.strokeWidth = unit * 6f; canvas.drawPath(arrow, linePaint)
            dot.color = USER_BLUE; canvas.drawPath(arrow, dot)
        }
        canvas.restore()
    }

    private companion object {
        const val FRAME_MS = 500L // at most 2 fps
        const val PIXEL_RATIO = 2f
        const val SITES_MS = 15_000L
        const val SITES_MOVE_M = 400.0
        const val DEFAULT_LAT = -33.8688
        const val DEFAULT_LON = 151.2093
        const val USER_BLUE = 0xFF1E88E5.toInt()
    }
}

internal object Distance {
    fun m(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6_371_000.0 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
