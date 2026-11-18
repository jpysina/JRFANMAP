package jp.jrfanmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class RailNotice(val railway: String, val status: String, val text: String, val published: String)
object RailParser {
    private fun localized(value: Any?): String = when (value) {
        is JSONObject -> value.optString("ja").ifBlank { value.optString("en") }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }
    fun parse(json: String): List<RailNotice> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            RailNotice(item.optString("odpt:railway").removePrefix("odpt.Railway:JR-East.").ifBlank { "JR 东日本" },
                localized(item.opt("odpt:trainInformationStatus")).ifBlank { "状态未提供" },
                localized(item.opt("odpt:trainInformationText")).ifBlank { "没有说明文本" }, item.optString("dc:date"))
        }
    }
}
class RailRepository {
    suspend fun fetch(key: String): List<RailNotice> = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "请先在设置中保存 ODPT API Key" }
        val encoded = URLEncoder.encode(key.trim(), "UTF-8")
        val url = URI("https://api.odpt.org/api/v4/odpt:TrainInformation?odpt:operator=odpt.Operator:JR-East&acl:consumerKey=$encoded").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 15000
        connection.instanceFollowRedirects = false // Never forward the key to another host.
        try {
            val status = connection.responseCode
            check(status == 200) { when (status) {
                401, 403 -> "ODPT 拒绝访问（$status），请检查 Key 与数据访问权限"
                429 -> "请求过于频繁，请稍后刷新"
                else -> "交通数据服务返回 HTTP $status"
            } }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            RailParser.parse(text)
        } finally { connection.disconnect() }
    }
}
