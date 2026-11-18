package jp.jrfanmap.data

import kotlin.math.*

data class Fix(
    val latitude: Double, val longitude: Double, val altitude: Double?,
    val speedMps: Float?, val speedAccuracy: Float?, val bearing: Float?,
    val accuracy: Float, val verticalAccuracy: Float?, val provider: String,
    val time: Long, val elapsedNanos: Long, val mock: Boolean = false
)
data class Satellite(val constellation: String, val id: Int, val cn0: Float, val used: Boolean)
data class Telemetry(
    val recording: Boolean = false, val tripId: Long? = null, val fix: Fix? = null,
    val satellites: List<Satellite> = emptyList(), val pressureHpa: Float? = null,
    val magneticHeading: Float? = null, val acceleration: Float? = null,
    val network: Boolean = false, val message: String = "尚未开始定位",
    val distanceM: Double = 0.0, val maxSpeedKmh: Float = 0f,
    val startedAt: Long? = null, val path: List<Fix> = emptyList()
)

object FixPolicy {
    const val STALE_SECONDS = 10
    fun ageSeconds(fix: Fix, nowNanos: Long) = ((nowNanos - fix.elapsedNanos) / 1_000_000_000L).coerceAtLeast(0)
    fun fresh(fix: Fix, nowNanos: Long) = ageSeconds(fix, nowNanos) <= STALE_SECONDS
    fun distance(a: Fix, b: Fix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6_371_008.8 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
    }
    // Gaps, poor fixes, mock positions, jitter and impossible jumps do not add trip distance.
    fun segment(a: Fix?, b: Fix): Double {
        if (a == null || a.mock || b.mock || a.accuracy > 50 || b.accuracy > 50) return 0.0
        val dt = (b.elapsedNanos - a.elapsedNanos) / 1e9
        if (dt <= 0 || dt > 15) return 0.0
        val d = distance(a, b)
        return if (d < max(3.0, (a.accuracy + b.accuracy) * 0.25) || d / dt > 180) 0.0 else d
    }
}
