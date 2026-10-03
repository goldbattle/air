package com.aria.air.data

// The flight map's layer switches, kept in Prefs, and which layers are downloading.

import com.aria.air.util.*
import androidx.compose.material.icons.filled.Layers

/** Map layers the flight page's layers button toggles: radar on by default; the ones that cost requests off. */
object Layers {
    const val RADAR = "layerRadar"
    const val WEATHER = "layerWeather"
    const val ROUTES = "layerRoutes"
    const val FORECAST = "layerForecast"
    val radar get() = Prefs.flag(RADAR)
    val forecast get() = Prefs.flag(FORECAST, default = false)
    val weather get() = Prefs.flag(WEATHER, default = false)
    val routes get() = Prefs.flag(ROUTES, default = false)
    fun on(key: String) = when (key) { WEATHER -> weather; ROUTES -> routes; FORECAST -> forecast; else -> radar }

    /** Layers with a download in progress, for the spinner next to their switch. */
    val busy = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    suspend fun <T> loading(layer: String, block: suspend () -> T): T {
        busy[layer] = (busy[layer] ?: 0) + 1
        try { return block() } finally { busy[layer] = (busy[layer] ?: 1) - 1 }
    }
}
