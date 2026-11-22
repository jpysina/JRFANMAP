package jp.jrfanmap.rail

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.*
import org.junit.Test

class OperatorUpdatesTest {
    @Test fun readsJapaneseAlertAndPreservesSourceTime() {
        val text = GtfsRealtime.TranslatedString.newBuilder()
            .addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder().setLanguage("en").setText("Delay"))
            .addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder().setLanguage("ja-JP").setText("遅延"))
        val alert = GtfsRealtime.Alert.newBuilder().setHeaderText(text).setDescriptionText(text)
            .addInformedEntity(GtfsRealtime.EntitySelector.newBuilder().setRouteId("jr-line"))
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(100))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("alert-1").setAlert(alert))
            .build()
        val snapshot = OperatorUpdates.decode(feed.toByteArray(), 105_000)
        assertEquals(100_000L, snapshot.issuedAt)
        assertEquals("遅延", snapshot.alerts.single().title)
        assertEquals(listOf("jr-line"), snapshot.alerts.single().routeIds)
    }
}
