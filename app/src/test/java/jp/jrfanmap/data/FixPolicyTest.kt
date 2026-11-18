package jp.jrfanmap.data

import org.junit.Assert.*
import org.junit.Test

class FixPolicyTest {
    private fun fix(lat: Double = 35.0, elapsed: Long = 1_000_000_000, accuracy: Float = 4f, mock: Boolean = false) =
        Fix(lat, 139.0, null, null, null, null, accuracy, null, "gps", 0, elapsed, mock)
    @Test fun staleFixDoesNotRemainLive() {
        val f = fix()
        assertTrue(FixPolicy.fresh(f, 11_000_000_000))
        assertFalse(FixPolicy.fresh(f, 12_000_000_000))
    }
    @Test fun measuredSegmentAndNullSpeedStayDistinct() {
        val a = fix(); val b = fix(lat = 35.001, elapsed = 2_000_000_000)
        assertEquals(111.2, FixPolicy.segment(a, b), 0.5)
        assertNull(b.speedMps)
    }
    @Test fun poorAccuracyMockJitterAndTunnelGapsDoNotAddDistance() {
        val a = fix()
        assertEquals(0.0, FixPolicy.segment(a, fix(lat = 35.001, elapsed = 2_000_000_000, accuracy = 100f)), 0.0)
        assertEquals(0.0, FixPolicy.segment(a, fix(lat = 35.001, elapsed = 2_000_000_000, mock = true)), 0.0)
        assertEquals(0.0, FixPolicy.segment(a, fix(lat = 35.000001, elapsed = 2_000_000_000)), 0.0)
        assertEquals(0.0, FixPolicy.segment(a, fix(lat = 35.001, elapsed = 30_000_000_000)), 0.0)
        assertEquals(0.0, FixPolicy.segment(a, fix(lat = 36.0, elapsed = 2_000_000_000)), 0.0)
        assertEquals(0.0, FixPolicy.segment(a, a), 0.0)
    }
    @Test fun antimeridianDistanceUsesShortArc() {
        val a = fix().copy(longitude = 179.999)
        val b = a.copy(longitude = -179.999)
        assertTrue(FixPolicy.distance(a, b) < 200)
    }
}
