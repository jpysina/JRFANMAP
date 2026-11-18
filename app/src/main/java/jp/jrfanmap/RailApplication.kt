package jp.jrfanmap

import android.app.Application
import jp.jrfanmap.data.AppStore
import org.maplibre.android.MapLibre

class RailApplication : Application() {
    lateinit var store: AppStore
        private set
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        store = AppStore(this)
    }
}
