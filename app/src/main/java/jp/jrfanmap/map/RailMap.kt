package jp.jrfanmap.map

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.jrfanmap.data.Fix
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

class MapHandle {
    var map: MapLibreMap? = null
    fun area(): DownloadArea? = map?.let {
        val b = it.projection.visibleRegion.latLngBounds
        val min = (it.cameraPosition.zoom.toInt() - 1).coerceIn(4, 15)
        val max = (min + 2).coerceAtMost(16)
        DownloadArea(b, min, max, TileBudget.count(b.latitudeNorth, b.latitudeSouth, b.longitudeEast, b.longitudeWest, min, max))
    }
}

@Composable
fun RailMap(fix: Fix?, path: List<Fix>, follow: Boolean, handle: MapHandle, modifier: Modifier = Modifier, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { MapView(context).apply { onCreate(Bundle()) } }
    var ready by remember { mutableStateOf(false) }
    val errorCallback by rememberUpdatedState(onError)
    DisposableEffect(view, owner) {
        val listener = MapView.OnDidFailLoadingMapListener { errorCallback("地图加载失败：请检查网络或下载离线区域") }
        view.addOnDidFailLoadingMapListener(listener)
        view.getMapAsync { map ->
            handle.map = map
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isAttributionEnabled = true
            map.cameraPosition = CameraPosition.Builder().target(LatLng(35.6812, 139.7671)).zoom(11.0).build()
            map.setStyle(Style.Builder().fromUri(OfflineMaps.STYLE)) { style ->
                style.addSource(GeoJsonSource("trip", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("trip-line", "trip").withProperties(lineColor("#007D72"), lineWidth(4f), lineOpacity(0.85f)))
                style.addSource(GeoJsonSource("device", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(CircleLayer("device-dot", "device").withProperties(circleColor("#007D72"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
                ready = true
            }
        }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); handle.map = null; view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(ready, fix, path, follow) {
        if (!ready) return@LaunchedEffect
        val map = handle.map ?: return@LaunchedEffect
        map.style?.getSourceAs<GeoJsonSource>("device")?.setGeoJson(if (fix == null) FeatureCollection.fromFeatures(emptyArray<Feature>()) else FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)))))
        val segments = mutableListOf<MutableList<Point>>()
        var prev: Fix? = null
        for (f in path) {
            if (prev == null || (f.elapsedNanos - prev.elapsedNanos) > 15_000_000_000 || kotlin.math.abs(f.longitude - prev.longitude) > 180) segments.add(mutableListOf())
            segments.last().add(Point.fromLngLat(f.longitude, f.latitude)); prev = f
        }
        map.style?.getSourceAs<GeoJsonSource>("trip")?.setGeoJson(FeatureCollection.fromFeatures(segments.filter { it.size >= 2 }.map { Feature.fromGeometry(LineString.fromLngLats(it)) }))
        if (follow && fix != null) map.cameraPosition = CameraPosition.Builder(map.cameraPosition).target(LatLng(fix.latitude, fix.longitude)).build()
    }
    AndroidView(factory = { view }, modifier = modifier)
}
