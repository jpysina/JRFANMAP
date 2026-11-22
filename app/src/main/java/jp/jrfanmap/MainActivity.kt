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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import jp.jrfanmap.location.TrackingService
import jp.jrfanmap.ui.RailApp
import jp.jrfanmap.ui.RailViewModel

class MainActivity : ComponentActivity() {
    private val model by viewModels<RailViewModel>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) model.store.monitor.visible(true)
    }
    private val notification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTracking() }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { model.export(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RailApp(model, onRecord = {
                if (model.store.telemetry.value.recording) stopService(Intent(this, TrackingService::class.java))
                else if (!hasPreciseLocation()) {
                    model.message.value = "锁屏记录需要精确定位权限，请授权后再开始记录"
                    requestLocation()
                } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !getPreferences(0).getBoolean("notificationRequested", false)) {
                    getPreferences(0).edit().putBoolean("notificationRequested", true).apply()
                    notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startTracking()
            }, onLocationPermission = ::requestLocation, onExport = { id -> model.exportId = id; export.launch("jrfanmap-trip-$id.csv") }, onKeepAwake = {
                if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }, onImmersive = { immersive ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
                else controller.show(WindowInsetsCompat.Type.systemBars())
            })
        }
    }
    override fun onStart() {
        super.onStart()
        model.store.monitor.visible(true)
        val preferences = getPreferences(0)
        if (!hasPreciseLocation() && !preferences.getBoolean("locationRequested", false)) {
            preferences.edit().putBoolean("locationRequested", true).apply()
            requestLocation()
        }
    }
    override fun onResume() { super.onResume(); model.store.monitor.visible(true) }
    override fun onStop() {
        model.store.monitor.visible(false)
        super.onStop()
    }

    private fun hasPreciseLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocation() {
        if (hasPreciseLocation()) {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    private fun startTracking() {
        runCatching { startForegroundService(Intent(this, TrackingService::class.java)) }
            .onFailure { model.message.value = "无法启动定位服务，请保持应用在前台并检查系统定位权限" }
    }
}
