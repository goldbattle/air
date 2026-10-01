package com.aria.air

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Refreshes one flight and saves it, with the same alerts as the background check (so a cancellation or retiming
 * seen here first still notifies once). [force] = pull-to-refresh or the refresh button: ask even if asked recently.
 */
suspend fun refreshAndSave(ctx: android.content.Context, f: Flight, force: Boolean, foreground: Boolean = true) {
    val fetched = runCatching { Api.refresh(f, auto = !force, foreground = foreground) }.getOrDefault(f)
    val n = fetched.copy(alerted = Notify.events(ctx, f, fetched))
    Store.get(f.id)?.let { cur -> Store.upsert(n.copy(seat = cur.seat, booking = cur.booking, notes = cur.notes, friend = cur.friend, work = cur.work)) }
    Store.get(f.id)?.let { Notify.live(ctx, it) }
}

private fun refreshNow(ctx: android.content.Context, id: String, force: Boolean = false, done: () -> Unit = {}) = App.scope.launch {
    Store.get(id)?.let { refreshAndSave(ctx, it, force) }
    done()
}

@Composable
fun FlightScreen(id: String) {
    val f = Store.get(id)
    if (f == null) {
        // Deleted elsewhere while open: close, unless we're already animating out.
        LaunchedEffect(Unit) { if ((Nav.stack.lastOrNull() as? Screen.FlightDetail)?.id == id) Nav.pop() }
        return
    }
    val ctx = LocalContext.current
    val now = rememberNow()
    val phase = f.phase(now)
    var shown by remember(id) { mutableStateOf(if (phase == Phase.Done) Phase.Arrival else phase) }
    var refreshing by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Refresh on open, then every minute while the flight is around the airport or in the air.
    LaunchedEffect(id) {
        while (true) {
            val cur = Store.get(id) ?: break
            if (cur.phase() == Phase.Done && !cur.maybeLive && cur.updated != null) break
            refreshing = true
            refreshNow(ctx, id) { refreshing = false }.join()
            if (Store.get(id)?.let { it.phase() in Phase.Departure..Phase.Arrival || it.maybeLive } != true) break
            delay(60_000)
        }
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Once the map scrolls away, the floating buttons get a solid bar with the flight number, like iOS.
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            refreshing, { refreshing = true; refreshNow(ctx, id, force = true) { refreshing = false } }, Modifier.fillMaxSize(),
        ) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item("map") { MapHeader(f) }
            item("body") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    PhaseBar(f, current = phase, shown = shown) { shown = it }
                    Spacer(Modifier.height(12.dp))
                    Group(Modifier.animateContentSize()) {
                        when (shown) {
                            Phase.PreTravel -> PreTravelPanel(f, now) { edit = it }
                            Phase.Departure -> DeparturePanel(f, now)
                            Phase.InFlight -> InFlightPanel(f, now)
                            else -> ArrivalPanel(f, now)
                        }
                    }
                    if (phase <= Phase.Departure && Duration.between(now, f.dep ?: f.depGuess) < Duration.ofHours(24)) YourPlane(f, now)
                    SectionHeader("Schedule")
                    TimesCard(f)
                    SectionHeader("Details")
                    DetailsGroup(f) { edit = it }
                    Spacer(Modifier.height(24.dp))
                    ActionsGroup(f, onEdit = { edit = it }, onDelete = { confirmDelete = true })
                    Footnote(sources(f))
                }
            }
        }
        }
        // Floating controls over the map.
        Row(
            Modifier.fillMaxWidth().background(if (solid) C.bg else Color.Transparent).statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingButton(Icons.Filled.ChevronLeft, "Back") { Nav.pop() }
            Text(
                if (solid) "${f.label} · ${f.from} → ${f.to}" else "", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            if (refreshing) Box(Modifier.size(40.dp).clip(CircleShape).background(C.card), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.blue)
            } else FloatingButton(Icons.Filled.Refresh, "Refresh") { refreshing = true; refreshNow(ctx, id, force = true) { refreshing = false } }
            Spacer(Modifier.width(8.dp))
            FloatingButton(Icons.Filled.IosShare, "Share") { share(ctx, f) }
        }
    }
    EditDialog(edit) { edit = null }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${f.label}?") },
        text = { Text("It's removed from your ${if (f.mine) "flights and passport" else "friends' flights"}. This can't be undone.") },
        confirmButton = {
            TextButton({ confirmDelete = false; Nav.pop(); Store.remove(f.id); TrackWorker.schedule(ctx) }) { Text("Delete", color = C.red) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FloatingButton(icon: ImageVector, description: String, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(C.card.copy(alpha = 0.92f)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, description, tint = C.label, modifier = Modifier.size(22.dp)) }

@Composable
private fun MapHeader(f: Flight) {
    val o = f.origin; val d = f.dest
    // The map runs behind the title; the route sits in the upper part and a gradient keeps the title readable.
    Box(Modifier.fillMaxWidth()) {
        if (o != null && d != null) {
            WorldMap(
                listOf(MapRoute(o, d, highlight = true)), Modifier.matchParentSize(), focus = true, centerY = 0.36f,
                plane = f.live?.takeIf { f.isAirborne },
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 0.8f to C.bg.copy(alpha = 0.85f), 1f to C.bg),
            ),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 250.dp, bottom = 8.dp)) { Title(f) }
    }
}

@Composable
private fun Title(f: Flight) = Column(Modifier.padding(start = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AirlineBadge(f.airline, 34)
        Spacer(Modifier.width(10.dp))
        Text(f.label, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        val (s, tone) = f.status()
        Badge(s, toneColor(tone))
    }
    Text("${f.origin?.city ?: f.from} → ${f.dest?.city ?: f.to}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Text(
        longDay(f.date) + if (!f.mine) " · ${f.friend}'s flight" else "",
        fontSize = 15.sp, color = C.secondary,
    )
}

/** Pre-travel · Departure · PIT → SFO · Arrival, like the airline apps. The current phase gets a dot. */
@Composable
private fun PhaseBar(f: Flight, current: Phase, shown: Phase, onSelect: (Phase) -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(C.fill).padding(3.dp),
) {
    val phases = listOf(Phase.PreTravel, Phase.Departure, Phase.InFlight, Phase.Arrival)
    val reached = if (current == Phase.Done) Phase.Arrival else current
    phases.forEach { p ->
        val on = p == shown
        Row(
            Modifier.weight(if (p == Phase.InFlight) 1.1f else 1f).height(36.dp).clip(RoundedCornerShape(50))
                .background(if (on) C.raised else Color.Transparent).clickable { onSelect(p) },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p == reached) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (current == Phase.InFlight) C.green else C.blue))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (p == Phase.InFlight) "${f.from} → ${f.to}" else p.title,
                fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Clip,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (p <= reached || on) C.label else C.secondary,
            )
        }
    }
}

