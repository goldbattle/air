package com.aria.air.sync

// Keeping each flight's weather (takeoff, landing, route) filled in and fresh.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant

/** Fills in and refreshes the weather kept with each flight, on [Cadence.weatherEvery]'s schedule. */
object WeatherSync {
    /** Fills in takeoff/landing weather: once for past flights, every 3 h for upcoming ones until they happen. */
    suspend fun refresh(f: Flight): Flight {
        val o = f.origin ?: return f
        val d = f.dest ?: return f
        val now = Instant.now(clock)
        fun stale(w: Wx?, happened: Boolean) = w == null || (!happened && Duration.between(w.fetched, now) > Cadence.weatherEvery(now, w.at))
        var n = f
        val dep = f.dep ?: f.depGuess
        val arr = f.arr ?: f.arrGuess
        if (stale(f.depWx, dep < now.minus(Duration.ofHours(1)))) OpenMeteo.weather(o, dep)?.let { n = n.copy(depWx = it) }
        if (stale(f.arrWx, arr < now.minus(Duration.ofHours(1)))) OpenMeteo.weather(d, arr)?.let { n = n.copy(arrWx = it) }
        return refreshRoute(n)
    }

    /** One pass over every flight still missing weather (e.g. an imported history). */
    suspend fun backfill() {
        for (f in Store.flights.filter { it.depWx == null || it.arrWx == null || it.routeWx == null }.toList()) {
            val n = runCatching { refresh(f) }.getOrDefault(f)
            if (n != f) Store.get(f.id)?.let { cur -> Store.upsert(cur.copy(depWx = n.depWx ?: cur.depWx, arrWx = n.arrWx ?: cur.arrWx, routeWx = n.routeWx ?: cur.routeWx)) }
            kotlinx.coroutines.delay(250) // be polite to the free API
        }
    }

    /**
     * Fills or refreshes [f]'s route weather: on the takeoff weather's cadence while upcoming, then once more after
     * landing so the record is what happened rather than a forecast.
     */
    suspend fun refreshRoute(f: Flight): Flight {
        if (f.cancelled || !Layers.weather) return f // off by default: nothing fetched unless the layer is on
        val now = Instant.now(clock)
        val arr = f.arr ?: f.arrGuess
        val w = f.routeWx
        val stale = w == null || w.fetched < arr &&
            (arr < now.minus(Duration.ofHours(1)) || Duration.between(w.fetched, now) > Cadence.weatherEvery(now, f.dep ?: f.depGuess))
        if (!stale) return f
        return runCatching { Layers.loading(Layers.WEATHER) { OpenMeteo.route(f) } }.getOrNull()?.let { f.copy(routeWx = it) } ?: f
    }
}
