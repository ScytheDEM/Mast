package au.signalatlas.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/** Where the map looks. Keep one per screen (e.g. in a ViewModel) so the view survives recreation. */
class MapCameraState(initialLat: Double = -27.0, initialLon: Double = 134.0, initialZoom: Double = 3.6) {
    // The last settled camera, so a recreated MapView reopens where the user left it.
    internal var lat = initialLat
    internal var lon = initialLon
    internal var zoom = initialZoom
    internal var map: MapLibreMap? = null
    private var pending: Triple<Double, Double, Double>? = null

    /** The last settled zoom (the initial one before the map has been touched). */
    val currentZoom: Double get() = zoom

    fun animateTo(lat: Double, lon: Double, zoom: Double) {
        val m = map
        if (m == null) {
            pending = Triple(lat, lon, zoom)
            this.lat = lat
            this.lon = lon
            this.zoom = zoom
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), ANIMATION_MS)
        }
    }

    internal fun attach(m: MapLibreMap) {
        map = m
        pending?.let { (la, lo, z) -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), z)) }
        pending = null
    }

    internal fun record(position: CameraPosition) {
        position.target?.let { lat = it.latitude; lon = it.longitude }
        zoom = position.zoom
    }

    private companion object {
        const val ANIMATION_MS = 600
    }
}

private const val OVERLAY_SWAP_TIMEOUT_MS = 6_000L

/**
 * Suspends until [sourceId] has delivered tiles and a frame has then rendered completely, or [timeoutMs] passes
 * (a swap must not wait forever on a stuck tile: the old pair is replaced anyway).
 */
private suspend fun awaitSourceRendered(mapView: MapView, sourceId: String, timeoutMs: Long) {
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var changed = false
            val onSource = MapView.OnSourceChangedListener { id -> if (id == sourceId) changed = true }
            val onFrame = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                if (fully && changed && cont.isActive) cont.resume(Unit)
            }
            mapView.addOnSourceChangedListener(onSource)
            mapView.addOnDidFinishRenderingFrameListener(onFrame)
            cont.invokeOnCancellation {
                mapView.removeOnSourceChangedListener(onSource)
                mapView.removeOnDidFinishRenderingFrameListener(onFrame)
            }
        }
    }
}

private const val SITES_SOURCE = "sites"
private const val LAYER_CLUSTERS = "sites-clusters"
private const val LAYER_CLUSTER_COUNT = "sites-cluster-count"
private const val LAYER_POINTS = "sites-points"
private const val LAYER_SELECTED = "sites-selected"

/** Compares by reference: a new list from the repository is new data even if it is equal. */
private class Identity<T : Any>(val value: T) {
    override fun equals(other: Any?) = other is Identity<*> && other.value === value
    override fun hashCode() = System.identityHashCode(value)
}

