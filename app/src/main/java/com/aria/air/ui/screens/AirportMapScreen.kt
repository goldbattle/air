package com.aria.air.ui.screens

// An airport's indoor map from OpenStreetMap: floors, gates, food, restrooms and shops, with your gate marked.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.ui.components.*
import com.aria.air.ui.map.*
import com.aria.air.util.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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

/** An airport name short enough for the header: "San Francisco International Airport" -> "San Francisco Intl". */
internal fun shortName(name: String) = name.replace("International", "Intl").replace("Municipal", "Muni")
    .removeSuffix(" Airport").trim()

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
    // You are here: asked for the first time you tap the locate button, then shown whenever the map is open.
    fun granted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    var follow by remember { mutableStateOf(false) } // center on the next fix
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = granted()
        if (allowed) follow = true
        else Toast.makeText(ctx, "Location is off for Air. Turn it on in Settings to see where you are.", Toast.LENGTH_LONG).show()
    }
    val fix = rememberHere(allowed)
    // Only near the airport: from farther away the dot would just sit off the map.
    val here = fix?.takeIf { a != null && distKm(a.lat, a.lon, it.latitude, it.longitude) < NEAR_KM }

    Box(Modifier.fillMaxSize().background(C.sea).onSizeChanged { size = it }) {
        if (map != null && a != null && size.width > 0) {
            val proj = remember(map) { Proj(a.lat, a.lon) }
            val fit = remember(map, size) { fitView(map, proj, size) }
            // Opens on your gate, close enough to read the gate numbers around it; otherwise the whole airport.
            val start = remember(map, size, target) {
                target?.let { View(proj.x(it.at), proj.y(it.at), max(fit.scale, size.width / 450.0)) } ?: fit
            }
            val v = view ?: start
            LaunchedEffect(follow, fix) {
                if (!follow || fix == null) return@LaunchedEffect
                // A rough first fix (a cell tower miles off) is often followed by a good one: wait a moment for it.
                if (here == null) delay(5_000)
                follow = false
                if (here == null) Toast.makeText(ctx, "You're ${fmtInt((distKm(a.lat, a.lon, fix.latitude, fix.longitude) / KM_PER_MI).roundToInt())} mi from $code", Toast.LENGTH_SHORT).show()
                else view = View(proj.x(Pt(here.latitude, here.longitude)), proj.y(Pt(here.latitude, here.longitude)), max(v.scale, size.width / 300.0))
            }
            IndoorCanvas(map, proj, v, level, filter, target, picked, here)
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
            // Right side, under the header: refresh, layers (what to show), the floor, and where you are.
            var floors by remember { mutableStateOf(false) }
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 68.dp, end = 12.dp)) {
                if (floors) LevelPicker(map.levels, level, Modifier.padding(end = 10.dp)) { level = it; picked = null; floors = false }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RefreshButton(busy) { App.scope.launch { IndoorMaps.get(code, force = true) } }
                    PlacesButton(map, level, filter, busy) {
                        filter = if (filter == it) null else it; picked = null
                    }
                    if (map.levels.size > 1) LevelButton(map.levels, level, onTap = { floors = !floors }) { level = it; picked = null }
                    Pill {
                        MapIcon(Icons.Filled.MyLocation, "Where am I", if (here != null) C.blue else C.label) {
                            if (!allowed) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else if (fix == null) { follow = true; Toast.makeText(ctx, "Finding you…", Toast.LENGTH_SHORT).show() }
                            else follow = true
                        }
                    }
                }
            }
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

        // Top: a header bar with back, which airport, floor, gate and filter, and open in another maps app.
        Row(
            Modifier.fillMaxWidth().shadow(2.dp).background(C.bar).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapIcon(Icons.Filled.ChevronLeft, "Back", C.blue, size = 30) { Nav.pop() }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(a?.name?.let(::shortName) ?: code, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    filter?.let { "${it.label} only" },
                ).joinToString(" · ")
                Text(sub, fontSize = 13.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MapIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open in a maps app", C.blue) {
                val p = picked?.at ?: target?.at ?: a?.let { Pt(it.lat, it.lon) } ?: return@MapIcon
                val label = picked?.let { it.name.ifEmpty { it.kind.label } } ?: target?.let { "Gate ${it.ref}" } ?: code
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${p.lat},${p.lon}(${Uri.encode(label)})".toUri())) }
            }
        }

        // Bottom: what you tapped, then the credit OpenStreetMap's license asks for, as plain small text.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            picked?.let { PickedCard(it, target?.takeIf { t -> t != it }, Modifier.padding(bottom = 6.dp)) { picked = null } }
            Text(
                "© OpenStreetMap contributors", fontSize = 10.sp, color = C.secondary,
                modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://www.openstreetmap.org/copyright".toUri())) } },
            )
        }
    }
}

