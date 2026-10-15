package com.aria.air.ui.screens

// An airport's indoor map from OpenStreetMap: floors, gates, food, restrooms and shops, with your gate marked.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.ui.components.*
import com.aria.air.ui.map.*
import com.aria.air.util.*
import android.content.Intent
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val M_PER_DEG = 111_320.0

/** Where the map looks: the center in meters east/south of the airport's reference point, and pixels per meter. */
private data class View(val x: Double, val y: Double, val scale: Double)

/** Lat/lon to meters east ([x]) and south ([y]) of the airport, flat-earth (fine across an airport). */
private class Proj(private val lat0: Double, private val lon0: Double) {
    private val kx = cos(Math.toRadians(lat0)) * M_PER_DEG
    fun x(p: Pt) = (p.lon - lon0) * kx
    fun y(p: Pt) = (lat0 - p.lat) * M_PER_DEG
}

/** "1", "-1", "1.5" for a floor. */
private fun levelName(l: Double) = if (l % 1.0 == 0.0) l.toInt().toString() else l.toString()

/**
 * An airport's indoor map: terminals and rooms on the chosen floor, gates, and points of interest as icons. Pinch to
 * zoom, drag to pan, double-tap to fit; tap an icon or gate for its details. [gate] (the flight's) is marked and the map
 * opens on it. Data from OpenStreetMap, downloaded once and saved for offline use.
 */
@Composable
fun AirportMapScreen(code: String, gate: String) {
    val ctx = LocalContext.current
    val a = Db.airport(code)
    // Busy servers are retried a few times while the screen is open; once saved, the map opens instantly offline.
    LaunchedEffect(code) {
        repeat(4) {
            if (IndoorMaps.get(code) != null) return@LaunchedEffect
            delay(20_000)
        }
    }
    val map = IndoorMaps.maps[code]
    val busy = IndoorMaps.busy[code] == true
    var level by remember(map) { mutableStateOf(map?.mainLevel) }
    var filter by remember { mutableStateOf<Place?>(null) }
    var picked by remember { mutableStateOf<Feature?>(null) }
    val exact = remember(map, gate) { map?.gate(gate) }
    val target = exact ?: remember(map, gate) { map?.nearGate(gate) }
    var view by remember { mutableStateOf<View?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(C.sea).onSizeChanged { size = it }) {
        if (map != null && a != null && size.width > 0) {
            val proj = remember(map) { Proj(a.lat, a.lon) }
            val fit = remember(map, size) { fitView(map, proj, size) }
            // Opens on your gate, close enough to read the gate numbers around it; otherwise the whole airport.
            val start = remember(map, size, target) {
                target?.let { View(proj.x(it.at), proj.y(it.at), max(fit.scale, size.width / 450.0)) } ?: fit
            }
            val v = view ?: start
            IndoorCanvas(map, proj, v, level, filter, target, picked)
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(map) {
                        detectTapGestures(
                            onDoubleTap = { view = null },
                            onTap = { p -> picked = hit(map, proj, view ?: start, size, p, level, filter, with(density) { 28.dp.toPx() }) },
                        )
                    }
                    .pointerInput(map) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val c = view ?: start
                            val s = (c.scale * zoom).coerceIn(fit.scale * 0.5, fit.scale * 400)
                            val w = size.width / 2f; val h = size.height / 2f
                            // Keep the point under the fingers in place while zooming, then follow the drag.
                            view = View(
                                c.x + (centroid.x - w) / c.scale - (centroid.x - w) / s - pan.x / s,
                                c.y + (centroid.y - h) / c.scale - (centroid.y - h) / s - pan.y / s, s,
                            )
                        }
                    },
            )
            if (map.levels.size > 1) LevelPicker(
                map.levels, level, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 68.dp, end = 12.dp),
            ) { level = it; picked = null }
            if (map.features.none { it.kind >= Place.ROOM }) Notice(
                Icons.Filled.Map, "Only the outlines so far",
                "OpenStreetMap volunteers haven't mapped the inside of $code yet.", Modifier.align(Alignment.Center),
            )
        } else if (busy || IndoorMaps.errors[code] == null) {
            Notice(null, "Getting the $code map", "From OpenStreetMap, once; then it opens offline.", Modifier.align(Alignment.Center))
        } else {
            Notice(
                Icons.Filled.CloudOff, "Couldn't get the map",
                if (IndoorMaps.errors[code]?.contains("busy") == true) "OpenStreetMap's servers are busy. Try again in a minute."
                else "No connection. Once downloaded, the map works offline.",
                Modifier.align(Alignment.Center), action = "Try again",
            ) { App.scope.launch { IndoorMaps.get(code, force = true) } }
        }

        // Top: back, which airport and floor (and your gate), and open in another maps app.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MapButton(Icons.Filled.ChevronLeft, "Back") { Nav.pop() }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { Column(
                Modifier.shadow(3.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                    .background(C.card).padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(a?.name ?: code, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    code,
                    level?.takeIf { (map?.levels?.size ?: 0) > 1 }?.let { "Level ${levelName(it)}" },
                    gate.takeIf { it.isNotEmpty() }?.let {
                        when {
                            exact != null -> "Gate $it"
                            target != null -> "Gate $it not mapped, near ${target.ref}"
                            else -> "Gate $it not mapped"
                        }
                    },
                ).joinToString(" · ")
                Text(sub, fontSize = 12.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            Spacer(Modifier.width(10.dp))
            MapButton(Icons.AutoMirrored.Filled.OpenInNew, "Open in a maps app") {
                val p = picked?.at ?: target?.at ?: a?.let { Pt(it.lat, it.lon) } ?: return@MapButton
                val label = picked?.let { it.name.ifEmpty { it.kind.label } } ?: target?.let { "Gate ${it.ref}" } ?: code
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${p.lat},${p.lon}(${Uri.encode(label)})".toUri())) }
            }
        }

        // Bottom: what you tapped, the filters, then the credit OpenStreetMap's license asks for, with a refresh.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            picked?.let { PickedCard(it, target?.takeIf { t -> t != it }, Modifier.padding(horizontal = 12.dp)) { picked = null } }
            if (map != null) Filters(map, level, filter) { filter = if (filter == it) null else it; picked = null }
            Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "© OpenStreetMap", fontSize = 11.sp, color = C.secondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.card.copy(alpha = 0.8f))
                        .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://www.openstreetmap.org/copyright".toUri())) } }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (map != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (busy) "Updating…" else "Updated ${ago(map.at)} · Refresh", fontSize = 11.sp, color = if (busy) C.secondary else C.blue,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.card.copy(alpha = 0.8f))
                            .clickable(enabled = !busy) { App.scope.launch { IndoorMaps.get(code, force = true) } }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** A centered card for loading, errors and empty maps: an icon (a spinner when null), a title, a line, and a button. */
