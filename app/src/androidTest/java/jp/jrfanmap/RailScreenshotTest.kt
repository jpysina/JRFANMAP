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
        assertPanelAreaBelow(.10f)
        takeReviewScreenshot("portrait-collapsed")
        compose.onNodeWithTag("rail-info-expand").performClick()
        compose.onNodeWithText("站点名录").assertIsDisplayed()
        assertPanelAreaBelow(.23f)
        takeReviewScreenshot("portrait-expanded")
        compose.onNodeWithText("国土交通省数据来源 ↗").performScrollTo().assertIsDisplayed()
    }

    @Test fun captureLiveForReview() {
        val store = (compose.activity.application as RailApplication).store
        assertTrue(store.catalog.prepare().lines > 500)
        compose.onNodeWithTag("live-page").assertIsDisplayed()
        compose.onNodeWithText("全屏").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { hasRenderedTracks() }
        val status = compose.onNodeWithText("LIVE ·", substring = true).fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("map-preview-shinjuku").fetchSemanticsNode().boundsInRoot
        assertTrue("Location labels do not overlap", status.bottom <= preview.top)
        takeReviewScreenshot("portrait-live")
    }

    private fun hasRenderedTracks(): Boolean {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        return try {
            (bitmap.height / 4 until bitmap.height * 3 / 4 step 4).any { y ->
                (bitmap.width / 5 until bitmap.width * 4 / 5 step 4).any { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.red(pixel) < 30 && Color.green(pixel) in 110..135 && Color.blue(pixel) in 120..145
                }
            }
        } finally {
            bitmap.recycle()
        }
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
        compose.onNodeWithTag("rail-info-expand").performClick()
        compose.onNodeWithText("站点名录").assertIsDisplayed()
        assertPanelAreaBelow(.21f)
        takeReviewScreenshot("landscape-expanded")
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
