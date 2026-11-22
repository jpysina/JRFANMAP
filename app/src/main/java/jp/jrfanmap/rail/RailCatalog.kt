package jp.jrfanmap.rail

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class GeoPoint(val lon: Double, val lat: Double)
data class RailLine(val id: Long, val operator: String, val name: String, val category: String)
data class RailSection(val lineId: Long, val shape: List<GeoPoint>)
data class RailStation(val name: String, val point: GeoPoint)
data class RailNetwork(val line: RailLine, val sections: List<RailSection>, val stations: List<RailStation>)
data class CatalogInfo(val year: Int, val lines: Int, val stations: Int, val bytes: Long)
data class RailBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun arguments() = arrayOf(west, east, south, north).map { it.toString() }.toTypedArray()
}

const val CATALOG_SOURCE = "https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2025.html"
private const val CATALOG_INDEX = "https://nlftp.mlit.go.jp/ksj/index.html"
const val CATALOG_CREDIT = "国土交通省「国土数値情報（鉄道データ）」を加工して作成 · CC BY 4.0"
const val BUNDLED_YEAR = 2025

class RailCatalog(private val context: Context) : SQLiteOpenHelper(context, "rail-catalog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY, operator TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sections(line INTEGER NOT NULL, west REAL, east REAL, south REAL, north REAL, shape TEXT NOT NULL)")
        db.execSQL("CREATE INDEX section_bounds ON sections(west,east,south,north)")
        db.execSQL("CREATE INDEX section_line ON sections(line)")
        db.execSQL("CREATE TABLE stations(line INTEGER NOT NULL, name TEXT NOT NULL, lon REAL, lat REAL)")
        db.execSQL("CREATE INDEX station_line ON stations(line)")
        db.execSQL("CREATE TABLE metadata(year INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun info(): CatalogInfo {
        val db = readableDatabase
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
        val year = db.rawQuery("SELECT year FROM metadata", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return if (year == 0) CatalogInfo(0, 0, 0, 0) else
            CatalogInfo(year, count("lines"), count("stations"), context.getDatabasePath(databaseName).length())
    }

    @Synchronized
    fun prepare(): CatalogInfo {
        info().takeIf { it.year > 0 }?.let { return it }
        replace(BUNDLED_YEAR) { insert ->
            GZIPInputStream(context.assets.open("rail-2025.tsv.gz")).bufferedReader().useLines { rows ->
                rows.forEach { row ->
                    val fields = row.split('\t', limit = 5)
                    insert(fields[0], fields[1], fields[2], fields[3], decodeShape(fields[4]))
                }
            }
        }
        return info()
    }

    suspend fun update(reinstall: Boolean, report: (String) -> Unit): CatalogInfo = withContext(Dispatchers.IO) {
        val previous = prepare()
        report("检查国土交通省数据版本…")
        val index = readPage(URI(CATALOG_INDEX))
        val detailRegex = Regex("""gml/datalist/(KsjTmplt-N02-(20\d{2})\.html)""")
        val detail = detailRegex.findAll(index).maxByOrNull { it.groupValues[2].toInt() }
        val detailPage = URI(if (detail == null) CATALOG_SOURCE else
            "https://nlftp.mlit.go.jp/ksj/gml/datalist/${detail.groupValues[1]}")
        val html = readPage(detailPage)
        val archiveRegex = Regex("""\.\./data/N02/N02-(\d{2})/N02-\1_GML\.zip""")
        val latest = archiveRegex.findAll(html).maxByOrNull { it.groupValues[1].toInt() }
            ?: error("未识别到官方数据版本")
        val year = 2000 + latest.groupValues[1].toInt()
        if (year < previous.year || (year == previous.year && !reinstall)) return@withContext previous
        val url = detailPage.resolve(latest.value).toURL()
        val tmp = java.io.File.createTempFile("rail-n02-", ".zip", context.cacheDir)
        try {
            report("下载 $year 年度铁路数据…")
            publicRequest(url.toURI()) { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        total += length
                        check(total <= 48L * 1024 * 1024) { "数据包超过允许大小" }
                        output.write(buffer, 0, length)
                    }
                }
            }
            report("校验并导入本地数据库…")
            ZipFile(tmp).use { zip ->
                fun entry(suffix: String) = zip.entries().asSequence().single {
                    it.name.split('/').contains("UTF-8") && it.name.endsWith("_$suffix.geojson")
                }.also { check(it.size in 1..96L * 1024 * 1024) { "数据文件大小异常" } }
                val sections = entry("RailroadSection")
                val stations = entry("Station")
                replace(year) { insert ->
                    zip.getInputStream(sections).bufferedReader().use { readFeatures(JsonReader(it), insert) }
                    zip.getInputStream(stations).bufferedReader().use { readFeatures(JsonReader(it), insert) }
                }
            }
            info()
        } finally {
            tmp.delete()
        }
    }

    private fun readPage(uri: URI): String = publicRequest(uri) { it.readNBytesBounded(1_000_000).decodeToString() }

    private fun <T> publicRequest(uri: URI, read: (java.io.InputStream) -> T): T {
        val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "JRFANMAP/0.2.1 Android")
        try {
            val status = connection.responseCode
            if (status != 200) throw PublicCatalogHttpException(status)
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    private fun replace(year: Int, load: ((String, String, String, String, List<GeoPoint>) -> Unit) -> Unit) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            for (table in listOf("metadata", "stations", "sections", "lines")) db.delete(table, null, null)
            val ids = mutableMapOf<Pair<String, String>, Long>()
            val line = db.compileStatement("INSERT INTO lines(operator,name,category) VALUES (?,?,?)")
            val section = db.compileStatement("INSERT INTO sections(line,west,east,south,north,shape) VALUES (?,?,?,?,?,?)")
            val station = db.compileStatement("INSERT INTO stations(line,name,lon,lat) VALUES (?,?,?,?)")
            var sectionCount = 0
            var stationCount = 0
            line.use { section.use { station.use {
                load { operator, name, category, stationName, points ->
                    if (operator.isNotBlank() && name.isNotBlank() && points.size >= 2 &&
                        points.all { it.lon in 120.0..155.0 && it.lat in 20.0..47.0 }) {
                        val id = ids.getOrPut(operator to name) {
                            line.bindString(1, operator); line.bindString(2, name); line.bindString(3, category)
                            line.executeInsert()
                        }
                        if (stationName.isBlank()) {
                            section.bindLong(1, id); section.bindDouble(2, points.minOf { it.lon })
                            section.bindDouble(3, points.maxOf { it.lon }); section.bindDouble(4, points.minOf { it.lat })
                            section.bindDouble(5, points.maxOf { it.lat })
                            section.bindString(6, points.joinToString(";") { "${it.lon},${it.lat}" })
                            section.executeInsert()
                            sectionCount++
                        } else {
                            station.bindLong(1, id); station.bindString(2, stationName)
                            station.bindDouble(3, points.map { it.lon }.average())
                            station.bindDouble(4, points.map { it.lat }.average())
                            station.executeInsert()
                            stationCount++
                        }
                    }
                }
            } } }
            check(ids.size > 400 && stationCount > 8000 && sectionCount > 15_000) { "全国数据不完整，保留现有数据库" }
            db.execSQL("INSERT INTO metadata VALUES (?)", arrayOf(year))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun sections(bounds: RailBounds): List<RailSection> =
        readSections("east>=? AND west<=? AND north>=? AND south<=?", bounds.arguments())

    fun stations(bounds: RailBounds): List<RailStation> = readableDatabase.rawQuery(
        "SELECT DISTINCT name,lon,lat FROM stations WHERE lon>=? AND lon<=? AND lat>=? AND lat<=?",
        bounds.arguments()).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(RailStation(cursor.getString(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2))))
        }
    }

    private fun readSections(where: String, args: Array<String>) =
        readableDatabase.rawQuery("SELECT line,shape FROM sections WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) add(RailSection(c.getLong(0), decodeShape(c.getString(1)))) }
        }

    fun network(id: Long): RailNetwork? {
        val db = readableDatabase
        val args = arrayOf(id.toString())
        val line = db.rawQuery("SELECT operator,name,category FROM lines WHERE id=?", args).use {
            if (it.moveToFirst()) RailLine(id, it.getString(0), it.getString(1), it.getString(2)) else null
        } ?: return null
        val stations = db.rawQuery("SELECT name,lon,lat FROM stations WHERE line=?", args).use { c ->
            buildList { while (c.moveToNext()) add(RailStation(c.getString(0), GeoPoint(c.getDouble(1), c.getDouble(2)))) }
        }
        return RailNetwork(line, readSections("line=?", args), stations)
    }

    fun lines(ids: List<Long>): List<RailLine> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val byId = readableDatabase.rawQuery("SELECT id,operator,name,category FROM lines WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    put(id, RailLine(id, cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                }
            }
        }
        return ids.mapNotNull(byId::get)
    }

    private fun decodeShape(value: String) = value.split(';').map {
        val (lon, lat) = it.split(',', limit = 2)
        GeoPoint(lon.toDouble(), lat.toDouble())
    }
}

class PublicCatalogHttpException(val status: Int) : java.io.IOException("国土交通省返回 HTTP $status")

internal fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toByteArray()
        check(result.size() + count <= limit) { "响应超过允许大小" }
        result.write(buffer, 0, count)
    }
}

private fun readFeatures(reader: JsonReader, insert: (String, String, String, String, List<GeoPoint>) -> Unit) {
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() != "features") { reader.skipValue(); continue }
        reader.beginArray()
        while (reader.hasNext()) {
            val properties = mutableMapOf<String, String>()
            val points = mutableListOf<GeoPoint>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "properties" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            if (key in setOf("N02_002", "N02_003", "N02_004", "N02_005") &&
                                reader.peek() != android.util.JsonToken.NULL) properties[key] = reader.nextString()
                            else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    "geometry" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() != "coordinates") { reader.skipValue(); continue }
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginArray()
                                points += GeoPoint(reader.nextDouble(), reader.nextDouble())
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                            }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            insert(properties["N02_004"].orEmpty(), properties["N02_003"].orEmpty(), properties["N02_002"].orEmpty(), properties["N02_005"].orEmpty(), points)
        }
        reader.endArray()
    }
    reader.endObject()
}
