package com.aria.air.ui.screens

// The flight page's sections: phase panels, your plane, the schedule card and details.

import com.aria.air.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------- Phase panels ----------

/** A panel's headline ("Departs in 3h 10m") with an optional line under it. */
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

/** One cell for [StatRow]: label, value, optional color. */
private fun stat(label: String, value: String, color: Color? = null) = Triple(label, value, color)

/** Time left until [to], or "" once it has passed. */
private fun countdown(to: Instant, now: Instant): String {
    val d = Duration.between(now, to)
    return if (d.isNegative) "" else span(d)
}

/** The time right now in [zone]. */
private fun localNow(zone: ZoneId) = time(Instant.now(), zone)

/**
 * Before the airport: the countdown, estimated boarding, seat and flight time, and the confirmation and seat to fill
 * in.
 */
@Composable
internal fun PreTravelPanel(f: Flight, now: Instant, onEdit: (EditRequest) -> Unit) {
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

/**
 * At the airport: departs in (or departed), the delay, terminal, gate and time, the takeoff weather and the airport.
 */
@Composable
internal fun DeparturePanel(f: Flight, now: Instant) {
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

/**
 * The flight itself: time to landing and a progress line, then live speed, altitude, heading and distance while the
 * plane broadcasts, or the plan until it does.
 */
@Composable
internal fun InFlightPanel(f: Flight, now: Instant) {
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

/**
 * After landing: when, how late, the arrival terminal, gate and baggage belt, landing weather, the airport and the time
 * zone change.
 */
@Composable
internal fun ArrivalPanel(f: Flight, now: Instant) {
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

/** The time difference between the two airports at landing, when there is one. */
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

/** A row for an airport that opens its page. */
@Composable
private fun AirportCell(code: String, subtitle: String) {
    val a = Db.airport(code)
    Cell(
        a?.name ?: code, subtitle = "${flag(a?.country ?: "")} ${a?.city ?: ""} · $subtitle".trim(),
        icon = Icons.Filled.LocalAirport, chevron = true,
    ) { Nav.push(Screen.AirportInfo(code)) }
}

/** Weather at takeoff or landing ([event]), marked as a forecast while it's still ahead. */
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
internal fun YourPlane(f: Flight, now: Instant) {
    var open by remember(f.id) { mutableStateOf(false) }
    val i = f.inbound
    SectionHeader("Your plane")
    Group(Modifier.animateContentSize()) {
        if (f.reg.isEmpty()) {
            Text(
                if (Prefs.hasStatusKey) {
                    val checked = f.updated?.let { " Last checked ${time(it, f.fromTz)}." }.orEmpty()
                    // Without the tail number there's no telling whether it's parked here or still on its way (a red-eye).
                    "The flight status service hasn't named the aircraft yet, so its previous flight can't be followed." +
                        (if (Prefs.aeroKey.isBlank()) " A FlightAware key (Settings › Data) usually has it hours ahead." else "") + checked
                } else "Add an AeroDataBox or FlightAware key (Settings › Data) to see the aircraft for this flight and whether its previous flight is on time.",
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

/** Red when 15 minutes or more late, green when known and not, nothing when unknown. */
private fun delayColor(delay: Long?): Color? = when {
    delay == null -> null
    delay >= 15 -> C.red
    else -> C.green
}


// ---------- Schedule card ----------

/**
 * The schedule: both ends with their times, delays, weather, gates and belt, and the route weather between. Tapping a
 * time sets it by hand.
 */
@Composable
internal fun TimesCard(f: Flight) {
    val ctx = LocalContext.current
    fun setTime(dep: Boolean) {
        val zone = if (dep) f.fromTz else f.toTz
        val cur = (if (dep) f.depSched else f.arrSched)?.atZone(zone)?.toLocalTime() ?: LocalTime.of(if (dep) 9 else 12, 0)
        pickTime(ctx, cur) { t ->
            var at = f.date.atTime(t).atZone(zone).toInstant()
            if (!dep) while (at < (f.depSched ?: f.depGuess)) at = at.plus(Duration.ofDays(1)) // overnight arrival
            Store.upsert(if (dep) f.copy(depSched = at) else f.copy(arrSched = at))
            Tracker.schedule(ctx)
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
        RouteWxLine(f)
        TimeRow(f.to, f.dest?.name, f.arr, f.arrSched, f.toTz, f.arrDelay, f.arrWx, listOf(f.arrTerminal.let { if (it.isEmpty()) "" else "Terminal $it" }, f.arrGate.let { if (it.isEmpty()) "" else "Gate $it" }, f.baggage.let { if (it.isEmpty()) "" else "Bags $it" })) { setTime(false) }
    }
    if (!f.hasTimes) Footnote("Tap a time to set it. With an AeroDataBox or FlightAware key, times fill in automatically.")
}

/** One end of the schedule: airport code and name, chips (terminal, gate, bags), and the time colored by delay. */
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

/** Details to read and edit: airline (opens its guide), work trip, aircraft, traveler, booking code, seat and notes. */
@Composable
internal fun DetailsGroup(f: Flight, onEdit: (EditRequest) -> Unit) = Group {
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