// ---------- Phase panels ----------

@Composable
private fun Big(text: String, sub: String? = null, color: Color = C.label) = Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
    Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
    if (sub != null) Text(sub, fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(top = 2.dp))
}

/** A row of big-number stats (Terminal / Gate / Time, Speed / Altitude ...). */
@Composable
private fun StatRow(vararg stats: Triple<String, String, Color?>) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    stats.forEach { (label, value, color) ->
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label.uppercase(), fontSize = 11.sp, color = C.secondary, letterSpacing = 0.5.sp, maxLines = 1)
            Text(
                smallSuffix(value), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color ?: C.label,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "7:30 AM" with a smaller "AM", "450 mph" with a smaller unit, so big numbers fit three to a row. */
private fun smallSuffix(v: String) = androidx.compose.ui.text.buildAnnotatedString {
    val m = Regex("^(.*\\d)( ?(?:AM|PM|mph|ft|fpm|mi))$").find(v)
    if (m == null) append(v) else {
        append(m.groupValues[1])
        pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = 14.sp)); append(m.groupValues[2]); pop()
    }
}

private fun stat(label: String, value: String, color: Color? = null) = Triple(label, value, color)

private fun countdown(to: Instant, now: Instant): String {
    val d = Duration.between(now, to)
    return if (d.isNegative) "" else span(d)
}

