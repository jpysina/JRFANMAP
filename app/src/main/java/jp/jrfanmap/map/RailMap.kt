package jp.jrfanmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eightbitlab.com.blurview.BlurTarget
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
import kotlin.math.roundToInt

class MapHandle {
    internal var map: MapLibreMap? = null
    internal var blurTarget by mutableStateOf<BlurTarget?>(null)
    fun area(): DownloadArea? = map?.let {
        val b = it.projection.visibleRegion.latLngBounds
        val min = (it.cameraPosition.zoom.toInt() - 1).coerceIn(4, 14)
        val max = (min + 3).coerceAtMost(16)
        DownloadArea(b, min, max, TileBudget.count(b.latitudeNorth, b.latitudeSouth, b.longitudeEast, b.longitudeWest, min, max))
    }
}

enum class MapBase { RAIL, PALE, STANDARD, VECTOR, NAVIGATION }

private const val SCHEMATIC_STYLE = "asset://rail-schematic.json"
private const val VECTOR_STYLE = "asset://gsi-style.json"
private const val NAVIGATION_STYLE = "asset://gsi-navigation.json"
private const val NAVIGATION_OFFLINE_STYLE = "asset://rail-navigation-offline.json"
private const val NAVIGATION_VECTOR_SOURCE = "gsi"
private const val HIGHLIGHT = "selected-line"
private const val HIGHLIGHT_CASING = "selected-line-casing"
private const val TRACK_CASING = "rail-casing"
private const val SELECTED_STOPS = "selected-stations"
private const val SELECTED_LABELS = "selected-station-labels"
private const val AHEAD_SOURCE = "ahead-local"
internal const val MAX_AHEAD_STOPS = 4
internal const val NEXT_STATION_COLOR = 0xFFD4F5B4
internal const val LATER_STATION_COLOR = 0xFFA6B9E1
internal const val NAVIGATION_INK = 0xFF15182B
private const val HIGHLIGHT_COLOR = "#F5A623"
private const val NAVIGATION_HIGHLIGHT_COLOR = "#F6B772"
private const val HIGHLIGHT_GLOW = "selected-rail-glow"
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
    onGesture: () -> Unit = {}, onEmptyTap: () -> Unit = {}, frostedHud: Boolean = false,
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
    var loadedNavigationMode by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var showingShinjuku by remember { mutableStateOf(false) }
    var lastJapanCenter by remember { mutableStateOf<GeoPoint?>(null) }
    val errorCallback by rememberUpdatedState(onError)
    val lineCallback by rememberUpdatedState(onLines)
    val stationCallback by rememberUpdatedState(onStation)
    val loadCallback by rememberUpdatedState(loadRail)
    val navigation = base == MapBase.NAVIGATION
    val styleUri = when {
        navigation && online -> NAVIGATION_STYLE
        navigation -> NAVIGATION_OFFLINE_STYLE
        base == MapBase.VECTOR && online -> VECTOR_STYLE
        else -> SCHEMATIC_STYLE
    }
    LaunchedEffect(showingShinjuku) { previewCallback(showingShinjuku) }
    DisposableEffect(view, owner) {
        var disposed = false
        var started = false
        var resumed = false
        val listener = MapView.OnDidFailLoadingMapListener { errorCallback("地图加载失败，可切回铁路底图") }
        val layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                view.post { if (!disposed) map?.let { viewport = it.viewport() } }
            }
        }
        view.addOnDidFailLoadingMapListener(listener)
        view.addOnLayoutChangeListener(layout)
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
            instance.addOnCameraIdleListener { viewport = instance.viewport() }
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
            handle.blurTarget = null
            tapJob?.cancel()
            view.removeOnDidFailLoadingMapListener(listener)
            view.removeOnLayoutChangeListener(layout)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(map, styleUri) {
        val instance = map ?: return@LaunchedEffect
        loadedStyle = null
        loadedNavigationMode = null
        instance.setStyle(Style.Builder().fromUri(styleUri)) { style ->
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            style.addSource(GeoJsonSource("rail-local", empty))
            val selectedFilter = eq(get("railId"), "")
            val trackColor = if (navigation) match(get("category"), literal("#8C9CB5"),
                stop("1", "#BE96AB"), stop("2", "#83A7B3"), stop("3", "#93A2CE"),
                stop("4", "#A69BC8"), stop("5", "#B59A90"))
            else match(get("category"), literal("#4F6572"), stop("1", "#9B4960"),
                stop("2", "#25677A"), stop("3", "#4E679A"), stop("4", "#79668B"), stop("5", "#895B45"))
            style.addLayer(LineLayer(TRACK_CASING, "rail-local").withProperties(lineColor(if (navigation) "#111626" else "#F7FAF8"),
                lineWidth(interpolate(linear(), zoom(), stop(5, 1.4), stop(12, 3.4), stop(17, 6.0))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineOpacity(if (navigation) .55f else .92f)))
            style.addLayer(LineLayer(TRACKS, "rail-local").withProperties(lineColor(trackColor),
                lineWidth(interpolate(linear(), zoom(), stop(5, .65), stop(12, 2.0), stop(17, 4.0))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            if (navigation) style.addLayer(LineLayer(HIGHLIGHT_GLOW, "rail-local").withFilter(selectedFilter)
                .withProperties(lineColor(NAVIGATION_HIGHLIGHT_COLOR), lineOpacity(.26f), lineBlur(4f),
                    lineWidth(interpolate(linear(), zoom(), stop(5, 5), stop(12, 10), stop(17, 16))),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(LineLayer(HIGHLIGHT_CASING, "rail-local").withFilter(selectedFilter)
                .withProperties(lineColor(if (navigation) "#503725" else "#254049"),
                    lineWidth(interpolate(linear(), zoom(), stop(5, 3.8), stop(12, 7.0), stop(17, 11.0))),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(LineLayer(HIGHLIGHT, "rail-local").withFilter(selectedFilter)
                .withProperties(lineColor(if (navigation) NAVIGATION_HIGHLIGHT_COLOR else HIGHLIGHT_COLOR),
                    lineWidth(interpolate(linear(), zoom(), stop(5, 2.0), stop(12, 4.4), stop(17, 7.5))),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            style.addSource(GeoJsonSource(AHEAD_SOURCE, empty))
            if (!navigation) style.addLayer(CircleLayer("ahead-dots", AHEAD_SOURCE).withProperties(circleColor("#F4B039"),
                circleRadius(5f), circleStrokeColor("#15252A"), circleStrokeWidth(2f)))
            style.addSource(GeoJsonSource("stations-local", empty))
            style.addLayer(CircleLayer(STATIONS, "stations-local").withProperties(circleColor(if (navigation) "#A0B5BE" else "#F7FAF8"),
                circleRadius(interpolate(linear(), zoom(), stop(10, 1.0), stop(14, 2.5), stop(17, 4.0))),
                circleStrokeColor(if (navigation) "#1A303E" else "#587580"), circleStrokeWidth(1f)))
            style.addLayer(CircleLayer(SELECTED_STOPS, "stations-local").withFilter(selectedFilter)
                .withProperties(circleColor("#FFFBEE"), circleRadius(4f), circleStrokeColor("#81510A"), circleStrokeWidth(2f)))
            style.addLayer(SymbolLayer(SELECTED_LABELS, "stations-local").withFilter(selectedFilter).withProperties(
                textField(get("station")), textFont(arrayOf("NotoSansCJKjp-Regular")), textSize(11f),
                textColor(if (navigation) "#F6E8DA" else "#253D45"),
                textHaloColor(if (navigation) "#1A2032" else "#FFFBEE"), textHaloWidth(1.3f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, if (navigation) 1f else .7f))).apply { minZoom = 12.5f })
            if (navigation) addAheadStationMarkers(style, context)
            style.addSource(GeoJsonSource("trip", empty))
            style.addLayer(LineLayer("trip-line", "trip").withProperties(lineColor("#EF8C78"), lineWidth(4f)))
            style.addSource(GeoJsonSource("device", empty))
            style.addLayer(CircleLayer("device-dot", "device").withProperties(circleColor(if (navigation) "#67BCCD" else "#017D72"), circleRadius(7f),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
            loadedStyle = style
            loadedNavigationMode = when (styleUri) {
                NAVIGATION_STYLE -> "在线导航底图"
                NAVIGATION_OFFLINE_STYLE -> "离线导航底图"
                else -> null
            }
            viewport = instance.viewport()
        }
    }

    LaunchedEffect(loadedStyle, base, styleUri, regions) {
        val style = loadedStyle ?: return@LaunchedEffect
        style.layers.filter { it.id.startsWith(GSI_LAYER_PREFIX) }.forEach {
            style.removeLayer(it.id)
            style.removeSource(it.id)
        }
        style.layers.filter { it.id.startsWith("$GSI_RAIL_DETAIL-") }.forEach { style.removeLayer(it.id) }
        if (style.getSource(GSI_RAIL_DETAIL) != null) style.removeSource(GSI_RAIL_DETAIL)
        if (base == MapBase.RAIL || styleUri == VECTOR_STYLE) return@LaunchedEffect
        if (styleUri == NAVIGATION_STYLE) {
            addGsiRailDetails(style, NAVIGATION_VECTOR_SOURCE)
            return@LaunchedEffect
        }
        fun add(id: String, url: String, region: GsiRegion? = null, pale: Boolean = false) {
            val tiles = TileSet("2.2.0", url).apply {
                attribution = GSI_ATTRIBUTION
                setMinZoom(region?.minZoom?.toFloat() ?: if (pale) 5f else 4f)
                setMaxZoom(region?.maxZoom?.toFloat() ?: 18f)
                if (region != null) setBounds(region.west.toFloat(), region.south.toFloat(), region.east.toFloat(), region.north.toFloat())
            }
            style.addSource(RasterSource(id, tiles, 256))
            val layer = RasterLayer(id, id).withProperties(rasterFadeDuration(0f))
            if (pale) layer.setProperties(rasterBrightnessMin(.28f), rasterSaturation(-.45f))
            if (navigation) layer.setProperties(rasterBrightnessMax(.49f), rasterSaturation(-.78f),
                rasterOpacity(.70f), rasterHueRotate(13f))
            style.addLayerBelow(layer, TRACK_CASING)
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

    LaunchedEffect(loadedStyle, highlightedLine, ahead, online, navigation) {
        val style = loadedStyle ?: return@LaunchedEffect
        val filter = eq(get("railId"), highlightedLine?.toString().orEmpty())
        style.getLayerAs<LineLayer>(HIGHLIGHT_CASING)?.setFilter(filter)
        style.getLayerAs<LineLayer>(HIGHLIGHT)?.setFilter(filter)
        style.getLayerAs<LineLayer>(HIGHLIGHT_GLOW)?.setFilter(filter)
        val opacity = when {
            highlightedLine == null -> .93f
            navigation -> .47f
            else -> .60f
        }
        style.getLayerAs<LineLayer>(TRACKS)?.setProperties(lineOpacity(opacity))
        style.getLayerAs<CircleLayer>(SELECTED_STOPS)?.setFilter(filter)
        style.getLayerAs<SymbolLayer>(SELECTED_LABELS)?.setFilter(if (online) filter else eq(get("railId"), ""))
        style.getSourceAs<GeoJsonSource>(AHEAD_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(ahead.mapIndexed { index, stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.station.point.lon, stop.station.point.lat)).apply {
                addStringProperty("marker", aheadMarkerId(index + 1))
            }
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
            val stations = stationMarkers(datasets.second).map { station ->
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
    AndroidView(factory = {
        if (frostedHud && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurTarget(it).apply {
                addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                handle.blurTarget = this
            }
        } else view
    }, modifier = modifier.testTag(if (loadedStyle != null) "rail-map-ready" else "rail-map-loading").semantics {
        loadedNavigationMode?.let { stateDescription = it }
    })
}

private fun aheadMarkerId(order: Int) = "ahead-station-$order"

private fun addAheadStationMarkers(style: Style, context: Context) {
    val metrics = context.resources.displayMetrics
    val size = (22 * metrics.density).roundToInt()
    val center = size / 2f
    val font = context.resources.getFont(jp.jrfanmap.R.font.chakra_petch_semibold)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
        textSize = 12 * metrics.density
    }
    val background = NAVIGATION_INK.toInt()
    for (order in 1..MAX_AHEAD_STOPS) {
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { density = metrics.densityDpi }
        val canvas = Canvas(image)
        val tint = (if (order == 1) NEXT_STATION_COLOR else LATER_STATION_COLOR).toInt()
        paint.color = background
        canvas.drawCircle(center, center, 10.5f * metrics.density, paint)
        paint.color = tint
        canvas.drawCircle(center, center, 8.8f * metrics.density, paint)
        if (order > 1) {
            paint.color = background
            canvas.drawCircle(center, center, 7.4f * metrics.density, paint)
        }
        paint.color = if (order == 1) background else tint
        canvas.drawText(order.toString(), center, center - (paint.ascent() + paint.descent()) / 2, paint)
        style.addImage(aheadMarkerId(order), image)
    }
    style.addLayer(SymbolLayer("ahead-markers", AHEAD_SOURCE).withProperties(
        iconImage(get("marker")), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconSize(interpolate(linear(), zoom(), stop(11.5, .75), stop(13, 1.0)))
    ).apply { minZoom = 11.5f })
}

private fun MapLibreMap.viewport(): Viewport {
    val bounds = projection.visibleRegion.latLngBounds
    return Viewport(RailBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth), cameraPosition.zoom)
}

private fun stationMarkers(stations: List<RailStation>): List<RailStation> =
    stations.groupBy { it.lineId to it.name }.values.flatMap { duplicates ->
        val clusters = mutableListOf<MutableList<RailStation>>()
        for (station in duplicates) {
            val cluster = clusters.firstOrNull { group ->
                val anchor = group.first().point
                kotlin.math.hypot((station.point.lon - anchor.lon) * kotlin.math.cos(Math.toRadians(anchor.lat)),
                    station.point.lat - anchor.lat) < .005
            }
            if (cluster == null) clusters.add(mutableListOf(station)) else cluster.add(station)
        }
        clusters.map { group ->
            group.first().copy(point = GeoPoint(group.map { it.point.lon }.average(), group.map { it.point.lat }.average()))
        }
    }

private fun addGsiRailDetails(style: Style, source: String = GSI_RAIL_DETAIL) {
    val navigation = source == NAVIGATION_VECTOR_SOURCE
    if (!navigation) {
        val tiles = TileSet("2.2.0", GSI_RAIL_TILE).apply {
            setMinZoom(4f)
            setMaxZoom(16f)
            attribution = "<a href=\"https://maps.gsi.go.jp/vector/\">地理院地図Vector</a>"
        }
        style.addSource(VectorSource(source, tiles))
    }
    val railway = eq(get("ftCode"), 8201)
    val underground = any(*listOf(2, 3, 100, 300, 500).map { eq(get("railState"), it) }.toTypedArray())
    val siding = eq(get("snglDbl"), 3)
    fun layer(name: String, filter: Expression, min: Float, vararg properties: org.maplibre.android.style.layers.PropertyValue<*>) {
        val layer = LineLayer("$GSI_RAIL_DETAIL-$name", source).withSourceLayer("railway")
            .withFilter(all(railway, filter)).withProperties(*properties).apply { minZoom = min }
        style.addLayerBelow(layer, if (navigation) HIGHLIGHT_GLOW else HIGHLIGHT_CASING)
    }
    layer("surface", all(not(underground), not(siding)), 13f, lineColor("#71858B"),
        lineWidth(interpolate(linear(), zoom(), stop(13, .65), stop(16, 1.5))), lineOpacity(if (navigation) .46f else .82f))
    layer("tunnel", underground, 13f, lineColor("#52677C"), lineWidth(1.25f),
        lineDasharray(arrayOf(2.5f, 1.8f)), lineOpacity(if (navigation) .52f else .86f))
    layer("siding", siding, 15f, lineColor("#77888C"), lineWidth(.85f), lineOpacity(if (navigation) .39f else .70f))
}
