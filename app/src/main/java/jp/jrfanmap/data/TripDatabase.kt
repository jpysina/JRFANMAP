package jp.jrfanmap.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.OutputStream

data class Trip(val id: Long, val start: Long, val end: Long?, val points: Int, val distance: Double, val interrupted: Boolean)
data class TripSpeedBucket(val firstTime: Long, val lastTime: Long, val meanKmh: Float, val maxKmh: Float)
data class TripSpeedHistory(val start: Long, val end: Long, val buckets: List<TripSpeedBucket>) {
    val peakKmh: Float get() = buckets.maxOfOrNull { it.maxKmh } ?: 0f
}

class TripDatabase(context: Context) : SQLiteOpenHelper(context, "trips.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, interrupted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE fixes(id INTEGER PRIMARY KEY, trip INTEGER NOT NULL, time INTEGER NOT NULL, lat REAL, lon REAL, altitude REAL, speed REAL, accuracy REAL, bearing REAL, provider TEXT, mock INTEGER, segment REAL)")
        db.execSQL("CREATE INDEX trip_fixes ON fixes(trip, time)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun recover() { writableDatabase.execSQL("UPDATE trips SET end=COALESCE((SELECT MAX(time) FROM fixes WHERE trip=trips.id),start), interrupted=1 WHERE end IS NULL") }
    @Synchronized fun begin(): Long = writableDatabase.insertOrThrow("trips", null, ContentValues().apply { put("start", System.currentTimeMillis()) })
    @Synchronized fun finish(id: Long) { writableDatabase.update("trips", ContentValues().apply { put("end", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString())) }
    @Synchronized fun add(id: Long, f: Fix, segment: Double) {
        writableDatabase.insertOrThrow("fixes", null, ContentValues().apply {
            put("trip", id); put("time", f.time); put("lat", f.latitude); put("lon", f.longitude)
            put("altitude", f.altitude); put("speed", f.speedMps); put("accuracy", f.accuracy)
            put("bearing", f.bearing); put("provider", f.provider); put("mock", if (f.mock) 1 else 0); put("segment", segment)
        })
    }
    @Synchronized fun list(): List<Trip> = readableDatabase.rawQuery("SELECT t.id,t.start,t.end,COUNT(f.id),COALESCE(SUM(f.segment),0),t.interrupted FROM trips t LEFT JOIN fixes f ON t.id=f.trip GROUP BY t.id ORDER BY t.start DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Trip(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3), c.getDouble(4), c.getInt(5) != 0)) }
    }
    @Synchronized fun delete(id: Long) { writableDatabase.beginTransaction(); try {
        writableDatabase.delete("fixes", "trip=?", arrayOf(id.toString())); writableDatabase.delete("trips", "id=?", arrayOf(id.toString())); writableDatabase.setTransactionSuccessful()
    } finally { writableDatabase.endTransaction() } }

    @Synchronized fun speedHistory(id: Long): TripSpeedHistory? {
        val db = readableDatabase
        val selection = arrayOf(id.toString())
        val (start, end) = db.rawQuery("SELECT MIN(time), MAX(time) FROM fixes WHERE trip=?", selection).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            cursor.getLong(0) to cursor.getLong(1)
        }
        val window = ((end - start) / 360 + 1).coerceAtLeast(1_000)
        val buckets = db.rawQuery("""
            SELECT MIN(time), MAX(time), AVG(speed), MAX(speed) FROM fixes
            WHERE time BETWEEN ? AND ? AND trip=? AND mock=0 AND speed BETWEEN 0 AND 180
            GROUP BY (time - ?) / ? ORDER BY MIN(time)
        """.trimIndent(), arrayOf(start.toString(), end.toString(), id.toString(), start.toString(), window.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(TripSpeedBucket(cursor.getLong(0), cursor.getLong(1),
                    cursor.getFloat(2) * 3.6f, cursor.getFloat(3) * 3.6f))
            }
        }
        return TripSpeedHistory(start, end, buckets)
    }

    @Synchronized fun exportCsv(id: Long, output: OutputStream) {
        output.bufferedWriter().use { w ->
            w.appendLine("timestamp_utc_ms,latitude,longitude,altitude_wgs84_m,speed_mps,accuracy_m,bearing_deg,provider,mock")
            readableDatabase.rawQuery("SELECT time,lat,lon,altitude,speed,accuracy,bearing,provider,mock FROM fixes WHERE trip=? ORDER BY time,id", arrayOf(id.toString())).use { c ->
                while (c.moveToNext()) w.appendLine((0..8).joinToString(",") { column ->
                    if (c.isNull(column)) "" else when (column) {
                        0, 8 -> c.getLong(column).toString()
                        7 -> c.getString(column)
                        else -> c.getDouble(column).toString()
                    }
                })
            }
        }
    }
}
