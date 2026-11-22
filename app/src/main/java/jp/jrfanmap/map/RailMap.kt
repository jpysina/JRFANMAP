package jp.jrfanmap.map

import android.graphics.RectF
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.jrfanmap.data.Fix
import jp.jrfanmap.rail.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

class MapHandle {
    internal var map: MapLibreMap? = null
    fun area(): DownloadArea? = map?.let {
        val b = it.projection.visibleRegion.latLngBounds
        val min = (it.cameraPosition.zoom.toInt() - 1).coerceIn(4, 15)
        val max = (min + 2).coerceAtMost(16)
        DownloadArea(b, min, max, TileBudget.count(b.latitudeNorth, b.latitudeSouth, b.longitudeEast, b.longitudeWest, min, max))
    }
}

private data class Viewport(val bounds: RailBounds, val zoom: Double)
private const val TRACKS = "local-railway-lines"
private const val STATIONS = "local-railway-stations"

@Composable
fun RailMap(
    fix: Fix?, path: List<Fix>, follow: Boolean, handle: MapHandle, showGsi: Boolean, catalogYear: Int,
    loadRail: suspend (RailBounds, Boolean) -> Pair<List<RailSection>, List<RailStation>>,
    onLines: (List<Long>) -> Unit, onStation: (String) -> Unit,
    modifier: Modifier = Modifier, onError: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    val errorCallback by rememberUpdatedState(onError)
    val lineCallback by rememberUpdatedState(onLines)
    val stationCallback by rememberUpdatedState(onStation)
    val loadCallback by rememberUpdatedState(loadRail)
    DisposableEffect(view, owner) {
        var disposed = false
        var started = false
        var resumed = false
        val listener = MapView.OnDidFailLoadingMapListener { errorCallback("地图加载失败，可切换到内置铁路地图") }
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
            instance.addOnMapClickListener { location ->
                val pixel = instance.projection.toScreenLocation(location)
                val radius = 14 * context.resources.displayMetrics.density
                val rect = RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius)
                val hit = instance.queryRenderedFeatures(rect, TRACKS)
                    .mapNotNull { it.getStringProperty("railId")?.toLongOrNull() }.distinct().take(12)
                val station = instance.queryRenderedFeatures(pixel, STATIONS).firstOrNull()?.getStringProperty("station")
                when {
                    station != null -> stationCallback(station)
                    hit.isNotEmpty() -> lineCallback(hit)
                }
                station != null || hit.isNotEmpty()
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
            view.removeOnDidFailLoadingMapListener(listener)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(map, showGsi) {
        val instance = map ?: return@LaunchedEffect
        loadedStyle = null
        instance.setStyle(Style.Builder().fromUri(if (showGsi) OfflineMaps.STYLE else "asset://rail-schematic.json")) { style ->
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            style.addSource(GeoJsonSource("rail-local", empty))
            style.addLayer(LineLayer(TRACKS, "rail-local").withProperties(lineColor(if (showGsi) "#0A696C" else "#86DFE0"),
                lineWidth(3f), lineOpacity(0.92f)))
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
        style.getSourceAs<GeoJsonSource>("rail-local")?.setGeoJson(lines)
        style.getSourceAs<GeoJsonSource>("stations-local")?.setGeoJson(stations)
    }
    LaunchedEffect(loadedStyle, fix, follow) {
        val style = loadedStyle ?: return@LaunchedEffect
        val feature = fix?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        style.getSourceAs<GeoJsonSource>("device")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(feature)))
        val instance = map ?: return@LaunchedEffect
        if (follow && fix != null) {
            val target = LatLng(fix.latitude, fix.longitude)
            if (instance.cameraPosition.target?.distanceTo(target)?.let { it > 100 } != false) {
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
    AndroidView(factory = { view }, modifier = modifier)
}
