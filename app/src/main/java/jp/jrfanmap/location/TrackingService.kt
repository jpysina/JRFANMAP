package jp.jrfanmap.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import jp.jrfanmap.MainActivity
import jp.jrfanmap.R
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.*
import kotlinx.coroutines.*

class TrackingService : Service() {
    private val store get() = (application as RailApplication).store
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var trip: Long? = null
    private var previous: Fix? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (trip != null) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            store.update { it.copy(message = "需要精确定位权限才能记录") }; stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tracking", "行程记录", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "tracking").setSmallIcon(R.drawable.ic_train)
            .setContentTitle("JRFANMAP · 正在记录行程").setContentText("锁屏后继续定位 · 点击返回仪表")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止记录", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification)
            trip = store.database.begin()
            store.update { it.copy(recording = true, tripId = trip, startedAt = System.currentTimeMillis(),
                distanceM = 0.0, maxSpeedKmh = 0f, path = emptyList()) }
            store.monitor.record(::recordFix)
        } catch (_: SecurityException) {
            store.update { it.copy(message = "定位权限已撤销，请重新授权") }; stopSelf()
        } catch (_: Exception) {
            store.update { it.copy(message = "无法启动记录，请检查系统定位与存储空间") }; stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun recordFix(f: Fix) {
        val id = trip ?: return
        val segment = FixPolicy.segment(previous, f)
        previous = f
        store.update { it.copy(distanceM = it.distanceM + segment,
            maxSpeedKmh = if (!f.mock && f.accuracy <= 50) maxOf(it.maxSpeedKmh, (f.speedMps ?: 0f) * 3.6f) else it.maxSpeedKmh,
            path = (it.path + f).takeLast(3600)) }
        writer.launch { runCatching { store.database.add(id, f, segment) }.onFailure {
            store.update { it.copy(message = "轨迹写入失败，记录已停止，请检查存储空间") }
            withContext(Dispatchers.Main) { stopSelf() }
        } }
    }
    override fun onDestroy() {
        if (trip != null) store.monitor.record(null)
        val id = trip; trip = null
        store.update { it.copy(recording = false) }
        writer.launch { try { if (id != null) store.database.finish(id) } finally { writer.cancel() } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val ACTION_STOP = "jp.jrfanmap.STOP" }
}