@Composable
private fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, text: String, modifier: Modifier,
    action: String? = null, onAction: () -> Unit = {},
) = Column(
    modifier.padding(32.dp).widthIn(max = 320.dp).shadow(4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
        .background(C.card).padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    if (icon == null) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = C.blue)
    else Icon(icon, null, tint = C.secondary, modifier = Modifier.size(36.dp))
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.label, modifier = Modifier.padding(top = 12.dp))
    Text(text, fontSize = 14.sp, color = C.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    if (action != null) Button(action, Modifier.padding(top = 16.dp).fillMaxWidth(), onClick = onAction)
}

/** The view that shows all the terminals (or everything mapped, when no terminal is). */
private fun fitView(map: IndoorMap, proj: Proj, size: IntSize): View {
    val pts = (map.features.filter { it.kind == Place.TERMINAL }.ifEmpty { map.features }).flatMap { it.rings.flatten() }
    if (pts.isEmpty()) return View(0.0, 0.0, 1.0)
    val xs = pts.map(proj::x); val ys = pts.map(proj::y)
    val w = max(xs.max() - xs.min(), 50.0); val h = max(ys.max() - ys.min(), 50.0)
    return View((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, min(size.width / w, size.height / h) * 0.85)
}

/** The point of interest or gate nearest a tap, within [radius] pixels; null when nothing is close. */
private fun hit(map: IndoorMap, proj: Proj, v: View, size: IntSize, p: Offset, level: Double?, filter: Place?, radius: Float): Feature? =
    map.features.asSequence()
        .filter { !it.kind.area && it.on(level) && (filter == null || it.kind == filter || it.kind == Place.GATE) }
        .map { f -> f to hypot((size.width / 2 + (proj.x(f.at) - v.x) * v.scale) - p.x, (size.height / 2 + (proj.y(f.at) - v.y) * v.scale) - p.y) }
        .filter { it.second < radius }.minByOrNull { it.second }?.first

/** The map itself: floor plan shapes in map space, then icons, gate numbers and labels in screen space. */
@Composable
private fun IndoorCanvas(map: IndoorMap, proj: Proj, v: View, level: Double?, filter: Place?, target: Feature?, picked: Feature?) {
    val visible = remember(map, level) { map.features.filter { it.on(level) } }
    // Shapes in meters, built once per floor; the canvas transform moves and scales them.
    val shapes = remember(visible) {
        visible.filter { it.rings.first().size >= 4 }.sortedBy { it.kind.ordinal.coerceAtMost(Place.GATE.ordinal) }.map { f ->
            f to Path().apply {
                fillType = PathFillType.EvenOdd
                f.rings.forEach { r ->
                    r.forEachIndexed { i, p -> if (i == 0) moveTo(proj.x(p).toFloat(), proj.y(p).toFloat()) else lineTo(proj.x(p).toFloat(), proj.y(p).toFloat()) }
                    close()
                }
            }
        }
    }
    val pois = remember(visible) { visible.filter { !it.kind.area && it.kind != Place.GATE } }
    val gates = remember(map) { map.features.filter { it.kind == Place.GATE } }
    val colors = PlaceColors()
    Canvas(Modifier.fillMaxSize().background(colors.ground)) {
        val s = v.scale.toFloat()
        val dx = (size.width / 2 - v.x * v.scale).toFloat(); val dy = (size.height / 2 - v.y * v.scale).toFloat()
        fun sx(f: Feature) = (dx + proj.x(f.at) * s).toFloat()
        fun sy(f: Feature) = (dy + proj.y(f.at) * s).toFloat()
        withTransform({ translate(dx, dy); scale(s, s, Offset.Zero) }) {
            val line = Stroke(width = 1.dp.toPx() / s)
            for ((f, path) in shapes) {
                val fill = colors.fill(f.kind, f == picked)
                if (fill != Color.Transparent) drawPath(path, fill)
                if (f.kind != Place.APRON) drawPath(path, colors.outline, style = line)
            }
        }
        val onScreen = { x: Float, y: Float -> x > -50 && y > -50 && x < size.width + 50 && y < size.height + 50 }
        // Greedy decluttering: an icon or label is skipped when it would overlap one already drawn.
        val taken = mutableListOf<android.graphics.RectF>()
        fun free(r: android.graphics.RectF) = taken.none { android.graphics.RectF.intersects(it, r) }.also { if (it) taken += r }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val zoomedIn = s > size.width / 900f // under ~900 m across: room for names and gate numbers
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            // Your gate first, so nothing hides it.
            target?.let { t ->
                val x = sx(t); val y = sy(t); val r = 15.dp.toPx()
                paint.color = colors.blue; c.drawCircle(x, y, r, paint)
                paint.color = android.graphics.Color.WHITE; paint.textSize = 11.dp.toPx(); paint.typeface = Typeface.DEFAULT_BOLD
                c.drawText(t.ref.ifEmpty { t.name }.take(4), x, y + paint.textSize * 0.36f, paint)
                taken += android.graphics.RectF(x - r, y - r, x + r, y + r)
            }
            // Zoomed out, only the places people look for; elevators, stairs and the like join once zoomed in.
            val minor = setOf(Place.ELEVATOR, Place.STAIRS, Place.MONEY, Place.INFO, Place.CHARGING, Place.WATER)
            val shown = pois.filter { if (filter != null) it.kind == filter else zoomedIn || it.kind !in minor }
                .sortedBy { (if (it.kind in minor) 2 else 0) + (if (it.name.isEmpty()) 1 else 0) } // food and shops win space
            for (f in shown) {
                val x = sx(f); val y = sy(f)
                val r = (if (filter != null) 13 else if (f.kind in minor) 8 else 11).dp.toPx()
                if (!onScreen(x, y) || !free(android.graphics.RectF(x - r, y - r, x + r, y + r))) continue
                paint.color = if (f == picked) colors.blue else colors.chip
                c.drawCircle(x, y, r, paint)
                paint.textSize = r * 1.15f; paint.typeface = Typeface.DEFAULT
                c.drawText(f.kind.icon, x, y + paint.textSize * 0.36f, paint)
                if (zoomedIn && f.name.isNotEmpty()) {
                    paint.textSize = 11.dp.toPx(); paint.typeface = Typeface.DEFAULT
                    val w = paint.measureText(f.name) / 2
                    val box = android.graphics.RectF(x - w, y + r, x + w, y + r + paint.textSize * 1.3f)
                    if (free(box)) {
                        // A halo in the ground color keeps names readable over walls and rooms.
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.dp.toPx(); paint.color = colors.halo
                        c.drawText(f.name, x, y + r + paint.textSize, paint)
                        paint.style = Paint.Style.FILL; paint.color = colors.ink
                        c.drawText(f.name, x, y + r + paint.textSize, paint)
                    }
                }
            }
            if (zoomedIn && filter == null) {
                paint.textSize = 10.dp.toPx(); paint.typeface = Typeface.DEFAULT_BOLD
                for (g in gates) {
                    if (g == target) continue
                    val x = sx(g); val y = sy(g); val label = g.ref.ifEmpty { g.name }.take(5)
                    if (label.isEmpty() || !onScreen(x, y)) continue
                    val w = paint.measureText(label) / 2 + 4.dp.toPx(); val h = paint.textSize * 0.8f
                    if (!free(android.graphics.RectF(x - w, y - h, x + w, y + h))) continue
                    paint.color = if (g == picked) colors.blue else colors.gate
                    c.drawRoundRect(x - w, y - h, x + w, y + h, h, h, paint)
                    paint.color = android.graphics.Color.WHITE
                    c.drawText(label, x, y + paint.textSize * 0.36f, paint)
                }
            }
        }
    }
}

