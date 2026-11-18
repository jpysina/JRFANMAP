package jp.jrfanmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import jp.jrfanmap.location.TrackingService
import jp.jrfanmap.ui.RailApp
import jp.jrfanmap.ui.RailViewModel

class MainActivity : ComponentActivity() {
    private val model by viewModels<RailViewModel>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startTracking()
        else model.message.value = "未授予精确定位。请在系统设置中允许精确位置；不会开始记录。"
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { model.export(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RailApp(model, onRecord = {
                if (model.store.telemetry.value.recording) stopService(Intent(this, TrackingService::class.java))
                else {
                    val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                    permission.launch(permissions.toTypedArray())
                }
            }, onExport = { id -> model.exportId = id; export.launch("jrfanmap-trip-$id.csv") }, onKeepAwake = {
                if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            })
        }
    }
    private fun startTracking() {
        runCatching { startForegroundService(Intent(this, TrackingService::class.java)) }
            .onFailure { model.message.value = "无法启动定位服务，请保持应用在前台并检查系统定位权限" }
    }
}
