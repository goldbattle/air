package com.aria.air.util

// Geography on a sphere: distances, great circles, longitude unwrapping, and the small point/box types.

import com.aria.air.data.*
import androidx.compose.ui.unit.dp
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_KM = 6371.0
const val KM_PER_MI = 1.609344

fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
    return 2 * EARTH_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
/** Points along the great circle from a to b as (lat, lon), longitudes unwrapped so the line is continuous. */
fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 64): List<Pair<Double, Double>> {
    val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
    val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
    val d = distKm(lat1, lon1, lat2, lon2) / EARTH_KM
    if (d < 1e-6) return listOf(lat1 to lon1)
    val out = ArrayList<Pair<Double, Double>>(n + 1)
    var prevLon = lon1
    for (i in 0..n) {
        val f = i.toDouble() / n
        val a = sin((1 - f) * d) / sin(d); val b = sin(f * d) / sin(d)
        val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
        val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
        val z = a * sin(p1) + b * sin(p2)
        var lon = Math.toDegrees(atan2(y, x))
        while (lon - prevLon > 180) lon -= 360
        while (lon - prevLon < -180) lon += 360
        prevLon = lon
        out += Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to lon
    }
    return out
}
/** One breadcrumb: position, altitude and time (epoch seconds). */
data class Pt(val lat: Double, val lon: Double, val altFt: Int = 0, val t: Long = 0)
/** [pts] as (lat, lon) with longitudes unwrapped to stay continuous, starting near [ref]. */
fun unwrap(pts: List<Pt>, ref: Double): List<Pair<Double, Double>> {
    var prev = ref
    return pts.map { p ->
        var lon = p.lon
        while (lon - prev > 180) lon -= 360
        while (lon - prev < -180) lon += 360
        prev = lon
        p.lat to lon
    }
}
/** Lon/lat rectangle; longitudes unwrapped like [greatCircle]'s, so they can run past ±180. */
data class GeoBox(val lonMin: Double, val lonMax: Double, val latMin: Double, val latMax: Double)
