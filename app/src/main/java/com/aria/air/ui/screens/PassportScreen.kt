package com.aria.air.ui.screens

// The Passport tab: travel stats as a passport card and top lists.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.ui.components.*
import com.aria.air.ui.map.*
import com.aria.air.util.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.LocalAirport
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt

private const val EARTH_CIRCUMFERENCE_KM = 40_075.0
private const val MOON_KM = 384_400.0

/** Travel history as a passport card plus top lists. Counts my finished flights. */
@Composable
fun PassportScreen() {
    val done = Store.flights.filter { it.mine && !it.cancelled && it.phase() == Phase.Done }
    var kind by remember { mutableIntStateOf(0) } // all, personal, work
    val mine = when (kind) { 1 -> done.filter { !it.work }; 2 -> done.filter { it.work }; else -> done }
    val years = mine.map { it.date.year }.distinct().sortedDescending()
    var sel by remember { mutableIntStateOf(0) }
    val year = years.getOrNull(sel - 1)
    val list = if (year == null) mine else mine.filter { it.date.year == year }
    val s = remember(list) { stats(list) }
    val miles = s.km / KM_PER_MI

    Page("Passport") {
        if (done.any { it.work }) item("kind") {
            Segmented(listOf("All ${done.size}", "Personal ${done.count { !it.work }}", "Work ${done.count { it.work }}"), kind) { kind = it; sel = 0 }
            Spacer(Modifier.height(10.dp))
        }
        if (years.isNotEmpty()) item("years") {
            Pills(listOf("All time") + years.map { it.toString() }, sel) { sel = it }
            Spacer(Modifier.height(14.dp))
        }
        item("card") { PassportCard(list, s, year) }
        if (list.isEmpty()) {
            item("empty") { Footnote("Finished flights you add, search or import show up here: your map, miles and countries.") }
            return@Page
        }
        item("facts") {
            SectionHeader("That's")
            Group {
                Cell("Around the Earth", "%.1f×".format(s.km / EARTH_CIRCUMFERENCE_KM))
                Hairline()
                Cell("To the Moon", "${(s.km / MOON_KM * 100).roundToInt()}%")
                Hairline()
                Cell("Average flight", "${fmtInt((miles / s.flights).roundToInt())} mi · ${span(s.air.dividedBy(s.flights.toLong()))}")
                s.longest?.let {
                    Hairline()
                    Cell("Longest", "${it.from} → ${it.to} · ${fmtInt((it.distanceKm / KM_PER_MI).roundToInt())} mi")
                }
            }
        }
        item("review") { Review(s, list, mine, year) }
        item("airports") {
            SectionHeader("Top airports")
            Group {
                s.airports.entries.sortedByDescending { it.value }.take(6).forEachIndexed { i, (code, n) ->
                    if (i > 0) Hairline(50.dp)
                    val a = Db.airport(code)
                    Cell(
                        "$code · ${a?.city ?: ""}", subtitle = a?.name, value = "$n", icon = Icons.Filled.LocalAirport, chevron = true,
                    ) { Nav.push(Screen.AirportInfo(code)) }
                }
            }
        }
        item("airlines") {
            SectionHeader("Airlines")
            Group {
                s.airlines.entries.sortedByDescending { it.value }.take(6).forEachIndexed { i, (code, n) ->
                    if (i > 0) Hairline(56.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AirlineBadge(code)
                        Spacer(Modifier.width(12.dp))
                        Text(Db.airline(code)?.name ?: code, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("$n", fontSize = 17.sp, color = C.secondary)
                    }
                }
            }
        }
        item("routes") {
            SectionHeader("Top routes")
            Group {
                s.routes.entries.sortedByDescending { it.value }.take(5).forEachIndexed { i, (r, n) ->
                    if (i > 0) Hairline(50.dp)
                    Cell(r, value = "$n×", icon = Icons.Filled.FlightTakeoff)
                }
            }
        }
        if (s.aircraft.isNotEmpty()) item("aircraft") {
            SectionHeader("Aircraft")
            Group {
                s.aircraft.entries.sortedByDescending { it.value }.take(5).forEachIndexed { i, (type, n) ->
                    if (i > 0) Hairline(50.dp)
                    Cell(type, value = "$n×", icon = Icons.Filled.AirplanemodeActive)
                }
            }
        }
        item("countries") {
            SectionHeader("Countries")
            Group {
                s.countries.sorted().forEachIndexed { i, iso ->
                    if (i > 0) Hairline()
                    Cell("${flag(iso)}  ${countryName(iso)}")
                }
            }
        }
    }
}

/**
 * The year in review (or all-time highlights): estimated CO₂, punctuality, and for a year its busiest month, airports
 * visited for the first time and how it compares with the year before.
 */
@Composable
private fun Review(s: Stats, list: List<Flight>, all: List<Flight>, year: Int?) {
    val rows = buildList<Triple<String, String, String?>> {
        val t = s.co2Kg / 1000
        add(Triple("CO₂ (estimate)", if (t >= 1) "≈ %.1f t".format(t) else "≈ ${(s.co2Kg / 10).roundToInt() * 10} kg", "Rough, per economy seat"))
        s.averageDelay?.let { avg ->
            add(Triple("On time", "${s.onTime * 100 / s.timed}%", "${s.onTime} of ${s.timed} landed within 15 min" + if (avg > 0) " · avg ${span(java.time.Duration.ofMinutes(avg))} late" else ""))
        }
        s.aircraft.maxByOrNull { it.value }?.let { (type, n) -> add(Triple("Most flown aircraft", type, "$n flights")) }
        if (year != null) {
            s.months.maxByOrNull { it.value }?.takeIf { it.value > 1 }?.let { (m, n) ->
                add(Triple("Busiest month", m.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), "$n flights"))
            }
            firstVisits(list, all.filter { it.date.year < year }).takeIf { it.isNotEmpty() }?.let { new ->
                add(Triple("New airports", "${new.size}", new.take(8).joinToString(", ") + if (new.size > 8) "…" else ""))
            }
            all.filter { it.date.year == year - 1 }.takeIf { it.isNotEmpty() }?.let { prev ->
                val df = s.flights - prev.size
                val dm = ((s.km - prev.sumOf { it.distanceKm }) / KM_PER_MI).roundToInt()
                add(Triple("vs. ${year - 1}", "${if (df >= 0) "+" else ""}$df flight${if (kotlin.math.abs(df) == 1) "" else "s"}", "${if (dm >= 0) "+" else "−"}${fmtInt(kotlin.math.abs(dm))} mi"))
            }
        }
    }
    SectionHeader(if (year != null) "$year in review" else "Highlights")
    Group {
        rows.forEachIndexed { i, (title, value, sub) ->
            if (i > 0) Hairline()
            Cell(title, value, subtitle = sub)
        }
    }
}

