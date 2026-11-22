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
import jp.jrfanmap.data.Fix
import jp.jrfanmap.rail.*
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
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

enum class MapBase { RAIL, STANDARD, VECTOR }

private const val SCHEMATIC_STYLE = "asset://rail-schematic.json"
private const val VECTOR_STYLE = "asset://gsi-style.json"
private const val HIGHLIGHT = "matched-line"
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
    modifier: Modifier = Modifier, matchedLine: Long? = null, ahead: List<AheadStation> = emptyList(),
    onGesture: () -> Unit = {}, onEmptyTap: () -> Unit = {}, onError: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val gestureCallback by rememberUpdatedState(onGesture)
    val emptyTapCallback by rememberUpdatedState(onEmptyTap)
    var tapJob by remember { mutableStateOf<Job?>(null) }
    val view = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    val errorCallback by rememberUpdatedState(onError)
    val lineCallback by rememberUpdatedState(onLines)
    val stationCallback by rememberUpdatedState(onStation)
    val loadCallback by rememberUpdatedState(loadRail)
    val detailStyle = base == MapBase.VECTOR && online
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
            instance.cameraPosition = CameraPosition.Builder()
                .target(fix?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(35.6812, 139.7671)).zoom(10.0).build()
            instance.addOnCameraIdleListener {
                val b = instance.projection.visibleRegion.latLngBounds
                viewport = Viewport(RailBounds(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth),
                    instance.cameraPosition.zoom)
            }
            instance.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureCallback()
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
            style.addLayer(LineLayer(TRACKS, "rail-local").withProperties(lineColor("#087F88"), lineWidth(3f), lineOpacity(0.96f)))
            style.addLayer(LineLayer(HIGHLIGHT, "rail-local").withFilter(Expression.eq(Expression.get("railId"), ""))
                .withProperties(lineColor("#F4B039"), lineWidth(5f)))
            style.addSource(GeoJsonSource("ahead-local", empty))
            style.addLayer(CircleLayer("ahead-dots", "ahead-local").withProperties(circleColor("#F4B039"),
                circleRadius(5f), circleStrokeColor("#15252A"), circleStrokeWidth(2f)))
            style.addSource(GeoJsonSource("stations-local", empty))
            style.addLayer(CircleLayer(STATIONS, "stations-local").withProperties(circleColor("#F6E6AF"),
                circleRadius(3f), circleStrokeColor("#263947"), circleStrokeWidth(1f)))
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
        if (base == MapBase.RAIL || detailStyle) return@LaunchedEffect
        fun add(id: String, url: String, region: GsiRegion? = null) {
            val tiles = TileSet("2.2.0", url).apply {
                attribution = GSI_ATTRIBUTION
                setMinZoom(region?.minZoom?.toFloat() ?: 4f)
                setMaxZoom(region?.maxZoom?.toFloat() ?: 16f)
                if (region != null) setBounds(region.west.toFloat(), region.south.toFloat(), region.east.toFloat(), region.north.toFloat())
            }
            style.addSource(RasterSource(id, tiles, 256))
            style.addLayerBelow(RasterLayer(id, id).withProperties(rasterFadeDuration(0f)), TRACKS)
        }
        if (online) add(GSI_LAYER_PREFIX + "online", GSI_STANDARD + "{z}/{x}/{y}.png")
        regions.forEach { add(GSI_LAYER_PREFIX + it.id, it.tileUrl, it) }
    }

    LaunchedEffect(loadedStyle, matchedLine, ahead) {
        val style = loadedStyle ?: return@LaunchedEffect
        style.getLayerAs<LineLayer>(HIGHLIGHT)?.setFilter(Expression.eq(Expression.get("railId"), matchedLine?.toString().orEmpty()))
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
                }
            }
            val stations = datasets.second.map { station ->
                Feature.fromGeometry(Point.fromLngLat(station.point.lon, station.point.lat)).apply {
                    addStringProperty("station", station.name)
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
    LaunchedEffect(loadedStyle, fix, follow) {
        val style = loadedStyle ?: return@LaunchedEffect
        val feature = fix?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        style.getSourceAs<GeoJsonSource>("device")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(feature)))
        val instance = map ?: return@LaunchedEffect
        if (follow && fix != null) {
            val target = LatLng(fix.latitude, fix.longitude)
            if (instance.cameraPosition.target?.distanceTo(target)?.let { it > 15 } != false) {
                instance.cameraPosition = CameraPosition.Builder(instance.cameraPosition).target(target).build()
            }
        }
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
