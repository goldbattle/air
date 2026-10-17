package com.aria.air.ui.widget

// What the home screen widget shows and draws (the widget class itself is FlightWidget).

import com.aria.air.*
import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.ui.components.Accent
import com.aria.air.ui.map.*
import com.aria.air.util.*
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNight
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** The widget's colors, light and dark; Glance picks by the system theme. */
private object W {
    val bg = DayNight(Color.White, Color(0xFF1C1C1E))

    /** Translucent, so the wallpaper shows through like the iOS widgets. */
    val glass = DayNight(Color(0xC7FFFFFF), Color(0xB31C1C1E))
    val label = DayNight(Color.Black, Color.White)
    val secondary = DayNight(Color(0xFF8A8A8E), Color(0xFF98989F))
    val fill = DayNight(Color(0xFFE5E5EA), Color(0xFF3A3A3C))
    val blue = DayNight(Color(0xFF007AFF), Color(0xFF0A84FF))
    val green = DayNight(Color(0xFF34C759), Color(0xFF30D158))
    val orange = DayNight(Color(0xFFFF9500), Color(0xFFFF9F0A))
    val red = DayNight(Color(0xFFFF3B30), Color(0xFFFF453A))
}

/** How long the widget shows one flight before the next: about one background check. */
private val WIDGET_TURN: Duration = Duration.ofMinutes(30)

private fun upcoming() = Store.flights.filter { it.upcoming }

/** The flights that take turns in the widget: all of them, or ([stacked]) all but the one fixed on top. */
private fun rotating(stacked: Boolean, now: Instant) = turnOrder(upcoming(), now).let { if (stacked) it.drop(1) else it }

/**
 * What the widget shows: one flight taking turns ([takingTurns]: my active flight always), or, [stacked] at the large
 * size, the first in line fixed on top (my next flight, if any) and the others taking turns below it.
 */
internal fun widgetFlights(stacked: Boolean, now: Instant = Instant.now(clock)): List<Flight> {
    val top = if (stacked) turnOrder(upcoming(), now).firstOrNull() ?: return emptyList() else null
    return listOfNotNull(top, Turns.showing("widget", rotating(stacked, now), now, WIDGET_TURN))
}

/** The flight the widget shows first (for the radar fetch). */
internal fun widgetFlight(now: Instant = Instant.now(clock)): Flight? = widgetFlights(false, now).firstOrNull()

private val STACKED = ActionParameters.Key<Boolean>("stacked")

/** The widget's › button: on to the next flight in turn (below the fixed one, when stacked). */
class NextWidgetFlight : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val now = Instant.now(clock)
        Turns.next("widget", rotating(parameters[STACKED] == true, now), now, WIDGET_TURN)
        FlightWidget.refresh(context)
    }
}

/** The › action, or null when there's nothing else to show. */
private fun nextAction(stacked: Boolean): Action? =
    if (rotating(stacked, Instant.now(clock)).size < 2) null else actionRunCallback<NextWidgetFlight>(actionParametersOf(STACKED to stacked))

/** ›: shows the next flight. */
@Composable
private fun NextButton(next: Action?) {
    if (next == null) return
    Spacer(GlanceModifier.width(6.dp))
    Box(
        GlanceModifier.size(24.dp).background(W.fill).cornerRadius(12.dp).clickable(next),
        contentAlignment = Alignment.Center,
    ) { Text("›", style = TextStyle(color = W.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)) }
}

/** The flight's color from Settings: mine or friends'. */
private fun accent(f: Flight) = (if (f.mine) Accent.mine else Accent.friend).let { DayNight(Color(it.light), Color(it.dark)) }

