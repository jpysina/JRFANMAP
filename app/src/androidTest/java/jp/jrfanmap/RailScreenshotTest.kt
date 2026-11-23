package jp.jrfanmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import jp.jrfanmap.data.Fix
import jp.jrfanmap.rail.GeoPoint
import jp.jrfanmap.rail.LiveMatch
import jp.jrfanmap.rail.RailBounds
import jp.jrfanmap.rail.TrackGraph
import jp.jrfanmap.ui.RailViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.min
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class RailScreenshotTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureLivePortrait() {
        val simulate = prepareInterval()
        capture("live-portrait")
        compose.onNodeWithTag("live-station-details").performClick()
        compose.waitUntil(12_000) { compose.onAllNodesWithTag("rail-station-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("站点名录").assertIsDisplayed()
        compose.onNodeWithTag("rail-info-close").performClick()
        simulate(20_000)
        compose.onNodeWithTag("live-segment-progress").assertDoesNotExist()
        compose.onNodeWithText("定位已过期").assertIsDisplayed()
    }

    @Test fun captureLiveLandscape() {
        prepareInterval()
        val page = compose.onNodeWithTag("live-page").fetchSemanticsNode().boundsInRoot
        assertTrue(page.width > page.height)
        capture("live-landscape")
    }

    @Test fun captureHistory() {
        val db = (compose.activity.application as RailApplication).store.database
        val id = db.begin()
        try {
            val start = System.currentTimeMillis() - 1_000_000
            repeat(180) { index ->
                val stage = (index % 90) / 90f
                val ramp = min(stage / .23f, (1 - stage) / .26f).coerceIn(0f, 1f)
                val speed = ramp * (72f + sin(index * .29f) * 3f)
                db.add(id, Fix(35.68, 139.76, null, speed, null, null, 4f, null, "gps",
                    start + index * 5_000L + if (index >= 90) 60_000 else 0L, index * 5_000_000_000L), speed * 5.0)
            }
            db.finish(id)
            compose.onNodeWithTag("tab-3").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("速度曲线").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("速度曲线").performClick()
            compose.waitUntil(12_000) { compose.onAllNodesWithTag("trip-speed-plot").fetchSemanticsNodes().isNotEmpty() }
            capture("trip-speed-history")
        } finally {
            db.delete(id)
        }
    }

    /** The screenshots use an actual catalog segment and a controlled GPS observation. */
    private fun prepareInterval(): (Long) -> Unit {
        val activity = compose.activity
        val app = activity.application as RailApplication
        val store = app.store
        store.catalog.prepare()
        lateinit var model: RailViewModel
        compose.runOnIdle {
            model = ViewModelProvider(activity)[RailViewModel::class.java]
            store.monitor.visible(false)
        }
        compose.waitUntil(20_000) { model.catalog.value.info != null }
        compose.onNodeWithText("全屏").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rail-map-ready").fetchSemanticsNodes().isNotEmpty() }
        val network = store.catalog.sections(RailBounds(139.75, 35.64, 139.77, 35.68)).map { it.lineId }.distinct()
            .firstNotNullOf { id -> store.catalog.network(id)?.takeIf { it.line.name == "東海道新幹線" } }
        val position = GeoPoint(139.75805, 35.65750)
        val along = TrackGraph(network).ahead(GeoPoint(139.75834, 35.65940), position)
        val interval = checkNotNull(along.interval)
        check(interval.previous.station.name == "東京" && interval.next.station.name == "品川")
        val simulate: (Long) -> Unit = { age ->
            compose.runOnIdle {
                val observed = SystemClock.elapsedRealtimeNanos() - age * 1_000_000
                val fix = Fix(position.lat, position.lon, 11.0, 25f, 0.5f, 180f, 5f, null,
                    "gps", System.currentTimeMillis() - age, observed)
                store.update { it.copy(active = true, fix = fix, network = true) }
                model.live.value = LiveMatch(network.line, along.reason, 2.0, along.stations, interval, observed)
            }
        }
        simulate(0)
        compose.onNodeWithTag("live-speed").assertTextEquals("90")
        compose.onNodeWithTag("live-segment-progress").assertIsDisplayed()
        compose.onNodeWithText("下一经由站").assertIsDisplayed()
        compose.waitUntil(25_000) {
            simulate(0)
            mapIsPainted()
        }
        val card = compose.onNodeWithTag("live-interval-card").fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("rail-map-ready").fetchSemanticsNode().boundsInRoot
        assertTrue("The floating card keeps most of the map visible", card.width * card.height < canvas.width * canvas.height * .25f)
        return simulate
    }

    private fun mapIsPainted(): Boolean {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val image = ui.takeScreenshot() ?: return false
        try {
            var light = 0
            var route = 0
            for (y in image.height / 5 until image.height * 3 / 5 step 4) {
                for (x in image.width / 5 until image.width * 4 / 5 step 4) {
                    val pixel = image.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    if (r > 233 && r == g && g == b) light++
                    if (r > 229 && g in 135..185 && b < 65) route++
                }
            }
            return light > 500 && route > 12
        } finally { image.recycle() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(400)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "ui-screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
