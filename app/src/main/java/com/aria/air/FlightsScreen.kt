package com.aria.air

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Per-list UI state that survives navigating into a flight and back. */
class ListMemory {
    val past = mutableIntStateOf(0)
    val who = mutableIntStateOf(0)
    val scroll = androidx.compose.foundation.lazy.LazyListState()

    companion object {
        val flights = ListMemory()
        val friends = ListMemory()
    }
}

/** Ticks once a minute so countdowns and progress stay current while a screen is open. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    return now
}

/** My flights, or friends' flights: Upcoming / Past, grouped by day. */
@Composable
fun FlightsScreen(friends: Boolean) {
    val now = rememberNow()
    // Kept outside the screen so opening a flight and coming back returns to the same tab, filter and scroll spot.
    val mem = if (friends) ListMemory.friends else ListMemory.flights
    var past by mem.past
    var who by mem.who
    val names = Store.friends
    val all = Store.flights.filter { it.mine != friends }
        .filter { !friends || who == 0 || it.friend == names.getOrNull(who - 1) }
    val (up, done) = all.partition { it.phase(now) != Phase.Done }
    val shown = if (past == 0) up.sortedBy { it.depGuess } else done.sortedByDescending { it.depGuess }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refreshing by remember { mutableStateOf(false) }
    Page(if (friends) "Friends" else "Flights", state = mem.scroll, refreshing = refreshing, onRefresh = {
        refreshing = true
        App.scope.launch {
            // Pull-to-refresh forces a look only at flights within 3 days; far-out ones stay on their slow schedule.
            val soon = java.time.Instant.now().plus(java.time.Duration.ofDays(3))
            for (f in up) refreshAndSave(ctx, f, force = f.phase() != Phase.PreTravel || f.depGuess < soon, foreground = false)
            FlightWidget.refresh(ctx)
            refreshing = false
        }
    }, actions = {
        CircleButton(Icons.Filled.Add, "Add flight") { Nav.push(Screen.Add(friend = friends)) }
    }) {
        item("seg") {
            Segmented(listOf("Upcoming ${up.size}", "Past ${done.size}"), past) { past = it }
            Spacer(Modifier.height(8.dp))
        }
        if (friends && names.isNotEmpty()) item("who") {
            Pills(listOf("Everyone") + names, who) { who = it }
            Spacer(Modifier.height(4.dp))
        }
        if (shown.isEmpty()) item("empty") { EmptyState(friends, past == 1) }
        shown.groupBy { it.date }.forEach { (date, list) ->
            item("h$date") {
                Text(
                    dayLabel(date) + if (list.size > 1) " · ${list.size} flights" else "",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            items(list, key = { it.id }) { f ->
                FlightCard(f, now, showFriend = friends) { Nav.push(Screen.FlightDetail(f.id)) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(friends: Boolean, past: Boolean) = Column(
    Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Icon(Icons.Filled.Flight, null, tint = C.tertiary, modifier = Modifier.size(56.dp).rotate(45f))
    Spacer(Modifier.height(12.dp))
    Text(
        when {
            past -> "No past flights yet"
            friends -> "Track a friend's flight"
            else -> "No upcoming flights"
        },
        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
    )
    Text(
        when {
            past -> "Finished flights land here and count toward your passport."
            friends -> "Add their flight number to follow it live: progress, speed, and when they land."
            else -> "Search a flight number, or import a confirmation email, PDF or screenshot."
        },
        fontSize = 15.sp, color = C.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
    )
    if (!past) {
        Spacer(Modifier.height(20.dp))
        Button("Add a flight", icon = Icons.Filled.Add) { Nav.push(Screen.Add(friend = friends)) }
    }
}

fun toneColor(t: Tone) = when (t) {
    Tone.Good -> C.green
    Tone.Warn -> C.orange
    Tone.Bad -> C.red
    Tone.Info -> C.blue
    Tone.Neutral -> C.secondary
}

/** Airline emblem on its badge color if we have one, else a colored monogram. */
@Composable
fun AirlineBadge(code: String, size: Int = 28) {
    val icon = remember(code) { Guides.airlineIcon(code) }
    val bg = if (icon != null) Guides.airlineIconBg(code) else Guides.airlineColor(code)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, code, Modifier.fillMaxSize())
        else Text(code, color = Color.White, fontSize = (size * 0.42).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun FlightCard(f: Flight, now: Instant, showFriend: Boolean = false, onClick: () -> Unit) {
    val (status, tone) = f.status(now)
    val phase = f.phase(now)
    Group(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirlineBadge(f.airline)
                Spacer(Modifier.width(8.dp))
                Text(f.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (f.work && f.mine) {
                    Spacer(Modifier.width(8.dp))
                    Badge("Work", C.indigo)
                }
                if (showFriend) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Person, null, tint = C.secondary, modifier = Modifier.size(15.dp))
                    Text(f.friend, fontSize = 15.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                Badge(status, toneColor(tone))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Endpoint(f.from, f.origin?.city, f.dep, f.depSched, f.fromTz, f.depDelay, Alignment.Start, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Icon(Icons.Filled.Flight, null, tint = C.tertiary, modifier = Modifier.size(18.dp).rotate(90f))
                    Text(middleLabel(f, phase, now), fontSize = 13.sp, color = C.secondary, textAlign = TextAlign.Center)
                }
                Endpoint(f.to, f.dest?.city, f.arr, f.arrSched, f.toTz, f.arrDelay, Alignment.End, Modifier.weight(1f))
            }
            if (phase == Phase.InFlight) {
                Spacer(Modifier.height(12.dp))
                ProgressLine(f.progress(now))
                Text(
                    buildString {
                        append("${span(Duration.between(now, f.arr ?: f.arrGuess).let { if (it.isNegative) Duration.ZERO else it })} left")
                        f.live?.takeIf { f.isAirborne }?.let { append(" · ${(it.speedKt * 1.15078).roundToInt()} mph · ${fmtInt(it.altFt)} ft") }
                    },
                    fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun middleLabel(f: Flight, phase: Phase, now: Instant): String = when (phase) {
    Phase.PreTravel, Phase.Departure -> {
        val d = Duration.between(now, f.dep ?: f.depGuess)
        if (d.isNegative) "Boarding" else "in ${span(d)}"
    }
    else -> span(f.duration)
}

@Composable
private fun Endpoint(
    code: String, city: String?, best: Instant?, sched: Instant?, zone: java.time.ZoneId, delay: Long?,
    align: Alignment.Horizontal, modifier: Modifier,
) = Column(modifier, horizontalAlignment = align) {
    Text(code, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    Text(city ?: "", fontSize = 13.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val color = when {
        best == null -> C.secondary
        delay == null -> C.label
        delay >= 15 -> C.red
        else -> C.green
    }
    Text(time(best, zone), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color)
    if (sched != null && best != null && delay != null && kotlin.math.abs(delay) >= 5) {
        Text(time(sched, zone), fontSize = 13.sp, color = C.tertiary, textDecoration = TextDecoration.LineThrough)
    }
}

/** Route line with a plane riding along it. */
@Composable
fun ProgressLine(p: Double, height: Int = 4, color: Color = C.blue) {
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(22.dp).onSizeChanged { width = it.width }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height.dp).clip(CircleShape).background(C.fill))
        Box(Modifier.fillMaxWidth(p.toFloat()).height(height.dp).clip(CircleShape).background(color))
        val x = with(density) { (width * p).toFloat().toDp() } - 11.dp
        Icon(
            Icons.Filled.Flight, null, tint = color,
            modifier = Modifier.offset(x = x.coerceAtLeast(0.dp)).size(22.dp).rotate(90f),
        )
    }
}
