package jp.jrfanmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RailScreenshotTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capturePortraitForReview() {
        val store = (compose.activity.application as RailApplication).store
        assertTrue(store.catalog.prepare().lines > 500)
        compose.onNodeWithTag("tab-2").performClick()
        openLineForReview()
        awaitShinjukuMap(highlighted = true)
        assertPanelAreaBelow(.10f)
        takeReviewScreenshot("portrait-collapsed")
        compose.onNodeWithTag("rail-info-expand").performClick()
        awaitStationRows()
        assertPanelAreaBelow(.23f)
        takeReviewScreenshot("portrait-expanded")
        compose.onNodeWithTag("reverse-stations").performClick()
        compose.onNodeWithText("田端 → 品川").assertIsDisplayed()
        compose.onNodeWithText("国土交通省数据来源 ↗").performScrollTo().assertIsDisplayed()
    }

    @Test fun captureLiveForReview() {
        val store = (compose.activity.application as RailApplication).store
        assertTrue(store.catalog.prepare().lines > 500)
        compose.onNodeWithTag("live-page").assertIsDisplayed()
        compose.onNodeWithText("全屏").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() }
        awaitShinjukuMap(highlighted = false)
        val status = compose.onNodeWithText("LIVE ·", substring = true).fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("map-preview-shinjuku").fetchSemanticsNode().boundsInRoot
        assertTrue("Location labels do not overlap", status.bottom <= preview.top)
        takeReviewScreenshot("portrait-live")
    }

    @Test fun captureLandscapeForReview() {
        val store = (compose.activity.application as RailApplication).store
        assertTrue(store.catalog.prepare().lines > 500)
        compose.onNodeWithTag("tab-2").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("map-page").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.let {
                it.width > it.height
            } == true
        }
        openLineForReview()
        awaitShinjukuMap(highlighted = true)
        compose.onNodeWithTag("rail-info-expand").performClick()
        awaitStationRows()
        assertPanelAreaBelow(.21f)
        takeReviewScreenshot("landscape-expanded")
    }

    private fun awaitStationRows() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("rail-station-row").fetchSemanticsNodes().size > 2 }
        compose.onNodeWithText("站点名录").assertIsDisplayed()
        compose.onNodeWithText("品川 → 田端").assertIsDisplayed()
    }

    private fun awaitShinjukuMap(highlighted: Boolean) {
        compose.waitUntil(30_000) { mapTilesAndRouteAreVisible(highlighted) }
    }

    private fun mapTilesAndRouteAreVisible(highlighted: Boolean): Boolean {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        var mapPixels = 0
        var railPixels = 0
        try {
            for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 4) {
                for (x in bitmap.width / 5 until bitmap.width * 4 / 5 step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val (red, green, blue) = Triple(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (red > 234 && red == green && green == blue) mapPixels++
                    if (highlighted && red > 229 && green in 135..185 && blue < 65) railPixels++
                    if (!highlighted && red in 30..100 && green in 94..135 && blue in 105..165) railPixels++
                }
            }
            return mapPixels > 500 && railPixels > 20
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPanelAreaBelow(maximum: Float) {
        val panel = compose.onNodeWithTag("rail-info-panel").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("rail-map-ready").fetchSemanticsNode().boundsInRoot
        val ratio = (panel.width * panel.height) / (map.width * map.height)
        println("Rail panel covers ${ratio * 100}% of the map")
        assertTrue("Panel area $ratio is below $maximum", ratio < maximum)
    }

    private fun openLineForReview() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("map-preview-shinjuku").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rail-map-ready").performTouchInput { click(center) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("rail-info-expand").fetchSemanticsNodes().isNotEmpty() }
        val choices = compose.onAllNodesWithTag("rail-choice")
        if (choices.fetchSemanticsNodes().isNotEmpty()) {
            val yamanote = compose.onAllNodesWithText("山手線")
            val choice = if (yamanote.fetchSemanticsNodes().isNotEmpty()) yamanote.onFirst() else choices.onFirst()
            choice.performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("rail-info-expand").fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithTag("rail-info-panel").assertIsDisplayed()
    }

    private fun takeReviewScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination = File(instrumentation.targetContext.filesDir, "ui-screenshots")
        destination.mkdirs()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(destination, "$name.png").outputStream().use { output ->
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { screenshot.recycle() }
    }

}
