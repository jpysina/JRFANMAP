package jp.jrfanmap.map

import android.graphics.RectF
import android.graphics.PointF
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.Fix
import jp.jrfanmap.rail.*
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.*

class MapHandle {
    internal var map: MapLibreMap? = null
    fun area(): DownloadArea? = map?.let {
        val b = it.projection.visibleRegion.latLngBounds
        val min = (it.cameraPosition.zoom.toInt() - 1).coerceIn(4, 14)
        val max = (min + 3).coerceAtMost(16)
        DownloadArea(b, min, max, TileBudget.count(b.latitudeNorth, b.latitudeSouth, b.longitudeEast, b.longitudeWest, min, max))
    }
}

enum class MapBase { RAIL, PALE, STANDARD, VECTOR }

private const val SCHEMATIC_STYLE = "asset://rail-schematic.json"
private const val VECTOR_STYLE = "asset://gsi-style.json"
private const val HIGHLIGHT = "selected-line"
private const val HIGHLIGHT_CASING = "selected-line-casing"
private const val TRACK_CASING = "rail-casing"
private const val SELECTED_STOPS = "selected-stations"
private const val SELECTED_LABELS = "selected-station-labels"
private const val HIGHLIGHT_COLOR = "#F5A623"
private const val GSI_PALE = "https://cyberjapandata.gsi.go.jp/xyz/pale/"
private const val GSI_RAIL_DETAIL = "gsi-rail-details"
private const val GSI_RAIL_TILE = "https://cyberjapandata.gsi.go.jp/xyz/experimental_bvmap/{z}/{x}/{y}.pbf"
private const val GSI_LAYER_PREFIX = "gsi-raster-"

private data class Viewport(val bounds: RailBounds, val zoom: Double)
private const val TRACKS = "local-railway-lines"
private const val STATIONS = "local-railway-stations"