/**
 * The passport card: name, year, a map of the routes and the big totals, with a passport-style code line at the bottom.
 */
@Composable
private fun PassportCard(list: List<Flight>, s: Stats, year: Int?) {
    val routes = remember(list) {
        list.mapNotNull { f -> f.origin?.let { o -> f.dest?.let { d -> MapRoute(o, d) } } }.distinctBy { listOf(it.from.iata, it.to.iata).sorted() }
    }
    val ink = Color.White
    val soft = Color.White.copy(alpha = 0.7f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(lerp(C.mine, Color.Black, 0.6f), lerp(C.mine, Color.Black, 0.25f), C.mine)))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AIR PASSPORT", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(year?.toString() ?: "ALL TIME", color = soft, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Text(Prefs.name.ifEmpty { "Traveler" }, color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        WorldMap(
            routes, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(12.dp)),
            land = Color.White.copy(alpha = 0.22f), sea = Color.Transparent, line = lerp(C.mine, Color.White, 0.65f), dot = Color.White,
        )
        Spacer(Modifier.height(16.dp))
        Row {
            BigStat("${s.flights}", "Flights", Modifier.weight(1f))
            BigStat(fmtInt((s.km / KM_PER_MI).roundToInt()), "Miles", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            val h = s.air.toHours()
            BigStat(if (h >= 48) "${h / 24}d ${h % 24}h" else "${h}h", "In the air", Modifier.weight(1f))
            BigStat("${s.airports.size}", "Airports", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BigStat("${s.airlines.size}", "Airlines", Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Text(s.countries.sorted().joinToString("") { flag(it) }.ifEmpty { "—" }, fontSize = 20.sp, maxLines = 2)
                Text("${s.countries.size} COUNTRIES", color = soft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "P<AIR<<${Prefs.name.uppercase().replace(' ', '<').ifEmpty { "TRAVELER" }}<<<${s.flights}F<${(s.km / KM_PER_MI).roundToInt()}MI<<<<<<<<<<<<",
            color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        )
    }
    if (list.isNotEmpty()) Footnote("Since ${list.minOf { it.date }.let { if (it.year == LocalDate.now().year) dayLabel(it) else it.year.toString() }}")
}

/** A big number with a small label, for the passport card. */
@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) = Column(modifier) {
    Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text(label.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}
