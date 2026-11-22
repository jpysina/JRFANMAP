package jp.jrfanmap.rail

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.transit.realtime.GtfsRealtime
import java.net.HttpURLConnection
import java.net.URI

data class OperatorPage(val operator: String, val title: String, val url: String)
val operatorPages = listOf(
    OperatorPage("北海道旅客鉄道", "JR北海道", "https://www3.jrhokkaido.co.jp/webunkou/"),
    OperatorPage("東日本旅客鉄道", "JR東日本", "https://traininfo.jreast.co.jp/train_info/kanto.aspx"),
    OperatorPage("東海旅客鉄道", "JR東海", "https://traininfo.jr-central.co.jp/zairaisen/index.html"),
    OperatorPage("西日本旅客鉄道", "JR西日本", "https://trafficinfo.westjr.co.jp/"),
    OperatorPage("四国旅客鉄道", "JR四国", "https://www.jr-shikoku.co.jp/info/"),
    OperatorPage("九州旅客鉄道", "JR九州", "https://www.jrkyushu.co.jp/trains/info/")
)
data class Alert(val title: String, val description: String, val routeIds: List<String>)
data class AlertSnapshot(val alerts: List<Alert>, val fetchedAt: Long, val issuedAt: Long?)

class OperatorUpdates(context: Context) : SQLiteOpenHelper(context, "operator-alerts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshots(url TEXT PRIMARY KEY, payload BLOB NOT NULL, fetched INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun cached(url: String): AlertSnapshot? = readableDatabase.rawQuery("SELECT payload,fetched FROM snapshots WHERE url=?",
        arrayOf(url)).use { if (it.moveToFirst()) decode(it.getBlob(0), it.getLong(1)) else null }

    fun refresh(url: String): AlertSnapshot {
        validateFeedUrl(url)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/x-protobuf, application/octet-stream")
        connection.setRequestProperty("User-Agent", "JRFANMAP/0.2")
        val bytes = try {
            check(connection.responseCode == 200) { "公告源 HTTP ${connection.responseCode}" }
            connection.inputStream.use { it.readNBytesBounded(1_500_000) }
        } finally { connection.disconnect() }
        val now = System.currentTimeMillis()
        val snapshot = decode(bytes, now)
        writableDatabase.execSQL("INSERT OR REPLACE INTO snapshots VALUES (?,?,?)", arrayOf(url, bytes, now))
        return snapshot
    }

    fun clear() { writableDatabase.delete("snapshots", null, null) }

    companion object {
        fun validateFeedUrl(url: String) {
            val uri = URI(url)
            require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
                uri.userInfo == null && uri.fragment == null && uri.query == null) {
                "请输入公开的 HTTPS 公告源地址，不包含凭据、查询参数或片段"
            }
        }

        internal fun decode(bytes: ByteArray, fetched: Long): AlertSnapshot {
            val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
            check(feed.header.incrementality != GtfsRealtime.FeedHeader.Incrementality.DIFFERENTIAL) {
                "仅支持完整公告快照（FULL_DATASET）"
            }
            val now = fetched / 1_000
            fun GtfsRealtime.TranslatedString.text(): String =
                (translationList.firstOrNull { it.language.startsWith("ja", true) } ?: translationList.firstOrNull())?.text.orEmpty()
            val alerts = feed.entityList.asSequence().filter { it.hasAlert() && !it.isDeleted }.map { it.alert }.filter { alert ->
                alert.activePeriodList.isEmpty() || alert.activePeriodList.any {
                    (!it.hasStart() || now >= it.start) && (!it.hasEnd() || now < it.end)
                }
            }.map {
                Alert(it.headerText.text().take(400), it.descriptionText.text().take(2_000),
                    it.informedEntityList.map { e -> e.routeId }.filter(String::isNotBlank).distinct())
            }.take(150).toList()
            return AlertSnapshot(alerts, fetched, feed.header.timestamp.takeIf { feed.header.hasTimestamp() && it > 0 }?.times(1_000))
        }
    }
}
