package jp.jrfanmap

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.jrfanmap.data.Fix
import jp.jrfanmap.data.KeyStore
import jp.jrfanmap.data.TripDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun navigationWorksWithoutPermissionsOrCredentials() {
        compose.onNodeWithText("沿着铁道，感知此刻").assertIsDisplayed()
        compose.onNodeWithText("●  开始定位与行程记录").assertIsDisplayed()
        compose.onNodeWithText("卫星", useUnmergedTree = true).performClick()
        compose.onNodeWithText("等待卫星状态").assertIsDisplayed()
        compose.onNodeWithText("铁路", useUnmergedTree = true).performClick()
        compose.onNodeWithText("尚未获取运营方数据").assertIsDisplayed()
        compose.onNodeWithText("行程", useUnmergedTree = true).performClick()
        compose.onNodeWithText("行程记录").assertIsDisplayed()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("你的随车观测站").assertIsDisplayed()
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
    @Test fun apiKeyUsesDeviceKeystore() {
        val keys = KeyStore(compose.activity)
        keys.save("local-test-value")
        assertEquals("local-test-value", keys.read())
        val raw = compose.activity.getSharedPreferences("api_keys",0).getString("odpt","")!!
        assertFalse(raw.contains("local-test-value"))
        keys.save(""); assertEquals("", keys.read())
    }
}