/** Fills for each kind of place, from the app palette so dark mode works. */
private class PlaceColors {
    val outline = C.separator
    val blue = C.blue.toArgb()
    val chip = C.card.toArgb()
    val ink = C.label.toArgb()
    val gate = C.secondary.copy(alpha = 0.85f).toArgb()
    /** Behind everything (and the halo around labels): darker than the terminals in both modes. */
    val ground = if (C.dark) Color(0xFF0E0E10) else C.sea
    val halo = ground.toArgb()
    private val apron = if (C.dark) Color(0xFF1A1A1D) else C.fill
    private val terminal = if (C.dark) Color(0xFF2C2C2E) else C.card
    private val area = if (C.dark) Color(0xFF35353A) else C.blue.copy(alpha = 0.06f)
    private val room = if (C.dark) Color(0xFF45454B) else C.land
    private val food = C.orange.copy(alpha = 0.28f)
    private val shop = C.indigo.copy(alpha = 0.22f)
    private val rest = C.blue.copy(alpha = 0.22f)
    private val lounge = C.green.copy(alpha = 0.25f)
    private val picked = C.blue.copy(alpha = 0.4f)

    fun fill(k: Place, isPicked: Boolean) = if (isPicked) picked else when (k) {
        Place.APRON -> apron
        Place.TERMINAL -> terminal
        Place.AREA -> area
        Place.FOOD, Place.COFFEE, Place.BAR -> food
        Place.SHOP -> shop
        Place.TOILETS, Place.WATER -> rest
        Place.LOUNGE -> lounge
        else -> room
    }
}

