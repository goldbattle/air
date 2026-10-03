package com.aria.air.api

// Open-Meteo (free, no key): weather at airports and along the route.

import com.aria.air.data.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Open-Meteo: free, no key. Sends only coordinates and dates. */
object OpenMeteo {
    /**
     * Weather at [a] at the hour of [at], from Open-Meteo (free, no key): the forecast API up to ~16 days ahead and
     * for the last two months, the ERA5 archive before that. Sends only the airport's coordinates and the date.
     */
    suspend fun weather(a: Airport, at: Instant): Wx? = withContext(Dispatchers.IO) {
        val utc = at.atZone(java.time.ZoneOffset.UTC)
        val day = utc.toLocalDate()
        val today = LocalDate.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC)
        val host = when {
            day < today.minusDays(60) -> "https://archive-api.open-meteo.com/v1/archive"
            day <= today.plusDays(15) -> "https://api.open-meteo.com/v1/forecast"
            else -> return@withContext null // too far out to forecast yet
        }
        val h = runCatching {
            JSONObject(Http.get("$host?latitude=${a.lat}&longitude=${a.lon}&hourly=temperature_2m,weather_code,wind_speed_10m&start_date=$day&end_date=$day&timezone=GMT"))
                .getJSONObject("hourly")
        }.getOrNull() ?: return@withContext null
        val i = utc.hour
        val t = h.optJSONArray("temperature_2m")?.opt(i) as? Number ?: return@withContext null
        Wx(
            tempC = t.toDouble(), code = (h.optJSONArray("weather_code")?.opt(i) as? Number)?.toInt() ?: -1,
            windKmh = (h.optJSONArray("wind_speed_10m")?.opt(i) as? Number)?.toDouble() ?: 0.0, at = at, fetched = Instant.now(clock),
        )
    }

    const val ROUTE_POINTS = 8

    /** [ROUTE_POINTS] points evenly along the great circle (the ends have takeoff/landing weather) and when the plane is over each. */
    fun samples(f: Flight): List<Triple<Double, Double, Instant>> {
        val o = f.origin ?: return emptyList(); val d = f.dest ?: return emptyList()
        val arc = greatCircle(o.lat, o.lon, d.lat, d.lon, ROUTE_POINTS + 1)
        if (arc.size < ROUTE_POINTS + 2) return emptyList()
        val dep = f.dep ?: f.depGuess
        val ms = Duration.between(dep, f.arr ?: f.arrGuess).toMillis()
        fun r2(v: Double) = Math.round(v * 100) / 100.0
        return (1..ROUTE_POINTS).map { i ->
            val (la, lo) = arc[i]
            Triple(r2(la), r2((lo + 540) % 360 - 180), dep.plusMillis(ms * i / (ROUTE_POINTS + 1)))
        }
    }

    /** One Open-Meteo request for all points: forecast up to ~16 days out and two months back, ERA5 archive before. */
    suspend fun route(f: Flight): RouteWx? = withContext(Dispatchers.IO) {
        val s = samples(f).ifEmpty { return@withContext null }
        val first = s.first().third.atZone(ZoneOffset.UTC).toLocalDate()
        val last = s.last().third.plusSeconds(1800).atZone(ZoneOffset.UTC).toLocalDate()
        val today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val host = when {
            first < today.minusDays(60) -> "https://archive-api.open-meteo.com/v1/archive"
            last <= today.plusDays(15) -> "https://api.open-meteo.com/v1/forecast"
            else -> return@withContext null
        }
        val arr = JSONArray(Http.get(
            "$host?latitude=${s.joinToString(",") { "${it.first}" }}&longitude=${s.joinToString(",") { "${it.second}" }}" +
                "&hourly=weather_code,precipitation,cloud_cover,cape&start_date=$first&end_date=$last&timezone=GMT",
        ))
        val day0 = first.atStartOfDay(ZoneOffset.UTC).toInstant()
        val pts = s.mapIndexed { i, (la, lo, at) ->
            val h = arr.getJSONObject(i).getJSONObject("hourly")
            val k = Duration.between(day0, at.plusSeconds(1800)).toHours().toInt() // nearest hour
            fun num(key: String) = h.optJSONArray(key)?.opt(k) as? Number
            val code = num("weather_code")?.toInt() ?: return@withContext null // not in the archive yet
            RoutePt(la, lo, at, code, num("precipitation")?.toDouble() ?: 0.0, num("cloud_cover")?.toInt() ?: 0, num("cape")?.toDouble())
        }
        RouteWx(pts, Instant.now(clock))
    }
}
