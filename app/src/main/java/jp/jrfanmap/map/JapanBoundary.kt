package jp.jrfanmap.map

import jp.jrfanmap.rail.GeoPoint
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal val SHINJUKU_STATION = GeoPoint(139.70061, 35.69011)
internal const val SHINJUKU_ZOOM = 13.0
internal const val JAPAN_FIX_ZOOM = 12.0

internal data class MapAnchor(val center: GeoPoint, val shinjukuPreview: Boolean) {
    companion object { val Shinjuku = MapAnchor(SHINJUKU_STATION, true) }
}

/** An offline country outline used only to choose the map camera, never to modify GPS observations. */
internal class JapanBoundary private constructor(private val islands: List<Island>) {
    fun anchor(device: GeoPoint?): MapAnchor =
        if (device != null && islands.any { it.contains(device) }) MapAnchor(device, false) else MapAnchor.Shinjuku

    private class Island(rings: List<List<Point>>) {
        private val outer = rings.first()
        private val holes = rings.drop(1)
        private val west = outer.minOf { it.longitude() }
        private val east = outer.maxOf { it.longitude() }
        private val south = outer.minOf { it.latitude() }
        private val north = outer.maxOf { it.latitude() }

        fun contains(point: GeoPoint): Boolean =
            point.lon in west..east && point.lat in south..north &&
                insideRing(outer, point) && holes.none { insideRing(it, point) }

        private fun insideRing(ring: List<Point>, point: GeoPoint): Boolean {
            var inside = false
            var previous = ring.last()
            for (current in ring) {
                val vertical = (current.latitude() > point.lat) != (previous.latitude() > point.lat)
                if (vertical) {
                    val intersection = current.longitude() + (point.lat - current.latitude()) *
                        (previous.longitude() - current.longitude()) / (previous.latitude() - current.latitude())
                    if (point.lon < intersection) inside = !inside
                }
                previous = current
            }
            return inside
        }
    }

    companion object {
        fun fromGeoJson(json: String): JapanBoundary {
            val polygons = FeatureCollection.fromJson(json).features().orEmpty().flatMap { feature ->
                when (val geometry = feature.geometry()) {
                    is MultiPolygon -> geometry.coordinates()
                    is Polygon -> listOf(geometry.coordinates())
                    else -> emptyList()
                }
            }
            return JapanBoundary(polygons.filter { (it.firstOrNull()?.size ?: 0) >= 4 }.map(::Island))
        }
    }
}
