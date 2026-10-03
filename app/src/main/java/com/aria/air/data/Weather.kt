package com.aria.air.data

// Weather we keep with a flight: at takeoff and landing, and along the route.

import com.aria.air.importer.*
import com.aria.air.util.*
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Weather at an airport around takeoff or landing (Open-Meteo), with when it was fetched. */
data class Wx(val tempC: Double, val code: Int, val windKmh: Double, val at: Instant, val fetched: Instant)

/** WMO weather code as an emoji and a word. */
fun Wx.icon() = when (code) {
    0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
    in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"
    else -> "🌡️"
}

fun Wx.desc() = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
    in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 80..82 -> "Showers"; in 71..77 -> "Snow"; 85, 86 -> "Snow showers"
    in 95..99 -> "Thunderstorms"; else -> "—"
}

/** "64°F" (or °C with Settings › Data › Celsius). */
fun Wx.temp() = if (Prefs.flag("celsius", false)) "${Math.round(tempC)}°C" else "${Math.round(tempC * 9 / 5 + 32)}°F"
fun Wx.wind() = if (Prefs.flag("celsius", false)) "${Math.round(windKmh)} km/h" else "${Math.round(windKmh / KM_PER_MI)} mph"
fun Wx.short() = "${icon()} ${temp()}"
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
/** The weather icon for a point, like a forecast map: storms (with lightning when strongly convective), snow, rain, cloud, sun. */
fun RoutePt.icon(): String = when (sky()) {
    Sky.Storms -> if ((cape ?: 0.0) >= 1500 || code in 96..99) "⛈️" else "🌩️"
    Sky.Snow -> "🌨️"
    Sky.Rain -> if (mm >= 4) "🌧️" else "🌦️"
    Sky.Clear -> if (cloud >= 70) "☁️" else if (cloud >= 30) "⛅" else "☀️"
}
