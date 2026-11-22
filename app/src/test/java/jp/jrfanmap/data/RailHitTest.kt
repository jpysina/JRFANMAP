package jp.jrfanmap.data

import jp.jrfanmap.map.nearbyRailIds
import jp.jrfanmap.rail.GeoPoint
import jp.jrfanmap.rail.RailSection
import org.junit.Assert.assertEquals
import org.junit.Test

class RailHitTest {
    @Test fun returnsNearestTrackFromOfflineGeometryAtAnOverpass() {
        val sections = listOf(
            RailSection(2, listOf(GeoPoint(139.0, 35.002), GeoPoint(139.02, 35.002))),
            RailSection(1, listOf(GeoPoint(139.0, 35.0), GeoPoint(139.02, 35.0))),
            RailSection(3, listOf(GeoPoint(139.0102, 34.99), GeoPoint(139.0102, 35.01)))
        )
        assertEquals(listOf(1L, 3L), nearbyRailIds(GeoPoint(139.01, 35.0), .001, .001, sections))
        assertEquals(emptyList<Long>(), nearbyRailIds(GeoPoint(139.05, 35.0), .001, .001, sections))
    }
}