/** An icon button with no background of its own, for the header and inside [Pill]s. */
@Composable
private fun MapIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, size: Int = 22, onClick: () -> Unit,
) = Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
}

/** The floating card the map's side buttons sit in. */
@Composable
private fun Pill(modifier: Modifier = Modifier, color: Color = C.card, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.width(44.dp).shadow(3.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(color).then(modifier),
    horizontalAlignment = Alignment.CenterHorizontally, content = content,
)

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
private fun IndoorCanvas(map: IndoorMap, proj: Proj, v: View, level: Double?, filter: Place?, target: Feature?, picked: Feature?, here: Location?) {
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
            // Only the places people look for; elevators, ATMs, water and the like show when picked below.
            val minor = setOf(Place.ELEVATOR, Place.STAIRS, Place.MONEY, Place.INFO, Place.CHARGING, Place.WATER)
            val shown = pois.filter { if (filter != null) it.kind == filter else it.kind !in minor }
                .sortedBy { if (it.name.isEmpty()) 1 else 0 }
            for (f in shown) {
                val x = sx(f); val y = sy(f)
                val r = (if (filter != null) 13 else 11).dp.toPx()
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
        // You are here, on top of everything, with how sure the phone is as a faint circle (Wi-Fi indoors: tens of
        // meters). Phones can't tell the floor, so it shows on every level.
        here?.let { h ->
            val p = Pt(h.latitude, h.longitude)
            val c = Offset((dx + proj.x(p) * s).toFloat(), (dy + proj.y(p) * s).toFloat())
            val acc = (h.accuracy * s).coerceAtMost(size.maxDimension)
            if (acc > 9.dp.toPx()) {
                drawCircle(C.blue.copy(alpha = 0.14f), acc, c)
                drawCircle(C.blue.copy(alpha = 0.35f), acc, c, style = Stroke(1.dp.toPx()))
            }
            drawCircle(Color.White, 9.dp.toPx(), c)
            drawCircle(C.blue, 6.5.dp.toPx(), c)
        }
    }
}

/** How close to the airport the dot is shown, and the locate button centers on you. */
private const val NEAR_KM = 5.0

/**
 * Where the phone is while [on], from Android's own location service (GPS, Wi-Fi, cell), updated every few seconds.
 * Read on the phone only; nothing is sent anywhere.
 */
@Composable
private fun rememberHere(on: Boolean): Location? {
    val ctx = LocalContext.current
    var here by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(on) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        // A newer fix wins unless it's much vaguer than a recent one (a cell fix right after a Wi-Fi one).
        val listener = LocationListener { l ->
            val h = here
            if (h == null || (l.time > h.time && (l.accuracy <= h.accuracy * 2 || l.time - h.time > 30_000))) here = l
        }
        if (on && lm != null) runCatching { // SecurityException if the permission was just taken away
            // The fused provider blends GPS, Wi-Fi and cell; without it, ask each one and keep the best.
            val all = lm.getProviders(true)
            for (provider in if (LocationManager.FUSED_PROVIDER in all) listOf(LocationManager.FUSED_PROVIDER) else all) {
                lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }?.let(listener::onLocationChanged)
                lm.requestLocationUpdates(provider, 2_000L, 2f, listener, Looper.getMainLooper())
            }
        }
        onDispose { runCatching { lm?.removeUpdates(listener) } }
    }
    return here
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

