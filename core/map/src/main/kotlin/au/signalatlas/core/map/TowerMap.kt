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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.maplibre.android.tile.TileOperation
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
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
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textPadding
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

    /**
     * Centres ([lat], [lon]) at [zoom] in the part of the map not covered by [bottomInsetPx] (e.g. an open bottom sheet).
     * The inset stays the camera's padding until the next call.
     */
    fun animateTo(lat: Double, lon: Double, zoom: Double, bottomInsetPx: Int = 0) {
        val m = map
        if (m == null) {
            pending = Triple(lat, lon, zoom)
            this.lat = lat
            this.lon = lon
            this.zoom = zoom
        } else {
            val position = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(zoom)
                .padding(0.0, 0.0, 0.0, bottomInsetPx.toDouble())
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(position), ANIMATION_MS)
        }
    }

    /** The (lat, lon) under the screen point ([xPx], [yPx]) of the map view; null before the map is ready. */
    fun latLngAt(xPx: Float, yPx: Float): Pair<Double, Double>? =
        map?.projection?.fromScreenLocation(android.graphics.PointF(xPx, yPx))?.let { it.latitude to it.longitude }

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

/** A safety net only: a swap waits for the new tiles (see [CoverageLayer.SwapGate]), however slowly they render. */
private const val OVERLAY_SWAP_TIMEOUT_MS = 60_000L

/**
 * Suspends until the tiles of [sourceId] are in (see [CoverageLayer.SwapGate]) or [timeoutMs] passes (a swap must not
 * wait forever on a stuck tile: the old pair is replaced anyway).
 */
private suspend fun awaitSourceRendered(mapView: MapView, sourceId: String, timeoutMs: Long) {
    val gate = CoverageLayer.SwapGate(sourceId)
    var waiter: CancellableContinuation<Unit>? = null
    fun open() { waiter?.takeIf { it.isActive }?.resume(Unit) }
    val onTile = MapView.OnTileActionListener { op, x, y, z, _, _, source ->
        if (gate.onTile(op.toTileEvent(), z, x, y, source)) open()
    }
    val onIdle = MapView.OnDidBecomeIdleListener { if (gate.onIdle()) open() }
    mapView.addOnTileActionListener(onTile)
    mapView.addOnDidBecomeIdleListener(onIdle)
    try {
        withTimeoutOrNull(timeoutMs) { suspendCancellableCoroutine { waiter = it } }
    } finally {
        mapView.removeOnTileActionListener(onTile)
        mapView.removeOnDidBecomeIdleListener(onIdle)
    }
}

private fun TileOperation.toTileEvent(): CoverageLayer.TileEvent = when (this) {
    TileOperation.RequestedFromNetwork -> CoverageLayer.TileEvent.NETWORK_REQUESTED
    TileOperation.LoadFromNetwork -> CoverageLayer.TileEvent.NETWORK_LOADED
    TileOperation.EndParse -> CoverageLayer.TileEvent.PARSED
    TileOperation.Error, TileOperation.Cancelled -> CoverageLayer.TileEvent.FAILED
    else -> CoverageLayer.TileEvent.OTHER
}