private fun localNow(zone: ZoneId) = time(Instant.now(), zone)

@Composable
private fun PreTravelPanel(f: Flight, now: Instant, onEdit: (EditRequest) -> Unit) {
    val dep = f.dep ?: f.depGuess
    val left = countdown(dep, now)
    Big(
        if (left.isEmpty()) "Departed" else "Departs in $left",
        if (f.dep != null) "${longDay(f.date)} at ${time(f.dep, f.fromTz)}" else "${longDay(f.date)} · add the time below",
    )
    Hairline()
    StatRow(
        stat("Est. boarding", f.boarding?.let { time(it, f.fromTz) } ?: "—"),
        stat("Seat", f.seat.ifEmpty { "—" }, if (f.seat.isNotEmpty()) C.blue else null),
        stat("Flight time", span(f.duration)),
    )
    Hairline()
    Cell("Confirmation", f.booking.ifEmpty { "Add" }, icon = Icons.Filled.ConfirmationNumber, chevron = true) {
        onEdit(EditRequest("Confirmation code", f.booking, caps = true) { Store.upsert(f.copy(booking = it.uppercase())) })
    }
    Hairline(50.dp)
    Cell("Seat", f.seat.ifEmpty { "Add" }, icon = Icons.Filled.EventSeat, chevron = true) {
        onEdit(EditRequest("Seat", f.seat, caps = true) { Store.upsert(f.copy(seat = it.uppercase())) })
    }
    Hairline(50.dp)
    val h = Prefs.reminderHours
    Cell("Reminder", if (h == 0 || !f.mine) "Off" else "$h h before", icon = Icons.Filled.Alarm, chevron = true) { Nav.open(Tab.Settings) }
    TimezoneCell(f)
}