/** Whose flight it is, as a pill in the friends' color; nothing for mine. */
@Composable
private fun Who(f: Flight) {
    if (f.mine) return
    val a = Accent.friend
    Text(
        f.friend.substringBefore(' '),
        style = TextStyle(color = accent(f), fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        modifier = GlanceModifier.background(DayNight(Color(a.light).copy(alpha = 0.18f), Color(a.dark).copy(alpha = 0.28f)))
            .cornerRadius(8.dp).padding(horizontal = 6.dp, vertical = 1.dp),
    )
    Spacer(GlanceModifier.width(6.dp))
}

/** What the big line says: the headline and the countdown under it. */
internal fun headline(f: Flight, now: Instant): Pair<String, String> {
    val dep = f.dep ?: f.depGuess
    val left = { t: Instant -> span(Duration.between(now, t).let { if (it.isNegative) Duration.ZERO else it }) }
    return when (f.phase(now)) {
        Phase.InFlight -> "Lands in" to left(f.arr ?: f.arrGuess)
        Phase.Arrival, Phase.Done -> "Landed" to time(f.arrActual ?: f.arr ?: f.arrGuess, f.toTz)
        else -> {
            val boarding = dep.minus(f.boardingLead)
            if (f.dep != null && now < boarding && Duration.between(now, boarding) < Duration.ofHours(4)) "Boarding in" to left(boarding)
            else if (f.dep == null) "Departs" to dayLabel(f.date)
            else "Departs in" to left(dep)
        }
    }
}

/** The widget color for a [Tone]. */
private fun toneProvider(t: Tone) = when (t) {
    Tone.Good -> W.green
    Tone.Warn -> W.orange
    Tone.Bad -> W.red
    Tone.Info -> W.blue
    Tone.Neutral -> W.secondary
}

/** Whether the system is in dark mode, for the route bitmap (Glance colors switch on their own; a bitmap doesn't). */
private fun isNight(ctx: Context) =
    (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

/**
 * Each shown flight's line color, light and dark: mine or friends' ([Accent]); a second flight in the same color gets
 * a shifted hue so the two routes tell apart.
 */
private fun hues(fs: List<Flight>): List<Pair<Int, Int>> = fs.mapIndexed { i, f ->
    val a = if (f.mine) Accent.mine else Accent.friend
    val same = i > 0 && fs[0].mine == f.mine
    fun shift(c: Long) = if (!same) c.toInt() else FloatArray(3).let { hsv ->
        android.graphics.Color.colorToHSV(c.toInt(), hsv); hsv[0] = (hsv[0] + 55) % 360; android.graphics.Color.HSVToColor(hsv)
    }
    shift(a.light) to shift(a.dark)
}

/** A flight's line color as a Glance color (light and dark). */
private fun hueProvider(h: Pair<Int, Int>) = DayNight(Color(h.first), Color(h.second))

/**
 * The map behind the widget: land faint enough to read text over, the radar, and each flight's great circle (flown
 * part solid, the rest faded) in its color with the plane at its position, all fitted into the band between [top] and
 * [bottom] (fractions of the height). Glance can't draw, so this is a bitmap sized to the widget.
 */
private fun routeBitmap(ctx: Context, fs: List<Flight>, wDp: Float, hDp: Float, top: Float, bottom: Float): android.graphics.Bitmap? {
    val density = ctx.resources.displayMetrics.density
    val night = isNight(ctx)
    // Once there's a recorded path (Track.kt), the line is that, then the plan from the plane on; split at the plane.
    // Every arc is unwrapped around the first one's start, so routes across the date line stay together.
    var ref: Double? = null
    class Line(val f: Flight, val arc: List<Pair<Double, Double>>, val color: Int, val flown: Int)
    val lines = fs.zip(hues(fs)).mapNotNull { (f, hue) ->
        val o = f.origin ?: return@mapNotNull null
        val d = f.dest ?: return@mapNotNull null
        val r = ref ?: o.lon.also { ref = it }
        val mp = f.paths(emptyList(), emptyList(), 0)
        val arc = unwrap(if (mp.flown.size < 2) greatCircle(o.lat, o.lon, d.lat, d.lon, 96).map { Pt(it.first, it.second) } else mp.flown + mp.rest.drop(1), r)
        Line(f, arc, if (night) hue.second else hue.first, mp.flown.size)
    }
    if (lines.isEmpty()) return null
    val w = (wDp * density).roundToInt().coerceIn(1, 1400)
    val h = (hDp * density).roundToInt().coerceIn(1, 1400)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)

    // Fit every arc into the band, leaving the text readable.
    val all = lines.flatMap { it.arc }
    val t = h * top; val b = h * bottom
    val left = w * 0.10f; val right = w * 0.90f
    val lonMin = all.minOf { it.second }; val lonMax = all.maxOf { it.second }
    val latMin = all.minOf { it.first }; val latMax = all.maxOf { it.first }
    val kx = kotlin.math.cos(Math.toRadians((latMin + latMax) / 2))
    val spanLon = maxOf(lonMax - lonMin, 6.0) * kx
    val spanLat = maxOf(latMax - latMin, 3.0)
    val scale = minOf((right - left) / spanLon, (b - t) / spanLat)
    val cLon = (lonMin + lonMax) / 2; val cLat = (latMin + latMax) / 2
    val cx = (left + right) / 2; val cy = (t + b) / 2
    fun x(lon: Double) = (cx + (lon - cLon) * kx * scale).toFloat()
    fun y(lat: Double) = (cy - (lat - cLat) * scale).toFloat()

    val land = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0x2EFFFFFF else 0x1A000000
    }
    val path = android.graphics.Path()
    for (shift in listOf(-360.0, 0.0, 360.0)) for (r in Land.rings) {
        if (r.size < 6) continue
        path.moveTo(x(r[0] + shift), y(r[1].toDouble()))
        var i = 2
        while (i < r.size) { path.lineTo(x(r[i] + shift), y(r[i + 1].toDouble())); i += 2 }
        path.close()
    }
    c.drawPath(path, land)
    // Radar layer (RainViewer.kt), so storms along either route show; one picture per area.
    fs.mapNotNull { RainViewer.cached(it) }.distinct().forEach { drawRadar(c, it, ::x, ::y) }

    val stroke = 3.2f * density
    val base = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xB3FFFFFF.toInt() else 0x99000000.toInt(); textSize = 11 * density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val ground = if (night) 0xFF1C1C1E.toInt() else android.graphics.Color.WHITE
    for (l in lines) {
        val f = l.f; val arc = l.arc
        val phase = f.phase()
        val p = when {
            phase >= Phase.Arrival -> 1.0
            phase == Phase.InFlight -> f.progress()
            else -> 0.0
        }
        val split = (if (l.flown >= 2) l.flown - 1 else (p * (arc.size - 1)).roundToInt()).coerceIn(0, arc.size - 1)
        fun draw(from: Int, to: Int, paint: android.graphics.Paint) {
            if (to <= from) return
            val lp = android.graphics.Path()
            lp.moveTo(x(arc[from].second), y(arc[from].first))
            for (i in from + 1..to) lp.lineTo(x(arc[i].second), y(arc[i].first))
            c.drawPath(lp, paint)
        }
        draw(split, arc.size - 1, android.graphics.Paint(base).apply {
            color = l.color; alpha = 110; pathEffect = android.graphics.DashPathEffect(floatArrayOf(2 * stroke, 1.6f * stroke), 0f)
        })
        draw(0, split, android.graphics.Paint(base).apply { color = l.color })
        for ((la, lo) in listOf(arc.first(), arc.last())) {
            dot.color = ground; c.drawCircle(x(lo), y(la), 5.5f * density, dot)
            dot.color = l.color; c.drawCircle(x(lo), y(la), 3.8f * density, dot)
        }
        // Airport codes next to the ends of the line.
        val a0 = arc.first(); val a1 = arc.last()
        val leftEnd = x(a0.second) <= x(a1.second)
        label.textAlign = if (leftEnd) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
        c.drawText(f.from, x(a0.second) + (if (leftEnd) -9 else 9) * density, y(a0.first) + 4 * density, label)
        label.textAlign = if (leftEnd) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.RIGHT
        c.drawText(f.to, x(a1.second) + (if (leftEnd) 9 else -9) * density, y(a1.first) + 4 * density, label)
        if (phase == Phase.InFlight) {
            val a = arc[maxOf(0, split - 1)]; val bb = arc[minOf(arc.size - 1, split + 1)]
            val bearing = Math.toDegrees(kotlin.math.atan2((x(bb.second) - x(a.second)).toDouble(), -(y(bb.first) - y(a.first)).toDouble())).toFloat()
            val px = x(arc[split].second); val py = y(arc[split].first)
            val s = 22 * density
            dot.color = ground; c.drawCircle(px, py, s * 0.62f, dot)
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_plane)?.mutate()?.let { dr ->
                dr.setTint(l.color)
                c.save(); c.rotate(bearing, px, py)
                dr.setBounds((px - s / 2).toInt(), (py - s / 2).toInt(), (px + s / 2).toInt(), (py + s / 2).toInt())
                dr.draw(c); c.restore()
            }
        }
    }
    return bmp
}

