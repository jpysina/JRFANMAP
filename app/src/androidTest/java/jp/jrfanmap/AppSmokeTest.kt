package jp.jrfanmap

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.jrfanmap.data.Fix
import jp.jrfanmap.rail.RailBounds
import jp.jrfanmap.RailApplication
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import jp.jrfanmap.data.TripDatabase
import jp.jrfanmap.map.DownloadArea
import org.maplibre.android.geometry.LatLngBounds
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensEachPageAndEntersImmersiveMode() {
        compose.onNodeWithTag("live-page").assertIsDisplayed()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithTag("tab-0").assertDoesNotExist()
        compose.onNodeWithText("退出全屏").performClick()
        listOf("gps-page", "map-page", "history-page", "data-page").forEachIndexed { index, page ->
            compose.onNodeWithTag("tab-${index + 1}").performClick()
            compose.onNodeWithTag(page).assertIsDisplayed()
        }
    }

    @Test fun tappingTokyoRailOpensOfflineLineDetails() {
        val catalog = (compose.activity.application as RailApplication).store.catalog
        assertTrue(catalog.prepare().lines > 500)
        compose.onNodeWithTag("tab-2").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rail-map-ready").performTouchInput { click(center) }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("站点名录").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("rail-choice").fetchSemanticsNodes().isNotEmpty()
        }
        val choices = compose.onAllNodesWithTag("rail-choice")
        if (choices.fetchSemanticsNodes().isNotEmpty()) choices.onFirst().performClick()
        compose.onNodeWithText("站点名录").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("下载标准地图").performClick()
        compose.onNodeWithText("下载地理院标准地图").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun downloadsRealGsiTileAndReadsSavedPyramid() {
        val maps = (compose.activity.application as RailApplication).store.offline
        compose.waitUntil(10_000) { !maps.state.value.busy }
        val before = maps.state.value.count
        val bounds = LatLngBounds.from(35.687, 139.775, 35.680, 139.764)
        compose.runOnIdle { maps.download(DownloadArea(bounds, 11, 11, 1)) }
        compose.waitUntil(45_000) { !maps.state.value.busy }
        val state = maps.state.value
        assertEquals(state.message, before + 1, state.count)
        val region = state.regions.first { it.minZoom == 11 && it.maxZoom == 11 }
        val tile = File(URI(region.tileUrl.replace("{z}", "11").replace("{x}", "1819").replace("{y}", "806")))
        assertTrue(tile.isFile && tile.length() > 100)
    }

    @Test fun actualDatabaseRoundTripExportAndRecovery() {
        val context = compose.activity
        val db = TripDatabase(context)
        val id = db.begin()
        try {
            db.add(id, Fix(35.6812,139.7671,40.0,20f,1f,90f,5f,8f,"gps",123456,1000000000), 40.0)
            val out = ByteArrayOutputStream(); db.exportCsv(id, out)
            assertTrue(out.toString("UTF-8").contains("35.6812,139.7671"))
            assertEquals(1, db.list().first { it.id == id }.points)
            db.recover()
            assertTrue(db.list().first { it.id == id }.interrupted)
            assertNotNull(db.list().first { it.id == id }.end)
        } finally { db.delete(id); db.close() }
    }
    @Test fun bundledRailDatabaseContainsJapaneseLinesAndStations() {
        val catalog = (compose.activity.application as RailApplication).store.catalog
        val info = catalog.prepare()
        assertTrue(info.lines > 500)
        assertTrue(info.stations >= 10_000)
        val lines = catalog.sections(RailBounds(139.72, 35.61, 139.80, 35.72))
        assertTrue(lines.map { it.lineId }.distinct().mapNotNull(catalog::network).any { it.line.name == "東海道新幹線" })
        assertTrue(catalog.stations(RailBounds(139.75, 35.67, 139.78, 35.70)).any { it.name == "東京" })
    }

    @Test fun reinstallsPublicRailArchiveAndKeepsStations() {
        val catalog = (compose.activity.application as RailApplication).store.catalog
        val installed = catalog.prepare()
        val updated = runBlocking { catalog.update(reinstall = true) {} }
        assertTrue(updated.year >= installed.year)
        assertTrue(updated.stations >= 10_000)
        assertTrue(catalog.stations(RailBounds(139.75, 35.67, 139.78, 35.70)).any { it.name == "東京" })
    }
}