private const val SITES_SOURCE = "sites"
private const val LAYER_CLUSTERS = "sites-clusters"
private const val LAYER_CLUSTER_COUNT = "sites-cluster-count"
private const val LAYER_POINTS = "sites-points"
private const val LAYER_DAS_RING = "sites-das-ring"
private const val LAYER_DAS_GLYPH = "sites-das-glyph"
private const val LAYER_SELECTED = "sites-selected"
private const val BEAMS_SOURCE = "site-beams"
private const val LAYER_BEAMS = "site-beams-fill"
private const val LAYER_BEAMS_LINE = "site-beams-line"
private const val LAYER_BEAMS_LABEL = "site-beams-label"
private const val CONNECTED_SOURCE = "connected"
private const val LAYER_CONNECTED_LINE = "connected-line"
private const val LAYER_CONNECTED_NEIGHBOURS = "connected-neighbours"
private const val LAYER_CONNECTED_RING = "connected-ring"
private const val LAYER_CONNECTED_DOT = "connected-dot"
private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
private const val ESTIMATED_ACCURACY_M = 70f
private const val PULSE_STEPS = 24
private const val PULSE_FRAME_MS = 70L

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
    /** The location is estimated (dead-reckoned in a tunnel): a fainter dot with a soft pulse and a wide accuracy halo. */
    userLocationEstimated: Boolean = false,
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
    /** The connected tower: pulse ring, line from [userLocation], faint neighbours. Null = none. */
    connected: ConnectedHighlight? = null,
    /** The selected site's sector lobes, drawn translucent at [beamLengthM]; null = none. */
    siteBeams: SiteBeams? = null,
    beamLengthM: Double = SectorBeams.lengthMetersForZoom(null),
    /** Show the lobes' labels at every zoom (else only from [SectorBeams.LABEL_MIN_ZOOM]). */
    beamLabelsAlways: Boolean = false,
    /** True while the coverage overlay still has tiles being calculated (see [CoverageLoadTracker]). */
    onCoverageLoading: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSiteClick by rememberUpdatedState(onSiteClick)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentOnVisibleTiles by rememberUpdatedState(onVisibleTiles)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnCoverageLoading by rememberUpdatedState(onCoverageLoading)

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

    // Coverage tiles are calculated on request; outstanding requests of the overlay's sources mean "calculating".
    DisposableEffect(mapView) {
        val tracker = CoverageLoadTracker()
        var reported = false
        fun report(now: Boolean) {
            if (now != reported) {
                reported = now
                currentOnCoverageLoading(now)
            }
        }
        val onTile = MapView.OnTileActionListener { op, x, y, z, _, _, source ->
            report(tracker.onTile(op.toTileEvent(), z, x, y, source))
        }
        val onIdle = MapView.OnDidBecomeIdleListener { report(tracker.onIdle()) }
        mapView.addOnTileActionListener(onTile)
        mapView.addOnDidBecomeIdleListener(onIdle)
        onDispose {
            mapView.removeOnTileActionListener(onTile)
            mapView.removeOnDidBecomeIdleListener(onIdle)
        }
    }

    LaunchedEffect(map, dark, basemapKey) {
        val m = map ?: return@LaunchedEffect
        val json = withContext(Dispatchers.IO) { StyleLoader.load(context, dark, basemapKey) }
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            addTowerLayers(loaded, dark)
            addConnectedLayers(loaded, selectedColor)
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

    // The connected tower: a GeoJSON source of its own, so site updates never touch it.
    LaunchedEffect(style, connected) {
        val src = style?.getSourceAs<GeoJsonSource>(CONNECTED_SOURCE) ?: return@LaunchedEffect
        src.setGeoJson(connected?.toGeoJson() ?: EMPTY_COLLECTION)
    }
    LaunchedEffect(style, connected != null) {
        val ring = style?.getLayerAs<CircleLayer>(LAYER_CONNECTED_RING)?.takeIf { connected != null } ?: return@LaunchedEffect
        var t = 0
        while (true) {
            val phase = (t % PULSE_STEPS) / PULSE_STEPS.toFloat()
            ring.setProperties(circleRadius(10f + 24f * phase), circleStrokeOpacity(0.8f * (1f - phase)))
            t++
            delay(PULSE_FRAME_MS)
        }
    }

    // The selected site's lobes: layers of their own, re-inserted under the tower dots (above the coverage overlay).
    LaunchedEffect(style, siteBeams, beamLengthM) {
        val st = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val json = siteBeams?.let { withContext(Dispatchers.Default) { SectorBeams.toGeoJson(it, beamLengthM) } }
        st.removeLayer(LAYER_BEAMS_LABEL)
        st.removeLayer(LAYER_BEAMS_LINE)
        st.removeLayer(LAYER_BEAMS)
        st.removeSource(BEAMS_SOURCE)
        if (json == null) return@LaunchedEffect
        st.addSource(GeoJsonSource(BEAMS_SOURCE, json))
        val netColor = match(
            get("net"), color(0xFF9AA0A6.toInt()),
            *Network.entries.map { stop(it.name, color(NetworkColors.argb(it))) }.toTypedArray(),
        )
        val lobes = eq(get("kind"), literal("lobe"))
        st.addLayerBelow(
            FillLayer(LAYER_BEAMS, BEAMS_SOURCE).withFilter(lobes)
                .withProperties(fillColor(netColor), fillOpacity(SectorBeams.FILL_OPACITY), fillAntialias(true)),
            LAYER_CLUSTERS,
        )
        st.addLayerBelow(
            LineLayer(LAYER_BEAMS_LINE, BEAMS_SOURCE).withFilter(lobes)
                .withProperties(lineColor(netColor), lineWidth(1.5f), lineOpacity(SectorBeams.OUTLINE_OPACITY)),
            LAYER_CLUSTERS,
        )
        st.addLayer(
            SymbolLayer(LAYER_BEAMS_LABEL, BEAMS_SOURCE).withFilter(eq(get("kind"), literal("label"))).withProperties(
                textField(get("text")),
                textFont(arrayOf("Noto Sans Medium")),
                textSize(12f),
                textColor(netColor),
                textHaloColor(if (dark) 0xE6000000.toInt() else 0xF2FFFFFF.toInt()),
                textHaloWidth(1.6f),
                textPadding(2f),
                textOptional(true),
            ).apply { if (!beamLabelsAlways) minZoom = SectorBeams.LABEL_MIN_ZOOM },
        )
    }
    LaunchedEffect(style, beamLabelsAlways) {
        style?.getLayer(LAYER_BEAMS_LABEL)?.let { it.minZoom = if (beamLabelsAlways) 0f else SectorBeams.LABEL_MIN_ZOOM }
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
    LaunchedEffect(style, showDot, userLocationColor, userLocationEstimated) {
        val m = map ?: return@LaunchedEffect
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        setUserLocationVisible(m, s, context, showDot, userLocationColor, userLocationEstimated)
    }
    LaunchedEffect(style, userLocation, userLocationEstimated) {
        val m = map ?: return@LaunchedEffect
        val at = userLocation ?: return@LaunchedEffect
        if (style?.isFullyLoaded != true) return@LaunchedEffect
        val lc = m.locationComponent
        if (lc.isLocationComponentActivated && lc.isLocationComponentEnabled) {
            val loc = Location("signal-atlas").apply {
                latitude = at.first
                longitude = at.second
                if (userLocationEstimated) accuracy = ESTIMATED_ACCURACY_M
            }
            lc.forceLocationUpdate(loc)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** No location engine runs, so no permission is used here; the caller only passes a position it may show. */
@SuppressLint("MissingPermission")
private fun setUserLocationVisible(map: MapLibreMap, style: Style, context: Context, visible: Boolean, color: Int, estimated: Boolean) {
    val lc = map.locationComponent
    if (!visible) {
        if (lc.isLocationComponentActivated && lc.isLocationComponentEnabled) lc.isLocationComponentEnabled = false
        return
    }
    val options = LocationComponentOptions.builder(context)
        .foregroundTintColor(if (estimated) (color and 0x00FFFFFF) or 0x99000000.toInt() else color)
        .backgroundTintColor(if (estimated) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt())
        .accuracyColor(color)
        .accuracyAlpha(if (estimated) 0.2f else 0.12f)
        .pulseEnabled(estimated)
        .pulseColor(color)
        .pulseSingleDuration(2500f)
        .pulseMaxRadius(36f)
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
    // DAS (in-building / tunnel) sites: a coloured ring around the dot plus a D / T glyph once zoomed in.
    val dasColor = match(
        get("das"), color(TowerLayer.DAS_BUILDING_ARGB),
        stop(2, color(TowerLayer.DAS_TUNNEL_ARGB)),
    )
    style.addLayer(
        CircleLayer(LAYER_DAS_RING, SITES_SOURCE)
            .withFilter(org.maplibre.android.style.expressions.Expression.all(not(has("point_count")), TowerLayer.dasFilter()))
            .withProperties(
                circleColor(0x00000000),
                circleRadius(interpolate(linear(), zoom(), stop(8, 8), stop(16, 14))),
                circleStrokeColor(dasColor),
                circleStrokeWidth(3f),
            ),
    )
    style.addLayer(
        SymbolLayer(LAYER_DAS_GLYPH, SITES_SOURCE)
            .withFilter(org.maplibre.android.style.expressions.Expression.all(not(has("point_count")), TowerLayer.dasFilter()))
            .withProperties(
                textField(match(get("das"), literal("D"), stop(2, literal("T")))),
                textFont(arrayOf("Noto Sans Medium")),
                textSize(11f),
                textColor(dasColor),
                textHaloColor(0xFFFFFFFF.toInt()),
                textHaloWidth(1.5f),
                textOffset(arrayOf(0f, -1.6f)),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
    )
    style.getLayerAs<SymbolLayer>(LAYER_DAS_GLYPH)?.minZoom = 12f
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

/** Above the tower layers: faint neighbours, the line to the serving site, its pulse ring and a dot. */
private fun addConnectedLayers(style: Style, color: Int) {
    style.addSource(GeoJsonSource(CONNECTED_SOURCE, EMPTY_COLLECTION))
    style.addLayer(
        LineLayer(LAYER_CONNECTED_LINE, CONNECTED_SOURCE).withFilter(eq(get("kind"), literal("line"))).withProperties(
            lineColor(color), lineWidth(2f), lineOpacity(0.8f), lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_CONNECTED_NEIGHBOURS, CONNECTED_SOURCE).withFilter(eq(get("kind"), literal("neighbour"))).withProperties(
            circleColor(color), circleOpacity(0.25f), circleRadius(7f), circleStrokeColor(color), circleStrokeOpacity(0.35f),
            circleStrokeWidth(1f),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_CONNECTED_RING, CONNECTED_SOURCE).withFilter(eq(get("kind"), literal("serving"))).withProperties(
            circleColor(color), circleOpacity(0f), circleRadius(14f), circleStrokeColor(color), circleStrokeWidth(3f),
            circleStrokeOpacity(0.6f),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_CONNECTED_DOT, CONNECTED_SOURCE).withFilter(eq(get("kind"), literal("serving"))).withProperties(
            circleColor(color), circleRadius(6f), circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(2f),
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
