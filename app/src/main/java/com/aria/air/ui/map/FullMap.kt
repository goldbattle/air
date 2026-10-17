package com.aria.air.ui.map

// The full-screen, zoomable flight map with its layers button and freshness footer.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.sync.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/** The layers button in a corner of the flight map (same as the map's other buttons), with its menu of toggles. */
@Composable
fun LayersButton(modifier: Modifier = Modifier) = Box(modifier) {
    var open by remember { mutableStateOf(false) }
    MapButton(Icons.Filled.Layers, "Map layers") { open = true }
    DropdownMenu(open, { open = false }, containerColor = C.card) {
        for ((label, key) in listOf(
            "Radar" to Layers.RADAR, "Forecast radar (US)" to Layers.FORECAST,
            "Route weather" to Layers.WEATHER, "Past routes" to Layers.ROUTES,
        )) {
            val on = Layers.on(key)
            DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Still downloading in the background: a small spinner beside the switch.
                        if ((Layers.busy[key] ?: 0) > 0) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.secondary)
                            Spacer(Modifier.width(10.dp))
                        }
                        Switch(on, null, Modifier.size(width = 44.dp, height = 24.dp))
                    }
                },
                onClick = { Prefs.setFlag(key, !on) },
            )
        }
    }
}

/**
 * The route map full screen: pinch to zoom and drag to pan (double-tap to fit the route again), the same layers and
 * the layers button. Zooming enlarges the radar picture already fetched (no new requests); weather along the route
 * shows as icons. Opened by tapping the flight page's map.
 */
@Composable
fun FullMap(f: Flight, pulls: Int, onClose: () -> Unit) {
    val o = f.origin ?: return; val d = f.dest ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val radar = rememberWeatherMap(f, pulls)
    val wx = f.routeWx?.takeIf { Layers.weather }
    val arc = remember(o, d) { greatCircle(o.lat, o.lon, d.lat, d.lon) }
    val pill = C.card.copy(alpha = 0.9f).toArgb(); val ink = C.label.toArgb()
    var camera by remember { mutableStateOf<Camera?>(null) }
    val fit = remember { arrayOfNulls<Camera>(1) } // what the map fits to, reported while drawing
    var size by remember { mutableStateOf(IntSize.Zero) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(C.sea).onSizeChanged { size = it }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { camera = null }) }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val c = camera ?: fit[0] ?: return@detectTransformGestures
                        val base = fit[0]?.scale ?: c.scale
                        val s = (c.scale * zoom).coerceIn(base * 0.6, base * 60)
                        val w = size.width / 2f; val h = size.height / 2f
                        // Keep the point under the fingers in place while zooming, then follow the drag.
                        val lon = c.lon + (centroid.x - w) / (c.kx * c.scale) - (centroid.x - w) / (c.kx * s) - pan.x / (c.kx * s)
                        val lat = c.lat - (centroid.y - h) / c.scale + (centroid.y - h) / s + pan.y / s
                        camera = Camera(lon, lat.coerceIn(-80.0, 80.0), s, c.kx)
                    }
                },
        ) {
            WorldMap(
                listOf(MapRoute(o, d, highlight = true)), Modifier.fillMaxSize(), focus = true, line = C.of(f), dot = C.of(f), plane = f.plane(), paths = f.mapPaths(),
                camera = camera, onFit = { fit[0] = it },
                under = radar?.let { r -> { x, y -> drawWeatherMap(r, x, y, 0.65f) } },
                over = { x, y ->
                    wx?.let { drawRouteWxIcons(it, o.lon, x, y) }
                    // Airport codes as small pills beside the ends, on the outside of the line.
                    val leftFirst = x(arc.first().second) <= x(arc.last().second)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 13.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    for ((code, end, left) in listOf(Triple(f.from, arc.first(), leftFirst), Triple(f.to, arc.last(), !leftFirst))) {
                        val pw = paint.measureText(code) + 14.dp.toPx(); val ph = 22.dp.toPx(); val gap = 12.dp.toPx()
                        val cx = x(end.second); val cy = y(end.first)
                        val l = if (left) cx - gap - pw else cx + gap
                        drawIntoCanvas { c ->
                            paint.color = pill
                            c.nativeCanvas.drawRoundRect(l, cy - ph / 2, l + pw, cy + ph / 2, 6.dp.toPx(), 6.dp.toPx(), paint)
                            paint.color = ink
                            c.nativeCanvas.drawText(code, l + 7.dp.toPx(), cy + paint.textSize * 0.36f, paint)
                        }
                    }
                },
            )
            MapFooter(f, radar, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(12.dp))
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Debug.on) {
                    MapButton(Icons.Filled.BugReport, "Copy map debug info") {
                        Debug.copy(ctx, Debug.flightReport(f) + "\n" + mapReport(f, radar, camera, fit[0], size), "Map debug info")
                    }
                    Spacer(Modifier.width(10.dp))
                }
                LayersButton()
                Spacer(Modifier.width(10.dp))
                MapButton(Icons.Filled.Close, "Close", onClose)
            }
        }
    }
}