/** The full list of floors, top first, beside the level button. */
@Composable
private fun LevelPicker(levels: List<Double>, level: Double?, modifier: Modifier, onPick: (Double) -> Unit) = Column(
    modifier.shadow(3.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(C.card).padding(4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    levels.reversed().forEach { l ->
        val on = l == level
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (on) C.blue else Color.Transparent).clickable { onPick(l) },
            contentAlignment = Alignment.Center,
        ) { Text(levelName(l), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else C.label) }
    }
}

/**
 * The floor you're on, as a dial in a round button like the others: the number rolls with your finger, and letting go
 * past half a notch moves one floor (drag up for the floor above, down for the one below), else it springs back. One
 * floor per drag, wrapping around past the top and bottom floors. Tap for the full list ([onTap]).
 */
@Composable
private fun LevelButton(levels: List<Double>, level: Double?, onTap: () -> Unit, onPick: (Double) -> Unit) {
    val notch = with(LocalDensity.current) { 30.dp.toPx() } // the next number starts just outside the button
    val i = levels.indexOf(level).coerceAtLeast(0)
    val roll = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun at(d: Int) = levels[(i + d).mod(levels.size)]
    Pill {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onTap).pointerInput(levels, i) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        // Rolled up (negative) brings the floor above into place, as the dial turns under the finger.
                        val d = if (roll.value < 0) 1 else -1
                        scope.launch {
                            if (kotlin.math.abs(roll.value) < notch / 2) roll.animateTo(0f, spring(dampingRatio = 0.6f))
                            else { roll.animateTo(-d * notch, tween(120)); onPick(at(d)); roll.snapTo(0f) }
                        }
                    },
                    onDragCancel = { scope.launch { roll.animateTo(0f) } },
                ) { change, dy ->
                    change.consume()
                    scope.launch { roll.snapTo((roll.value + dy).coerceIn(-notch, notch)) }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            // The floor above rolls up from below, the one below down from above; only the middle one shows at rest.
            for (d in -1..1) {
                val y = d * notch + roll.value
                if (d != 0 && (y > 0) == (d > 0) && kotlin.math.abs(y) >= notch) continue
                Text(
                    levelName(at(d)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.label,
                    modifier = Modifier.graphicsLayer { translationY = y; alpha = 1f - (kotlin.math.abs(y) / notch).coerceIn(0f, 1f) },
                )
            }
        }
    }
}

/** Downloads the map again from OpenStreetMap; the arrows spin while it does. */
@Composable
private fun RefreshButton(busy: Boolean, onClick: () -> Unit) {
    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
    )
    Pill {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Refresh, "Refresh the map", tint = if (busy) C.blue else C.label,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (busy) spin else 0f },
            )
        }
    }
}

/**
 * The layers button: a menu of the kinds of places on this floor ("🚻 Restrooms 4"), one at a time showing only that
 * kind, then when the map was downloaded. Blue while a kind is picked.
 */
@Composable
private fun PlacesButton(map: IndoorMap, level: Double?, on: Place?, busy: Boolean, onPick: (Place) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val counts = remember(map, level) {
        map.features.filter { !it.kind.area && it.kind != Place.GATE && it.on(level) }.groupingBy { it.kind }.eachCount()
            .entries.sortedBy { it.key.ordinal }
    }
    Box {
        Pill(color = if (on != null) C.blue else C.card) {
            MapIcon(Icons.Filled.Layers, "Places", if (on != null) Color.White else C.label) { open = true }
        }
        DropdownMenu(open, { open = false }, containerColor = C.card) {
            DropdownMenuItem(
                text = { Text("Everything") }, onClick = { on?.let(onPick); open = false },
                trailingIcon = { if (on == null) Icon(Icons.Filled.Check, null, tint = C.blue) },
            )
            counts.forEach { (k, n) ->
                DropdownMenuItem(
                    text = { Text("${k.icon}  ${k.label}") }, onClick = { onPick(k); open = false },
                    trailingIcon = { if (k == on) Icon(Icons.Filled.Check, null, tint = C.blue) else Text("$n", color = C.secondary) },
                )
            }
            HorizontalDivider(color = C.separator)
            DropdownMenuItem(
                text = { Text(if (busy) "Updating…" else "Map updated ${ago(map.at)}", fontSize = 13.sp, color = C.secondary) },
                enabled = false, onClick = {},
            )
        }
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
