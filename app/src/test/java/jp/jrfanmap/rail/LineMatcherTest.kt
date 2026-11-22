package jp.jrfanmap.rail

import jp.jrfanmap.data.Fix
import org.junit.Assert.*
import org.junit.Test

class LineMatcherTest {
    private val line = RailLine(1, "東日本旅客鉄道", "試験線", "2")
    private fun point(meters: Int, north: Int = 0) = GeoPoint(139.0 + meters / 91_000.0, 35.0 + north / 111_195.0)
    private fun fix(p: GeoPoint, seconds: Long) =
        Fix(p.lat, p.lon, null, 12f, 1f, 90f, 5f, null, "gps", 0, seconds * 1_000_000_000)

    @Test fun motionMatchesTrackAndRejectsNearbyCrossing() {
        val tracks = listOf(
            RailSection(1, listOf(point(0), point(800))),
            RailSection(2, listOf(point(300, -200), point(300, 200)))
        )
        assertEquals(listOf(1L), candidates(fix(point(200), 10), fix(point(300), 20), tracks).map { it.id })
        assertTrue(candidates(fix(point(297), 10), fix(point(300), 20), tracks).isEmpty())
    }

    @Test fun nextStationsAreOrderedInDirectionOfTravel() {
        val graph = TrackGraph(RailNetwork(line, listOf(RailSection(1, listOf(point(0), point(400), point(800)))),
            listOf(RailStation("東京", point(100)), RailStation("神田", point(500)), RailStation("秋葉原", point(740)))))
        assertEquals(listOf("神田", "秋葉原"), graph.ahead(point(190), point(300)).stations.map { it.station.name })
        assertEquals(listOf("東京"), graph.ahead(point(390), point(300)).stations.map { it.station.name })
    }

    @Test fun neverSelectsAnUnobservedBranchOrDisconnectedSection() {
        val graph = TrackGraph(RailNetwork(line, listOf(
            RailSection(1, listOf(point(0), point(400), point(800))),
            RailSection(1, listOf(point(400), point(800, 300))),
            RailSection(1, listOf(point(900), point(1400)))
        ), listOf(RailStation("分岐前", point(300)), RailStation("支線", point(700, 225)), RailStation("本線", point(700)),
            RailStation("遠方", point(1000)))))
        val result = graph.ahead(point(90), point(200))
        assertTrue(result.reason.contains("分岔"))
        assertEquals(listOf("分岐前"), result.stations.map { it.station.name })
    }

    @Test fun bundledTokaidoStationsFollowTheRealGeometry() {
        val graph = TrackGraph(bundledNetwork("東海旅客鉄道", "東海道新幹線"))
        assertTrue(graph.network.stations.any { it.name == "品川" })
        val next = graph.ahead(GeoPoint(139.75834, 35.65940), GeoPoint(139.75805, 35.65750))
        assertEquals("品川", next.stations.firstOrNull()?.station?.name)
    }

    @Test fun bundledYamanoteBrowsingFollowsTheRegisteredTrack() {
        val graph = TrackGraph(bundledNetwork("東日本旅客鉄道", "山手線"))
        assertEquals(listOf("品川", "大崎", "五反田", "目黒", "恵比寿", "渋谷", "原宿", "代々木", "新宿",
            "新大久保", "高田馬場", "目白", "池袋", "大塚", "巣鴨", "駒込", "田端"),
            graph.browsingStationGroups().single().map { it.name })
    }

    @Test fun separatesStationListsAcrossBreaksInTheRailGeometry() {
        val graph = TrackGraph(RailNetwork(line, listOf(
            RailSection(1, listOf(point(0), point(400))),
            RailSection(1, listOf(point(900), point(1400)))
        ), listOf(RailStation("東端", point(1300)), RailStation("西端", point(100)),
            RailStation("西中央", point(300)), RailStation("東中央", point(1000)))))
        assertEquals(setOf(listOf("西端", "西中央"), listOf("東中央", "東端")),
            graph.browsingStationGroups().map { group -> group.map { it.name } }.toSet())
    }

    private fun bundledNetwork(operator: String, name: String): RailNetwork {
        val resource = java.io.File("src/main/assets/rail-2025.seed")
        val sections = mutableListOf<RailSection>()
        val stations = mutableListOf<RailStation>()
        java.util.zip.GZIPInputStream(resource.inputStream()).bufferedReader().useLines { rows ->
            rows.filter { it.startsWith("$operator\t$name\t") }.forEach {
                val parts = it.split('\t', limit = 5)
                val shape = parts[4].split(';').map { item ->
                    val (lon, lat) = item.split(',')
                    GeoPoint(lon.toDouble(), lat.toDouble())
                }
                if (parts[3].isEmpty()) sections += RailSection(1, shape)
                else stations += RailStation(parts[3], GeoPoint(shape.map { it.lon }.average(), shape.map { it.lat }.average()))
            }
        }
        return RailNetwork(line.copy(operator = operator, name = name), sections, stations)
    }
}
