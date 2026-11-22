package jp.jrfanmap.map

import jp.jrfanmap.rail.GeoPoint
import jp.jrfanmap.rail.RailSection
import kotlin.math.hypot

/** Ranks nearby tracks in units of the tap tolerance, independent of rendering or camera zoom. */
internal fun nearbyRailIds(tap: GeoPoint, longitudeRadius: Double, latitudeRadius: Double, tracks: List<RailSection>): List<Long> {
    if (longitudeRadius <= 0 || latitudeRadius <= 0) return emptyList()
    val distances = mutableMapOf<Long, Double>()
    for (track in tracks) {
        for ((a, b) in track.shape.zipWithNext()) {
            val ax = (a.lon - tap.lon) / longitudeRadius
            val ay = (a.lat - tap.lat) / latitudeRadius
            val dx = (b.lon - a.lon) / longitudeRadius
            val dy = (b.lat - a.lat) / latitudeRadius
            val length = dx * dx + dy * dy
            val fraction = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            val distance = hypot(ax + fraction * dx, ay + fraction * dy)
            if (distance <= 1.0 && distance < (distances[track.lineId] ?: Double.POSITIVE_INFINITY)) {
                distances[track.lineId] = distance
            }
        }
    }
    val closest = distances.values.minOrNull() ?: return emptyList()
    return distances.entries.sortedBy { it.value }.takeWhile { it.value <= closest + 0.5 }.take(12).map { it.key }
}
