package com.aria.air

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Weather at one point of the route at the time the plane is expected over it (Open-Meteo). [cape] J/kg, null if unknown. */
data class RoutePt(val lat: Double, val lon: Double, val at: Instant, val code: Int, val mm: Double, val cloud: Int, val cape: Double?)

/** Weather along the route, kept with the flight: what's left of the weather after radar imagery is gone. */
data class RouteWx(val pts: List<RoutePt>, val fetched: Instant)

enum class Sky(val word: String, val icon: String) { Clear("", ""), Rain("Rain", "🌧️"), Snow("Snow", "🌨️"), Storms("Storms", "⛈️") }

/** Rain, snow or storms worth a mention; drizzle and cloud are not. High CAPE with real rain counts as storms. */
fun RoutePt.sky(): Sky = when {
    code in 95..99 || (cape ?: 0.0) >= 1000 && mm >= 1 -> Sky.Storms
    code in 71..77 || code == 85 || code == 86 -> Sky.Snow
    code in 61..67 || code in 80..82 || mm >= 0.5 -> Sky.Rain
    else -> Sky.Clear
}

/** "Storms near Denver around 9:40 AM", or null when nothing along the route is worth saying. */
fun RouteWx.summary(zone: ZoneId): String? {
    val worst = pts.filter { it.sky() != Sky.Clear }.maxByOrNull { it.sky().ordinal } ?: return null
    val near = Db.airports.values.filter { it.rank <= 1 }.minByOrNull { distKm(it.lat, it.lon, worst.lat, worst.lon) }
        ?.takeIf { distKm(it.lat, it.lon, worst.lat, worst.lon) < 200 }
    return "${worst.sky().word} ${near?.let { "near ${it.city}" } ?: "en route"} around ${time(worst.at, near?.tz ?: zone)}"
}

fun RouteWx.toJson(): JSONObject = JSONObject().put("f", fetched.toEpochMilli()).put("p", JSONArray(pts.map {
    JSONArray().put(it.lat).put(it.lon).put(it.at.epochSecond).put(it.code).put(it.mm).put(it.cloud).put(it.cape ?: JSONObject.NULL)
}))

fun routeWxFrom(j: JSONObject): RouteWx {
    val p = j.getJSONArray("p")
    return RouteWx((0 until p.length()).map { i ->
        val a = p.getJSONArray(i)
        RoutePt(a.getDouble(0), a.getDouble(1), Instant.ofEpochSecond(a.getLong(2)), a.getInt(3), a.getDouble(4), a.getInt(5),
            if (a.isNull(6)) null else a.getDouble(6))
    }, Instant.ofEpochMilli(j.getLong("f")))
}

object RouteWeather {
    const val N = 8

    /** [N] points evenly along the great circle (the ends have takeoff/landing weather) and when the plane is over each. */
    fun samples(f: Flight): List<Triple<Double, Double, Instant>> {
        val o = f.origin ?: return emptyList(); val d = f.dest ?: return emptyList()
        val arc = greatCircle(o.lat, o.lon, d.lat, d.lon, N + 1)
        if (arc.size < N + 2) return emptyList()
        val dep = f.dep ?: f.depGuess
        val ms = Duration.between(dep, f.arr ?: f.arrGuess).toMillis()
        fun r2(v: Double) = Math.round(v * 100) / 100.0
        return (1..N).map { i ->
            val (la, lo) = arc[i]
            Triple(r2(la), r2((lo + 540) % 360 - 180), dep.plusMillis(ms * i / (N + 1)))
        }
    }