/**
 * The widget, by size: small and wide show one flight on its map; large (wide and about three rows) shows two flights'
 * routes on one map, each in its own hue, with a row for each below: the first in line fixed, the other taking turns.
 * Tapping a flight opens it.
 */
@Composable
internal fun WidgetContent(ctx: Context) {
    val size = LocalSize.current
    val wide = size.width >= FlightWidget.WIDE.width
    val large = wide && size.height >= FlightWidget.LARGE.height
    val fs = widgetFlights(stacked = large)
    val first = fs.firstOrNull()
    val open = Intent(ctx, MainActivity::class.java).apply { if (first != null) putExtra("flight", first.id) }
    Box(GlanceModifier.fillMaxSize().background(W.glass).cornerRadius(24.dp).clickable(actionStartActivity(open))) {
        // The map band: high when two rows of text sit below it, mid-height otherwise.
        val (top, bottom) = when {
            fs.size == 2 -> 0.07f to 0.48f
            large -> 0.24f to 0.60f
            else -> 0.30f to 0.56f
        }
        if (fs.isNotEmpty()) routeBitmap(ctx, fs, size.width.value, size.height.value, top, bottom)?.let {
            Image(ImageProvider(it), null, GlanceModifier.fillMaxSize(), contentScale = androidx.glance.layout.ContentScale.FillBounds)
        }
        Box(GlanceModifier.fillMaxSize().padding(if (wide) 16.dp else 14.dp)) {
            when {
                first == null -> Empty()
                fs.size == 2 -> Column(GlanceModifier.fillMaxSize()) {
                    val h = hues(fs)
                    Spacer(GlanceModifier.defaultWeight())
                    FlightRow(ctx, fs[0], h[0], next = null)
                    Spacer(GlanceModifier.height(8.dp))
                    FlightRow(ctx, fs[1], h[1], nextAction(stacked = true))
                }
                wide -> Wide(first, nextAction(stacked = false))
                else -> Small(first, nextAction(stacked = false))
            }
        }
    }
}

