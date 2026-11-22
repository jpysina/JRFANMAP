package jp.jrfanmap.map

import jp.jrfanmap.rail.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class JapanBoundaryTest {
    private val boundary = JapanBoundary.fromGeoJson(File("src/main/assets/japan-boundary.geojson").readText())

    @Test fun abroadAndUnresolvedLocationsUseShinjuku() {
        val outside = listOf(null, GeoPoint(121.4737, 31.2304), GeoPoint(126.972, 37.555),
            GeoPoint(131.885, 43.115), GeoPoint(140.0, 27.0))
        for (point in outside) assertEquals(MapAnchor.Shinjuku, boundary.anchor(point))
    }

    @Test fun recognisesJapanBeyondTheMainIslandAndKeepsItsDevicePosition() {
        val stations = listOf(SHINJUKU_STATION, GeoPoint(141.3508, 43.0687), GeoPoint(130.4207, 33.5904),
            GeoPoint(127.6793, 26.2130), GeoPoint(122.9895, 24.4680))
        for (station in stations) assertEquals(MapAnchor(station, false), boundary.anchor(station))
    }
}
