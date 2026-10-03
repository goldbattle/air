package com.aria.air.data

// The flight map's layer switches, kept in Prefs, and which layers are downloading.

import com.aria.air.util.*

/** Map layers the flight page's layers button toggles: radar on by default; the ones that cost requests off. */
object Layers {
    /** Prefs keys of the layers. Radar is on by default; the others cost requests, so they start off. */
    const val RADAR = "layerRadar"
    const val WEATHER = "layerWeather"
    const val ROUTES = "layerRoutes"
    const val FORECAST = "layerForecast"

    /** Precipitation radar (live, then the rain map once the flight is over). */
    val radar get() = Prefs.flag(RADAR)

    /** HRRR forecast radar before takeoff, US routes only. */
    val forecast get() = Prefs.flag(FORECAST, default = false)

    /** Weather along the route, fetched from Open-Meteo. */
    val weather get() = Prefs.flag(WEATHER, default = false)

    /** Paths flown recently under the same flight number. */
    val routes get() = Prefs.flag(ROUTES, default = false)

    /** Whether the layer with this Prefs key is on. */
    fun on(key: String) = when (key) { WEATHER -> weather; ROUTES -> routes; FORECAST -> forecast; else -> radar }

    /** Layers with a download in progress, for the spinner next to their switch. */
    val busy = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    /**
     * Runs [block] while counting [layer] as busy, so its switch shows a spinner. The count comes back down even when
     * it fails.
     */
    suspend fun <T> loading(layer: String, block: suspend () -> T): T {
        busy[layer] = (busy[layer] ?: 0) + 1
        try { return block() } finally { busy[layer] = (busy[layer] ?: 1) - 1 }
    }
}