@Composable
fun TowerMap(
    sites: List<SiteSummary>,
    selectedSiteId: Int?,
    dark: Boolean,
    basemapKey: String?,
    camera: MapCameraState,
    onSiteClick: (Int) -> Unit,
    onCameraIdle: (GeoBounds) -> Unit,
    selectedColor: Int,
    modifier: Modifier = Modifier,
    /** The user's position (lat, lon) for the location dot; pass null without location permission. */
    userLocation: Pair<Double, Double>? = null,
    userLocationColor: Int = selectedColor,
    /** Tile URL template of the coverage overlay (`CoverageTileService.tileUrlTemplate()`); null = no overlay. */
    coverageUrlTemplate: String? = null,
    coverageView: CoverageSelection.View = CoverageSelection.View.RSRP,
    coverageOpacity: Float = CoverageLayer.DEFAULT_OPACITY,
    /** On camera idle: the tiles the overlay needs (see [CoverageLayer.visibleTiles]); pass to `setVisible`. */
    onVisibleTiles: (Set<Triple<Int, Int, Int>>) -> Unit = {},
    /** The settled zoom, on every camera idle (including the initial one). */
    onZoom: (Double) -> Unit = {},
    /** A long press on the map at (lat, lon). */
    onLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    /** Any tap on the map (whether or not it hit a site or cluster). */
    onMapTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSiteClick by rememberUpdatedState(onSiteClick)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentOnVisibleTiles by rememberUpdatedState(onVisibleTiles)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMapTap by rememberUpdatedState(onMapTap)

    val mapView = remember { createMapView(context, camera) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // The latest sites GeoJSON, re-applied whenever the style (light/dark, basemap) is replaced.
    var sitesJson by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val tracker = MapViewLifecycle(mapView)
        val observer = LifecycleEventObserver { _, event -> tracker.moveTo(event.targetState) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.moveTo(Lifecycle.State.DESTROYED)
            camera.map = null
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnCameraIdleListener {
                camera.record(m.cameraPosition)
                val b = m.projection.visibleRegion.latLngBounds
                val bounds = GeoBounds(b.getLatSouth(), b.getLonWest(), b.getLatNorth(), b.getLonEast())
                currentOnCameraIdle(bounds)
                currentOnZoom(m.cameraPosition.zoom)
                currentOnVisibleTiles(CoverageLayer.visibleTiles(bounds, m.cameraPosition.zoom))
            }
            m.addOnMapClickListener { latLng ->
                currentOnMapTap()
                handleClick(m, latLng, mapView.context, currentOnSiteClick)
            }
            m.addOnMapLongClickListener { latLng ->
                currentOnLongPress(latLng.latitude, latLng.longitude)
                true
            }
            camera.attach(m)
            map = m
        }
        onDispose { }
    }

    LaunchedEffect(map, dark, basemapKey) {
        val m = map ?: return@LaunchedEffect
        val json = withContext(Dispatchers.IO) { StyleLoader.load(context, dark, basemapKey) }
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            addTowerLayers(loaded, dark)
            sitesJson?.let { loaded.getSourceAs<GeoJsonSource>(SITES_SOURCE)?.setGeoJson(it) }
            style = loaded
        }
    }

    // The overlay sits below the tower layers. Toggling coverage adds or removes it; any other template change (view,
    // data, terrain epoch) swaps the new tiles in without the overlay going blank (see CoverageLayer.CoverageOverlay).
    val overlay = remember { CoverageLayer.CoverageOverlay() }
    LaunchedEffect(style, coverageUrlTemplate) {
        val s = style ?: return@LaunchedEffect
        val pendingId = overlay.sync(s, coverageUrlTemplate, coverageView, coverageOpacity, LAYER_CLUSTERS)
        if (pendingId != null) {
            awaitSourceRendered(mapView, pendingId, OVERLAY_SWAP_TIMEOUT_MS)
            overlay.promote(s)
        }
    }
    LaunchedEffect(style, coverageView, coverageOpacity) {
        style?.let { overlay.restyle(it, coverageView, coverageOpacity) }
    }

    LaunchedEffect(Identity(sites)) {
        val json = withContext(Dispatchers.Default) { TowerLayer.featureCollection(sites) }
        sitesJson = json
        style?.getSourceAs<GeoJsonSource>(SITES_SOURCE)?.setGeoJson(json)
    }

    LaunchedEffect(style, selectedSiteId, selectedColor) {
        val layer = style?.getLayerAs<CircleLayer>(LAYER_SELECTED) ?: return@LaunchedEffect
        layer.setFilter(TowerLayer.selectedFilter(selectedSiteId))
        layer.setProperties(circleStrokeColor(selectedColor))
    }

    // The location dot: MapLibre's LocationComponent fed by us (no location engine of its own).
    // Activated once per map; it re-adds its layers itself when the style is replaced.
    val showDot = userLocation != null
    LaunchedEffect(style, showDot, userLocationColor) {
        val m = map ?: return@LaunchedEffect
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        setUserLocationVisible(m, s, context, showDot, userLocationColor)
    }
    LaunchedEffect(style, userLocation) {
        val m = map ?: return@LaunchedEffect
        val at = userLocation ?: return@LaunchedEffect
        if (style?.isFullyLoaded != true) return@LaunchedEffect
        val lc = m.locationComponent
        if (lc.isLocationComponentActivated && lc.isLocationComponentEnabled) {
            lc.forceLocationUpdate(Location("signal-atlas").apply { latitude = at.first; longitude = at.second })
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** No location engine runs, so no permission is used here; the caller only passes a position it may show. */
@SuppressLint("MissingPermission")
private fun setUserLocationVisible(map: MapLibreMap, style: Style, context: Context, visible: Boolean, color: Int) {
    val lc = map.locationComponent
    if (!visible) {
        if (lc.isLocationComponentActivated && lc.isLocationComponentEnabled) lc.isLocationComponentEnabled = false
        return
    }
    val options = LocationComponentOptions.builder(context)
        .foregroundTintColor(color)
        .backgroundTintColor(0xFFFFFFFF.toInt())
        .accuracyColor(color)
        .accuracyAlpha(0.12f)
        .pulseEnabled(false)
        .build()
    if (lc.isLocationComponentActivated) {
        lc.applyStyle(options)
    } else {
        lc.activateLocationComponent(
            LocationComponentActivationOptions.Builder(context, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(options)
                .build(),
        )
    }
    if (!lc.isLocationComponentEnabled) lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.NORMAL
}

private fun createMapView(context: Context, camera: MapCameraState): MapView {
    MapHttp.initMapLibre(context)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(false)
        .camera(CameraPosition.Builder().target(LatLng(camera.lat, camera.lon)).zoom(camera.zoom).build())
        .attributionEnabled(true)
        .logoEnabled(false)
    return MapView(context, options).apply { onCreate(null) }
}

/** Taps: a site opens its details; a cluster zooms in by 2 around the tap. */
private fun handleClick(map: MapLibreMap, latLng: LatLng, context: Context, onSiteClick: (Int) -> Unit): Boolean {
    val p = map.projection.toScreenLocation(latLng)
    val slop = 12f * context.resources.displayMetrics.density
    val box = RectF(p.x - slop, p.y - slop, p.x + slop, p.y + slop)
    val site = map.queryRenderedFeatures(box, LAYER_SELECTED, LAYER_POINTS).firstOrNull()
    site?.getNumberProperty("id")?.let {
        onSiteClick(it.toInt())
        return true
    }
    if (map.queryRenderedFeatures(box, LAYER_CLUSTERS).isNotEmpty()) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, map.cameraPosition.zoom + 2), 350)
        return true
    }
    return false
}