@Composable
fun RailMap(
    fix: Fix?, path: List<Fix>, follow: Boolean, handle: MapHandle, base: MapBase, online: Boolean,
    regions: List<GsiRegion>, catalogYear: Int,
    loadRail: suspend (RailBounds, Boolean) -> Pair<List<RailSection>, List<RailStation>>,
    onLines: (List<Long>) -> Unit, onStation: (String) -> Unit,
    modifier: Modifier = Modifier, highlightedLine: Long? = null, ahead: List<AheadStation> = emptyList(),
    onGesture: () -> Unit = {}, onEmptyTap: () -> Unit = {},
    onShinjukuPreview: (Boolean) -> Unit = {}, onError: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val boundary = remember(context) { (context.applicationContext as RailApplication).store.japanBoundary }
    val location = fix?.let { GeoPoint(it.longitude, it.latitude) }
    val anchor = remember(location, boundary) { boundary.anchor(location) }
    val latestAnchor by rememberUpdatedState(anchor)
    val previewCallback by rememberUpdatedState(onShinjukuPreview)
    val gestureCallback by rememberUpdatedState(onGesture)
    val emptyTapCallback by rememberUpdatedState(onEmptyTap)
    var tapJob by remember { mutableStateOf<Job?>(null) }
    val view = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var showingShinjuku by remember { mutableStateOf(false) }
    var lastJapanCenter by remember { mutableStateOf<GeoPoint?>(null) }
    val errorCallback by rememberUpdatedState(onError)
    val lineCallback by rememberUpdatedState(onLines)
    val stationCallback by rememberUpdatedState(onStation)
    val loadCallback by rememberUpdatedState(loadRail)
    val detailStyle = base == MapBase.VECTOR && online
    LaunchedEffect(showingShinjuku) { previewCallback(showingShinjuku) }
    DisposableEffect(view, owner) {
        var disposed = false
        var started = false
        var resumed = false
        val listener = MapView.OnDidFailLoadingMapListener { errorCallback("地图加载失败，可切回铁路底图") }
        view.addOnDidFailLoadingMapListener(listener)
        view.getMapAsync { instance ->
            if (disposed) return@getMapAsync
            map = instance
            handle.map = instance
            instance.uiSettings.isCompassEnabled = true
            instance.uiSettings.isAttributionEnabled = true
            instance.uiSettings.isRotateGesturesEnabled = false
            instance.uiSettings.isTiltGesturesEnabled = false
            val start = latestAnchor
            showingShinjuku = start.shinjukuPreview
            lastJapanCenter = start.center.takeUnless { start.shinjukuPreview }
            instance.cameraPosition = CameraPosition.Builder()
                .target(LatLng(start.center.lat, start.center.lon))
                .zoom(if (start.shinjukuPreview) SHINJUKU_ZOOM else JAPAN_FIX_ZOOM).build()
            instance.addOnCameraIdleListener {
                val b = instance.projection.visibleRegion.latLngBounds
                viewport = Viewport(RailBounds(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth),
                    instance.cameraPosition.zoom)
            }
            fun enterFreeBrowse() {
                showingShinjuku = false
                gestureCallback()
            }
            instance.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) = enterFreeBrowse()
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            instance.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) enterFreeBrowse()
            }
            instance.addOnMapClickListener { location ->
                val pixel = instance.projection.toScreenLocation(location)
                val radius = 22 * context.resources.displayMetrics.density
                val rect = RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius)
                val hits = instance.queryRenderedFeatures(rect, TRACKS)
                    .mapNotNull { it.getStringProperty("railId")?.toLongOrNull() }.distinct().take(12)
                val station = instance.queryRenderedFeatures(pixel, STATIONS).firstOrNull()?.getStringProperty("station")
                val nw = instance.projection.fromScreenLocation(PointF(rect.left, rect.top))
                val se = instance.projection.fromScreenLocation(PointF(rect.right, rect.bottom))
                val lng = kotlin.math.abs(se.longitude - nw.longitude) / 2
                val lat = kotlin.math.abs(nw.latitude - se.latitude) / 2
                val geotap = GeoPoint(location.longitude, location.latitude)
                tapJob?.cancel()
                tapJob = scope.launch {
                    val geometry = if (instance.cameraPosition.zoom >= 5 && lng < 5 && lat < 5) {
                        val nearby = RailBounds(geotap.lon - lng, geotap.lat - lat, geotap.lon + lng, geotap.lat + lat)
                        try {
                            val (tracks, _) = loadCallback(nearby, false)
                            withContext(Dispatchers.Default) { nearbyRailIds(geotap, lng, lat, tracks) }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            emptyList()
                        }
                    } else emptyList()
                    when {
                        geometry.isNotEmpty() -> lineCallback(geometry)
                        hits.isNotEmpty() -> lineCallback(hits)
                        station != null -> stationCallback(station)
                        else -> emptyTapCallback()
                    }
                }
                true
            }
        }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> { view.onStart(); started = true }
            Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
            Lifecycle.Event.ON_PAUSE -> { view.onPause(); resumed = false }
            Lifecycle.Event.ON_STOP -> { view.onStop(); started = false }
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            owner.lifecycle.removeObserver(observer)
            handle.map = null
            tapJob?.cancel()
            view.removeOnDidFailLoadingMapListener(listener)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(map, detailStyle) {
        val instance = map ?: return@LaunchedEffect
        loadedStyle = null
        instance.setStyle(Style.Builder().fromUri(if (detailStyle) VECTOR_STYLE else SCHEMATIC_STYLE)) { style ->
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            style.addSource(GeoJsonSource("rail-local", empty))
            val selectedFilter = eq(get("railId"), "")
            val trackColor = match(get("category"), literal("#4F6572"),
                stop("1", "#9B4960"), stop("2", "#25677A"), stop("3", "#4E679A"),
                stop("4", "#79668B"), stop("5", "#895B45"))
            style.addLayer(LineLayer(TRACK_CASING, "rail-local").withProperties(lineColor("#F7FAF8"),
                lineWidth(interpolate(linear(), zoom(), stop(5, 1.4), stop(12, 3.4), stop(17, 6.0))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineOpacity(.92f)))
            style.addLayer(LineLayer(TRACKS, "rail-local").withProperties(lineColor(trackColor),
                lineWidth(interpolate(linear(), zoom(), stop(5, .65), stop(12, 2.0), stop(17, 4.0))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(LineLayer(HIGHLIGHT_CASING, "rail-local").withFilter(selectedFilter)
                .withProperties(lineColor("#254049"),
                    lineWidth(interpolate(linear(), zoom(), stop(5, 3.8), stop(12, 7.0), stop(17, 11.0))),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(LineLayer(HIGHLIGHT, "rail-local").withFilter(selectedFilter)
                .withProperties(lineColor(HIGHLIGHT_COLOR),
                    lineWidth(interpolate(linear(), zoom(), stop(5, 2.0), stop(12, 4.4), stop(17, 7.5))),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addSource(GeoJsonSource("ahead-local", empty))
            style.addLayer(CircleLayer("ahead-dots", "ahead-local").withProperties(circleColor("#F4B039"),
                circleRadius(5f), circleStrokeColor("#15252A"), circleStrokeWidth(2f)))
            style.addSource(GeoJsonSource("stations-local", empty))
            style.addLayer(CircleLayer(STATIONS, "stations-local").withProperties(circleColor("#F7FAF8"),
                circleRadius(interpolate(linear(), zoom(), stop(10, 1.0), stop(14, 2.5), stop(17, 4.0))),
                circleStrokeColor("#587580"), circleStrokeWidth(1f)))
            style.addLayer(CircleLayer(SELECTED_STOPS, "stations-local").withFilter(selectedFilter)
                .withProperties(circleColor("#FFFBEE"), circleRadius(4f), circleStrokeColor("#81510A"), circleStrokeWidth(2f)))
            style.addLayer(SymbolLayer(SELECTED_LABELS, "stations-local").withFilter(selectedFilter).withProperties(
                textField(get("station")), textFont(arrayOf("NotoSansCJKjp-Regular")), textSize(11f),
                textColor("#253D45"), textHaloColor("#FFFBEE"), textHaloWidth(1.3f),
                textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, .7f))).apply { minZoom = 12.5f })
            style.addSource(GeoJsonSource("trip", empty))
            style.addLayer(LineLayer("trip-line", "trip").withProperties(lineColor("#EF8C78"), lineWidth(4f)))
            style.addSource(GeoJsonSource("device", empty))
            style.addLayer(CircleLayer("device-dot", "device").withProperties(circleColor("#017D72"), circleRadius(7f),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
            loadedStyle = style
            val b = instance.projection.visibleRegion.latLngBounds
            viewport = Viewport(RailBounds(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth), instance.cameraPosition.zoom)
        }
    }

    LaunchedEffect(loadedStyle, base, online, regions) {
        val style = loadedStyle ?: return@LaunchedEffect
        style.layers.filter { it.id.startsWith(GSI_LAYER_PREFIX) }.forEach {
            style.removeLayer(it.id)
            style.removeSource(it.id)
        }
        style.layers.filter { it.id.startsWith("$GSI_RAIL_DETAIL-") }.forEach { style.removeLayer(it.id) }
        if (style.getSource(GSI_RAIL_DETAIL) != null) style.removeSource(GSI_RAIL_DETAIL)
        if (base == MapBase.RAIL || detailStyle) return@LaunchedEffect
        fun add(id: String, url: String, region: GsiRegion? = null, pale: Boolean = false) {
            val tiles = TileSet("2.2.0", url).apply {
                attribution = GSI_ATTRIBUTION
                setMinZoom(region?.minZoom?.toFloat() ?: if (pale) 5f else 4f)
                setMaxZoom(region?.maxZoom?.toFloat() ?: 18f)
                if (region != null) setBounds(region.west.toFloat(), region.south.toFloat(), region.east.toFloat(), region.north.toFloat())
            }
            style.addSource(RasterSource(id, tiles, 256))
            style.addLayerBelow(RasterLayer(id, id).withProperties(rasterFadeDuration(0f)), TRACK_CASING)
        }
        fun addOnline() {
            val pale = base == MapBase.PALE
            add(GSI_LAYER_PREFIX + "online", (if (pale) GSI_PALE else GSI_STANDARD) + "{z}/{x}/{y}.png", pale = pale)
        }
        if (online && base != MapBase.PALE) addOnline()
        regions.forEach { add(GSI_LAYER_PREFIX + it.id, it.tileUrl, it) }
        if (online && base == MapBase.PALE) {
            addOnline()
            addGsiRailDetails(style)
        }
    }

    LaunchedEffect(loadedStyle, highlightedLine, ahead, online) {
        val style = loadedStyle ?: return@LaunchedEffect
        val filter = eq(get("railId"), highlightedLine?.toString().orEmpty())
        style.getLayerAs<LineLayer>(HIGHLIGHT_CASING)?.setFilter(filter)
        style.getLayerAs<LineLayer>(HIGHLIGHT)?.setFilter(filter)
        style.getLayerAs<LineLayer>(TRACKS)?.setProperties(lineOpacity(if (highlightedLine == null) .93f else .60f))
        style.getLayerAs<CircleLayer>(SELECTED_STOPS)?.setFilter(filter)
        style.getLayerAs<SymbolLayer>(SELECTED_LABELS)?.setFilter(if (online) filter else eq(get("railId"), ""))
        style.getSourceAs<GeoJsonSource>("ahead-local")?.setGeoJson(FeatureCollection.fromFeatures(ahead.map {
            Feature.fromGeometry(Point.fromLngLat(it.station.point.lon, it.station.point.lat))
        }))
    }

    LaunchedEffect(loadedStyle, viewport, catalogYear) {
        val style = loadedStyle ?: return@LaunchedEffect
        val area = viewport ?: return@LaunchedEffect
        val datasets = if (area.zoom >= 5 && area.bounds.east >= area.bounds.west) {
            runCatching { loadCallback(area.bounds, area.zoom >= 10) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                errorCallback("铁路图层无法读取")
                emptyList<RailSection>() to emptyList()
            }
        } else emptyList<RailSection>() to emptyList()
        val (lines, stations) = withContext(Dispatchers.Default) {
            val step = if (area.zoom < 8) 4 else 1
            val lines = datasets.first.map { section ->
                val points = section.shape.filterIndexed { index, _ -> index % step == 0 || index == section.shape.lastIndex }
                Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addStringProperty("railId", section.lineId.toString())
                    addStringProperty("category", section.category)
                }
            }
            val stations = datasets.second.map { station ->
                Feature.fromGeometry(Point.fromLngLat(station.point.lon, station.point.lat)).apply {
                    addStringProperty("station", station.name)
                    station.lineId?.let { addStringProperty("railId", it.toString()) }
                }
            }
            FeatureCollection.fromFeatures(lines) to FeatureCollection.fromFeatures(stations)
        }
        withContext(Dispatchers.Main.immediate) {
            if (loadedStyle === style) {
                style.getSourceAs<GeoJsonSource>("rail-local")?.setGeoJson(lines)
                style.getSourceAs<GeoJsonSource>("stations-local")?.setGeoJson(stations)
            }
        }
    }
    LaunchedEffect(loadedStyle, fix) {
        val style = loadedStyle ?: return@LaunchedEffect
        val feature = fix?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        style.getSourceAs<GeoJsonSource>("device")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(feature)))
    }
    LaunchedEffect(map, location, anchor, follow) {
        val instance = map ?: return@LaunchedEffect
        if (location != null) lastJapanCenter = anchor.center.takeUnless { anchor.shinjukuPreview }
        // Wait at the last Japanese map position through a tunnel or a stale fix.
        val target = if (location == null) lastJapanCenter?.let { MapAnchor(it, false) } ?: anchor else anchor
        if (!follow && !(showingShinjuku && !target.shinjukuPreview)) return@LaunchedEffect
        val point = LatLng(target.center.lat, target.center.lon)
        val enteringPreview = target.shinjukuPreview && !showingShinjuku
        if (enteringPreview || instance.cameraPosition.target?.distanceTo(point)?.let { it > 15 } != false) {
            val camera = CameraPosition.Builder(instance.cameraPosition).target(point)
            if (enteringPreview) camera.zoom(SHINJUKU_ZOOM)
            instance.cameraPosition = camera.build()
        }
        showingShinjuku = target.shinjukuPreview
    }
    LaunchedEffect(loadedStyle, path) {
        val style = loadedStyle ?: return@LaunchedEffect
        val segments = mutableListOf<MutableList<Point>>()
        var prev: Fix? = null
        for (fix in path) {
            if (prev == null || fix.elapsedNanos - prev.elapsedNanos > 15_000_000_000 || kotlin.math.abs(fix.longitude - prev.longitude) > 180) {
                segments.add(mutableListOf())
            }
            segments.last().add(Point.fromLngLat(fix.longitude, fix.latitude))
            prev = fix
        }
        style.getSourceAs<GeoJsonSource>("trip")?.setGeoJson(FeatureCollection.fromFeatures(
            segments.filter { it.size >= 2 }.map { Feature.fromGeometry(LineString.fromLngLats(it)) }))
    }
    AndroidView(factory = { view }, modifier = modifier.testTag(if (loadedStyle != null) "rail-map-ready" else "rail-map-loading"))
}

private fun addGsiRailDetails(style: Style) {
    val tiles = TileSet("2.2.0", GSI_RAIL_TILE).apply {
        setMinZoom(4f)
        setMaxZoom(16f)
        attribution = "<a href=\"https://maps.gsi.go.jp/vector/\">地理院地図Vector</a>"
    }
    style.addSource(VectorSource(GSI_RAIL_DETAIL, tiles))
    val railway = eq(get("ftCode"), 8201)
    val underground = any(*listOf(2, 3, 100, 300, 500).map { eq(get("railState"), it) }.toTypedArray())
    val siding = eq(get("snglDbl"), 3)
    fun layer(name: String, filter: Expression, min: Float, vararg properties: org.maplibre.android.style.layers.PropertyValue<*>) {
        val layer = LineLayer("$GSI_RAIL_DETAIL-$name", GSI_RAIL_DETAIL).withSourceLayer("railway")
            .withFilter(all(railway, filter)).withProperties(*properties).apply { minZoom = min }
        style.addLayerBelow(layer, HIGHLIGHT_CASING)
    }
    layer("surface", all(not(underground), not(siding)), 13f, lineColor("#71858B"),
        lineWidth(interpolate(linear(), zoom(), stop(13, .65), stop(16, 1.5))), lineOpacity(.82f))
    layer("tunnel", underground, 13f, lineColor("#52677C"), lineWidth(1.25f),
        lineDasharray(arrayOf(2.5f, 1.8f)), lineOpacity(.86f))
    layer("siding", siding, 15f, lineColor("#77888C"), lineWidth(.85f), lineOpacity(.70f))
}