/**
 * What each thing on the full-screen map shows and how fresh it is: the moment (with its day; it can be past or
 * future) and when it was fetched. One quiet line per layer that's showing.
 */
@Composable
private fun MapFooter(f: Flight, radar: RadarImage?, modifier: Modifier) {
    val z = ZoneId.systemDefault()
    fun at(t: Instant) = dayLabel(t.atZone(z).toLocalDate()) + " " + time(t, z)
    val lines = buildList {
        radar?.let { r ->
            val what = when (r.box) {
                NasaGibs.WORLD -> "Rain (NASA IMERG)"
                NoaaHrrr.BOX -> "Forecast radar (NOAA HRRR)"
                else -> "Radar (RainViewer)"
            }
            add("$what: ${at(r.frame)} · fetched ${ago(r.fetched)}")
        }
        if (Layers.weather) f.routeWx?.takeIf { it.pts.isNotEmpty() }?.let { w ->
            add("Route weather: ${at(w.pts.first().at)} – ${time(w.pts.last().at, z)} · fetched ${ago(w.fetched)}")
        }
        if (Layers.routes) PastRoutes.cache[f.routeKey]?.let { c ->
            val days = c.legs.mapNotNull { it.firstOrNull()?.t }.map { Instant.ofEpochSecond(it).atZone(z).toLocalDate() }
            val span = if (days.isEmpty()) "none yet" else "${days.size} flights, ${dayLabel(days.min())} – ${dayLabel(days.max())}"
            add("Past routes: $span · fetched ${ago(c.at)}")
        }
        f.updated?.let { add("Flight status · fetched ${ago(it)}") }
        f.plane()?.let { add((if (it.estimated) "Position: estimated, last signal " else "Position: ") + "${at(it.at)} · ${ago(it.at)}") }
    }
    if (lines.isEmpty()) return
    Text(
        lines.joinToString("\n"), fontSize = 11.sp, lineHeight = 15.sp, color = C.secondary,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(C.card.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A round map control, the same as the flight page's back and share buttons. */
@Composable
fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(C.card.copy(alpha = 0.92f)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, label, tint = C.label, modifier = Modifier.size(22.dp)) }

/** What the full-screen map is showing, for the debug report. */
private fun mapReport(f: Flight, radar: RadarImage?, camera: Camera?, fit: Camera?, size: IntSize) = buildString {
    val now = Instant.now(clock)
    appendLine("== map  ${size.width}x${size.height}  camera ${camera ?: "fit"}  fit $fit")
    appendLine("layers radar=${Layers.radar} weather=${Layers.weather} routes=${Layers.routes} busy=${Layers.busy}")
    appendLine("live radar relevant=${RainViewer.relevant(f, now)}  rain map relevant=${NasaGibs.relevant(f, now)} slot ${NasaGibs.keyFor(f)}")
    appendLine("radar ${radar?.let { "frame ${it.frame} fetched ${it.fetched} box ${it.box} ${it.bitmap.width}x${it.bitmap.height}" } ?: "none"}")
    appendLine("route weather ${f.routeWx?.let { w -> "fetched ${w.fetched}: " + w.pts.joinToString { "${it.sky()}/${it.code}/${it.mm}mm/cape ${it.cape}" } } ?: "none"}")
    val p = f.mapPaths()
    appendLine("paths: past ${p.typical.size} (${p.typical.joinToString { "${it.size} pts" }}), flown ${p.flown.size} pts, guessed ${p.guess.size} pts, arc ${p.arc}, cached ${PastRoutes.cache[f.routeKey]?.let { "${it.legs.size} at ${it.at}" } ?: "none"}")
}