private fun addTowerLayers(style: Style, dark: Boolean) {
    style.addSource(
        GeoJsonSource(
            SITES_SOURCE,
            GeoJsonOptions().withCluster(true).withClusterRadius(45).withClusterMaxZoom(11),
        ),
    )
    val clusterFill = if (dark) 0xE6B5B8BD.toInt() else 0xE65F6368.toInt()
    val clusterText = if (dark) 0xFF1C1D1F.toInt() else 0xFFFFFFFF.toInt()
    val clusterStroke = if (dark) 0xFF1C1D1F.toInt() else 0xFFFFFFFF.toInt()
    style.addLayer(
        CircleLayer(LAYER_CLUSTERS, SITES_SOURCE).withFilter(has("point_count")).withProperties(
            circleColor(clusterFill),
            circleRadius(step(get("point_count"), literal(13), stop(50, 16), stop(500, 20), stop(5000, 25))),
            circleStrokeColor(clusterStroke),
            circleStrokeWidth(1.5f),
        ),
    )
    style.addLayer(
        SymbolLayer(LAYER_CLUSTER_COUNT, SITES_SOURCE).withFilter(has("point_count")).withProperties(
            textField(get("point_count_abbreviated")),
            textFont(arrayOf("Noto Sans Medium")),
            textSize(12f),
            textColor(clusterText),
            textAllowOverlap(true),
            textIgnorePlacement(true),
        ),
    )
    val netColor = match(
        get("net"), color(0xFF9AA0A6.toInt()),
        *Network.entries.map { stop(it.name, color(NetworkColors.argb(it))) }.toTypedArray(),
    )
    style.addLayer(
        CircleLayer(LAYER_POINTS, SITES_SOURCE).withFilter(not(has("point_count"))).withProperties(
            circleColor(netColor),
            circleRadius(interpolate(linear(), zoom(), stop(8, 5), stop(16, 9))),
            circleStrokeColor(0xFFFFFFFF.toInt()),
            circleStrokeWidth(1.5f),
        ),
    )
    // Drawn on top; its filter is the only thing that changes when the selection does.
    style.addLayer(
        CircleLayer(LAYER_SELECTED, SITES_SOURCE)
            .withFilter(TowerLayer.selectedFilter(null))
            .withProperties(
                circleColor(netColor),
                circleRadius(interpolate(linear(), zoom(), stop(8, 9), stop(16, 13))),
                circleStrokeWidth(3f),
            ),
    )
}

/** Drives a [MapView] through its lifecycle callbacks in order, from wherever it is now. */
private class MapViewLifecycle(private val view: MapView) {
    private var state = Lifecycle.State.CREATED // MapView.onCreate ran in createMapView.

    fun moveTo(target: Lifecycle.State) {
        while (state != target && state != Lifecycle.State.DESTROYED) {
            state = if (target > state) {
                when (state) {
                    Lifecycle.State.CREATED -> Lifecycle.State.STARTED.also { view.onStart() }
                    Lifecycle.State.STARTED -> Lifecycle.State.RESUMED.also { view.onResume() }
                    else -> return
                }
            } else {
                when (state) {
                    Lifecycle.State.RESUMED -> Lifecycle.State.STARTED.also { view.onPause() }
                    Lifecycle.State.STARTED -> Lifecycle.State.CREATED.also { view.onStop() }
                    Lifecycle.State.CREATED -> Lifecycle.State.DESTROYED.also { view.onDestroy() }
                    else -> return
                }
            }
        }
    }
}