/** The floor buttons down the right side, top floor first. */
@Composable
private fun LevelPicker(levels: List<Double>, level: Double?, modifier: Modifier, onPick: (Double) -> Unit) = Column(
    modifier.shadow(3.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(C.card).padding(3.dp),
    verticalArrangement = Arrangement.spacedBy(1.dp),
) {
    levels.reversed().forEach { l ->
        val on = l == level
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (on) C.blue else Color.Transparent).clickable { onPick(l) },
            contentAlignment = Alignment.Center,
        ) { Text(levelName(l), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else C.label) }
    }
}

/** Chips for the kinds of places on this floor ("🚻 Restrooms 4"); one at a time shows only that kind. */
@Composable
private fun Filters(map: IndoorMap, level: Double?, on: Place?, onPick: (Place) -> Unit) {
    val counts = remember(map, level) {
        map.features.filter { !it.kind.area && it.kind != Place.GATE && it.on(level) }.groupingBy { it.kind }.eachCount()
            .entries.sortedBy { it.key.ordinal }
    }
    if (counts.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(6.dp))
        counts.forEach { (k, n) ->
            val sel = k == on
            Row(
                Modifier.shadow(2.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(if (sel) C.blue else C.card)
                    .clickable { onPick(k) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k.icon, fontSize = 13.sp)
                Text(" ${k.label}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (sel) Color.White else C.label)
                Text(" $n", fontSize = 13.sp, color = if (sel) Color.White.copy(alpha = 0.8f) else C.secondary)
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

/** What you tapped: its name, kind, floors, opening hours, and how far it is from your gate ([from]). */
@Composable
private fun PickedCard(f: Feature, from: Feature?, modifier: Modifier, onClose: () -> Unit) = Row(
    modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.card)
        .clickable(onClick = onClose).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (f.kind.icon.isNotEmpty()) Text(f.kind.icon, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
    Column(Modifier.weight(1f)) {
        Text(
            if (f.kind == Place.GATE) "Gate ${f.ref.ifEmpty { f.name }}" else f.name.ifEmpty { f.kind.label },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.label,
        )
        val sub = listOfNotNull(
            f.kind.label.takeIf { f.name.isNotEmpty() && f.kind != Place.GATE },
            f.levels.takeIf { it.isNotEmpty() }?.let { l -> "Level " + l.joinToString(", ") { levelName(it) } },
            f.hours.takeIf { it.isNotEmpty() },
            // ponytail: straight line at walking pace; corridors make the real walk longer.
            from?.let { g ->
                val m = distKm(g.at.lat, g.at.lon, f.at.lat, f.at.lon) * 1000
                "≈ ${(m / 10).roundToInt() * 10} m from ${g.ref.ifEmpty { "your gate" }} · ${max(1, (m / 80).roundToInt())} min walk"
            },
        ).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = C.secondary)
    }
}
