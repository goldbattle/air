package com.aria.air.ui.screens

// One flight's page: map header, phases, and the actions on it.

import com.aria.air.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.sync.*
import com.aria.air.ui.components.*
import com.aria.air.ui.map.*
import com.aria.air.util.*
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Refreshes the flight in the app scope (leaving the page doesn't cancel it), then its past routes; [done] runs once
 * the status part is saved.
 */
private fun refreshNow(ctx: android.content.Context, id: String, force: Boolean = false, done: () -> Unit = {}) = App.scope.launch {
    Store.get(id)?.let { refreshAndSave(ctx, it, force) }
    done()
    Store.get(id)?.let { PastRoutes.update(it, force) } // recent paths of this flight number (PastRoutes)
}

/**
 * A flight's page: the route map with the title, the legs of a connection, the phase panels, your plane, the schedule,
 * details and actions. Refreshes on opening, and every minute while the flight is near or in the air.
 */
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
    // Only a pull shows the spinner; the automatic refreshes while the page is open run quietly.
    var refreshing by remember { mutableStateOf(false) }
    var pulls by remember { mutableIntStateOf(0) } // each pull also fetches the weather map and past routes
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Refresh on open, then every minute while the flight is around the airport or in the air.
    LaunchedEffect(id) {
        while (true) {
            val cur = Store.get(id) ?: break
            if (cur.phase() == Phase.Done && !cur.maybeLive && cur.updated != null) break
            refreshNow(ctx, id).join()
            if (Store.get(id)?.let { it.phase() in Phase.Departure..Phase.Arrival || it.maybeLive } != true) break
            delay(60_000)
        }
    }

    // Past routes and route weather when their layers are on: on opening, and as soon as one is switched on.
    val routesOn = Layers.routes; val weatherOn = Layers.weather
    LaunchedEffect(id, routesOn) { Store.get(id)?.let { PastRoutes.update(it) } }
    LaunchedEffect(id, weatherOn) {
        if (!weatherOn) return@LaunchedEffect
        Store.get(id)?.let { cur -> WeatherSync.refreshRoute(cur).routeWx?.let { w -> Store.get(id)?.let { Store.upsert(it.copy(routeWx = w)) } } }
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Once the map scrolls away, the floating buttons get a solid bar with the flight number, like iOS.
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            refreshing, { refreshing = true; pulls++; refreshNow(ctx, id, force = true) { refreshing = false } }, Modifier.fillMaxSize(),
        ) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item("map") { MapHeader(f, pulls) }
            item("body") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    val legs = f.connection()
                    if (legs.size > 1) {
                        LegBar(f, legs)
                        Spacer(Modifier.height(12.dp))
                    }
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
            if (Debug.on) {
                Spacer(Modifier.width(8.dp))
                FloatingButton(Icons.Filled.BugReport, "Copy debug info") { Debug.copy(ctx, Debug.flightReport(f), "Flight debug info") }
            }
            Text(
                if (solid) "${f.label} · ${f.from} → ${f.to}" else "", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            FloatingButton(Icons.Filled.IosShare, "Share") { share(ctx, f) }
        }
    }
    EditDialog(edit) { edit = null }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${f.label}?") },
        text = { Text("It's removed from your ${if (f.mine) "flights and passport" else "friends' flights"}. This can't be undone.") },
        confirmButton = {
            TextButton({ confirmDelete = false; Nav.pop(); Store.remove(f.id); Tracker.schedule(ctx) }) { Text("Delete", color = C.red) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** A round button floating over the map (back, debug, share). */
@Composable
private fun FloatingButton(icon: ImageVector, description: String, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(C.card.copy(alpha = 0.92f)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, description, tint = C.label, modifier = Modifier.size(22.dp)) }

/** The legs of a connection, this one highlighted; tap another to switch to it without going back to the list. */
@Composable
private fun LegBar(f: Flight, legs: List<Flight>) = Group {
    val i = legs.indexOfFirst { it.id == f.id }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ConnectingAirports, null, tint = C.blue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Leg ${i + 1} of ${legs.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        legs.getOrNull(i + 1)?.let { Text(layoverText(f, it), fontSize = 13.sp, color = C.secondary) }
    }
    Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        legs.forEach { leg ->
            val on = leg.id == f.id
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) C.blue else C.fill)
                    .clickable(enabled = !on) { Nav.replace(Screen.FlightDetail(leg.id)) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${leg.from} → ${leg.to}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else C.label, maxLines = 1)
                Text(leg.label, fontSize = 12.sp, color = if (on) Color.White.copy(alpha = 0.85f) else C.secondary, maxLines = 1)
            }
        }
    }
}

/** The map behind the title, with the radar and route weather layers. Tapping it opens the full-screen map. */
@Composable
private fun MapHeader(f: Flight, pulls: Int) {
    val o = f.origin; val d = f.dest
    var full by remember { mutableStateOf(false) } // full-screen map (FullMap.kt)
    if (full) FullMap(f, pulls) { full = false }
    // The map runs behind the title; the route sits in the upper part and a gradient keeps the title readable.
    Box(Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { full = true }) {
        if (o != null && d != null) {
            val radar = rememberWeatherMap(f, pulls) // live radar, or the rain during the flight (MapLayers.kt)
            val wx = f.routeWx?.takeIf { Layers.weather }
            WorldMap(
                listOf(MapRoute(o, d, highlight = true)), Modifier.matchParentSize(), focus = true, centerY = 0.36f,
                plane = f.live?.takeIf { f.isAirborne }, paths = f.mapPaths(),
                under = radar?.let { r -> { x, y -> drawWeatherMap(r, x, y, 0.5f) } }, // radar layer
                over = wx?.let { w -> { x, y -> drawRouteWx(w, o.lon, x, y) } }, // route weather layer (MapLayers.kt)
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

/** Airline badge, flight number and status, the two cities and the date. */
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

/** Move between my flights and friends', and delete. */
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

/** Where the page's data came from and how fresh it is, for the footnote. */
private fun sources(f: Flight) = buildList {
    f.updated?.let { add("Status updated ${ago(it)}") }
    f.live?.let { add("Live position from adsb.lol ${ago(it.at)}") }
    add("Added via ${f.source}")
}.joinToString(" · ")

/** Shares the flight as a short text: route, date, times and status. */
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
