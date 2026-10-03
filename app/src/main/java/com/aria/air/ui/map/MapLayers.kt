package com.aria.air.ui.map

// What the map draws over the land: radar pictures and route weather, and the state that refreshes them.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Radar for the flight page's map, refreshed every [RainViewer.EVERY] while the page is open. */
@Composable
fun rememberRadar(f: Flight): RadarImage? {
    var r by remember(f.id) { mutableStateOf<RadarImage?>(null) }
    val on = Layers.radar
    LaunchedEffect(f.id, on) {
        while (true) {
            r = Store.get(f.id)?.let { RainViewer.get(it) }
            delay(RainViewer.EVERY.toMillis())
        }
    }
    return r.takeIf { on }
}

/** Draws [r] soft and translucent, stretched over its box in the map's coordinates. */
fun DrawScope.drawRadar(r: RadarImage, x: (Double) -> Float, y: (Double) -> Float, alpha: Float = 0.5f) {
    val l = x(r.box.lonMin); val t = y(r.box.latMax)
    drawImage(
        r.image, srcSize = IntSize(r.image.width, r.image.height), dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
        dstSize = IntSize((x(r.box.lonMax) - l).roundToInt(), (y(r.box.latMin) - t).roundToInt()), alpha = alpha,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
    )
}

/** The same onto a widget's canvas. */
fun drawRadar(c: android.graphics.Canvas, r: RadarImage, x: (Double) -> Float, y: (Double) -> Float, alpha: Int = 110) {
    c.drawBitmap(
        r.bitmap, null, android.graphics.RectF(x(r.box.lonMin), y(r.box.latMax), x(r.box.lonMax), y(r.box.latMin)),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha },
    )
}

/**
 * The precipitation under a flight's map: live radar from a few hours before departure to landing, refreshed while
 * shown; afterwards the rain as it was during the flight. [force] changes on pull-to-refresh to fetch what's missing.
 */
@Composable
fun rememberWeatherMap(f: Flight, force: Int = 0): RadarImage? {
    val live = rememberRadar(f)
    val forecast = rememberForecastRadar(f) // before takeoff, US routes, its own layer (off by default)
    var past by remember(f.id) { mutableStateOf(NasaGibs.keyFor(f).let { NasaGibs.cached(it) }) }
    LaunchedEffect(f.id, force, f.arrActual) {
        if (NasaGibs.relevant(f, Instant.now(clock))) past = NasaGibs.get(NasaGibs.keyFor(f))
    }
    return past?.takeIf { Layers.radar && NasaGibs.relevant(f, Instant.now(clock)) } ?: forecast ?: live
}

/** [r] drawn once per world copy, so a world-wide picture also covers routes past ±180°. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWeatherMap(r: RadarImage, x: (Double) -> Float, y: (Double) -> Float, alpha: Float) {
    if (r.box != NasaGibs.WORLD) return drawRadar(r, x, y, alpha)
    for (shift in listOf(-360.0, 0.0, 360.0)) drawRadar(r, { x(it + shift) }, y, alpha)
}

/** The forecast picture for [f]'s takeoff while the Forecast radar layer is on, refreshed hourly while shown. */
@Composable
fun rememberForecastRadar(f: Flight): RadarImage? {
    var r by remember(f.id) { mutableStateOf<RadarImage?>(null) }
    val on = Layers.forecast
    LaunchedEffect(f.id, on) {
        if (!on) return@LaunchedEffect
        while (true) {
            r = Store.get(f.id)?.let { NoaaHrrr.get(it) }
            delay(NoaaHrrr.EVERY.toMillis())
        }
    }
    return r.takeIf { on && NoaaHrrr.relevant(f, Instant.now(clock)) }
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
