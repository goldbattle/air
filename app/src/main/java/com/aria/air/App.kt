package com.aria.air

// The Application: loads data and starts background work when the process starts.

import com.aria.air.api.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.importer.*
import com.aria.air.sync.*
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The process start: loads the bundled data and saved state into the singletons every screen and worker reads (Prefs,
 * Db, Store, caches), then starts the background flight check, the update check and a weather backfill.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.load(this)
        Land.load(this)
        Guides.init(this)
        Store.init(this)
        RainViewer.init(this)
        NasaGibs.init(this)
        PastRoutes.init(this)
        Mail.init(this)
        Debug.init(this)
        Store.onChange = { scope.launch { FlightWidget.refresh(this@App) } }
        Notify.channels(this)
        Tracker.schedule(this)
        Updater.schedule(this)
        scope.launch { WeatherSync.backfill() }
    }

    /** App-wide state that outlives any screen. */
    companion object {
        /** Outlives screens, so lookups and imports finish when you navigate away. */
        val scope = MainScope()
    }
}