@Composable
private fun DeparturePanel(f: Flight, now: Instant) {
    val delay = f.depDelay
    val left = countdown(f.dep ?: f.depGuess, now)
    Big(
        when {
            f.cancelled -> "Cancelled"
            f.tookOff != null -> "Departed ${time(f.tookOff, f.fromTz)}"
            left.isEmpty() -> "Departing"
            else -> "Departs in $left"
        },
        when {
            delay != null && delay >= 15 -> "Delayed ${span(Duration.ofMinutes(delay))} · scheduled ${time(f.depSched, f.fromTz)}"
            delay != null -> "On time"
            else -> null
        },
        if (f.cancelled || (delay ?: 0) >= 15) C.red else C.label,
    )
    Hairline()
    StatRow(
        stat("Terminal", f.depTerminal.ifEmpty { "—" }),
        stat("Gate", f.depGate.ifEmpty { "—" }, if (f.depGate.isNotEmpty()) C.blue else null),
        stat(if (f.tookOff != null) "Departed" else "Departs", time(f.dep, f.fromTz), delayColor(delay)),
    )
    WeatherCell(f.depWx, "takeoff", f.origin?.city ?: f.from)
    Hairline()
    AirportCell(f.from, "Local time ${localNow(f.fromTz)}")
    if (!Prefs.hasStatusKey && f.depGate.isEmpty()) {
        Hairline()
        Text(
            "Gates and delays need an AeroDataBox or FlightAware key (Settings › Data).",
            fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun InFlightPanel(f: Flight, now: Instant) {
    val l = f.live?.takeIf { f.isAirborne }
    val phase = f.phase(now)
    val arr = f.arr ?: f.arrGuess
    Big(
        when {
            phase < Phase.InFlight -> "${span(f.duration)} flight"
            phase > Phase.InFlight -> "Landed ${time(f.arrActual ?: arr, f.toTz)}"
            else -> "Lands in ${countdown(arr, now).ifEmpty { "moments" }}"
        },
        "${fmtInt((f.distanceKm / KM_PER_MI).roundToInt())} mi · ${f.origin?.city ?: f.from} to ${f.dest?.city ?: f.to}",
    )
    Column(Modifier.padding(horizontal = 16.dp)) {
        ProgressLine(if (phase > Phase.InFlight) 1.0 else f.progress(now), height = 6)
        Row {
            Text(f.from, fontSize = 13.sp, color = C.secondary, modifier = Modifier.weight(1f))
            Text(f.to, fontSize = 13.sp, color = C.secondary)
        }
    }
    Spacer(Modifier.height(4.dp))
    Hairline()
    if (l != null) {
        StatRow(
            stat("Speed", "${(l.speedKt * 1.15078).roundToInt()} mph"),
            stat("Altitude", "${fmtInt(l.altFt)} ft"),
            stat("Heading", "${l.track.roundToInt()}° ${compass(l.track)}"),
        )
        StatRow(
            stat("Time left", countdown(arr, now).ifEmpty { "—" }),
            stat("Distance left", "${fmtInt((f.remainingKm() / KM_PER_MI).roundToInt())} mi"),
            stat(if (l.vrateFpm < -300) "Descending" else if (l.vrateFpm > 300) "Climbing" else "Level", "${fmtInt(kotlin.math.abs(l.vrateFpm))} fpm"),
        )
        Hairline()
        Text(
            "Live position ${ago(l.at)}" + (if (l.reg.isNotEmpty()) " · ${l.aircraft} ${l.reg}" else ""),
            fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(16.dp),
        )
    } else {
        StatRow(
            stat("Distance", "${fmtInt((f.distanceKm / KM_PER_MI).roundToInt())} mi"),
            stat("Duration", span(f.duration)),
            stat("Aircraft", f.aircraft.ifEmpty { "—" }),
        )
        Hairline()
        Text(
            if (phase == Phase.InFlight) "Waiting for the plane's live signal (ADS-B via adsb.lol). Progress is estimated from the schedule until then."
            else "Speed, altitude and position show here live while the plane is in the air.",
            fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ArrivalPanel(f: Flight, now: Instant) {
    val delay = f.arrDelay
    val arr = f.arr ?: f.arrGuess
    Big(
        when {
            f.arrActual != null -> "Landed ${time(f.arrActual, f.toTz)}"
            f.phase(now) >= Phase.Arrival -> "Arrived"
            else -> "Arrives ${time(f.arr, f.toTz).takeIf { f.arr != null } ?: "in ${countdown(arr, now)}"}"
        },
        when {
            delay != null && delay >= 15 -> "${span(Duration.ofMinutes(delay))} late · scheduled ${time(f.arrSched, f.toTz)}"
            delay != null && delay <= -5 -> "${span(Duration.ofMinutes(-delay))} early"
            delay != null -> "On time"
            else -> null
        },
    )
    Hairline()
    StatRow(
        stat("Terminal", f.arrTerminal.ifEmpty { "—" }),
        stat("Gate", f.arrGate.ifEmpty { "—" }, if (f.arrGate.isNotEmpty()) C.blue else null),
        stat("Baggage", f.baggage.ifEmpty { "—" }, if (f.baggage.isNotEmpty()) C.orange else null),
    )
    WeatherCell(f.arrWx, "landing", f.dest?.city ?: f.to)
    Hairline()
    AirportCell(f.to, "Local time ${localNow(f.toTz)}")
    TimezoneCell(f)
}

@Composable
private fun TimezoneCell(f: Flight) {
    val at = f.arr ?: f.arrGuess
    val diff = (f.toTz.rules.getOffset(at).totalSeconds - f.fromTz.rules.getOffset(at).totalSeconds) / 3600.0
    if (diff == 0.0) return
    Hairline()
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Time zone change", fontSize = 17.sp)
            Text(
                "${time(at, f.toTz)} in ${f.dest?.city ?: f.to} is ${time(at, f.fromTz)} in ${f.origin?.city ?: f.from}",
                fontSize = 13.sp, color = C.secondary,
            )
        }
        val h = if (diff % 1.0 == 0.0) diff.toInt().toString() else "%.1f".format(diff)
        Text((if (diff > 0) "+" else "") + "${h}h", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = C.indigo)
    }
}

@Composable
private fun AirportCell(code: String, subtitle: String) {
    val a = Db.airport(code)
    Cell(
        a?.name ?: code, subtitle = "${flag(a?.country ?: "")} ${a?.city ?: ""} · $subtitle".trim(),
        icon = Icons.Filled.LocalAirport, chevron = true,
    ) { Nav.push(Screen.AirportInfo(code)) }
}

@Composable
private fun WeatherCell(w: Wx?, event: String, city: String) {
    if (w == null) return
    Hairline()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(w.icon(), fontSize = 30.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${w.desc()} at $event", fontSize = 17.sp)
            Text(
                "$city · wind ${w.wind()}" + if (w.at > Instant.now()) " · forecast" else "",
                fontSize = 13.sp, color = C.secondary,
            )
        }
        Text(w.temp(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The aircraft for this flight on its previous flight: collapsed to one line with a verdict, expanding to where it
 * is, when it lands here and what that means for our departure.
 */
@Composable
private fun YourPlane(f: Flight, now: Instant) {
    var open by remember(f.id) { mutableStateOf(false) }
    val i = f.inbound
    SectionHeader("Your plane")
    Group(Modifier.animateContentSize()) {
        if (f.reg.isEmpty()) {
            Text(
                if (Prefs.hasStatusKey) "The airline hasn't assigned an aircraft yet, usually about a day before. Its inbound flight shows here once it has."
                else "Add an AeroDataBox or FlightAware key (Settings › Data) to see the aircraft for this flight and whether its previous flight is on time.",
                fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(16.dp),
            )
            return@Group
        }
        val tone = f.inboundTone()
        val verdict = when {
            i == null -> "Probably parked"
            f.inboundOvernight() -> "Here since last night"
            f.inboundHere() -> "At the gate"
            tone == Tone.Good -> "On track"
            tone == Tone.Warn -> "Tight turnaround"
            tone == Tone.Bad -> "Likely to delay you"
            else -> "Watching"
        }
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Flight, null, tint = toneColor(tone), modifier = Modifier.size(22.dp).rotate(135f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(verdict, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = toneColor(tone).takeIf { tone != Tone.Neutral } ?: C.label)
                Text(
                    f.inboundLine() ?: "${f.reg} isn't broadcasting yet. Planes usually switch their transponder off at the gate " +
                        "(overnight, for example); it shows up here as soon as it powers up or moves.",
                    fontSize = 13.sp, color = C.secondary,
                )
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = C.tertiary)
        }
        if (open) {
            Hairline()
            if (i != null && f.inboundHere()) {
                // Parked here: when it arrived and how long it has to turn around.
                val since = i.landedAt ?: i.firstSeen
                StatRow(
                    stat("Arrived", i.landedAt?.let { time(it, f.fromTz) } ?: if (f.inboundOvernight()) "Overnight" else "Earlier"),
                    stat("On the ground", span(Duration.between(since, now)) + if (i.landedAt == null) "+" else ""),
                    stat("Before you leave", span(Duration.between(now, f.dep ?: f.depGuess).let { if (it.isNegative) Duration.ZERO else it }), C.green),
                )
                if (i.from.isNotEmpty() || i.label.isNotEmpty()) StatRow(
                    stat("Last flight", i.label.ifEmpty { "—" }),
                    stat("Came from", i.from.ifEmpty { "—" }),
                    stat("Last signal", span(Duration.between(i.at, now).let { if (it.isNegative) Duration.ZERO else it }) + " ago"),
                )
                Hairline()
            }
            if (i != null && !f.inboundHere()) {
                StatRow(
                    stat("Flight", i.label.ifEmpty { "—" }),
                    stat("Lands here", i.eta?.let { time(it, f.fromTz) } ?: "—", toneColor(tone).takeIf { i.eta != null }),
                    stat("To spare", f.inboundSpare()?.let { if (it >= 0) span(Duration.ofMinutes(it)) else "-" + span(Duration.ofMinutes(-it)) } ?: "—", toneColor(tone)),
                )
                StatRow(
                    stat("Coming from", i.from.ifEmpty { "—" }),
                    stat(if (i.onGround) "On ground" else "Speed", if (i.onGround) "yes" else "${(i.speedKt * 1.15078).roundToInt()} mph"),
                    stat("Altitude", if (i.onGround) "—" else "${fmtInt(i.altFt)} ft"),
                )
                Hairline()
            }
            Cell("Aircraft", listOf(f.aircraft, f.reg).filter { it.isNotEmpty() }.joinToString(" · "))
            Hairline()
            Text(
                (i?.let { "Position ${ago(it.at)} from adsb.lol. " } ?: "") +
                    "A plane usually needs 30 to 45 minutes on the ground between flights, so an inbound landing with less " +
                    "time to spare before your departure often means a delay, even before the airline announces one.",
                fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun delayColor(delay: Long?): Color? = when {
    delay == null -> null
    delay >= 15 -> C.red
    else -> C.green
}

private fun compass(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8]

// ---------- Schedule card ----------

@Composable
private fun TimesCard(f: Flight) {
    val ctx = LocalContext.current
    fun setTime(dep: Boolean) {
        val zone = if (dep) f.fromTz else f.toTz
        val cur = (if (dep) f.depSched else f.arrSched)?.atZone(zone)?.toLocalTime() ?: LocalTime.of(if (dep) 9 else 12, 0)
        pickTime(ctx, cur) { t ->
            var at = f.date.atTime(t).atZone(zone).toInstant()
            if (!dep) while (at < (f.depSched ?: f.depGuess)) at = at.plus(Duration.ofDays(1)) // overnight arrival
            Store.upsert(if (dep) f.copy(depSched = at) else f.copy(arrSched = at))
            TrackWorker.schedule(ctx)
        }
    }
    Group {
        TimeRow(f.from, f.origin?.name, f.dep, f.depSched, f.fromTz, f.depDelay, f.depWx, listOf(f.depTerminal.let { if (it.isEmpty()) "" else "Terminal $it" }, f.depGate.let { if (it.isEmpty()) "" else "Gate $it" })) { setTime(true) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(0.5.dp).background(C.separator))
            Text(
                "  ${span(f.duration)} · ${fmtInt((f.distanceKm / KM_PER_MI).roundToInt())} mi  ",
                fontSize = 13.sp, color = C.secondary,
            )
            Box(Modifier.weight(1f).height(0.5.dp).background(C.separator))
        }
        TimeRow(f.to, f.dest?.name, f.arr, f.arrSched, f.toTz, f.arrDelay, f.arrWx, listOf(f.arrTerminal.let { if (it.isEmpty()) "" else "Terminal $it" }, f.arrGate.let { if (it.isEmpty()) "" else "Gate $it" }, f.baggage.let { if (it.isEmpty()) "" else "Bags $it" })) { setTime(false) }
    }
    if (!f.hasTimes) Footnote("Tap a time to set it. With an AeroDataBox or FlightAware key, times fill in automatically.")
}

@Composable
private fun TimeRow(code: String, name: String?, best: Instant?, sched: Instant?, zone: ZoneId, delay: Long?, wx: Wx?, chips: List<String>, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.Top,
) {
    Column(Modifier.weight(1f)) {
        Text(code, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(name ?: "", fontSize = 13.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val c = chips.filter { it.isNotEmpty() }
        if (c.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            c.forEach { Badge(it, C.orange) }
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(if (best == null) "Set time" else time(best, zone), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (best == null) C.blue else delayColor(delay) ?: C.label)
        when {
            delay != null && delay >= 15 -> Text("${span(Duration.ofMinutes(delay))} late", fontSize = 13.sp, color = C.red)
            delay != null && delay <= -5 -> Text("${span(Duration.ofMinutes(-delay))} early", fontSize = 13.sp, color = C.green)
            delay != null -> Text("On time", fontSize = 13.sp, color = C.green)
        }
        if (sched != null && best != null && delay != null && kotlin.math.abs(delay) >= 5) {
            Text("Sched. ${time(sched, zone)}", fontSize = 13.sp, color = C.secondary, textAlign = TextAlign.End)
        }
        if (wx != null) Text(wx.short(), fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- Details & actions ----------

@Composable
private fun DetailsGroup(f: Flight, onEdit: (EditRequest) -> Unit) = Group {
    val airline = Db.airline(f.airline)
    Cell(
        airline?.name ?: f.airline, subtitle = Guides.airline(f.airline)?.optString("alliance")?.takeIf { it.isNotEmpty() },
        icon = Icons.Filled.Flight, chevron = Guides.airline(f.airline) != null,
        onClick = Guides.airline(f.airline)?.let { { Nav.push(Screen.AirlineInfo(f.airline)) } },
    )
    if (f.mine) {
        Hairline(50.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Filled.Work, null, tint = C.blue, modifier = Modifier.size(22.dp))
            Box(Modifier.weight(1f)) {
                SwitchCell("Work trip", f.work, "Business travel, counted separately in Passport") { Store.upsert(f.copy(work = it)) }
            }
        }
    }
    Hairline(50.dp)
    Cell("Aircraft", listOf(f.aircraft, f.reg).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" }, icon = Icons.Filled.AirplanemodeActive)
    if (!f.mine) {
        Hairline(50.dp)
        Cell("Traveler", f.friend, icon = Icons.Filled.Person, chevron = true) {
            onEdit(EditRequest("Traveler", f.friend) { if (it.isNotEmpty()) Store.upsert(f.copy(friend = it)) })
        }
    }
    Hairline(50.dp)
    Cell("Booking code", f.booking.ifEmpty { "Add" }, icon = Icons.Filled.ConfirmationNumber, chevron = true) {
        onEdit(EditRequest("Booking code", f.booking, caps = true) { Store.upsert(f.copy(booking = it.uppercase())) })
    }
    Hairline(50.dp)
    Cell("Seat", f.seat.ifEmpty { "Add" }, icon = Icons.Filled.EventSeat, chevron = true) {
        onEdit(EditRequest("Seat", f.seat, caps = true) { Store.upsert(f.copy(seat = it.uppercase())) })
    }
    Hairline(50.dp)
    Cell("Notes", f.notes.ifEmpty { "Add" }, icon = Icons.AutoMirrored.Filled.Notes, chevron = true) {
        onEdit(EditRequest("Notes", f.notes) { Store.upsert(f.copy(notes = it)) })
    }
}

@Composable
private fun ActionsGroup(f: Flight, onEdit: (EditRequest) -> Unit, onDelete: () -> Unit) = Group {
    if (f.mine) {
        Cell("Move to Friends' flights", icon = Icons.Filled.Group) {
            onEdit(EditRequest("Whose flight is it?", "") { name ->
                if (name.isNotEmpty()) { Store.upsert(f.copy(friend = name)); Nav.open(Tab.Friends) }
            })
        }
    } else {
        Cell("Move to My flights", icon = Icons.Filled.Person) { Store.upsert(f.copy(friend = "")); Nav.open(Tab.Flights) }
    }
    Hairline(50.dp)
    Cell("Delete flight", color = C.red, icon = Icons.Filled.Delete, iconTint = C.red, onClick = onDelete)
}

private fun sources(f: Flight) = buildList {
    f.updated?.let { add("Status updated ${ago(it)}") }
    f.live?.let { add("Live position from adsb.lol ${ago(it.at)}") }
    add("Added via ${f.source}")
}.joinToString(" · ")

private fun share(ctx: android.content.Context, f: Flight) {
    val text = buildString {
        append("${f.label} ${f.origin?.city ?: f.from} (${f.from}) → ${f.dest?.city ?: f.to} (${f.to}), ${longDay(f.date)}")
        f.dep?.let { append("\nDeparts ${time(it, f.fromTz)}") }
        f.arr?.let { append(" · Arrives ${time(it, f.toTz)}") }
        val (s, _) = f.status()
        append("\nStatus: $s")
    }
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share flight"))
}
