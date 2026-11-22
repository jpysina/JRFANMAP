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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensEachPageAndEntersImmersiveMode() {
        compose.onNodeWithTag("live-page").assertIsDisplayed()
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithTag("tab-0").assertDoesNotExist()
        compose.onNodeWithText("退出全屏").performClick()
        listOf("gps-page", "map-page", "history-page", "data-page").forEachIndexed { index, page ->
            compose.onNodeWithTag("tab-${index + 1}").performClick()
            compose.onNodeWithTag(page).assertIsDisplayed()
        }
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
}