    /** One Open-Meteo request for all points: forecast up to ~16 days out and two months back, ERA5 archive before. */
    suspend fun fetch(f: Flight): RouteWx? = withContext(Dispatchers.IO) {
        val s = samples(f).ifEmpty { return@withContext null }
        val first = s.first().third.atZone(ZoneOffset.UTC).toLocalDate()
        val last = s.last().third.plusSeconds(1800).atZone(ZoneOffset.UTC).toLocalDate()
        val today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val host = when {
            first < today.minusDays(60) -> "https://archive-api.open-meteo.com/v1/archive"
            last <= today.plusDays(15) -> "https://api.open-meteo.com/v1/forecast"
            else -> return@withContext null
        }
        val arr = JSONArray(Api.get(
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

    /**
     * Fills or refreshes [f]'s route weather: on the takeoff weather's cadence while upcoming, then once more after
     * landing so the record is what happened rather than a forecast.
     */
    suspend fun refresh(f: Flight): Flight {
        if (f.cancelled || !Layers.weather) return f // off by default: nothing fetched unless the layer is on
        val now = Instant.now(clock)
        val arr = f.arr ?: f.arrGuess
        val w = f.routeWx
        val stale = w == null || w.fetched < arr &&
            (arr < now.minus(Duration.ofHours(1)) || Duration.between(w.fetched, now) > Api.wxEvery(now, f.dep ?: f.depGuess))
        if (!stale) return f
        return runCatching { Layers.loading(Layers.WEATHER) { fetch(f) } }.getOrNull()?.let { f.copy(routeWx = it) } ?: f
    }
}

/** Up to four soft dots on the arc where rain, snow or storms were or are expected; nothing when it's clear. */
fun DrawScope.drawRouteWx(w: RouteWx, refLon: Double, x: (Double) -> Float, y: (Double) -> Float) {
    w.pts.filter { it.sky() != Sky.Clear }.sortedByDescending { it.sky().ordinal }.take(4).forEach { p ->
        var lon = p.lon
        while (lon - refLon > 180) lon -= 360
        while (lon - refLon < -180) lon += 360
        val c = Offset(x(lon), y(p.lat))
        val color = when (p.sky()) { Sky.Storms -> Color(0xFFFF9F0A); Sky.Snow -> Color(0xFF8E9BFF); else -> Color(0xFF32ADE6) }
        drawCircle(Color.White.copy(alpha = 0.8f), 5.5.dp.toPx(), c)
        drawCircle(color.copy(alpha = 0.85f), 3.5.dp.toPx(), c)
    }
}

/** The weather icon for a point, like a forecast map: storms (with lightning when strongly convective), snow, rain, cloud, sun. */
fun RoutePt.icon(): String = when (sky()) {
    Sky.Storms -> if ((cape ?: 0.0) >= 1500 || code in 96..99) "⛈️" else "🌩️"
    Sky.Snow -> "🌨️"
    Sky.Rain -> if (mm >= 4) "🌧️" else "🌦️"
    Sky.Clear -> if (cloud >= 70) "☁️" else if (cloud >= 30) "⛅" else "☀️"
}

/** Every point's weather as an icon on a small round chip, for the full-screen map. */
fun DrawScope.drawRouteWxIcons(w: RouteWx, refLon: Double, x: (Double) -> Float, y: (Double) -> Float) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
    }
    w.pts.forEach { p ->
        var lon = p.lon
        while (lon - refLon > 180) lon -= 360
        while (lon - refLon < -180) lon += 360
        val c = Offset(x(lon), y(p.lat) - 16.dp.toPx()) // just above the line
        drawCircle(Color.White.copy(alpha = 0.85f), 12.dp.toPx(), c)
        drawIntoCanvas { it.nativeCanvas.drawText(p.icon(), c.x, c.y + paint.textSize * 0.35f, paint) }
    }
}

/** A quiet line under the schedule: the worst weather along the route, or that it's clear. */
@Composable
fun RouteWxLine(f: Flight) {
    val w = f.routeWx ?: return
    val s = w.summary(f.fromTz) ?: "Clear along the route"
    val icon = w.pts.maxOf { it.sky() }.icon.ifEmpty { "☀️" }
    Text(
        "$icon $s", fontSize = 13.sp, color = C.secondary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
    )
}