/**
 * One flight in the large widget: a bar in its route's color, airline, whose and which flight, its status or details,
 * and the countdown. Tapping it opens the flight.
 */
@Composable
private fun FlightRow(ctx: Context, f: Flight, hue: Pair<Int, Int>, next: Action?) {
    val now = Instant.now()
    val (title, big) = headline(f, now)
    val (status, tone) = f.status(now)
    val open = Intent(ctx, MainActivity::class.java).putExtra("flight", f.id)
    Row(
        GlanceModifier.fillMaxWidth().background(W.fill).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(actionStartActivity(open)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(4.dp).height(34.dp).background(hueProvider(hue)).cornerRadius(2.dp)) {}
        Spacer(GlanceModifier.width(8.dp))
        AirlineChip(f)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Who(f)
                Text("${f.label} · ${f.dest?.city ?: f.to}", style = TextStyle(color = W.label, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
            Text(
                listOf(status, detailLine(f)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = TextStyle(color = toneProvider(tone), fontSize = 12.sp), maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(title, style = TextStyle(color = W.secondary, fontSize = 11.sp), maxLines = 1)
            Text(big, style = TextStyle(color = hueProvider(hue), fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        NextButton(next)
    }
}

/** "No upcoming flights". */
@Composable
private fun Empty() = Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
    Image(ImageProvider(R.drawable.ic_plane), null, GlanceModifier.size(28.dp), colorFilter = ColorFilter.tint(W.secondary))
    Spacer(GlanceModifier.height(6.dp))
    Text("No upcoming flights", style = TextStyle(color = W.secondary, fontSize = 13.sp, textAlign = TextAlign.Center))
}

/** The airline's emblem, or its code on its color. */
@Composable
private fun AirlineChip(f: Flight) {
    val icon = Guides.airlineIcon(f.airline)
    Box(
        GlanceModifier.size(20.dp).background(ColorProvider(if (icon != null) Guides.airlineIconBg(f.airline) else Guides.airlineColor(f.airline))).cornerRadius(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(ImageProvider(icon.asAndroidBitmap()), f.airline, GlanceModifier.fillMaxSize())
        else Text(f.airline, style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold))
    }
}

/** Gate/seat before the flight, speed/altitude in the air, gate/bags after. */
internal fun detailLine(f: Flight): String {
    val l = f.live?.takeIf { f.isAirborne }
    if (l != null) return "${(l.speedKt * 1.15078).roundToInt()} mph · ${fmtInt(l.altFt)} ft"
    val landed = f.phase() >= Phase.Arrival
    return listOfNotNull(
        (if (landed) f.arrGate else f.depGate).takeIf { it.isNotEmpty() }?.let { "Gate $it" },
        if (landed) f.baggage.takeIf { it.isNotEmpty() }?.let { "Bags $it" } else f.seat.takeIf { it.isNotEmpty() }?.let { "Seat $it" },
    ).joinToString(" · ")
}

/** The small widget: the flight, the big countdown, and the time or what's next. */
@Composable
private fun Small(f: Flight, next: Action?) {
    val now = Instant.now()
    val (title, big) = headline(f, now)
    val flying = f.phase(now) == Phase.InFlight
    Column(GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AirlineChip(f)
            Spacer(GlanceModifier.width(6.dp))
            Who(f)
            Text(
                f.label,
                style = TextStyle(color = W.label, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            NextButton(next)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(title, style = TextStyle(color = W.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        Text(big, style = TextStyle(color = if (flying || !f.mine) accent(f) else W.label, fontSize = 32.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(
            if (flying) "Lands ${time(f.arr ?: f.arrGuess, f.toTz)}"
            else listOfNotNull(time(f.dep, f.fromTz).takeIf { f.dep != null }, detailLine(f).takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { "${f.from} → ${f.to}" },
            style = TextStyle(color = W.secondary, fontSize = 12.sp), maxLines = 1,
        )
    }
}

/** The wide widget: flight, city and status on top, the big countdown, both ends' times and details. */
@Composable
private fun Wide(f: Flight, next: Action?) {
    val now = Instant.now()
    val (title, big) = headline(f, now)
    val phase = f.phase(now)
    val (status, tone) = f.status(now)
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AirlineChip(f)
            Spacer(GlanceModifier.width(6.dp))
            Who(f)
            Text(
                "${f.label} · ${f.dest?.city ?: f.to}",
                style = TextStyle(color = W.label, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(status, style = TextStyle(color = toneProvider(tone), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            NextButton(next)
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(GlanceModifier.defaultWeight()) {
                Text(title, style = TextStyle(color = W.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(big, style = TextStyle(color = if (phase == Phase.InFlight || !f.mine) accent(f) else W.label, fontSize = 40.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${f.from} ${time(f.dep ?: f.depGuess, f.fromTz)}",
                    style = TextStyle(color = W.label, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                )
                Text(
                    "${f.to} ${time(f.arr ?: f.arrGuess, f.toTz)}",
                    style = TextStyle(color = W.label, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                )
                detailLine(f).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = TextStyle(color = W.secondary, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
    }
}
